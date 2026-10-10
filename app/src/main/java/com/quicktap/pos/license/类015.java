package com.quicktap.pos.license;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.quicktap.pos.R;
import com.quicktap.pos.auth.类004;
import com.quicktap.pos.ui.类042;
import com.quicktap.pos.util.类046;
import com.quicktap.pos.util.类056;

import java.net.URLEncoder;
import java.util.concurrent.TimeUnit;

/** Refreshes the server licence status and reminds the shop before expiry. */
public final class 类015 extends Worker {
    private static final String UNIQUE_NAME = "quicktap-license-expiry-reminder";
    private static final String CHANNEL_ID = "license_expiry";
    private static final int NOTIFICATION_ID = 4107;

    public 类015(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        if (!类004.get(context).isSignedIn()) return Result.success();

        类014 state = 类013.statusBlocking(context);
        if (state == null || !state.networkOk || !state.success) return Result.success();

        int daysLeft = state.daysLeft;
        if (daysLeft < 0 && state.expiresAtMs > 0) {
            daysLeft = (int) Math.ceil((state.expiresAtMs - System.currentTimeMillis()) / 86400000d);
        }
        if (!类014.ACTIVE.equals(state.state) && !类014.EXPIRED.equals(state.state)) {
            return Result.success();
        }
        if (类014.EXPIRED.equals(state.state) || daysLeft <= 7) {
            postReminder(context, daysLeft);
        }
        return Result.success();
    }

    private static void postReminder(Context context, int daysLeft) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return;

        类046 prefs = 类046.get(context);
        String bucket = daysLeft < 0 ? "expired" : daysLeft == 0 ? "today" : String.valueOf(Math.min(daysLeft, 7));
        String reminderKey = bucket + ":" + dayKey();
        if (reminderKey.equals(prefs.getLastLicenseReminderKey())) return;
        prefs.setLastLicenseReminderKey(reminderKey);

        ensureChannel(context);
        boolean expired = daysLeft <= 0;
        String title = expired ? "Your plan has expired" : "Your plan will expire soon";
        String message = expired
                ? "Your plan has expired. Renew it fast to keep using QuickTap."
                : "Your plan will expire in " + daysLeft + " day" + (daysLeft == 1 ? "" : "s") + ". Renew it fast.";
        String text = "Hello, my QuickTap plan is " + (expired ? "expired" : "about to expire")
                + ". I want to renew it. Please help me with the renewal.";

        Intent action = whatsappIntent(context, text);
        if (action == null) action = new Intent(context, 类042.class);
        action.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pending = PendingIntent.getActivity(context, NOTIFICATION_ID,
                action, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_bell)
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(message + " Tap to contact support on WhatsApp."))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pending);
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) manager.notify(NOTIFICATION_ID, builder.build());
    }

    private static Intent whatsappIntent(Context context, String message) {
        if (!类056.available(context)) return null;
        try {
            String encoded = URLEncoder.encode(message, "UTF-8");
            return new Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/" + 类056.number(context) + "?text=" + encoded));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Plan expiry reminders", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Renewal reminders for the QuickTap plan");
        manager.createNotificationChannel(channel);
    }

    private static String dayKey() {
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US);
        return format.format(new java.util.Date());
    }

    public static void schedule(Context context) {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                类015.class, 6, TimeUnit.HOURS)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
    }
}
