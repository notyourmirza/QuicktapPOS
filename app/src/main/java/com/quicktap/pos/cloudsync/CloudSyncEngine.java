package com.quicktap.pos.cloudsync;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The Cloud Sync engine: push local changes, pull remote changes, import a
 * fresh device.
 *
 * Rules this class enforces (from the Phase 3 spec):
 *  - OFFLINE FIRST. Every write is already committed locally before we are
 *    called; a failed sync can never lose or block a sale.
 *  - NO KEY / NOT ENTITLED = NO NETWORK. If the feature is off we return
 *    immediately without opening a socket.
 *  - IDEMPOTENT. Queue rows are marked sent only after the server accepted
 *    them, so a retry can never create a duplicate.
 *  - NO LOOPS. Records that come from the server are written straight through
 *    the DAOs, never through the repository, so the change-capture hook never
 *    fires for them.
 *  - LAST-WRITE-WINS on the originating device's timestamp, exactly like the
 *    server does (CloudController::upsertRecord).
 *
 * All blocking methods must run on a background thread.
 */
public final class CloudSyncEngine {

    public interface Listener {
        void onFinished(boolean ok, String message);
    }

    public interface Progress {
        /** @param done records applied so far, @param total records on the server */
        void onProgress(int done, int total);
    }

    /** How the import wizard should treat records that already exist locally. */
    public enum ImportMode {
        /** Merge: newest change wins. Never deletes local data. (safe default) */
        MERGE,
        /** Only pull records this device has never seen. */
        ONLY_MISSING
    }

    /** Outcome of one sync round trip. */
    public static final class SyncOutcome {
        public final boolean ok;
        public final String message;

        SyncOutcome(boolean ok, String message) {
            this.ok = ok;
            this.message = message == null ? "" : message;
        }
    }

    /** Outcome of "Save & Connect". */
    public static final class ConnectResult {
        public final boolean ok;
        public final CloudSyncState state;
        public final String message;
        public final String planName;
        public final String shopName;
        public final int usersActive;
        public final int maxUsers;
        public final int devicesActive;
        public final int maxDevices;
        public final String planExpiresAt;
        public final boolean cloudSyncEnabled;

        ConnectResult(boolean ok, CloudSyncState state, String message, String planName,
                      String shopName, int usersActive, int maxUsers, int devicesActive,
                      int maxDevices, String planExpiresAt, boolean cloudSyncEnabled) {
            this.ok = ok;
            this.state = state;
            this.message = message;
            this.planName = planName;
            this.shopName = shopName;
            this.usersActive = usersActive;
            this.maxUsers = maxUsers;
            this.devicesActive = devicesActive;
            this.maxDevices = maxDevices;
            this.planExpiresAt = planExpiresAt;
            this.cloudSyncEnabled = cloudSyncEnabled;
        }
    }

    private static final String PUSH = "v1/sync/push";
    private static final String PULL = "v1/sync/pull";
    private static final String FULL = "v1/sync/full";
    private static final String VERIFY = "v1/auth/verify";
    private static final String PLAN = "v1/plan";

    private static final int MAX_PUSH_ROUNDS = 100;
    private static final int MAX_PULL_PAGES = 500;
    private static final long CLOCK_SKEW_LIMIT_MS = 5L * 60L * 1000L;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    /** Exponential backoff target set when the server asks us to slow down. */
    private static volatile long nextAllowedAtMs = 0L;
    private static volatile int backoffStep = 0;

    private CloudSyncEngine() { }

    // ==================================================================
    //  feature flag
    // ==================================================================

    /** True when a key is saved, the endpoint is HTTPS and the plan allows sync. */
    public static boolean isEnabled(Context context) {
        return CloudSyncGate.enabled(context);
    }

    public static boolean isConfigured(Context context) {
        return CloudSyncSecureStore.get(context).isConfigured();
    }

    // ==================================================================
    //  public entry points
    // ==================================================================

    /** Manual / UI triggered sync. Runs on the IO pool and reports on the main thread. */
    public static void syncNow(Context context, Listener listener) {
        com.quicktap.pos.util.类045.io().execute(() -> {
            SyncOutcome outcome = syncOnce(context);
            if (listener != null) {
                com.quicktap.pos.util.类045.main().post(() ->
                        listener.onFinished(outcome.ok, outcome.message));
            }
        });
    }

