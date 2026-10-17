package nx.ext;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Adds a "GIF" button next to the comment box of YouTube's comment and reply dialog. It opens NX's
 * GIF picker over the dialog and puts the chosen GIF's link into the comment.
 */
@SuppressWarnings("unused")
public final class GifButton {
    private GifButton() {
    }

    private static final String TAG = "nx_gif_button";
    /** View tag key that marks NX's own text fields. */
    static final int R_PICKER = 0x7e0a0001;

    /** Injection point: the comment dialog's view was created. */
    public static void attach(View root) {
        try {
            if (root == null || !Gif.enabled()) return;
            int id = root.getResources().getIdentifier("comment", "id", root.getContext().getPackageName());
            View found = id == 0 ? null : root.findViewById(id);
            if (!(found instanceof EditText)) return;
            final EditText input = (EditText) found;

            // The text sits in a scroll view inside the row that also holds the dialog's buttons.
            View scroll = (View) input.getParent();
            while (scroll != null && !(scroll.getParent() instanceof LinearLayout
                    && ((LinearLayout) scroll.getParent()).getOrientation() == LinearLayout.HORIZONTAL)) {
                scroll = scroll.getParent() instanceof View ? (View) scroll.getParent() : null;
            }
            if (scroll == null) return;
            LinearLayout row = (LinearLayout) scroll.getParent();
            if (row.findViewWithTag(TAG) != null) return;

            TextView button = new TextView(row.getContext());
            button.setTag(TAG);
            button.setText("GIF");
            button.setTypeface(Typeface.DEFAULT_BOLD);
            button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            button.setGravity(Gravity.CENTER);
            boolean dark = (row.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            int color = dark ? 0xFFF1F1F1 : 0xFF0F0F0F;
            button.setTextColor(color);
            GradientDrawable frame = new GradientDrawable();
            frame.setCornerRadius(dp(row, 4));
            frame.setStroke(dp(row, 2), color);
            button.setBackground(frame);
            button.setPadding(dp(row, 5), dp(row, 1), dp(row, 5), dp(row, 1));
            button.setContentDescription("GIF");
            button.setOnClickListener(v -> open(input));

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.CENTER_VERTICAL;
            lp.leftMargin = dp(row, 8);
            lp.rightMargin = dp(row, 8);
            row.addView(button, row.indexOfChild(scroll) + 1, lp);
        } catch (Throwable t) {
            Nx.log("GIF button failed", t);
        }
    }

    // region Any comment box: watch all windows for a focused comment text field

    private static boolean watching;
    private static java.lang.ref.WeakReference<EditText> current = new java.lang.ref.WeakReference<>(null);
    private static android.widget.PopupWindow chip;
    private static String lastNoted;

    /** Watches every window the app opens (dialogs and sheets too) for a focused comment box. */
    @SuppressWarnings("unchecked")
    static synchronized void watchWindows() {
        if (watching) return;
        watching = true;
        try {
            Class<?> global = Class.forName("android.view.WindowManagerGlobal");
            Object instance = global.getMethod("getInstance").invoke(null);
            java.lang.reflect.Field field = global.getDeclaredField("mViews");
            field.setAccessible(true);
            final java.util.ArrayList<View> views = (java.util.ArrayList<View>) field.get(instance);
            java.util.ArrayList<View> watched = new java.util.ArrayList<View>(views) {
                @Override
                public boolean add(View view) {
                    boolean added = super.add(view);
                    onWindow(view);
                    return added;
                }
            };
            field.set(instance, watched);
            for (View v : watched) onWindow(v);
        } catch (Throwable t) {
            Diagnostics.note(Nx.t("GIF: pencereler izlenemiyor: ", "GIF: cannot watch windows: ") + t);
        }
    }

    private static void onWindow(View root) {
        try {
            root.getViewTreeObserver().addOnGlobalFocusChangeListener((oldFocus, newFocus) -> onFocus(newFocus));
        } catch (Throwable ignored) {
        }
    }

    private static void onFocus(View focused) {
        try {
            if (!Gif.enabled()) return;
            if (!(focused instanceof EditText)) {
                hideChip();
                return;
            }
            EditText input = (EditText) focused;
            if (input.getTag(R_PICKER) != null) return; // NX's own search box
            String described = describe(input);
            if (!isCommentBox(input, described)) {
                if (!described.equals(lastNoted)) {
                    lastNoted = described;
                    Diagnostics.note(Nx.t("GIF: yorum kutusu sanılmadı: ", "GIF: not taken for a comment box: ") + described);
                }
                hideChip();
                return;
            }
            showChip(input);
        } catch (Throwable t) {
            Nx.log("GIF focus failed", t);
        }
    }

    private static String describe(EditText input) {
        String id = "";
        try {
            if (input.getId() != View.NO_ID) id = input.getResources().getResourceEntryName(input.getId());
        } catch (Throwable ignored) {
        }
        CharSequence hint = input.getHint();
        CharSequence desc = input.getContentDescription();
        return "id=" + id + ", hint=" + (hint == null ? "" : hint) + ", desc=" + (desc == null ? "" : desc)
                + ", class=" + input.getClass().getSimpleName();
    }

    private static boolean isCommentBox(EditText input, String described) {
        String text = described.toLowerCase(java.util.Locale.ROOT);
        if (text.contains("search") || text.contains("ara")) {
            // Search fields: "YouTube'da ara", "search".
            if (!text.contains("yorum") && !text.contains("comment")) return false;
        }
        if (text.contains("yorum") || text.contains("yanıt") || text.contains("yanit")
                || text.contains("comment") || text.contains("reply")) return true;
        // Ancestors named after comments (e.g. the comment dialog).
        View v = input;
        for (int i = 0; i < 8 && v != null; i++) {
            try {
                if (v.getId() != View.NO_ID && v.getResources().getResourceEntryName(v.getId()).contains("comment")) return true;
            } catch (Throwable ignored) {
            }
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return false;
    }

    private static void showChip(final EditText input) {
        if (input == current.get() && chip != null && chip.isShowing()) return;
        hideChip();
        // The classic dialog already has an inline button.
        if (input.getParent() != null && rowHasButton(input)) return;
        current = new java.lang.ref.WeakReference<>(input);

        boolean dark = (input.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        TextView button = new TextView(input.getContext());
        button.setText("GIF");
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(dark ? 0xFF0F0F0F : 0xFFFFFFFF);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(input, 14));
        bg.setColor(dark ? 0xFFF1F1F1 : 0xFF0F0F0F);
        button.setBackground(bg);
        button.setPadding(dp(input, 12), dp(input, 5), dp(input, 12), dp(input, 5));
        button.setOnClickListener(v -> open(input));

        chip = new android.widget.PopupWindow(button, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, false);
        chip.setOutsideTouchable(false);
        chip.setElevation(dp(input, 4));
        chip.setInputMethodMode(android.widget.PopupWindow.INPUT_METHOD_NOT_NEEDED);
        input.post(() -> {
            try {
                if (!input.isAttachedToWindow() || chip == null) return;
                button.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                // Above the right end of the text field.
                int x = Math.max(0, input.getWidth() - button.getMeasuredWidth());
                int y = -input.getHeight() - button.getMeasuredHeight() - dp(input, 6);
                chip.showAsDropDown(input, x, y);
            } catch (Throwable t) {
                Nx.log("GIF chip failed", t);
            }
        });
        input.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                if (current.get() == v) hideChip();
            }
        });
    }

    private static boolean rowHasButton(View input) {
        View v = input;
        for (int i = 0; i < 4 && v != null; i++) {
            if (v instanceof ViewGroup && ((ViewGroup) v).findViewWithTag(TAG) != null) return true;
            v = v.getParent() instanceof View ? (View) v.getParent() : null;
        }
        return false;
    }

    private static void hideChip() {
        if (chip != null) {
            try {
                chip.dismiss();
            } catch (Throwable ignored) {
            }
            chip = null;
        }
    }

    // endregion

    private static void open(final EditText input) {
        try {
            Activity activity = activity(input.getContext());
            if (activity == null) return;
            boolean dark = (activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            final Dialog dialog = new Dialog(activity,
                    dark ? android.R.style.Theme_DeviceDefault_Dialog_NoActionBar : android.R.style.Theme_DeviceDefault_Light_Dialog_NoActionBar);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setContentView(GifActivity.picker(activity, link -> {
                insert(input, link);
                dialog.dismiss();
            }));
            Window window = dialog.getWindow();
            if (window != null) {
                window.setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                        Math.round(activity.getResources().getDisplayMetrics().heightPixels * 0.75f));
                window.setGravity(Gravity.BOTTOM);
            }
            hideChip();
            dialog.show();
        } catch (Throwable t) {
            Nx.log("GIF picker failed", t);
        }
    }

    private static void insert(EditText input, String link) {
        Editable text = input.getText();
        int at = Math.max(0, input.getSelectionStart());
        String before = at > 0 && !Character.isWhitespace(text.charAt(at - 1)) ? " " : "";
        text.insert(at, before + link + " ");
        input.requestFocus();
        Nx.toast(Nx.t("GIF eklendi; gönderince GIF olarak paylaşılır.", "GIF added; it is posted as a GIF when you send."));
    }

    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    private static int dp(View v, float value) {
        return Math.round(value * v.getResources().getDisplayMetrics().density);
    }
}
