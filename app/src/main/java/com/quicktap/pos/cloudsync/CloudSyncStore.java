package com.quicktap.pos.cloudsync;

import android.content.Context;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Everything the Cloud Sync feature persists.
 *
 * This is the only class that talks to {@link CloudSyncDb}. It is safe to call
 * from any background thread; callers must NOT call it from the UI thread.
 */
public final class CloudSyncStore {

    /** A row is retried a few times, then parked until the user asks again. */
    public static final int MAX_ATTEMPTS = 8;
    public static final int BATCH_SIZE = 200;          // server MAX_BATCH_RECORDS
    private static final long SYNCED_ROW_TTL_MS = 7L * 24L * 60L * 60L * 1000L;

    private static final String K_CURSOR = "cursor";
    private static final String K_LAST_SYNC_AT = "last_sync_at";
    private static final String K_STATE = "state";
    private static final String K_ERROR = "last_error";
    private static final String K_PLAN = "plan_json";
    private static final String K_IMPORTED = "imported";
    private static final String K_CLOCK_WARN = "clock_warn";
    private static final String K_PLAN_LIMIT = "plan_limit_msg";

    private final Context context;
    private final CloudSyncDao dao;
    private final CloudSyncSecureStore secure;

    private CloudSyncStore(Context context) {
        this.context = context.getApplicationContext();
        this.dao = CloudSyncDb.get(this.context).dao();
        this.secure = CloudSyncSecureStore.get(this.context);
    }

    public static CloudSyncStore get(Context context) {
        return new CloudSyncStore(context);
    }

    // ---------------------------------------------------------------- device

    public String deviceUid() {
        return secure.deviceUid();
    }

    public String deviceName() {
        return secure.deviceName();
    }

    // ----------------------------------------------------------- uuid mapping

    /**
     * Returns the stable uuid of a local row, creating one on first use.
     * products / categories / bills already own a uuid column; for those the
     * caller passes it in so the two stores never disagree.
     */
    public String uuidFor(String entity, long localId, String knownUuid) {
        if (knownUuid != null && !knownUuid.isEmpty()) {
            // The row already owns a uuid column (products / categories / bills).
            // Do NOT bind it here: Room does not write the generated id back
            // into the entity, so a brand-new row would be bound to id 0 and
            // collide with the next new row. The entity's own column is the
            // single source of truth for these three tables.
            return knownUuid;
        }
        SyncMapRow existing = dao.mapByLocal(entity, localId);
        if (existing != null && existing.uuid != null && !existing.uuid.isEmpty()) {
            return existing.uuid;
        }
        String generated = UUID.randomUUID().toString();
        bind(entity, localId, generated);
        return generated;
    }

    public void bind(String entity, long localId, String uuid) {
        if (entity == null || uuid == null || uuid.isEmpty()) {
            return;
        }
        SyncMapRow row = new SyncMapRow();
        row.entity = entity;
        row.localId = localId;
        row.uuid = uuid;
        row.updatedAt = System.currentTimeMillis();
        row.deleted = false;
        dao.putMap(row);
    }

    public void touchMap(String entity, long localId, long updatedAt, boolean deleted) {
        SyncMapRow row = dao.mapByLocal(entity, localId);
        if (row == null) {
            return;
        }
        row.updatedAt = updatedAt;
        row.deleted = deleted;
        dao.putMap(row);
    }

    /** Local id for an incoming uuid, or 0 when the device has never seen it. */
    public long localIdFor(String entity, String uuid) {
        SyncMapRow row = dao.mapByUuid(entity, uuid);
        return row == null ? 0L : row.localId;
    }

    // ----------------------------------------------------------------- queue

    /**
     * Records one local change. Collapses any older pending row for the same
     * record so a rapid edit sequence uploads once, not five times.
     *
     * NEVER throws: a sync bookkeeping failure must not break a sale.
     */
    public void enqueue(String entity, long localId, String uuid,
                        String operation, String payload, long updatedAt) {
        try {
            if (entity == null || uuid == null || uuid.isEmpty()) {
                return;
            }
            dao.dropPendingFor(uuid);
            SyncQueueRow row = new SyncQueueRow();
            row.entity = entity;
            row.recordUuid = uuid;
            row.localId = localId;
            row.operation = operation == null ? CloudSyncCodec.OP_UPSERT : operation;
            row.payload = payload == null ? "" : payload;
            row.version = 1;
            row.updatedAt = updatedAt > 0 ? updatedAt : System.currentTimeMillis();
            row.createdAt = System.currentTimeMillis();
            row.attempts = 0;
            row.lastError = null;
            row.synced = false;
            row.fromServer = false;
            dao.insertQueue(row);
        } catch (Throwable ignored) {
            // The sale is already committed locally; losing a queue row is bad,
            // losing the sale would be unacceptable.
        }
    }

