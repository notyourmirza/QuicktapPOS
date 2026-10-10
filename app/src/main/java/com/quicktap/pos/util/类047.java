package com.quicktap.pos.util;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import androidx.sqlite.db.SupportSQLiteDatabase;

import com.quicktap.pos.data.AppDatabase;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Validated manual backup and restore of the live Room database.
 * The user chooses a destination with the Storage Access Framework.
 */
public final class 类047 {
    private 类047() { }

    public static String suggestedFileName() {
        return "quicktap_backup_" + 类049.fileStamp(System.currentTimeMillis()) + ".db";
    }

    public static File createValidatedSnapshot(Context context) throws IOException {
        Context app = context.getApplicationContext();
        File snapshot = new File(app.getCacheDir(), "quicktap_validated_snapshot.db");
        deleteQuietly(snapshot);
        snapshotLiveDatabase(app, snapshot);
        return snapshot;
    }

    public static void backupTo(Context context, Uri target) throws IOException {
        Context app = context.getApplicationContext();
        File snapshot = createValidatedSnapshot(app);
        try (InputStream in = new FileInputStream(snapshot);
             OutputStream out = app.getContentResolver().openOutputStream(target, "w")) {
            if (out == null) throw new IOException("Cannot open backup destination");
            copy(in, out);
        } finally {
            deleteQuietly(snapshot);
        }
    }

    private static Map<String, Long> snapshotLiveDatabase(Context app, File snapshot) throws IOException {
        AppDatabase room = AppDatabase.get(app);
        SupportSQLiteDatabase live = room.getOpenHelper().getWritableDatabase();
        Map<String, Long> counts = tableCounts(live);
        try (Cursor ignored = live.query("PRAGMA wal_checkpoint(TRUNCATE)", null)) {
            // Cursor execution completes the checkpoint before it is closed.
        } catch (Exception e) {
            throw new IOException("Live database checkpoint failed", e);
        }
        AppDatabase.closeInstance();
        File liveFile = app.getDatabasePath(AppDatabase.NAME);
        if (!liveFile.exists() || liveFile.length() <= 0) throw new IOException("Live database unavailable");
        try (InputStream in = new FileInputStream(liveFile);
             OutputStream out = new FileOutputStream(snapshot)) {
            copy(in, out);
        }
        validateDatabase(snapshot, counts);
        return counts;
    }

    private static Map<String, Long> tableCounts(SupportSQLiteDatabase db) throws IOException {
        Map<String, Long> counts = new LinkedHashMap<>();
        try (Cursor tables = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", null)) {
            while (tables.moveToNext()) {
                String table = tables.getString(0);
                try (Cursor count = db.query("SELECT COUNT(*) FROM " + quote(table), null)) {
                    counts.put(table, count.moveToFirst() ? count.getLong(0) : 0L);
                }
            }
        } catch (Exception e) {
            throw new IOException("Unable to count live database tables", e);
        }
        if (counts.isEmpty()) throw new IOException("Live database has no tables");
        return counts;
    }

    private static void validateDatabase(File file, Map<String, Long> expected) throws IOException {
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            try (Cursor integrity = db.rawQuery("PRAGMA integrity_check", null)) {
                if (!integrity.moveToFirst() || !"ok".equalsIgnoreCase(integrity.getString(0))) {
                    throw new IOException("Backup integrity check failed");
                }
            }
            try (Cursor foreign = db.rawQuery("PRAGMA foreign_key_check", null)) {
                if (foreign.moveToFirst()) throw new IOException("Backup foreign-key validation failed");
            }
            for (Map.Entry<String, Long> entry : expected.entrySet()) {
                long actual;
                try (Cursor count = db.rawQuery("SELECT COUNT(*) FROM " + quote(entry.getKey()), null)) {
                    actual = count.moveToFirst() ? count.getLong(0) : -1L;
                }
                if (actual != entry.getValue()) {
                    throw new IOException("Backup failed — some data could not be included: "
                            + entry.getKey() + " live=" + entry.getValue() + " backup=" + actual);
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Backup database could not be opened", e);
        } finally {
            if (db != null) db.close();
        }
    }

    public static void restoreFrom(Context context, Uri source) throws IOException {
        Context app = context.getApplicationContext();
        File incoming = new File(app.getCacheDir(), "quicktap_restore_incoming.db");
        File safety = new File(app.getCacheDir(), "quicktap_pre_restore_safe.db");
        deleteQuietly(incoming);
        try (InputStream in = app.getContentResolver().openInputStream(source);
             OutputStream out = new FileOutputStream(incoming)) {
            if (in == null) throw new IOException("Cannot open backup file");
            copy(in, out);
        }
        validateDatabase(incoming, tableCountsFromFile(incoming));
        AppDatabase.closeInstance();
        File dbFile = app.getDatabasePath(AppDatabase.NAME);
        deleteQuietly(safety);
        if (dbFile.exists()) copy(dbFile, safety);
        deleteQuietly(new File(dbFile.getPath() + "-wal"));
        deleteQuietly(new File(dbFile.getPath() + "-shm"));
        try {
            copy(incoming, dbFile);
            validateDatabase(dbFile, tableCountsFromFile(incoming));
            AppDatabase.get(app).getOpenHelper().getWritableDatabase();
            deleteQuietly(incoming);
            deleteQuietly(safety);
        } catch (Exception restoreFailure) {
            deleteQuietly(dbFile);
            if (safety.exists()) copy(safety, dbFile);
            AppDatabase.get(app);
            deleteQuietly(incoming);
            throw restoreFailure instanceof IOException ? (IOException) restoreFailure
                    : new IOException("Restore failed; previous database was preserved", restoreFailure);
        }
    }

    private static Map<String, Long> tableCountsFromFile(File file) throws IOException {
        SQLiteDatabase db = null;
        try {
            db = SQLiteDatabase.openDatabase(file.getPath(), null, SQLiteDatabase.OPEN_READONLY);
            Map<String, Long> counts = new LinkedHashMap<>();
            try (Cursor tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", null)) {
                while (tables.moveToNext()) {
                    String table = tables.getString(0);
                    try (Cursor count = db.rawQuery("SELECT COUNT(*) FROM " + quote(table), null)) {
                        counts.put(table, count.moveToFirst() ? count.getLong(0) : 0L);
                    }
                }
            }
            if (counts.isEmpty()) throw new IOException("Backup contains no tables");
            return counts;
        } catch (Exception e) {
            if (e instanceof IOException) throw (IOException) e;
            throw new IOException("Backup database could not be inspected", e);
        } finally {
            if (db != null) db.close();
        }
    }

    private static String quote(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
            copy(in, out);
        }
    }

    private static void copy(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
        out.flush();
    }

    private static void deleteQuietly(File file) {
        if (file.exists()) { //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }
}
