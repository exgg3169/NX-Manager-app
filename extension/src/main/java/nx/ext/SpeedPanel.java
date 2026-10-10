package nx.ext;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.Locale;

/**
 * Replaces YouTube's playback speed panel, which offers speeds above 2x only to Premium members,
 * with NX's own panel (0.25x–4x). The Litho filter reports when YouTube's panel is built; the
 * next list drawn in a separate window is that panel, which is then closed.
 */
@SuppressWarnings("unused")
public final class SpeedPanel {
    private SpeedPanel() {
    }

    private static final String PANEL_PATH = "playback_rate_selector_menu_sheet";
    private static final float MIN = 0.25f, MAX = 4f, STEP = 0.05f;
    private static final float[] PRESETS = {0.5f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f};

    private static volatile long panelSeenAt;
    private static long shownAt;
    private static WeakReference<Dialog> current = new WeakReference<>(null);

    static boolean enabled() {
        return Features.has(Features.SPEED_MENU) && Nx.bool(NxSettings.SPEED_MENU);
    }

    /** From the Litho filter: a component with [path] is being built. */
    static void onComponent(String path) {
        if (path.contains(PANEL_PATH) && enabled()) panelSeenAt = SystemClock.elapsedRealtime();
    }

    /** Injection point: a Litho list was created. */
    public static void onRecyclerView(final View list) {
        if (list == null || !enabled()) return;
        list.getViewTreeObserver().addOnDrawListener(new ViewTreeObserver.OnDrawListener() {
            @Override
            public void onDraw() {
                if (panelSeenAt == 0 || SystemClock.elapsedRealtime() - panelSeenAt > 3000) return;
                // Changing views is not allowed while drawing.
                list.post(() -> replace(list));
            }
        });
    }

    private static void replace(View list) {
        try {
            if (panelSeenAt == 0 || !list.isShown()) return;
            Activity activity = activity(list.getContext());
            if (activity == null) return;
            View root = list.getRootView();
            if (root != activity.getWindow().getDecorView()) {
                // A bottom sheet in its own window: Back closes it like the user would.
                panelSeenAt = 0;
                root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
                root.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
            } else {
                // A sheet inside the main window. The panel's list holds one row of five parts
                // (title, slider, buttons, presets, footer); other lists of the screen do not.
                if (!(list instanceof ViewGroup) || ((ViewGroup) list).getChildCount() == 0) return;
                View first = ((ViewGroup) list).getChildAt(0);
                if (!(first instanceof ViewGroup) || ((ViewGroup) first).getChildCount() != 5) {
                    Diagnostics.note(Nx.t("Hız paneli tanınamadı (parça sayısı ", "Speed panel not recognized (parts ")
                            + (first instanceof ViewGroup ? ((ViewGroup) first).getChildCount() : -1) + ")");
                    return;
                }
                View sheet = parent(list, 3), container = parent(list, 4);
                if (sheet == null || container == null) return;
                panelSeenAt = 0;
                sheet.setVisibility(View.GONE);
                container.setVisibility(View.GONE);
            }
            show(activity);
        } catch (Throwable t) {
            Nx.log("Speed panel failed", t);
        }
    }

    private static View parent(View view, int levels) {
        for (int i = 0; i < levels && view != null; i++) {
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return view;
    }

    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    // region Panel

    static void show(Activity activity) {
        long now = SystemClock.elapsedRealtime();
        Dialog open = current.get();
        if (now - shownAt < 800 || (open != null && open.isShowing())) return;
        shownAt = now;

        boolean dark = (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        final int text = dark ? Color.WHITE : Color.BLACK;
        final int chip = dark ? 0xFF3A3A3A : 0xFFEDEDED;
        final int accent = dark ? 0xFFFF8A80 : 0xFFD32F2F;

        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 20));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(dark ? 0xFF212121 : Color.WHITE);
        float r = dp(activity, 16);
        bg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        box.setBackground(bg);

        View handle = new View(activity);
        GradientDrawable handleBg = new GradientDrawable();
        handleBg.setColor(dark ? 0xFF5A5A5A : 0xFFCCCCCC);
        handleBg.setCornerRadius(dp(activity, 2));
        handle.setBackground(handleBg);
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(activity, 40), dp(activity, 4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = dp(activity, 16);
        box.addView(handle, hp);

        final TextView value = new TextView(activity);
        value.setTextColor(text);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        value.setTypeface(Typeface.DEFAULT_BOLD);
        value.setGravity(Gravity.CENTER);
        box.addView(value);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final SeekBar bar = new SeekBar(activity);
        bar.setMax(Math.round((MAX - MIN) / STEP));
        Button minus = roundButton(activity, "−", text, chip);
        Button plus = roundButton(activity, "+", text, chip);
        row.addView(minus);
        row.addView(bar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(plus);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rp.topMargin = dp(activity, 8);
        box.addView(row, rp);

        final float[] speed = {clamp(Video.currentSpeed())};
        final Runnable refresh = () -> {
            value.setText(String.format(Locale.US, "%.2fx", speed[0]));
            bar.setProgress(Math.round((speed[0] - MIN) / STEP));
        };
        final java.util.function.Consumer<Float> apply = s -> {
            speed[0] = clamp(s);
            Video.setSpeedByUser(speed[0]);
            refresh.run();
        };
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (!fromUser) return;
                speed[0] = clamp(MIN + progress * STEP);
                value.setText(String.format(Locale.US, "%.2fx", speed[0]));
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                apply.accept(speed[0]);
            }
        });
        minus.setOnClickListener(v -> apply.accept(speed[0] - STEP));
        plus.setOnClickListener(v -> apply.accept(speed[0] + STEP));

        HorizontalScrollView scroll = new HorizontalScrollView(activity);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(activity);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        for (final float preset : PRESETS) {
            Button b = new Button(activity);
            b.setAllCaps(false);
            b.setText(label(preset));
            b.setTextColor(preset > 2f ? accent : text);
            b.setMinWidth(0);
            b.setMinimumWidth(dp(activity, 56));
            GradientDrawable c = new GradientDrawable();
            c.setColor(chip);
            c.setCornerRadius(dp(activity, 20));
            b.setBackground(c);
            b.setOnClickListener(v -> {
                apply.accept(preset);
                dialog.dismiss();
            });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(activity, 40));
            cp.rightMargin = dp(activity, 8);
            chips.addView(b, cp);
        }
        scroll.addView(chips);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(activity, 16);
        box.addView(scroll, sp);

        refresh.run();
        dialog.setContentView(box);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.setGravity(Gravity.BOTTOM);
            window.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT);
            window.getAttributes().windowAnimations = android.R.style.Animation_InputMethod;
        }
        current = new WeakReference<>(dialog);
        dialog.show();
    }

    private static Button roundButton(Context context, String symbol, int text, int fill) {
        Button b = new Button(context);
        b.setText(symbol);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        b.setTextColor(text);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        b.setBackground(d);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(0, 0, 0, 0);
        int size = dp(context, 48);
        b.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return b;
    }

    private static float clamp(float s) {
        float rounded = Math.round(s / STEP) * STEP;
        return Math.max(MIN, Math.min(MAX, rounded));
    }

    private static String label(float s) {
        return (s == (int) s ? String.valueOf((int) s) : String.valueOf(s)) + "x";
    }

    private static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    // endregion
}
