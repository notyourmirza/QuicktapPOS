package com.quicktap.pos.data.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.quicktap.pos.data.entity.Expense;
import com.quicktap.pos.data.entity.ExpenseCategory;

import java.util.List;

@Dao
public interface ExpenseDao {
    @Insert long insert(Expense expense);
    @Update void update(Expense expense);
    @Delete void delete(Expense expense);

    @Query("SELECT * FROM expenses ORDER BY date DESC, id DESC")
    LiveData<List<Expense>> observeAll();

    @Query("SELECT * FROM expenses WHERE date BETWEEN :from AND :to ORDER BY date DESC, id DESC")
    List<Expense> between(long from, long to);

    @Query("SELECT IFNULL(SUM(amount), 0) FROM expenses WHERE date BETWEEN :from AND :to")
    double totalBetween(long from, long to);

    @Query("SELECT * FROM expense_categories ORDER BY name COLLATE NOCASE")
    LiveData<List<ExpenseCategory>> observeCategories();

    @Insert long insertCategory(ExpenseCategory category);

    @Query("SELECT COUNT(*) FROM expense_categories WHERE name = :name COLLATE NOCASE")
    int countCategory(String name);
}
