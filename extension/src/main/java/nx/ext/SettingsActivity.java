package nx.ext;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Process;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

/** The "NX settings" screen, opened from YouTube's settings. Built in code, it needs no resources. */
public final class SettingsActivity extends Activity {
    private LinearLayout list;
    private boolean dark;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        dark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        setTheme(dark ? android.R.style.Theme_DeviceDefault : android.R.style.Theme_DeviceDefault_Light);
        super.onCreate(savedInstanceState);
        setTitle(Nx.t("NX Ayarları", "NX settings"));

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(8), dp(16), dp(24));
        scroll.addView(list);
        setContentView(scroll);

        TextView header = text(Nx.t("NX YouTube", "NX YouTube"), 22, true);
        header.setPadding(0, dp(16), 0, dp(4));
        list.addView(header);
        list.addView(text(Nx.t("Değişikliklerin çoğu bir sonraki videoda geçerli olur. Bazıları için uygulamayı yeniden başlatın.",
                "Most changes apply from the next video. Some need an app restart."), 13, false));

        if (Features.has(Features.GIF)) {
            Button gif = new Button(this);
            gif.setText(Nx.t("GIF bul (yorum için)", "Find a GIF (for comments)"));
            gif.setOnClickListener(v -> startActivity(new Intent(this, GifActivity.class)));
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            gp.topMargin = dp(16);
            list.addView(gif, gp);
        }
        if (Features.has(Features.SPOOF_STREAMS)) addVrLogin();
        if (Features.has(Features.DIAGNOSTICS)) addDiagnostics();

        String section = null;
        for (NxSettings.Setting s : NxSettings.ALL) {
            if (s.feature != null && !Features.has(s.feature)) continue;
            if (!s.section.equals(section)) {
                section = s.section;
                TextView t = text(section, 15, true);
                t.setTextColor(accent());
                t.setPadding(0, dp(24), 0, dp(8));
                list.addView(t);
            }
            list.addView(s.values == null ? switchRow(s) : choiceRow(s));
        }

        Button restart = new Button(this);
        restart.setText(Nx.t("Uygulamayı yeniden başlat", "Restart app"));
        restart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restartApp();
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(24);
        list.addView(restart, lp);
    }

    /** Sign-in for the playback fix: YouTube often refuses streams to signed-out VR apps. */
    private void addVrLogin() {
        TextView title = text(Nx.t("Video akışı düzeltmesi", "Playback fix"), 15, true);
        title.setTextColor(accent());
        title.setPadding(0, dp(24), 0, dp(4));
        list.addView(title);
        list.addView(text(Nx.t("YouTube çoğu videoda \"bot olmadığınızı doğrulamak için oturum açın\" diyor. "
                        + "Bir kez oturum açın: ekranda çıkan kodu google.com/device adresine girin.",
                "YouTube often says \"sign in to confirm you're not a bot\". Sign in once: enter the code "
                        + "shown here at google.com/device."), 13, false));
        final Button button = new Button(this);
        final Runnable update = () -> button.setText(VrLogin.signedIn()
                ? Nx.t("Oturum açık ✓ — kapatmak için dokunun", "Signed in ✓ — tap to sign out")
                : Nx.t("Akış düzeltmesi için oturum aç", "Sign in for the playback fix"));
        update.run();
        button.setOnClickListener(v -> {
            if (VrLogin.signedIn()) {
                VrLogin.signOut();
                update.run();
                return;
            }
            button.setEnabled(false);
            new Thread(() -> {
                try {
                    final VrLogin.Code code = VrLogin.requestCode();
                    final AlertDialog[] dialog = new AlertDialog[1];
                    runOnUiThread(() -> {
                        button.setEnabled(true);
                        TextView codeView = text(code.userCode, 30, true);
                        codeView.setGravity(Gravity.CENTER);
                        codeView.setTextIsSelectable(true);
                        codeView.setPadding(0, dp(16), 0, dp(16));
                        dialog[0] = new AlertDialog.Builder(this)
                                .setTitle(Nx.t("Kodu girin", "Enter the code"))
                                .setMessage(Nx.t("1. Kodu kopyalayın\n2. " + code.verificationUrl + " adresini açın\n3. Kodu girip hesabınızı seçin\nOnaydan sonra bu pencere kendiliğinden kapanır.",
                                        "1. Copy the code\n2. Open " + code.verificationUrl + "\n3. Enter the code and pick your account\nThis window closes by itself once approved."))
                                .setView(codeView)
                                .setPositiveButton(Nx.t("Kopyala ve aç", "Copy and open"), null)
                                .setNegativeButton(android.R.string.cancel, null)
                                .show();
                        dialog[0].getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
                            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("NX", code.userCode));
                            try {
                                startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(code.verificationUrl)));
                            } catch (Throwable t) {
                                Nx.toast(code.verificationUrl);
                            }
                        });
                    });
                    final String error = VrLogin.waitForApproval(code);
                    runOnUiThread(() -> {
                        if (dialog[0] != null) dialog[0].dismiss();
                        update.run();
                        Nx.toast(error == null ? Nx.t("Oturum açıldı ✓", "Signed in ✓")
                                : Nx.t("Oturum açılamadı: ", "Sign-in failed: ") + error);
                    });
                } catch (Throwable t) {
                    runOnUiThread(() -> {
                        button.setEnabled(true);
                        Nx.toast(Nx.t("Bağlantı hatası: ", "Connection error: ") + t.getMessage());
                    });
                }
            }).start();
        });
        list.addView(button);
    }

    /** Test panel: token state, recent player errors and a microG service check. */
    private void addDiagnostics() {
        TextView title = text(Nx.t("Tanılama / Test", "Diagnostics / Test"), 15, true);
        title.setTextColor(accent());
        title.setPadding(0, dp(24), 0, dp(4));
        list.addView(title);
        list.addView(text(Nx.t("Bir video açın, durana kadar izleyin, sonra buraya dönüp ekran görüntüsü alın.",
                "Play a video until it stops, then come back here and take a screenshot."), 13, false));

        final TextView report = text(Diagnostics.report(), 13, false);
        report.setTypeface(Typeface.MONOSPACE);
        report.setTextIsSelectable(true);
        report.setPadding(0, dp(8), 0, dp(8));
        list.addView(report);

        final TextView serviceResult = text("", 13, true);
        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button test = new Button(this);
        test.setText(Nx.t("microG jeton servisini test et", "Test microG token service"));
        test.setOnClickListener(v -> {
            serviceResult.setText(Nx.t("Test ediliyor…", "Testing…"));
            Diagnostics.testTokenService(getApplicationContext(), message -> runOnUiThread(() -> {
                serviceResult.setText(message);
                report.setText(Diagnostics.report());
            }));
        });
        Button refresh = new Button(this);
        refresh.setText(Nx.t("Yenile", "Refresh"));
        refresh.setOnClickListener(v -> report.setText(Diagnostics.report()));
        Button clear = new Button(this);
        clear.setText(Nx.t("Temizle", "Clear"));
        clear.setOnClickListener(v -> {
            Diagnostics.clear();
            report.setText(Diagnostics.report());
        });
        Button copy = new Button(this);
        copy.setText(Nx.t("Hataları kopyala", "Copy errors"));
        copy.setOnClickListener(v -> {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("NX", Diagnostics.fullReport(this)));
            Nx.toast(Nx.t("Panoya kopyalandı", "Copied to clipboard"));
        });
        buttons.addView(copy);
        buttons.addView(refresh);
        buttons.addView(clear);
        list.addView(test);
        list.addView(serviceResult);
        list.addView(buttons);
    }

    private View switchRow(final NxSettings.Setting s) {
        LinearLayout row = row();
        LinearLayout texts = texts(s.title, s.summary);
        final Switch toggle = new Switch(this);
        toggle.setChecked(Nx.bool(s));
        toggle.setClickable(false);
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(toggle);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean value = !toggle.isChecked();
                toggle.setChecked(value);
                Nx.put(s, value);
            }
        });
        return row;
    }

    private View choiceRow(final NxSettings.Setting s) {
        LinearLayout row = row();
        final LinearLayout texts = texts(s.title, labelOf(s, Nx.str(s)));
        row.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int checked = indexOf(s.values, Nx.str(s));
                new AlertDialog.Builder(SettingsActivity.this)
                        .setTitle(s.title)
                        .setSingleChoiceItems(s.labels, checked, (dialog, which) -> {
                            Nx.put(s, s.values[which]);
                            ((TextView) texts.getChildAt(1)).setText(s.labels[which]);
                            dialog.dismiss();
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
            }
        });
        return row;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        row.setMinimumHeight(dp(56));
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackground, ripple, true);
        row.setBackgroundResource(ripple.resourceId);
        return row;
    }

    private LinearLayout texts(String title, String summary) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(text(title, 16, false));
        TextView sub = text(summary == null ? "" : summary, 13, false);
        sub.setAlpha(0.7f);
        sub.setVisibility(summary == null ? View.GONE : View.VISIBLE);
        box.addView(sub);
        return box;
    }

    private TextView text(String value, int sp, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private int accent() {
        return dark ? Color.parseColor("#FF8A80") : Color.parseColor("#D32F2F");
    }

    private static String labelOf(NxSettings.Setting s, String value) {
        int i = indexOf(s.values, value);
        return i >= 0 ? s.labels[i] : value;
    }

    private static int indexOf(String[] values, String value) {
        for (int i = 0; i < values.length; i++) if (values[i].equals(value)) return i;
        return -1;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void restartApp() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
        }
        finishAffinity();
        Process.killProcess(Process.myPid());
    }
}
