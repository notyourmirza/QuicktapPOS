package com.quicktap.pos.cloudsync;

import android.content.Context;

import com.quicktap.pos.data.AppDatabase;
import com.quicktap.pos.data.entity.Bill;
import com.quicktap.pos.data.entity.BillItem;
import com.quicktap.pos.data.entity.Category;
import com.quicktap.pos.data.entity.Customer;
import com.quicktap.pos.data.entity.Expense;
import com.quicktap.pos.data.entity.LedgerCustomer;
import com.quicktap.pos.data.entity.Product;
import com.quicktap.pos.data.entity.Supplier;

import java.util.List;
import java.util.UUID;

/**
 * The smallest possible hook between the POS and Cloud Sync.
 *
 * These methods are called from com.quicktap.pos.data.类009 (the single place
 * where the app writes data). Each one does nothing at all when the feature is
 * off, and every one of them swallows its own exceptions: a sync problem must
 * never be able to stop a sale.
 *
 * Nothing here reads or writes business data - it only mirrors what the
 * repository has already committed into the Cloud Sync queue.
 */
public final class CloudSyncHooks {

    private CloudSyncHooks() { }

    // ------------------------------------------------------------- products

    public static void productUpserted(Context context, Product product) {
        if (!CloudSyncGate.enabled(context) || product == null) {
            return;
        }
        try {
            CloudSyncStore store = CloudSyncStore.get(context);
            String uuid = store.uuidFor(CloudSyncCodec.PRODUCTS, product.id,
                    product.uuid == null || product.uuid.isEmpty() ? null : product.uuid);
            if (product.uuid == null || product.uuid.isEmpty()) {
                product.uuid = uuid;
            }
            store.enqueue(CloudSyncCodec.PRODUCTS, product.id, uuid, CloudSyncCodec.OP_UPSERT,
                    CloudSyncCodec.encodeProduct(context, product, 0L), product.updatedAt);
            CloudSyncWorker.kick(context);
        } catch (Throwable ignored) {
            // never break a save
        }
    }

    public static void productDeleted(Context context, Product product) {
        if (!CloudSyncGate.enabled(context) || product == null) {
            return;
        }
        try {
            CloudSyncStore store = CloudSyncStore.get(context);
            String uuid = store.uuidFor(CloudSyncCodec.PRODUCTS, product.id, product.uuid);
            store.enqueue(CloudSyncCodec.PRODUCTS, product.id, uuid, CloudSyncCodec.OP_DELETE, "",
                    product.updatedAt);
            CloudSyncWorker.kick(context);
        } catch (Throwable ignored) {
            // never break a delete
        }
    }

    /**
     * A sale changes stock directly through the DAO, so the affected products
     * must be queued explicitly (the repository never calls saveProduct then).
     */
    public static void productsStockChanged(Context context, List<Long> productIds) {
        if (!CloudSyncGate.enabled(context) || productIds == null || productIds.isEmpty()) {
            return;
        }
        try {
            AppDatabase db = AppDatabase.get(context);
            for (Long id : productIds) {
                if (id == null || id <= 0) {
                    continue;
                }
                Product product = db.productDao().byId(id);
                if (product == null) {
                    continue;
                }
                if (product.updatedAt <= 0) {
                    product.updatedAt = System.currentTimeMillis();
                }
                productUpserted(context, product);
            }
        } catch (Throwable ignored) {
            // stock bookkeeping is not worth failing a sale over
        }
    }

    // ----------------------------------------------------------- categories

    public static void categoryUpserted(Context context, Category category) {
        if (!CloudSyncGate.enabled(context) || category == null) {
            return;
        }
        try {
            CloudSyncStore store = CloudSyncStore.get(context);
            String uuid = store.uuidFor(CloudSyncCodec.CATEGORIES, category.id,
                    category.uuid == null || category.uuid.isEmpty() ? null : category.uuid);
            if (category.uuid == null || category.uuid.isEmpty()) {
                category.uuid = uuid;
            }
            store.enqueue(CloudSyncCodec.CATEGORIES, category.id, uuid, CloudSyncCodec.OP_UPSERT,
                    CloudSyncCodec.encodeCategory(category), category.updatedAt);
            CloudSyncWorker.kick(context);
        } catch (Throwable ignored) {
            // never break a save
        }
    }

    public static void categoryDeleted(Context context, Category category) {
        if (!CloudSyncGate.enabled(context) || category == null) {
            return;
        }
        try {
            CloudSyncStore store = CloudSyncStore.get(context);
            String uuid = store.uuidFor(CloudSyncCodec.CATEGORIES, category.id, category.uuid);
            store.enqueue(CloudSyncCodec.CATEGORIES, category.id, uuid, CloudSyncCodec.OP_DELETE, "",
                    category.updatedAt);
            CloudSyncWorker.kick(context);
        } catch (Throwable ignored) {
            // never break a delete
        }
    }

    // ---------------------------------------------------------------- bills

