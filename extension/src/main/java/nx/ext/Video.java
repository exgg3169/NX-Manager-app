package nx.ext;

import android.os.SystemClock;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Current video and player state. The patch adds bridge methods with fixed names
 * ({@code nxTime}, {@code nxSeek}, {@code nxSetSpeed}) to YouTube's obfuscated player class.
 */
@SuppressWarnings("unused")
public final class Video {
    private Video() {
    }

    public interface Listener {
        void onNewVideo(String videoId);
    }

    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static WeakReference<Object> player = new WeakReference<>(null);
    private static Method timeMethod, seekMethod, speedMethod;
    private static volatile String videoId;
    private static long newVideoAt;
    private static boolean settingSpeed;

    public static void addListener(Listener l) {
        synchronized (LISTENERS) {
            LISTENERS.add(l);
        }
    }

    /** Injection point: the player controller was created. */
    public static void setPlayer(Object p) {
        if (p == null) return;
        player = new WeakReference<>(p);
        try {
            Class<?> c = p.getClass();
            timeMethod = c.getMethod("nxTime");
            seekMethod = c.getMethod("nxSeek", long.class);
            speedMethod = c.getMethod("nxSetSpeed", float.class);
        } catch (Throwable t) {
            Nx.log("Player bridge missing", t);
        }
    }

    /** Injection point: the video being played. Called often, also for the same video. */
    public static void onVideoId(String id) {
        if (id == null || id.isEmpty() || id.equals(videoId)) return;
        videoId = id;
        newVideoAt = SystemClock.elapsedRealtime();
        Listener[] copy;
        synchronized (LISTENERS) {
            copy = LISTENERS.toArray(new Listener[0]);
        }
        for (Listener l : copy) {
            try {
                l.onNewVideo(id);
            } catch (Throwable t) {
                Nx.log("Listener failed", t);
            }
        }
        if (Features.has(Features.SPONSORBLOCK)) SponsorBlock.onNewVideo(id);
        if (Features.has(Features.QUALITY)) applyDefaultSpeed();
    }

    public static String videoId() {
        return videoId;
    }

    /** Current position in milliseconds, or -1. */
    public static long time() {
        try {
            Object p = player.get();
            return p == null || timeMethod == null ? -1 : (Long) timeMethod.invoke(p);
        } catch (Throwable t) {
            return -1;
        }
    }

    public static boolean seek(long ms) {
        try {
            Object p = player.get();
            return p != null && seekMethod != null && (Boolean) seekMethod.invoke(p, ms);
        } catch (Throwable t) {
            Nx.log("Seek failed", t);
            return false;
        }
    }

    public static void setSpeed(float speed) {
        try {
            Object p = player.get();
            if (p == null || speedMethod == null) return;
            settingSpeed = true;
            speedMethod.invoke(p, speed);
        } catch (Throwable t) {
            Nx.log("Speed failed", t);
        } finally {
            settingSpeed = false;
        }
    }

    // region Playback speed

    private static void applyDefaultSpeed() {
        final float speed = parseSpeed(Nx.str(NxSettings.SPEED));
        if (speed == 1.0f) return;
        // The player resets the speed while it loads the video, so apply it a bit later too.
        Nx.runOnMainDelayed(new Runnable() {
            @Override
            public void run() {
                setSpeed(speed);
            }
        }, 700);
        Nx.runOnMainDelayed(new Runnable() {
            @Override
            public void run() {
                setSpeed(speed);
            }
        }, 2000);
    }

    /** Injection point: the player speed changes, by the user or by the app. */
    public static void onSpeedSet(float speed) {
        if (settingSpeed || !Features.has(Features.QUALITY) || !Nx.bool(NxSettings.REMEMBER_SPEED)) return;
        // Changes right after a video starts come from the app, not the user.
        if (SystemClock.elapsedRealtime() - newVideoAt < 2500) return;
        if (speed <= 0f || speed > 4f) return;
        Nx.put(NxSettings.SPEED, String.valueOf(speed));
    }

    private static float parseSpeed(String value) {
        try {
            return Float.parseFloat(value);
        } catch (Exception e) {
            return 1.0f;
        }
    }

    // endregion
}
