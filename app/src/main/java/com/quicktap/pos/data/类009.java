package com.quicktap.pos.data;

import android.content.Context;

import androidx.lifecycle.LiveData;

import com.quicktap.pos.data.dao.BillDao;
import com.quicktap.pos.data.dao.CategoryDao;
import com.quicktap.pos.data.dao.ProductDao;
import com.quicktap.pos.data.entity.Bill;
import com.quicktap.pos.data.entity.BillItem;
import com.quicktap.pos.data.entity.Category;
import com.quicktap.pos.data.entity.Product;
import com.quicktap.pos.data.entity.Expense;
import com.quicktap.pos.data.entity.ExpenseCategory;
import com.quicktap.pos.data.entity.Customer;
import com.quicktap.pos.data.entity.Supplier;
import com.quicktap.pos.data.entity.Purchase;
import com.quicktap.pos.data.entity.PurchaseItem;
import com.quicktap.pos.data.entity.CashSession;
import com.quicktap.pos.data.entity.Refund;
import com.quicktap.pos.data.entity.LedgerCustomer;
import com.quicktap.pos.data.entity.LedgerEntry;
import com.quicktap.pos.data.model.类058;
import com.quicktap.pos.data.model.类059;
import com.quicktap.pos.data.model.类060;
import com.quicktap.pos.data.model.类061;
import com.quicktap.pos.sync.类034;
import com.quicktap.pos.util.类045;
import com.quicktap.pos.util.类046;
import com.quicktap.pos.util.类052;

import java.util.List;
import java.util.Locale;

/**
 * Single source of truth for all offline data. ViewModels talk only to this class;
 * nothing else in the app touches a DAO directly.
 */
public class 类009 {

    private final ProductDao productDao;
    private final CategoryDao categoryDao;
    private final BillDao billDao;
    private final com.quicktap.pos.data.dao.ExpenseDao expenseDao;
    private final com.quicktap.pos.data.dao.CommercialDao commercialDao;
    private final com.quicktap.pos.data.dao.LedgerDao ledgerDao;
    private final 类046 prefs;
    private final Context context;

    public 类009(Context context) {
        this.context = context.getApplicationContext();
        AppDatabase db = AppDatabase.get(context);
        this.productDao = db.productDao();
        this.categoryDao = db.categoryDao();
        this.billDao = db.billDao();
        this.expenseDao = db.expenseDao();
        this.commercialDao = db.commercialDao();
        this.ledgerDao = db.ledgerDao();
        this.prefs = 类046.get(context);
        类045.io().execute(this::seedIfEmpty);
    }

    /** First launch: create a small starter catalogue so the grid is never blank. */
    private void seedIfEmpty() {
        if (categoryDao.count() > 0) return;
        long fastFood = categoryDao.insert(new Category("Fast Food", 1));
        long drinks = categoryDao.insert(new Category("Drinks", 2));
        long dessert = categoryDao.insert(new Category("Dessert", 3));
        addSeed("Classic Burger", 250, fastFood, "250001", "ic_bill", 18);
        addSeed("Cheese Burger", 320, fastFood, "250002", "ic_bill", 15);
        addSeed("Chicken Pizza", 750, fastFood, "250003", "ic_bill", 12);
        addSeed("French Fries", 150, fastFood, "250004", "ic_products", 25);
        addSeed("Chicken Wrap", 280, fastFood, "250005", "ic_products", 20);
        addSeed("Club Sandwich", 420, fastFood, "250006", "ic_bill", 10);
        addSeed("Milk Tea", 60, drinks, "260001", "ic_store", 40);
        addSeed("Cold Coffee", 180, drinks, "260002", "ic_store", 22);
        addSeed("Fresh Lime", 90, drinks, "260003", "ic_store", 30);
        addSeed("Mango Shake", 220, drinks, "260004", "ic_store", 16);
        addSeed("Chocolate Cake", 220, dessert, "270001", "ic_star", 8);
        addSeed("Brownie Sundae", 260, dessert, "270002", "ic_star", 9);
    }

    private void addSeed(String name, double price, long categoryId,
                         String barcode, String imageDrawable, int stock) {
        Product p = new Product();
        p.name = name;
        p.price = price;
        p.categoryId = categoryId;
        p.barcode = barcode;
        p.stock = stock;
        p.available = true;
        p.imageUri = "drawable:" + imageDrawable;
        productDao.insert(p);
    }

