package com.quicktap.pos.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.migration.Migration;
import androidx.sqlite.db.SupportSQLiteDatabase;

import com.quicktap.pos.data.dao.BillDao;
import com.quicktap.pos.data.dao.ExpenseDao;
import com.quicktap.pos.data.dao.CategoryDao;
import com.quicktap.pos.data.dao.ProductDao;
import com.quicktap.pos.data.dao.SyncDao;
import com.quicktap.pos.data.dao.CommercialDao;
import com.quicktap.pos.data.entity.Bill;
import com.quicktap.pos.data.entity.Expense;
import com.quicktap.pos.data.entity.ExpenseCategory;
import com.quicktap.pos.data.entity.BillItem;
import com.quicktap.pos.data.entity.Category;
import com.quicktap.pos.data.entity.Product;
import com.quicktap.pos.data.entity.Customer;
import com.quicktap.pos.data.entity.Supplier;
import com.quicktap.pos.data.entity.Purchase;
import com.quicktap.pos.data.entity.PurchaseItem;
import com.quicktap.pos.data.entity.CashSession;
import com.quicktap.pos.data.entity.Refund;
import com.quicktap.pos.data.entity.LedgerCustomer;
import com.quicktap.pos.data.entity.LedgerEntry;

@Database(
        entities = {Product.class, Category.class, Bill.class, BillItem.class,
                Expense.class, ExpenseCategory.class, Customer.class, Supplier.class,
                Purchase.class, PurchaseItem.class, CashSession.class, Refund.class,
                LedgerCustomer.class, LedgerEntry.class},
        version = 11,
        exportSchema = false)
public abstract class AppDatabase extends RoomDatabase {

    public static final String NAME = "quicktap.db";

    public abstract ProductDao productDao();

    public abstract CategoryDao categoryDao();

    public abstract BillDao billDao();

    public abstract SyncDao syncDao();

    public abstract ExpenseDao expenseDao();

    public abstract CommercialDao commercialDao();

    public abstract com.quicktap.pos.data.dao.LedgerDao ledgerDao();

