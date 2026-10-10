# Phase 3 — Cloud Sync (app source) — implementation report

**Scope:** app source code only. No APK was built, no keystore or signing
config was created.
**Version bump:** `app/build.gradle` → `versionCode 2`, `versionName "1.1.0"`.

---

## 1. New files (all additive, all in one isolated package)

| File | Why |
|---|---|
| `…/cloudsync/CloudSyncState.java` | Status enum (DISCONNECTED, CONNECTING, CONNECTED, OFFLINE, INVALID_KEY, NOT_IN_PLAN, PLAN_EXPIRED, ERROR) |
| `…/cloudsync/CloudSyncDb.java` | Room database `quicktap_cloudsync.db` (v1) — queue, uuid map, state |
| `…/cloudsync/SyncQueueRow.java` | Pending-change row (entity, uuid, operation, payload, attempts, synced) |
| `…/cloudsync/SyncMapRow.java` | local row id ⇄ server uuid, for tables with no uuid column |
| `…/cloudsync/SyncStateRow.java` | cursor / last-sync / plan snapshot key-value store |
| `…/cloudsync/CloudSyncDao.java` | All SQL for the three tables |
| `…/cloudsync/CloudSyncStore.java` | The only class that talks to the sync DB |
| `…/cloudsync/CloudSyncSecureStore.java` | Android Keystore (AES-256/GCM) storage for the API key |
| `…/cloudsync/CloudSyncTransport.java` | HTTPS client for the customer endpoint |
| `…/cloudsync/CloudSyncCodec.java` | Entity ⇄ JSON record, and apply-records-coming-down |
| `…/cloudsync/CloudSyncEngine.java` | push / pull / verify / import / backoff / error mapping |
| `…/cloudsync/CloudSyncGate.java` | Cached feature flag (10 s TTL) |
| `…/cloudsync/CloudSyncWorker.java` | WorkManager periodic + debounced one-off |
| `…/cloudsync/CloudSyncHooks.java` | The thin hook the repository calls |
| `…/cloudsync/CloudSyncStatus.java` | In-memory snapshot + listener registry for the UI |
| `…/ui/cloudsync/CloudSyncActivity.java` | The Cloud Sync screen |
| `res/layout/activity_cloud_sync.xml` | Its layout |
| `res/drawable/ic_cloud_sync.xml` | Status icon |

---

## 2. Existing files touched — every one, with the reason

| File | Change | Reason |
|---|---|---|
| `data/类009.java` (repository) | 14 one-line hook calls | It is the single place where the app writes data, so it is the only place change capture has to sit. Every call is inside the existing `类045.io()` lambdas and every hook is wrapped in try/catch. |
| `sync/类034.java` | **2 lines added** at the top of `runBlocking` | When Cloud Sync is configured it owns `/v1/sync/*`; otherwise the legacy JWT path runs untouched. (The previous session had replaced the guard with a cloud-only check, which disabled legacy sync for shops without a key — that is reverted.) |
| `类001.java` (Application) | 2 lines in `onCreate` | Schedule the Cloud Sync worker and publish the status on cold start. Both wrapped in try/catch like the neighbouring lines. |
| `ui/settings/类092.java` | +1 import, +1 field, +1 call, +2 methods, `onResume`, listener cleanup in `onDestroyView` | Add the "Cloud Sync" entry and live status summary to the existing Settings screen. |
| `data/dao/BillDao.java` | **+1 query** `updateBill(Bill)` | A bill pulled from another device must replace the local copy. |
| `data/dao/ExpenseDao.java` | **+1 query** `byId(long)` | Look up a row before applying a pulled record. |
| `data/dao/LedgerDao.java` | **+1 query** `byId(long)` | Same. |
| `data/dao/CommercialDao.java` | **+2 queries** `allCustomersBlocking()`, `allSuppliersBlocking()` | Full-table scan for the one-off "Upload existing data". |
| `net/类019.java` | **visibility only**: `static` → `public static` on `socketFactory()` | The Cloud Sync transport reuses the app's TLS socket factory. No behaviour change. |
| `res/layout/fragment_settings.xml` | +1 card | "Cloud Sync" entry above "Backup & Restore". |
| `ui/类042.java` | +1 field, +3 lines in `onCreate`, +2 in `onTopBarItem`, +1 in `onResume`, new `onDestroy` | Attach the toolbar status icon (§3.7). |
| `res/menu/top_app_bar.xml` | +1 item (`action_cloud_sync`, `visible="false"`) | The status icon; hidden until a key is saved. |
| `res/values/strings.xml` | +1 string | Title for the icon. |
| `AndroidManifest.xml` | +1 `<activity>` | Register the new screen. |
| `app/build.gradle` | version bump only | — |
| `app/proguard-rules.pro` | +5 lines | Keep the new package and databinding classes. |

**No file was renamed, moved, deleted or refactored. No Chinese folder or
class name was touched. No Chinese UI string was translated.**

---

## 2b. Spec coverage — every clause of the Phase 3 prompt

