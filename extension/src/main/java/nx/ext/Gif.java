package nx.ext;

import android.util.Base64;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * GIF comments. YouTube accepts a Tenor GIF id ("tenorId") with a new comment or reply, but this
 * app version has no field for it. When the text of a comment holds a Tenor link, the app's own
 * request (same headers and sign-in) is sent as JSON with the GIF id, and the answer is asked for
 * as protobuf so the app reads it as usual.
 */
@SuppressWarnings("unused")
public final class Gif {
    private Gif() {
    }

    private static final Pattern VIEW_LINK = Pattern.compile(
            "(?:https?://)?(?:www\\.)?tenor\\.com/(?:[a-z]{2}(?:-[A-Z]{2})?/)?view/[^\\s]*?-(\\d{6,20})\\b[^\\s]*");
    private static final Pattern MEDIA_LINK = Pattern.compile(
            "(?:https?://)?media\\d*\\.tenor\\.com/([A-Za-z0-9_-]{16})[^\\s]*");

    static final class Rewrite {
        final byte[] body; // null when the request is left alone

        Rewrite(byte[] body) {
            this.body = body;
        }
    }

    private static final Map<Object, Rewrite> decisions = new WeakHashMap<>();
    private static final ThreadLocal<Boolean> deciding = new ThreadLocal<>();

    static boolean enabled() {
        return Features.has(Features.GIF) && Nx.bool(NxSettings.GIF_COMMENTS);
    }

    // region Injection points (the InnerTube request class)

    /** The request URL. */
    public static String url(Object request, String url) {
        Rewrite r = decide(request);
        if (r == null || r.body == null || url == null) return url;
        return url + (url.contains("?") ? "&" : "?") + "alt=proto";
    }

    /** The request headers; the map is changed in place. */
    public static void headers(Object request, Map<String, String> headers) {
        Rewrite r = decide(request);
        if (r == null || r.body == null || headers == null) return;
        for (String key : headers.keySet().toArray(new String[0])) {
            if (key.equalsIgnoreCase("Content-Type")) headers.remove(key);
        }
        headers.put("Content-Type", "application/json");
    }

    /** The request body. */
    public static byte[] body(Object request, byte[] body) {
        Rewrite r = decide(request);
        return r == null || r.body == null ? body : r.body;
    }

    // endregion

    private static Rewrite decide(Object request) {
        if (request == null || Boolean.TRUE.equals(deciding.get())) return null;
        synchronized (decisions) {
            Rewrite known = decisions.get(request);
            if (known != null) return known;
        }
        Rewrite result = new Rewrite(null);
        if (enabled()) {
            deciding.set(true);
            try {
                result = build(request);
            } catch (Throwable t) {
                Nx.log("GIF comment failed", t);
            } finally {
                deciding.set(false);
            }
        }
        synchronized (decisions) {
            decisions.put(request, result);
        }
        return result;
    }

