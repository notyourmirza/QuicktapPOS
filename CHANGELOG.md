# QuickTap POS — Changelog

## 1.2.0 (versionCode 3) — billing-only edition

The app is now a **billing system with a server-decided licence**, nothing else is
remotely styled. Two features were deleted, three were added, and the backend the app
talks to is finally real code in this repository instead of a specification.

### Removed

- **Server-controlled colouring.** `theme/类037` (the 499-line view-tree repaint) and
  `theme/类039` (ten unreachable presets) are deleted; `theme/类036` keeps only what is
  genuinely per-shop remote configuration: app name, logo, receipt template, support
  number, sync interval, notices. The two HTTP calls it used to make per theme change,
  the six cached prefs and the full `recreate()` are gone with it, as are the theme
  entries in `util/类046`.
- **The paid Cloud Sync add-on** — 16 classes in `cloudsync/`, `CloudSyncActivity`,
  `activity_cloud_sync.xml`, its settings card and its icon. Its job (multi-till data)
  is covered by the existing "Sync now" path against `v1/sync/push|pull`, which is now
  implemented server-side. `CLOUD_SYNC_USER_GUIDE.md` is deleted; `PHASE3_*` reports stay
  as history with a banner.
- The demo watermark in the three receipt printers and the `Demo` button on the login
  screen, plus the splash demo prefs.

### Added

- **Forced licence page everywhere it matters.** Saving a bill checks
  `类012.maySell()` before it writes, `类062` observes the server state once per screen
  and routes away from it, and every protected screen (billing, products, settings,
  notifications **and the plan store**) runs `类075.protect()` on resume: banner plus
  revalidation. An unknown state fails closed to the licence screen instead of trusting
  the cache, and `DEVICE_LIMIT`/`DEVICE_MISMATCH`/`ACCOUNT_BLOCKED` now reach the blocked
  page with the server's own sentence underneath.
- **Device-id accounting.** `license/DeviceRegistry.java` + `ui/license/
  DeviceAccessActivity.java`: which tills this shop has, when each was last seen, how
  many checks it has made, and *Free this seat* so a replaced phone needs no support
  call. The server enforces the plan's seat (`DEVICE_LIMIT`, 409) on registration,
  sign-in and every cached list.
- **Working Google Drive backup.** `backup/BackupCrypto.java` defines an explicit
  envelope (`QTPB2` + KDF id + salt/iterations + AES-256-GCM) that is readable in both
  directions; `backup/类006.java` was rewritten — the truncating streaming upload is
  gone, failures land in `drive_last_error` and in the Settings status line, a Drive
  consent prompt is actually raised, and the previous copy is deleted only after the new
  upload was verified by size. Optional **passphrase** sealing (PBKDF2-HMAC-SHA256 →
  AES-256-GCM) makes a copy restorable on a new phone; the automatic weekly copy stays
  device-key sealed. Local file backup/restore uses the same format and asks for the same
  optional passphrase. Setup: [`DRIVE_BACKUP_SETUP.md`](DRIVE_BACKUP_SETUP.md).
- **`billing-api/`** — the PHP 8 + MySQL backend the app always assumed: `index.php`
  front controller with the shared envelope, `lib/license.php` as the single licence
  authority, `lib/routes.php` with all nineteen handlers, `lib/schema.php` rendering the
  schema for MySQL or SQLite, `install.php`, an `admin/` panel (shops, seats, codes,
  plans, settings incl. the WhatsApp number, notices, clients, activity), plus
  `tests/smoke.php` and `tests/http-smoke.sh`.
- Two static checks that stand in for a build: `tools/check_sources.py` (layouts,
  manifest, ids, imports, cross-file references, banned leftovers) and
  `tools/check_api_contract.py` (app call sites ↔ route table ↔ handler definitions ↔
  schema tables ↔ config keys).

### Changed

- `app/build.gradle`: `versionCode 2 → 3`, `versionName "1.1.0" → "1.2.0"`, and the
  `com.google.gms.google-services` plugin is applied so `app/google-services.json`
  becomes the Drive/Sign-In configuration. The committed placeholder has
  `"oauth_client": []`, so the app builds and runs today; Drive reports "needs setup"
  until a real client id is present.
- Every source file is now named after the class it declares (`类NNN.java`). The shipped
  tree had `#U7c7bNNN.java` filenames, which no `javac` on any platform accepts — 91
  files renamed, 90 matched to their public class and `net/类019` kept package-private.
- Settings' Drive card reports connection, sealing mode, last/next backup, the last
  error, whether Google is still waiting for consent, and whether this build has an
  OAuth client id at all.
- Docs: `README.md` rewritten for this edition, `LICENSE_API.md` is now the realised
  contract (with the field names the app parses) instead of a three-endpoint sketch.

### Compatibility and upgrade

- **Room is untouched**: `AppDatabase` stays at v11, no entity, no DAO, no migration.
  An existing till keeps its data; nothing in this release can lose it.
- Existing `QTPB1` Drive backups (device-key sealed, no passphrase) still restore.
- Deploying `billing-api/` is required for sign-in and licence checks; a shop that was
  running against another host must be given the new `BOOTSTRAP_BASE_URL` (or
  `api_base_url` from the panel) and the same `APP_ID` / `API_KEY` pair.
- No new runtime permission: Drive goes over HTTPS with the account's own token.

---

## 1.1.0 (versionCode 2) — Phase 3: paid Cloud Sync (app side)

> **Removed in 1.2.0** — see above. Kept here because installs outlive plans.

Adds an optional, paid **Cloud Sync** add-on to the Android app. Server side
(Phase 2) was already live; this release is the app half of the feature.

### Added

- **Cloud Sync screen** (`Settings › Cloud Sync`): endpoint URL + API key entry stored
  encrypted in the Android Keystore, Save & Connect (`GET /v1/auth/verify`), status
  (Connected / Offline / Invalid key / Not included in your plan / Plan expired / Error),
  plan name, user & device usage, pending-change count, last sync time, this device's
  name, Sync now, Import from cloud, Upload existing data once, Disconnect.
- **Automatic background sync** (WorkManager, 15-minute periodic + a debounced run 10 s
  after every change) and an **offline-first queue** in its own database
  (`quicktap_cloudsync.db`).
- **New-device import wizard** with progress, resumable page by page; a **sync status
  entry** on the Settings screen; **bad-clock detection** (a device more than 5 minutes
  off the server is warned about, because that decides which edit wins).

### Synced entities

`categories`, `products`, `bills` (with line items), `customers`, `suppliers`,
`expenses`, `ledger_customers`

### Notes from that release

- `app/build.gradle` — `versionCode 1 → 2`, `versionName "1.0" → "1.1.0"`; proguard keep
  rules, manifest entry for `CloudSyncActivity`, new Settings card. No new dependencies,
  no new permissions.
- Behaviour when the feature was off (the default): nothing changed — no endpoint, no
  key, no queue row, no worker, no network call.

---

## 1.0 (versionCode 1)

First release: offline POS, billing, inventory, expenses, reports, commercial modules,
file backup, licence + device binding.
