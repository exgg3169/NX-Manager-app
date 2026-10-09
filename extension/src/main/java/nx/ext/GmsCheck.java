package nx.ext;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;

import java.util.Locale;

/**
 * Runs inside the patched YouTube. Explains what to do when microG (GmsCore), which the
 * patched app needs for sign-in and other Google services, is missing or restricted.
 */
@SuppressWarnings("unused")
public final class GmsCheck {
    private static final String GMS_PACKAGE = "app.revanced.android.gms";
    private static final String DOWNLOAD_URL = "https://github.com/MorpheApp/MicroG-RE/releases/latest";

    private static boolean checked;

    private GmsCheck() {
    }

    /** Called at the start of the main activity's onCreate. */
    public static void check(final Activity activity) {
        if (checked) return;
        checked = true;
        // Show dialogs after the activity is set up.
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    runCheck(activity);
                } catch (Throwable ignored) {
                    // Never crash the host app because of this check.
                }
            }
        });
    }

    private static void runCheck(final Activity activity) {
        if (activity.isFinishing()) return;
        boolean turkish = "tr".equals(Locale.getDefault().getLanguage());

        if (!isInstalled(activity)) {
            new AlertDialog.Builder(activity)
                    .setTitle(turkish ? "microG gerekli" : "microG required")
                    .setMessage(turkish
                            ? "Bu YouTube sürümü giriş yapmak ve Google hizmetleri için microG (GmsCore) kullanır, ancak cihazda kurulu değil. Lütfen microG'yi kurun."
                            : "This YouTube build uses microG (GmsCore) for sign-in and Google services, but it is not installed. Please install microG.")
                    .setPositiveButton(turkish ? "İndir" : "Download", (dialog, which) -> open(activity,
                            new Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL))))
                    .setNegativeButton(turkish ? "Kapat" : "Close", null)
                    .show();
            return;
        }

        PowerManager power = (PowerManager) activity.getSystemService(Activity.POWER_SERVICE);
        if (power != null && !power.isIgnoringBatteryOptimizations(GMS_PACKAGE)) {
            new AlertDialog.Builder(activity)
                    .setTitle(turkish ? "microG pil kısıtlaması" : "microG battery restriction")
                    .setMessage(turkish
                            ? "Bildirimlerin gelmesi için microG'nin pil optimizasyonu kapatılmalı. Açılan listede microG'yi bulup \"Optimize etme\" seçin."
                            : "Turn off battery optimization for microG so notifications arrive. Find microG in the list and choose \"Don't optimize\".")
                    .setPositiveButton(turkish ? "Ayarla" : "Settings", (dialog, which) -> {
                        open(activity, new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    })
                    .setNegativeButton(turkish ? "Sonra" : "Later", null)
                    .show();
        }
    }

    private static boolean isInstalled(Activity activity) {
        try {
            activity.getPackageManager().getPackageInfo(GMS_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private static void open(Activity activity, Intent intent) {
        try {
            activity.startActivity(intent);
        } catch (Exception ignored) {
        }
    }
}
