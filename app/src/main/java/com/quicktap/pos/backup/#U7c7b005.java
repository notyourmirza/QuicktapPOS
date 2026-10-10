package com.quicktap.pos.backup;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.quicktap.pos.util.类046;

import java.util.concurrent.TimeUnit;

/**
      * Weekly automatic backup. One copy only: every run uploads a fresh encrypted
     * database snapshot and deletes the previous file only after verification.

 */
public class 类005 extends Worker {

    private static final String UNIQUE_NAME = "quicktap-weekly-backup";

    public 类005(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        if (!类046.get(ctx).isWeeklyBackupEnabled()) return Result.success();
        if (类007.isConfigured(ctx)) {
            return 类007.backupNowBlocking(ctx) ? Result.success() : Result.retry();
        }
        if (!类006.isConnected(ctx)) return Result.success();
        return 类006.backupNowBlocking(ctx) ? Result.success() : Result.retry();
    }

    public static void schedule(Context context) {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                类005.class, 7, TimeUnit.DAYS)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build())
                .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
    }

    public static void cancel(Context context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME);
    }
}
