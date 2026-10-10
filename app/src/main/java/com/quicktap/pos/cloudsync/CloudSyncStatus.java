package com.quicktap.pos.cloudsync;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.List;

/**
 * In-memory snapshot of the Cloud Sync feature plus a tiny listener registry,
 * so the status icon, the Settings screen and the Cloud Sync screen all show
 * the same thing without polling the database.
 *
 * Values are mirrored into CloudSyncStore (SQLite) so a restart restores them.
 */
public final class CloudSyncStatus {

    public interface Listener {
        void onChanged(CloudSyncStatus status);
    }

    private static final List<Listener> LISTENERS = new ArrayList<>();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static volatile CloudSyncStatus current = new CloudSyncStatus(
            CloudSyncState.DISCONNECTED, 0, 0L, "", "", "");

    public final CloudSyncState state;
    /** Rows waiting to be uploaded. */
    public final int pending;
    public final long lastSyncAt;
    public final String message;
    public final String planName;
    public final String deviceName;

    private CloudSyncStatus(CloudSyncState state, int pending, long lastSyncAt,
                            String message, String planName, String deviceName) {
        this.state = state;
        this.pending = pending;
        this.lastSyncAt = lastSyncAt;
        this.message = message == null ? "" : message;
        this.planName = planName == null ? "" : planName;
        this.deviceName = deviceName == null ? "" : deviceName;
    }

    public static CloudSyncStatus get() {
        return current;
    }

    public static void addListener(Listener listener) {
        if (listener == null) {
            return;
        }
        synchronized (LISTENERS) {
            if (!LISTENERS.contains(listener)) {
                LISTENERS.add(listener);
            }
        }
    }

    public static void removeListener(Listener listener) {
        synchronized (LISTENERS) {
            LISTENERS.remove(listener);
        }
    }

    public static void publish(CloudSyncStatus status) {
        current = status;
        MAIN.post(() -> {
            synchronized (LISTENERS) {
                for (Listener listener : new ArrayList<>(LISTENERS)) {
                    try {
                        listener.onChanged(status);
                    } catch (Throwable ignored) {
                        // one broken screen must not stop the others updating
                    }
                }
            }
        });
    }

    /** Rebuilds the snapshot from disk. Safe to call from a background thread. */
    public static CloudSyncStatus refresh(Context context) {
        try {
            CloudSyncStore store = CloudSyncStore.get(context);
            String planName = store.plan().optJSONObject("plan") != null
                    ? store.plan().optJSONObject("plan").optString("name", "")
                    : "";
            CloudSyncStatus status = new CloudSyncStatus(
                    store.state(),
                    store.pendingCount(),
                    store.lastSyncAt(),
                    store.lastError(),
                    planName,
                    store.deviceName());
            publish(status);
            return status;
        } catch (Throwable ignored) {
            return current;
        }
    }

    public boolean isConnected() {
        return state == CloudSyncState.CONNECTED;
    }

    /** True when the icon should tell the cashier that something needs action. */
    public boolean needsAttention() {
        return state == CloudSyncState.INVALID_KEY
                || state == CloudSyncState.NOT_IN_PLAN
                || state == CloudSyncState.PLAN_EXPIRED
                || state == CloudSyncState.ERROR;
    }
}