    private static Rewrite build(Object request) throws Exception {
        Class<?> c = request.getClass();
        String url = (String) c.getMethod("nxUrl").invoke(request);
        if (url == null) return new Rewrite(null);
        String path = android.net.Uri.parse(url).getPath();
        if (path == null) return new Rewrite(null);
        boolean reply = path.endsWith("/comment/create_comment_reply");
        if (!reply && !path.endsWith("/comment/create_comment")) return new Rewrite(null);

        byte[] raw = (byte[]) c.getMethod("nxBody").invoke(request);
        if (raw == null) return new Rewrite(null);
        boolean gzip = raw.length > 2 && (raw[0] & 0xFF) == 0x1F && (raw[1] & 0xFF) == 0x8B;
        byte[] proto = gzip ? gunzip(raw) : raw;

        // The request holds the context (field 1) and two strings: the text and the params.
        String a = Proto.string(proto, 2), b = Proto.string(proto, 3);
        String text, params;
        String idA = tenorId(a), idB = tenorId(b);
        String id;
        if (idA != null) {
            id = idA;
            text = a;
            params = b;
        } else if (idB != null) {
            id = idB;
            text = b;
            params = a;
        } else {
            return new Rewrite(null);
        }
        if (params == null || params.isEmpty()) return new Rewrite(null);

        String rest = removeLinks(text).trim();
        Locale locale = Locale.getDefault();
        JSONObject client = new JSONObject()
                .put("clientName", "ANDROID")
                .put("clientVersion", appVersion())
                .put("androidSdkVersion", android.os.Build.VERSION.SDK_INT)
                .put("osName", "Android")
                .put("osVersion", android.os.Build.VERSION.RELEASE)
                .put("hl", locale.getLanguage())
                .put("gl", locale.getCountry());
        JSONObject json = new JSONObject()
                .put("context", new JSONObject().put("client", client))
                .put(reply ? "createReplyParams" : "createCommentParams", params)
                .put("tenorId", id);
        if (!rest.isEmpty()) json.put("commentText", rest);

        Diagnostics.note(Nx.t("GIF yorumu gönderiliyor (Tenor ", "Sending GIF comment (Tenor ") + id + ")");
        byte[] out = json.toString().getBytes(StandardCharsets.UTF_8);
        return new Rewrite(gzip ? gzip(out) : out);
    }

    // region Tenor links

    /** The Tenor GIF id in [text], or null. */
    static String tenorId(String text) {
        if (text == null || !text.contains("tenor.com")) return null;
        Matcher view = VIEW_LINK.matcher(text);
        if (view.find()) return view.group(1);
        Matcher media = MEDIA_LINK.matcher(text);
        if (media.find()) return mediaIdToGifId(media.group(1));
        return null;
    }

    private static String removeLinks(String text) {
        return MEDIA_LINK.matcher(VIEW_LINK.matcher(text).replaceAll("")).replaceAll("");
    }

    /** Media file ids start with the 64-bit GIF id in URL-safe base64. */
    static String mediaIdToGifId(String mediaId) {
        try {
            byte[] bytes = Base64.decode(mediaId.substring(0, 11) + "=", Base64.URL_SAFE | Base64.NO_WRAP);
            if (bytes.length < 8) return null;
            long n = 0;
            for (int i = 0; i < 8; i++) n = (n << 8) | (bytes[i] & 0xFF);
            return Long.toUnsignedString(n);
        } catch (Throwable t) {
            return null;
        }
    }

    /** The link NX's GIF picker copies; it is turned into a GIF when the comment is sent. */
    static String link(String gifId) {
        return "https://tenor.com/view/gif-" + gifId;
    }

    // endregion

    private static String appVersion() {
        try {
            return Nx.context().getPackageManager().getPackageInfo(Nx.context().getPackageName(), 0).versionName;
        } catch (Throwable t) {
            return "21.16.256";
        }
    }

    private static byte[] gunzip(byte[] data) throws Exception {
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            return out.toByteArray();
        }
    }

    private static byte[] gzip(byte[] data) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream zip = new GZIPOutputStream(out)) {
            zip.write(data);
        }
        return out.toByteArray();
    }

    /** Minimal protobuf reading. */
    static final class Proto {
        static String string(byte[] message, int number) {
            int[] pos = {0};
            while (pos[0] < message.length) {
                long key = varint(message, pos);
                int type = (int) (key & 7);
                if (type == 2) {
                    int length = (int) varint(message, pos);
                    if ((key >>> 3) == number) return new String(message, pos[0], length, StandardCharsets.UTF_8);
                    pos[0] += length;
                } else if (type == 0) {
                    varint(message, pos);
                } else if (type == 1) {
                    pos[0] += 8;
                } else if (type == 5) {
                    pos[0] += 4;
                } else {
                    return null;
                }
            }
            return null;
        }

        private static long varint(byte[] b, int[] pos) {
            long result = 0;
            int shift = 0;
            while (pos[0] < b.length) {
                int c = b[pos[0]++] & 0xFF;
                result |= (long) (c & 0x7F) << shift;
                if (c < 0x80) break;
                shift += 7;
            }
            return result;
        }
    }
}
