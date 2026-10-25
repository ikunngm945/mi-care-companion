package cn.local.micompanion.hook;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

/** Minimal view-tree surgery used by the two injected settings pages. */
public final class Injection {
    public static final String TAG = "mi-companion-link-header";
    private Injection() { }

    public static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    /** Places a fixed row at the top of the activity content, below its own title/header. */
    public static boolean addTopEntry(Activity activity, View row) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null || TAG.equals(content.getTag())) return false;
        if (content.getChildCount() != 1) return false;
        View child = content.getChildAt(0);
        content.removeView(child);

        FrameLayout wrapper = new FrameLayout(activity);
        FrameLayout.LayoutParams childParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        childParams.topMargin = dp(activity, 64);
        wrapper.addView(child, childParams);
        content.addView(wrapper, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        content.addView(row, params);
        content.setTag(TAG);

        final ViewGroup host = content;
        final ViewGroup holder = wrapper;
        Runnable place = () -> positionBelowHeader(activity, host, holder, row);
        content.post(place);
        for (int delay : new int[]{200, 500, 900, 1500, 2500, 4000}) content.postDelayed(place, delay);
        return true;
    }

    private static void positionBelowHeader(Activity activity, ViewGroup content, ViewGroup wrapper, View row) {
        int top = 0;
        for (int i = 0; i < content.getChildCount(); i++) {
            View sibling = content.getChildAt(i);
            if (sibling != wrapper && sibling != row) top = Math.max(top, sibling.getBottom());
        }
        if (top <= 0) {
            int titleId = activity.getResources().getIdentifier(
                    "action_bar_title_expand", "id", activity.getPackageName());
            View title = titleId == 0 ? null : activity.findViewById(titleId);
            if (title != null && title.getHeight() > 0) {
                int[] titlePos = new int[2];
                int[] contentPos = new int[2];
                title.getLocationInWindow(titlePos);
                content.getLocationInWindow(contentPos);
                top = titlePos[1] + title.getHeight() - contentPos[1] + dp(activity, 16);
            }
        }
        if (top <= 0) {
            int inset = 0;
            if (Build.VERSION.SDK_INT >= 23) {
                WindowInsets insets = activity.getWindow().getDecorView().getRootWindowInsets();
                if (insets != null) {
                    inset = Build.VERSION.SDK_INT >= 30
                            ? insets.getInsets(WindowInsets.Type.statusBars()).top
                            : insets.getSystemWindowInsetTop();
                }
            }
            top = inset > 0 ? inset : dp(activity, 44);
        }
        ViewGroup.LayoutParams raw = row.getLayoutParams();
        if (!(raw instanceof FrameLayout.LayoutParams)) return;
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams)raw;
        if (lp.topMargin != top) {
            lp.topMargin = top;
            row.setLayoutParams(lp);
        }
    }

    /** Replaces the activity's content with a settings form, leaving its title bar intact. */
    public static boolean replaceContent(Activity activity, View view) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null || TAG.equals(content.getTag())) return false;
        content.removeAllViews();
        content.addView(view, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        content.setTag(TAG);
        return true;
    }
}
