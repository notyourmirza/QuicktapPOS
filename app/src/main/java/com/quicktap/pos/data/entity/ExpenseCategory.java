package com.quicktap.pos.data.entity;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

@Entity(tableName = "expense_categories")
public class ExpenseCategory {
    @PrimaryKey(autoGenerate = true)
    public long id;
    @NonNull public String name = "";
    public long createdAt;

    public ExpenseCategory() { }
    public ExpenseCategory(String name) {
        this.name = name;
        this.createdAt = System.currentTimeMillis();
    }
}
