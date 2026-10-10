package com.quicktap.pos.data.entity;

import androidx.room.Entity;
import androidx.room.Index;

@Entity(tableName = "refunds", indices = {@Index(value = {"billId"}), @Index(value = {"createdAt"})})
public class Refund {
    @androidx.room.PrimaryKey(autoGenerate = true)
    public long id;
    public long billId;
    public long productId;
    public int quantity;
    public double amount;
    public String reason = "";
    public String refundMethod = "CASH";
    public long createdAt;
}