    /**
     * One full round trip.
     *
     * @return [success] - kept in the boolean[] shape the legacy sync caller
     *         (com.quicktap.pos.sync.类034) already used.
     */
    public static boolean[] syncOnceBlocking(Context context) {
        return new boolean[]{syncOnce(context).ok};
    }

    /**
     * One full round trip with a human readable message.
     * Safe to call from any background thread; never throws.
     */
    public static SyncOutcome syncOnce(Context context) {
        if (!RUNNING.compareAndSet(false, true)) {
            return new SyncOutcome(true, "");
        }
        try {
            if (!isEnabled(context)) {
                return done(context, false, "");
            }
            if (System.currentTimeMillis() < nextAllowedAtMs) {
                return done(context, false, "Waiting for the server, retrying shortly");
            }
            if (!isOnline(context)) {
                CloudSyncStore.get(context).setState(CloudSyncState.OFFLINE);
                return done(context, false, "No internet connection");
            }

            CloudSyncStore store = CloudSyncStore.get(context);
            store.setState(CloudSyncState.CONNECTING);

            boolean pushed = push(context, store);
            boolean pulled = pulled(context, store);

            if (pushed && pulled) {
                backoffStep = 0;
                store.setState(CloudSyncState.CONNECTED);
                store.clearTransientErrors();
                store.setLastSyncAt(System.currentTimeMillis());
                store.prune();
                return done(context, true, "Sync complete");
            }
            String message = store.lastError();
            return done(context, false, message.isEmpty() ? "Sync incomplete" : message);
        } finally {
            RUNNING.set(false);
        }
    }

    private static SyncOutcome done(Context context, boolean ok, String message) {
        CloudSyncStatus.refresh(context);
        return new SyncOutcome(ok, message);
    }

    private static boolean pulled(Context context, CloudSyncStore store) {
        CloudSyncTransport transport = new CloudSyncTransport(context);
        String cursor = store.cursor();
        try {
            for (int page = 0; page < MAX_PULL_PAGES; page++) {
                Map<String, String> query = new HashMap<>();
                query.put("since", cursor);
                query.put("limit", "500");
                query.put("device_uid", store.deviceUid());

                CloudSyncTransport.Result result = transport.get(PULL, query);
                if (!handleTransportResult(store, result, "pull")) {
                    return false;
                }
                checkClockSkew(store, result.data());

                JSONObject data = result.data();
                JSONArray records = data.optJSONArray("records");
                if (records != null) {
                    applyPage(context, store, records, ImportMode.MERGE);
                }

                String next = data.isNull("next_cursor") ? null : data.optString("next_cursor", "");
                boolean hasMore = data.optBoolean("has_more", false);
                if (next != null && !next.isEmpty() && !"null".equals(next)) {
                    store.setCursor(next);
                    cursor = next;
                }
                if (!hasMore || records == null || records.length() == 0) {
                    break;
                }
            }
            return true;
        } catch (CloudSyncTransport.TransportException e) {
            store.setState(e.offline ? CloudSyncState.OFFLINE : CloudSyncState.ERROR);
            store.setLastError(e.getMessage());
            return false;
        } catch (Throwable e) {
            store.setState(CloudSyncState.ERROR);
            store.setLastError("Unexpected error while downloading changes.");
            return false;
        }
    }

