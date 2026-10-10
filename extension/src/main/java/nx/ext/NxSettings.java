package nx.ext;

import java.util.ArrayList;
import java.util.List;

/** Every user setting of the patched app, with the texts shown in the NX settings screen. */
public final class NxSettings {
    private NxSettings() {
    }

    public static final class Setting {
        public final String key;
        public final String section;
        public final String title;
        public final String summary;
        public final Object def;
        /** Feature this setting belongs to (see {@link Features}). */
        public final String feature;
        /** For choice settings: stored values and their labels; null for on/off settings. */
        public final String[] values;
        public final String[] labels;

        Setting(String feature, String key, String section, String title, String summary, Object def, String[] values, String[] labels) {
            this.feature = feature;
            this.key = key;
            this.section = section;
            this.title = title;
            this.summary = summary;
            this.def = def;
            this.values = values;
            this.labels = labels;
        }
    }

    public static final List<Setting> ALL = new ArrayList<>();

    private static String feature = null;

    private static Setting bool(String key, String section, String title, String summary, boolean def) {
        Setting s = new Setting(feature, key, section, title, summary, def, null, null);
        ALL.add(s);
        return s;
    }

    private static Setting choice(String key, String section, String title, String def, String[] values, String[] labels) {
        Setting s = new Setting(feature, key, section, title, null, def, values, labels);
        ALL.add(s);
        return s;
    }

    private static final String ADS = Nx.t("Reklamlar", "Ads");
    private static final String SHORTS = "Shorts";
    private static final String VIDEO = Nx.t("Video", "Video");
    private static final String RYD = "Return YouTube Dislike";
    private static final String SB = "SponsorBlock";
    private static final String PLAYBACK = Nx.t("Oynatma", "Playback");

    // region Ads

    static {
        feature = Features.FEED_ADS;
    }

    public static final Setting HIDE_FEED_ADS = bool("hide_feed_ads", ADS,
            Nx.t("Akıştaki reklamları gizle", "Hide feed ads"),
            Nx.t("Ana sayfa, arama ve izleme sayfasındaki reklam kartları", "Ad cards in home, search and watch page"), true);
    public static final Setting HIDE_PREMIUM = bool("hide_premium", ADS,
            Nx.t("Premium tanıtımlarını gizle", "Hide Premium promotions"), null, true);
    public static final Setting HIDE_MERCH = bool("hide_merch", ADS,
            Nx.t("Ürün ve alışveriş raflarını gizle", "Hide merchandise and shopping shelves"), null, true);

    // endregion

    // region Shorts

    static {
        feature = Features.SHORTS;
    }

    public static final Setting HIDE_SHORTS_BUTTON = bool("hide_shorts_button", SHORTS,
            Nx.t("Alt menüdeki Shorts düğmesini gizle", "Hide Shorts button in navigation bar"), null, true);
    public static final Setting HIDE_SHORTS_SHELF = bool("hide_shorts_shelf", SHORTS,
            Nx.t("Akıştaki Shorts rafını gizle", "Hide Shorts shelf in feeds"),
            Nx.t("Ana sayfa, abonelikler ve aramadaki Shorts bölümleri", "Shorts sections in home, subscriptions and search"), true);

    // endregion

    // region Video quality and speed

    static {
        feature = Features.QUALITY;
    }

    private static final String[] QUALITY_VALUES = {"auto", "2160", "1440", "1080", "720", "480", "360", "240", "144"};
    private static final String[] QUALITY_LABELS = {
            Nx.t("Otomatik (YouTube seçer)", "Automatic (YouTube decides)"),
            "2160p", "1440p", "1080p", "720p", "480p", "360p", "240p", "144p"};

