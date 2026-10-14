package nx.ext;

import java.io.PrintWriter;
import java.io.StringWriter;

/** Keeps the last crash of the app, so the diagnostics report can show it after a restart. */
final class CrashLog {
    private CrashLog() {
    }

    private static final String KEY = "last_crash";
    private static boolean installed;

    static synchronized void install() {
        if (installed) return;
        installed = true;
        final Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                StringWriter text = new StringWriter();
                error.printStackTrace(new PrintWriter(text));
                String trace = text.toString();
                if (trace.length() > 3000) trace = trace.substring(0, 3000) + "…";
                String time = new java.text.SimpleDateFormat("dd.MM HH:mm:ss", java.util.Locale.US).format(new java.util.Date());
                // commit(): the process is about to die.
                Nx.prefs().edit().putString(KEY, time + " [" + thread.getName() + "]\n" + trace).commit();
            } catch (Throwable ignored) {
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    static String last() {
        try {
            return Nx.prefs().getString(KEY, null);
        } catch (Throwable t) {
            return null;
        }
    }

    static void clear() {
        try {
            Nx.prefs().edit().remove(KEY).apply();
        } catch (Throwable ignored) {
        }
    }
}