    private static boolean push(Context context, CloudSyncStore store) {
        CloudSyncTransport transport = new CloudSyncTransport(context);
        try {
            for (int round = 0; round < MAX_PUSH_ROUNDS; round++) {
                List<SyncQueueRow> batch = store.pending(CloudSyncStore.BATCH_SIZE);
                if (batch.isEmpty()) {
                    return true;
                }

                JSONArray records = new JSONArray();
                List<Long> ids = new ArrayList<>(batch.size());
                for (SyncQueueRow row : batch) {
                    JSONObject record = new JSONObject();
                    try {
                        JSONObject payload = row.payload == null || row.payload.isEmpty()
                                ? new JSONObject() : new JSONObject(row.payload);
                        record.put("id", row.recordUuid);
                        record.put("entity", row.entity);
                        record.put("data", payload);
                        record.put("version", row.version);
                        record.put("is_deleted", CloudSyncCodec.OP_DELETE.equals(row.operation));
                        record.put("updated_at", row.updatedAt);
                    } catch (Exception e) {
                        // Unparsable payload: park the row instead of retrying forever.
                        store.markFailed(java.util.Collections.singletonList(row.id), "bad payload");
                        continue;
                    }
                    records.put(record);
                    ids.add(row.id);
                }
                if (ids.isEmpty()) {
                    continue;
                }

                JSONObject body = new JSONObject();
                body.put("device_uid", store.deviceUid());
                body.put("records", records);

                CloudSyncTransport.Result result = transport.post(PUSH, body);
                if (!handleTransportResult(store, result, "push")) {
                    return false;
                }
                // Marked sent only now: a retry therefore cannot duplicate data.
                store.markSent(ids);
            }
            return true;
        } catch (CloudSyncTransport.TransportException e) {
            store.setState(e.offline ? CloudSyncState.OFFLINE : CloudSyncState.ERROR);
            store.setLastError(e.getMessage());
            return false;
        } catch (Throwable e) {
            store.setState(CloudSyncState.ERROR);
            store.setLastError("Unexpected error while uploading changes.");
            return false;
        }
    }

    // ==================================================================
    //  connect / disconnect
    // ==================================================================

    /**
     * Saves the endpoint + key and calls /v1/auth/verify.
     * Blocking - call from a background thread.
     */
    public static ConnectResult connect(Context context, String endpoint, String apiKey) {
        String cleanEndpoint = endpoint == null ? "" : endpoint.trim();
        String cleanKey = apiKey == null ? "" : apiKey.trim();

        if (!cleanEndpoint.toLowerCase(java.util.Locale.US).startsWith("https://")) {
            return fail("Enter an endpoint that starts with https://");
        }
        if (cleanKey.isEmpty()) {
            return fail("Enter the API key from your customer portal.");
        }

        CloudSyncSecureStore secure = CloudSyncSecureStore.get(context);
        CloudSyncStore store = CloudSyncStore.get(context);
        secure.saveEndpoint(cleanEndpoint);
        secure.saveApiKey(cleanKey);
        store.setState(CloudSyncState.CONNECTING);

        CloudSyncTransport transport = new CloudSyncTransport(context);
        try {
            Map<String, String> query = new HashMap<>();
            query.put("device_uid", store.deviceUid());
            CloudSyncTransport.Result result = transport.get(VERIFY, query);

            if (result.http == 401) {
                store.setState(CloudSyncState.INVALID_KEY);
                store.setLastError("This API key was not accepted. Check it and try again.");
                CloudSyncStatus.refresh(context);
                return new ConnectResult(false, CloudSyncState.INVALID_KEY,
                        "Invalid or revoked API key.", "", "", 0, 0, 0, 0, "", false);
            }
            if (result.http == 402) {
                boolean expired = "PLAN_EXPIRED".equals(result.code());
                CloudSyncState state = expired
                        ? CloudSyncState.PLAN_EXPIRED : CloudSyncState.NOT_IN_PLAN;
                store.setState(state);
                store.setLastError(result.message());
                cachePlan(context, store, result.data());
                CloudSyncStatus.refresh(context);
                return new ConnectResult(false, state,
                        result.message().isEmpty()
                                ? "Cloud Sync is not included in your plan." : result.message(),
                        "", "", 0, 0, 0, 0, "", false);
            }
            if (result.http == 403) {
                store.setState(CloudSyncState.PLAN_EXPIRED);
                store.setLastError(result.message());
                cachePlan(context, store, result.data());
                CloudSyncStatus.refresh(context);
                return new ConnectResult(false, CloudSyncState.PLAN_EXPIRED,
                        result.message().isEmpty() ? "Your account is not active." : result.message(),
                        "", "", 0, 0, 0, 0, "", false);
            }
            if (!result.ok()) {
                store.setState(CloudSyncState.ERROR);
                store.setLastError(result.message().isEmpty()
                        ? "The server rejected the request." : result.message());
                CloudSyncStatus.refresh(context);
                return fail(result.message());
            }

            JSONObject data = result.data();
            cachePlan(context, store, data);
            checkClockSkew(store, data);

            boolean entitled = data.optBoolean("cloud_sync_enabled", false);
            store.setState(entitled ? CloudSyncState.CONNECTED : CloudSyncState.NOT_IN_PLAN);
            store.setLastError("");
            if (!entitled) {
                store.setLastError("Cloud Sync is not included in your plan.");
            }
            CloudSyncStatus.refresh(context);

            JSONObject plan = data.optJSONObject("plan");
            JSONObject usage = data.optJSONObject("usage");
            JSONObject limits = data.optJSONObject("limits");
            JSONObject tenant = data.optJSONObject("tenant");

            return new ConnectResult(
                    entitled,
                    entitled ? CloudSyncState.CONNECTED : CloudSyncState.NOT_IN_PLAN,
                    entitled ? "Connected" : "Cloud Sync is not included in your plan.",
                    plan == null ? "" : plan.optString("name", ""),
                    tenant == null ? "" : tenant.optString("name", ""),
                    usage == null ? 0 : usage.optInt("users_active", 0),
                    limits == null ? 0 : limits.optInt("max_users", 0),
                    usage == null ? 0 : usage.optInt("devices_active", 0),
                    limits == null ? 0 : limits.optInt("max_devices", 0),
                    data.optString("plan_expires_at", ""),
                    entitled);
        } catch (CloudSyncTransport.TransportException e) {
            store.setState(e.offline ? CloudSyncState.OFFLINE : CloudSyncState.ERROR);
            store.setLastError(e.getMessage());
            CloudSyncStatus.refresh(context);
            return fail(e.getMessage());
        } catch (Throwable e) {
            store.setState(CloudSyncState.ERROR);
            store.setLastError("Could not reach the server.");
            CloudSyncStatus.refresh(context);
            return fail("Could not reach the server.");
        }
    }

