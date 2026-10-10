package com.quicktap.pos.util;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;
import org.json.JSONArray;

/** Wrapper over SharedPreferences for store, printer, licence and session settings. */
public class 类046 {

    private static final String FILE = "quicktap_prefs";
    private static 类046 instance;

    private final SharedPreferences sp;

    private 类046(Context context) {
        sp = context.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static synchronized 类046 get(Context context) {
        if (instance == null) instance = new 类046(context);
        return instance;
    }

    // ---- store profile ----
    public String getStoreName() { return sp.getString("store_name", "QuickTap Store"); }
    public void setStoreName(String v) { sp.edit().putString("store_name", v).apply(); }

    public String getStorePhone() { return sp.getString("store_phone", ""); }
    public void setStorePhone(String v) { sp.edit().putString("store_phone", v).apply(); }

    public String getStoreAddress() { return sp.getString("store_address", ""); }
    public void setStoreAddress(String v) { sp.edit().putString("store_address", v).apply(); }

    public String getCurrency() { return sp.getString("currency", "Rs"); }
    public void setCurrency(String v) { sp.edit().putString("currency", v).apply(); }

    public String getReceiptFooter() {
        return sp.getString("receipt_footer", "Thank you! Please visit again.");
    }
    public void setReceiptFooter(String v) { sp.edit().putString("receipt_footer", v).apply(); }

    public String getInvoicePrefix() { return sp.getString("invoice_prefix", "INV-"); }
    public void setInvoicePrefix(String v) { sp.edit().putString("invoice_prefix", v).apply(); }

    public synchronized int nextInvoiceSeq() {
        int next = sp.getInt("invoice_seq", 0) + 1;
        sp.edit().putInt("invoice_seq", next).apply();
        return next;
    }

    /** Returns the next invoice sequence without advancing it. */
    public int peekNextInvoiceSeq() {
        return sp.getInt("invoice_seq", 0) + 1;
    }

    /** Sets the last-used sequence so the next invoice becomes the supplied value. */
    public synchronized void setLastInvoiceSeq(int value) {
        sp.edit().putInt("invoice_seq", Math.max(0, value)).apply();
    }

    // ---- billing defaults ----
    public float getTaxPercent() { return sp.getFloat("tax_percent", 0f); }
    public void setTaxPercent(float v) { sp.edit().putFloat("tax_percent", v).apply(); }

    public float getDiscountPercent() { return sp.getFloat("discount_percent", 0f); }
    public void setDiscountPercent(float v) { sp.edit().putFloat("discount_percent", v).apply(); }

    // ---- optional inventory alerts ----
    public boolean isLowStockAlertsEnabled() { return sp.getBoolean("low_stock_alerts_enabled", true); }
    public void setLowStockAlertsEnabled(boolean enabled) { sp.edit().putBoolean("low_stock_alerts_enabled", enabled).apply(); }

    // ---- checkout payment features ----
    public boolean isPaymentPaidEnabled() { return sp.getBoolean("payment_paid_enabled", true); }
    public void setPaymentPaidEnabled(boolean v) { sp.edit().putBoolean("payment_paid_enabled", v).apply(); }
    public boolean isPaymentCodEnabled() { return sp.getBoolean("payment_cod_enabled", true); }
    public void setPaymentCodEnabled(boolean v) { sp.edit().putBoolean("payment_cod_enabled", v).apply(); }
    public boolean isPaymentDineInEnabled() { return sp.getBoolean("payment_dine_in_enabled", true); }
    public void setPaymentDineInEnabled(boolean v) { sp.edit().putBoolean("payment_dine_in_enabled", v).apply(); }
    public boolean isPaymentTakeAwayEnabled() { return sp.getBoolean("payment_takeaway_enabled", true); }
    public void setPaymentTakeAwayEnabled(boolean v) { sp.edit().putBoolean("payment_takeaway_enabled", v).apply(); }
    public boolean isPaymentDeliveryEnabled() { return sp.getBoolean("payment_delivery_enabled", true); }
    public void setPaymentDeliveryEnabled(boolean v) { sp.edit().putBoolean("payment_delivery_enabled", v).apply(); }

    // ---- configurable order types ----
    public java.util.List<String> getOrderTypes() {
        java.util.ArrayList<String> result = new java.util.ArrayList<>();
        String raw = sp.getString("order_types", "Dine In|Rider");
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                String v = a.optString(i, "").trim();
                if (!v.isEmpty() && !result.contains(v)) result.add(v);
            }
        } catch (Exception ignored) { }
        if (result.isEmpty()) { result.add("Dine In"); result.add("Rider"); }
        return result;
    }
    public void setOrderTypes(java.util.List<String> values) {
        JSONArray a = new JSONArray();
        if (values != null) for (String v : values) {
            if (v != null && !v.trim().isEmpty()) a.put(v.trim());
        }
        sp.edit().putString("order_types", a.toString()).apply();
    }

    // ---- central feature management ----
    public boolean isCustomerNameEnabled() { return sp.getBoolean("feature_customer_name", true); }
    public void setCustomerNameEnabled(boolean v) { sp.edit().putBoolean("feature_customer_name", v).apply(); }
    public boolean isCustomerPhoneEnabled() { return sp.getBoolean("feature_customer_phone", true); }
    public void setCustomerPhoneEnabled(boolean v) { sp.edit().putBoolean("feature_customer_phone", v).apply(); }
    public boolean isDeliveryAddressEnabled() { return sp.getBoolean("feature_delivery_address", true); }
    public void setDeliveryAddressEnabled(boolean v) { sp.edit().putBoolean("feature_delivery_address", v).apply(); }
    public boolean isOrderNoteEnabled() { return sp.getBoolean("feature_order_note", true); }
    public void setOrderNoteEnabled(boolean v) { sp.edit().putBoolean("feature_order_note", v).apply(); }
    public boolean isTableManagementEnabled() { return sp.getBoolean("feature_table_management", true); }
    public void setTableManagementEnabled(boolean v) { sp.edit().putBoolean("feature_table_management", v).apply(); }
    public boolean isStockManagementEnabled() { return sp.getBoolean("feature_stock_management", true); }
    public void setStockManagementEnabled(boolean v) { sp.edit().putBoolean("feature_stock_management", v).apply(); }
    public boolean isReceiptLogoFeatureEnabled() { return sp.getBoolean("feature_receipt_logo", true); }
    public void setReceiptLogoFeatureEnabled(boolean v) { sp.edit().putBoolean("feature_receipt_logo", v).apply(); }
    public boolean isReceiptHeaderFeatureEnabled() { return sp.getBoolean("feature_receipt_header", true); }
    public void setReceiptHeaderFeatureEnabled(boolean v) { sp.edit().putBoolean("feature_receipt_header", v).apply(); }
    public boolean isReceiptFooterFeatureEnabled() { return sp.getBoolean("feature_receipt_footer", true); }
    public void setReceiptFooterFeatureEnabled(boolean v) { sp.edit().putBoolean("feature_receipt_footer", v).apply(); }
    public boolean isReceiptCopiesFeatureEnabled() { return sp.getBoolean("feature_receipt_copies", true); }
    public void setReceiptCopiesFeatureEnabled(boolean v) { sp.edit().putBoolean("feature_receipt_copies", v).apply(); }

    // ---- appearance ----
    public boolean isDarkMode() { return sp.getBoolean("dark_mode", false); }
    public void setDarkMode(boolean v) { sp.edit().putBoolean("dark_mode", v).apply(); }

    /** "light" | "dark" | "system" — survives restarts. */
    public String getThemeMode() {
        return sp.getString("theme_mode", isDarkMode() ? "dark" : "light");
    }
    public void setThemeMode(String v) { sp.edit().putString("theme_mode", v).apply(); }


    // ---- printer ----
    public String getPrinterMac() { return sp.getString("printer_mac", null); }
    public String getPrinterName() { return sp.getString("printer_name", null); }
    public void setPrinter(String mac, String name) {
        sp.edit().putString("printer_mac", mac).putString("printer_name", name).apply();
    }
    /** 32 chars for 58mm rolls, 48 chars for 80mm rolls. */
    public int getPaperChars() { return sp.getInt("paper_chars", 32); }
    public void setPaperChars(int v) { sp.edit().putInt("paper_chars", v).apply(); }

    /** Number of physical receipt copies printed for one print action. */
    public int getReceiptCopies() { return Math.max(1, Math.min(10, sp.getInt("receipt_copies", 1))); }
    public void setReceiptCopies(int v) { sp.edit().putInt("receipt_copies", Math.max(1, Math.min(10, v))).apply(); }

    // ---- demo mode + session + licence ----
    public boolean isDemoMode() { return sp.getBoolean("demo_mode", false); }
    public void setDemoMode(boolean enabled) { sp.edit().putBoolean("demo_mode", enabled).apply(); }

    public String getUserEmail() { return sp.getString("user_email", null); }
    public void setUserEmail(String v) { sp.edit().putString("user_email", v).apply(); }

    public String getAuthToken() { return sp.getString("auth_token", null); }
    public void setAuthToken(String v) { sp.edit().putString("auth_token", v).apply(); }

    public String getDeviceId() { return sp.getString("device_id", null); }
    public void setDeviceId(String v) { sp.edit().putString("device_id", v).apply(); }

    /** PENDING | APPROVED | REJECTED | BLOCKED | EXPIRED */
    public String getLicenseStatus() { return sp.getString("license_status", "PENDING"); }
    public void setLicenseStatus(String v) { sp.edit().putString("license_status", v).apply(); }

    public String getLicenseMessage() { return sp.getString("license_message", ""); }
    public void setLicenseMessage(String v) { sp.edit().putString("license_message", v).apply(); }

    public long getLicenseExpiry() { return sp.getLong("license_expiry", 0L); }
    public void setLicenseExpiry(long v) { sp.edit().putLong("license_expiry", v).apply(); }

    public long getLastLicenseCheck() { return sp.getLong("license_checked", 0L); }
    public void setLastLicenseCheck(long v) { sp.edit().putLong("license_checked", v).apply(); }

    // ---- v1 licence cache (UX only — the server stays the authority) ----

    /** NO_ACCOUNT | PENDING | ACTIVE | EXPIRED | REVOKED | SUSPENDED | BLOCKED | UNKNOWN */
    public String getLicenseState() { return sp.getString("license_state_v1", "UNKNOWN"); }
    public void setLicenseState(String v) { sp.edit().putString("license_state_v1", v).apply(); }

    /** Human duration published by the server ("1 Year", "47 Days", "Lifetime"). */
    public String getLicenseDurationLabel() { return sp.getString("license_duration_label", ""); }
    public void setLicenseDurationLabel(String v) {
        sp.edit().putString("license_duration_label", v).apply();
    }

    /** Expiry in epoch ms as reported by the SERVER clock (0 = lifetime/unknown). */
    public long getLicenseExpiresAt() { return sp.getLong("license_expires_at_ms", 0L); }
    public void setLicenseExpiresAt(long v) { sp.edit().putLong("license_expires_at_ms", v).apply(); }

    public int getLicenseDaysLeft() { return sp.getInt("license_days_left", -1); }
    public void setLicenseDaysLeft(int v) { sp.edit().putInt("license_days_left", v).apply(); }
    public String getLastLicenseReminderKey() { return sp.getString("license_reminder_key", ""); }
    public void setLastLicenseReminderKey(String v) { sp.edit().putString("license_reminder_key", v == null ? "" : v).apply(); }

    /** True once the server accepted the username + password confirmation. */
    public boolean isLicenseConfirmed() { return sp.getBoolean("license_confirmed", false); }
    public void setLicenseConfirmed(boolean v) { sp.edit().putBoolean("license_confirmed", v).apply(); }

    /** Timestamp of the last SUCCESSFUL server answer (used for the offline grace). */
    public long getLastLicenseSuccessAt() { return sp.getLong("license_success_at", 0L); }
    public void setLastLicenseSuccessAt(long v) { sp.edit().putLong("license_success_at", v).apply(); }

    /** Server-published refresh interval for background licence checks. */
    public int getLicenseSyncMinutes() { return sp.getInt("license_sync_minutes", 60); }
    public void setLicenseSyncMinutes(int v) { sp.edit().putInt("license_sync_minutes", v).apply(); }

    /** Drops every cached licence hint (used on sign-out). */
    public void clearLicenseCache() {
        sp.edit().remove("license_state_v1").remove("license_duration_label")
                .remove("license_expires_at_ms").remove("license_days_left")
                .remove("license_confirmed").remove("license_success_at")
                .remove("license_reminder_key").apply();
    }

    public void signOut() {
        sp.edit().remove("auth_token").remove("user_email").apply();
    }

    // ---- JWT session (v2 API) ----
    public String getAccessToken() { return sp.getString("access_token", null); }
    public void setAccessToken(String v) { sp.edit().putString("access_token", v).apply(); }

    public String getRefreshToken() { return sp.getString("refresh_token", null); }
    public void setRefreshToken(String v) { sp.edit().putString("refresh_token", v).apply(); }

    public long getTokenExpiresAt() { return sp.getLong("token_expires_at", 0L); }
    public void setTokenExpiresAt(long v) { sp.edit().putLong("token_expires_at", v).apply(); }

    public String getUsername() { return sp.getString("username", null); }
    public void setUsername(String v) { sp.edit().putString("username", v).apply(); }

    public String getFullName() { return sp.getString("full_name", ""); }
    public void setFullName(String v) { sp.edit().putString("full_name", v).apply(); }

    public String getUserRole() { return sp.getString("user_role", "cashier"); }
    public void setUserRole(String v) { sp.edit().putString("user_role", v).apply(); }

    public String getShopName() { return sp.getString("shop_name", getStoreName()); }
    public void setShopName(String v) { sp.edit().putString("shop_name", v).apply(); }

    // ---- security: fingerprint + auto lock ----
    public boolean isFingerprintEnabled() { return sp.getBoolean("fingerprint_enabled", false); }
    public void setFingerprintEnabled(boolean v) { sp.edit().putBoolean("fingerprint_enabled", v).apply(); }

    /** Idle minutes before the lock screen appears. 0 disables auto lock. */
    public int getAutoLockMinutes() { return sp.getInt("auto_lock_minutes", 5); }
    public void setAutoLockMinutes(int v) { sp.edit().putInt("auto_lock_minutes", v).apply(); }

    public long getLastActivityAt() { return sp.getLong("last_activity_at", 0L); }
    public void touchActivity() { sp.edit().putLong("last_activity_at", System.currentTimeMillis()).apply(); }

    public boolean isLocked() { return sp.getBoolean("session_locked", false); }
    public void setLocked(boolean v) { sp.edit().putBoolean("session_locked", v).apply(); }

    // ---- server-driven theme ----
    public String getThemePrimary() { return sp.getString("theme_primary", ""); }
    public void setThemePrimary(String v) { sp.edit().putString("theme_primary", v).apply(); }

    public String getThemeSecondary() { return sp.getString("theme_secondary", ""); }
    public void setThemeSecondary(String v) { sp.edit().putString("theme_secondary", v).apply(); }

    public String getThemeAppName() { return sp.getString("theme_app_name", "QuickTap POS"); }
    public void setThemeAppName(String v) { sp.edit().putString("theme_app_name", v).apply(); }

    public String getThemeLogoUrl() { return sp.getString("theme_logo_url", ""); }
    public void setThemeLogoUrl(String v) { sp.edit().putString("theme_logo_url", v).apply(); }

    public int getThemeVersion() { return sp.getInt("theme_version", 0); }
    public void setThemeVersion(int v) { sp.edit().putInt("theme_version", v).apply(); }

    // ---- sync + backup ----
    /**
     * Automatic background sync. OFF by default: the cashier presses "Sync now".
     * Unattended syncing was re-uploading old rows and duplicating data.
     */
    public boolean isAutoSyncEnabled() { return sp.getBoolean("auto_sync", false); }
    public void setAutoSyncEnabled(boolean v) { sp.edit().putBoolean("auto_sync", v).apply(); }

    /** Weekly automatic backup that always replaces the previous copy. */
    public boolean isWeeklyBackupEnabled() { return sp.getBoolean("weekly_backup", true); }
    public void setWeeklyBackupEnabled(boolean v) { sp.edit().putBoolean("weekly_backup", v).apply(); }

    public long getLastSyncAt() { return sp.getLong("last_sync_at", 0L); }
    public void setLastSyncAt(long v) { sp.edit().putLong("last_sync_at", v).apply(); }

    public boolean isDriveBackupEnabled() { return sp.getBoolean("drive_backup", false); }
    public void setDriveBackupEnabled(boolean v) { sp.edit().putBoolean("drive_backup", v).apply(); }

    public long getLastBackupAt() { return sp.getLong("last_backup_at", 0L); }
    public void setLastBackupAt(long v) { sp.edit().putLong("last_backup_at", v).apply(); }

    public String getLastBackupFileId() { return sp.getString("last_backup_file", ""); }
    public void setLastBackupFileId(String v) { sp.edit().putString("last_backup_file", v).apply(); }

    public String getLocalBackupTreeUri() { return sp.getString("local_backup_tree_uri", ""); }
    public void setLocalBackupTreeUri(String v) { sp.edit().putString("local_backup_tree_uri", v == null ? "" : v).apply(); }
    public long getLastLocalBackupAt() { return sp.getLong("last_local_backup_at", 0L); }
    public void setLastLocalBackupAt(long v) { sp.edit().putLong("last_local_backup_at", v).apply(); }

    // ---- super-admin controlled presets ----
    public String getThemeKey() { return sp.getString("theme_key", "material_you"); }
    public void setThemeKey(String v) { sp.edit().putString("theme_key", v).apply(); }

    public boolean isReceiptPreviewEnabled() { return sp.getBoolean("receipt_preview_enabled", true); }
    public void setReceiptPreviewEnabled(boolean v) { sp.edit().putBoolean("receipt_preview_enabled", v).apply(); }

    public String getReceiptTemplateCode() { return sp.getString("receipt_template_code", ""); }
    public void setReceiptTemplateCode(String v) { sp.edit().putString("receipt_template_code", v == null ? "" : v).apply(); }

    public String getReceiptTemplate() { return sp.getString("receipt_template", "classic"); }
    public void setReceiptTemplate(String v) { sp.edit().putString("receipt_template", v).apply(); }

    /** WhatsApp number published by the Super Admin for every contact button. */
    public String getSupportWhatsapp() { return sp.getString("support_whatsapp", ""); }
    public void setSupportWhatsapp(String v) { sp.edit().putString("support_whatsapp", v).apply(); }

    /** JSON array of admin announcements shown in the notification centre. */
    public String getNotices() { return sp.getString("notices_json", ""); }
    public void setNotices(String v) { sp.edit().putString("notices_json", v).apply(); }

    /** Plan the Super Admin assigned to this shop (cached from /v1/plans). */
    public String getCurrentPlan() { return sp.getString("current_plan_json", ""); }
    public void setCurrentPlan(String v) { sp.edit().putString("current_plan_json", v).apply(); }


    /** Plan store published by the Super Admin (cached so it renders offline). */
    public String getPlanCatalog() { return sp.getString("plan_catalog", ""); }
    public void setPlanCatalog(String v) { sp.edit().putString("plan_catalog", v).apply(); }

    // ---- receipt design (all optional, owned by the shop) ----
    public boolean isReceiptLogoEnabled() { return sp.getBoolean("receipt_logo", false); }
    public void setReceiptLogoEnabled(boolean v) { sp.edit().putBoolean("receipt_logo", v).apply(); }

    /** Local file path of the monochrome logo printed on top of the slip. */
    public String getReceiptLogoPath() { return sp.getString("receipt_logo_path", ""); }
    public void setReceiptLogoPath(String v) { sp.edit().putString("receipt_logo_path", v).apply(); }

    public boolean isReceiptNoteEnabled() { return sp.getBoolean("receipt_note_on", false); }
    public void setReceiptNoteEnabled(boolean v) { sp.edit().putBoolean("receipt_note_on", v).apply(); }

    public String getReceiptNote() { return sp.getString("receipt_note", ""); }
    public void setReceiptNote(String v) { sp.edit().putString("receipt_note", v).apply(); }


        // ---- optional customer ledger ----
    public boolean isCustomerLedgerEnabled() { return sp.getBoolean("feature_customer_ledger", false); }
    public void setCustomerLedgerEnabled(boolean value) { sp.edit().putBoolean("feature_customer_ledger", value).apply(); }

    // Legacy methods remain source-compatible but no longer expose old modules.
    public boolean isCustomerManagementEnabled() { return isCustomerLedgerEnabled(); }
    public void setCustomerManagementEnabled(boolean value) { setCustomerLedgerEnabled(value); }
    public boolean isPurchaseSupplierEnabled() { return false; }
    public void setPurchaseSupplierEnabled(boolean value) { }
    public boolean isCashRegisterEnabled() { return false; }
    public void setCashRegisterEnabled(boolean value) { }
    public boolean isReturnsRefundsEnabled() { return false; }
    public void setReturnsRefundsEnabled(boolean value) { }
    public boolean isAdvancedReportsEnabled() { return false; }
    public void setAdvancedReportsEnabled(boolean value) { }

    // ---- server-driven splash screen ----
    public String getSplashJson() { return sp.getString("splash_json", ""); }
    public void setSplashJson(String v) { sp.edit().putString("splash_json", v == null ? "" : v).apply(); }

    private JSONObject splash() {
        String raw = getSplashJson();
        if (raw == null || raw.trim().isEmpty()) return new JSONObject();
        try { return new JSONObject(raw); } catch (Exception e) { return new JSONObject(); }
    }

    public boolean isSplashEnabled() { return splash().optBoolean("enabled", true); }
    public String getSplashTitle() { return splash().optString("title", getThemeAppName()); }
    public String getSplashTagline() { return splash().optString("tagline", "Fast counter. Faster sales."); }
    public String getSplashCreditPrefix() { return splash().optString("credit_prefix", "Powered by"); }
    public String getSplashCreditText() { return splash().optString("credit_text", "MA Technologies"); }
    public String getSplashLogoUrl() { return splash().optString("logo_url", getThemeLogoUrl()); }
    public String getSplashBackgroundColor() { return splash().optString("background_color", ""); }
    public String getSplashTextColor() { return splash().optString("text_color", ""); }
    public String getSplashAccentColor() { return splash().optString("accent_color", getThemePrimary()); }
    /** fade | zoom | slide_up | pulse | rotate */
    public String getSplashAnimation() { return splash().optString("animation", "fade"); }
    public int getSplashDurationMs() { return splash().optInt("duration_ms", 1400); }
    public boolean isSplashShowCredit() { return splash().optBoolean("show_credit", true); }
    public boolean isSplashShowProgress() { return splash().optBoolean("show_progress", true); }
    public int getSplashVersion() { return splash().optInt("version", 0); }

    /** Wipes tokens and session state but keeps store settings and cached data. */
    public void clearSession() {
        sp.edit()
                .remove("access_token").remove("refresh_token").remove("token_expires_at")
                .remove("auth_token").remove("session_locked").remove("last_activity_at")
                .apply();
    }
}
