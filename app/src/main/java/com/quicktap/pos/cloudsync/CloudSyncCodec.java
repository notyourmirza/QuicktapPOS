package com.quicktap.pos.cloudsync;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

import com.quicktap.pos.data.AppDatabase;
import com.quicktap.pos.data.dao.BillDao;
import com.quicktap.pos.data.dao.CategoryDao;
import com.quicktap.pos.data.dao.CommercialDao;
import com.quicktap.pos.data.dao.ExpenseDao;
import com.quicktap.pos.data.dao.LedgerDao;
import com.quicktap.pos.data.dao.ProductDao;
import com.quicktap.pos.data.dao.SyncDao;
import com.quicktap.pos.data.entity.Bill;
import com.quicktap.pos.data.entity.BillItem;
import com.quicktap.pos.data.entity.Category;
import com.quicktap.pos.data.entity.Customer;
import com.quicktap.pos.data.entity.Expense;
import com.quicktap.pos.data.entity.LedgerCustomer;
import com.quicktap.pos.data.entity.Product;
import com.quicktap.pos.data.entity.Supplier;

/**
 * Translates between local Room entities and the generic Cloud Sync record
 * contract, and applies records that came DOWN from the server.
 *
 * Record shape sent to /v1/sync/push:
 * <pre>
 * {"id":"<uuid>","entity":"products","data":{...},"version":1,
 *  "is_deleted":false,"updated_at":1700000000000}
 * </pre>
 *
 * IMPORTANT: this class writes through the DAOs directly, never through
 * com.quicktap.pos.data.类009 (the repository). That is what stops records
 * received from the server from re-entering the push queue - the change
 * capture hook lives inside the repository, and we simply do not go through it.
 */
public final class CloudSyncCodec {

    public static final String OP_UPSERT = "UPSERT";
    public static final String OP_DELETE = "DELETE";

    // Entity names. These match CloudController::TYPED_ENTITIES on the server.
    public static final String PRODUCTS = "products";
    public static final String CATEGORIES = "categories";
    public static final String BILLS = "bills";
    public static final String CUSTOMERS = "customers";
    public static final String SUPPLIERS = "suppliers";
    public static final String EXPENSES = "expenses";
    public static final String LEDGER_CUSTOMERS = "ledger_customers";

    public static final String[] ENTITIES = {
            CATEGORIES, PRODUCTS, BILLS, CUSTOMERS, SUPPLIERS, EXPENSES, LEDGER_CUSTOMERS
    };

    /** Field carrying the originating device's clock, used for last-write-wins. */
    private static final String CLIENT_AT = "clientUpdatedAt";

    private CloudSyncCodec() { }

    // ===================================================================
    //  ENCODE  (local -> server)
    // ===================================================================

    public static String encodeProduct(Context context, Product product, long localCategoryUuidOwner) {
        JSONObject data = new JSONObject();
        put(data, "name", product.name);
        put(data, "price", product.price);
        put(data, "costPrice", product.costPrice);
        put(data, "barcode", product.barcode);
        put(data, "imageUri", product.imageUri);
        put(data, "available", product.available);
        put(data, "favorite", product.favorite);
        put(data, "stock", product.stock);
        put(data, "minimumStock", product.minimumStock);
        put(data, "soldCount", product.soldCount);
        put(data, "categoryUuid", categoryUuidOf(context, product.categoryId));
        put(data, CLIENT_AT, product.updatedAt);
        return data.toString();
    }

    public static String encodeCategory(Category category) {
        JSONObject data = new JSONObject();
        put(data, "name", category.name);
        put(data, "position", category.position);
        put(data, CLIENT_AT, category.updatedAt);
        return data.toString();
    }