    // ---------------- products ----------------

    public LiveData<List<Product>> observeProducts(long categoryId, String query) {
        return productDao.observeFiltered(categoryId, query == null ? "" : query.trim());
    }

    public LiveData<List<Product>> observeAllProducts() { return productDao.observeAll(); }
    public List<Product> getAllProductsBlocking() { return productDao.getAllBlocking(); }

    public void saveProduct(Product product, Runnable done) {
        类045.io().execute(() -> {
            stampForSync(product);
            if (product.id == 0) productDao.insert(product);
            else productDao.update(product);
            if (done != null) 类045.main().post(done);
            类034.syncAuto(context);
        });
    }

    public void deleteProduct(Product product) {
        类045.io().execute(() -> {
            // Soft delete first so the removal replicates, then drop it locally.
            product.deleted = true;
            stampForSync(product);
            productDao.update(product);
            类034.syncAuto(context);
        });
    }

    // ---------------- categories ----------------

    public LiveData<List<Category>> observeCategories() { return categoryDao.observeAll(); }

    public List<Category> categoriesBlocking() { return categoryDao.getAll(); }

    public void saveCategory(Category category) {
        类045.io().execute(() -> {
            stampForSync(category);
            if (category.id == 0) categoryDao.insert(category);
            else categoryDao.update(category);
            类034.syncAuto(context);
        });
    }

    public void deleteCategory(Category category) {
        类045.io().execute(() -> {
            category.deleted = true;
            stampForSync(category);
            categoryDao.update(category);
            类034.syncAuto(context);
        });
    }

    // ---------------- expenses ----------------

    public LiveData<List<Expense>> observeExpenses() { return expenseDao.observeAll(); }
    public LiveData<List<ExpenseCategory>> observeExpenseCategories() { return expenseDao.observeCategories(); }
    public List<Expense> expensesBetween(long from, long to) { return expenseDao.between(from, to); }
    public double expenseTotalBetween(long from, long to) { return expenseDao.totalBetween(from, to); }
    public double productCostBetween(long from, long to) { return billDao.productCostBetween(from, to); }

    public void saveExpense(Expense expense, Runnable done) {
        类045.io().execute(() -> {
            long now = System.currentTimeMillis();
            if (expense.createdAt == 0L) expense.createdAt = now;
            expense.updatedAt = now;
            if (expense.id == 0L) expenseDao.insert(expense); else expenseDao.update(expense);
            if (done != null) 类045.main().post(done);
        });
    }

    public void deleteExpense(Expense expense, Runnable done) {
        类045.io().execute(() -> {
            expenseDao.delete(expense);
            if (done != null) 类045.main().post(done);
        });
    }

    public void addExpenseCategory(String name, Runnable done) {
        类045.io().execute(() -> {
            String cleaned = name == null ? "" : name.trim();
            if (!cleaned.isEmpty() && expenseDao.countCategory(cleaned) == 0) {
                expenseDao.insertCategory(new ExpenseCategory(cleaned));
            }
            if (done != null) 类045.main().post(done);
        });
    }

    // ---------------- optional commercial modules ----------------

    public LiveData<List<Customer>> observeCustomers() { return commercialDao.observeCustomers(); }
    public void saveCustomer(Customer row, Runnable done) { 类045.io().execute(() -> { long now = System.currentTimeMillis(); if (row.createdAt == 0) row.createdAt = now; row.updatedAt = now; if (row.id == 0) commercialDao.insertCustomer(row); else commercialDao.updateCustomer(row); if (done != null) 类045.main().post(done); }); }
    public void deleteCustomer(Customer row) { 类045.io().execute(() -> commercialDao.deleteCustomer(row)); }
    public LiveData<List<Supplier>> observeSuppliers() { return commercialDao.observeSuppliers(); }
    public void saveSupplier(Supplier row, Runnable done) { 类045.io().execute(() -> { long now = System.currentTimeMillis(); if (row.createdAt == 0) row.createdAt = now; row.updatedAt = now; if (row.id == 0) commercialDao.insertSupplier(row); else commercialDao.updateSupplier(row); if (done != null) 类045.main().post(done); }); }
    public void deleteSupplier(Supplier row) { 类045.io().execute(() -> commercialDao.deleteSupplier(row)); }
    public LiveData<List<Purchase>> observePurchases() { return commercialDao.observePurchases(); }
    public long savePurchaseBlocking(Purchase purchase, List<PurchaseItem> items) { long id = commercialDao.insertPurchase(purchase); for (PurchaseItem item : items) { item.purchaseId = id; commercialDao.insertPurchaseItem(item); if (item.productId > 0 && item.quantity > 0) productDao.increaseStock(item.productId, item.quantity); } return id; }
    public LiveData<List<CashSession>> observeCashSessions() { return commercialDao.observeCashSessions(); }
    public CashSession openCashSessionBlocking() { return commercialDao.openCashSession(); }
    public long saveCashSessionBlocking(CashSession row) { return commercialDao.insertCashSession(row); }
    public void updateCashSession(CashSession row) { 类045.io().execute(() -> commercialDao.updateCashSession(row)); }
    public void saveRefund(Refund row, Runnable done) { 类045.io().execute(() -> { commercialDao.insertRefund(row); if (row.productId > 0 && row.quantity > 0) productDao.increaseStock(row.productId, row.quantity); if (done != null) 类045.main().post(done); }); }

