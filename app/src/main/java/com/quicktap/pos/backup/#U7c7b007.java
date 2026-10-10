package com.quicktap.pos.backup;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.quicktap.pos.data.AppDatabase;
import com.quicktap.pos.util.类045;
import com.quicktap.pos.util.类046;
import com.quicktap.pos.util.类049;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/** Automatic shared-storage backup. Files live in Download/QuickTap POS and survive uninstall. */
public final class 类007 {
    public interface Callback { void onResult(boolean ok, String message); }
    private static final String FOLDER = "Download/QuickTap POS/";
    private static final String PREFIX = "QuickTap_POS_Backup_";
    private static final String EXT = ".qtpbackup";
    private 类007() { }

    public static boolean isConfigured(Context context) { return false; }
    public static String folderUri(Context context) { return ""; }
    public static boolean saveFolder(Context context, Uri ignored) { return false; }

    public static void downloadToDownloads(Context context, Callback cb) {
        if (Build.VERSION.SDK_INT < 29) { finish(cb, false, "Downloads API unavailable on this Android version"); return; }
        Context app = context.getApplicationContext();
        类045.io().execute(() -> {
            File encrypted = null; Uri target = null;
            try {
                encrypted = 类006.exportEncryptedSnapshot(app);
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, PREFIX + 类049.fileStamp(System.currentTimeMillis()) + EXT);
                values.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
                values.put(MediaStore.Downloads.RELATIVE_PATH, "Download/QuickTap POS/");
                values.put(MediaStore.Downloads.IS_PENDING, 1);
                target = app.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (target == null) throw new java.io.IOException("Downloads provider unavailable");
                try (InputStream in = new FileInputStream(encrypted); OutputStream out = app.getContentResolver().openOutputStream(target, "w")) {
                    if (out == null) throw new java.io.IOException("Downloads file is not writable"); copy(in, out);
                }
                ContentValues ready = new ContentValues(); ready.put(MediaStore.Downloads.IS_PENDING, 0);
                app.getContentResolver().update(target, ready, null, null);
                if (!verifyMediaStoreSize(app, target, encrypted.length())) throw new java.io.IOException("Download verification failed");
                finish(cb, true, "Backup downloaded to Download/QuickTap POS");
            } catch (Exception e) {
                if (target != null) try { app.getContentResolver().delete(target, null, null); } catch (Exception ignored) { }
                finish(cb, false, "Automatic Downloads save unavailable");
            } finally { if (encrypted != null) encrypted.delete(); }
        });
    }

    public static void writeBackupFile(Context context, Uri target, Callback cb) {
        Context app = context.getApplicationContext();
        if (target == null) { finish(cb, false, "No backup file location selected"); return; }
        类045.io().execute(() -> {
            File encrypted = null;
            try {
                encrypted = 类006.exportEncryptedSnapshot(app);
                try (InputStream in = new FileInputStream(encrypted); OutputStream out = app.getContentResolver().openOutputStream(target, "w")) {
                    if (out == null) throw new java.io.IOException("Cannot open selected backup file");
                    copy(in, out);
                }
                finish(cb, true, "Encrypted backup file downloaded successfully");
            } catch (Exception e) { finish(cb, false, "Backup download failed. Please choose a writable location."); }
            finally { if (encrypted != null) encrypted.delete(); }
        });
    }

    public static void restoreBackupFile(Context context, Uri source, Callback cb) {
        Context app = context.getApplicationContext();
        if (source == null) { finish(cb, false, "No backup file selected"); return; }
        类045.io().execute(() -> {
            try {
                byte[] payload;
                try (InputStream in = app.getContentResolver().openInputStream(source)) { if (in == null) throw new java.io.IOException("Cannot read selected backup"); payload = readAll(in); }
                File restored = 类006.validateAndDecryptSnapshot(app, payload);
                AppDatabase.closeInstance();
                File db = app.getDatabasePath(AppDatabase.NAME); File safety = new File(app.getCacheDir(), "pre_file_restore_safe.db");
                if (db.exists()) copy(db, safety);
                deleteQuietly(new File(db.getPath() + "-wal")); deleteQuietly(new File(db.getPath() + "-shm")); copy(restored, db);
                try { AppDatabase.get(app).query("PRAGMA integrity_check", null).close(); }
                catch (Exception bad) { if (safety.exists()) copy(safety, db); AppDatabase.get(app); throw bad; }
                finish(cb, true, "Backup restored successfully. Please reopen the app.");
            } catch (Exception e) { finish(cb, false, "Restore failed. Select a valid QuickTap encrypted backup file."); }
        });
    }

    public static void backupNow(Context context, Callback cb) {
        Context app = context.getApplicationContext();
        类045.io().execute(() -> {
            File encrypted = null; Uri createdUri = null; File legacyCreated = null;
            try {
                encrypted = 类006.exportEncryptedSnapshot(app);
                String name = PREFIX + 类049.fileStamp(System.currentTimeMillis()) + EXT;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                    values.put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream");
                    values.put(MediaStore.Downloads.RELATIVE_PATH, FOLDER);
                    values.put(MediaStore.Downloads.IS_PENDING, 1);
                    createdUri = app.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (createdUri == null) throw new java.io.IOException("Cannot create Download/QuickTap POS file");
                    try (InputStream in = new FileInputStream(encrypted); OutputStream out = app.getContentResolver().openOutputStream(createdUri, "w")) {
                        if (out == null) throw new java.io.IOException("Cannot write backup file"); copy(in, out);
                    }
                    ContentValues ready = new ContentValues(); ready.put(MediaStore.Downloads.IS_PENDING, 0);
                    app.getContentResolver().update(createdUri, ready, null, null);
                    if (!verifyMediaStoreSize(app, createdUri, encrypted.length())) throw new java.io.IOException("Backup verification failed");
                    deleteOlderMediaStore(app, createdUri);
                } else {
                    File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "QuickTap POS");
                    if (!folder.exists() && !folder.mkdirs()) throw new java.io.IOException("Cannot create Download/QuickTap POS");
                    legacyCreated = new File(folder, name);
                    copy(encrypted, legacyCreated);
                    if (legacyCreated.length() != encrypted.length()) throw new java.io.IOException("Backup verification failed");
                    deleteOlderLegacy(folder, legacyCreated);
                }
                类046.get(app).setLastLocalBackupAt(System.currentTimeMillis());
                finish(cb, true, "Backup saved automatically in Download/QuickTap POS");
            } catch (Exception e) {
                if (createdUri != null) try { app.getContentResolver().delete(createdUri, null, null); } catch (Exception ignored) { }
                if (legacyCreated != null) legacyCreated.delete();
                finish(cb, false, "Backup failed. Check free storage space and try again.");
            } finally { if (encrypted != null) encrypted.delete(); }
        });
    }

    public static boolean backupNowBlocking(Context context) {
        CountDownLatch latch = new CountDownLatch(1); AtomicBoolean ok = new AtomicBoolean(false);
        backupNow(context, (success, message) -> { ok.set(success); latch.countDown(); });
        try { latch.await(130, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        return ok.get();
    }

    public static void restoreLatest(Context context, Callback cb) {
        Context app = context.getApplicationContext();
        类045.io().execute(() -> {
            try {
                File restored = decryptLatestToCache(app);
                AppDatabase.closeInstance();
                File db = app.getDatabasePath(AppDatabase.NAME);
                File safety = new File(app.getCacheDir(), "pre_local_restore_safe.db");
                if (db.exists()) copy(db, safety);
                deleteQuietly(new File(db.getPath() + "-wal")); deleteQuietly(new File(db.getPath() + "-shm"));
                copy(restored, db);
                try { AppDatabase.get(app).query("PRAGMA integrity_check", null).close(); }
                catch (Exception bad) { if (safety.exists()) copy(safety, db); AppDatabase.get(app); throw bad; }
                finish(cb, true, "Backup restored from Download/QuickTap POS. Please reopen the app.");
            } catch (Exception e) { finish(cb, false, "No valid backup found in Download/QuickTap POS."); }
        });
    }

    private static File decryptLatestToCache(Context app) throws Exception {
        byte[] payload = readLatest(app);
        return 类006.validateAndDecryptSnapshot(app, payload);
    }
    private static byte[] readLatest(Context app) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            Uri latest = latestMediaStore(app); if (latest == null) throw new java.io.FileNotFoundException("No backup");
            try (InputStream in = app.getContentResolver().openInputStream(latest)) { if (in == null) throw new java.io.IOException("Cannot read backup"); return readAll(in); }
        }
        File folder = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "QuickTap POS");
        File latest = null; File[] files = folder.listFiles();
        if (files != null) for (File f : files) if (f.isFile() && f.getName().startsWith(PREFIX) && f.getName().endsWith(EXT) && (latest == null || f.lastModified() > latest.lastModified())) latest = f;
        if (latest == null) throw new java.io.FileNotFoundException("No backup");
        try (InputStream in = new FileInputStream(latest)) { return readAll(in); }
    }
    private static Uri latestMediaStore(Context app) {
        Cursor c = null; Uri result = null; long best = -1;
        try {
            String[] projection = {MediaStore.Downloads._ID, MediaStore.Downloads.DATE_MODIFIED, MediaStore.Downloads.DISPLAY_NAME};
            c = app.getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, projection, MediaStore.Downloads.RELATIVE_PATH + "=? AND " + MediaStore.Downloads.DISPLAY_NAME + " LIKE ?", new String[]{FOLDER, PREFIX + "%" + EXT}, MediaStore.Downloads.DATE_MODIFIED + " DESC");
            if (c != null && c.moveToFirst()) result = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getString(0));
        } finally { if (c != null) c.close(); } return result;
    }
    private static boolean verifyMediaStoreSize(Context app, Uri uri, long expected) { Cursor c = null; try { c = app.getContentResolver().query(uri, new String[]{MediaStore.Downloads.SIZE}, null, null, null); return c != null && c.moveToFirst() && c.getLong(0) == expected; } catch (Exception e) { return false; } finally { if (c != null) c.close(); } }
    private static void deleteOlderMediaStore(Context app, Uri keep) { List<Uri> old = new ArrayList<>(); Cursor c = null; try { c = app.getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, new String[]{MediaStore.Downloads._ID}, MediaStore.Downloads.RELATIVE_PATH + "=? AND " + MediaStore.Downloads.DISPLAY_NAME + " LIKE ?", new String[]{FOLDER, PREFIX + "%" + EXT}, null); if (c != null) while (c.moveToNext()) { Uri u = Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getString(0)); if (!u.equals(keep)) old.add(u); } } finally { if (c != null) c.close(); } for (Uri u : old) app.getContentResolver().delete(u, null, null); }
    private static void deleteOlderLegacy(File folder, File keep) { File[] files = folder.listFiles(); if (files != null) for (File f : files) if (!f.equals(keep) && f.getName().startsWith(PREFIX) && f.getName().endsWith(EXT)) f.delete(); }
    private static void copy(File from, File to) throws Exception { try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) { copy(in, out); } }
    private static void copy(InputStream in, OutputStream out) throws Exception { byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); out.flush(); }
    private static byte[] readAll(InputStream in) throws Exception { ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] b = new byte[8192]; int n; while ((n = in.read(b)) != -1) out.write(b, 0, n); return out.toByteArray(); }
    private static void deleteQuietly(File f) { if (f.exists()) f.delete(); }
    private static void finish(Callback cb, boolean ok, String msg) { if (cb != null) 类045.main().post(() -> cb.onResult(ok, msg)); }
}