    public static String encodeBill(Bill bill, List<BillItem> items) {
        JSONObject data = new JSONObject();
        put(data, "invoiceNo", bill.invoiceNo);
        put(data, "createdAt", bill.createdAt);
        put(data, "orderType", bill.orderType);
        put(data, "tableNo", bill.tableNo);
        put(data, "customerName", bill.customerName);
        put(data, "customerPhone", bill.customerPhone);
        put(data, "address", bill.address);
        put(data, "notes", bill.notes);
        put(data, "customerCode", bill.customerCode);
        put(data, "subtotal", bill.subtotal);
        put(data, "discount", bill.discount);
        put(data, "discountAmount", bill.discountAmount);
        put(data, "discountType", bill.discountType);
        put(data, "discountPercentage", bill.discountPercentage);
        put(data, "tax", bill.tax);
        put(data, "total", bill.total);
        put(data, "paid", bill.paid);
        put(data, "paymentMethod", bill.paymentMethod);
        put(data, CLIENT_AT, bill.updatedAt);

        JSONArray lines = new JSONArray();
        if (items != null) {
            for (BillItem item : items) {
                JSONObject line = new JSONObject();
                put(line, "name", item.name);
                put(line, "categoryName", item.categoryName);
                put(line, "price", item.price);
                put(line, "qty", item.qty);
                put(line, "productId", item.productId);
                lines.put(line);
            }
        }
        put(data, "items", lines);
        return data.toString();
    }

    public static String encodeCustomer(Customer row) {
        JSONObject data = new JSONObject();
        put(data, "name", row.name);
        put(data, "phone", row.phone);
        put(data, "address", row.address);
        put(data, "notes", row.notes);
        put(data, "active", row.active);
        put(data, CLIENT_AT, row.updatedAt);
        return data.toString();
    }

    public static String encodeSupplier(Supplier row) {
        JSONObject data = new JSONObject();
        put(data, "name", row.name);
        put(data, "phone", row.phone);
        put(data, "address", row.address);
        put(data, "notes", row.notes);
        put(data, "active", row.active);
        put(data, CLIENT_AT, row.updatedAt);
        return data.toString();
    }

    public static String encodeExpense(Expense row) {
        JSONObject data = new JSONObject();
        put(data, "title", row.title);
        put(data, "category", row.category);
        put(data, "amount", row.amount);
        put(data, "date", row.date);
        put(data, "time", row.time);
        put(data, "description", row.description);
        put(data, CLIENT_AT, row.updatedAt);
        return data.toString();
    }

    public static String encodeLedgerCustomer(LedgerCustomer row) {
        JSONObject data = new JSONObject();
        put(data, "customerCode", row.customerCode);
        put(data, "name", row.name);
        put(data, "phone", row.phone);
        put(data, "address", row.address);
        put(data, "notes", row.notes);
        put(data, "customFields", row.customFields);
        put(data, "active", row.active);
        put(data, CLIENT_AT, row.updatedAt);
        return data.toString();
    }

    /** Resolves the uuid of a product's category (empty when uncategorised). */
    private static String categoryUuidOf(Context context, long categoryId) {
        if (categoryId <= 0) {
            return "";
        }
        try {
            SyncDao syncDao = AppDatabase.get(context).syncDao();
            String uuid = syncDao.categoryUuid(categoryId);
            if (uuid != null && !uuid.isEmpty()) {
                return uuid;
            }
            // The category may have been created before this feature existed and
            // therefore have no uuid yet - fall back to scanning the small table.
            for (Category category : AppDatabase.get(context).categoryDao().getAll()) {
                if (category.id == categoryId && category.uuid != null && !category.uuid.isEmpty()) {
                    return category.uuid;
                }
            }
        } catch (Throwable ignored) {
            // an unmapped category is not worth failing a push over
        }
        return "";
    }

    // ===================================================================
    //  lookup helpers
    // ===================================================================

