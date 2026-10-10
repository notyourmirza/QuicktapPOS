package com.quicktap.pos.print;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.quicktap.pos.data.entity.Bill;
import com.quicktap.pos.data.entity.BillItem;
import com.quicktap.pos.util.类046;
import com.quicktap.pos.util.类049;
import com.quicktap.pos.util.类053;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Turns a saved bill into an ESC/POS byte stream. Layout adapts to the
 * configured paper width (32 chars for 58mm, 48 chars for 80mm).
 */
public class 类026 {

    private final 类046 prefs;
    private final int width;
    private final 类030.Template template;

    public 类026(类046 prefs) {
        this.prefs = prefs;
        this.width = prefs.getPaperChars();
        this.template = 类030.byKey(prefs.getReceiptTemplate());
    }

    public byte[] build(Bill bill, List<BillItem> items) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write(类021.INIT);
            out.write(类021.ALIGN_CENTER);

            // ----- optional shop logo -----
            byte[] logo = prefs.isReceiptLogoFeatureEnabled() ? logoBytes() : new byte[0];
            if (logo.length > 0) {
                out.write(logo);
                line(out, "");
            }
            String customCode = prefs.getReceiptTemplateCode();
            if (customCode != null && !customCode.trim().isEmpty()) {
                out.write(类021.ALIGN_CENTER);
                line(out, 类032.render(customCode, prefs, bill, items));
                out.write(类021.FEED_CUT);
                return out.toByteArray();
            }

            // ----- professional store header -----
            out.write(类021.BOLD_ON);
            line(out, clip(prefs.getStoreName().toUpperCase(Locale.getDefault()), width));
            out.write(类021.BOLD_OFF);
            if (notEmpty(prefs.getStoreAddress())) wrap(out, prefs.getStoreAddress());
            if (notEmpty(prefs.getStorePhone())) line(out, "Tel: " + prefs.getStorePhone());
            rule(out, false);
            if (prefs.isReceiptHeaderFeatureEnabled() && prefs.isReceiptNoteEnabled() && notEmpty(prefs.getReceiptNote())) {
                line(out, "");
                wrap(out, prefs.getReceiptNote());
            }

            out.write(类021.BOLD_ON);
            line(out, bill.paid ? "SALES INVOICE" : "PROFORMA INVOICE");
            out.write(类021.BOLD_OFF);
            rule(out, true);

            // ----- bill meta -----
            out.write(template.centeredMeta ? 类021.ALIGN_CENTER : 类021.ALIGN_LEFT);
            line(out, pair("Invoice", bill.invoiceNo));
            line(out, pair("Date", 类049.receipt(bill.createdAt)));
            line(out, pair("Order", readableType(bill.orderType)));
            if (notEmpty(bill.customerName)) line(out, pair("Customer", bill.customerName));
            if (notEmpty(bill.customerPhone)) line(out, pair("Phone", bill.customerPhone));
            if (notEmpty(bill.address)) wrap(out, "Address: " + bill.address);
            out.write(类021.ALIGN_LEFT);
            rule(out, false);

            // ----- items -----
            out.write(类021.BOLD_ON);
            line(out, "ORDER ITEMS");
            out.write(类021.BOLD_OFF);
            line(out, columns("ITEM", "QTY", "AMOUNT"));
            out.write(类021.BOLD_OFF);
            rule(out, false);
            for (BillItem item : items) {
                if (template.showItemPrice) {
                    line(out, clip(item.name, width));
                    line(out, columns("  @ " + 类053.format(item.price),
                            String.valueOf(item.qty), 类053.format(item.lineTotal())));
                } else {
                    line(out, columns(item.name, String.valueOf(item.qty),
                            类053.format(item.lineTotal())));
                }
            }
            rule(out, false);

            // ----- totals -----
            line(out, right("Subtotal", 类053.format(bill.subtotal)));
            double appliedDiscount = bill.discountAmount > 0 ? bill.discountAmount : bill.discount;
            line(out, right("Discount", "-" + 类053.format(appliedDiscount)));
            line(out, right("Tax", 类053.format(bill.tax)));
            rule(out, true);
            out.write(类021.BOLD_ON);
            out.write(类021.DOUBLE_HEIGHT_ON);
            line(out, right("GRAND TOTAL " + prefs.getCurrency(), 类053.format(bill.total)));
            out.write(类021.DOUBLE_OFF);
            out.write(类021.BOLD_OFF);
            rule(out, true);
            out.write(类021.ALIGN_CENTER);
            line(out, "PAYMENT:  " + (bill.paymentMethod == null || bill.paymentMethod.isEmpty() ? "CASH" : bill.paymentMethod));
            line(out, "STATUS:  " + (bill.paid ? "PAID" : "UNPAID"));
            out.write(类021.ALIGN_LEFT);
            if (notEmpty(bill.notes)) {
                rule(out, false);
                out.write(类021.BOLD_ON);
                line(out, "CUSTOMER / RIDER NOTE");
                out.write(类021.BOLD_OFF);
                wrap(out, bill.notes);
            }

