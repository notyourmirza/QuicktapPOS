package com.quicktap.pos.cloudsync;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;

/**
 * Cloud Sync's own SQLite database.
 *
 * WHY A SEPARATE DATABASE?
 * The POS database (com.quicktap.pos.data.AppDatabase, quicktap.db) is on
 * version 11 and is built with .fallbackToDestructiveMigration(). Touching its
 * schema - even additively - risks a wipe of the shop's sales history if any
 * migration ever fails to match. Keeping the queue, the uuid map and the
 * cursor in their own file means:
 *   - zero changes to quicktap.db, zero schema risk, zero data loss
 *   - the feature can be removed by deleting one file
 */
@Database(entities = {SyncQueueRow.class, SyncMapRow.class, SyncStateRow.class},
        version = 1,
        exportSchema = false)
public abstract class CloudSyncDb extends RoomDatabase {

    public static final String NAME = "quicktap_cloudsync.db";

    public abstract CloudSyncDao dao();

    private static volatile CloudSyncDb instance;

    public static CloudSyncDb get(Context context) {
        if (instance == null) {
            synchronized (CloudSyncDb.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                                    context.getApplicationContext(), CloudSyncDb.class, NAME)
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return instance;
    }
}
