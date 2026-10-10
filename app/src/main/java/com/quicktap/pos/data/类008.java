package com.quicktap.pos.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.quicktap.pos.util.类049;

import java.util.concurrent.TimeUnit;

/** Keeps local sales data within the latest rolling month. */
public final class 类008 extends Worker {

    private static final String UNIQUE_NAME = "quicktap-monthly-sales-retention";

    public 类008(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        类009 repository = new 类009(getApplicationContext());
        repository.purgeBillsOlderThan(类049.oneMonthAgo());
        return Result.success();
    }

    public static void schedule(Context context) {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                类008.class, 1, TimeUnit.DAYS)
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, request);
    }
}
