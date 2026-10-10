package com.quicktap.pos.util;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.quicktap.pos.R;
import com.quicktap.pos.类001;
import com.quicktap.pos.data.entity.Product;

import java.util.List;

/** Background-safe, threshold-crossing low-stock notifications. */
public final class 类052 {
    private static final String CHANNEL_ID = "quicktap_stock_alerts";
    private static final String PREFS = "quicktap_stock_alert_state";
    private static final String KEY_PREFIX = "state_";

    private 类052() { }

    public static void check(Context context) {
        Context app = context.getApplicationContext();
        if (!类046.get(app).isLowStockAlertsEnabled()) return;
        类045.io().execute(() -> checkBlocking(app));
    }

    private static void checkBlocking(Context context) {
        List<Product> products = 类001.get().repo().getAllProductsBlocking();
        android.content.SharedPreferences states = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        for (Product product : products) {
            if (product.stock < 0) continue;
            int state = product.stock == 0 ? 2 : product.stock <= product.minimumStock ? 1 : 0;
            String key = KEY_PREFIX + product.id;
            int previous = states.getInt(key, 0);
            if (state == 0) {
                states.edit().remove(key).apply();
            } else if (state != previous) {
                states.edit().putInt(key, state).apply();
                post(context, product, state);
            }
        }
    }

    private static void post(Context context, Product product, int state) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;
        ensureChannel(context);
        String title = state == 2 ? "Stock Khatam Ho Gaya" : "Stock Khatam Hone Wala Hai";
        String message = state == 2
                ? product.name + " ka stock khatam ho gaya hai. Restock zaroor karein."
                : product.name + " ka stock khatam hone wala hai — sirf " + product.stock
                + " left hain. Restock zaroor karein.";
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_products)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message))
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);
        try { NotificationManagerCompat.from(context).notify((int) (10000 + product.id), builder.build()); }
        catch (SecurityException ignored) { }
    }

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) return;
        manager.createNotificationChannel(new NotificationChannel(
                CHANNEL_ID, "Inventory stock alerts", NotificationManager.IMPORTANCE_DEFAULT));
    }
}
