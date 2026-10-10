package com.quicktap.pos.cloudsync;

import android.content.Context;

/**
 * Feature flag for Cloud Sync.
 *
 * The flag is cached for a short time because the repository calls it on every
 * single write (a sale touches one bill plus several products). Reading two
 * small tables on each write would be wasteful on a slow till.
 *
 * When this returns false the whole feature is inert: no network call, no
 * queue row, no worker - the app behaves exactly as it did before Phase 3.
 */
public final class CloudSyncGate {

    private static final long CACHE_TTL_MS = 10_000L;

    private static volatile Boolean cached;
    private static volatile long cachedAtMs = 0L;

    private CloudSyncGate() { }

    public static synchronized boolean enabled(Context context) {
        long now = System.currentTimeMillis();
        if (cached != null && now - cachedAtMs < CACHE_TTL_MS) {
            return cached;
        }
        boolean value = compute(context);
        cached = value;
        cachedAtMs = now;
        return value;
    }

    /** Call after connect / disconnect so the next check is immediate. */
    public static synchronized void invalidate() {
        cached = null;
        cachedAtMs = 0L;
    }

    private static boolean compute(Context context) {
        try {
            CloudSyncSecureStore secure = CloudSyncSecureStore.get(context);
            if (!secure.isConfigured()) {
                return false;
            }
            return CloudSyncStore.get(context).state().isUsable();
        } catch (Throwable ignored) {
            // A broken keystore or database must degrade to "feature off",
            // never to a crash in the middle of a sale.
            return false;
        }
    }
}
