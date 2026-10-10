package com.quicktap.pos.backup;

import android.accounts.Account;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Base64;

import androidx.annotation.Nullable;

import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.Scope;
import com.quicktap.pos.BuildConfig;
import com.quicktap.pos.data.AppDatabase;
import com.quicktap.pos.util.类045;
import com.quicktap.pos.util.类046;
import com.quicktap.pos.util.类047;
import com.quicktap.pos.util.类049;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypted, latest-only Google Drive backup for the local POS database. */
public final class 类006 {
    private static final String SCOPE = "oauth2:https://www.googleapis.com/auth/drive.appdata";
    private static final String DRIVE = "https://www.googleapis.com/drive/v3/files";
    private static final String UPLOAD = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart&fields=id,name,size,md5Checksum";
    private static final String FOLDER_NAME = "QuickTap POS Backup";
    private static final String KEY_ALIAS = "quicktap_pos_backup_key_v1";
    private static final byte[] MAGIC = new byte[]{'Q','T','P','B','1'};
    public static final long WEEK_MS = 7L * 24 * 60 * 60 * 1000;
    public interface Callback { void onResult(boolean ok, String message); }
    private 类006() { }

    public static GoogleSignInClient signInClient(Context context) {
        return GoogleSignIn.getClient(context, new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                .requestEmail().requestScopes(new Scope("https://www.googleapis.com/auth/drive.appdata")).build());
    }
    public static Intent signInIntent(Context context) { return signInClient(context).getSignInIntent(); }
    public static boolean isConnected(Context context) { GoogleSignInAccount a = GoogleSignIn.getLastSignedInAccount(context); return a != null && a.getAccount() != null; }
    public static String connectedEmail(Context context) { GoogleSignInAccount a = GoogleSignIn.getLastSignedInAccount(context); return a == null ? null : a.getEmail(); }
    public static void disconnect(Context context, Callback cb) { signInClient(context).signOut().addOnCompleteListener(t -> { 类046.get(context).setDriveBackupEnabled(false); finish(cb, true, "Google Drive disconnected"); }); }

