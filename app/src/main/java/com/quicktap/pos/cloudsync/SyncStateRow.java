package com.quicktap.pos.cloudsync;

import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** Tiny key/value store for the sync cursor, device uid and plan snapshot. */
@Entity(tableName = "cs_state")
public class SyncStateRow {

    @PrimaryKey
    public String stateKey;

    public String value;
}
