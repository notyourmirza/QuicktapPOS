package com.quicktap.pos.cloudsync;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Android-Keystore backed storage for the customer's Cloud Sync API key.
 *
 * The key is encrypted with an AES-256/GCM key that lives in the hardware
 * keystore, so it can never be read out of the preferences file. It is also
 * never written to logcat - every accessor here is used only inside the sync
 * module and the value is masked in the UI.
 */
public final class CloudSyncSecureStore {

    private static final String KS = "AndroidKeyStore";
    private static final String ALIAS = "quicktap_cloud_sync";
    private static final String PREF = "cloud_sync_secure";

    private static final String ENDPOINT = "endpoint";
    private static final String KEY = "api_key";
    private static final String DEVICE = "device_uid";
    private static final String DEVICE_NAME = "device_name";

    private final Context context;
    private final SharedPreferences prefs;

    private CloudSyncSecureStore(Context context) {
        this.context = context.getApplicationContext();
        prefs = this.context.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        ensureKey();
    }

    public static CloudSyncSecureStore get(Context context) {
        return new CloudSyncSecureStore(context);
    }

    // ------------------------------------------------------------- settings

    public String endpoint() {
        return prefs.getString(ENDPOINT, "");
    }

    /** Trims on paste: a trailing space or newline is the most common typo. */
    public void saveEndpoint(String value) {
        prefs.edit().putString(ENDPOINT, value == null ? "" : value.trim()).apply();
    }

    public String apiKey() {
        return decrypt(prefs.getString(KEY, ""));
    }

    public void saveApiKey(String value) {
        prefs.edit().putString(KEY, encrypt(value == null ? "" : value.trim())).apply();
    }

    /**
     * Stable per-installation id, generated once and then kept forever.
     *
     * It deliberately reuses the fingerprint the app already sends at login
     * (com.quicktap.pos.auth.类003). That is the value stored in
     * devices.device_id, so a Cloud Sync push from this device updates the
     * right row - last_sync_at and the plan's device-usage counter only work
     * when the two ids match.
     */
    public String deviceUid() {
        String existing = prefs.getString(DEVICE, "");
        if (existing != null && !existing.isEmpty()) {
            return existing;
        }
        String uid = "";
        try {
            uid = com.quicktap.pos.auth.类003.id(context);
        } catch (Throwable ignored) {
            // fall back to a locally generated id below
        }
        if (uid == null || uid.isEmpty()) {
            uid = "dev-" + java.util.UUID.randomUUID().toString().replace("-", "");
        }
        saveDeviceUid(uid);
        return uid;
    }

    private void saveDeviceUid(String value) {
        prefs.edit().putString(DEVICE, value).apply();
    }

    public String deviceName() {
        return prefs.getString(DEVICE_NAME, android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
    }

    public void saveDeviceName(String value) {
        prefs.edit().putString(DEVICE_NAME, value == null ? "" : value.trim()).apply();
    }

    /** True when both fields are present and the endpoint is HTTPS. */
    public boolean isConfigured() {
        String endpoint = endpoint();
        return !apiKey().isEmpty()
                && endpoint != null
                && endpoint.toLowerCase(java.util.Locale.US).startsWith("https://");
    }

    /** Clears key + endpoint but leaves local POS data completely untouched. */
    public void clear() {
        prefs.edit().remove(ENDPOINT).remove(KEY).apply();
    }

    // ------------------------------------------------------------ encryption

    private void ensureKey() {
        try {
            KeyStore ks = KeyStore.getInstance(KS);
            ks.load(null);
            if (!ks.containsAlias(ALIAS)) {
                KeyGenerator generator = KeyGenerator.getInstance(
                        KeyProperties.KEY_ALGORITHM_AES, KS);
                generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                        KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .build());
                generator.generateKey();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Secure storage unavailable", e);
        }
    }

    private SecretKey key() throws Exception {
        KeyStore ks = KeyStore.getInstance(KS);
        ks.load(null);
        return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
    }

    private String encrypt(String plain) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            byte[] iv = cipher.getIV();
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(iv, Base64.NO_WRAP) + ":"
                    + Base64.encodeToString(cipherText, Base64.NO_WRAP);
        } catch (Exception e) {
            throw new IllegalStateException("Secure storage failed", e);
        }
    }

    private String decrypt(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        try {
            String[] parts = value.split(":", 2);
            if (parts.length != 2) {
                return "";
            }
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(),
                    new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)),
                    StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }
}
