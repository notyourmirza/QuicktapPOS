package com.quicktap.pos.data.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "ledger_entries", indices = {@Index("customerId"), @Index("createdAt")})
public class LedgerEntry {
    public static final String SALE = "SALE";
    public static final String PAYMENT = "PAYMENT";
    public static final String ADJUSTMENT = "ADJUSTMENT";
    public static final String DEDUCTION = "DEDUCTION";

    @PrimaryKey(autoGenerate = true)
    public long id;
    public long customerId;
    public long billId;
    @NonNull public String type = SALE;
    public double amount;
    @NonNull public String note = "";
    public long createdAt;
}
