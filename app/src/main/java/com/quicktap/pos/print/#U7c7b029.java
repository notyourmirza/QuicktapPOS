package com.quicktap.pos.print;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;

import androidx.core.content.FileProvider;

import com.quicktap.pos.类001;
import com.quicktap.pos.data.entity.Bill;
import com.quicktap.pos.data.entity.BillItem;
import com.quicktap.pos.util.类045;
import com.quicktap.pos.util.类046;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;

/** Secure, cache-only HD receipt PNG export and native sharing. */
public final class 类029 {
    public interface Callback { void onReady(Bitmap bitmap); void onError(String message); }
    private 类029() { }

    public static void loadReceipt(Context context, long billId, Callback callback) {
        Context app = context.getApplicationContext();
        类045.io().execute(() -> {
            try {
                Bill bill = 类001.get().repo().billById(billId);
                if (bill == null) throw new IllegalStateException("Receipt not found");
                List<BillItem> items = 类001.get().repo().itemsOf(billId);
                Bitmap bitmap = 类027.render(类046.get(app), bill, items);
                类045.main().post(() -> callback.onReady(bitmap));
            } catch (Throwable e) {
                类045.main().post(() -> callback.onError("Could not load this receipt"));
            }
        });
    }

    public static void share(Context context, long billId, Callback callback) {
        Context app = context.getApplicationContext();
        类045.io().execute(() -> {
            try {
                Bill bill = 类001.get().repo().billById(billId);
                if (bill == null) throw new IllegalStateException("Receipt not found");
                List<BillItem> items = 类001.get().repo().itemsOf(billId);
                Bitmap bitmap = 类027.render(类046.get(app), bill, items);
                File dir = new File(app.getCacheDir(), "shared");
                if (!dir.exists() && !dir.mkdirs()) throw new IllegalStateException("Cache unavailable");
                File[] old = dir.listFiles((d, name) -> name.endsWith(".png"));
                if (old != null) for (File file : old) file.delete();
                File target = new File(dir, "receipt_" + bill.id + ".png");
                try (FileOutputStream out = new FileOutputStream(target)) {
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
                }
                bitmap.recycle();
                Uri uri = FileProvider.getUriForFile(app, app.getPackageName() + ".fileprovider", target);
                Intent send = new Intent(Intent.ACTION_SEND);
                send.setType("image/*");
                send.putExtra(Intent.EXTRA_STREAM, uri);
                send.putExtra(Intent.EXTRA_TEXT, "Receipt " + bill.invoiceNo);
                send.setClipData(ClipData.newRawUri("Receipt", uri));
                send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                类045.main().post(() -> {
                    try {
                        Context launchContext = context;
                        Intent whatsappIntent = new Intent(send);
                        boolean whatsappAvailable = !launchContext.getPackageManager()
                                .queryIntentActivities(whatsappIntent.setPackage("com.whatsapp"), 0).isEmpty();
                        Intent launch = whatsappAvailable
                                ? whatsappIntent
                                : Intent.createChooser(send, "Share receipt");
                        launch.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        if (launchContext instanceof android.app.Activity) {
                            launchContext.startActivity(launch);
                        } else {
                            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            app.startActivity(launch);
                        }
                        if (callback != null) callback.onReady(null);
                    } catch (android.content.ActivityNotFoundException e) {
                        if (callback != null) callback.onError("No app available to share this receipt");
                    } catch (RuntimeException e) {
                        if (callback != null) callback.onError("Could not open sharing apps");
                    }
                });
            } catch (Throwable e) {
                类045.main().post(() -> { if (callback != null) callback.onError("Could not share this receipt"); });
            }
        });
    }
}
