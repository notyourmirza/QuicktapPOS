package com.quicktap.pos.data.entity;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "bills", indices = {@Index("createdAt"), @Index(value = "uuid", unique = true)})
public class Bill {
    public static final String TYPE_DINE_IN = "DINE_IN";
    public static final String TYPE_TAKE_AWAY = "TAKE_AWAY";
    public static final String TYPE_DELIVERY = "DELIVERY";

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** Human readable invoice number, e.g. INV-000123. */
    @NonNull
    public String invoiceNo = "";

    public long createdAt;

    @NonNull
    public String orderType = TYPE_DINE_IN;

    public String tableNo;
    public String customerName;
    public String customerPhone;
    public String address;
    public String notes;

    /** Optional numeric customer account code; 0 means no ledger account. */
    @androidx.room.ColumnInfo(defaultValue = "0")
    public int customerCode;

    public double subtotal;
    /** Backward-compatible applied discount amount. */
    public double discount;
    /** Persisted discount amount for this order. */
    @androidx.room.ColumnInfo(defaultValue = "0")
    public double discountAmount;
    /** AMOUNT or PERCENT. */
    @NonNull
    @androidx.room.ColumnInfo(defaultValue = "AMOUNT")
    public String discountType = "AMOUNT";
    @androidx.room.ColumnInfo(defaultValue = "0")
    public double discountPercentage;
    public double tax;
    public double total;

    public boolean paid;

    /** Payment channel selected at checkout, e.g. CASH, CARD, UPI, or CREDIT. */
    @NonNull
    public String paymentMethod = "CASH";

    // ---- offline sync metadata (see com.quicktap.pos.sync.类034) ----

    /** Stable cross-device identifier used by the server as the upsert key. */
    public String uuid;

    /** Local last-modified stamp in epoch millis; drives last-write-wins. */
    @ColumnInfo(defaultValue = "0")
    public long updatedAt;

    /** 1 while the row still has to be pushed to the server. */
    @ColumnInfo(defaultValue = "1")
    public boolean dirty = true;

    /** Soft delete so the deletion can be replicated to other devices. */
    @ColumnInfo(defaultValue = "0")
    public boolean deleted = false;
}
