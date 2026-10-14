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
