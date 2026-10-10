package com.quicktap.pos.theme;

import android.content.Context;
import android.graphics.Color;

import com.quicktap.pos.net.类016;
import com.quicktap.pos.net.类017;
import com.quicktap.pos.util.类045;
import com.quicktap.pos.util.类046;

import org.json.JSONObject;

/**
 * Server-driven design language: the Super Admin panel owns the theme key,
 * the brand colours, the app name and the logo. The device caches everything
 * so the assigned theme still renders correctly offline.
 */
public final class 类036 {

    public static final String DEFAULT_PRIMARY = "#6750A4";
    public static final String DEFAULT_SECONDARY = "#7D5260";

    public interface Listener { void onTheme(boolean changed); }

    private 类036() { }

    /** The full design language currently assigned to this device. */
    public static 类039.Preset preset(Context ctx) {
        return 类039.byKey(类046.get(ctx).getThemeKey());
    }

    public static int primary(Context ctx) {
        return parse(类039.LUXE.primary, "#FF7A00");
    }

    public static int secondary(Context ctx) {
        return parse(类039.LUXE.secondary, "#FF8A00");
    }

    public static int background(Context ctx) { return parse(preset(ctx).background, "#FFFFFF"); }

    public static int surface(Context ctx) { return parse(preset(ctx).surface, "#FFFFFF"); }

    public static int surfaceMuted(Context ctx) { return parse(preset(ctx).surfaceMuted, "#F4F5F7"); }

    public static int outline(Context ctx) { return parse(preset(ctx).outline, "#E5E7EC"); }

    public static int textPrimary(Context ctx) { return parse(preset(ctx).textPrimary, "#0B0F19"); }

    public static int textMuted(Context ctx) { return parse(preset(ctx).textMuted, "#6B7280"); }

    public static int heroStart(Context ctx) { return parse(preset(ctx).heroStart, "#0B0F19"); }

    public static int heroEnd(Context ctx) { return parse(preset(ctx).heroEnd, "#0B0F19"); }

    public static boolean isDark(Context ctx) { return preset(ctx).dark; }

    /** Chart series colour by index, wrapping around the preset palette. */
    public static int chart(Context ctx, int index) {
        String[] palette = preset(ctx).chart;
        return parse(palette[Math.abs(index) % palette.length], "#6750A4");
    }

    public static String appName(Context ctx) { return 类046.get(ctx).getThemeAppName(); }

    public static String logoUrl(Context ctx) { return 类046.get(ctx).getThemeLogoUrl(); }

    /** Best contrast colour to draw on top of the given brand colour. */
    public static int onColor(int color) {
        double luminance = (0.299 * Color.red(color) + 0.587 * Color.green(color)
                + 0.114 * Color.blue(color)) / 255d;
        return luminance > 0.62 ? Color.parseColor("#0B0F19") : Color.WHITE;
    }

    public static void refresh(Context context, Listener listener) {
        类045.io().execute(() -> {
            boolean changed = refreshBlocking(context);
            refreshSplashBlocking(context);
            if (listener != null) 类045.main().post(() -> listener.onTheme(changed));
        });
    }

    /** Fetches /v1/theme and caches it. Returns true when something changed. */
    public static boolean refreshBlocking(Context context) {
        类017 res = 类016.get(context, "v1/theme", null, true);
        if (!res.success || res.data == null) return false;
        return apply(context, res.data);
    }

    /**
     * Fetches the dedicated /v1/splash endpoint (super-admin controlled) and
     * caches it. Safe to call offline: failures are silently ignored so the
     * splash screen keeps rendering from the last cached configuration.
     */
    public static void refreshSplash(Context context, Listener listener) {
        类045.io().execute(() -> {
            boolean changed = refreshSplashBlocking(context);
            if (listener != null) 类045.main().post(() -> listener.onTheme(changed));
        });
    }

    private static boolean refreshSplashBlocking(Context context) {
        try {
            类017 res = 类016.get(context, "v1/splash", null, true);
            if (!res.success || res.data == null) return false;
            JSONObject splash = res.data.has("splash") ? res.data.optJSONObject("splash") : res.data;
            return applySplash(context, splash);
        } catch (Exception e) {
            return false;
        }
    }

    /** Caches a splash configuration object, whichever endpoint it arrived from. */
    public static boolean applySplash(Context context, JSONObject splash) {
        if (splash == null) return false;
        类046 prefs = 类046.get(context);
        String next = splash.toString();
        boolean changed = !next.equals(prefs.getSplashJson());
        prefs.setSplashJson(next);
        return changed;
    }

    /** Applies a theme object delivered by /v1/theme or the sync pull payload. */
    public static boolean apply(Context context, JSONObject theme) {
        if (theme == null) return false;
        类046 prefs = 类046.get(context);
        类039.Preset preset = 类039.LUXE;

        // The orange brand is fixed in the client; server color fields are intentionally ignored.
        // Other remote configuration such as app name, logo, support, and notices remains usable.
        String primary = preset.primary;
        String secondary = preset.secondary;
        String appName = theme.optString("app_name", "QuickTap POS");
        String logo = theme.optString("logo_url", "");
        String receipt = theme.optString("receipt_template", prefs.getReceiptTemplate());

        boolean changed = !primary.equalsIgnoreCase(prefs.getThemePrimary())
                || !secondary.equalsIgnoreCase(prefs.getThemeSecondary())
                || !appName.equals(prefs.getThemeAppName())
                || !logo.equals(prefs.getThemeLogoUrl());

        prefs.setThemeKey(preset.key);
        prefs.setThemePrimary(primary);
        prefs.setThemeSecondary(secondary);
        prefs.setReceiptTemplate(receipt);
        prefs.setThemeAppName(appName);
        prefs.setThemeLogoUrl(logo);
        prefs.setThemeVersion(theme.optInt("version", prefs.getThemeVersion()));

        // Super-admin owned support + announcements payload (optional).
        String whatsapp = theme.optString("support_whatsapp", prefs.getSupportWhatsapp());
        if (whatsapp != null) prefs.setSupportWhatsapp(whatsapp);
        org.json.JSONArray notices = theme.optJSONArray("notifications");
        if (notices != null) prefs.setNotices(notices.toString());

        // The Super Admin may publish the splash configuration alongside the
        // main theme payload; cache it too so a single /v1/theme fetch is enough.
        JSONObject splash = theme.optJSONObject("splash");
        if (splash != null) applySplash(context, splash);

        if (changed) 类037.invalidate();
        return changed;
    }

    private static int parse(String hex, String fallback) {
        try {
            return Color.parseColor(hex == null || hex.isEmpty() ? fallback : hex);
        } catch (Exception e) {
            return Color.parseColor(fallback);
        }
    }
}