    // ---------------- customer ledger ----------------

    public LiveData<List<LedgerCustomer>> observeLedgerCustomers() { return ledgerDao.observeCustomers(); }
    public LedgerCustomer ledgerCustomerByCodeBlocking(int code) { return code <= 0 ? null : ledgerDao.byCode(code); }
    public double ledgerBalanceBlocking(long customerId) { return ledgerDao.balance(customerId); }
    public List<LedgerEntry> ledgerEntriesBlocking(long customerId) { return ledgerDao.entriesBlocking(customerId); }
    public List<LedgerEntry> ledgerEntriesBlocking(long customerId, long from, long to) { return ledgerDao.entriesBlockingRange(customerId, from, to); }
    public double ledgerTotalCreditSalesBlocking() { return ledgerDao.totalCreditSales(); }
    public double ledgerTotalPaymentsBlocking() { return ledgerDao.totalPayments(); }
    public void saveLedgerCustomer(LedgerCustomer row, Runnable done) {
        类045.io().execute(() -> {
            long now = System.currentTimeMillis();
            if (row.createdAt == 0) row.createdAt = now;
            row.updatedAt = now;
            if (row.customerCode <= 0) row.customerCode = ledgerDao.nextCode();
            if (row.id == 0) ledgerDao.insertCustomer(row); else ledgerDao.updateCustomer(row);
            if (done != null) 类045.main().post(done);
        });
    }
    public void addLedgerDeduction(long customerId, double amount, String note, Runnable done) {
        addLedgerTransaction(customerId, amount, LedgerEntry.DEDUCTION, note, done);
    }

    private void addLedgerTransaction(long customerId, double amount, String type, String note, Runnable done) {
        类045.io().execute(() -> {
            if (customerId > 0 && amount > 0) {
                LedgerEntry entry = new LedgerEntry(); entry.customerId = customerId; entry.type = type; entry.amount = amount; entry.note = note == null ? type : note; entry.createdAt = System.currentTimeMillis(); ledgerDao.insertEntry(entry);
            }
            if (done != null) 类045.main().post(done);
        });
    }

    public void addLedgerPayment(long customerId, double amount, String note, Runnable done) {
        类045.io().execute(() -> {
            if (customerId > 0 && amount > 0) {
                LedgerEntry entry = new LedgerEntry();
                entry.customerId = customerId;
                entry.type = LedgerEntry.PAYMENT;
                entry.amount = amount;
                entry.note = note == null ? "Payment received" : note;
                entry.createdAt = System.currentTimeMillis();
                ledgerDao.insertEntry(entry);
            }
            if (done != null) 类045.main().post(done);
        });
    }

    // ---------------- bills ----------------

    public LiveData<List<Bill>> observeRecentBills(int limit) {
        return billDao.observeRecent(limit);
    }

    public LiveData<List<Bill>> observeUnpaidCustomerOrders() { return billDao.observeUnpaidCustomerOrders(); }

    public void markCustomerOrderPaid(Bill bill, Runnable done) {
        类045.io().execute(() -> {
            if (bill != null && !bill.paid && bill.customerCode > 0) {
                int changed = billDao.markPaid(bill.id, "CASH", System.currentTimeMillis());
                if (changed > 0) {
                    LedgerCustomer customer = ledgerDao.byCode(bill.customerCode);
                    if (customer != null) {
                        LedgerEntry entry = new LedgerEntry(); entry.customerId = customer.id; entry.billId = bill.id; entry.type = LedgerEntry.PAYMENT; entry.amount = bill.total; entry.note = "Collected " + bill.invoiceNo; entry.createdAt = System.currentTimeMillis(); ledgerDao.insertEntry(entry);
                    }
                    类034.syncAuto(context);
                }
            }
            if (done != null) 类045.main().post(done);
        });
    }

