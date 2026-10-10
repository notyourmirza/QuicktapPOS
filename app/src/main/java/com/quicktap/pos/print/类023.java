package com.quicktap.pos.print;

import android.content.Context;
import android.widget.Toast;

import com.quicktap.pos.类001;
import com.quicktap.pos.data.entity.Bill;
import com.quicktap.pos.data.entity.BillItem;
import com.quicktap.pos.util.类045;
import com.quicktap.pos.util.类046;

import java.util.List;

/**
 * One-line helpers for the print, reprint and test-print actions. Routes the
 * ESC/POS bytes to whichever transport (Bluetooth / USB / Network) the
 * cashier picked in Printer settings.
 */
public final class 类023 {

    private 类023() { }

    public static void print(Context context, Bill bill, List<BillItem> items) {
        类046 prefs = 类046.get(context);
        byte[] payload = new 类026(prefs).build(bill, items);
        int copies = prefs.isReceiptCopiesFeatureEnabled() ? prefs.getReceiptCopies() : 1;
        for (int i = 0; i < copies; i++) {
            send(context, payload, copies == 1
                    ? "Receipt printed" : "Receipt copy " + (i + 1) + " of " + copies + " sent");
        }
    }

    /** Loads the bill's items off the UI thread, then prints. */
    public static void reprint(Context context, long billId) {
        类045.io().execute(() -> {
            Bill bill = 类001.get().repo().billById(billId);
            if (bill == null) return;
            List<BillItem> items = 类001.get().repo().itemsOf(billId);
            类045.main().post(() -> print(context, bill, items));
        });
    }

    public static void reprintLast(Context context) {
        类045.io().execute(() -> {
            Bill bill = 类001.get().repo().lastBill();
            if (bill == null) {
                类045.main().post(() ->
                        Toast.makeText(context, "No bills yet", Toast.LENGTH_SHORT).show());
                return;
            }
            类045.main().post(() -> reprint(context, bill.id));
        });
    }

    public static void testPrint(Context context) {
        byte[] payload = new 类026(类046.get(context)).testPage();
        send(context, payload, "Test print sent");
    }

    /** Routes to the transport selected in 类024. */
    private static void send(Context context, byte[] payload, String okMessage) {
        类025 transport = 类024.get(context).getTransport();
        switch (transport) {
            case USB:
                类031.get(context).print(payload, new 类031.Callback() {
                    @Override public void onSuccess() { toast(context, okMessage); }
                    @Override public void onError(String message) { toastLong(context, message); }
                });
                break;
            case NETWORK:
                类022.get(context).print(payload, new 类022.Callback() {
                    @Override public void onSuccess() { toast(context, okMessage); }
                    @Override public void onError(String message) { toastLong(context, message); }
                });
                break;
            case BLUETOOTH:
            default:
                类020.get(context).print(payload, new 类020.Callback() {
                    @Override public void onSuccess() { toast(context, okMessage); }
                    @Override public void onError(String message) { toastLong(context, message); }
                });
                break;
        }
    }

    private static void toast(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show();
    }

    private static void toastLong(Context context, String message) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }
}