    /**
     * True when this device already holds a record with this uuid.
     * Used by the import wizard's "import only missing" mode.
     */
    public static boolean existsLocally(Context context, CloudSyncStore store,
                                        String entity, String uuid) {
        if (uuid == null || uuid.isEmpty()) {
            return false;
        }
        try {
            SyncDao syncDao = AppDatabase.get(context).syncDao();
            switch (entity) {
                case CATEGORIES:
                    return syncDao.categoryByUuid(uuid) != null;
                case PRODUCTS:
                    return syncDao.productByUuid(uuid) != null;
                case BILLS:
                    return syncDao.billByUuid(uuid) != null;
                case CUSTOMERS:
                case SUPPLIERS:
                case EXPENSES:
                case LEDGER_CUSTOMERS:
                    return store.localIdFor(entity, uuid) > 0L;
                default:
                    return false;
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Rough size of the local dataset - used to decide on a pre-import backup. */
    public static int localRecordCount(Context context) {
        try {
            AppDatabase db = AppDatabase.get(context);
            return db.productDao().count()
                    + db.categoryDao().count()
                    + db.billDao().totalBills();
        } catch (Throwable ignored) {
            return 0;
        }
    }

    // ===================================================================
    //  APPLY  (server -> local)
    // ===================================================================

    /**
     * Applies one record received from the server.
     *
     * @return true when the local database was changed.
     */
    public static boolean apply(Context context, CloudSyncStore store, JSONObject record) {
        String entity = record.optString("entity", "");
        String uuid = record.optString("id", "");
        JSONObject data = record.optJSONObject("data");
        boolean deleted = record.optBoolean("is_deleted", false);
        if (data == null) {
            data = new JSONObject();
        }
        if (uuid.isEmpty()) {
            return false;
        }
        long clientAt = data.optLong(CLIENT_AT, System.currentTimeMillis());

        try {
            AppDatabase db = AppDatabase.get(context);
            switch (entity) {
                case CATEGORIES:
                    return applyCategory(db, store, uuid, data, deleted, clientAt);
                case PRODUCTS:
                    return applyProduct(context, db, store, uuid, data, deleted, clientAt);
                case BILLS:
                    return applyBill(db, store, uuid, data, deleted, clientAt);
                case CUSTOMERS:
                    return applyCustomer(db, store, uuid, data, deleted, clientAt);
                case SUPPLIERS:
                    return applySupplier(db, store, uuid, data, deleted, clientAt);
                case EXPENSES:
                    return applyExpense(db, store, uuid, data, deleted, clientAt);
                case LEDGER_CUSTOMERS:
                    return applyLedgerCustomer(db, store, uuid, data, deleted, clientAt);
                default:
                    return false;   // entity we do not know - ignore, never crash
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------- categories

    private static boolean applyCategory(AppDatabase db, CloudSyncStore store, String uuid,
                                         JSONObject data, boolean deleted, long clientAt) {
        CategoryDao dao = db.categoryDao();
        SyncDao syncDao = db.syncDao();
        Category existing = syncDao.categoryByUuid(uuid);
        if (existing == null) {
            if (deleted) {
                return false;
            }
            Category created = new Category();
            created.name = data.optString("name", "Unnamed");
            created.position = data.optInt("position", 0);
            created.uuid = uuid;
            created.updatedAt = clientAt;
            long id = dao.insert(created);
            store.bind(CATEGORIES, id, uuid);
            return true;
        }
        if (existing.updatedAt > clientAt) {
            return false;                    // local row is newer - keep it
        }
        if (deleted) {
            // Mirror the app's own delete: soft, so billing history stays intact.
            existing.deleted = true;
            existing.updatedAt = clientAt;
            dao.update(existing);
            store.touchMap(CATEGORIES, existing.id, clientAt, true);
            return true;
        }
        existing.name = data.optString("name", existing.name);
        existing.position = data.optInt("position", existing.position);
        existing.updatedAt = clientAt;
        dao.update(existing);
        store.touchMap(CATEGORIES, existing.id, clientAt, false);
        return true;
    }

    // --------------------------------------------------------------- products

    private static boolean applyProduct(Context context, AppDatabase db, CloudSyncStore store,
                                        String uuid, JSONObject data, boolean deleted, long clientAt) {
        ProductDao dao = db.productDao();
        SyncDao syncDao = db.syncDao();
        Product existing = syncDao.productByUuid(uuid);
        if (existing == null) {
            if (deleted) {
                return false;
            }
            Product created = new Product();
            created.name = data.optString("name", "Unnamed");
            created.price = data.optDouble("price", 0d);
            created.costPrice = data.optDouble("costPrice", 0d);
            created.barcode = data.optString("barcode", "");
            created.imageUri = data.optString("imageUri", null);
            created.available = data.optBoolean("available", true);
            created.favorite = data.optBoolean("favorite", false);
            created.stock = data.optInt("stock", -1);
            created.minimumStock = data.optInt("minimumStock", 5);
            created.soldCount = data.optInt("soldCount", 0);
            created.categoryId = localCategoryId(db, data.optString("categoryUuid", ""));
            created.uuid = uuid;
            created.updatedAt = clientAt;
            long id = dao.insert(created);
            store.bind(PRODUCTS, id, uuid);
            return true;
        }
        if (existing.updatedAt > clientAt) {
            return false;
        }
        if (deleted) {
            existing.deleted = true;
            existing.updatedAt = clientAt;
            dao.update(existing);
            store.touchMap(PRODUCTS, existing.id, clientAt, true);
            return true;
        }
        existing.name = data.optString("name", existing.name);
        existing.price = data.optDouble("price", existing.price);
        existing.costPrice = data.optDouble("costPrice", existing.costPrice);
        existing.barcode = data.optString("barcode", existing.barcode);
        existing.imageUri = data.optString("imageUri", existing.imageUri);
        existing.available = data.optBoolean("available", existing.available);
        existing.favorite = data.optBoolean("favorite", existing.favorite);
        existing.stock = data.optInt("stock", existing.stock);
        existing.minimumStock = data.optInt("minimumStock", existing.minimumStock);
        existing.soldCount = data.optInt("soldCount", existing.soldCount);
        long categoryId = localCategoryId(db, data.optString("categoryUuid", ""));
        if (categoryId > 0) {
            existing.categoryId = categoryId;
        }
        existing.updatedAt = clientAt;
        dao.update(existing);
        store.touchMap(PRODUCTS, existing.id, clientAt, false);
        return true;
    }

    private static long localCategoryId(AppDatabase db, String categoryUuid) {
        if (categoryUuid == null || categoryUuid.isEmpty()) {
            return 0L;
        }
        Long id = db.syncDao().categoryIdByUuid(categoryUuid);
        return id == null ? 0L : id;
    }

    // ------------------------------------------------------------------ bills

    private static boolean applyBill(AppDatabase db, CloudSyncStore store, String uuid,
                                     JSONObject data, boolean deleted, long clientAt) {
        BillDao dao = db.billDao();
        SyncDao syncDao = db.syncDao();
        Bill existing = syncDao.billByUuid(uuid);
        if (existing == null) {
            if (deleted) {
                return false;
            }
            Bill created = new Bill();
            copyBill(data, created);
            created.uuid = uuid;
            created.updatedAt = clientAt;
            long id = dao.insertBill(created);
            dao.insertItems(billItems(id, data));
            store.bind(BILLS, id, uuid);
            return true;
        }
        if (existing.updatedAt > clientAt) {
            return false;
        }
        if (deleted) {
            dao.deleteBillCascade(existing.id);
            store.touchMap(BILLS, existing.id, clientAt, true);
            return true;
        }
        copyBill(data, existing);
        existing.updatedAt = clientAt;
        dao.updateBill(existing);
        dao.deleteItems(existing.id);
        dao.insertItems(billItems(existing.id, data));
        store.touchMap(BILLS, existing.id, clientAt, false);
        return true;
    }

    private static void copyBill(JSONObject data, Bill bill) {
        bill.invoiceNo = data.optString("invoiceNo", bill.invoiceNo);
        bill.createdAt = data.optLong("createdAt", bill.createdAt);
        bill.orderType = data.optString("orderType", bill.orderType);
        bill.tableNo = data.optString("tableNo", bill.tableNo);
        bill.customerName = data.optString("customerName", bill.customerName);
        bill.customerPhone = data.optString("customerPhone", bill.customerPhone);
        bill.address = data.optString("address", bill.address);
        bill.notes = data.optString("notes", bill.notes);
        bill.customerCode = data.optInt("customerCode", bill.customerCode);
        bill.subtotal = data.optDouble("subtotal", bill.subtotal);
        bill.discount = data.optDouble("discount", bill.discount);
        bill.discountAmount = data.optDouble("discountAmount", bill.discountAmount);
        bill.discountType = data.optString("discountType", bill.discountType);
        bill.discountPercentage = data.optDouble("discountPercentage", bill.discountPercentage);
        bill.tax = data.optDouble("tax", bill.tax);
        bill.total = data.optDouble("total", bill.total);
        bill.paid = data.optBoolean("paid", bill.paid);
        bill.paymentMethod = data.optString("paymentMethod", bill.paymentMethod);
    }

    private static java.util.List<BillItem> billItems(long billId, JSONObject data) {
        java.util.List<BillItem> items = new java.util.ArrayList<>();
        JSONArray lines = data.optJSONArray("items");
        if (lines == null) {
            return items;
        }
        for (int i = 0; i < lines.length(); i++) {
            JSONObject line = lines.optJSONObject(i);
            if (line == null) {
                continue;
            }
            BillItem item = new BillItem();
            item.billId = billId;
            item.name = line.optString("name", "Item");
            item.categoryName = line.optString("categoryName", "");
            item.price = line.optDouble("price", 0d);
            item.qty = line.optInt("qty", 1);
            item.productId = line.optLong("productId", 0L);
            items.add(item);
        }
        return items;
    }

    // -------------------------------------------------------------- customers

    private static boolean applyCustomer(AppDatabase db, CloudSyncStore store, String uuid,
                                         JSONObject data, boolean deleted, long clientAt) {
        CommercialDao dao = db.commercialDao();
        long localId = store.localIdFor(CUSTOMERS, uuid);
        Customer existing = localId > 0 ? dao.customer(localId) : null;
        if (existing == null) {
            if (deleted) {
                return false;
            }
            Customer created = new Customer();
            created.name = data.optString("name", "");
            created.phone = data.optString("phone", "");
            created.address = data.optString("address", "");
            created.notes = data.optString("notes", "");
            created.active = data.optBoolean("active", true);
            created.createdAt = clientAt;
            created.updatedAt = clientAt;
            long id = dao.insertCustomer(created);
            store.bind(CUSTOMERS, id, uuid);
            return true;
        }
        if (existing.updatedAt > clientAt) {
            return false;
        }
        if (deleted) {
            dao.deleteCustomer(existing);
            store.touchMap(CUSTOMERS, existing.id, clientAt, true);
            return true;
        }
        existing.name = data.optString("name", existing.name);
        existing.phone = data.optString("phone", existing.phone);
        existing.address = data.optString("address", existing.address);
        existing.notes = data.optString("notes", existing.notes);
        existing.active = data.optBoolean("active", existing.active);
        existing.updatedAt = clientAt;
        dao.updateCustomer(existing);
        store.touchMap(CUSTOMERS, existing.id, clientAt, false);
        return true;
    }

    // -------------------------------------------------------------- suppliers

    private static boolean applySupplier(AppDatabase db, CloudSyncStore store, String uuid,
                                         JSONObject data, boolean deleted, long clientAt) {
        CommercialDao dao = db.commercialDao();
        long localId = store.localIdFor(SUPPLIERS, uuid);
        Supplier existing = localId > 0 ? dao.supplier(localId) : null;
        if (existing == null) {
            if (deleted) {
                return false;
            }
            Supplier created = new Supplier();
            created.name = data.optString("name", "");
            created.phone = data.optString("phone", "");
            created.address = data.optString("address", "");
            created.notes = data.optString("notes", "");
            created.active = data.optBoolean("active", true);
            created.createdAt = clientAt;
            created.updatedAt = clientAt;
            long id = dao.insertSupplier(created);
            store.bind(SUPPLIERS, id, uuid);
            return true;
        }
        if (existing.updatedAt > clientAt) {
            return false;
        }
        if (deleted) {
            dao.deleteSupplier(existing);
            store.touchMap(SUPPLIERS, existing.id, clientAt, true);
            return true;
        }
        existing.name = data.optString("name", existing.name);
        existing.phone = data.optString("phone", existing.phone);
        existing.address = data.optString("address", existing.address);
        existing.notes = data.optString("notes", existing.notes);
        existing.active = data.optBoolean("active", existing.active);
        existing.updatedAt = clientAt;
        dao.updateSupplier(existing);
        store.touchMap(SUPPLIERS, existing.id, clientAt, false);
        return true;
    }

    // --------------------------------------------------------------- expenses

    private static boolean applyExpense(AppDatabase db, CloudSyncStore store, String uuid,
                                        JSONObject data, boolean deleted, long clientAt) {
        ExpenseDao dao = db.expenseDao();
        long localId = store.localIdFor(EXPENSES, uuid);
        Expense existing = localId > 0 ? dao.byId(localId) : null;
        if (existing == null) {
            if (deleted) {
                return false;
            }
            Expense created = new Expense();
            created.title = data.optString("title", "Expense");
            created.category = data.optString("category", "Other");
            created.amount = data.optDouble("amount", 0d);
            created.date = data.optLong("date", clientAt);
            created.time = data.optString("time", "");
            created.description = data.optString("description", "");
            created.createdAt = clientAt;
            created.updatedAt = clientAt;
            long id = dao.insert(created);
            store.bind(EXPENSES, id, uuid);
            return true;
        }
        if (existing.updatedAt > clientAt) {
            return false;
        }
        if (deleted) {
            dao.delete(existing);
            store.touchMap(EXPENSES, existing.id, clientAt, true);
            return true;
        }
        existing.title = data.optString("title", existing.title);
        existing.category = data.optString("category", existing.category);
        existing.amount = data.optDouble("amount", existing.amount);
        existing.date = data.optLong("date", existing.date);
        existing.time = data.optString("time", existing.time);
        existing.description = data.optString("description", existing.description);
        existing.updatedAt = clientAt;
        dao.update(existing);
        store.touchMap(EXPENSES, existing.id, clientAt, false);
        return true;
    }

    // -------------------------------------------------------- ledger customers

    private static boolean applyLedgerCustomer(AppDatabase db, CloudSyncStore store, String uuid,
                                               JSONObject data, boolean deleted, long clientAt) {
        LedgerDao dao = db.ledgerDao();
        long localId = store.localIdFor(LEDGER_CUSTOMERS, uuid);
        LedgerCustomer existing = localId > 0 ? dao.byId(localId) : null;
        if (existing == null) {
            if (deleted) {
                return false;
            }
            LedgerCustomer created = new LedgerCustomer();
            created.customerCode = data.optInt("customerCode", 0);
            if (created.customerCode <= 0) {
                created.customerCode = dao.nextCode();
            }
            created.name = data.optString("name", "");
            created.phone = data.optString("phone", "");
            created.address = data.optString("address", "");
            created.notes = data.optString("notes", "");
            created.customFields = data.optString("customFields", "{}");
            created.active = data.optBoolean("active", true);
            created.createdAt = clientAt;
            created.updatedAt = clientAt;
            long id = dao.insertCustomer(created);
            store.bind(LEDGER_CUSTOMERS, id, uuid);
            return true;
        }
        if (existing.updatedAt > clientAt) {
            return false;
        }
        if (deleted) {
            existing.active = false;      // ledger rows are referenced by bills
            existing.updatedAt = clientAt;
            dao.updateCustomer(existing);
            store.touchMap(LEDGER_CUSTOMERS, existing.id, clientAt, true);
            return true;
        }
        existing.name = data.optString("name", existing.name);
        existing.phone = data.optString("phone", existing.phone);
        existing.address = data.optString("address", existing.address);
        existing.notes = data.optString("notes", existing.notes);
        existing.customFields = data.optString("customFields", existing.customFields);
        existing.active = data.optBoolean("active", existing.active);
        existing.updatedAt = clientAt;
        dao.updateCustomer(existing);
        store.touchMap(LEDGER_CUSTOMERS, existing.id, clientAt, false);
        return true;
    }

    // ===================================================================
    //  helpers
    // ===================================================================

    private static void put(JSONObject object, String key, Object value) {
        try {
            if (value == null) {
                object.put(key, JSONObject.NULL);
            } else {
                object.put(key, value);
            }
        } catch (Exception ignored) {
            // a single bad field must never abort the whole record
        }
    }
}