    public List<SyncQueueRow> pending(int limit) {
        return dao.pending(MAX_ATTEMPTS, limit);
    }

    public int pendingCount() {
        return dao.pendingCount(MAX_ATTEMPTS);
    }

    /** Total pending including rows parked after too many failures. */
    public int pendingCountAll() {
        return dao.pendingCountAll();
    }

    public void markSent(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        dao.markSynced(ids);
    }

    public void markFailed(List<Long> ids, String error) {
        if (ids == null || ids.isEmpty()) {
            return;
        }
        dao.markAttemptFailed(ids, error == null ? "unknown" : error);
    }

    public void retryParked() {
        dao.resetAttempts();
    }

    public void prune() {
        dao.pruneSynced(System.currentTimeMillis() - SYNCED_ROW_TTL_MS);
    }

    /** Marks every unsent row as sent - used right after a clean full import. */
    public void markQueueClean() {
        List<Long> ids = new ArrayList<>();
        for (SyncQueueRow row : dao.pending(MAX_ATTEMPTS, Integer.MAX_VALUE)) {
            ids.add(row.id);
        }
        dao.markSynced(ids);
    }

    // ----------------------------------------------------------------- state

    public String cursor() {
        String value = dao.state(K_CURSOR);
        return value == null ? "0" : value;
    }

    public void setCursor(String cursor) {
        put(K_CURSOR, cursor == null ? "0" : cursor);
    }

    public long lastSyncAt() {
        String value = dao.state(K_LAST_SYNC_AT);
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public void setLastSyncAt(long millis) {
        put(K_LAST_SYNC_AT, String.valueOf(millis));
    }

    public CloudSyncState state() {
        String value = dao.state(K_STATE);
        if (value == null) {
            return CloudSyncState.DISCONNECTED;
        }
        try {
            return CloudSyncState.valueOf(value);
        } catch (IllegalArgumentException e) {
            return CloudSyncState.DISCONNECTED;
        }
    }

    public void setState(CloudSyncState state) {
        put(K_STATE, state == null ? CloudSyncState.DISCONNECTED.name() : state.name());
    }

    public String lastError() {
        String value = dao.state(K_ERROR);
        return value == null ? "" : value;
    }

    public void setLastError(String message) {
        put(K_ERROR, message == null ? "" : message);
    }

    /** Called after a fully successful round trip: nothing is outstanding. */
    public void clearTransientErrors() {
        put(K_ERROR, "");
        put(K_PLAN_LIMIT, "");
    }

    public boolean hasImported() {
        return "1".equals(dao.state(K_IMPORTED));
    }

    public void setImported(boolean value) {
        put(K_IMPORTED, value ? "1" : "0");
    }

    public boolean clockWarningShown() {
        return "1".equals(dao.state(K_CLOCK_WARN));
    }

    public void setClockWarningShown(boolean value) {
        put(K_CLOCK_WARN, value ? "1" : "0");
    }

    /** Cached snapshot of GET /v1/plan so the UI can render without network. */
    public JSONObject plan() {
        String raw = dao.state(K_PLAN);
        if (raw == null || raw.isEmpty()) {
            return new JSONObject();
        }
        try {
            return new JSONObject(raw);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public void setPlan(JSONObject plan) {
        put(K_PLAN, plan == null ? "" : plan.toString());
    }

    /**
     * The server's plan-limit wording (users / devices), verbatim.
     * Cleared automatically whenever a round trip succeeds.
     */
    public String planLimitMessage() {
        String value = dao.state(K_PLAN_LIMIT);
        return value == null ? "" : value;
    }

    public void setPlanLimitMessage(String message) {
        put(K_PLAN_LIMIT, message == null ? "" : message);
    }

    private void put(String key, String value) {
        SyncStateRow row = new SyncStateRow();
        row.stateKey = key;
        row.value = value;
        dao.putState(row);
    }

    // ------------------------------------------------------------ disconnect

    /** Clears keys, cursor and queue. Local POS data is never touched. */
    public void disconnect() {
        try {
            dao.wipeAll();
        } catch (Throwable ignored) {
            // nothing we can do; the key is cleared next and that is what matters
        }
        secure.clear();
    }

    public Context context() {
        return context;
    }
}
