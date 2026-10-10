# QuickTap POS — Changelog

## 1.1.0 (versionCode 2) — Phase 3: paid Cloud Sync (app side)

Adds an optional, paid **Cloud Sync** add-on to the Android app. Server side
(Phase 2) was already live; this release is the app half of the feature.

### Added

- **Cloud Sync screen** (`Settings › Cloud Sync`)
  - endpoint URL + API key entry, stored encrypted in the Android Keystore
  - **Save & Connect** (calls `GET /v1/auth/verify`)
  - status: Connected / Offline / Invalid key / Not included in your plan /
    Plan expired / Error
  - plan name, user & device usage (`2/3 users`), pending-change count,
    last sync time, this device's name
  - **Sync now**, **Import data from cloud**, **Upload existing data once**,
    **Disconnect**
- **Automatic background sync** (WorkManager, 15-minute periodic + a debounced
  run 10 s after every change)
- **Offline-first queue** in its own database (`quicktap_cloudsync.db`)
- **New-device import wizard** with progress, resumable page by page
- **Sync status entry** on the Settings screen (tap-through to the screen)
- **Bad-clock detection** — warns when the device clock differs from the server
  by more than 5 minutes, because that decides which edit wins

### Synced entities

`categories`, `products`, `bills` (with line items), `customers`, `suppliers`,
`expenses`, `ledger_customers`

### Changed

- `app/build.gradle` — `versionCode 1 → 2`, `versionName "1.0" → "1.1.0"`
- `app/proguard-rules.pro` — keep rules for the new package
- `app/src/main/AndroidManifest.xml` — registered `CloudSyncActivity`
- `app/src/main/res/layout/fragment_settings.xml` — new "Cloud Sync" card
- No new dependencies, no new permissions.

### Behaviour when the feature is off (default)

Nothing changes. No endpoint, no key → no queue row, no worker, no network
call. The app is exactly as offline as it was in 1.0.

---

## 1.0 (versionCode 1)

First release: offline POS, billing, inventory, expenses, reports,
commercial modules, Google Drive / file backup, licence + device binding.
