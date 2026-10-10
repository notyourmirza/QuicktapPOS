package com.quicktap.pos.cloudsync;

import android.content.Intent;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.graphics.drawable.DrawableCompat;

import android.view.Menu;
import android.view.MenuItem;

/**
 * The small, unobtrusive Cloud Sync icon in the main toolbar.
 *
 * Behaviour:
 *  - hidden completely when no key is saved (the app looks exactly as before)
 *  - muted grey when offline
 *  - red when the key / plan needs attention
 *  - tinted with the brand colour when connected, with the pending count in
 *    its content description
 *  - tapping it always opens the Cloud Sync screen
 *
 * It never starts network work by itself; it only renders CloudSyncStatus.
 */
public final class CloudSyncIndicator implements CloudSyncStatus.Listener {

    private final AppCompatActivity activity;
    private final Toolbar toolbar;
    private final int iconRes;
    private boolean installed;

    private CloudSyncIndicator(AppCompatActivity activity, Toolbar toolbar, int iconRes) {
        this.activity = activity;
        this.toolbar = toolbar;
        this.iconRes = iconRes;
    }

    /**
     * Adds the icon to the toolbar and keeps it up to date.
     * Call once from the host activity's onCreate(); call {@link #destroy()}
     * from onDestroy().
     */
    public static CloudSyncIndicator attach(@NonNull AppCompatActivity activity,
                                            @NonNull Toolbar toolbar,
                                            int iconRes) {
        CloudSyncIndicator indicator = new CloudSyncIndicator(activity, toolbar, iconRes);
        indicator.install();
        return indicator;
    }

    private void install() {
        installed = true;
        CloudSyncStatus.addListener(this);
        refresh();
    }

    public void destroy() {
        if (!installed) {
            return;
        }
        installed = false;
        CloudSyncStatus.removeListener(this);
    }

    public void refresh() {
        refresh(CloudSyncStatus.get());
    }

    private void refresh(CloudSyncStatus status) {
        if (toolbar == null || activity == null || activity.isFinishing()) {
            return;
        }
        Menu menu = toolbar.getMenu();
        if (menu == null) {
            return;
        }
        MenuItem item = menu.findItem(com.quicktap.pos.R.id.action_cloud_sync);
        if (item == null) {
            return;
        }

        boolean configured = CloudSyncSecureStore.get(activity).isConfigured();
        item.setVisible(configured);
        if (!configured) {
            return;
        }

        Drawable icon = androidx.appcompat.content.res.AppCompatResources
                .getDrawable(activity, iconRes);
        if (icon == null) {
            return;
        }
        icon = DrawableCompat.wrap(icon).mutate();

        int tint;
        if (status.needsAttention()) {
            tint = activity.getResources().getColor(com.quicktap.pos.R.color.danger,
                    activity.getTheme());
        } else if (status.state == CloudSyncState.CONNECTED && status.pending > 0) {
            tint = resolveAttr(androidx.appcompat.R.attr.colorPrimary);
        } else if (status.state == CloudSyncState.CONNECTED) {
            tint = resolveAttr(android.R.attr.textColorPrimary);
        } else {
            tint = resolveAttr(android.R.attr.textColorSecondary);
        }
        DrawableCompat.setTint(icon, tint);
        item.setIcon(icon);

        String label;
        switch (status.state) {
            case CONNECTED:
                label = status.pending > 0
                        ? "Cloud Sync - " + status.pending + " pending"
                        : "Cloud Sync - up to date";
                break;
            case OFFLINE:
                label = "Cloud Sync - offline, " + status.pending + " queued";
                break;
            case INVALID_KEY:
                label = "Cloud Sync - invalid key";
                break;
            case NOT_IN_PLAN:
                label = "Cloud Sync - not in your plan";
                break;
            case PLAN_EXPIRED:
                label = "Cloud Sync - plan expired";
                break;
            case CONNECTING:
                label = "Cloud Sync - syncing";
                break;
            default:
                label = "Cloud Sync";
                break;
        }
        item.setTitle(label);
    }

    private int resolveAttr(int attr) {
        int[] attrs = new int[]{attr};
        android.content.res.TypedArray a = activity.obtainStyledAttributes(attrs);
        int color = a.getColor(0, 0xFF888888);
        a.recycle();
        return color;
    }

    /** Call from the host's toolbar click handler. */
    public boolean handleClick(MenuItem item) {
        if (item == null || item.getItemId() != com.quicktap.pos.R.id.action_cloud_sync) {
            return false;
        }
        activity.startActivity(new Intent(activity,
                com.quicktap.pos.ui.cloudsync.CloudSyncActivity.class));
        return true;
    }

    @Override
    public void onChanged(CloudSyncStatus status) {
        if (activity != null && !activity.isFinishing()) {
            activity.runOnUiThread(() -> refresh(status));
        }
    }
}
