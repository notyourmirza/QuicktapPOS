package com.quicktap.pos.data.dao;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Transaction;

import com.quicktap.pos.data.entity.Bill;
import com.quicktap.pos.data.entity.BillItem;
import com.quicktap.pos.data.model.类058;
import com.quicktap.pos.data.model.类059;
import com.quicktap.pos.data.model.类060;
import com.quicktap.pos.data.model.类061;

import java.util.List;

@Dao
public interface BillDao {

    @Insert
    long insertBill(Bill bill);

    @Insert
    void insertItems(List<BillItem> items);

    @Transaction
    default long saveBill(Bill bill, List<BillItem> items) {
        long id = insertBill(bill);
        for (BillItem item : items) item.billId = id;
        insertItems(items);
        return id;
    }

    @Query("SELECT * FROM bills ORDER BY createdAt DESC LIMIT :limit")
    LiveData<List<Bill>> observeRecent(int limit);

    @Query("SELECT * FROM bills WHERE createdAt BETWEEN :from AND :to ORDER BY createdAt DESC")
    List<Bill> between(long from, long to);

    @Query("SELECT * FROM bills b WHERE b.createdAt BETWEEN :from AND :to "
            + "AND (:query = '' OR b.invoiceNo LIKE '%' || :query || '%' "
            + "OR IFNULL(b.customerName,'') LIKE '%' || :query || '%' "
            + "OR IFNULL(b.customerPhone,'') LIKE '%' || :query || '%' "
            + "OR EXISTS (SELECT 1 FROM bill_items si WHERE si.billId = b.id AND si.name LIKE '%' || :query || '%')) "
            + "AND (:paymentMethod = '' OR b.paymentMethod = :paymentMethod) "
            + "AND (:orderType = '' OR b.orderType = :orderType) "
            + "AND (:paidMode = -1 OR b.paid = :paidMode) "
            + "AND (:discountMode = 0 OR (:discountMode = 1 AND IFNULL(b.discountAmount, b.discount) > 0) "
            + "OR (:discountMode = 2 AND IFNULL(b.discountAmount, b.discount) <= 0)) "
            + "ORDER BY b.createdAt DESC LIMIT :limit")
    LiveData<List<Bill>> observeFiltered(long from, long to, String query, String paymentMethod,
                                         String orderType, int paidMode, int discountMode, int limit);

    @Query("SELECT COUNT(*) AS orders, IFNULL(SUM(b.total),0) AS sales, "
            + "IFNULL(SUM(IFNULL(b.discountAmount, b.discount)),0) AS discount "
            + "FROM bills b WHERE b.createdAt BETWEEN :from AND :to "
            + "AND (:query = '' OR b.invoiceNo LIKE '%' || :query || '%' "
            + "OR IFNULL(b.customerName,'') LIKE '%' || :query || '%' "
            + "OR IFNULL(b.customerPhone,'') LIKE '%' || :query || '%' "
            + "OR EXISTS (SELECT 1 FROM bill_items si WHERE si.billId = b.id AND si.name LIKE '%' || :query || '%')) "
            + "AND (:paymentMethod = '' OR b.paymentMethod = :paymentMethod) "
            + "AND (:orderType = '' OR b.orderType = :orderType) "
            + "AND (:paidMode = -1 OR b.paid = :paidMode) "
            + "AND (:discountMode = 0 OR (:discountMode = 1 AND IFNULL(b.discountAmount, b.discount) > 0) "
            + "OR (:discountMode = 2 AND IFNULL(b.discountAmount, b.discount) <= 0))")
    LiveData<类060> observeFilteredSummary(long from, long to, String query, String paymentMethod,
                                                      String orderType, int paidMode, int discountMode);

    @Query("SELECT * FROM bills WHERE id = :id")
    Bill byId(long id);