    private static ConnectResult fail(String message) {
        return new ConnectResult(false, CloudSyncState.ERROR, message,
                "", "", 0, 0, 0, 0, "", false);
    }

    /** Clears the key, the cursor and the queue. Local POS data is untouched. */
    public static void disconnect(Context context) {
        CloudSyncStore store = CloudSyncStore.get(context);
        store.disconnect();
        store.setState(CloudSyncState.DISCONNECTED);
        store.setLastError("");
        backoffStep = 0;
        nextAllowedAtMs = 0L;
        CloudSyncStatus.refresh(context);
    }

    // ==================================================================
    //  import wizard (new device)
    // ==================================================================

    /** Number of records the server already holds for this key (-1 when unknown). */
    public static int serverRecordCount(Context context) {
        CloudSyncTransport transport = new CloudSyncTransport(context);
        CloudSyncStore store = CloudSyncStore.get(context);
        try {
            Map<String, String> query = new HashMap<>();
            query.put("page", "1");
            query.put("page_size", "1");
            query.put("device_uid", store.deviceUid());
            CloudSyncTransport.Result result = transport.get(FULL, query);
            if (!result.ok()) {
                return -1;
            }
            return result.data().optInt("total", 0);
        } catch (Throwable e) {
            return -1;
        }
    }

    /**
     * Paginated first-time import. Resumable: it starts from the page after the
     * one already recorded, so an interrupted import can simply be run again.
     *
     * @return number of records applied (-1 on failure).
     */
    /** Kept for callers that just want the safe default (merge). */
    public static int importFromCloud(Context context, Progress progress) {
        return importFromCloud(context, progress, ImportMode.MERGE);
    }

