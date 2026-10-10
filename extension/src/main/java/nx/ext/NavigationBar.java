package nx.ext;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** Hides navigation bar buttons the user does not want. */
@SuppressWarnings("unused")
public final class NavigationBar {
    private NavigationBar() {
    }

    /** Called with every navigation bar button right after YouTube creates it. */
    public static void onButtonCreated(final View button) {
        if (button == null || !Features.has(Features.SHORTS) || !Nx.bool(NxSettings.HIDE_SHORTS_BUTTON)) return;
        if (isShorts(button)) {
            button.setVisibility(View.GONE);
            return;
        }
        // The label may be set after creation.
        button.post(new Runnable() {
            @Override
            public void run() {
                if (isShorts(button)) button.setVisibility(View.GONE);
            }
        });
    }

    // "Shorts" is the tab's name in every language.
    private static boolean isShorts(View view) {
        CharSequence description = view.getContentDescription();
        if (description != null && "Shorts".contentEquals(description)) return true;
        if (view instanceof TextView && "Shorts".contentEquals(((TextView) view).getText())) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) if (isShorts(group.getChildAt(i))) return true;
        }
        return false;
    }
}