    public static final Setting QUALITY_WIFI = choice("quality_wifi", VIDEO,
            Nx.t("Varsayılan kalite (Wi-Fi)", "Default quality (Wi-Fi)"), "auto", QUALITY_VALUES, QUALITY_LABELS);
    public static final Setting QUALITY_MOBILE = choice("quality_mobile", VIDEO,
            Nx.t("Varsayılan kalite (mobil veri)", "Default quality (mobile data)"), "auto", QUALITY_VALUES, QUALITY_LABELS);
    public static final Setting SPEED = choice("speed", VIDEO,
            Nx.t("Varsayılan oynatma hızı", "Default playback speed"), "1.0",
            Video.SPEED_VALUES, Video.speedLabels());
    public static final Setting REMEMBER_SPEED = bool("remember_speed", VIDEO,
            Nx.t("Seçilen hızı hatırla", "Remember chosen speed"),
            Nx.t("Videoda değiştirdiğiniz hız sonraki videolarda da kullanılır", "A speed you pick is used for the next videos too"), true);

    static {
        feature = "speed_menu";
    }

    public static final Setting SPEED_MENU = bool("speed_menu", VIDEO,
            Nx.t("NX hız paneli (4x'e kadar)", "NX speed panel (up to 4x)"),
            Nx.t("YouTube'un Premium isteyen hız paneli yerine 0.25x–4x NX panelini açar", "Opens NX's 0.25x–4x panel instead of YouTube's, which asks for Premium"), true);

    // endregion

    // region Playback

    static {
        feature = "spoof_streams";
    }

    public static final Setting SPOOF_STREAMS = bool("spoof_streams", PLAYBACK,
            Nx.t("Video akışı düzeltmesi", "Fix video playback"),
            Nx.t("Videolar durursa veya açılmazsa açık tutun", "Keep on if videos stop or do not start"), true);

    // endregion

    // region Return YouTube Dislike

    static {
        feature = "ryd";
    }

    public static final Setting RYD_ENABLED = bool("ryd_enabled", RYD,
            Nx.t("Beğenmeme sayısını göster", "Show dislike count"),
            Nx.t("Veriler returnyoutubedislike.com'dan gelir", "Data from returnyoutubedislike.com"), true);

    // endregion

    // region SponsorBlock

    static {
        feature = Features.SPONSORBLOCK;
    }

    public static final Setting SB_ENABLED = bool("sb_enabled", SB,
            Nx.t("SponsorBlock'u aç", "Enable SponsorBlock"),
            Nx.t("Topluluğun işaretlediği bölümleri atlar (sponsor.ajay.app)", "Skips community-marked segments (sponsor.ajay.app)"), true);
    public static final Setting SB_TOAST = bool("sb_toast", SB,
            Nx.t("Atlarken bildirim göster", "Show a message when skipping"), null, true);
    public static final Setting SB_SPONSOR = bool("sb_cat_sponsor", SB, Nx.t("Sponsor", "Sponsor"), null, true);
    public static final Setting SB_SELFPROMO = bool("sb_cat_selfpromo", SB,
            Nx.t("Kendi tanıtımı", "Self promotion"), null, true);
    public static final Setting SB_INTERACTION = bool("sb_cat_interaction", SB,
            Nx.t("Abone ol / beğen hatırlatması", "Interaction reminder"), null, true);
    public static final Setting SB_INTRO = bool("sb_cat_intro", SB, Nx.t("Giriş", "Intro"), null, false);
    public static final Setting SB_OUTRO = bool("sb_cat_outro", SB, Nx.t("Kapanış / jenerik", "Outro / credits"), null, false);
    public static final Setting SB_PREVIEW = bool("sb_cat_preview", SB, Nx.t("Önizleme / özet", "Preview / recap"), null, false);
    public static final Setting SB_FILLER = bool("sb_cat_filler", SB, Nx.t("Konu dışı dolgu", "Filler tangent"), null, false);
    public static final Setting SB_MUSIC = bool("sb_cat_music_offtopic", SB,
            Nx.t("Müzik videolarında müzik olmayan kısım", "Non-music section in music videos"), null, true);

    // endregion

    // region Diagnostics

    // endregion
}
