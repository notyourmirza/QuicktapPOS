package com.quicktap.pos.data.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "ledger_customers", indices = {@Index(value = "customerCode", unique = true), @Index("name")})
public class LedgerCustomer {
    @PrimaryKey(autoGenerate = true)
    public long id;
    public int customerCode;
    @NonNull public String name = "";
    @NonNull public String phone = "";
    @NonNull public String address = "";
    @NonNull public String notes = "";
    /** JSON object of user-defined field labels and values. */
    @NonNull public String customFields = "{}";
    public long createdAt;
    public long updatedAt;
    public boolean active = true;
}
