package com.quicktap.pos.cloudsync;

import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Transaction;

import java.util.List;

/** Data access for the isolated Cloud Sync database. */
@Dao
public interface CloudSyncDao {

    // ------------------------------------------------------------------ queue

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertQueue(SyncQueueRow row);

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void insertQueueAll(List<SyncQueueRow> rows);

    /**
     * Oldest pending changes first, so a sale is uploaded before a later edit.
     * Rows that already failed too many times are skipped until the user asks
     * for a retry (they are never deleted, so nothing can be lost).
     */
    @Query("SELECT * FROM cs_queue WHERE synced = 0 AND fromServer = 0 AND attempts < :maxAttempts "
            + "ORDER BY createdAt ASC LIMIT :limit")
    List<SyncQueueRow> pending(int maxAttempts, int limit);

    @Query("SELECT COUNT(*) FROM cs_queue WHERE synced = 0 AND fromServer = 0 AND attempts < :maxAttempts")
    int pendingCount(int maxAttempts);

    @Query("SELECT COUNT(*) FROM cs_queue WHERE synced = 0 AND fromServer = 0")
    int pendingCountAll();

    /** Collapses duplicates: only the newest pending row for a record survives. */
    @Query("DELETE FROM cs_queue WHERE recordUuid = :uuid AND synced = 0 AND fromServer = 0")
    void dropPendingFor(String uuid);

    @Query("UPDATE cs_queue SET synced = 1, lastError = NULL WHERE id IN (:ids)")
    void markSynced(List<Long> ids);

    @Query("UPDATE cs_queue SET attempts = attempts + 1, lastError = :error WHERE id IN (:ids)")
    void markAttemptFailed(List<Long> ids, String error);

    @Query("UPDATE cs_queue SET attempts = 0, lastError = NULL WHERE synced = 0")
    void resetAttempts();

    /** Housekeeping: accepted rows are useless once they are a week old. */
    @Query("DELETE FROM cs_queue WHERE synced = 1 AND createdAt < :cutoff")
    void pruneSynced(long cutoff);

    @Query("DELETE FROM cs_queue")
    void clearQueue();

    // -------------------------------------------------------------------- map

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putMap(SyncMapRow row);

    @Query("SELECT * FROM cs_map WHERE entity = :entity AND localId = :localId LIMIT 1")
    SyncMapRow mapByLocal(String entity, long localId);

    @Query("SELECT * FROM cs_map WHERE entity = :entity AND uuid = :uuid LIMIT 1")
    SyncMapRow mapByUuid(String entity, String uuid);

    @Query("DELETE FROM cs_map")
    void clearMap();

    // ------------------------------------------------------------------ state

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void putState(SyncStateRow row);

    @Query("SELECT value FROM cs_state WHERE stateKey = :key LIMIT 1")
    String state(String key);

    @Query("DELETE FROM cs_state")
    void clearState();

    /** Used by "Disconnect": everything goes, local POS data stays untouched. */
    @Transaction
    default void wipeAll() {
        clearQueue();
        clearMap();
        clearState();
    }
}