    public static void backupWeeklyIfDue(Context context, Callback cb) {
        类046 p = 类046.get(context);
        if (!p.isWeeklyBackupEnabled() || !isConnected(context)) { finish(cb, false, "Weekly backup is off or Google Drive is disconnected"); return; }
        if (System.currentTimeMillis() - p.getLastBackupAt() < WEEK_MS) { finish(cb, true, "Backup is up to date"); return; }
        backupNow(context, cb);
    }
    public static void backupNow(android.app.Activity activity, Callback cb) { backupNow(activity.getApplicationContext(), cb); }
    public static boolean backupNowBlocking(Context context) {
        CountDownLatch latch = new CountDownLatch(1); AtomicBoolean ok = new AtomicBoolean(false);
        backupNow(context, (success, message) -> { ok.set(success); latch.countDown(); });
        try { latch.await(130, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return ok.get();
    }

    public static void backupNow(Context context, Callback cb) {
        Context app = context.getApplicationContext();
        类045.io().execute(() -> {
            try {
                if (!isConnected(app)) { finish(cb, false, "Connect a Google account first"); return; }
                if (!com.quicktap.pos.net.类016.isOnline(app)) { finish(cb, false, "Backup postponed — Internet connection unavailable"); return; }
                if (Build.VERSION.SDK_INT < 23) { finish(cb, false, "Encrypted Drive backup requires Android 6 or newer"); return; }
                Account account = GoogleSignIn.getLastSignedInAccount(app).getAccount();
                String token = GoogleAuthUtil.getToken(app, account, SCOPE);
                File encrypted = createEncryptedSnapshot(app);
                String folderId = findOrCreateFolder(token);
                String name = "Latest Backup " + 类049.fileStamp(System.currentTimeMillis()) + ".qtpbackup";
                JSONObject uploaded = upload(token, folderId, name, encrypted);
                if (uploaded == null || uploaded.optString("id", "").isEmpty()) { finish(cb, false, "Backup failed. Your previous backup has been preserved."); return; }
                String id = uploaded.optString("id", "");
                if (!verifyRemote(token, id, encrypted.length())) { finish(cb, false, "Backup verification failed. Your previous backup has been preserved."); return; }
                String old = 类046.get(app).getLastBackupFileId();
                if (old != null && !old.isEmpty() && !old.equals(id)) deleteRemote(token, old);
                类046 p = 类046.get(app); p.setDriveBackupEnabled(true); p.setLastBackupAt(System.currentTimeMillis()); p.setLastBackupFileId(id);
                finish(cb, true, "Encrypted backup completed successfully");
            } catch (Exception e) { finish(cb, false, "Backup failed. Your previous backup has been preserved."); }
        });
    }

    public static void restoreLatest(Context context, Callback cb) {
        Context app = context.getApplicationContext();
        类045.io().execute(() -> {
            try {
                if (!isConnected(app)) { finish(cb, false, "Connect a Google account first"); return; }
                if (!com.quicktap.pos.net.类016.isOnline(app)) { finish(cb, false, "Restore requires an internet connection"); return; }
                if (Build.VERSION.SDK_INT < 23) { finish(cb, false, "Encrypted Drive restore requires Android 6 or newer"); return; }
                Account account = GoogleSignIn.getLastSignedInAccount(app).getAccount();
                String token = GoogleAuthUtil.getToken(app, account, SCOPE);
                String id = 类046.get(app).getLastBackupFileId();
                if (id == null || id.isEmpty()) { JSONObject latest = findLatest(token); id = latest == null ? "" : latest.optString("id", ""); }
                if (id.isEmpty()) { finish(cb, false, "No QuickTap POS backup found"); return; }
                byte[] remote = download(token, id);
                File restored = decryptAndValidate(app, remote);
                AppDatabase.closeInstance();
                File db = app.getDatabasePath(AppDatabase.NAME);
                File safety = new File(app.getCacheDir(), "pre_restore_safe.db");
                copy(db, safety);
                deleteQuietly(new File(db.getPath() + "-wal")); deleteQuietly(new File(db.getPath() + "-shm"));
                copy(restored, db);
                try { AppDatabase.get(app).query("PRAGMA integrity_check", null).close(); } catch (Exception bad) { copy(safety, db); AppDatabase.get(app); throw bad; }
                finish(cb, true, "Backup restored successfully. Please reopen the app.");
            } catch (Exception e) { finish(cb, false, "Restore failed. Current data was preserved where possible."); }
        });
    }

    public static File exportEncryptedSnapshot(Context context) throws Exception { return createEncryptedSnapshot(context.getApplicationContext()); }
    public static File validateAndDecryptSnapshot(Context context, byte[] payload) throws Exception { return decryptAndValidate(context.getApplicationContext(), payload); }

    private static File createEncryptedSnapshot(Context app) throws Exception {
        File liveSnapshot = 类047.createValidatedSnapshot(app);
        byte[] data;
        try { data = readAll(liveSnapshot); }
        finally { deleteQuietly(liveSnapshot); }
        JSONObject meta = new JSONObject().put("backup_version", 1).put("app_version", BuildConfig.VERSION_NAME).put("schema_version", 11).put("created_at", System.currentTimeMillis()).put("database_size", data.length).put("sha256", sha256(data));
        ByteArrayOutputStream plain = new ByteArrayOutputStream(); DataOutputStream out = new DataOutputStream(new BufferedOutputStream(plain)); out.write(MAGIC); out.writeUTF(meta.toString()); out.writeInt(data.length); out.write(data); out.flush();
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, key()); byte[] iv = cipher.getIV(); byte[] encrypted = cipher.doFinal(plain.toByteArray());
        File result = new File(app.getCacheDir(), "quicktap_latest.qtpbackup"); try (DataOutputStream file = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(result)))) { file.write(MAGIC); file.writeInt(iv.length); file.write(iv); file.write(encrypted); }
        return result;
    }
    private static File decryptAndValidate(Context app, byte[] payload) throws Exception {
        DataInputStream in = new DataInputStream(new BufferedInputStream(new ByteArrayInputStream(payload))); byte[] magic = new byte[5]; in.readFully(magic); if (!java.util.Arrays.equals(magic, MAGIC)) throw new java.io.IOException("Unsupported backup"); int ivLen = in.readInt(); if (ivLen < 12 || ivLen > 32) throw new java.io.IOException("Invalid backup"); byte[] iv = new byte[ivLen]; in.readFully(iv); byte[] cipherText = readAll(in); Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv)); byte[] plainBytes = cipher.doFinal(cipherText); DataInputStream plain = new DataInputStream(new BufferedInputStream(new ByteArrayInputStream(plainBytes))); byte[] inner = new byte[5]; plain.readFully(inner); if (!java.util.Arrays.equals(inner, MAGIC)) throw new java.io.IOException("Invalid backup"); JSONObject meta = new JSONObject(plain.readUTF()); if (meta.optInt("backup_version", 0) != 1) throw new java.io.IOException("Unsupported backup version"); int size = plain.readInt(); if (size <= 0 || size > 512 * 1024 * 1024) throw new java.io.IOException("Invalid backup size"); byte[] db = new byte[size]; plain.readFully(db); if (!sha256(db).equals(meta.optString("sha256"))) throw new java.io.IOException("Backup integrity check failed"); File result = new File(app.getCacheDir(), "quicktap_restore.db"); try (FileOutputStream out = new FileOutputStream(result)) { out.write(db); } return result;
    }
    private static SecretKey key() throws Exception { KeyStore ks = KeyStore.getInstance("AndroidKeyStore"); ks.load(null); if (!ks.containsAlias(KEY_ALIAS)) { KeyGenerator g = KeyGenerator.getInstance("AES", "AndroidKeyStore"); g.init(256); g.generateKey(); } return ((KeyStore.SecretKeyEntry) ks.getEntry(KEY_ALIAS, null)).getSecretKey(); }

    private static String findOrCreateFolder(String token) throws Exception { JSONObject found = findFolder(token); if (found != null) return found.optString("id"); JSONObject meta = new JSONObject().put("name", FOLDER_NAME).put("mimeType", "application/vnd.google-apps.folder").put("parents", new JSONArray().put("appDataFolder")); HttpURLConnection c = connection(DRIVE, token, "POST", "application/json"); c.setDoOutput(true); try (OutputStream o = c.getOutputStream()) { o.write(meta.toString().getBytes(StandardCharsets.UTF_8)); } if (c.getResponseCode() >= 400) throw new java.io.IOException("Folder create failed"); return new JSONObject(new String(readAll(c.getInputStream()), StandardCharsets.UTF_8)).optString("id"); }
    private static JSONObject findFolder(String token) throws Exception { String q = URLEncoder.encode("name='" + FOLDER_NAME + "' and mimeType='application/vnd.google-apps.folder' and 'appDataFolder' in parents and trashed=false", "UTF-8"); HttpURLConnection c = connection(DRIVE + "?spaces=appDataFolder&q=" + q + "&fields=files(id,name)", token, "GET", null); if (c.getResponseCode() >= 400) return null; JSONArray files = new JSONObject(new String(readAll(c.getInputStream()), StandardCharsets.UTF_8)).optJSONArray("files"); return files == null || files.length() == 0 ? null : files.getJSONObject(0); }
    private static JSONObject findLatest(String token) throws Exception { String folder = findOrCreateFolder(token); String q = URLEncoder.encode("'" + folder + "' in parents and trashed=false", "UTF-8"); HttpURLConnection c = connection(DRIVE + "?q=" + q + "&orderBy=createdTime desc&fields=files(id,name,size)", token, "GET", null); if (c.getResponseCode() >= 400) return null; JSONArray files = new JSONObject(new String(readAll(c.getInputStream()), StandardCharsets.UTF_8)).optJSONArray("files"); return files == null || files.length() == 0 ? null : files.getJSONObject(0); }
    private static JSONObject upload(String token, String folder, String name, File file) throws Exception { String b = "qtpos" + System.currentTimeMillis(); JSONObject meta = new JSONObject().put("name", name).put("parents", new JSONArray().put(folder)); HttpURLConnection c = connection(UPLOAD, token, "POST", "multipart/related; boundary=" + b); c.setDoOutput(true); try (OutputStream out = c.getOutputStream(); InputStream in = new FileInputStream(file)) { write(out, "--" + b + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n" + meta + "\r\n"); write(out, "--" + b + "\r\nContent-Type: application/octet-stream\r\n\r\n"); copy(in, out); write(out, "\r\n--" + b + "--\r\n"); } if (c.getResponseCode() >= 400) return null; return new JSONObject(new String(readAll(c.getInputStream()), StandardCharsets.UTF_8)); }
    private static boolean verifyRemote(String token, String id, long expected) throws Exception { HttpURLConnection c = connection(DRIVE + "/" + URLEncoder.encode(id, "UTF-8") + "?fields=id,size,name", token, "GET", null); if (c.getResponseCode() >= 400) return false; JSONObject o = new JSONObject(new String(readAll(c.getInputStream()), StandardCharsets.UTF_8)); return id.equals(o.optString("id")) && o.optLong("size", 0) == expected; }
    private static byte[] download(String token, String id) throws Exception { HttpURLConnection c = connection(DRIVE + "/" + URLEncoder.encode(id, "UTF-8") + "?alt=media", token, "GET", null); if (c.getResponseCode() >= 400) throw new java.io.IOException("Download failed"); return readAll(c.getInputStream()); }
    private static void deleteRemote(String token, String id) { try { HttpURLConnection c = connection(DRIVE + "/" + URLEncoder.encode(id, "UTF-8"), token, "DELETE", null); c.getResponseCode(); c.disconnect(); } catch (Exception ignored) { } }
    private static HttpURLConnection connection(String url, String token, String method, @Nullable String type) throws Exception { HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection(); c.setRequestMethod(method); c.setConnectTimeout(30000); c.setReadTimeout(120000); c.setRequestProperty("Authorization", "Bearer " + token); c.setRequestProperty("Accept", "application/json"); if (type != null) c.setRequestProperty("Content-Type", type); return c; }
    private static void write(OutputStream out, String value) throws Exception { out.write(value.getBytes(StandardCharsets.UTF_8)); }
    private static byte[] readAll(File f) throws Exception { try (InputStream in = new FileInputStream(f)) { return readAll(in); } }
    private static byte[] readAll(InputStream in) throws Exception { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); return out.toByteArray(); }
    private static void copy(File from, File to) throws Exception { try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) { copy(in, out); } }
    private static void copy(InputStream in, OutputStream out) throws Exception { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); out.flush(); }
    private static String sha256(byte[] b) throws Exception { byte[] d = MessageDigest.getInstance("SHA-256").digest(b); StringBuilder s = new StringBuilder(); for (byte x : d) s.append(String.format("%02x", x)); return s.toString(); }
    private static void deleteQuietly(File f) { if (f.exists()) f.delete(); }
    private static void finish(Callback cb, boolean ok, String msg) { if (cb != null) 类045.main().post(() -> cb.onResult(ok, msg)); }
}
