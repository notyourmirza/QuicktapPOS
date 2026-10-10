package com.quicktap.pos.data.entity;

import androidx.room.Entity;
import androidx.room.Index;

@Entity(tableName = "purchase_items", indices = {@Index(value = {"purchaseId"}), @Index(value = {"productId"})})
public class PurchaseItem {
    @androidx.room.PrimaryKey(autoGenerate = true)
    public long id;
    public long purchaseId;
    public long productId;
    public int quantity;
    public double unitCost;
}
