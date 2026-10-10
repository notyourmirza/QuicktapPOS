package com.quicktap.pos.print;

import com.quicktap.pos.data.entity.Bill;
import com.quicktap.pos.data.entity.BillItem;
import com.quicktap.pos.util.类046;
import com.quicktap.pos.util.类049;
import com.quicktap.pos.util.类053;
import java.util.List;

/** Renders the optional simple token-based receipt code consistently in preview and print. */
public final class 类032 {
    private 类032() { }
    public static String render(String code, 类046 prefs, Bill bill, List<BillItem> items) {
        StringBuilder itemText = new StringBuilder();
        if (items != null) for (BillItem item : items) itemText.append(item.name).append("  x").append(item.qty).append("  ").append(类053.format(item.lineTotal())).append("\n");
        double discount = bill.discountAmount > 0 ? bill.discountAmount : bill.discount;
        String out = code.replace("{STORE_NAME}", safe(prefs.getStoreName()))
                .replace("{ADDRESS}", safe(prefs.getStoreAddress()))
                .replace("{PHONE}", safe(prefs.getStorePhone()))
                .replace("{INVOICE}", safe(bill.invoiceNo))
                .replace("{DATE}", 类049.receipt(bill.createdAt))
                .replace("{ORDER_TYPE}", type(bill.orderType))
                .replace("{PAYMENT}", safe(bill.paymentMethod))
                .replace("{STATUS}", bill.paid ? "PAID" : "UNPAID")
                .replace("{ITEMS}", itemText.toString().trim())
                .replace("{SUBTOTAL}", 类053.format(bill.subtotal))
                .replace("{DISCOUNT}", 类053.format(discount))
                .replace("{TAX}", 类053.format(bill.tax))
                .replace("{TOTAL}", 类053.format(bill.total))
                .replace("{NOTE}", safe(bill.notes))
                .replace("{FOOTER}", safe(prefs.getReceiptFooter()));
        return out;
    }
    private static String safe(String v) { return v == null ? "" : v; }
    private static String type(String v) { if (Bill.TYPE_TAKE_AWAY.equals(v)) return "Take Away"; if (Bill.TYPE_DELIVERY.equals(v)) return "Delivery"; if (Bill.TYPE_DINE_IN.equals(v)) return "Dine In"; return v == null || v.isEmpty() ? "Dine In" : v; }
}