    /**
     * Paginated first-time import. Resumable: re-running it after an
     * interruption simply continues.
     *
     * Before touching anything, a validated snapshot of the local database is
     * written to the app's private files directory (see {@link #preImportBackup})
     * so a large import can always be undone.
     *
     * @return records applied, or -1 on failure.
     */
    public static int importFromCloud(Context context, Progress progress, ImportMode mode) {
        CloudSyncTransport transport = new CloudSyncTransport(context);
        CloudSyncStore store = CloudSyncStore.get(context);
        int applied = 0;
        try {
            if (CloudSyncCodec.localRecordCount(context) > 0) {
                preImportBackup(context);
            }
            int page = 1;
            int total = 0;
            for (int guard = 0; guard < MAX_PULL_PAGES; guard++) {
                Map<String, String> query = new HashMap<>();
                query.put("page", String.valueOf(page));
                query.put("page_size", "500");
                query.put("device_uid", store.deviceUid());

                CloudSyncTransport.Result result = transport.get(FULL, query);
                if (!handleTransportResult(store, result, "import")) {
                    return -1;
                }
                JSONObject data = result.data();
                total = data.optInt("total", total);
                JSONArray records = data.optJSONArray("records");
                if (records != null) {
                    applied += applyPage(context, store, records,
                            mode == null ? ImportMode.MERGE : mode);
                }
                if (progress != null) {
                    progress.onProgress(applied, total);
                }
                boolean hasMore = data.optBoolean("has_more", false);
                if (!hasMore || records == null || records.length() == 0) {
                    break;
                }
                page = data.optInt("next_page", page + 1);
            }
            store.setImported(true);
            // Everything already on the server is now local; do not push it back.
            store.markQueueClean();
            store.setLastSyncAt(System.currentTimeMillis());
            store.setState(CloudSyncState.CONNECTED);
            store.setLastError("");
            CloudSyncStatus.refresh(context);
            return applied;
        } catch (CloudSyncTransport.TransportException e) {
            store.setState(e.offline ? CloudSyncState.OFFLINE : CloudSyncState.ERROR);
            store.setLastError(e.getMessage());
            CloudSyncStatus.refresh(context);
            return -1;
        } catch (Throwable e) {
            store.setState(CloudSyncState.ERROR);
            store.setLastError("Import failed.");
            CloudSyncStatus.refresh(context);
            return -1;
        }
    }

    /**
     * Applies one page of records inside a single Room transaction, so a crash
     * or a lost connection can never leave the local database half updated.
     *
     * @return number of records that actually changed something.
     */
    private static int applyPage(Context context, CloudSyncStore store,
                                 JSONArray records, ImportMode mode) {
        final int[] changed = new int[]{0};
        final boolean onlyMissing = mode == ImportMode.ONLY_MISSING;
        try {
            AppDatabase.get(context).runInTransaction(() -> {
                for (int i = 0; i < records.length(); i++) {
                    JSONObject record = records.optJSONObject(i);
                    if (record == null) {
                        continue;
                    }
                    if (onlyMissing && CloudSyncCodec.existsLocally(context, store,
                            record.optString("entity", ""), record.optString("id", ""))) {
                        continue;
                    }
                    // Writes go straight to the DAOs: no hook, therefore no loop.
                    if (CloudSyncCodec.apply(context, store, record)) {
                        changed[0]++;
                    }
                }
            });
        } catch (Throwable ignored) {
            // A failed page is retried on the next run; nothing is half written
            // because the whole page was one transaction.
        }
        return changed[0];
    }

    /**
     * Safety copy of the local database before a large import.
     * Keeps the three most recent snapshots in the app's private files dir.
     */
    public static File preImportBackup(Context context) {
        try {
            File snapshot = com.quicktap.pos.util.类047.createValidatedSnapshot(context);
            File dir = new File(context.getFilesDir(), "pre_import_backups");
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            File target = new File(dir, "quicktap_before_import_"
                    + System.currentTimeMillis() + ".db");
            try (java.io.InputStream in = new java.io.FileInputStream(snapshot);
                 java.io.OutputStream out = new java.io.FileOutputStream(target)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            }
            snapshot.delete();

            File[] existing = dir.listFiles();
            if (existing != null && existing.length > 3) {
                java.util.Arrays.sort(existing, (a, b) -> a.getName().compareTo(b.getName()));
                for (int i = 0; i < existing.length - 3; i++) {
                    existing[i].delete();
                }
            }
            return target;
        } catch (Throwable ignored) {
            // A missing safety copy must never block the import itself.
            return null;
        }
    }

    /** Refreshes the cached plan snapshot (GET /v1/plan works even when locked). */
    public static void refreshPlan(Context context) {
        CloudSyncStore store = CloudSyncStore.get(context);
        if (!CloudSyncSecureStore.get(context).isConfigured()) {
            return;
        }
        try {
            CloudSyncTransport.Result result = new CloudSyncTransport(context).get(PLAN, null);
            if (result.ok()) {
                cachePlan(context, store, result.data());
                CloudSyncStatus.refresh(context);
            }
        } catch (Throwable ignored) {
            // a plan refresh is cosmetic - never surface an error for it
        }
    }

