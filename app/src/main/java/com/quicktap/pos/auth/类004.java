package com.quicktap.pos.auth;

import android.content.Context;

import com.quicktap.pos.BuildConfig;
import com.quicktap.pos.net.类016;
import com.quicktap.pos.net.类017;
import com.quicktap.pos.theme.类036;
import com.quicktap.pos.util.类045;
import com.quicktap.pos.util.类046;

import org.json.JSONObject;

/**
 * Owns everything about "who is signed in on this device":
 * JWT access/refresh tokens, device binding, fingerprint preference,
 * the auto-lock clock and the server-driven profile snapshot.
 *
 * All network work happens on the IO executor; callbacks come back on the
 * caller's thread via {@link 类045#main()}.
 */
public final class 类004 {

    public interface Callback { void onResult(boolean ok, String message, String code); }

    private static 类004 instance;

    private final Context app;
    private final 类046 prefs;

    private 类004(Context context) {
        this.app = context.getApplicationContext();
        this.prefs = 类046.get(this.app);
    }

    public static synchronized 类004 get(Context context) {
        if (instance == null) {
            instance = new 类004(context);
            // Let the HTTP layer renew expired access tokens by itself.
            类016.setTokenRefresher(类004::refreshBlockingStatic);
        }
        return instance;
    }

    // ---------------------------------------------------------------- state

    public boolean isSignedIn() { return prefs.getAccessToken() != null && prefs.getRefreshToken() != null; }

    public String username() { return prefs.getUsername(); }

    public String role() { return prefs.getUserRole(); }

    public boolean isFingerprintEnabled() { return prefs.isFingerprintEnabled(); }

    // ---------------------------------------------------------------- login

    public void login(String username, String password, Callback cb) {
        类045.io().execute(() -> {
            try {
                JSONObject body = new JSONObject()
                        .put("username", username)
                        .put("password", password)
                        .put("device_id", 类003.id(app))
                        .put("device_name", 类003.name())
                        .put("app_version", BuildConfig.VERSION_NAME)
                        .put("os_version", 类003.osVersion());

                类017 res = 类016.post(app, "v1/auth/login", body, false);
                if (res.success) {
                    storeSession(res.dataOrEmpty());
                    prefs.setUsername(username);
                    prefs.touchActivity();
                }
                // Hand control back to the UI immediately. Branding used to be
                // fetched here with a second blocking request, which is what
                // made the login screen sit on the spinner for so long.
                post(cb, res.success, res.message, res.errorCode);
                if (res.success) {
                    类045.io().execute(() -> 类036.refreshBlocking(app));
                }
            } catch (Exception e) {
                post(cb, false, "Sign-in failed. Please try again.", "CLIENT_ERROR");
            }
        });
    }

    /** Verifies the current user's password on the auto-lock screen. */
    public void unlockWithPassword(String password, Callback cb) {
        类045.io().execute(() -> {
            try {
                类017 res = 类016.post(app, "v1/auth/unlock",
                        new JSONObject().put("password", password), true);
                if (res.success) prefs.touchActivity();
                post(cb, res.success, res.message, res.errorCode);
            } catch (Exception e) {
                post(cb, false, "Unlock failed", "CLIENT_ERROR");
            }
        });
    }

    /** Turns fingerprint unlock on/off both locally and on the server. */
    public void setFingerprintEnabled(boolean enabled, Callback cb) {
        prefs.setFingerprintEnabled(enabled);
        类045.io().execute(() -> {
            类017 res;
            try {
                res = 类016.post(app, "v1/auth/fingerprint",
                        new JSONObject().put("enabled", enabled), true);
            } catch (Exception e) {
                res = 类017.offline("Network error. Please try again.");
            }
            post(cb, res.success, res.message, res.errorCode);
        });
    }

