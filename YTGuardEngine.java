package com.rohit.ytguard;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Locale;

public class YTGuardEngine implements Application.ActivityLifecycleCallbacks {

    private static YTGuardEngine instance;
    private static final String PREFS_NAME = "ytguard_internal_prefs";
    private static final String KEY_CONFIG = "config_json";
    private static final String TAG_BUTTON = "ytguard_inapp_btn";
    private static final String TAG_SHIELD = "ytguard_inapp_shield";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private WeakReference currentActivityRef;
    private boolean isLoopRunning = false;
    private long screenOffTime = 0L;
    private long unverifiedWatchStart = 0L;
    private long lastBlockActionTime = 0L;

    public static void initFromContext(Context context) {
        if (context == null) return;
        try {
            if (instance == null) {
                Context appCtx = context.getApplicationContext();
                if (appCtx instanceof Application) {
                    Application app = (Application) appCtx;
                    instance = new YTGuardEngine(app);
                    app.registerActivityLifecycleCallbacks(instance);
                }
            }
            if (instance != null && context instanceof Activity) {
                instance.attachToActivity((Activity) context);
            }
        } catch (Throwable ignored) {
        }
    }

    private YTGuardEngine(final Application app) {
        ensureDefaultConfig(app);
        try {
            IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            app.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent == null) return;
                    String action = intent.getAction();
                    if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                        screenOffTime = System.currentTimeMillis();
                    } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                        if (screenOffTime > 0L && (System.currentTimeMillis() - screenOffTime >= 5 * 60 * 1000L)) {
                            lockFamilyMode(app);
                        }
                        screenOffTime = 0L;
                    }
                }
            }, filter);
        } catch (Throwable ignored) {
        }
    }

    private void startMonitorLoop() {
        if (isLoopRunning) return;
        isLoopRunning = true;
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    inspectActiveActivity();
                } catch (Throwable ignored) {
                }
                if (isLoopRunning) {
                    handler.postDelayed(this, 280L);
                }
            }
        });
    }

    private void attachToActivity(final Activity activity) {
        if (activity == null) return;
        currentActivityRef = new WeakReference(activity);
        startMonitorLoop();

        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                injectGuardButton(activity);
            }
        }, 400L);
    }

    private void injectGuardButton(final Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            if (!(decor instanceof FrameLayout)) return;
            FrameLayout root = (FrameLayout) decor;

            View existing = root.findViewWithTag(TAG_BUTTON);
            TextView pill;
            if (existing instanceof TextView) {
                pill = (TextView) existing;
            } else {
                pill = new TextView(activity);
                pill.setTag(TAG_BUTTON);
                pill.setTextColor(Color.parseColor("#F8FAFC"));
                pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                pill.setTypeface(Typeface.DEFAULT_BOLD);
                pill.setPadding(dp(activity, 10), dp(activity, 5), dp(activity, 10), dp(activity, 5));

                GradientDrawable bg = new GradientDrawable();
                bg.setColor(Color.parseColor("#E60F172A"));
                bg.setCornerRadius(dp(activity, 14));
                bg.setStroke(dp(activity, 1), Color.parseColor("#475569"));
                pill.setBackground(bg);

                FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                );
                lp.gravity = Gravity.TOP | Gravity.START;
                lp.topMargin = dp(activity, 42);
                lp.leftMargin = dp(activity, 120);

                pill.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showMainControlDialog(activity);
                    }
                });

                root.addView(pill, lp);
            }

            JSONObject cfg = loadConfig(activity);
            boolean family = isFamilyMode(cfg);
            pill.setText(family ? "GUARD • FAMILY" : "GUARD • STRICT");
            pill.bringToFront();
        } catch (Throwable ignored) {
        }
    }

    private void inspectActiveActivity() {
        if (currentActivityRef == null) return;
        final Activity activity = (Activity) currentActivityRef.get();
        if (activity == null || activity.isFinishing()) return;

        injectGuardButton(activity);

        JSONObject cfg = loadConfig(activity);
        long now = System.currentTimeMillis();

        if (isFamilyMode(cfg)) {
            unverifiedWatchStart = 0L;
            hideLockShield(activity);
            return;
        }

        View decor = activity.getWindow().getDecorView();
        ArrayList texts = new ArrayList();
        ArrayList ids = new ArrayList();
        boolean[] shortsDetected = new boolean[]{false};

        boolean shortsLocked = cfg.optBoolean("enforceShortsBlock", true)
                && (now >= cfg.optLong("shortsUnlockExpiry", 0L));

        scanViewTree(decor, texts, ids, shortsLocked, shortsDetected, activity);

        // 1. Block Shorts Inside YouTube
        if (shortsLocked && shortsDetected[0]) {
            pauseMediaPlayback(activity);
            if (now - lastBlockActionTime > 700L) {
                lastBlockActionTime = now;
                activity.onBackPressed();
                Toast.makeText(activity, "YT Guard: Shorts is locked (Password #2)", Toast.LENGTH_SHORT).show();
            }
            return;
        }

        // 2. Check Active Video Watch Player
        boolean isWatchPlayerOpen = false;
        for (int i = 0; i < ids.size(); i++) {
            String id = (String) ids.get(i);
            if (id.contains("watch_next") || id.contains("player_view")
                    || id.contains("watch_while") || id.contains("video_metadata")) {
                isWatchPlayerOpen = true;
                break;
            }
        }
        if (!isWatchPlayerOpen) {
            for (int i = 0; i < texts.size(); i++) {
                String t = ((String) texts.get(i)).toLowerCase(Locale.ROOT);
                if (t.equals("subscribe") || t.equals("subscribed")) {
                    isWatchPlayerOpen = true;
                    break;
                }
            }
        }

        if (!isWatchPlayerOpen) {
            unverifiedWatchStart = 0L;
            hideLockShield(activity);
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < texts.size(); i++) {
            sb.append(((String) texts.get(i)).toLowerCase(Locale.ROOT)).append(" | ");
        }
        String screenText = sb.toString();

        // 2A. Title Keyword Blacklist
        if (cfg.optBoolean("enforceTitleBlacklist", true)) {
            JSONArray blockedArr = cfg.optJSONArray("blockedTitleKeywords");
            if (blockedArr != null) {
                for (int i = 0; i < blockedArr.length(); i++) {
                    String word = blockedArr.optString(i, "").trim().toLowerCase(Locale.ROOT);
                    if (!word.isEmpty() && screenText.contains(word)) {
                        unverifiedWatchStart = 0L;
                        enforceInAppVideoBlock(activity, "Blocked Title Keyword: \"" + word + "\"");
                        return;
                    }
                }
            }
        }

        // 2B. 15-Minute Emergency Single-Video Pass
        if (now < cfg.optLong("emergencyPassExpiry", 0L)) {
            unverifiedWatchStart = 0L;
            hideLockShield(activity);
            return;
        }

        // 2C. Channel Whitelist & Interest Filter
        boolean enforceWhitelist = cfg.optBoolean("enforceWhitelist", true);
        boolean enforceInterests = cfg.optBoolean("enforceInterestFilter", false);

        if (!enforceWhitelist && !enforceInterests) {
            unverifiedWatchStart = 0L;
            hideLockShield(activity);
            return;
        }

        JSONObject activeGroup = getActiveGroup(cfg);
        if (activeGroup == null) return;

        boolean channelOk = !enforceWhitelist;
        if (enforceWhitelist) {
            JSONArray chArr = activeGroup.optJSONArray("channels");
            if (chArr != null) {
                for (int i = 0; i < chArr.length(); i++) {
                    String ch = chArr.optString(i, "").trim().toLowerCase(Locale.ROOT);
                    if (!ch.isEmpty() && screenText.contains(ch)) {
                        channelOk = true;
                        break;
                    }
                }
            }
        }

        boolean interestOk = !enforceInterests;
        if (enforceInterests) {
            JSONArray intArr = activeGroup.optJSONArray("interests");
            if (intArr != null) {
                for (int i = 0; i < intArr.length(); i++) {
                    String kw = intArr.optString(i, "").trim().toLowerCase(Locale.ROOT);
                    if (!kw.isEmpty() && screenText.contains(kw)) {
                        interestOk = true;
                        break;
                    }
                }
            }
        }

        if (channelOk && interestOk) {
            unverifiedWatchStart = 0L;
            hideLockShield(activity);
        } else {
            if (unverifiedWatchStart == 0L) {
                unverifiedWatchStart = now;
            } else if (now - unverifiedWatchStart >= 1100L) {
                String gName = activeGroup.optString("name", "Active Group");
                String reason = !channelOk
                        ? "Channel is not whitelisted in [" + gName + "]"
                        : "Video topic does not match [" + gName + "] interests";
                enforceInAppVideoBlock(activity, reason);
            }
        }
    }

    private void scanViewTree(
            View view,
            ArrayList texts,
            ArrayList ids,
            final boolean shortsLocked,
            boolean[] shortsDetected,
            final Activity activity
    ) {
        if (view == null || view.getVisibility() != View.VISIBLE) return;
        if (TAG_SHIELD.equals(view.getTag()) || TAG_BUTTON.equals(view.getTag())) return;

        String idName = "";
        if (view.getId() != View.NO_ID) {
            try {
                idName = view.getResources().getResourceEntryName(view.getId()).toLowerCase(Locale.ROOT);
                ids.add(idName);
            } catch (Throwable ignored) {
            }
        }

        CharSequence descObj = view.getContentDescription();
        String desc = descObj != null ? descObj.toString() : "";
        if (!desc.isEmpty()) {
            texts.add(desc);
        }

        if (view instanceof TextView) {
            CharSequence tObj = ((TextView) view).getText();
            String t = tObj != null ? tObj.toString() : "";
            if (!t.isEmpty()) {
                texts.add(t);
            }
        }

        if (shortsLocked) {
            if (idName.contains("reel_shelf") || idName.contains("shorts_shelf") || idName.contains("reel_item")) {
                view.setVisibility(View.GONE);
                return;
            }
            if (desc.equalsIgnoreCase("Shorts")) {
                if (view.isSelected()) {
                    shortsDetected[0] = true;
                }
                view.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        Toast.makeText(activity, "YT Guard: Shorts is locked (Requires Password #2)", Toast.LENGTH_SHORT).show();
                    }
                });
            }
            if (idName.contains("reel_watch") || idName.contains("reel_player")
                    || idName.contains("shorts_player") || idName.contains("reel_recycler")) {
                shortsDetected[0] = true;
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) view;
            int count = vg.getChildCount();
            for (int i = 0; i < count; i++) {
                scanViewTree(vg.getChildAt(i), texts, ids, shortsLocked, shortsDetected, activity);
            }
        }
    }

    private void enforceInAppVideoBlock(final Activity activity, String reason) {
        pauseMediaPlayback(activity);
        showLockShield(activity, reason);
    }

    private void showLockShield(final Activity activity, String reason) {
        try {
            View decor = activity.getWindow().getDecorView();
            if (!(decor instanceof FrameLayout)) return;
            final FrameLayout root = (FrameLayout) decor;

            View existing = root.findViewWithTag(TAG_SHIELD);
            if (existing != null) {
                TextView reasonTv = (TextView) existing.findViewWithTag("shield_reason");
                if (reasonTv != null) reasonTv.setText(reason);
                existing.bringToFront();
                return;
            }

            final LinearLayout shield = new LinearLayout(activity);
            shield.setTag(TAG_SHIELD);
            shield.setOrientation(LinearLayout.VERTICAL);
            shield.setGravity(Gravity.CENTER);
            shield.setBackgroundColor(Color.parseColor("#F80F172A"));
            shield.setClickable(true);
            shield.setFocusable(true);
            shield.setPadding(dp(activity, 28), dp(activity, 28), dp(activity, 28), dp(activity, 28));

            TextView badge = new TextView(activity);
            badge.setText("YT GUARD • STRICT LOCK");
            badge.setTextColor(Color.parseColor("#94A3B8"));
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            shield.addView(badge);

            TextView title = new TextView(activity);
            title.setText("Playback Restricted");
            title.setTextColor(Color.WHITE);
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setPadding(0, dp(activity, 8), 0, dp(activity, 8));
            shield.addView(title);

            TextView reasonTv = new TextView(activity);
            reasonTv.setTag("shield_reason");
            reasonTv.setText(reason);
            reasonTv.setTextColor(Color.parseColor("#CBD5E1"));
            reasonTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            reasonTv.setGravity(Gravity.CENTER);
            reasonTv.setPadding(0, 0, 0, dp(activity, 24));
            shield.addView(reasonTv);

            Button backBtn = new Button(activity);
            backBtn.setText("Close Blocked Video");
            backBtn.setAllCaps(false);
            backBtn.setTextColor(Color.parseColor("#0F172A"));
            backBtn.setBackgroundColor(Color.WHITE);
            backBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    unverifiedWatchStart = 0L;
                    hideLockShield(activity);
                    pauseMediaPlayback(activity);
                    activity.onBackPressed();
                }
            });
            shield.addView(backBtn);

            Button panelBtn = new Button(activity);
            panelBtn.setText("Open YT Guard Controls");
            panelBtn.setAllCaps(false);
            panelBtn.setTextColor(Color.WHITE);
            panelBtn.setBackgroundColor(Color.TRANSPARENT);
            panelBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    showMainControlDialog(activity);
                }
            });
            shield.addView(panelBtn);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
            );
            root.addView(shield, lp);
            shield.bringToFront();
        } catch (Throwable ignored) {
        }
    }

    private void hideLockShield(Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            if (!(decor instanceof FrameLayout)) return;
            FrameLayout root = (FrameLayout) decor;
            View existing = root.findViewWithTag(TAG_SHIELD);
            if (existing != null) {
                root.removeView(existing);
            }
        } catch (Throwable ignored) {
        }
    }

    private void pauseMediaPlayback(Context context) {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE));
            am.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE));
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                AudioFocusRequest req = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                        .setAudioAttributes(new AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                .build())
                        .setOnAudioFocusChangeListener(new AudioManager.OnAudioFocusChangeListener() {
                            @Override
                            public void onAudioFocusChange(int focusChange) {}
                        })
                        .build();
                am.requestAudioFocus(req);
            }
        } catch (Throwable ignored) {
        }
    }

    private void showMainControlDialog(final Activity activity) {
        final JSONObject cfg = loadConfig(activity);
        final long now = System.currentTimeMillis();
        final boolean familyActive = isFamilyMode(cfg);
        final boolean shortsUnlocked = now < cfg.optLong("shortsUnlockExpiry", 0L);
        final boolean passActive = now < cfg.optLong("emergencyPassExpiry", 0L);
        final JSONObject activeGroup = getActiveGroup(cfg);

        ScrollView scroll = new ScrollView(activity);
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(activity, 20), dp(activity, 16), dp(activity, 20), dp(activity, 12));
        scroll.addView(container);

        addSectionTitle(activity, container, "1. Family Mode (Unrestricted YouTube)");
        addSubtitle(activity, container, familyActive
                ? "Status: UNLOCKED (" + formatRem(cfg.optLong("familyModeExpiry", 0L)) + " left)"
                : "Status: LOCKED (Auto-locks after timer or 5m screen off)");

        Button famBtn = createActionButton(activity, familyActive
                ? "Lock Into Strict Study Mode Now (No Password)"
                : "Unlock Family Mode (Password #1)");
        container.addView(famBtn);

        addSectionTitle(activity, container, "2. YouTube Shorts Blocker");
        addSubtitle(activity, container, shortsUnlocked
                ? "Status: UNLOCKED (" + formatRem(cfg.optLong("shortsUnlockExpiry", 0L)) + " left)"
                : "Status: BLOCKED EVERYWHERE");

        Button shortsBtn = createActionButton(activity, shortsUnlocked
                ? "Lock Shorts Immediately (No Password)"
                : "Temporarily Unlock Shorts (Password #2)");
        container.addView(shortsBtn);

        String gName = activeGroup != null ? activeGroup.optString("name", "Study") : "Study";
        addSectionTitle(activity, container, "3. Active Group: " + gName);
        Button switchGroupBtn = createActionButton(activity, "Switch Active Group (Password #4)");
        Button manageChannelsBtn = createActionButton(activity, "Edit Whitelisted Channels & Groups (Password #3)");
        Button manageInterestsBtn = createActionButton(activity, "Edit Interest Topics & Keyword Blacklist");
        container.addView(switchGroupBtn);
        container.addView(manageChannelsBtn);
        container.addView(manageInterestsBtn);

        addSectionTitle(activity, container, "4. Emergency 15-Min Video Pass");
        Button passBtn = createActionButton(activity, passActive
                ? "Lock 15m Pass Now (" + formatRem(cfg.optLong("emergencyPassExpiry", 0L)) + ")"
                : "Activate 15-Min Single-Video Pass (Password #5)");
        container.addView(passBtn);

        addSectionTitle(activity, container, "5. Independent 5-Password Matrix");
        Button pwdBtn = createActionButton(activity, "Change Slot Passwords (#1 to #5)");
        container.addView(pwdBtn);

        final AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("YT GUARD • IN-APP CONTROLLER")
                .setView(scroll)
                .setNegativeButton("Close", null)
                .create();

        famBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                if (familyActive) {
                    try {
                        cfg.put("familyModeExpiry", 0L);
                        saveConfig(activity, cfg);
                    } catch (Throwable ignored) {}
                } else {
                    promptPasswordAndDuration(activity, cfg, "family", "Unlock Family Mode (Password #1)",
                            new int[]{15, 30, 60, 120, 180}, "familyModeExpiry");
                }
            }
        });

        shortsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                if (shortsUnlocked) {
                    try {
                        cfg.put("shortsUnlockExpiry", 0L);
                        saveConfig(activity, cfg);
                    } catch (Throwable ignored) {}
                } else {
                    promptPasswordAndDuration(activity, cfg, "shorts", "Unlock Shorts (Password #2)",
                            new int[]{10, 20, 30, 60}, "shortsUnlockExpiry");
                }
            }
        });

        switchGroupBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                verifyPassword(activity, cfg, "switchGroup", "Switch Group (Password #4)", new Runnable() {
                    @Override
                    public void run() {
                        showGroupSwitchDialog(activity, cfg);
                    }
                });
            }
        });

        manageChannelsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                verifyPassword(activity, cfg, "admin", "Group & Whitelist Admin (Password #3)", new Runnable() {
                    @Override
                    public void run() {
                        showGroupChannelEditorDialog(activity, cfg);
                    }
                });
            }
        });

        manageInterestsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                showKeywordsAndInterestsMenu(activity, cfg);
            }
        });

        passBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                if (passActive) {
                    try {
                        cfg.put("emergencyPassExpiry", 0L);
                        saveConfig(activity, cfg);
                    } catch (Throwable ignored) {}
                } else {
                    verifyPassword(activity, cfg, "interest", "Activate 15m Video Pass (Password #5)", new Runnable() {
                        @Override
                        public void run() {
                            try {
                                cfg.put("emergencyPassExpiry", System.currentTimeMillis() + (15 * 60 * 1000L));
                                saveConfig(activity, cfg);
                                hideLockShield(activity);
                                Toast.makeText(activity, "15-Minute Video Pass Activated", Toast.LENGTH_SHORT).show();
                            } catch (Throwable ignored) {}
                        }
                    });
                }
            }
        });

        pwdBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dialog.dismiss();
                showPasswordMatrixDialog(activity, cfg);
            }
        });

        dialog.show();
    }

    private void promptPasswordAndDuration(
            final Activity activity,
            final JSONObject cfg,
            String slotKey,
            String title,
            final int[] minsOptions,
            final String expiryKey
    ) {
        verifyPassword(activity, cfg, slotKey, title, new Runnable() {
            @Override
            public void run() {
                String[] labels = new String[minsOptions.length];
                for (int i = 0; i < minsOptions.length; i++) {
                    labels[i] = minsOptions[i] + " Minutes";
                }
                new AlertDialog.Builder(activity)
                        .setTitle("Select Duration")
                        .setItems(labels, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface d, int which) {
                                try {
                                    long exp = System.currentTimeMillis() + (minsOptions[which] * 60 * 1000L);
                                    cfg.put(expiryKey, exp);
                                    saveConfig(activity, cfg);
                                    hideLockShield(activity);
                                } catch (Throwable ignored) {}
                            }
                        })
                        .show();
            }
        });
    }

    private void verifyPassword(
            final Activity activity,
            final JSONObject cfg,
            final String slotKey,
            String title,
            final Runnable onVerified
    ) {
        final EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        input.setHint("Enter Password");

        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setView(input)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Verify", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        JSONObject pwds = cfg.optJSONObject("passwords");
                        String expected = pwds != null ? pwds.optString(slotKey, "") : "";
                        if (input.getText().toString().trim().equals(expected)) {
                            onVerified.run();
                        } else {
                            Toast.makeText(activity, "Incorrect password for this security slot", Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    private void showGroupSwitchDialog(final Activity activity, final JSONObject cfg) {
        final JSONArray groups = cfg.optJSONArray("groups");
        if (groups == null || groups.length() == 0) return;
        String[] names = new String[groups.length()];
        for (int i = 0; i < groups.length(); i++) {
            JSONObject g = groups.optJSONObject(i);
            names[i] = g != null ? g.optString("name", "Group") : "Group";
        }
        new AlertDialog.Builder(activity)
                .setTitle("Select Active Focus Group")
                .setItems(names, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        try {
                            JSONObject g = groups.optJSONObject(which);
                            if (g != null) {
                                cfg.put("activeGroupId", g.optString("id"));
                                saveConfig(activity, cfg);
                                Toast.makeText(activity, "Switched to " + g.optString("name"), Toast.LENGTH_SHORT).show();
                            }
                        } catch (Throwable ignored) {}
                    }
                })
                .show();
    }

    private void showGroupChannelEditorDialog(final Activity activity, final JSONObject cfg) {
        final JSONObject activeGroup = getActiveGroup(cfg);
        if (activeGroup == null) return;
        final JSONArray chArr = activeGroup.optJSONArray("channels");

        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 12));

        final EditText newChannelInput = new EditText(activity);
        newChannelInput.setHint("Enter Channel Name or New Group Name");
        layout.addView(newChannelInput);

        StringBuilder currentList = new StringBuilder("Whitelisted Channels:\n");
        if (chArr != null) {
            for (int i = 0; i < chArr.length(); i++) {
                currentList.append("• ").append(chArr.optString(i)).append("\n");
            }
        }
        TextView listTv = new TextView(activity);
        listTv.setText(currentList.toString());
        listTv.setPadding(0, dp(activity, 10), 0, 0);
        layout.addView(listTv);

        new AlertDialog.Builder(activity)
                .setTitle("Manage Group: " + activeGroup.optString("name"))
                .setView(layout)
                .setPositiveButton("Add Channel", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String val = newChannelInput.getText().toString().trim();
                        if (!val.isEmpty() && chArr != null) {
                            chArr.put(val);
                            saveConfig(activity, cfg);
                            Toast.makeText(activity, "Added channel: " + val, Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .setNeutralButton("Create New Group", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String gName = newChannelInput.getText().toString().trim();
                        if (!gName.isEmpty()) {
                            try {
                                JSONArray groups = cfg.optJSONArray("groups");
                                JSONObject newG = new JSONObject();
                                String id = "g_" + System.currentTimeMillis();
                                newG.put("id", id);
                                newG.put("name", gName);
                                newG.put("channels", new JSONArray());
                                newG.put("interests", new JSONArray());
                                groups.put(newG);
                                cfg.put("activeGroupId", id);
                                saveConfig(activity, cfg);
                                Toast.makeText(activity, "Created group: " + gName, Toast.LENGTH_SHORT).show();
                            } catch (Throwable ignored) {}
                        }
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showKeywordsAndInterestsMenu(final Activity activity, final JSONObject cfg) {
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 12));

        final EditText wordInput = new EditText(activity);
        wordInput.setHint("Enter keyword / topic");
        layout.addView(wordInput);

        CheckBox interestToggle = new CheckBox(activity);
        interestToggle.setText("Enforce Interest Topic Filter");
        interestToggle.setChecked(cfg.optBoolean("enforceInterestFilter", false));
        interestToggle.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (isChecked) {
                    try {
                        cfg.put("enforceInterestFilter", true);
                        saveConfig(activity, cfg);
                    } catch (Throwable ignored) {}
                } else {
                    verifyPassword(activity, cfg, "interest", "Disable Interest Filter (Password #5)", new Runnable() {
                        @Override
                        public void run() {
                            try {
                                cfg.put("enforceInterestFilter", false);
                                saveConfig(activity, cfg);
                            } catch (Throwable ignored) {}
                        }
                    });
                }
            }
        });
        layout.addView(interestToggle);

        new AlertDialog.Builder(activity)
                .setTitle("Blocked Words & Interest Topics")
                .setView(layout)
                .setPositiveButton("Add Blocked Word (No Pwd)", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        String w = wordInput.getText().toString().trim().toLowerCase(Locale.ROOT);
                        if (!w.isEmpty()) {
                            JSONArray arr = cfg.optJSONArray("blockedTitleKeywords");
                            if (arr != null) {
                                arr.put(w);
                                saveConfig(activity, cfg);
                                Toast.makeText(activity, "Blocked word added: " + w, Toast.LENGTH_SHORT).show();
                            }
                        }
                    }
                })
                .setNeutralButton("Add Interest Topic (Pwd #5)", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        final String kw = wordInput.getText().toString().trim();
                        if (!kw.isEmpty()) {
                            verifyPassword(activity, cfg, "interest", "Add Interest (Password #5)", new Runnable() {
                                @Override
                                public void run() {
                                    JSONObject g = getActiveGroup(cfg);
                                    if (g != null) {
                                        JSONArray arr = g.optJSONArray("interests");
                                        if (arr != null) {
                                            arr.put(kw);
                                            saveConfig(activity, cfg);
                                            Toast.makeText(activity, "Interest added: " + kw, Toast.LENGTH_SHORT).show();
                                        }
                                    }
                                }
                            });
                        }
                    }
                })
                .setNegativeButton("Close", null)
                .show();
    }

    private void showPasswordMatrixDialog(final Activity activity, final JSONObject cfg) {
        final String[] keys = new String[]{"family", "shorts", "admin", "switchGroup", "interest"};
        final String[] titles = new String[]{
                "Password #1: Family Mode",
                "Password #2: Shorts Unlock",
                "Password #3: Group & Whitelist Admin",
                "Password #4: Group Switcher",
                "Password #5: Interest Filter & 15m Pass"
        };
        new AlertDialog.Builder(activity)
                .setTitle("Select Password Slot to Change")
                .setItems(titles, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, final int which) {
                        verifyPassword(activity, cfg, keys[which], "Verify Current " + titles[which], new Runnable() {
                            @Override
                            public void run() {
                                final EditText newInput = new EditText(activity);
                                newInput.setHint("Enter New Password (min 4 chars)");
                                new AlertDialog.Builder(activity)
                                        .setTitle("Set New " + titles[which])
                                        .setView(newInput)
                                        .setPositiveButton("Save", new DialogInterface.OnClickListener() {
                                            @Override
                                            public void onClick(DialogInterface dialog, int w) {
                                                String np = newInput.getText().toString().trim();
                                                if (np.length() >= 4) {
                                                    try {
                                                        cfg.optJSONObject("passwords").put(keys[which], np);
                                                        saveConfig(activity, cfg);
                                                        Toast.makeText(activity, "Password updated!", Toast.LENGTH_SHORT).show();
                                                    } catch (Throwable ignored) {}
                                                } else {
                                                    Toast.makeText(activity, "Min 4 characters required", Toast.LENGTH_SHORT).show();
                                                }
                                            }
                                        })
                                        .show();
                            }
                        });
                    }
                })
                .show();
    }

    private void ensureDefaultConfig(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (prefs.contains(KEY_CONFIG)) return;
        try {
            JSONObject root = new JSONObject();
            JSONObject pwds = new JSONObject();
            pwds.put("family", "1111");
            pwds.put("shorts", "2222");
            pwds.put("admin", "3333");
            pwds.put("switchGroup", "4444");
            pwds.put("interest", "5555");
            root.put("passwords", pwds);

            root.put("familyModeExpiry", 0L);
            root.put("shortsUnlockExpiry", 0L);
            root.put("emergencyPassExpiry", 0L);
            root.put("enforceShortsBlock", true);
            root.put("enforceWhitelist", true);
            root.put("enforceTitleBlacklist", true);
            root.put("enforceInterestFilter", false);
            root.put("activeGroupId", "study");

            JSONArray blocked = new JSONArray();
            String[] defBlocked = new String[]{"vlog", "roast", "prank", "controversy", "reaction", "gossip"};
            for (int i = 0; i < defBlocked.length; i++) blocked.put(defBlocked[i]);
            root.put("blockedTitleKeywords", blocked);

            JSONArray groups = new JSONArray();
            JSONObject g1 = new JSONObject();
            g1.put("id", "study");
            g1.put("name", "B.Sc & Defence Study");
            JSONArray ch1 = new JSONArray();
            ch1.put("Physics Wallah");
            ch1.put("Maths Jugad Se");
            ch1.put("Defence Wallah");
            g1.put("channels", ch1);
            JSONArray int1 = new JSONArray();
            int1.put("Physics");
            int1.put("Mathematics");
            int1.put("Chemistry");
            int1.put("NDA");
            g1.put("interests", int1);
            groups.put(g1);

            JSONObject g2 = new JSONObject();
            g2.put("id", "coding");
            g2.put("name", "Coding & Full-Stack");
            JSONArray ch2 = new JSONArray();
            ch2.put("Flutter");
            ch2.put("Fireship");
            ch2.put("CodeWithHarry");
            ch2.put("Hitesh Choudhary");
            g2.put("channels", ch2);
            JSONArray int2 = new JSONArray();
            int2.put("Flutter");
            int2.put("Kotlin");
            int2.put("JavaScript");
            g2.put("interests", int2);
            groups.put(g2);

            root.put("groups", groups);
            prefs.edit().putString(KEY_CONFIG, root.toString()).commit();
        } catch (Throwable ignored) {}
    }

    private JSONObject loadConfig(Context context) {
        ensureDefaultConfig(context);
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        try {
            return new JSONObject(prefs.getString(KEY_CONFIG, "{}"));
        } catch (Throwable e) {
            return new JSONObject();
        }
    }

    private void saveConfig(Context context, JSONObject cfg) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_CONFIG, cfg.toString()).commit();
    }

    private void lockFamilyMode(Context context) {
        JSONObject cfg = loadConfig(context);
        try {
            cfg.put("familyModeExpiry", 0L);
            saveConfig(context, cfg);
        } catch (Throwable ignored) {}
    }

    private boolean isFamilyMode(JSONObject cfg) {
        return System.currentTimeMillis() < cfg.optLong("familyModeExpiry", 0L);
    }

    private JSONObject getActiveGroup(JSONObject cfg) {
        String id = cfg.optString("activeGroupId", "study");
        JSONArray arr = cfg.optJSONArray("groups");
        if (arr == null || arr.length() == 0) return null;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject g = arr.optJSONObject(i);
            if (g != null && id.equals(g.optString("id"))) return g;
        }
        return arr.optJSONObject(0);
    }

    private String formatRem(long expiry) {
        long diff = (expiry - System.currentTimeMillis()) / 1000L;
        if (diff <= 0) return "0m";
        return (diff / 60L) + "m " + (diff % 60L) + "s";
    }

    private void addSectionTitle(Context ctx, LinearLayout parent, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#0F172A"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(ctx, 12), 0, dp(ctx, 2));
        parent.addView(tv);
    }

    private void addSubtitle(Context ctx, LinearLayout parent, String text) {
        TextView tv = new TextView(ctx);
        tv.setText(text);
        tv.setTextColor(Color.parseColor("#475569"));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tv.setPadding(0, 0, 0, dp(ctx, 6));
        parent.addView(tv);
    }

    private Button createActionButton(Context ctx, String text) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        return b;
    }

    private int dp(Context ctx, int val) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, val, ctx.getResources().getDisplayMetrics());
    }

    @Override public void onActivityCreated(Activity activity, Bundle savedInstanceState) { attachToActivity(activity); }
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityResumed(Activity activity) { attachToActivity(activity); }
    @Override public void onActivityPaused(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle outState) {}
    @Override public void onActivityDestroyed(Activity activity) {}
}
