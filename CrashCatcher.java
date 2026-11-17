package com.rohit.ytguard;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.os.Handler;
import android.os.Looper;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.ref.WeakReference;

public class CrashCatcher {

    private static boolean installed = false;
    private static WeakReference<Activity> last;

    public static void install(Context c) {
        try {
            if (c instanceof Activity) {
                last = new WeakReference<Activity>((Activity) c);
            }
            if (installed) return;
            installed = true;
            final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
            Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
                @Override
                public void uncaughtException(Thread t, Throwable e) {
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

    private static boolean show(Thread t, Throwable e) throws Exception {
        StringWriter sw = new StringWriter();
        e.printStackTrace(new PrintWriter(sw));
        String s = "Thread: " + t.getName() + "\n" + sw.toString();
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
