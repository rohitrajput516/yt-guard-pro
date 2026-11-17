package com.rohit.ytguard;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.Application;
import android.app.ApplicationExitInfo;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Set;

public class CrashCatcher {

    private static boolean installed = false;
    private static WeakReference<Activity> last;
    private static Context appCtx;

    private static final StringBuilder LOG = new StringBuilder();
    private static long t0 = 0L;
    private static boolean flushPending = false;

    public static void install(Context c) {
        try {
            if (c instanceof Activity) {
                last = new WeakReference<Activity>((Activity) c);
            }
            if (installed) return;
            installed = true;
            t0 = SystemClock.elapsedRealtime();

            Context a = c.getApplicationContext();
            appCtx = (a != null) ? a : c;

            synchronized (CrashCatcher.class) {
                LOG.append("YTGuard timeline report\n");
                LOG.append("package: ").append(appCtx.getPackageName()).append("\n");
                LOG.append("android sdk: ").append(Build.VERSION.SDK_INT).append("\n");
                LOG.append("pid: ").append(android.os.Process.myPid()).append("\n\n");
                LOG.append("== last Java crash saved by guard ==\n");
                LOG.append(appCtx.getSharedPreferences("ytguard_crash", 0).getString("last", "(none)"));
                LOG.append("\n\n== previous process exits (newest first) ==\n");
                if (Build.VERSION.SDK_INT >= 30) {
                    try {
                        Api30.append(LOG, appCtx);
                    } catch (Throwable ignored) {
                    }
                } else {
                    LOG.append("(needs Android 11 or newer)\n");
                }
                LOG.append("\n== timeline of this process ==\n");
            }

            if (c instanceof Activity) {
                log("hook      " + describe((Activity) c));
            }

            try {
                if (appCtx instanceof Application) {
                    ((Application) appCtx).registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                        @Override
                        public void onActivityCreated(Activity act, Bundle b) {
                            log("created   " + describe(act));
                        }

                        @Override
                        public void onActivityStarted(Activity act) {
                            log("started   " + act.getClass().getName());
                        }

                        @Override
                        public void onActivityResumed(Activity act) {
                            log("resumed   " + act.getClass().getName());
                        }

                        @Override
                        public void onActivityPaused(Activity act) {
                            log("paused    " + act.getClass().getName() + " finishing=" + act.isFinishing());
                        }

                        @Override
                        public void onActivityStopped(Activity act) {
                            log("stopped   " + act.getClass().getName() + " finishing=" + act.isFinishing());
                        }

                        @Override
                        public void onActivitySaveInstanceState(Activity act, Bundle b) {
                        }

                        @Override
                        public void onActivityDestroyed(Activity act) {
                            log("destroyed " + act.getClass().getName()
                                    + " finishing=" + act.isFinishing()
                                    + " changingConfig=" + act.isChangingConfigurations());
                        }
                    });
                }
            } catch (Throwable ignored) {
            }

