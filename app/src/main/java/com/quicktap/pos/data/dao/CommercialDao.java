package com.quicktap.pos.data.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import com.quicktap.pos.data.entity.CashSession;
import com.quicktap.pos.data.entity.Customer;
import com.quicktap.pos.data.entity.Purchase;
import com.quicktap.pos.data.entity.PurchaseItem;
import com.quicktap.pos.data.entity.Refund;
import com.quicktap.pos.data.entity.Supplier;

import java.util.List;

@Dao
public interface CommercialDao {
    @Query("SELECT * FROM customers WHERE active = 1 ORDER BY name COLLATE NOCASE") LiveData<List<Customer>> observeCustomers();
    @Query("SELECT * FROM customers WHERE id = :id") Customer customer(long id);
    @Insert long insertCustomer(Customer row);
    @Update void updateCustomer(Customer row);
    @Delete void deleteCustomer(Customer row);

    @Query("SELECT * FROM suppliers WHERE active = 1 ORDER BY name COLLATE NOCASE") LiveData<List<Supplier>> observeSuppliers();
    @Query("SELECT * FROM suppliers WHERE id = :id") Supplier supplier(long id);
    @Insert long insertSupplier(Supplier row);
    @Update void updateSupplier(Supplier row);
    @Delete void deleteSupplier(Supplier row);

    @Query("SELECT * FROM purchases ORDER BY purchaseDate DESC, id DESC") LiveData<List<Purchase>> observePurchases();
    @Query("SELECT * FROM purchase_items WHERE purchaseId = :purchaseId") List<PurchaseItem> purchaseItems(long purchaseId);
    @Insert long insertPurchase(Purchase row);
    @Insert long insertPurchaseItem(PurchaseItem row);

    @Query("SELECT * FROM cash_sessions ORDER BY openedAt DESC, id DESC") LiveData<List<CashSession>> observeCashSessions();
    @Query("SELECT * FROM cash_sessions WHERE status = 'OPEN' ORDER BY openedAt DESC LIMIT 1") CashSession openCashSession();
    @Insert long insertCashSession(CashSession row);
    @Update void updateCashSession(CashSession row);

    @Query("SELECT * FROM refunds WHERE billId = :billId ORDER BY createdAt DESC") List<Refund> refundsForBill(long billId);
    @Insert long insertRefund(Refund row);
}
