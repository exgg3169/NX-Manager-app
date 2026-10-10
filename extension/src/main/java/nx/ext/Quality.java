package nx.ext;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;

/** Picks the initial video quality from the user's Wi-Fi / mobile data preference. */
@SuppressWarnings("unused")
public final class Quality {
    private Quality() {
    }

    /**
     * Injection point: YouTube's initial fixed resolution (an Optional of the resolution, e.g. 1080).
     * Returns the original value when the user chose automatic quality.
     */
    public static Object initialResolution(Object original) {
        try {
            if (!Features.has(Features.QUALITY)) return original;
            String value = Nx.str(onWifi() ? NxSettings.QUALITY_WIFI : NxSettings.QUALITY_MOBILE);
            if ("auto".equals(value) || original == null) return original;
            // YouTube ships its own Optional class; create the value through it.
            return original.getClass().getMethod("of", Object.class).invoke(null, Integer.valueOf(value));
        } catch (Throwable t) {
            Nx.log("Quality override failed", t);
            return original;
        }
    }

    private static boolean onWifi() {
        try {
            ConnectivityManager cm = (ConnectivityManager) Nx.context().getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return caps != null && (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET));
        } catch (Throwable t) {
            return true;
        }
    }
}
