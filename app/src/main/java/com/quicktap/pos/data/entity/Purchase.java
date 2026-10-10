package com.quicktap.pos.data.entity;

import androidx.room.Entity;
import androidx.room.Index;

@Entity(tableName = "purchases", indices = {@Index(value = {"supplierId"}), @Index(value = {"createdAt"})})
public class Purchase {
    @androidx.room.PrimaryKey(autoGenerate = true)
    public long id;
    public long supplierId;
    public String invoiceNo = "";
    public double total;
    public long purchaseDate;
    public String notes = "";
    public long createdAt;
    public long updatedAt;
}
