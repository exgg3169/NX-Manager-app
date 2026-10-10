package nx.ext;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

/** Shared helpers for code running inside the patched app. */
public final class Nx {
    public static final String TAG = "NX";
    private static Context context;
    private static SharedPreferences prefs;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private Nx() {
    }

    /** The app's context, available from anywhere without needing a hook into Application. */
    public static Context context() {
        if (context == null) {
            try {
                Object app = Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
                if (app != null) context = ((Context) app).getApplicationContext();
            } catch (Throwable t) {
                Log.e(TAG, "No context", t);
            }
        }
        return context;
    }

    public static SharedPreferences prefs() {
        if (prefs == null) {
            Context c = context();
            if (c != null) prefs = c.getSharedPreferences("nx_settings", Context.MODE_PRIVATE);
        }
        return prefs;
    }

    public static boolean bool(NxSettings.Setting setting) {
        try {
            SharedPreferences p = prefs();
            return p == null ? (Boolean) setting.def : p.getBoolean(setting.key, (Boolean) setting.def);
        } catch (Throwable t) {
            return (Boolean) setting.def;
        }
    }

    public static String str(NxSettings.Setting setting) {
        try {
            SharedPreferences p = prefs();
            return p == null ? (String) setting.def : p.getString(setting.key, (String) setting.def);
        } catch (Throwable t) {
            return (String) setting.def;
        }
    }

    public static void put(NxSettings.Setting setting, Object value) {
        SharedPreferences p = prefs();
        if (p == null) return;
        SharedPreferences.Editor e = p.edit();
        if (value instanceof Boolean) e.putBoolean(setting.key, (Boolean) value);
        else e.putString(setting.key, String.valueOf(value));
        e.apply();
    }

    public static void runOnMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else MAIN.post(r);
    }

    public static void runOnMainDelayed(Runnable r, long delayMs) {
        MAIN.postDelayed(r, delayMs);
    }

    public static void toast(final String text) {
        runOnMain(new Runnable() {
            @Override
            public void run() {
                Context c = context();
                if (c != null) Toast.makeText(c, text, Toast.LENGTH_SHORT).show();
            }
        });
    }

    public static boolean turkish() {
        return "tr".equals(java.util.Locale.getDefault().getLanguage());
    }

    /** Picks the Turkish or English text depending on the device language. */
    public static String t(String tr, String en) {
        return turkish() ? tr : en;
    }

    public static void log(String message, Throwable t) {
        Log.e(TAG, message, t);
    }
}
