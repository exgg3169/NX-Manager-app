package nx.ext;

import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Video playback fix. YouTube's own stream data is bound to a server-checked session (SABR with
 * attestation); on some devices the server rejects it and videos stop. The stream data is
 * replaced with the one YouTube gives its VR app, which uses plain stream URLs.
 */
@SuppressWarnings("unused")
public final class StreamSpoof {
    private StreamSpoof() {
    }

    private static final String CLIENT_NAME = "ANDROID_VR";
    private static final int CLIENT_ID = 28;
    private static final String CLIENT_VERSION = "1.43.32";
    private static final String USER_AGENT = "com.google.android.apps.youtube.vr.oculus/" + CLIENT_VERSION
            + " (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip";
    private static final String PLAYER_URL = "https://youtubei.googleapis.com/youtubei/v1/player?alt=proto";

    private static final long CACHE_MS = 30 * 60 * 1000;
    private static final int TIMEOUT_MS = 6000;

    private static final class Entry {
        final long at = System.currentTimeMillis();
        final byte[] streamingData; // null when the VR app gets no playable streams either

        Entry(byte[] streamingData) {
            this.streamingData = streamingData;
        }
    }

    private static final Map<String, Entry> cache = new LinkedHashMap<String, Entry>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > 30;
        }
    };
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();
    private static Method parseFrom;
    private static Object defaultInstance;

    /**
     * Injection point: a player response is being read. Returns the stream data to use instead of
     * [original]; [playerResponse] has an added {@code nxVideoId()} method.
     */
    public static Object streamingData(Object original, Object playerResponse) {
        try {
            if (original == null || !Features.has(Features.SPOOF_STREAMS) || !Nx.bool(NxSettings.SPOOF_STREAMS)) return original;
            String videoId = (String) playerResponse.getClass().getMethod("nxVideoId").invoke(playerResponse);
            if (videoId == null || videoId.isEmpty()) return original;

            Entry entry = cached(videoId);
            if (entry == null) {
                if (Looper.myLooper() == Looper.getMainLooper()) {
                    // No network on the main thread; have it ready next time.
                    prefetch(videoId);
                    return original;
                }
                entry = fetch(videoId);
            }
            if (entry.streamingData == null) return original;
            Object replacement = parse(original.getClass(), entry.streamingData);
            Diagnostics.onSpoof(videoId, true, null);
            return replacement;
        } catch (Throwable t) {
            Nx.log("Stream spoof failed", t);
            Diagnostics.onSpoof(null, false, String.valueOf(t));
            return original;
        }
    }

    private static Entry cached(String videoId) {
        synchronized (cache) {
            Entry e = cache.get(videoId);
            if (e != null && System.currentTimeMillis() - e.at < CACHE_MS) return e;
            return null;
        }
    }

    /** Fetches a video's streams ahead of time, e.g. as soon as its id is known. */
    static void prefetch(final String videoId) {
        if (videoId == null || !Features.has(Features.SPOOF_STREAMS) || !Nx.bool(NxSettings.SPOOF_STREAMS)) return;
        executor.execute(() -> {
            try {
                if (cached(videoId) == null) fetch(videoId);
            } catch (Throwable t) {
                Nx.log("Stream prefetch failed", t);
                Diagnostics.onSpoof(videoId, false, String.valueOf(t));
            }
        });
    }

    private static Entry fetch(String videoId) throws Exception {
        byte[] response = request(videoId);
        byte[] streamingData = null;
        String problem = null;
        int status = playabilityStatus(response);
        if (status != 0) {
            problem = "VR status " + status;
        } else {
            streamingData = field(response, 4);
            if (streamingData == null) problem = Nx.t("VR yanıtında akış yok", "no streams in VR response");
        }
        if (problem != null) Diagnostics.onSpoof(videoId, false, problem);
        Entry entry = new Entry(streamingData);
        synchronized (cache) {
            cache.put(videoId, entry);
        }
        return entry;
    }

    private static byte[] request(String videoId) throws Exception {
        Locale locale = Locale.getDefault();
        String body = "{\"context\":{\"client\":{"
                + "\"clientName\":\"" + CLIENT_NAME + "\",\"clientVersion\":\"" + CLIENT_VERSION + "\","
                + "\"deviceMake\":\"Oculus\",\"deviceModel\":\"Quest 3\",\"osName\":\"Android\",\"osVersion\":\"12L\","
                + "\"androidSdkVersion\":32,\"hl\":\"" + locale.getLanguage() + "\",\"gl\":\"" + locale.getCountry() + "\"}},"
                + "\"videoId\":\"" + videoId + "\",\"contentCheckOk\":true,\"racyCheckOk\":true}";
        HttpURLConnection c = (HttpURLConnection) new URL(PLAYER_URL).openConnection();
        try {
            c.setConnectTimeout(TIMEOUT_MS);
            c.setReadTimeout(TIMEOUT_MS);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            c.setRequestProperty("User-Agent", USER_AGENT);
            c.setRequestProperty("X-YouTube-Client-Name", String.valueOf(CLIENT_ID));
            c.setRequestProperty("X-YouTube-Client-Version", CLIENT_VERSION);
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            if (code != 200) throw new IllegalStateException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[16384];
                int n;
                while ((n = in.read(buffer)) > 0) bytes.write(buffer, 0, n);
                return bytes.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    /** Parses stream data bytes with the app's own protobuf class. */
    private static synchronized Object parse(Class<?> type, byte[] bytes) throws Exception {
        if (parseFrom == null) {
            defaultInstance = type.getMethod("getDefaultInstance").invoke(null);
            Class<?> base = type.getSuperclass();
            Method found = null;
            for (Class<?> c = base; c != null && found == null; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (m.getName().equals("parseFrom") && p.length == 2 && p[1] == byte[].class && p[0].isAssignableFrom(type)) {
                        found = m;
                        break;
                    }
                }
            }
            if (found == null) throw new NoSuchMethodException("parseFrom");
            found.setAccessible(true);
            parseFrom = found;
        }
        return parseFrom.invoke(null, defaultInstance, bytes);
    }

    // region Protobuf reading

    /** Status of the playability status message (field 2); 0 is OK. */
    private static int playabilityStatus(byte[] response) {
        byte[] status = field(response, 2);
        if (status == null) return -1;
        long[] value = varintField(status, 1);
        return value == null ? 0 : (int) value[0]; // proto3 default: OK
    }

    /** The bytes of the first length-delimited field [number], or null. */
    private static byte[] field(byte[] message, int number) {
        int[] pos = {0};
        while (pos[0] < message.length) {
            long key = varint(message, pos);
            int type = (int) (key & 7);
            if (type == 2) {
                int length = (int) varint(message, pos);
                if ((key >>> 3) == number) {
                    byte[] out = new byte[length];
                    System.arraycopy(message, pos[0], out, 0, length);
                    return out;
                }
                pos[0] += length;
            } else if (!skip(message, pos, type)) {
                return null;
            }
        }
        return null;
    }

    private static long[] varintField(byte[] message, int number) {
        int[] pos = {0};
        while (pos[0] < message.length) {
            long key = varint(message, pos);
            int type = (int) (key & 7);
            if (type == 0) {
                long v = varint(message, pos);
                if ((key >>> 3) == number) return new long[]{v};
            } else if (type == 2) {
                pos[0] += (int) varint(message, pos);
            } else if (!skip(message, pos, type)) {
                return null;
            }
        }
        return null;
    }

    private static boolean skip(byte[] message, int[] pos, int type) {
        switch (type) {
            case 0:
                varint(message, pos);
                return true;
            case 1:
                pos[0] += 8;
                return true;
            case 5:
                pos[0] += 4;
                return true;
            default:
                return false;
        }
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

    // endregion
}
