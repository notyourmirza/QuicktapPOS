package com.quicktap.pos.theme;

import android.content.Context;
import android.graphics.Color;

import androidx.core.content.ContextCompat;

import com.quicktap.pos.R;
import com.quicktap.pos.net.类016;
import com.quicktap.pos.net.类017;
import com.quicktap.pos.util.类045;
import com.quicktap.pos.util.类046;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Brand palette + the one remote-configuration call the app needs.
 *
 * <p>Colours are deliberately <strong>not</strong> remote controlled. Every colour
 * in the app comes from {@code res/values/colors.xml} (and {@code values-night}),
 * so a layout and the code can never disagree, nothing has to be repainted at
 * runtime and a screen never re-inflates because of a network answer.</p>
 *
 * <p>What the server still owns — cached locally so it keeps working offline —
 * is the small set of things a shop actually changes: the app name, the logo, the
 * receipt template, the support WhatsApp number and the announcements. All of it
 * arrives from a single {@code GET v1/app/config}.</p>
 */
public final class 类036 {

    /** Called on the main thread after a refresh; {@code changed} says whether anything moved. */
    public interface Listener { void onConfig(boolean changed); }

    private 类036() { }

    // ------------------------------------------------------------- colours

    public static int primary(Context ctx) { return color(ctx, R.color.brand_primary); }

    public static int secondary(Context ctx) { return color(ctx, R.color.brand_secondary); }

    public static int background(Context ctx) { return color(ctx, R.color.app_background); }

    public static int surface(Context ctx) { return color(ctx, R.color.app_surface); }

    public static int surfaceMuted(Context ctx) { return color(ctx, R.color.app_surface_muted); }

    public static int outline(Context ctx) { return color(ctx, R.color.app_outline); }

    public static int textPrimary(Context ctx) { return color(ctx, R.color.text_primary); }

    public static int textMuted(Context ctx) { return color(ctx, R.color.text_muted); }

    private static int color(Context ctx, int resId) {
        try {
            return ContextCompat.getColor(ctx, resId);
        } catch (Exception e) {
            return Color.parseColor("#FF7A00"); // never crash a screen over a colour
        }
    }

    /** Best contrast colour to draw on top of the given brand colour. */
    public static int onColor(int color) {
        double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color)
                + 0.114 * Color.blue(color)) / 255d;
        return luminance > 0.62 ? Color.parseColor("#0B0F19") : Color.WHITE;
    }

    // ------------------------------------------------- cached remote values

    public static String appName(Context ctx) { return 类046.get(ctx).getThemeAppName(); }

    public static String logoUrl(Context ctx) { return 类046.get(ctx).getThemeLogoUrl(); }

    // ------------------------------------------------------------- refresh

    /** Fetches the remote configuration in the background. Never blocks the caller. */
    public static void refresh(Context context, Listener listener) {
        类045.io().execute(() -> {
            boolean changed = refreshBlocking(context);
            if (listener != null) 类045.main().post(() -> listener.onConfig(changed));
        });
    }

    /** Fetches {@code v1/app/config} and caches it. Returns true when something changed. */
    public static boolean refreshBlocking(Context context) {
        try {
            // authed=true so a signed-in till receives ITS shop name; an anonymous
            // call still works and gets the panel-wide defaults.
            类017 res = 类016.get(context, "v1/app/config", null, true);
            if (!res.success || res.data == null) return false;
            return applyConfig(context, res.data);
        } catch (Exception e) {
            return false; // cached values keep working — config is never fatal
        }
    }

    /**
     * Caches a configuration object delivered by {@code v1/app/config}. Unknown or
     * missing fields keep their current value, so a partial answer is always safe.
     */
    public static boolean applyConfig(Context context, JSONObject config) {
        if (config == null) return false;
        类046 prefs = 类046.get(context);
        boolean changed = false;

        String appName = config.optString("app_name", "");
        if (!appName.isEmpty() && !appName.equals(prefs.getThemeAppName())) {
            prefs.setThemeAppName(appName);
            changed = true;
        }
        String logo = config.optString("logo_url", "");
        if (!logo.equals(prefs.getThemeLogoUrl())) {
            prefs.setThemeLogoUrl(logo);
            changed = true;
        }
        String receipt = config.optString("receipt_template", "");
        if (!receipt.isEmpty()) prefs.setReceiptTemplate(receipt);

        // Support number: the single contact route of the whole app.
        JSONObject support = config.optJSONObject("support");
        String whatsapp = support != null
                ? support.optString("whatsapp", "")
                : config.optString("support_whatsapp", "");
        if (!whatsapp.isEmpty() && !whatsapp.equals(prefs.getSupportWhatsapp())) {
            prefs.setSupportWhatsapp(whatsapp);
            changed = true;
        }

        // How often the licence should be re-checked, published by the panel.
        int minutes = config.optInt("sync_minutes", 0);
        if (minutes > 0) prefs.setLicenseSyncMinutes(minutes);

        // Announcements shown in the notification centre.
        JSONArray notices = config.optJSONArray("notifications");
        if (notices != null) prefs.setNotices(notices.toString());

        return changed;
    }
}