    /** Refreshes the cached profile (role, shop, plan) from /v1/auth/me. */
    public void refreshProfile(Callback cb) {
        类045.io().execute(() -> {
            类017 res = 类016.get(app, "v1/auth/me", null, true);
            if (res.success && res.data != null) {
                prefs.setUserRole(res.data.optString("role", prefs.getUserRole()));
                prefs.setFullName(res.data.optString("full_name", ""));
                prefs.setShopName(res.data.optString("shop_name", prefs.getStoreName()));
                if (res.data.has("fingerprint_enabled")) {
                    prefs.setFingerprintEnabled(res.data.optInt("fingerprint_enabled", 0) == 1);
                }
            }
            post(cb, res.success, res.message, res.errorCode);
        });
    }

    public void logout(Callback cb) {
        类045.io().execute(() -> {
            类016.post(app, "v1/auth/logout", new JSONObject(), true);
            prefs.clearSession();
            post(cb, true, "Signed out", "");
        });
    }

    /** Wipes the local session without contacting the server (device blocked etc.). */
    public void forceSignOut() { prefs.clearSession(); }

    /**
     * Stores the session returned by /v1/auth/register so the new account can
     * immediately poll its licence status. Same storage path as a normal login;
     * the password itself is never persisted.
     */
    public void adoptSession(JSONObject data, String username) {
        if (data == null) return;
        storeSession(data);
        if (username != null && !username.isEmpty()) prefs.setUsername(username);
        prefs.touchActivity();
    }


    // -------------------------------------------------------------- refresh

    private static boolean refreshBlockingStatic(Context ctx) {
        return get(ctx).refreshBlocking();
    }

    /** Exchanges the refresh token for a new access token. Call off the main thread. */
    public synchronized boolean refreshBlocking() {
        String refresh = prefs.getRefreshToken();
        if (refresh == null) return false;
        try {
            类017 res = 类016.post(app, "v1/auth/refresh",
                    new JSONObject().put("refresh_token", refresh), false);
            if (!res.success) {
                // A revoked/expired refresh token means the session is truly over.
                if (res.networkOk && res.httpCode == 401) prefs.clearSession();
                return false;
            }
            storeSession(res.dataOrEmpty());
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------ auto-lock

    /** Minutes of inactivity before the session lock screen appears. */
    public int autoLockMinutes() { return prefs.getAutoLockMinutes(); }

    public void setAutoLockMinutes(int minutes) { prefs.setAutoLockMinutes(minutes); }

    public void markActivity() { prefs.touchActivity(); }

    public void lockNow() { prefs.setLocked(true); }

    public void markUnlocked() {
        prefs.setLocked(false);
        prefs.touchActivity();
    }

    /** True when the app must show the lock screen before any POS screen. */
    public boolean shouldLock() {
        if (!isSignedIn()) return false;
        if (prefs.isLocked()) return true;
        int minutes = prefs.getAutoLockMinutes();
        if (minutes <= 0) return false;
        long idle = System.currentTimeMillis() - prefs.getLastActivityAt();
        return idle > minutes * 60_000L;
    }

    // ----------------------------------------------------------------- util

    private void storeSession(JSONObject data) {
        JSONObject tokens = data.optJSONObject("tokens");
        if (tokens == null) tokens = data;
        String access = tokens.optString("access_token", null);
        String refresh = tokens.optString("refresh_token", null);
        if (access != null) prefs.setAccessToken(access);
        if (refresh != null) prefs.setRefreshToken(refresh);
        prefs.setTokenExpiresAt(System.currentTimeMillis() + tokens.optLong("expires_in", 3600) * 1000L);

        JSONObject user = data.optJSONObject("user");
        if (user != null) {
            prefs.setUserRole(user.optString("role", "cashier"));
            prefs.setFullName(user.optString("full_name", ""));
            if (user.has("fingerprint_enabled")) {
                prefs.setFingerprintEnabled(user.optInt("fingerprint_enabled", 0) == 1);
            }
        }
        JSONObject shop = data.optJSONObject("shop");
        if (shop != null) {
            prefs.setShopName(shop.optString("name", prefs.getStoreName()));
            String currency = shop.optString("currency", "");
            if (!currency.isEmpty()) prefs.setCurrency(currency);
        }
    }

    private void post(Callback cb, boolean ok, String message, String code) {
        if (cb == null) return;
        类045.main().post(() -> cb.onResult(ok, message, code));
    }
}
