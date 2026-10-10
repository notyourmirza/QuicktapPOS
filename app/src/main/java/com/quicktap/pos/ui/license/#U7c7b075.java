package com.quicktap.pos.ui.license;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import com.quicktap.pos.license.类012;
import com.quicktap.pos.license.类013;
import com.quicktap.pos.license.类014;

/**
 * One reusable mechanism for every protected screen.
 *
 * Instead of copying the banner into dozens of layouts, a protected Activity
 * calls {@link #protect(Activity)} from {@code onResume()}. It:
 *   1. shows the shared {@link 类074} at the very top of the existing
 *      content view (nothing is covered — the content is pushed down), and
 *   2. asks the SERVER for the current state and hands the answer to the single
 *      {@link 类012} so a pending / expired / revoked / suspended /
 *      blocked / unconfirmed account is routed away from protected screens.
 *
 * Nothing here grants access: the gate keeps the offline grace rules and the
 * server keeps rejecting protected API calls regardless of the UI.
 */
public final class 类075 {

    private 类075() { }

    /** Attaches (once) the banner and re-validates the account with the server. */
    public static void protect(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        final 类074 banner = attach(activity);
        类013.status(activity, state -> {
            if (activity.isFinishing()) return;
            if (banner != null) banner.apply(state);
            if (isAllowed(activity, state)) return;
            类012.route(activity, state);
        });
    }

    /** True when this state may keep using an already-open protected screen. */
    private static boolean isAllowed(Activity activity, 类014 state) {
        if (state == null) return false;
        if (类014.OFFLINE.equals(state.state)) {
            // Only a previously server-confirmed ACTIVE licence rides out an outage.
            return 类012.offlineGraceAllows(activity);
        }
        return 类014.ACTIVE.equals(state.state) && state.unlocked && state.confirmed;
    }

    /** Inserts the banner at the top of the activity's content, at most once. */
    @Nullable
    public static 类074 attach(Activity activity) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content == null || content.getChildCount() == 0) return null;

        View root = content.getChildAt(0);
        if (root instanceof LinearLayout && "license_guard".equals(root.getTag())) {
            return (类074) ((LinearLayout) root).getChildAt(0);
        }

        ViewGroup.LayoutParams params = root.getLayoutParams();
        content.removeView(root);

        LinearLayout wrapper = new LinearLayout(activity);
        wrapper.setTag("license_guard");
        wrapper.setOrientation(LinearLayout.VERTICAL);

        类074 banner = new 类074(activity);
        banner.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        wrapper.addView(banner);

        wrapper.addView(root, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        content.addView(wrapper, params == null
                ? new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT)
                : params);
        return banner;
    }
}
