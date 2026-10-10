package com.quicktap.pos.data.entity;

import androidx.room.Entity;
import androidx.room.Index;

@Entity(tableName = "suppliers", indices = {@Index(value = {"name"}), @Index(value = {"phone"})})
public class Supplier {
    @androidx.room.PrimaryKey(autoGenerate = true)
    public long id;
    public String name = "";
    public String phone = "";
    public String address = "";
    public String notes = "";
    public long createdAt;
    public long updatedAt;
    public boolean active = true;
}
