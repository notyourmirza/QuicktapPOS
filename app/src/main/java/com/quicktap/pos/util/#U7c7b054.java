package com.quicktap.pos.util;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.quicktap.pos.auth.类004;
import com.quicktap.pos.sync.类034;
import com.quicktap.pos.ui.类041;
import com.quicktap.pos.ui.类044;

/**
 * Application-wide guard that implements the auto session lock.
 *
 * It tracks how long the app has been in the background / idle and, when the
 * configured timeout has passed, pushes the {@link 类044} in front of
 * whatever screen the cashier left open. It also kicks a sync every time the
 * app comes back to the foreground.
 */
public class 类054 implements Application.ActivityLifecycleCallbacks {

    private final Application app;
    private int started;
    private boolean unlockVisible;

    public 类054(Application app) { this.app = app; }

    public static void install(Application app) {
        app.registerActivityLifecycleCallbacks(new 类054(app));
    }

    @Override
    public void onActivityStarted(@NonNull Activity activity) {
        boolean cameToForeground = started == 0;
        started++;

        unlockVisible = activity instanceof 类044;
        if (unlockVisible || activity instanceof 类041) return;

        类004 session = 类004.get(app);
        // 类042 is the single owner of the unlock flow. Its onResume()
        // starts exactly one 类044. 类054 must not launch a
        // second one during onActivityStarted, which caused duplicate login
        // screens and OEM task crashes.
        if (session.shouldLock()) return;
        // Do not touch the activity timestamp here. This callback runs before
        // 类042.onResume(), and touching it would erase the elapsed idle
        // time and prevent the unlock screen from appearing after a timeout.
        if (cameToForeground) 类034.syncAuto(app);
    }

    @Override
    public void onActivityStopped(@NonNull Activity activity) {
        started = Math.max(0, started - 1);
        // Keep the last real user-interaction timestamp intact while the app is
        // backgrounded. The timeout must include time spent outside the app.
    }

    @Override
    public void onActivityResumed(@NonNull Activity activity) {
        // 类042 records genuine interaction through onUserInteraction().
        // Do not reset the timeout merely because Android resumed an Activity.
    }

    @Override public void onActivityCreated(@NonNull Activity a, @Nullable Bundle b) { }
    @Override public void onActivityPaused(@NonNull Activity a) { }
    @Override public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) { }
    @Override public void onActivityDestroyed(@NonNull Activity a) { }
}