| Clause | Status | Notes |
|---|---|---|
| 3.0 Discovery report | ✅ | `PHASE3_DISCOVERY.md` |
| 3.1 sync queue table | ✅ | `cs_queue` |
| 3.1 sync state / KV store (cursor, device_uid, last_sync_at, connected) | ✅ | `cs_state` |
| 3.1 uuid / updated_at / version / is_deleted + backfill | ✅ (alternative) | Instead of altering `quicktap.db` (which would risk a destructive migration) the uuid for tables without one lives in `cs_map`. Functionally identical, zero risk. |
| 3.1 soft delete preferred | ✅ | products/categories soft; the rest mirror the app's existing semantics |
| 3.1 stable device_uid | ✅ | `dev-<random>`, generated once |
| 3.1 schema version bumped so the migration runs | ✅ | new DB at version 1 (see §3) |
| 3.2 endpoint + key + Save & Connect | ✅ | |
| 3.2 all six statuses | ✅ | Connected / Offline / Invalid key / Not in plan / Plan expired / Error |
| 3.2 secure storage, never logged | ✅ | Android Keystore, AES-256/GCM |
| 3.2 URL must be https, trimmed | ✅ | validated client- and transport-side |
| 3.2 plan name, last sync, pending count, device name | ✅ | |
| 3.2 Sync now + Disconnect | ✅ | Disconnect clears the key only; local data untouched |
| 3.2 locked state + upgrade message when unpaid | ✅ | |
| 3.3 hook at the single write layer, inert when off, try/catch | ✅ | 14 calls in `类009` |
| 3.4 offline-first, push 200 then pull with cursor | ✅ | |
| 3.4 runs on start / after change / timer / Sync now / network return | ✅ | WorkManager `NetworkType.CONNECTED` covers "network returns" |
| 3.4 exponential backoff | ✅ | 30 s → 15 min on 429; `Result.retry()` otherwise |
| 3.4 401 / 402 / 403 / 429 / timeout / no-internet | ✅ | |
| 3.4 mark sent only after a server success | ✅ | |
| 3.4 LWW by `updated_at`/`version`, never overwrite newer local | ✅ | on `clientUpdatedAt`, mirroring the server's own comparison |
| 3.4 apply in a transaction | ✅ | each page applied inside `RoomDatabase.runInTransaction` |
| 3.4 no hook for server-sourced records | ✅ | the applier writes straight to the DAOs |
| 3.4 never block the cashier UI | ✅ | `类045.io()` + WorkManager |
| 3.4 bad device clock handling | ✅ | warns when > 5 min from `server_time_ms` |
| 3.5 ask "Import data from cloud?" on first connect when the cloud has data | ✅ | |
| 3.5 paginated `/sync/full`, progress, resumable | ✅ | |
| 3.5 warn when local data exists: merge / import-only-missing / cancel, safest default | ✅ | three-button dialog; merge is the positive action |
| 3.5 automatic local backup before a large import | ✅ | `类047.createValidatedSnapshot` → app-private `files/pre_import_backups/`, last 3 kept |
| 3.5 switch to normal push/pull afterwards | ✅ | queue marked clean, cursor set, `imported` flag set |
| 3.6 plan-limit message shown nicely, no crash on 4xx | ✅ | `PLAN_USER_LIMIT` / `PLAN_DEVICE_LIMIT` are *not* treated as a sync block — state stays CONNECTED and the server's own wording is shown. Note: **the app cannot create POS users** (no such screen), so this is belt-and-braces. |
| 3.7 small status icon in the main UI, tap opens the screen | ✅ | toolbar item, hidden until a key is saved |
| 3.8 free/no-key = zero network, no visible change | ✅ | flag cached for 10 s; hooks return immediately |
| 3.9 Firebase key flow untouched | ✅ | `license/*`, `auth/*`, `net/类018` not modified |
| 3.10 dependencies, permissions, ProGuard, version bump, build steps | ✅ | 0 new deps, 0 new permissions |
| 3.11 tests / manual script + results of quick checks | ✅ | `PHASE3_MANUAL_TESTS.md` |
| Deliverables: file list, migration+rollback, deps, build steps, tests, CHANGELOG, user guide | ✅ | |

## 3. Local database — migration & rollback

### What I did NOT do

`AppDatabase` (`quicktap.db`, version 11) is **not changed at all**. It is
built with `.fallbackToDestructiveMigration()`, so any schema mismatch there
would wipe the shop's sales history. Phase 3 therefore keeps everything in a
**separate database file**:

```
/data/data/com.quicktap.pos/databases/quicktap_cloudsync.db
```

### New schema (Room version 1)

```sql
CREATE TABLE cs_queue (
  id INTEGER PRIMARY KEY AUTOINCREMENT, entity TEXT, recordUuid TEXT,
  localId INTEGER, operation TEXT, payload TEXT, version INTEGER,
  updatedAt INTEGER, createdAt INTEGER, attempts INTEGER,
  lastError TEXT, synced INTEGER, fromServer INTEGER);
CREATE INDEX … ON cs_queue(entity), cs_queue(synced), cs_queue(recordUuid);

CREATE TABLE cs_map (
  entity TEXT, localId INTEGER, uuid TEXT, updatedAt INTEGER, deleted INTEGER,
  PRIMARY KEY(entity, localId));
CREATE INDEX … ON cs_map(entity, uuid);

CREATE TABLE cs_state (stateKey TEXT PRIMARY KEY, value TEXT);
```

