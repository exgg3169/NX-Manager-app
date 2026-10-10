package nx.ext;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Google sign-in for the playback fix. Without an account YouTube often answers the VR app with
 * "sign in to confirm you're not a bot". Uses the device flow of the YouTube VR app: the user
 * enters a short code at google.com/device; only a refresh token is stored, on the device.
 */
public final class VrLogin {
    private VrLogin() {
    }

    // The YouTube VR app's public OAuth client (installed apps cannot keep a secret).
    private static final String CLIENT_ID = "652469312169-4lvs9bnhr9lpns9v451j5oivd81vjvu1.apps.googleusercontent.com";
    private static final String CLIENT_SECRET = "3fTWrBJI5Uojm1TK7_iJCW5Z";
    private static final String SCOPE = "https://www.googleapis.com/auth/youtube";
    private static final String DEVICE_CODE_URL = "https://oauth2.googleapis.com/device/code";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String REVOKE_URL = "https://oauth2.googleapis.com/revoke?token=";

    private static final String KEY_REFRESH = "vr_refresh_token";

    private static String accessToken;
    private static long accessTokenExpiry;

    public static final class Code {
        public final String userCode, verificationUrl, deviceCode;
        public final int interval, expiresIn;

        Code(JSONObject json) {
            userCode = json.optString("user_code");
            verificationUrl = json.optString("verification_url", "https://www.google.com/device");
            deviceCode = json.optString("device_code");
            interval = Math.max(5, json.optInt("interval", 5));
            expiresIn = json.optInt("expires_in", 1800);
        }
    }

    public static boolean signedIn() {
        return !Nx.prefs().getString(KEY_REFRESH, "").isEmpty();
    }

    /** Step 1 (background thread): asks Google for a code the user enters at google.com/device. */
    public static Code requestCode() throws Exception {
        JSONObject body = new JSONObject()
                .put("client_id", CLIENT_ID)
                .put("scope", SCOPE)
                .put("device_id", UUID.randomUUID().toString())
                .put("device_model", "Quest 3");
        return new Code(post(DEVICE_CODE_URL, body));
    }

    /**
     * Step 2 (background thread): waits until the user approved the code. Returns null when
     * signed in, otherwise the reason it failed.
     */
    public static String waitForApproval(Code code) throws Exception {
        long deadline = System.currentTimeMillis() + code.expiresIn * 1000L;
        int interval = code.interval;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(interval * 1000L);
            JSONObject json = post(TOKEN_URL, new JSONObject()
                    .put("client_id", CLIENT_ID)
                    .put("client_secret", CLIENT_SECRET)
                    .put("code", code.deviceCode)
                    .put("grant_type", "http://oauth.net/grant_type/device/1.0"));
            String error = json.optString("error", "");
            if (error.isEmpty() && json.has("refresh_token")) {
                Nx.prefs().edit().putString(KEY_REFRESH, json.getString("refresh_token")).apply();
                remember(json);
                StreamSpoof.clearCache();
                return null;
            }
            if (error.equals("slow_down")) interval += 5;
            else if (!error.equals("authorization_pending")) return error.isEmpty() ? "unknown" : error;
        }
        return "expired";
    }

    public static void signOut() {
        final String token = Nx.prefs().getString(KEY_REFRESH, "");
        Nx.prefs().edit().remove(KEY_REFRESH).apply();
        synchronized (VrLogin.class) {
            accessToken = null;
            accessTokenExpiry = 0;
        }
        StreamSpoof.clearCache();
        if (token.isEmpty()) return;
        new Thread(() -> {
            try {
                HttpURLConnection c = (HttpURLConnection) new URL(REVOKE_URL + token).openConnection();
                c.setRequestMethod("POST");
                c.getResponseCode();
                c.disconnect();
            } catch (Throwable ignored) {
            }
        }).start();
    }

    /** The Authorization header value, or null when not signed in. Call off the main thread. */
    static synchronized String authorization() {
        String refresh = Nx.prefs().getString(KEY_REFRESH, "");
        if (refresh.isEmpty()) return null;
        if (accessToken == null || System.currentTimeMillis() > accessTokenExpiry) {
            try {
                JSONObject json = post(TOKEN_URL, new JSONObject()
                        .put("client_id", CLIENT_ID)
                        .put("client_secret", CLIENT_SECRET)
                        .put("refresh_token", refresh)
                        .put("grant_type", "refresh_token"));
                if (json.has("access_token")) {
                    remember(json);
                } else {
                    Diagnostics.onSpoof(null, false, Nx.t("Oturum yenilenemedi: ", "Sign-in refresh failed: ") + json.optString("error"));
                    if ("invalid_grant".equals(json.optString("error"))) Nx.prefs().edit().remove(KEY_REFRESH).apply();
                    return null;
                }
            } catch (Exception e) {
                Nx.log("Token refresh failed", e);
                return null;
            }
        }
        return "Bearer " + accessToken;
    }

    private static synchronized void remember(JSONObject json) {
        accessToken = json.optString("access_token");
        // Renew a minute early.
        accessTokenExpiry = System.currentTimeMillis() + (json.optLong("expires_in", 3600) - 60) * 1000L;
    }

    private static JSONObject post(String url, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(10000);
            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = c.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            if (in != null) {
                try (InputStream stream = in) {
                    byte[] buffer = new byte[4096];
                    int n;
                    while ((n = stream.read(buffer)) > 0) bytes.write(buffer, 0, n);
                }
            }
            String text = bytes.toString("UTF-8");
            return text.isEmpty() ? new JSONObject() : new JSONObject(text);
        } finally {
            c.disconnect();
        }
    }
}
