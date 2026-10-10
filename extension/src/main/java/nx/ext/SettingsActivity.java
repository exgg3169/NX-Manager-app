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
