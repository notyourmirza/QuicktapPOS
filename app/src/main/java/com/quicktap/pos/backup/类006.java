package com.quicktap.pos.backup;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.util.Log;

import androidx.annotation.Nullable;

import com.google.android.gms.auth.GoogleAuthException;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.UserRecoverableAuthException;
import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInClient;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.Scope;
import com.quicktap.pos.data.AppDatabase;
import com.quicktap.pos.net.类016;
import com.quicktap.pos.util.类045;
import com.quicktap.pos.util.类046;
import com.quicktap.pos.util.类047;
import com.quicktap.pos.util.类049;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Encrypted, latest-only Google Drive backup of the local POS database.
 *
 * <p>Design rules that matter:</p>
 * <ul>
 *   <li>Files live in the app's private {@code appDataFolder}, so no other app and
 *       no browsing user can see them, and they never count against visible quota.</li>
 *   <li>Only the OAuth access token for {@code drive.appdata} is used — the shop's
 *       Google password never touches this app, and no API key is needed.</li>
 *   <li>The payload is encrypted before it leaves the device (see {@link BackupCrypto}).</li>
 *   <li>One file is kept: a new copy is uploaded and verified <em>first</em>, and the
 *       previous one is deleted only afterwards, so a failed run never loses data.</li>
 *   <li>Every failure reports the stage that failed, because a silent "backup
 *       failed" toast is what makes shops give up on backups entirely.</li>
 * </ul>
 */
public final class 类006 {

    private static final String TAG = "DriveBackup";
    private static final String SCOPE_URL = "https://www.googleapis.com/auth/drive.appdata";
    private static final String SCOPE = "oauth2:" + SCOPE_URL;
    private static final String DRIVE = "https://www.googleapis.com/drive/v3/files";
    private static final String UPLOAD = "https://www.googleapis.com/upload/drive/v3/files"
            + "?uploadType=multipart&fields=id,name,size";
    private static final String FOLDER_NAME = "QuickTap POS Backup";
    public static final long WEEK_MS = 7L * 24 * 60 * 60 * 1000;

    public interface Callback { void onResult(boolean ok, String message); }

    private 类006() { }

    // ------------------------------------------------------------- sign in

    /**
     * Sign-in options for the Drive scope.
     *
     * <p>{@code default_web_client_id} is generated from {@code google-services.json}
     * by the Google Services Gradle plugin. Without a matching OAuth client Google
     * rejects the request with {@code DEVELOPER_ERROR (10)} — a SHA-1 of the signing
     * certificate and the package name must be registered in the same project.</p>
     */
    public static GoogleSignInClient signInClient(Context context) {
        GoogleSignInOptions.Builder builder =
                new GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
                        .requestEmail()
                        .requestScopes(new Scope(SCOPE_URL));
        String webClientId = webClientId(context);
        if (webClientId != null) builder.requestIdToken(webClientId);
        return GoogleSignIn.getClient(context, builder.build());
    }

    public static Intent signInIntent(Context context) {
        return signInClient(context).getSignInIntent();
    }