            // ----- footer -----
            rule(out, true);
            out.write(类021.ALIGN_CENTER);
            if (prefs.isReceiptFooterFeatureEnabled()) {
                if (template.thankYouBlock) line(out, "Thank you for your business");
                if (notEmpty(prefs.getReceiptFooter())) wrap(out, prefs.getReceiptFooter());
            }
            line(out, "");
            line(out, "Powered by " + prefs.getThemeAppName());
            out.write(类021.FEED_CUT);
        } catch (IOException e) {
            // ByteArrayOutputStream does not throw in practice.
            throw new IllegalStateException(e);
        }
        return out.toByteArray();

    }

    /** Simple centred test slip used by "Test print" in printer settings. */
    public byte[] testPage() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            out.write(类021.INIT);
            out.write(类021.ALIGN_CENTER);
            out.write(类021.DOUBLE_ON);
            line(out, prefs.getThemeAppName());
            out.write(类021.DOUBLE_OFF);
            line(out, "Printer test successful");
            line(out, prefs.getStoreName());
            line(out, 类049.receipt(System.currentTimeMillis()));
            line(out, divider('='));
            line(out, "Paper width: " + width + " chars");
            out.write(类021.FEED_CUT);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return out.toByteArray();
    }

    // ---------------- text layout helpers ----------------

    private void line(ByteArrayOutputStream out, String text) throws IOException {
        out.write((text + "\n").getBytes(StandardCharsets.ISO_8859_1));
    }

    /** Draws the template's divider; heavy rules use the template character. */
    private void rule(ByteArrayOutputStream out, boolean heavy) throws IOException {
        char c = template.divider;
        if (c == ' ') { line(out, ""); return; }
        line(out, divider(heavy ? c : '-'));
    }

    private String divider(char c) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < width; i++) sb.append(c);
        return sb.toString();
    }

    private String pair(String label, String value) {
        return padRight(label + ":", 10) + safe(value);
    }

    /** left | qty | right, with the right column flush to the paper edge. */
    private String columns(String left, String qty, String right) {
        int rightWidth = 10;
        int qtyWidth = 5;
        int leftWidth = Math.max(1, width - rightWidth - qtyWidth);
        return padRight(clip(left, leftWidth), leftWidth)
                + padLeft(qty, qtyWidth)
                + padLeft(right, rightWidth);
    }

    private String right(String label, String value) {
        int valueWidth = 12;
        int labelWidth = Math.max(1, width - valueWidth);
        return padRight(clip(label, labelWidth), labelWidth) + padLeft(value, valueWidth);
    }

    private String clip(String text, int max) {
        String s = safe(text);
        return s.length() <= max ? s : s.substring(0, max);
    }

    private String padRight(String text, int size) {
        StringBuilder sb = new StringBuilder(safe(text));
        while (sb.length() < size) sb.append(' ');
        return sb.toString();
    }

    private String padLeft(String text, int size) {
        StringBuilder sb = new StringBuilder(safe(text));
        while (sb.length() < size) sb.insert(0, ' ');
        return sb.toString();
    }

    private String safe(String value) { return value == null ? "" : value; }

    /** Word-wraps long lines so nothing is cut off on narrow paper. */
    private void wrap(ByteArrayOutputStream out, String text) throws IOException {
        String value = safe(text).trim();
        while (value.length() > width) {
            int cut = value.lastIndexOf(' ', width);
            if (cut <= 0) cut = width;
            line(out, value.substring(0, cut).trim());
            value = value.substring(cut).trim();
        }
        if (!value.isEmpty()) line(out, value);
    }

    /** Optional monochrome logo printed above the store name. */
    private byte[] logoBytes() {
        if (!prefs.isReceiptLogoEnabled()) return new byte[0];
        String path = prefs.getReceiptLogoPath();
        if (path == null || path.isEmpty() || !new File(path).exists()) return new byte[0];
        try {
            Bitmap bitmap = BitmapFactory.decodeFile(path);
            if (bitmap == null) return new byte[0];
            int dots = width >= 42 ? 384 : 240;
            byte[] raster = 类021.raster(bitmap, dots);
            bitmap.recycle();
            return raster;
        } catch (Throwable t) {
            return new byte[0];
        }
    }


    private boolean notEmpty(String value) { return value != null && !value.trim().isEmpty(); }

    private String readableType(String type) {
        switch (type) {
            case com.quicktap.pos.data.entity.Bill.TYPE_TAKE_AWAY: return "Take Away";
            case com.quicktap.pos.data.entity.Bill.TYPE_DELIVERY: return "Delivery";
            default: return type == null || type.isEmpty() ? "Dine In" : type;
        }
    }
}