            final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread t, Throwable e) {
                    try {
                        String s = trace(t, e);
                        appCtx.getSharedPreferences("ytguard_crash", 0).edit().putString("last", s).commit();
                        log("CRASH     " + (s.length() > 1200 ? s.substring(0, 1200) : s));
                        flushNow();
                    } catch (Throwable ignored) {
                    }
                    boolean shown = false;
                    try {
                        shown = show(t, e);
                    } catch (Throwable ignored) {
                    }
                    if (!shown && prev != null) {
                        prev.uncaughtException(t, e);
                    }
                }
            });
        } catch (Throwable ignored) {
        }
    }

    private static String trace(Thread t, Throwable e) {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String s = "Thread: " + t.getName() + "\n" + sw.toString();
        if (s.length() > 3000) s = s.substring(0, 3000);
        return s;
    }

    private static String describe(Activity a) {
        StringBuilder sb = new StringBuilder(a.getClass().getName());
        try {
            Intent i = a.getIntent();
            if (i != null) {
                sb.append(" [action=").append(i.getAction());
                sb.append(" comp=").append(i.getComponent() == null ? "null" : i.getComponent().flattenToShortString());
                sb.append(" flags=0x").append(Integer.toHexString(i.getFlags()));
                Set<String> cats = i.getCategories();
                if (cats != null) sb.append(" cats=").append(cats);
                sb.append("]");
            }
            sb.append(" taskId=").append(a.getTaskId());
            sb.append(" isTaskRoot=").append(a.isTaskRoot());
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private static void log(String s) {
        synchronized (CrashCatcher.class) {
            long dt = SystemClock.elapsedRealtime() - t0;
            LOG.append('+').append(dt).append("ms  ").append(s).append('\n');
        }
        scheduleFlush();
    }

    private static void scheduleFlush() {
        try {
            synchronized (CrashCatcher.class) {
                if (flushPending) return;
                flushPending = true;
            }
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            synchronized (CrashCatcher.class) {
                                flushPending = false;
                            }
                            flushNow();
                        }
                    }).start();
                }
            }, 300L);
        } catch (Throwable ignored) {
        }
    }

    private static void flushNow() {
        try {
            String text;
            synchronized (CrashCatcher.class) {
                text = LOG.toString();
            }
            writeToDownloads(appCtx, text);
        } catch (Throwable ignored) {
        }
    }

    private static void writeToDownloads(Context ctx, String text) {
        if (Build.VERSION.SDK_INT < 29) return;
        synchronized (CrashCatcher.class.getName()) {
            try {
                ContentResolver r = ctx.getContentResolver();
                try {
                    r.delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                            MediaStore.MediaColumns.DISPLAY_NAME + "=?",
                            new String[]{"ytguard_report.txt"});
                } catch (Throwable ignored) {
                }
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, "ytguard_report.txt");
                v.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri u = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (u == null) return;
                OutputStream os = r.openOutputStream(u);
                if (os != null) {
                    os.write(text.getBytes("UTF-8"));
                    os.close();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    static String reasonName(int r) {
        switch (r) {
            case 0: return "UNKNOWN";
            case 1: return "EXIT_SELF (app called exit itself)";
            case 2: return "SIGNALED (killed by signal)";
            case 3: return "LOW_MEMORY";
            case 4: return "CRASH (Java exception)";
            case 5: return "CRASH_NATIVE (native crash)";
            case 6: return "ANR";
            case 7: return "INITIALIZATION_FAILURE";
            case 8: return "PERMISSION_CHANGE";
            case 9: return "EXCESSIVE_RESOURCE_USAGE";
            case 10: return "USER_REQUESTED";
            case 11: return "USER_STOPPED";
            case 12: return "DEPENDENCY_DIED";
            case 13: return "OTHER";
            case 14: return "FREEZER";
            default: return "CODE_" + r;
        }
    }

    private static class Api30 {
        static void append(StringBuilder sb, Context ctx) {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            List<ApplicationExitInfo> list = am.getHistoricalProcessExitReasons(null, 0, 10);
            if (list == null || list.isEmpty()) {
                sb.append("(none)\n");
                return;
            }
            long now = System.currentTimeMillis();
            for (ApplicationExitInfo e : list) {
                sb.append("process=").append(e.getProcessName())
                        .append(" | reason=").append(reasonName(e.getReason()))
                        .append(" | status=").append(e.getStatus())
                        .append(" | ago=").append((now - e.getTimestamp()) / 1000L).append("s")
                        .append(" | ").append(e.getDescription())
                        .append("\n");
            }
        }
    }

    private static boolean show(Thread t, Throwable e) throws Exception {
        String s = trace(t, e);
        if (s.length() > 3500) s = s.substring(0, 3500);
        final String msg = s;
        final Activity a = (last == null) ? null : last.get();
        if (a == null) return false;

        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (!showDialog(a, msg)) return false;
            Looper.loop();
            return true;
        } else {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public void run() {
                    showDialog(a, msg);
                }
            });
            Thread.sleep(600000L);
            return true;
        }
    }

    private static boolean showDialog(Activity a, String msg) {
        try {
            AlertDialog.Builder b = new AlertDialog.Builder(a);
            b.setTitle("YTGuard CRASH LOG");
            b.setMessage(msg);
            b.setCancelable(false);
            b.setPositiveButton("Close app", new DialogInterface.OnClickListener() {
                @Override
                public void onClick(DialogInterface d, int w) {
                    android.os.Process.killProcess(android.os.Process.myPid());
                }
            });
            b.show();
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }
}
