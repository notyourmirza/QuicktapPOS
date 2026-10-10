package com.quicktap.pos.cloudsync;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;

/**
 * Maps a local POS row to the stable UUID used on the server.
 *
 * products / categories / bills already carry their own uuid column, but
 * customers, suppliers, expenses and ledger_customers do not. Instead of
 * altering those tables (which would force a migration of the POS database)
 * the mapping is kept here, in the Cloud Sync database.
 */
@Entity(tableName = "cs_map",
        primaryKeys = {"entity", "localId"},
        indices = {@Index(value = {"entity", "uuid"})})
public class SyncMapRow {

    public String entity;

    public long localId;

    /** UUID sent to (and received from) the server. */
    public String uuid;

    /** Last known client-side modification time of the row (epoch millis). */
    @ColumnInfo(name = "updatedAt")
    public long updatedAt;

    /** Tombstone flag so a delete replicates instead of resurrecting the row. */
    public boolean deleted;
}
