package com.quicktap.pos.cloudsync;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/**
 * One pending local change waiting to be pushed.
 *
 * Lives in its OWN database (quicktap_cloudsync.db) so the app's POS database
 * (quicktap.db) is never altered by this feature - no migration, no version
 * bump, no risk to existing data.
 */
@Entity(tableName = "cs_queue",
        indices = {@Index(value = {"entity"}), @Index(value = {"synced"}), @Index(value = {"recordUuid"})})
public class SyncQueueRow {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** One of CloudSyncCodec.ENTITIES, e.g. "products". */
    public String entity;

    /** Stable identity of the record on the server. */
    @ColumnInfo(name = "recordUuid")
    public String recordUuid;

    /** Row id in the local POS table (0 when the record no longer exists). */
    @ColumnInfo(name = "localId")
    public long localId;

    /** "UPSERT" or "DELETE". */
    public String operation;

    /** Serialised record body (JSON). Empty for DELETE. */
    public String payload;

    /** Monotonic version; the server uses (version, updatedAt) for LWW. */
    public int version;

    /** Client wall clock in epoch millis when the change happened. */
    @ColumnInfo(name = "updatedAt")
    public long updatedAt;

    /** When the row was queued (drives FIFO order and backoff). */
    @ColumnInfo(name = "createdAt")
    public long createdAt;

    public int attempts;

    @ColumnInfo(name = "lastError")
    public String lastError;

    /** True once the server accepted the record. */
    public boolean synced;

    /** True when the record came from the server and must not be pushed back. */
    @ColumnInfo(name = "fromServer")
    public boolean fromServer;
}
