package com.quicktap.pos.data.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.Index;
import androidx.room.PrimaryKey;

@Entity(tableName = "expenses", indices = {@Index("date"), @Index("category")})
public class Expense {
    @PrimaryKey(autoGenerate = true)
    public long id;
    @NonNull public String title = "";
    @NonNull public String category = "Other";
    public double amount;
    /** Start-of-day local timestamp used for filtering. */
    public long date;
    @NonNull public String time = "";
    @NonNull public String description = "";
    public long createdAt;
    public long updatedAt;
}
