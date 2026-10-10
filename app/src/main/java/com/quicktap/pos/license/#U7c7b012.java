package com.quicktap.pos.license;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;

import com.quicktap.pos.auth.类004;
import com.quicktap.pos.ui.类041;
import com.quicktap.pos.ui.类042;
import com.quicktap.pos.ui.license.类073;
import com.quicktap.pos.ui.license.类076;
import com.quicktap.pos.util.类046;

/**
 * Decides which screen the user belongs on, based on the SERVER's answer.
 *
 * The cached state is only ever used to keep an already-verified shop selling
 * for a short grace window while the network is down — it can never unlock an
 * account that the server has not activated, and protected API calls stay
 * rejected server-side regardless of anything cached here.
 */
public final class 类012 {

    /** How long an already-confirmed ACTIVE licence survives without a server answer. */
    private static final long OFFLINE_GRACE_MS = 24 * 60 * 60 * 1000L;

    public interface Router { void onRoute(类014 state); }

    private 类012() { }

    // ------------------------------------------------------------- routing

    /** Asks the server for the current state, then routes the activity. */
    public static void resolveAndRoute(Activity activity) {
        if (!类004.get(activity).isSignedIn()) {
            open(activity, 类041.class);
            return;
        }
        类013.status(activity, state -> route(activity, state));
    }

    /** Routes on an already-fetched state. Safe to call from any screen. */
    public static void route(Activity activity, 类014 state) {
        if (activity == null || activity.isFinishing()) return;

        if (类014.OFFLINE.equals(state.state)) {
            if (offlineGraceAllows(activity)) {
                open(activity, 类042.class);
            } else {
                openStatus(activity, 类014.OFFLINE);
            }
            return;
        }

        switch (state.state) {
            case 类014.NO_ACCOUNT:
                类004.get(activity).forceSignOut();
                类046.get(activity).clearLicenseCache();
                open(activity, 类041.class);
                return;
            case 类014.ACTIVE:
                if (state.unlocked && state.confirmed) {
                    open(activity, 类042.class);
                } else if (state.unlocked) {
                    open(activity, 类073.class);
                } else {
                    openStatus(activity, 类014.PENDING);
                }
                return;
            default:
                openStatus(activity, state.state);
        }
    }

    /** True while an already-confirmed ACTIVE licence may ride out a network outage. */
    public static boolean offlineGraceAllows(Context ctx) {
        类046 prefs = 类046.get(ctx);
        if (!类014.ACTIVE.equals(prefs.getLicenseState())) return false;
        if (!prefs.isLicenseConfirmed()) return false;

        long lastOk = prefs.getLastLicenseSuccessAt();
        if (lastOk <= 0) return false;
        long since = System.currentTimeMillis() - lastOk;
        if (since < 0 || since > OFFLINE_GRACE_MS) return false;

        // Expiry always comes from the SERVER clock recorded at the last check;
        // the grace window can never reach past it.
        long expiry = prefs.getLicenseExpiresAt();
        return expiry <= 0 || expiry > lastOk;
    }

    /** Cached hint used by the banner; never an authorisation decision. */
    public static boolean looksUnlocked(Context ctx) {
        类046 prefs = 类046.get(ctx);
        return 类014.ACTIVE.equals(prefs.getLicenseState()) && prefs.isLicenseConfirmed();
    }

    /** True when a background refresh is due (interval published by the server). */
    public static boolean refreshDue(Context ctx) {
        类046 prefs = 类046.get(ctx);
        long interval = Math.max(5, prefs.getLicenseSyncMinutes()) * 60_000L;
        return System.currentTimeMillis() - prefs.getLastLicenseCheck() > interval;
    }

    // ------------------------------------------------------------- helpers

    private static void openStatus(Activity activity, String state) {
        Intent intent = new Intent(activity, 类076.class)
                .putExtra(类076.EXTRA_STATE, state)
                // A locked-out account must not be able to press Back into the app.
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        activity.startActivity(intent);
        activity.finish();
    }

    private static void open(Activity activity, Class<?> target) {
        if (activity.getClass().equals(target)) return;
        activity.startActivity(new Intent(activity, target)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        activity.finish();
    }
}
