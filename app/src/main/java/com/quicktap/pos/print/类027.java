package com.quicktap.pos.print;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;

import com.quicktap.pos.data.entity.Bill;
import com.quicktap.pos.data.entity.BillItem;
import com.quicktap.pos.util.类046;
import com.quicktap.pos.util.类049;
import com.quicktap.pos.util.类053;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Renders a receipt as a real high-resolution image, not a screen capture. */
public final class 类027 {
    private 类027() { }

    public static Bitmap render(类046 prefs, Bill bill, List<BillItem> items) {
        final int width = prefs.getPaperChars() >= 42 ? 1400 : 1100;
        final 类030.Template template = 类030.byKey(prefs.getReceiptTemplate());
        final int pad = 64;
        final int line = 52;
        int rows = 18 + (items == null ? 0 : items.size() * 2);
        if (bill.notes != null && !bill.notes.isEmpty()) rows += 2;
        if (prefs.isReceiptHeaderFeatureEnabled() && prefs.isReceiptNoteEnabled() && prefs.getReceiptNote() != null) rows += 2;
        if (prefs.isReceiptFooterFeatureEnabled() && prefs.getReceiptFooter() != null) rows += 2;
        Bitmap logo = null;
        if (prefs.isReceiptLogoEnabled() && prefs.getReceiptLogoPath() != null) {
            logo = BitmapFactory.decodeFile(prefs.getReceiptLogoPath());
            if (logo != null) rows += 5;
        }
        int height = Math.max(1500, rows * line + 180);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(Color.WHITE);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        p.setColor(Color.rgb(25, 25, 25));
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.NORMAL));
        p.setTextSize(34f);
        float y = pad;
        if (logo != null) {
            float scale = Math.min(360f / logo.getWidth(), 240f / logo.getHeight());
            float w = logo.getWidth() * scale, h = logo.getHeight() * scale;
            canvas.drawBitmap(logo, (width - w) / 2f, y, p);
            y += h + 36;
            logo.recycle();
        }
        p.setTextAlign(Paint.Align.CENTER);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextSize(54f);
        canvas.drawText(prefs.getStoreName(), width / 2f, y, p); y += 58;
        p.setTypeface(Typeface.DEFAULT);
        p.setTextSize(30f);
        if (prefs.getStoreAddress() != null && !prefs.getStoreAddress().isEmpty()) { canvas.drawText(prefs.getStoreAddress(), width / 2f, y, p); y += 42; }
        if (prefs.getStorePhone() != null && !prefs.getStorePhone().isEmpty()) { canvas.drawText("Tel: " + prefs.getStorePhone(), width / 2f, y, p); y += 42; }
        y += 18;
        p.setTextAlign(Paint.Align.LEFT);
        if (prefs.isReceiptHeaderFeatureEnabled() && prefs.isReceiptNoteEnabled() && prefs.getReceiptNote() != null && !prefs.getReceiptNote().isEmpty()) {
            p.setTypeface(Typeface.DEFAULT); p.setTextSize(27f);
            canvas.drawText(prefs.getReceiptNote(), pad, y, p); y += 42;
        }
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        p.setTextSize(38f);
        canvas.drawText(bill.paid ? "SALES INVOICE" : "PROFORMA INVOICE", pad, y, p); y += 54;
        p.setTypeface(Typeface.DEFAULT); p.setTextSize(30f);
        y = pair(canvas, p, "Invoice", bill.invoiceNo, y, width, pad);
        y = pair(canvas, p, "Date", new SimpleDateFormat("dd MMM yyyy", Locale.getDefault()).format(new Date(bill.createdAt)), y, width, pad);
        y = pair(canvas, p, "Time", new SimpleDateFormat("hh:mm a", Locale.getDefault()).format(new Date(bill.createdAt)), y, width, pad);
        y = pair(canvas, p, "Order", readableType(bill.orderType), y, width, pad);
        if (bill.tableNo != null && !bill.tableNo.isEmpty()) y = pair(canvas, p, "Table", bill.tableNo, y, width, pad);
        if (bill.customerName != null && !bill.customerName.isEmpty()) y = pair(canvas, p, "Customer", bill.customerName, y, width, pad);
        if (bill.customerPhone != null && !bill.customerPhone.isEmpty()) y = pair(canvas, p, "Phone", bill.customerPhone, y, width, pad);
        if (bill.address != null && !bill.address.isEmpty()) y = pair(canvas, p, "Address", bill.address, y, width, pad);
        y += 16; rule(canvas, p, y, width, pad); y += 42;
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        canvas.drawText("ITEM", pad, y, p); canvas.drawText("QTY", width / 2f, y, p); canvas.drawText("AMOUNT", width - pad, y, p); y += 46;
        p.setTypeface(Typeface.DEFAULT);
        if (items != null) for (BillItem item : items) {
            canvas.drawText(clip(item.name, 30), pad, y, p);
            p.setTextAlign(Paint.Align.CENTER); canvas.drawText(String.valueOf(item.qty), width / 2f, y, p);
            p.setTextAlign(Paint.Align.RIGHT); canvas.drawText(类053.format(item.lineTotal()), width - pad, y, p);
            p.setTextAlign(Paint.Align.LEFT); y += 42;
            if (template.showItemPrice) { canvas.drawText("@ " + 类053.format(item.price) + " each", pad + 18, y, p); y += 42; }
        }
        rule(canvas, p, y, width, pad); y += 50;
        y = pair(canvas, p, "Subtotal", 类053.withCurrency(prefs.getCurrency(), bill.subtotal), y, width, pad);
        double discount = bill.discountAmount > 0 ? bill.discountAmount : bill.discount;
        y = pair(canvas, p, "Discount", "-" + 类053.withCurrency(prefs.getCurrency(), discount), y, width, pad);
        y = pair(canvas, p, "Tax", 类053.withCurrency(prefs.getCurrency(), bill.tax), y, width, pad);
        y += 14; rule(canvas, p, y, width, pad); y += 58;
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD)); p.setTextSize(44f);
        y = pair(canvas, p, "GRAND TOTAL", 类053.withCurrency(prefs.getCurrency(), bill.total), y, width, pad); p.setTextSize(30f); p.setTypeface(Typeface.DEFAULT);
        y = pair(canvas, p, "Payment", bill.paymentMethod == null ? "CASH" : bill.paymentMethod, y, width, pad);
        y = pair(canvas, p, "Status", bill.paid ? "PAID" : "UNPAID", y, width, pad);
        if (bill.notes != null && !bill.notes.isEmpty()) y = pair(canvas, p, "CUSTOMER / RIDER NOTE", bill.notes, y, width, pad);
        y += 34; rule(canvas, p, y, width, pad); y += 48;
        if (prefs.isReceiptFooterFeatureEnabled()) {
            p.setTextAlign(Paint.Align.CENTER);
            if (template.thankYouBlock) { canvas.drawText("Thank you for your business", width / 2f, y, p); y += 46; }
            if (prefs.getReceiptFooter() != null && !prefs.getReceiptFooter().isEmpty()) { canvas.drawText(prefs.getReceiptFooter(), width / 2f, y, p); y += 46; }
        }
        p.setTextAlign(Paint.Align.CENTER); p.setTextSize(26f); canvas.drawText("Powered by " + prefs.getThemeAppName(), width / 2f, y + 20, p);
        return Bitmap.createBitmap(bitmap, 0, 0, width, Math.min(height, (int) y + 80));
    }

    private static float pair(Canvas c, Paint p, String label, String value, float y, int width, int pad) {
        p.setTextAlign(Paint.Align.LEFT); c.drawText(label, pad, y, p);
        p.setTextAlign(Paint.Align.RIGHT); c.drawText(value == null ? "" : value, width - pad, y, p);
        p.setTextAlign(Paint.Align.LEFT); return y + 48;
    }
    private static void rule(Canvas c, Paint p, float y, int width, int pad) { p.setColor(Color.rgb(190,190,190)); c.drawRect(pad, y, width - pad, y + 2, p); p.setColor(Color.rgb(25,25,25)); }
    private static String clip(String s, int max) { if (s == null) return ""; return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)) + "…"; }
    private static String readableType(String type) { if (Bill.TYPE_TAKE_AWAY.equals(type)) return "Take Away"; if (Bill.TYPE_DELIVERY.equals(type)) return "Delivery"; if (Bill.TYPE_DINE_IN.equals(type)) return "Dine In"; return type == null || type.isEmpty() ? "Dine In" : type; }
}
