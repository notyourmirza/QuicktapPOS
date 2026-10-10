package com.quicktap.pos.cloudsync;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.concurrent.TimeUnit;

/**
 * Background Cloud Sync.
 *
 * WorkManager note: the shortest period WorkManager allows for a periodic job
 * is 15 minutes, so that is what the timer uses. Changes made at the till are
 * picked up much faster through {@link #kick(Context)}, which schedules a
 * one-time run with a short delay and REPLACEs the previous one - that is the
 * debounce. Both paths are no-ops when the feature is off.
 */
public class CloudSyncWorker extends Worker {

    public static final String UNIQUE_PERIODIC = "quicktap-cloud-sync-periodic";
    public static final String UNIQUE_ONEOFF = "quicktap-cloud-sync-once";

    /** Debounce: several rapid edits collapse into a single upload. */
    private static final long KICK_DELAY_SECONDS = 10L;

    public CloudSyncWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context context = getApplicationContext();
        if (!CloudSyncEngine.isEnabled(context)) {
            return Result.success();        // feature off: zero work, zero network
        }
        boolean ok = CloudSyncEngine.syncOnceBlocking(context)[0];
        return ok ? Result.success() : Result.retry();
    }

    /** Called when the app starts and whenever the feature is switched on. */
    public static void applySchedule(Context context) {
        try {
            if (!CloudSyncEngine.isEnabled(context)) {
                return;
            }
            PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                    CloudSyncWorker.class, 15, TimeUnit.MINUTES)
                    .setConstraints(new Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build())
                    .build();
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UNIQUE_PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request);
        } catch (Throwable ignored) {
            // WorkManager is optional; manual "Sync now" still works without it.
        }
    }

    public static void cancelSchedule(Context context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_PERIODIC);
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_ONEOFF);
        } catch (Throwable ignored) {
            // nothing to do
        }
    }

    /**
     * Debounced "something changed" nudge. Never throws and never blocks:
     * a cashier must never wait on the network.
     */
    public static void kick(Context context) {
        try {
            if (!CloudSyncEngine.isEnabled(context)) {
                return;
            }
            OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(CloudSyncWorker.class)
                    .setConstraints(new Constraints.Builder()
                            .setRequiredNetworkType(NetworkType.CONNECTED)
                            .build())
                    .setInitialDelay(KICK_DELAY_SECONDS, TimeUnit.SECONDS)
                    .build();
            WorkManager.getInstance(context).enqueueUniqueWork(
                    UNIQUE_ONEOFF, ExistingWorkPolicy.REPLACE, request);
        } catch (Throwable ignored) {
            // sync is best effort - never surface this to the cashier
        }
    }
}
