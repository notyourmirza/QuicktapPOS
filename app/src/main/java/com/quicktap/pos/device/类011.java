package com.quicktap.pos.device;

import android.app.admin.DeviceAdminReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/** Receives official Android device-admin lifecycle callbacks. */
public final class 类011 extends DeviceAdminReceiver {
    private static final String TAG = "QuickTapDeviceAdmin";

    @Override public void onEnabled(Context context, Intent intent) {
        super.onEnabled(context, intent);
        Log.i(TAG, "Device admin enabled");
    }

    @Override public void onDisabled(Context context, Intent intent) {
        super.onDisabled(context, intent);
        Log.i(TAG, "Device admin disabled");
    }
}