    @Query("SELECT * FROM bills WHERE paid = 0 AND customerCode > 0 ORDER BY createdAt ASC")
    LiveData<List<Bill>> observeUnpaidCustomerOrders();

    @Query("UPDATE bills SET paid = 1, paymentMethod = :paymentMethod, updatedAt = :updatedAt, dirty = 1 WHERE id = :id AND paid = 0")
    int markPaid(long id, String paymentMethod, long updatedAt);

    @Query("SELECT * FROM bills ORDER BY createdAt DESC LIMIT 1")
    Bill last();

    @Query("SELECT * FROM bill_items WHERE billId = :billId")
    List<BillItem> itemsOf(long billId);

    @Query("DELETE FROM bills WHERE id = :id")
    void deleteBill(long id);

    @Query("DELETE FROM bill_items WHERE billId = :billId")
    void deleteItems(long billId);

    @Query("DELETE FROM bill_items WHERE billId IN (SELECT id FROM bills WHERE createdAt < :cutoff)")
    void deleteItemsOlderThan(long cutoff);

    @Query("DELETE FROM bills WHERE createdAt < :cutoff")
    void deleteBillsOlderThan(long cutoff);

    @Transaction
    default void deleteBillsOlderThanCascade(long cutoff) {
        deleteItemsOlderThan(cutoff);
        deleteBillsOlderThan(cutoff);
    }

    @Transaction
    default void deleteBillCascade(long id) {
        deleteItems(id);
        deleteBill(id);
    }

    @Query("SELECT COUNT(*) AS orders, "
            + "IFNULL(SUM(total),0) AS revenue, "
            + "IFNULL(SUM(CASE WHEN paid = 1 THEN 1 ELSE 0 END),0) AS paidCount, "
            + "IFNULL(SUM(CASE WHEN paid = 0 THEN 1 ELSE 0 END),0) AS unpaidCount, "
            + "IFNULL(SUM(CASE WHEN paid = 0 THEN total ELSE 0 END),0) AS unpaidAmount "
            + "FROM bills WHERE createdAt BETWEEN :from AND :to")
    LiveData<类059> observeSummary(long from, long to);

    @Query("SELECT COUNT(*) AS orders, "
            + "IFNULL(SUM(total),0) AS revenue, "
            + "IFNULL(SUM(CASE WHEN paid = 1 THEN 1 ELSE 0 END),0) AS paidCount, "
            + "IFNULL(SUM(CASE WHEN paid = 0 THEN 1 ELSE 0 END),0) AS unpaidCount, "
            + "IFNULL(SUM(CASE WHEN paid = 0 THEN total ELSE 0 END),0) AS unpaidAmount "
            + "FROM bills WHERE createdAt BETWEEN :from AND :to")
    类059 summary(long from, long to);

    @Query("SELECT i.name AS name, SUM(i.qty) AS qty, SUM(i.price * i.qty) AS amount "
            + "FROM bill_items i JOIN bills b ON b.id = i.billId "
            + "WHERE b.createdAt BETWEEN :from AND :to "
            + "GROUP BY i.name ORDER BY amount DESC")
    List<类061> salesByProduct(long from, long to);

    @Query("SELECT IFNULL(i.categoryName,'Uncategorised') AS category, "
            + "SUM(i.qty) AS qty, SUM(i.price * i.qty) AS amount "
            + "FROM bill_items i JOIN bills b ON b.id = i.billId "
            + "WHERE b.createdAt BETWEEN :from AND :to "
            + "GROUP BY category ORDER BY amount DESC")
    List<类058> salesByCategory(long from, long to);

    @Query("SELECT IFNULL(SUM(p.costPrice * i.qty), 0) FROM bill_items i JOIN bills b ON b.id = i.billId LEFT JOIN products p ON p.id = i.productId WHERE b.createdAt BETWEEN :from AND :to")
    double productCostBetween(long from, long to);

    @Query("SELECT COUNT(*) FROM bills")
    int totalBills();
}
