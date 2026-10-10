package nx.ext;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.SystemClock;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Deque;
import java.util.Locale;

/**
 * Records what happens around playback attestation so the user can see why videos stop:
 * whether YouTube got a proof-of-origin token (PoToken) and which player errors occurred.
 */
@SuppressWarnings("unused")
public final class Diagnostics {
    private Diagnostics() {
    }

    private static final int MAX_EVENTS = 15;
    private static final Deque<String> events = new ArrayDeque<>();
    private static long lastTokenAt;
    private static int lastTokenSize = -1;
    private static int tokenCount, missingCount;
    private static long lastToastAt;

    // region Injection points

    /** YouTube read its session PoToken; empty means it has none. */
    public static void onPoToken(byte[] token) {
        try {
            int size = token == null ? 0 : token.length;
            lastTokenAt = System.currentTimeMillis();
            lastTokenSize = size;
            tokenCount++;
            if (size == 0 && tokenCount % 20 == 1) add(Nx.t("Jeton BOŞ döndü", "Token was EMPTY"));
        } catch (Throwable ignored) {
        }
    }

    /** The token store answered a lookup; [token] holds the token bytes or is null. */
    public static void onTokenLookup(Object token) {
        try {
            if (token == null) {
                onPoTokenMissing();
                return;
            }
            for (java.lang.reflect.Field f : token.getClass().getDeclaredFields()) {
                if (f.getType() == byte[].class && !java.lang.reflect.Modifier.isStatic(f.getModifiers())) {
                    f.setAccessible(true);
                    onPoToken((byte[]) f.get(token));
                    return;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static String lastError;

    /** Minting a token failed with [error]. */
    public static void onTokenError(Throwable error) {
        if (error == null) return;
        String text = String.valueOf(error);
        Throwable cause = error.getCause();
        if (cause != null && cause != error) text += " ← " + cause;
        if (text.length() > 160) text = text.substring(0, 160) + "…";
        if (text.equals(lastError)) return;
        lastError = text;
        add(Nx.t("Jeton üretim hatası: ", "Token minting error: ") + text);
    }

    /** YouTube found no session PoToken. */
    public static void onPoTokenMissing() {
        missingCount++;
        if (missingCount % 20 == 1) add(Nx.t("Jeton yok (oturum jetonu oluşturulamadı)", "No token (session token not initialized)"));
    }

    /** A player error report with its code, e.g. "potoken.nulloninit". */
    public static void onPlayerError(String code) {
        if (code == null) return;
        add(Nx.t("Oynatıcı hatası: ", "Player error: ") + code);
        // "cache" reports are routine disk cache notes, not playback failures.
        if (!"cache".equals(code) && Nx.bool(NxSettings.DIAG_TOASTS)) {
            long now = SystemClock.elapsedRealtime();
            if (now - lastToastAt > 3000) {
                lastToastAt = now;
                Nx.toast("NX: " + code);
            }
        }
    }

    // endregion

    private static synchronized void add(String event) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        events.addFirst(time + "  " + event);
        while (events.size() > MAX_EVENTS) events.removeLast();
    }

    public static synchronized void clear() {
        events.clear();
        lastTokenAt = 0;
        lastTokenSize = -1;
        lastError = null;
        tokenCount = 0;
        missingCount = 0;
    }

    /** A readable summary for the settings screen. */
    public static synchronized String report() {
        StringBuilder sb = new StringBuilder();
        if (lastTokenAt == 0) {
            sb.append(Nx.t("Jeton: henüz istenmedi (bir video açın)", "Token: not requested yet (play a video)"));
        } else {
            String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date(lastTokenAt));
            sb.append(Nx.t("Son jeton: ", "Last token: ")).append(time).append(" — ")
                    .append(lastTokenSize > 0 ? lastTokenSize + Nx.t(" bayt ✓", " bytes ✓") : Nx.t("BOŞ ✗", "EMPTY ✗"));
        }
        sb.append('\n').append(Nx.t("Jeton okuma: ", "Token reads: ")).append(tokenCount)
                .append(Nx.t(", jetonsuz: ", ", missing: ")).append(missingCount);
        sb.append("\n\n").append(Nx.t("Son olaylar:", "Recent events:"));
        if (events.isEmpty()) sb.append('\n').append(Nx.t("(yok)", "(none)"));
        for (String e : events) sb.append('\n').append(e);
        return sb.toString();
    }

    public interface Result {
        void done(String message);
    }

    /** Checks that microG's PoToken service can be reached at all. */
    public static void testTokenService(final Context context, final Result result) {
        final Intent intent = new Intent("app.revanced.android.gms.potokens.service.START")
                .setPackage("app.revanced.android.gms");
        final boolean[] finished = {false};
        final ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                finish(Nx.t("microG jeton servisine bağlanıldı ✓", "Connected to microG token service ✓"));
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
            }

            private void finish(String message) {
                if (finished[0]) return;
                finished[0] = true;
                try {
                    context.unbindService(this);
                } catch (Throwable ignored) {
                }
                result.done(message);
            }
        };
        boolean bound;
        try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
        } catch (Throwable t) {
            bound = false;
        }
        if (!bound) {
            finished[0] = true;
            result.done(Nx.t("microG jeton servisi bulunamadı ✗ (microG kurulu ve güncel mi?)",
                    "microG token service not found ✗ (is microG installed and up to date?)"));
            return;
        }
        Nx.runOnMainDelayed(new Runnable() {
            @Override
            public void run() {
                if (finished[0]) return;
                finished[0] = true;
                try {
                    context.unbindService(connection);
                } catch (Throwable ignored) {
                }
                result.done(Nx.t("microG jeton servisi yanıt vermedi ✗", "microG token service did not respond ✗"));
            }
        }, 8000);
    }
}
