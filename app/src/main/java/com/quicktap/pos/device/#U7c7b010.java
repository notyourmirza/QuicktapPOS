package com.quicktap.pos.device;

import android.app.Activity;
import android.app.admin.DevicePolicyManager;
import android.content.ComponentName;
import android.content.Context;
import android.os.Build;
import android.util.Log;

/** Official Android Enterprise Device Owner and Lock Task integration. */
public final class 类010 {
    private static final String TAG = "QuickTapKiosk";
    private final Context appContext;
    private final DevicePolicyManager policyManager;
    private final ComponentName admin;

    public 类010(Context context) {
        appContext = context.getApplicationContext();
        policyManager = (DevicePolicyManager) appContext.getSystemService(Context.DEVICE_POLICY_SERVICE);
        admin = new ComponentName(appContext, 类011.class);
    }

    public boolean isDeviceOwner() {
        try { return policyManager != null && policyManager.isDeviceOwnerApp(appContext.getPackageName()); }
        catch (RuntimeException e) { Log.w(TAG, "Device Owner check failed", e); return false; }
    }

    public boolean isDeviceAdminActive() {
        try { return policyManager != null && policyManager.isAdminActive(admin); }
        catch (RuntimeException e) { Log.w(TAG, "Device admin check failed", e); return false; }
    }

    public boolean configureKiosk() {
        if (!isDeviceOwner() || policyManager == null) return false;
        try {
            policyManager.setLockTaskPackages(admin, new String[]{appContext.getPackageName()});
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                policyManager.setLockTaskFeatures(admin, DevicePolicyManager.LOCK_TASK_FEATURE_NONE);
            }
            return true;
        } catch (SecurityException | IllegalArgumentException e) {
            Log.e(TAG, "Kiosk configuration failed", e);
            return false;
        }
    }

    public boolean isKioskModeEnabled() {
        try {
            ActivityManagerHolder holder = new ActivityManagerHolder(appContext);
            return holder.isLockTask();
        } catch (RuntimeException e) { return false; }
    }

    public boolean enableKioskMode(Activity activity) {
        if (activity == null || !isDeviceOwner() || !configureKiosk()) return false;
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) return false;
            if (policyManager != null && !policyManager.isLockTaskPermitted(appContext.getPackageName())) return false;
            activity.startLockTask();
            return true;
        } catch (SecurityException | IllegalStateException e) {
            Log.e(TAG, "Unable to start Lock Task", e);
            return false;
        }
    }

    public boolean disableKioskMode(Activity activity) {
        if (activity == null) return false;
        try { activity.stopLockTask(); return true; }
        catch (SecurityException | IllegalStateException e) { Log.w(TAG, "Unable to stop Lock Task", e); return false; }
    }

    /** Small isolated wrapper keeps ActivityManager API handling out of callers. */
    private static final class ActivityManagerHolder {
        private final android.app.ActivityManager manager;
        ActivityManagerHolder(Context context) { manager = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE); }
        boolean isLockTask() { return manager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && manager.getLockTaskModeState() != android.app.ActivityManager.LOCK_TASK_MODE_NONE; }
    }
}
