package com.quicktap.pos.data.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.quicktap.pos.data.entity.LedgerCustomer;
import com.quicktap.pos.data.entity.LedgerEntry;

import java.util.List;

@Dao
public interface LedgerDao {
    @Query("SELECT * FROM ledger_customers WHERE active = 1 ORDER BY customerCode ASC")
    LiveData<List<LedgerCustomer>> observeCustomers();

    @Query("SELECT * FROM ledger_customers WHERE active = 1 ORDER BY customerCode ASC")
    List<LedgerCustomer> customersBlocking();

    @Query("SELECT * FROM ledger_customers WHERE customerCode = :code AND active = 1 LIMIT 1")
    LedgerCustomer byCode(int code);

    @Query("SELECT COALESCE(MAX(customerCode), 0) + 1 FROM ledger_customers")
    int nextCode();

    @Insert long insertCustomer(LedgerCustomer customer);
    @Update void updateCustomer(LedgerCustomer customer);

    @Query("SELECT * FROM ledger_entries WHERE customerId = :customerId ORDER BY createdAt DESC, id DESC")
    LiveData<List<LedgerEntry>> observeEntries(long customerId);

    @Query("SELECT * FROM ledger_entries WHERE customerId = :customerId AND createdAt BETWEEN :from AND :to ORDER BY createdAt DESC, id DESC")
    List<LedgerEntry> entriesBlockingRange(long customerId, long from, long to);

    @Query("SELECT * FROM ledger_entries WHERE customerId = :customerId ORDER BY createdAt DESC, id DESC")
    List<LedgerEntry> entriesBlocking(long customerId);

    @Query("SELECT COALESCE(SUM(CASE WHEN type = 'SALE' THEN amount WHEN type = 'ADJUSTMENT' THEN amount ELSE 0 END), 0) - COALESCE(SUM(CASE WHEN type = 'PAYMENT' THEN amount WHEN type = 'DEDUCTION' THEN amount ELSE 0 END), 0) FROM ledger_entries WHERE customerId = :customerId")
    double balance(long customerId);

    @Query("SELECT COALESCE(SUM(CASE WHEN type = 'SALE' THEN amount ELSE 0 END), 0) FROM ledger_entries")
    double totalCreditSales();

    @Query("SELECT COALESCE(SUM(CASE WHEN type = 'PAYMENT' THEN amount ELSE 0 END), 0) FROM ledger_entries")
    double totalPayments();

    @Insert long insertEntry(LedgerEntry entry);
}