    public static void billSaved(Context context, Bill bill, List<BillItem> items) {
        if (!CloudSyncGate.enabled(context) || bill == null) {
            return;
        }
        try {
            CloudSyncStore store = CloudSyncStore.get(context);
            String uuid = store.uuidFor(CloudSyncCodec.BILLS, bill.id, bill.uuid);
            if (bill.uuid == null || bill.uuid.isEmpty()) {
                bill.uuid = uuid;
            }
            store.enqueue(CloudSyncCodec.BILLS, bill.id, uuid, CloudSyncCodec.OP_UPSERT,
                    CloudSyncCodec.encodeBill(bill, items), bill.updatedAt);
            CloudSyncWorker.kick(context);
        } catch (Throwable ignored) {
            // NEVER let sync block or undo a sale
        }
    }

    public static void billUpdated(Context context, Bill bill) {
        if (!CloudSyncGate.enabled(context) || bill == null) {
            return;
        }
        try {
            List<BillItem> items = AppDatabase.get(context).billDao().itemsOf(bill.id);
            billSaved(context, bill, items);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    public static void billDeleted(Context context, long billId) {
        if (!CloudSyncGate.enabled(context) || billId <= 0) {
            return;
        }
        try {
            Bill bill = AppDatabase.get(context).billDao().byId(billId);
            String uuid = bill != null && bill.uuid != null && !bill.uuid.isEmpty()
                    ? bill.uuid
                    : CloudSyncStore.get(context).localIdFor(CloudSyncCodec.BILLS, "") > 0
                    ? "" : UUID.randomUUID().toString();
            if (uuid.isEmpty()) {
                return;                      // nothing was ever pushed for it
            }
            CloudSyncStore.get(context).enqueue(CloudSyncCodec.BILLS, billId, uuid,
                    CloudSyncCodec.OP_DELETE, "", System.currentTimeMillis());
            CloudSyncWorker.kick(context);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ------------------------------------------------------------ customers

    public static void customerUpserted(Context context, Customer row) {
        if (!CloudSyncGate.enabled(context) || row == null) {
            return;
        }
        try {
            CloudSyncStore store = CloudSyncStore.get(context);
            String uuid = store.uuidFor(CloudSyncCodec.CUSTOMERS, row.id, null);
            store.enqueue(CloudSyncCodec.CUSTOMERS, row.id, uuid, CloudSyncCodec.OP_UPSERT,
                    CloudSyncCodec.encodeCustomer(row), row.updatedAt);
            CloudSyncWorker.kick(context);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    public static void customerDeleted(Context context, Customer row) {
        if (!CloudSyncGate.enabled(context) || row == null) {
            return;
        }
        try {
            CloudSyncStore store = CloudSyncStore.get(context);
            String uuid = store.uuidFor(CloudSyncCodec.CUSTOMERS, row.id, null);
            store.enqueue(CloudSyncCodec.CUSTOMERS, row.id, uuid, CloudSyncCodec.OP_DELETE, "",
                    System.currentTimeMillis());
            CloudSyncWorker.kick(context);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ------------------------------------------------------------ suppliers

    public static void supplierUpserted(Context context, Supplier row) {
        if (!CloudSyncGate.enabled(context) || row == null) {
            return;
        }
        try {
            CloudSyncStore store = CloudSyncStore.get(context);
            String uuid = store.uuidFor(CloudSyncCodec.SUPPLIERS, row.id, null);
            store.enqueue(CloudSyncCodec.SUPPLIERS, row.id, uuid, CloudSyncCodec.OP_UPSERT,
                    CloudSyncCodec.encodeSupplier(row), row.updatedAt);
            CloudSyncWorker.kick(context);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    public static void supplierDeleted(Context context, Supplier row) {
        if (!CloudSyncGate.enabled(context) || row == null) {
            return;
        }
        try {
            CloudSyncStore store = CloudSyncStore.get(context);
            String uuid = store.uuidFor(CloudSyncCodec.SUPPLIERS, row.id, null);
            store.enqueue(CloudSyncCodec.SUPPLIERS, row.id, uuid, CloudSyncCodec.OP_DELETE, "",
                    System.currentTimeMillis());
            CloudSyncWorker.kick(context);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ------------------------------------------------------------- expenses

    public static void expenseUpserted(Context context, Expense row) {
        if (!CloudSyncGate.enabled(context) || row == null) {
            return;
        }
        try {
            CloudSyncStore store = CloudSyncStore.get(context);
            String uuid = store.uuidFor(CloudSyncCodec.EXPENSES, row.id, null);
            store.enqueue(CloudSyncCodec.EXPENSES, row.id, uuid, CloudSyncCodec.OP_UPSERT,
                    CloudSyncCodec.encodeExpense(row), row.updatedAt);
            CloudSyncWorker.kick(context);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    public static void expenseDeleted(Context context, Expense row) {
        if (!CloudSyncGate.enabled(context) || row == null) {
            return;
        }
        try {
            CloudSyncStore store = CloudSyncStore.get(context);
            String uuid = store.uuidFor(CloudSyncCodec.EXPENSES, row.id, null);
            store.enqueue(CloudSyncCodec.EXPENSES, row.id, uuid, CloudSyncCodec.OP_DELETE, "",
                    System.currentTimeMillis());
            CloudSyncWorker.kick(context);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ------------------------------------------------------ ledger customers

    public static void ledgerCustomerUpserted(Context context, LedgerCustomer row) {
        if (!CloudSyncGate.enabled(context) || row == null) {
            return;
        }
        try {
            CloudSyncStore store = CloudSyncStore.get(context);
            String uuid = store.uuidFor(CloudSyncCodec.LEDGER_CUSTOMERS, row.id, null);
            store.enqueue(CloudSyncCodec.LEDGER_CUSTOMERS, row.id, uuid, CloudSyncCodec.OP_UPSERT,
                    CloudSyncCodec.encodeLedgerCustomer(row), row.updatedAt);
            CloudSyncWorker.kick(context);
        } catch (Throwable ignored) {
            // ignore
        }
    }

    // ------------------------------------------------------- full local push

    /**
     * Queues every local row of every synced entity.
     *
     * Needed because rows created BEFORE Cloud Sync was switched on were never
     * captured by the hook. This is always an explicit user action (or a first
     * connect onto an empty cloud account) - it never runs by itself.
     *
     * @return number of rows queued.
     */
    public static int enqueueEverything(Context context) {
        if (!CloudSyncGate.enabled(context)) {
            return 0;
        }
        int queued = 0;
        try {
            AppDatabase db = AppDatabase.get(context);
            CloudSyncStore store = CloudSyncStore.get(context);
            long now = System.currentTimeMillis();

            for (Category category : db.categoryDao().getAll()) {
                if (category.uuid == null || category.uuid.isEmpty()) {
                    category.uuid = store.uuidFor(CloudSyncCodec.CATEGORIES, category.id, null);
                    db.categoryDao().update(category);
                }
                if (category.updatedAt <= 0) {
                    category.updatedAt = now;
                }
                store.enqueue(CloudSyncCodec.CATEGORIES, category.id, category.uuid,
                        CloudSyncCodec.OP_UPSERT, CloudSyncCodec.encodeCategory(category),
                        category.updatedAt);
                queued++;
            }

            for (Product product : db.productDao().getAllBlocking()) {
                if (product.uuid == null || product.uuid.isEmpty()) {
                    product.uuid = store.uuidFor(CloudSyncCodec.PRODUCTS, product.id, null);
                    db.productDao().update(product);
                }
                if (product.updatedAt <= 0) {
                    product.updatedAt = now;
                }
                store.enqueue(CloudSyncCodec.PRODUCTS, product.id, product.uuid,
                        CloudSyncCodec.OP_UPSERT,
                        CloudSyncCodec.encodeProduct(context, product, 0L), product.updatedAt);
                queued++;
            }

            // Bills: re-read the recent ones with their items.
            for (Bill bill : db.billDao().between(0L, Long.MAX_VALUE)) {
                if (bill.uuid == null || bill.uuid.isEmpty()) {
                    bill.uuid = store.uuidFor(CloudSyncCodec.BILLS, bill.id, null);
                    db.billDao().updateBill(bill);
                }
                if (bill.updatedAt <= 0) {
                    bill.updatedAt = bill.createdAt > 0 ? bill.createdAt : now;
                }
                store.enqueue(CloudSyncCodec.BILLS, bill.id, bill.uuid, CloudSyncCodec.OP_UPSERT,
                        CloudSyncCodec.encodeBill(bill, db.billDao().itemsOf(bill.id)),
                        bill.updatedAt);
                queued++;
            }

            for (Customer row : db.commercialDao().allCustomersBlocking()) {
                queued += enqueueMapped(context, store, CloudSyncCodec.CUSTOMERS,
                        row.id, row.updatedAt, CloudSyncCodec.encodeCustomer(row));
            }
            for (Supplier row : db.commercialDao().allSuppliersBlocking()) {
                queued += enqueueMapped(context, store, CloudSyncCodec.SUPPLIERS,
                        row.id, row.updatedAt, CloudSyncCodec.encodeSupplier(row));
            }
            for (Expense row : db.expenseDao().between(0L, Long.MAX_VALUE)) {
                queued += enqueueMapped(context, store, CloudSyncCodec.EXPENSES,
                        row.id, row.updatedAt, CloudSyncCodec.encodeExpense(row));
            }
            for (LedgerCustomer row : db.ledgerDao().customersBlocking()) {
                queued += enqueueMapped(context, store, CloudSyncCodec.LEDGER_CUSTOMERS,
                        row.id, row.updatedAt, CloudSyncCodec.encodeLedgerCustomer(row));
            }

            CloudSyncWorker.kick(context);
        } catch (Throwable ignored) {
            // a bulk queue must never crash the settings screen
        }
        return queued;
    }

    private static int enqueueMapped(Context context, CloudSyncStore store, String entity,
                                     long localId, long updatedAt, String payload) {
        String uuid = store.uuidFor(entity, localId, null);
        long stamp = updatedAt > 0 ? updatedAt : System.currentTimeMillis();
        store.enqueue(entity, localId, uuid, CloudSyncCodec.OP_UPSERT, payload, stamp);
        return 1;
    }
}