    /**
     * v1 -> v2: adds the offline-sync bookkeeping columns (uuid, updatedAt,
     * dirty, deleted) and backfills existing rows so nothing is lost.
     */
    public static final Migration MIGRATION_1_2 = new Migration(1, 2) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            for (String table : new String[]{"products", "categories", "bills"}) {
                db.execSQL("ALTER TABLE " + table + " ADD COLUMN uuid TEXT");
                db.execSQL("ALTER TABLE " + table + " ADD COLUMN updatedAt INTEGER NOT NULL DEFAULT 0");
                db.execSQL("ALTER TABLE " + table + " ADD COLUMN dirty INTEGER NOT NULL DEFAULT 1");
                db.execSQL("ALTER TABLE " + table + " ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0");
                db.execSQL("UPDATE " + table + " SET uuid = lower(hex(randomblob(16))) WHERE uuid IS NULL");
                db.execSQL("UPDATE " + table + " SET updatedAt = strftime('%s','now') * 1000");
            }
        }
    };

    /**
     * v2 -> v3: de-duplicates rows that earlier auto-sync builds copied in and
     * enforces "one row per uuid" with a unique index, so a re-sync or a
     * restored backup can never insert a second copy of the same record again.
     */
    public static final Migration MIGRATION_2_3 = new Migration(2, 3) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            for (String table : new String[]{"products", "categories", "bills"}) {
                db.execSQL("UPDATE " + table + " SET uuid = lower(hex(randomblob(16))) "
                        + "WHERE uuid IS NULL OR uuid = ''");
                db.execSQL("DELETE FROM " + table + " WHERE id NOT IN "
                        + "(SELECT MIN(id) FROM " + table + " GROUP BY uuid)");
            }
            db.execSQL("DELETE FROM bill_items WHERE billId NOT IN (SELECT id FROM bills)");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_products_uuid ON products(uuid)");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_categories_uuid ON categories(uuid)");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_bills_uuid ON bills(uuid)");
        }
    };

    /** v3 -> v4: records the checkout payment channel without changing existing bill status. */
    public static final Migration MIGRATION_3_4 = new Migration(3, 4) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE bills ADD COLUMN paymentMethod TEXT NOT NULL DEFAULT 'CASH'");
        }
    };

    /** v4 -> v5: adds local expense management tables. */
    public static final Migration MIGRATION_4_5 = new Migration(4, 5) {
        @Override
        public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS expenses (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, title TEXT NOT NULL, category TEXT NOT NULL, amount REAL NOT NULL, date INTEGER NOT NULL, time TEXT NOT NULL, description TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_date ON expenses(date)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_expenses_category ON expenses(category)");
            db.execSQL("CREATE TABLE IF NOT EXISTS expense_categories (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, createdAt INTEGER NOT NULL)");
        }
    };

    /** v5 -> v6: adds optional product purchase cost for net-profit reporting. */
    public static final Migration MIGRATION_5_6 = new Migration(5, 6) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE products ADD COLUMN costPrice REAL NOT NULL DEFAULT 0");
        }
    };

    /** v6 -> v7: stores the exact discount mode and value on each bill. */
    public static final Migration MIGRATION_6_7 = new Migration(6, 7) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE bills ADD COLUMN discountAmount REAL NOT NULL DEFAULT 0");
            db.execSQL("ALTER TABLE bills ADD COLUMN discountType TEXT NOT NULL DEFAULT 'AMOUNT'");
            db.execSQL("ALTER TABLE bills ADD COLUMN discountPercentage REAL NOT NULL DEFAULT 0");
            db.execSQL("UPDATE bills SET discountAmount = discount, discountType = 'AMOUNT' WHERE discount > 0");
        }
    };

    /** v7 -> v8: adds per-product minimum stock threshold. */
    public static final Migration MIGRATION_7_8 = new Migration(7, 8) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE products ADD COLUMN minimumStock INTEGER NOT NULL DEFAULT 5");
        }
    };

    /** v8 -> v9: adds optional commercial-module tables without altering existing records. */
    public static final Migration MIGRATION_8_9 = new Migration(8, 9) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("CREATE TABLE IF NOT EXISTS customers (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, phone TEXT NOT NULL, address TEXT NOT NULL, notes TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, active INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_customers_phone ON customers(phone)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_customers_name ON customers(name)");
            db.execSQL("CREATE TABLE IF NOT EXISTS suppliers (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, phone TEXT NOT NULL, address TEXT NOT NULL, notes TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, active INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_suppliers_name ON suppliers(name)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_suppliers_phone ON suppliers(phone)");
            db.execSQL("CREATE TABLE IF NOT EXISTS purchases (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, supplierId INTEGER NOT NULL, invoiceNo TEXT NOT NULL, total REAL NOT NULL, purchaseDate INTEGER NOT NULL, notes TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_purchases_supplierId ON purchases(supplierId)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_purchases_createdAt ON purchases(createdAt)");
            db.execSQL("CREATE TABLE IF NOT EXISTS purchase_items (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, purchaseId INTEGER NOT NULL, productId INTEGER NOT NULL, quantity INTEGER NOT NULL, unitCost REAL NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_purchase_items_purchaseId ON purchase_items(purchaseId)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_purchase_items_productId ON purchase_items(productId)");
            db.execSQL("CREATE TABLE IF NOT EXISTS cash_sessions (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, openedAt INTEGER NOT NULL, closedAt INTEGER NOT NULL, openingCash REAL NOT NULL, expectedCash REAL NOT NULL, countedCash REAL NOT NULL, cashIn REAL NOT NULL, cashOut REAL NOT NULL, status TEXT NOT NULL, notes TEXT NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_cash_sessions_openedAt ON cash_sessions(openedAt)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_cash_sessions_status ON cash_sessions(status)");
            db.execSQL("CREATE TABLE IF NOT EXISTS refunds (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, billId INTEGER NOT NULL, productId INTEGER NOT NULL, quantity INTEGER NOT NULL, amount REAL NOT NULL, reason TEXT NOT NULL, refundMethod TEXT NOT NULL, createdAt INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_refunds_billId ON refunds(billId)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_refunds_createdAt ON refunds(createdAt)");
        }
    };

    /** v9 -> v10: adds the optional customer ledger without deleting prior commercial data. */
    public static final Migration MIGRATION_9_10 = new Migration(9, 10) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE bills ADD COLUMN customerCode INTEGER NOT NULL DEFAULT 0");
            db.execSQL("CREATE TABLE IF NOT EXISTS ledger_customers (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, customerCode INTEGER NOT NULL, name TEXT NOT NULL, phone TEXT NOT NULL, address TEXT NOT NULL, notes TEXT NOT NULL, createdAt INTEGER NOT NULL, updatedAt INTEGER NOT NULL, active INTEGER NOT NULL)");
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_ledger_customers_customerCode ON ledger_customers(customerCode)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_customers_name ON ledger_customers(name)");
            db.execSQL("CREATE TABLE IF NOT EXISTS ledger_entries (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, customerId INTEGER NOT NULL, billId INTEGER NOT NULL, type TEXT NOT NULL, amount REAL NOT NULL, note TEXT NOT NULL, createdAt INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_entries_customerId ON ledger_entries(customerId)");
            db.execSQL("CREATE INDEX IF NOT EXISTS index_ledger_entries_createdAt ON ledger_entries(createdAt)");
        }
    };

    /** v10 -> v11: stores optional user-defined customer fields as JSON. */
    public static final Migration MIGRATION_10_11 = new Migration(10, 11) {
        @Override public void migrate(@NonNull SupportSQLiteDatabase db) {
            db.execSQL("ALTER TABLE ledger_customers ADD COLUMN customFields TEXT NOT NULL DEFAULT '{}'");
        }
    };

    private static volatile AppDatabase instance;

    public static AppDatabase get(Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                                    context.getApplicationContext(), AppDatabase.class, NAME)
                            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11)
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return instance;
    }

    /** Used by restore-from-backup, which swaps the underlying file. */
    public static void closeInstance() {
        if (instance != null) {
            instance.close();
            instance = null;
        }
    }
}
