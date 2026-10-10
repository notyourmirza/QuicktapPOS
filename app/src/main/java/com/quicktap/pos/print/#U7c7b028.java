package com.quicktap.pos.print;

import com.quicktap.pos.data.entity.Bill;
import com.quicktap.pos.data.entity.BillItem;
import com.quicktap.pos.util.类046;
import com.quicktap.pos.util.类049;
import com.quicktap.pos.util.类053;

import java.util.List;
import java.util.Locale;

/** Text preview for the single receipt design shown in Printer Settings. */
public final class 类028 {
    private 类028() { }

    public static String render(类046 prefs, Bill bill, List<BillItem> items) {
        int width = prefs.getPaperChars();
        String customCode = prefs.getReceiptTemplateCode();
        if (customCode != null && !customCode.trim().isEmpty()) {
            return 类032.render(customCode, prefs, bill, items);
        }

        StringBuilder s = new StringBuilder();
        line(s, center(prefs.getStoreName().toUpperCase(Locale.getDefault()), width));
        divider(s, '-', width);
        line(s, center(bill.paid ? "SALES INVOICE" : "PROFORMA INVOICE", width));
        divider(s, '-', width);
        line(s, pair("Invoice", bill.invoiceNo, width));
        line(s, pair("Date", 类049.receipt(bill.createdAt), width));
        line(s, pair("Order", type(bill.orderType), width));
        if (!empty(bill.customerName)) line(s, pair("Customer", bill.customerName, width));
        if (!empty(bill.customerPhone)) line(s, pair("Phone", bill.customerPhone, width));
        if (!empty(bill.address)) wrap(s, "Address: " + bill.address, width);
        divider(s, '-', width);
        line(s, center("ORDER ITEMS", width));
        line(s, columns("ITEM", "QTY", "AMOUNT", width));
        divider(s, '-', width);

        类030.Template template = 类030.byKey(prefs.getReceiptTemplate());
        if (items != null) for (BillItem item : items) {
            if (template.showItemPrice) {
                line(s, clip(item.name, width));
                line(s, columns("  @ " + 类053.format(item.price), String.valueOf(item.qty), 类053.format(item.lineTotal()), width));
            } else {
                line(s, columns(item.name, String.valueOf(item.qty), 类053.format(item.lineTotal()), width));
            }
        }
        divider(s, '-', width);
        line(s, right("Subtotal", 类053.format(bill.subtotal), width));
        double discount = bill.discountAmount > 0 ? bill.discountAmount : bill.discount;
        line(s, right("Discount", "-" + 类053.format(discount), width));
        line(s, right("Tax", 类053.format(bill.tax), width));
        divider(s, '=', width);
        line(s, right("GRAND TOTAL " + prefs.getCurrency(), 类053.format(bill.total), width));
        divider(s, '=', width);
        line(s, center("PAYMENT:  " + (bill.paymentMethod == null || bill.paymentMethod.isEmpty() ? "CASH" : bill.paymentMethod), width));
        line(s, center("STATUS:  " + (bill.paid ? "PAID" : "UNPAID"), width));
        divider(s, '-', width);
        if (prefs.isReceiptFooterFeatureEnabled()) {
            if (template.thankYouBlock) line(s, center("Thank you for your business", width));
            if (!empty(prefs.getReceiptFooter())) wrapCentered(s, prefs.getReceiptFooter(), width);
        }
        if (prefs.isDemoMode()) {
            line(s, center("THIS IS JUST A DEMO", width));
            wrapCentered(s, "No real transaction or licence is used.", width);
        }
        line(s, center("POWERED BY " + prefs.getThemeAppName().toUpperCase(Locale.getDefault()), width));
        return s.toString();
    }

    private static void line(StringBuilder s, String text) { s.append(text).append('\n'); }
    private static void divider(StringBuilder s, char c, int width) { line(s, repeat(c, width)); }
    private static void wrap(StringBuilder s, String text, int width) {
        String v = text == null ? "" : text.trim();
        while (v.length() > width) { int cut = v.lastIndexOf(' ', width); if (cut <= 0) cut = width; line(s, clip(v.substring(0, cut).trim(), width)); v = v.substring(cut).trim(); }
        if (!v.isEmpty()) line(s, v);
    }
    private static void wrapCentered(StringBuilder s, String text, int width) {
        String v = text == null ? "" : text.trim();
        while (v.length() > width) { int cut = v.lastIndexOf(' ', width); if (cut <= 0) cut = width; line(s, center(v.substring(0, cut).trim(), width)); v = v.substring(cut).trim(); }
        if (!v.isEmpty()) line(s, center(v, width));
    }
    private static String pair(String a, String b, int width) { int label = Math.min(10, width / 3); return pad(a + ":", label) + clip(b, Math.max(0, width - label)); }
    private static String right(String a, String b, int width) { int value = Math.min(12, width / 3); return pad(clip(a, Math.max(0, width - value)), width - value) + left(b, value); }
    private static String columns(String a, String q, String b, int width) { int rw = Math.min(10, width / 3), qw = 4, lw = Math.max(1, width - rw - qw); return pad(clip(a, lw), lw) + left(q, qw) + left(b, rw); }
    private static String center(String v, int width) { String x = clip(v, width); return spaces(Math.max(0, (width - x.length()) / 2)) + x; }
    private static String pad(String v, int n) { return v + spaces(Math.max(0, n - v.length())); }
    private static String left(String v, int n) { return spaces(Math.max(0, n - v.length())) + clip(v, n); }
    private static String repeat(char c, int n) { StringBuilder b = new StringBuilder(); for (int i = 0; i < n; i++) b.append(c); return b.toString(); }
    private static String spaces(int n) { return repeat(' ', n); }
    private static String clip(String v, int n) { if (v == null) return ""; return v.length() <= n ? v : v.substring(0, Math.max(0, n)); }
    private static boolean empty(String v) { return v == null || v.trim().isEmpty(); }
    private static String type(String v) { if (Bill.TYPE_DELIVERY.equals(v)) return "Delivery"; if (Bill.TYPE_TAKE_AWAY.equals(v)) return "Take Away"; if (Bill.TYPE_DINE_IN.equals(v)) return "Dine In"; return v == null || v.isEmpty() ? "Dine In" : v; }
}