    /** The OAuth web client id from google-services.json, or null when absent. */
    @Nullable
    public static String webClientId(Context context) {
        try {
            int id = context.getResources().getIdentifier(
                    "default_web_client_id", "string", context.getPackageName());
            if (id == 0) return null;
            String value = context.getString(id);
            return value == null || value.trim().isEmpty() ? null : value.trim();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * False when the app was built without a Google OAuth client. Drive backup
     * cannot work then, and saying so in plain words beats an opaque sign-in error.
     */
    public static boolean isOAuthConfigured(Context context) {
        try {
            GoogleSignIn.getLastSignedInAccount(context);
            return webClientId(context.getApplicationContext()) != null;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isConnected(Context context) {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(context);
        return account != null && account.getAccount() != null;
    }

    @Nullable
    public static String connectedEmail(Context context) {
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(context);
        return account == null ? null : account.getEmail();
    }

    public static void disconnect(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        signInClient(app).signOut().addOnCompleteListener(task -> {
            类046.get(app).setDriveBackupEnabled(false);
            finish(callback, true, "Google Drive disconnected");
        });
    }

    // ---------------------------------------------------------------- backup

    public static void backupWeeklyIfDue(Context context, Callback callback) {
        类046 prefs = 类046.get(context);
        if (!prefs.isWeeklyBackupEnabled() || !isConnected(context)) {
            finish(callback, false, "Weekly backup is off or Google Drive is disconnected");
            return;
        }
        if (System.currentTimeMillis() - prefs.getLastBackupAt() < WEEK_MS) {
            finish(callback, true, "Backup is up to date");
            return;
        }
        backupNow(context, null, callback);
    }

    /** Device-key sealed backup, exactly as the automatic weekly run does it. */
    public static void backupNow(Context context, Callback callback) {
        backupNow(context, null, callback);
    }

    public static void backupNow(android.app.Activity activity, Callback callback) {
        backupNow(activity.getApplicationContext(), null, callback);
    }

    /**
     * @param passphrase optional; empty or null seals the file with this device's
     *                   hardware key, any other value lets the same file be
     *                   restored on a different phone.
     */
    public static void backupNow(Context context, @Nullable String passphrase, Callback callback) {
        Context app = context.getApplicationContext();
        类045.io().execute(() -> {
            File encrypted = null;
            try {
                String token = requireToken(app);
                encrypted = createEncryptedSnapshot(app, passphrase);
                String folderId = findOrCreateFolder(token);
                String name = "Latest Backup " + 类049.fileStamp(System.currentTimeMillis()) + ".qtpbackup";
                JSONObject uploaded = upload(token, folderId, name, encrypted);
                if (uploaded == null || uploaded.optString("id", "").isEmpty()) {
                    fail(app, callback, "Drive rejected the upload. Your previous backup is untouched.");
                    return;
                }
                String id = uploaded.optString("id", "");
                if (!verifyRemote(token, id, encrypted.length())) {
                    fail(app, callback, "Drive could not confirm the uploaded file. "
                            + "Your previous backup is untouched.");
                    return;
                }
                String old = 类046.get(app).getLastBackupFileId();
                if (old != null && !old.isEmpty() && !old.equals(id)) deleteRemote(token, old);

                类046 prefs = 类046.get(app);
                prefs.setDriveBackupEnabled(true);
                prefs.setLastBackupAt(System.currentTimeMillis());
                prefs.setLastBackupFileId(id);
                prefs.setDriveLastError("");
                prefs.setDriveBackupPassphrased(passphrase != null && !passphrase.isEmpty());
                finish(callback, true, passphrase == null || passphrase.isEmpty()
                        ? "Backup uploaded (restorable on this device)"
                        : "Backup uploaded (protected by your passphrase)");
            } catch (ConsentRequired required) {
                needConsent(app, required.intent);
                fail(app, callback, "Google needs the Drive permission again. Approve the "
                        + "prompt that just opened, then tap once more.");
            } catch (NotSignedIn notSignedIn) {
                fail(app, callback, "Connect your Google account in Settings first.");
            } catch (Exception e) {
                fail(app, callback, "Backup failed: " + reason(e)
                        + ". Your previous backup is untouched.");
            } finally {
                if (encrypted != null) encrypted.delete();
            }
        });
    }

    /** Blocks the calling (background) thread until the run finishes. */
    public static boolean backupNowBlocking(Context context) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean ok = new AtomicBoolean(false);
        backupNow(context, (success, message) -> {
            ok.set(success);
            latch.countDown();
        });
        try {
            latch.await(130, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return ok.get();
    }

    // --------------------------------------------------------------- restore

    public static void restoreLatest(Context context, Callback callback) {
        restoreLatest(context, null, callback);
    }

    /**
     * Downloads the newest backup and replaces the live database with it. A safety
     * copy of the current database is kept, and the swap only sticks when SQLite
     * reports the restored file as intact.
     */
    public static void restoreLatest(Context context, @Nullable String passphrase, Callback callback) {
        Context app = context.getApplicationContext();
        类045.io().execute(() -> {
            try {
                String token = requireToken(app);
                String id = 类046.get(app).getLastBackupFileId();
                if (id == null || id.isEmpty()) {
                    JSONObject latest = findLatest(token);
                    id = latest == null ? "" : latest.optString("id", "");
                }
                if (id.isEmpty()) {
                    fail(app, callback, "No QuickTap POS backup found in your Drive.");
                    return;
                }
                byte[] remote = download(token, id);
                File restored;
                try {
                    restored = BackupCrypto.decrypt(
                            new File(app.getCacheDir(), "quicktap_restore.db"), remote, passphrase);
                } catch (BackupCrypto.PassphraseRequiredException needsPassphrase) {
                    fail(app, callback, "This backup is protected by a passphrase. "
                            + "Type it in the backup dialog and try again.");
                    return;
                }
                installDatabase(app, restored);
                类046.get(app).setDriveLastError("");
                finish(callback, true, "Backup restored successfully. Please reopen the app.");
            } catch (ConsentRequired required) {
                needConsent(app, required.intent);
                fail(app, callback, "Google needs the Drive permission again. Approve the "
                        + "prompt that just opened, then try the restore once more.");
            } catch (NotSignedIn notSignedIn) {
                fail(app, callback, "Connect the Google account that holds the backup first.");
            } catch (Exception e) {
                fail(app, callback, "Restore failed: " + reason(e));
            }
        });
    }

    /** Shared by the Drive and the file-based restores. */
    static void installDatabase(Context app, File restored) throws Exception {
        AppDatabase.closeInstance();
        File db = app.getDatabasePath(AppDatabase.NAME);
        File safety = new File(app.getCacheDir(), "pre_restore_safe.db");
        if (db.exists()) copy(db, safety);
        deleteQuietly(new File(db.getPath() + "-wal"));
        deleteQuietly(new File(db.getPath() + "-shm"));
        copy(restored, db);
        try {
            AppDatabase.get(app).query("PRAGMA integrity_check", null).close();
        } catch (Exception bad) {
            if (safety.exists()) copy(safety, db);
            AppDatabase.get(app);
            throw new java.io.IOException("The backup did not pass the integrity check, "
                    + "so your current data was kept.");
        }
    }

    // ------------------------------------------------- snapshot for file saves

    /** Encrypts the live database into a cache file (used by the file backup too). */
    public static File exportEncryptedSnapshot(Context context) throws Exception {
        return createEncryptedSnapshot(context.getApplicationContext(), null);
    }

    public static File exportEncryptedSnapshot(Context context, @Nullable String passphrase)
            throws Exception {
        return createEncryptedSnapshot(context.getApplicationContext(), passphrase);
    }

    public static File validateAndDecryptSnapshot(Context context, byte[] payload) throws Exception {
        return BackupCrypto.decrypt(new File(context.getCacheDir(), "quicktap_restore.db"),
                payload, null);
    }

    public static File validateAndDecryptSnapshot(Context context, byte[] payload,
                                                   @Nullable String passphrase) throws Exception {
        return BackupCrypto.decrypt(new File(context.getCacheDir(), "quicktap_restore.db"),
                payload, passphrase);
    }

    /** True when a saved file can only be opened with a passphrase. */
    public static boolean requiresPassphrase(byte[] payload) {
        return BackupCrypto.requiresPassphrase(payload);
    }

    private static File createEncryptedSnapshot(Context app, @Nullable String passphrase)
            throws Exception {
        File snapshot = 类047.createValidatedSnapshot(app);
        byte[] data;
        try {
            try (InputStream in = new FileInputStream(snapshot)) {
                data = BackupCrypto.readAll(in);
            }
        } finally {
            deleteQuietly(snapshot);
        }
        File result = new File(app.getCacheDir(), "quicktap_latest.qtpbackup");
        deleteQuietly(result);
        return BackupCrypto.encrypt(result, data, passphrase);
    }

    // ------------------------------------------------------------ google auth

    private static final class NotSignedIn extends Exception { }

    private static final class ConsentRequired extends Exception {
        final Intent intent;
        ConsentRequired(Intent intent) { this.intent = intent; }
    }

    /**
     * A live OAuth token for the appData scope. Google may answer with a
     * recoverable "grant permission" request, which we surface immediately instead
     * of failing with a mystery error.
     */
    private static String requireToken(Context app) throws Exception {
        if (!isConnected(app)) throw new NotSignedIn();
        if (Build.VERSION.SDK_INT < 23) {
            throw new java.io.IOException("encrypted backups require Android 6 or newer");
        }
        if (!类016.isOnline(app)) {
            throw new java.io.IOException("no internet connection");
        }
        GoogleSignInAccount account = GoogleSignIn.getLastSignedInAccount(app);
        try {
            String token = GoogleAuthUtil.getToken(app, account.getAccount(), SCOPE);
            if (token == null || token.trim().isEmpty()) {
                throw new java.io.IOException("Google returned no access token");
            }
            类046.get(app).setDriveNeedsConsent(false);
            return token;
        } catch (UserRecoverableAuthException recoverable) {
            throw new ConsentRequired(recoverable.getIntent());
        } catch (GoogleAuthException refused) {
            throw new java.io.IOException("Google refused the Drive permission ("
                    + refused.getMessage() + ")");
        }
    }

    /** Opens Google's own "let QuickTap use Drive" screen when it asks for it. */
    private static void needConsent(Context app, @Nullable Intent intent) {
        类046.get(app).setDriveNeedsConsent(true);
        if (intent == null) return;
        try {
            app.startActivity(intent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP));
        } catch (Exception ignored) {
            // No window available right now — the status line tells the user instead.
        }
    }

    // --------------------------------------------------------------- drive api

    private static String findOrCreateFolder(String token) throws Exception {
        JSONObject found = findFolder(token);
        if (found != null) return found.optString("id");
        JSONObject meta = new JSONObject()
                .put("name", FOLDER_NAME)
                .put("mimeType", "application/vnd.google-apps.folder")
                .put("parents", new JSONArray().put("appDataFolder"));
        HttpURLConnection c = connection(DRIVE, token, "POST", "application/json");
        c.setDoOutput(true);
        try (OutputStream out = c.getOutputStream()) {
            out.write(meta.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (c.getResponseCode() >= 400) throw new java.io.IOException("the backup folder could not be created");
        return new JSONObject(new String(readAll(c.getInputStream()), StandardCharsets.UTF_8))
                .optString("id");
    }

    private static JSONObject findFolder(String token) throws Exception {
        String query = URLEncoder.encode("name='" + FOLDER_NAME
                + "' and mimeType='application/vnd.google-apps.folder' "
                + "and 'appDataFolder' in parents and trashed=false", "UTF-8");
        HttpURLConnection c = connection(DRIVE + "?spaces=appDataFolder&q=" + query
                + "&fields=files(id,name)", token, "GET", null);
        if (c.getResponseCode() >= 400) return null;
        JSONArray files = new JSONObject(new String(readAll(c.getInputStream()), StandardCharsets.UTF_8))
                .optJSONArray("files");
        return files == null || files.length() == 0 ? null : files.getJSONObject(0);
    }

    private static JSONObject findLatest(String token) throws Exception {
        String folder = findOrCreateFolder(token);
        String query = URLEncoder.encode("'" + folder + "' in parents and trashed=false", "UTF-8");
        HttpURLConnection c = connection(DRIVE + "?q=" + query
                + "&orderBy=createdTime desc&fields=files(id,name,size)", token, "GET", null);
        if (c.getResponseCode() >= 400) return null;
        JSONArray files = new JSONObject(new String(readAll(c.getInputStream()), StandardCharsets.UTF_8))
                .optJSONArray("files");
        return files == null || files.length() == 0 ? null : files.getJSONObject(0);
    }

    private static JSONObject upload(String token, String folder, String name, File file)
            throws Exception {
        String boundary = "qtpos" + System.currentTimeMillis();
        JSONObject meta = new JSONObject()
                .put("name", name)
                .put("parents", new JSONArray().put(folder));
        HttpURLConnection c = connection(UPLOAD, token, "POST",
                "multipart/related; boundary=" + boundary);
        c.setDoOutput(true);
        try (OutputStream out = c.getOutputStream(); InputStream in = new FileInputStream(file)) {
            write(out, "--" + boundary + "\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"
                    + meta + "\r\n");
            write(out, "--" + boundary + "\r\nContent-Type: application/octet-stream\r\n\r\n");
            copy(in, out);
            write(out, "\r\n--" + boundary + "--\r\n");
        }
        int code = c.getResponseCode();
        if (code >= 400) {
            String body = readError(c);
            Log.w(TAG, "Drive upload " + code + ": " + body);
            return null;
        }
        return new JSONObject(new String(readAll(c.getInputStream()), StandardCharsets.UTF_8));
    }

    private static boolean verifyRemote(String token, String id, long expected) throws Exception {
        HttpURLConnection c = connection(DRIVE + "/" + URLEncoder.encode(id, "UTF-8")
                + "?fields=id,size,name", token, "GET", null);
        if (c.getResponseCode() >= 400) return false;
        JSONObject o = new JSONObject(new String(readAll(c.getInputStream()), StandardCharsets.UTF_8));
        return id.equals(o.optString("id")) && o.optLong("size", 0) == expected;
    }

    private static byte[] download(String token, String id) throws Exception {
        HttpURLConnection c = connection(DRIVE + "/" + URLEncoder.encode(id, "UTF-8")
                + "?alt=media", token, "GET", null);
        if (c.getResponseCode() >= 400) throw new java.io.IOException("the backup could not be downloaded");
        return readAll(c.getInputStream());
    }

    private static void deleteRemote(String token, String id) {
        try {
            HttpURLConnection c = connection(DRIVE + "/" + URLEncoder.encode(id, "UTF-8"),
                    token, "DELETE", null);
            c.getResponseCode();
            c.disconnect();
        } catch (Exception ignored) {
            // A leftover old copy is harmless; the newest one is what restores.
        }
    }

    private static HttpURLConnection connection(String url, String token, String method,
                                                @Nullable String contentType) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(30_000);
        c.setReadTimeout(120_000);
        c.setRequestProperty("Authorization", "Bearer " + token);
        c.setRequestProperty("Accept", "application/json");
        if (contentType != null) c.setRequestProperty("Content-Type", contentType);
        return c;
    }

    // ---------------------------------------------------------------- helpers

    private static void fail(Context app, Callback callback, String message) {
        类046.get(app).setDriveLastError(message);
        Log.w(TAG, message);
        finish(callback, false, message);
    }

    private static String reason(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return e.getClass().getSimpleName();
        }
        return message.trim();
    }

    private static String readError(HttpURLConnection c) {
        try (InputStream in = c.getErrorStream() == null
                ? c.getInputStream() : c.getErrorStream()) {
            if (in == null) return "";
            return new String(readAll(in), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    private static void write(OutputStream out, String value) throws Exception {
        out.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        return out.toByteArray();
    }

    private static void copy(File from, File to) throws Exception {
        try (InputStream in = new FileInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            copy(in, out);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        out.flush();
    }

    private static void deleteQuietly(File file) {
        if (file.exists()) file.delete();
    }

    private static void finish(@Nullable Callback callback, boolean ok, String message) {
        if (callback == null) return;
        类045.main().post(() -> callback.onResult(ok, message));
    }
}
