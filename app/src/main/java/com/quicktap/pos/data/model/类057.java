package com.quicktap.pos.data.model;

import com.quicktap.pos.data.entity.Product;

/** In-memory cart row. Never persisted directly; converted to BillItem on print. */
public class 类057 {
    public final long productId;
    public final String name;
    public final String categoryName;
    public final double price;
    public final String imageUri;
    public int qty;

    public 类057(Product product, String categoryName) {
        this.productId = product.id;
        this.name = product.name;
        this.categoryName = categoryName;
        this.price = product.price;
        this.imageUri = product.imageUri;
        this.qty = 1;
    }

    /** Rebuilds a row from storage (parked bills) without touching the database. */
    public 类057(long productId, String name, String categoryName, double price, int qty) {
        this.productId = productId;
        this.name = name;
        this.categoryName = categoryName;
        this.price = price;
        this.imageUri = null;
        this.qty = Math.max(1, qty);
    }

    public 类057 copy() {
        return new 类057(productId, name, categoryName, price, qty);
    }

    public double total() { return price * qty; }
}