    public LiveData<List<Bill>> observeFilteredBills(long from, long to, String query,
                                                      String paymentMethod, String orderType,
                                                      int paidMode, int discountMode, int limit) {
        return billDao.observeFiltered(from, to, query == null ? "" : query,
                paymentMethod == null ? "" : paymentMethod,
                orderType == null ? "" : orderType, paidMode, discountMode, limit);
    }

    public LiveData<类060> observeFilteredHistorySummary(long from, long to, String query,
                                                                    String paymentMethod, String orderType,
                                                                    int paidMode, int discountMode) {
        return billDao.observeFilteredSummary(from, to, query == null ? "" : query,
                paymentMethod == null ? "" : paymentMethod,
                orderType == null ? "" : orderType, paidMode, discountMode);
    }

    public LiveData<类059> observeSummary(long from, long to) {
        return billDao.observeSummary(from, to);
    }

    public 类059 summary(long from, long to) { return billDao.summary(from, to); }

    public List<类061> salesByProduct(long from, long to) {
        return billDao.salesByProduct(from, to);
    }

    public List<类058> salesByCategory(long from, long to) {
        return billDao.salesByCategory(from, to);
    }

    public List<BillItem> itemsOf(long billId) { return billDao.itemsOf(billId); }

    public Bill billById(long id) { return billDao.byId(id); }

    public Bill lastBill() { return billDao.last(); }

    public void deleteBill(long id) {
        类045.io().execute(() -> billDao.deleteBillCascade(id));
    }

    /** Keeps the local sales database limited to the latest rolling month. */
    public void purgeBillsOlderThan(long cutoff) {
        类045.io().execute(() -> billDao.deleteBillsOlderThanCascade(cutoff));
    }

    /** Runs on the caller's (background) thread and returns the persisted id. */
    public long saveBillBlocking(Bill bill, List<BillItem> items) {
        bill.invoiceNo = nextInvoiceNo();
        if (bill.uuid == null || bill.uuid.isEmpty()) bill.uuid = 类034.newUuid();
        bill.updatedAt = System.currentTimeMillis();
        bill.dirty = true;
        long id = billDao.saveBill(bill, items);
        if (bill.customerCode > 0 && !bill.paid) {
            LedgerCustomer customer = ledgerDao.byCode(bill.customerCode);
            if (customer != null) {
                LedgerEntry entry = new LedgerEntry();
                entry.customerId = customer.id;
                entry.billId = id;
                entry.type = LedgerEntry.SALE;
                entry.amount = bill.total;
                entry.note = "Invoice " + bill.invoiceNo;
                entry.createdAt = bill.createdAt;
                ledgerDao.insertEntry(entry);
            }
        }
        for (BillItem item : items) {
            productDao.bumpSold(item.productId, item.qty);
            productDao.reduceStock(item.productId, item.qty);
        }
        类052.check(context);
        // Push the sale as soon as possible; it stays queued when offline.
        类034.syncAuto(context);
        return id;
    }

    /** Gives any locally edited row the sync metadata the server needs. */
    private void stampForSync(Product product) {
        if (product.uuid == null || product.uuid.isEmpty()) product.uuid = 类034.newUuid();
        product.updatedAt = System.currentTimeMillis();
        product.dirty = true;
    }

    private void stampForSync(Category category) {
        if (category.uuid == null || category.uuid.isEmpty()) category.uuid = 类034.newUuid();
        category.updatedAt = System.currentTimeMillis();
        category.dirty = true;
    }

    /**
     * Monotonic invoice sequence: the next number is always above both the
     * saved-bill count and the locally persisted sequence. Viewing history or
     * reopening an older invoice cannot move this value backwards.
     */
    public int nextInvoiceSequencePreview() {
        return Math.max(prefs.peekNextInvoiceSeq(), billDao.totalBills() + 1);
    }

    private String nextInvoiceNo() {
        int next = nextInvoiceSequencePreview();
        prefs.setLastInvoiceSeq(next);
        return String.format(Locale.US, "%s%06d", prefs.getInvoicePrefix(), next);
    }
}