    // ==================================================================
    //  internals
    // ==================================================================

    /**
     * Maps an HTTP result onto a Cloud Sync state.
     *
     * @return true when the caller may continue, false when it must stop.
     */
    private static boolean handleTransportResult(CloudSyncStore store,
                                                 CloudSyncTransport.Result result,
                                                 String action) {
        if (result.ok()) {
            return true;
        }
        switch (result.http) {
            case 401:
                store.setState(CloudSyncState.INVALID_KEY);
                store.setLastError("This API key is no longer valid. Sync stopped.");
                return false;
            case 402:
                String code = result.code();
                if ("PLAN_USER_LIMIT".equals(code) || "PLAN_DEVICE_LIMIT".equals(code)) {
                    // A seat/device limit is NOT a Cloud Sync block: syncing keeps
                    // working, we only carry the server's message to the UI.
                    store.setPlanLimitMessage(result.message().isEmpty()
                            ? "Your plan limit has been reached. Please upgrade."
                            : result.message());
                    return false;
                }
                if ("PLAN_EXPIRED".equals(code)) {
                    store.setState(CloudSyncState.PLAN_EXPIRED);
                } else {
                    store.setState(CloudSyncState.NOT_IN_PLAN);
                }
                store.setLastError(result.message().isEmpty()
                        ? "Cloud Sync is not included in your plan." : result.message());
                return false;
            case 403:
                store.setState(CloudSyncState.PLAN_EXPIRED);
                store.setLastError(result.message().isEmpty()
                        ? "Your account is not active." : result.message());
                return false;
            case 413:
                store.setLastError("Batch too large for the server.");
                return false;
            case 429:
                // Exponential backoff: 30s, 60s, 2m, 4m … capped at 15 minutes.
                backoffStep = Math.min(backoffStep + 1, 6);
                long delayMs = 30_000L * (1L << Math.max(0, backoffStep - 1));
                delayMs = Math.min(delayMs, 15 * 60_000L);
                nextAllowedAtMs = System.currentTimeMillis() + delayMs;
                store.setLastError("Server is busy, retrying shortly.");
                return false;
            case 0:
                store.setState(CloudSyncState.OFFLINE);
                store.setLastError("No internet connection.");
                return false;
            default:
                store.setState(CloudSyncState.ERROR);
                store.setLastError(result.message().isEmpty()
                        ? "Server error (" + result.http + ") during " + action + "."
                        : result.message());
                return false;
        }
    }

    private static void cachePlan(Context context, CloudSyncStore store, JSONObject data) {
        if (data == null || data.length() == 0) {
            return;
        }
        try {
            store.setPlan(data.has("plan") ? data : data);
        } catch (Throwable ignored) {
            // the plan snapshot is only used for display
        }
    }

    /**
     * A device with a badly wrong clock will always lose last-write-wins, so we
     * detect it from the server's own timestamp and surface a warning instead
     * of silently dropping data.
     */
    private static void checkClockSkew(CloudSyncStore store, JSONObject data) {
        if (data == null) {
            return;
        }
        long serverMs = data.optLong("server_time_ms", 0L);
        if (serverMs <= 0) {
            return;
        }
        long skew = Math.abs(serverMs - System.currentTimeMillis());
        if (skew > CLOCK_SKEW_LIMIT_MS && !store.clockWarningShown()) {
            store.setClockWarningShown(true);
            store.setLastError("This device's clock differs from the server by "
                    + (skew / 60000L) + " minutes. Please correct the date & time so the "
                    + "newest change always wins.");
        }
    }

    /**
     * The server's own plan-limit message ("" when none).
     * The POS app cannot create users today, so this exists purely so the
     * message is never lost or mislabelled as "not in your plan".
     */
    public static String planLimitMessage(Context context) {
        try {
            return CloudSyncStore.get(context).planLimitMessage();
        } catch (Throwable ignored) {
            return "";
        }
    }

    public static boolean isOnline(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context
                    .getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
                return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
            }
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected();
        } catch (Exception e) {
            return false;
        }
    }
}