### Migration

There is nothing to migrate: the file did not exist before, so Room creates it
at version 1 on first launch. Existing `quicktap.db` rows are untouched.

### Rollback

Deleting the three tables (or the whole file) removes the feature's state
without touching POS data:

```sql
DROP TABLE IF EXISTS cs_queue;
DROP TABLE IF EXISTS cs_map;
DROP TABLE IF EXISTS cs_state;
```

`Disconnect` in the UI does exactly this, plus clears the stored key.

### Backfill

Rows created *before* Cloud Sync was switched on were never captured by the
hook. They are uploaded by the explicit **"Upload existing data once"**
button (`CloudSyncHooks.enqueueEverything`), or offered automatically right
after connecting to an empty cloud account. It is never triggered silently.

---

## 4. Dependencies, permissions, ProGuard

- **New dependencies: NONE.**
  - HTTP: `HttpsURLConnection` (framework)
  - JSON: `org.json` (framework)
  - DB: `androidx.room` (already present)
  - Background work: `androidx.work` (already present)
  - Secure storage: Android Keystore + `javax.crypto` (framework)
- **New permissions: NONE.** `INTERNET` and `ACCESS_NETWORK_STATE` were
  already declared. `usesCleartextTraffic="false"` is unchanged — the endpoint
  is validated to start with `https://` before any call.
- **ProGuard/R8:** added
  `-keep class com.quicktap.pos.cloudsync.** { *; }`,
  `-keep class com.quicktap.pos.ui.cloudsync.** { *; }`,
  `-keep class com.quicktap.pos.databinding.** { *; }`.

---

## 5. Build instructions

Toolchain read from the project:

| Requirement | Version |
|---|---|
| Android Gradle Plugin | 8.5.2 |
| Gradle wrapper | in `gradle/wrapper/gradle-wrapper.properties` |
| JDK | **17** (`sourceCompatibility`/`targetCompatibility` 17) |
| `compileSdk` | 34 |
| `minSdk` / `targetSdk` | 21 / 34 |
| Android SDK Build-Tools | 34.x |
| Node / Flutter | not used |

```bash
cd quicktap-android-cn
./gradlew clean
./gradlew :app:assembleRelease      # produces app/build/outputs/apk/release/
# or a debug build first to smoke-test:
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

Sign with your existing keystore — **nothing about signing was changed**.

---

## 6. Static checks run (no APK)

| Check | Result |
|---|---|
| `javac` parse of every new + modified file | **0 syntax errors** (remaining errors are only unresolved `android.*` / `androidx.*` symbols — no Android SDK in this sandbox) |
| DAO method resolution — every `…Dao().method()` call in the new code checked against the real DAO sources | **all resolve** |
| View-binding ids used by `CloudSyncActivity` vs `activity_cloud_sync.xml` | **all present** |
| New ids used by `类092` vs `fragment_settings.xml` | **present** |
| XML entity sanity (`&rsaquo;` removed) | fixed |
| Menu item / drawable / string referenced by `CloudSyncIndicator` | all present |
| `javac` re-run after the second pass (import modes, transactions, icon, plan limits) | **0 syntax errors** |

**Not run:** a full Gradle compile and the Room annotation processor. The
sandbox has no Android SDK, so SQL strings and parameter binding in
`CloudSyncDao` were reviewed by hand rather than validated by Room. If Room
complains, it will be about syntax inside a `@Query` string, not about app
logic.

---

## 7. Deviations from the Phase 3 prompt

1. **`/v1/sync/push` max batch is 200 records**, not "about 200" — the server
   hard-fails with `413 BATCH_TOO_LARGE` above that. `CloudSyncStore.BATCH_SIZE`
   is pinned to 200 and the engine loops.
2. **Every request must also carry `X-App-Id` / `X-Api-Key`.** `api/index.php`
   runs `Auth::requireApiClient()` *before* the router, so a request with only
   the customer `Authorization: Bearer` header would get `401 NO_API_KEY`.
   `CloudSyncTransport` sends both.
3. **`GET /v1/auth/verify` returns `cloud_sync_enabled`, `plan`, `limits`,
   `usage`, `tenant`, `license`, `server_time_ms`** — the prompt's shape was
   close but not exact; the app parses what the server really returns.
4. **WorkManager cannot run every 30–60 s** (15-minute minimum for periodic
   work). Implemented as: 15-minute periodic + a debounced one-off 10 s after
   each change, plus a run on app start, on "Sync now", and whenever the
   network constraint is satisfied.
5. **The POS database was not migrated.** A separate sync database gives the
   same result with zero risk to `quicktap.db` (see §3).
6. **`类019.socketFactory()` was made `public`** so the new transport can
   reuse the app's TLS setup.
7. **The previous session's `CloudSyncGate`-only guard in `类034` was reverted**
   — it stopped the legacy JWT sync for shops that have no Cloud Sync key.
