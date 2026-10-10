package nx.ext;

/** Background playback switches; the patch passes YouTube's own answers through these. */
@SuppressWarnings("unused")
public final class Background {
    private Background() {
    }

    private static boolean enabled() {
        try {
            return Features.has(Features.BACKGROUND) && Nx.bool(NxSettings.BACKGROUND);
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean shortsEnabled() {
        try {
            return enabled() && Nx.bool(NxSettings.BACKGROUND_SHORTS);
        } catch (Throwable t) {
            return true;
        }
    }

    private static volatile String playerType;

    /** Injection point: the player changed its type (opened, minimized, feed preview, …). */
    public static void setPlayerType(Enum<?> type) {
        playerType = type == null ? null : type.name();
    }

    /** Injection point: may this video keep playing in the background? */
    public static boolean allowed(boolean original) {
        if (original || !enabled()) return original;
        // Only the opened player: not feed previews, not when no video is open.
        String type = playerType;
        return type == null || !(type.equals("NONE") || type.equals("HIDDEN") || type.equals("INLINE_MINIMAL"));
    }

    /** Injection point: may this Short keep playing in the background? */
    public static boolean shortsAllowed(boolean original) {
        return shortsEnabled() || (original && !enabled());
    }

    /** Injection point: skip the kids-video background block. */
    public static boolean skipKidsPolicy() {
        return enabled();
    }

    /** Injection point: a feature flag that must be on for background playback. */
    public static boolean enableFlag(boolean original) {
        return original || enabled();
    }

    /** Injection point: a feature flag that must be off for background playback. */
    public static boolean disableFlag(boolean original) {
        return original && !enabled();
    }
}
