package nx.ext;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * Skips segments marked by the SponsorBlock community (https://sponsor.ajay.app).
 * Videos are looked up by a 4-character hash prefix, so the server does not learn which video is watched.
 */
public final class SponsorBlock {
    private SponsorBlock() {
    }

    private static final String API = "https://sponsor.ajay.app/api/skipSegments/";
    private static final long POLL_MS = 400;

    private static final class Segment {
        final long start, end;
        final String category;
        boolean skipped;

        Segment(long start, long end, String category) {
            this.start = start;
            this.end = end;
            this.category = category;
        }
    }

    private static volatile List<Segment> segments = new ArrayList<>();
    private static volatile String segmentsFor;
    private static boolean polling;

    /** Called by {@link Video} when a new video starts. */
    static void onNewVideo(final String videoId) {
        segments = new ArrayList<>();
        segmentsFor = videoId;
        if (!Nx.bool(NxSettings.SB_ENABLED)) return;
        final String categories = enabledCategories();
        if (categories.equals("[]")) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<Segment> found = fetch(videoId, categories);
                if (!videoId.equals(segmentsFor) || found.isEmpty()) return;
                segments = found;
                startPolling();
            }
        }, "nx-sponsorblock").start();
    }

    private static void startPolling() {
        Nx.runOnMain(new Runnable() {
            @Override
            public void run() {
                if (polling) return;
                polling = true;
                Nx.runOnMainDelayed(POLLER, POLL_MS);
            }
        });
    }

    private static final Runnable POLLER = new Runnable() {
        @Override
        public void run() {
            List<Segment> current = segments;
            if (current.isEmpty() || !Nx.bool(NxSettings.SB_ENABLED)) {
                polling = false;
                return;
            }
            check(current, Video.time());
            Nx.runOnMainDelayed(this, POLL_MS);
        }
    };

    private static void check(List<Segment> current, long now) {
        if (now < 0) return;
        for (Segment s : current) {
            // Seeking back before a segment makes it skippable again.
            if (now < s.start - 1000) s.skipped = false;
            if (!s.skipped && now >= s.start && now < s.end - 500) {
                s.skipped = true;
                if (Video.seek(s.end) && Nx.bool(NxSettings.SB_TOAST)) {
                    Nx.toast(Nx.t("Atlandı: ", "Skipped: ") + label(s.category));
                }
                return;
            }
        }
    }

    private static List<Segment> fetch(String videoId, String categories) {
        List<Segment> result = new ArrayList<>();
        HttpURLConnection connection = null;
        try {
            String prefix = sha256(videoId).substring(0, 4);
            URL url = new URL(API + prefix + "?categories=" + URLEncoder.encode(categories, "UTF-8")
                    + "&actionTypes=" + URLEncoder.encode("[\"skip\"]", "UTF-8"));
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("User-Agent", "NX-YouTube");
            if (connection.getResponseCode() != 200) return result; // 404: no segments.
            JSONArray videos = new JSONArray(read(connection.getInputStream()));
            for (int i = 0; i < videos.length(); i++) {
                JSONObject video = videos.getJSONObject(i);
                if (!videoId.equals(video.optString("videoID"))) continue;
                JSONArray list = video.getJSONArray("segments");
                for (int j = 0; j < list.length(); j++) {
                    JSONObject s = list.getJSONObject(j);
                    JSONArray range = s.getJSONArray("segment");
                    long start = Math.round(range.getDouble(0) * 1000);
                    long end = Math.round(range.getDouble(1) * 1000);
                    if (end - start >= 1000) result.add(new Segment(start, end, s.optString("category")));
                }
            }
        } catch (Throwable t) {
            Nx.log("SponsorBlock fetch failed", t);
        } finally {
            if (connection != null) connection.disconnect();
        }
        return result;
    }

    private static String enabledCategories() {
        JSONArray array = new JSONArray();
        if (Nx.bool(NxSettings.SB_SPONSOR)) array.put("sponsor");
        if (Nx.bool(NxSettings.SB_SELFPROMO)) array.put("selfpromo");
        if (Nx.bool(NxSettings.SB_INTERACTION)) array.put("interaction");
        if (Nx.bool(NxSettings.SB_INTRO)) array.put("intro");
        if (Nx.bool(NxSettings.SB_OUTRO)) array.put("outro");
        if (Nx.bool(NxSettings.SB_PREVIEW)) array.put("preview");
        if (Nx.bool(NxSettings.SB_FILLER)) array.put("filler");
        if (Nx.bool(NxSettings.SB_MUSIC)) array.put("music_offtopic");
        return array.toString();
    }

    private static String label(String category) {
        switch (category) {
            case "sponsor": return Nx.t("sponsor", "sponsor");
            case "selfpromo": return Nx.t("kendi tanıtımı", "self promotion");
            case "interaction": return Nx.t("abone ol hatırlatması", "interaction reminder");
            case "intro": return Nx.t("giriş", "intro");
            case "outro": return Nx.t("kapanış", "outro");
            case "preview": return Nx.t("önizleme", "preview");
            case "filler": return Nx.t("dolgu", "filler");
            case "music_offtopic": return Nx.t("müzik dışı bölüm", "non-music section");
            default: return category;
        }
    }

    private static String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    private static String read(InputStream in) throws Exception {
        try (InputStream input = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = input.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toString("UTF-8");
        }
    }
}
