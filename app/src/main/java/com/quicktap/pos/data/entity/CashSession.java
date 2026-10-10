package com.quicktap.pos.data.entity;

import androidx.room.Entity;
import androidx.room.Index;

@Entity(tableName = "cash_sessions", indices = {@Index(value = {"openedAt"}), @Index(value = {"status"})})
public class CashSession {
    @androidx.room.PrimaryKey(autoGenerate = true)
    public long id;
    public long openedAt;
    public long closedAt;
    public double openingCash;
    public double expectedCash;
    public double countedCash;
    public double cashIn;
    public double cashOut;
    public String status = "OPEN";
    public String notes = "";
}
