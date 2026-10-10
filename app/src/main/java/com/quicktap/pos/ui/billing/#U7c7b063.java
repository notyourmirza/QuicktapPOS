package com.quicktap.pos.ui.billing;

import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Transformations;

import com.quicktap.pos.类001;
import com.quicktap.pos.data.类009;
import com.quicktap.pos.data.entity.Bill;
import com.quicktap.pos.data.entity.BillItem;
import com.quicktap.pos.data.entity.Category;
import com.quicktap.pos.data.entity.Product;
import com.quicktap.pos.data.model.类057;
import com.quicktap.pos.util.类045;
import com.quicktap.pos.util.类046;

import java.util.ArrayList;
import java.util.List;

/** Holds the live cart and the filtered product grid for the billing screen. */
public class 类063 extends AndroidViewModel {

    /** Filter key encoded as "categoryId|query" so one LiveData drives the grid. */
    private final MutableLiveData<String> filter = new MutableLiveData<>("0|");
    private final MutableLiveData<List<类057>> cart = new MutableLiveData<>(new ArrayList<>());
    private final LiveData<List<Product>> products;
    private final LiveData<List<Category>> categories;
    private final 类009 repo;
    private final 类046 prefs;

    private long categoryId = 0;
    private String query = "";
    private double orderDiscountInput = 0d;
    private String orderDiscountType = "AMOUNT";

    public 类063(@NonNull Application application) {
        super(application);
        repo = 类001.get().repo();
        prefs = 类046.get(application);
        categories = repo.observeCategories();
        products = Transformations.switchMap(filter, key -> {
            String[] parts = key.split("\\|", 2);
            return repo.observeProducts(Long.parseLong(parts[0]), parts.length > 1 ? parts[1] : "");
        });
    }

    public LiveData<List<Product>> products() { return products; }

    public LiveData<List<Category>> categories() { return categories; }

    public LiveData<List<类057>> cart() { return cart; }

    public void setCategory(long id) {
        categoryId = id;
        filter.setValue(categoryId + "|" + query);
    }

    public void setQuery(String text) {
        query = text == null ? "" : text.trim();
        filter.setValue(categoryId + "|" + query);
    }

    // ---------------- cart operations ----------------

    public void add(Product product, String categoryName) {
        List<类057> lines = current();
        for (类057 line : lines) {
            if (line.productId == product.id) {
                line.qty++;
                cart.setValue(lines);
                return;
            }
        }
        lines.add(new 类057(product, categoryName));
        cart.setValue(lines);
    }

    public void setQty(int index, int qty) {
        List<类057> lines = current();
        if (index < 0 || index >= lines.size()) return;
        if (qty <= 0) lines.remove(index);
        else lines.get(index).qty = qty;
        cart.setValue(lines);
    }

    public void remove(int index) { setQty(index, 0); }

    public void clear() {
        cart.setValue(new ArrayList<>());
        orderDiscountInput = 0d;
        orderDiscountType = "AMOUNT";
    }

    /** Adds an exact quantity in one shot (long press on a product card). */
    public void addQty(Product product, String categoryName, int qty) {
        if (qty <= 0) return;
        List<类057> lines = current();
        for (类057 line : lines) {
            if (line.productId == product.id) {
                line.qty += qty;
                cart.setValue(lines);
                return;
            }
        }
        类057 line = new 类057(product, categoryName);
        line.qty = qty;
        lines.add(line);
        cart.setValue(lines);
    }

    /** Detached copy of the cart, safe to park in storage. */
    public List<类057> snapshot() {
        List<类057> copy = new ArrayList<>();
        for (类057 line : current()) copy.add(line.copy());
        return copy;
    }

    /** Replaces the cart with a resumed parked order. */
    public void restore(List<类057> lines) {
        List<类057> copy = new ArrayList<>();
        if (lines != null) for (类057 line : lines) copy.add(line.copy());
        cart.setValue(copy);
    }


    private List<类057> current() {
        List<类057> lines = cart.getValue();
        return lines == null ? new ArrayList<>() : new ArrayList<>(lines);
    }

    // ---------------- totals ----------------

    public double subtotal() {
        double sum = 0;
        for (类057 line : current()) sum += line.total();
        return sum;
    }

    public void setDiscountType(String type) {
        orderDiscountType = "PERCENT".equals(type) ? "PERCENT" : "AMOUNT";
        orderDiscountInput = 0d;
    }

    public String discountType() { return orderDiscountType; }
    public double discountInput() { return orderDiscountInput; }

    /** Sets a non-negative discount and clamps it so the total can never go below zero. */
    public void setDiscountInput(double value) { orderDiscountInput = Math.max(0d, value); }

    public double discountAmount(double subtotal) {
        if (subtotal <= 0d || orderDiscountInput <= 0d) return 0d;
        double amount = "PERCENT".equals(orderDiscountType)
                ? subtotal * Math.min(100d, orderDiscountInput) / 100d
                : orderDiscountInput;
        return Math.min(subtotal, Math.max(0d, amount));
    }

    public double taxAmount(double taxable) {
        return taxable * prefs.getTaxPercent() / 100d;
    }

    public double grandTotal() {
        double sub = subtotal();
        double discount = discountAmount(sub);
        double taxable = sub - discount;
        return taxable + taxAmount(taxable);
    }

    public interface SaveCallback {
        void onSaved(Bill bill, List<BillItem> items);
    }

    /** Persists the bill off the UI thread and hands it back for printing. */
    public void saveBill(String orderType, String tableNo, String customerName,
                         String customerPhone, String address, String notes,
                         boolean paid, String paymentMethod, int customerCode,
                         SaveCallback callback) {
        List<类057> lines = current();
        if (lines.isEmpty()) return;

        double sub = subtotal();
        double discount = discountAmount(sub);
        double taxable = sub - discount;
        double tax = taxAmount(taxable);

        Bill bill = new Bill();
        bill.createdAt = System.currentTimeMillis();
        bill.orderType = orderType;
        bill.tableNo = tableNo;
        bill.customerName = customerName;
        bill.customerPhone = customerPhone;
        bill.address = address;
        bill.notes = notes;
        bill.customerCode = Math.max(0, customerCode);
        bill.subtotal = sub;
        bill.discount = discount;
        bill.discountAmount = discount;
        bill.discountType = orderDiscountType;
        bill.discountPercentage = "PERCENT".equals(orderDiscountType) ? Math.min(100d, orderDiscountInput) : 0d;
        bill.tax = tax;
        bill.total = taxable + tax;
        bill.paid = paid;
        bill.paymentMethod = paymentMethod == null || paymentMethod.trim().isEmpty()
                ? "CASH" : paymentMethod;

        List<BillItem> items = new ArrayList<>();
        for (类057 line : lines) {
            BillItem item = new BillItem();
            item.productId = line.productId;
            item.name = line.name;
            item.categoryName = line.categoryName;
            item.price = line.price;
            item.qty = line.qty;
            items.add(item);
        }

        类045.io().execute(() -> {
            long id = repo.saveBillBlocking(bill, items);
            bill.id = id;
            类045.main().post(() -> callback.onSaved(bill, items));
        });
    }
}
