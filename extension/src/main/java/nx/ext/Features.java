package nx.ext;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

/** Which NX features were patched in; the patcher lists them in the manifest meta-data "nx.features". */
public final class Features {
    private Features() {
    }

    public static final String FEED_ADS = "feed_ads";
    public static final String SHORTS = "shorts";
    public static final String QUALITY = "quality";
    public static final String SPONSORBLOCK = "sponsorblock";

    private static String list;

    public static boolean has(String feature) {
        if (list == null) {
            list = "";
            try {
                Context c = Nx.context();
                ApplicationInfo info = c.getPackageManager().getApplicationInfo(c.getPackageName(), PackageManager.GET_META_DATA);
                if (info.metaData != null) list = "," + info.metaData.getString("nx.features", "") + ",";
            } catch (Throwable t) {
                Nx.log("Features unknown", t);
            }
        }
        return list.contains("," + feature + ",");
    }
}
