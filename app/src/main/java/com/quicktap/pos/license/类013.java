package com.quicktap.pos.license;

import android.content.Context;

import com.quicktap.pos.BuildConfig;
import com.quicktap.pos.auth.类003;
import com.quicktap.pos.auth.类004;
import com.quicktap.pos.net.类016;
import com.quicktap.pos.net.类017;
import com.quicktap.pos.util.类045;
import com.quicktap.pos.util.类046;

import org.json.JSONObject;

/**
 * The only Android entry point for the server-authoritative licence API.
 *
 * Every call goes through the shared {@link 类016}, which already resolves
 * its base URL from {@link com.quicktap.pos.net.类018} (Firebase
 * controlled) and attaches the app credentials + JWT. No licence decision is
 * made here: the client only relays what the server said.
 *
 * Endpoints used:
 *   POST v1/auth/register     — create account + device registration
 *   GET  v1/license/status    — authoritative current state
 *   POST v1/license/verify    — redeem the licence key issued by the admin
 *   POST v1/license/confirm   — username + password confirmation
 *   GET  v1/app/config        — support number + server clock (pre-session)
 */
public final class 类013 {

    public interface Callback { void onResult(类014 state); }

    private 类013() { }

    // ----------------------------------------------------------- register

    /**
     * Creates the account on the server. The device identifier comes from the
     * existing {@link 类003} (hashed installation id) so the server can
     * enforce ONE DEVICE = ONE NEW ACCOUNT.
     */
    public static void register(Context ctx, String shopName, String username, String password,
                                String fullName, String phone, Callback cb) {
        final Context app = ctx.getApplicationContext();
        类045.io().execute(() -> {
            类017 res;
            try {
                JSONObject body = new JSONObject()
                        .put("shop_name", shopName)
                        .put("username", username)
                        .put("password", password)
                        .put("full_name", fullName)
                        .put("phone", phone)
                        .put("device_id", 类003.id(app))
                        .put("device_name", 类003.name())
                        .put("app_version", BuildConfig.VERSION_NAME)
                        .put("os_version", 类003.osVersion());
                res = 类016.post(app, "v1/auth/register", body, false);
            } catch (Exception e) {
                res = 类017.offline(null);
            }
            if (res.success) {
                // The server already returned a session for the new account so
                // the pending screen can poll /v1/license/status straight away.
                类004.get(app).adoptSession(res.dataOrEmpty(), username);
            }
            类014 state = 类014.from(res);
            cache(app, state);
            main(cb, state);
        });
    }

    // ------------------------------------------------------------- status

    /** Authoritative state for the signed-in account. Safe to call on demand. */
    public static void status(Context ctx, Callback cb) {
        final Context app = ctx.getApplicationContext();
        类045.io().execute(() -> {
            类014 state = statusBlocking(app);
            main(cb, state);
        });
    }

    /** Background-thread variant. */
    public static 类014 statusBlocking(Context app) {
        if (!类004.get(app).isSignedIn()) {
            类014 s = new 类014();
            s.state = 类014.NO_ACCOUNT;
            s.networkOk = true;
            s.message = "Create an account to continue.";
            return s;
        }
        类017 res = 类016.get(app, "v1/license/status", null, true);
        类014 state = 类014.from(res);
        cache(app, state);
        return state;
    }

    // ------------------------------------------------------------- verify

    /** Redeems the licence key issued by the Super Admin. */
    public static void verify(Context ctx, String licenseKey, Callback cb) {
        final Context app = ctx.getApplicationContext();
        类045.io().execute(() -> {
            类017 res;
            try {
                res = 类016.post(app, "v1/license/verify",
                        new JSONObject().put("license_key", licenseKey), true);
            } catch (Exception e) {
                res = 类017.offline(null);
            }
            类014 state = 类014.from(res);
            cache(app, state);
            main(cb, state);
        });
    }

    // ------------------------------------------------------------ confirm

    /**
     * Username + password confirmation. The password is passed straight to the
     * existing secure endpoint and never stored, cached or logged.
     */
    public static void confirm(Context ctx, String username, String password, Callback cb) {
        final Context app = ctx.getApplicationContext();
        类045.io().execute(() -> {
            类017 res;
            try {
                res = 类016.post(app, "v1/license/confirm",
                        new JSONObject().put("username", username).put("password", password), true);
            } catch (Exception e) {
                res = 类017.offline(null);
            }
            类014 state = 类014.from(res);
            if (res.success) state.confirmed = true;
            cache(app, state);
            main(cb, state);
        });
    }

    // ----------------------------------------------------------- app config

    /**
     * Pre-session bootstrap: the support/WhatsApp number and the refresh interval
     * published by the panel. It is the same payload the branding helper caches,
     * so the two never fetch the endpoint twice.
     */
    public static void syncAppConfig(Context ctx) {
        类045.io().execute(() -> com.quicktap.pos.theme.类036.refreshBlocking(ctx));
    }

    // ------------------------------------------------------------- helpers

    /**
     * Caches the answer for UX only (banner text, expiry label, routing hint).
     * Authorisation itself always requires a fresh server answer.
     */
    private static void cache(Context app, 类014 state) {
        if (state == null || !state.networkOk) return;
        类046 prefs = 类046.get(app);
        prefs.setLastLicenseCheck(System.currentTimeMillis());
        if (类014.UNKNOWN.equals(state.state)) return;
        prefs.setLicenseState(state.state);
        prefs.setLicenseMessage(state.message == null ? "" : state.message);
        if (state.seatLimit > 0 || state.seatUsed > 0) {
            prefs.setDeviceSeats(state.seatUsed, state.seatLimit);
        }
        if (state.success) {
            prefs.setLicenseDurationLabel(state.durationLabel);
            prefs.setLicenseExpiresAt(state.expiresAtMs);
            prefs.setLicenseDaysLeft(state.daysLeft);
            prefs.setLicenseConfirmed(state.confirmed);
            prefs.setLastLicenseSuccessAt(System.currentTimeMillis());
        }
        if (!state.unlocked) prefs.setLicenseConfirmed(state.confirmed);
    }

    private static void main(Callback cb, 类014 state) {
        if (cb == null) return;
        类045.main().post(() -> cb.onResult(state));
    }
}
