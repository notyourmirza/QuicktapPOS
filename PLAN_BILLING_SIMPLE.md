# QuickTap POS — Billing-only edition: plan

> **Status: delivered.** Every phase below is implemented in this tree — A (colouring
> removed), B (forced licence page), C (device accounting), D (Drive backup + passphrase),
> E (Cloud Sync deleted), plus the `billing-api/` PHP backend this plan asked for and
> `versionName 1.2.0`. The two exceptions, both deliberate: the Room schema was left at
> v11 untouched (a constraint, not an omission), and `app/google-services.json` stays a
> placeholder until the real OAuth clients exist — see
> [DRIVE_BACKUP_SETUP.md](DRIVE_BACKUP_SETUP.md). The plan text below is kept unchanged
> as the reasoning record, including the parts that were later refined (for example the
> licence screen now also covers the plan store). **Follow-up in 1.3.0:** the same billing
> was opened up to a website and a desktop program through one `X-Api-Token` header
> ([`billing-api/API.md`](billing-api/API.md)); nothing in this plan was undone by it.

---

**Goal (as asked):** a *simple* build where the only remotely-controlled thing that
matters is the **billing / licence system** (forced "expired" page), plus a
**working Google Drive backup** and a **device-id accounting**. The **colouring
feature is removed**. WhatsApp support number stays.

Everything below was verified by reading the actual sources in this repo — no
assumptions. Line/file names are the real ones (Chinese identifier markers kept).

---

## 1. What exists today (facts from the source)

| Area | File(s) | Actual state |
|---|---|---|
| Server theme + splash | `theme/类036` | `GET v1/theme`, `GET v1/splash`. `apply()` at `类036.java:124-135` **hard-codes `类039.LUXE`** and ignores the server's `primary` / `secondary` — so the "server colour" feature is already dead code that still costs 2 HTTP calls, 6 cached prefs and a full `recreate()` per theme change. |
| Runtime repaint | `theme/类037` (499 lines) | `ActivityLifecycleCallbacks` that walks every view tree on `onActivityResumed` and repaints surfaces, chips, cards, gradients. This is the main "colouring" complexity. |
| Presets | `theme/类039` (172 lines) | 10 unused presets; only `LUXE` is reachable. |
| Light/dark | `theme/类038` | Local only, works, keeps `AppCompatDelegate`. Unrelated to server colours. |
| Licence gate | `license/类012`, `类013`, `类014`, `类015` | Server-authoritative. `类012.route()` sends every non-ACTIVE state to `类076` with `NEW_TASK + CLEAR_TASK` (back can't re-enter). 24 h offline grace only for an already-confirmed ACTIVE licence. |
| Forced expired page | `ui/license/类076` | One screen for PENDING / EXPIRED / REVOKED / SUSPENDED / BLOCKED / INVALID / OFFLINE, 60 s auto-refresh, "Check License", "Verify", "Contact" (WhatsApp). Already the right shape. |
| Guard on screens | `ui/license/类075.protect()` | Called by only **5** activities (`ui/类042` does its own check at `类042.java:408-423`, plus `类081`, `类086`, `类091`, `CloudSyncActivity`). |
| **Loophole** | `ui/类041.java:39,82`, `ui/类043.java:66`, `ui/类042.java:338,353,486` | **Demo mode bypasses the licence entirely**: a "Demo" button on the login screen sets `demo_mode=true`; splash and MainActivity then skip `类012`/`类013` completely. An expired shop can stay in the app forever. |
| Device identity | `auth/类003` | `sha256(ANDROID_ID + MANUFACTURER + MODEL + DEVICE)`, cached in prefs (`device_id`), sent as `device_id` on `v1/auth/register` + `login`, and as the `X-Device-Id` header on **every** request (`net/类016.java:100`). Server already receives it on every call — nothing in the app ever *shows* or *limits* by it. |
| Plan store | `ui/plans/类083,类084,类085` | `GET v1/plans`; buy buttons already hidden ("assigned by administrator only"); expiry countdown from `expires_at_ms`. |
| WhatsApp support | `util/类056` + `v1/app/config` | Number published by the server, cached; `signature()` already appends store/user/**device**/licence. → **keeps working, untouched.** |
| Announcements | `ui/notifications/类080,类081,类082` | `GET v1/notifications`, cached. |
| Cloud Sync (paid add-on) | `cloudsync/*` (15 classes) + `ui/cloudsync/CloudSyncActivity` (460 lines) | Push/pull/full, per-customer key in Android Keystore, plan entitlement, last-write-wins. **`CloudSyncActivity` is NOT registered in `AndroidManifest.xml` and there is no Cloud Sync card in `res/layout/fragment_settings.xml`** (verified by grep) — so `CloudSyncIndicator.java:154` starting that activity throws `ActivityNotFoundException`. The feature is half-wired. |
| Google Drive backup | `backup/类006` (173 lines) | GoogleSignIn + `GoogleAuthUtil` `drive.appdata` scope + raw Drive v3 REST (folder, multipart upload, size verify, delete-old, download+AES-GCM decrypt+`PRAGMA integrity_check`). Weekly worker `类005`, local file path `类007`. |
| **Why Drive fails** | `app/google-services.json` | `"oauth_client": []` — **empty**. There is no Android/Web OAuth client id, so GoogleSignIn returns `DEVELOPER_ERROR (10)` (the app even maps that message at `类092.java:422`). Also `app/build.gradle` never applies the `com.google.gms.google-services` plugin, so `google-services.json` is not processed at all and `default_web_client_id` does not exist. Result: Drive backup can never connect on a real device. |
| Backup crypto | `类006.key()` | AES-256 key lives in the **Android Keystore** (`quicktap_pos_backup_key_v1`). A Drive backup therefore cannot be restored after reinstall or on another phone (the key is gone) — it is device-bound by design today. |
| Endpoint routing | `net/类018` | bootstrap `https://aifinzo.pro/api/` → Firebase Remote Config `api_base_url` → cached → fallback. Credentials in `BuildConfig`: `APP_ID app_41f7d123706a`, `API_KEY a94791db…`. Only HTTPS accepted. |
| Build reality | no `gradlew`/wrapper jar, `java`/Android SDK absent in this sandbox | I can edit + statically verify source here; the **APK must be built in Android Studio or CI**. There is also no `.github/` folder despite the README claiming a workflow. |

**Backend is not in this repo.** This checkout is the Android app only; the
endpoints above live on the Hostinger PHP server (`aifinzo.pro`). So "change from
backend" needs a decision — see §5 Q1.

---

## 2. Target shape (simple, one authority)

```
Android app ──HTTPS──> Billing API  (X-App-Id / X-Api-Key + JWT + X-Device-Id)
                          GET  v1/app/config     → support whatsapp, app name, logo,
                                                   receipt template, notifications,
                                                   splash text (NO colours)
                          GET  v1/license/status → license_status, unlocked, confirmed,
                                                   expires_at_ms, days_left,
                                                   device_seat {index, used, limit}
                          POST v1/license/verify|confirm
                          GET  v1/plans          → catalogue + current plan
                          GET  v1/devices        → this shop's device list (id, name,
                                                   app, os, last_seen, status)
                          POST v1/devices/deactivate → free a seat
       └─> Google Drive (drive.appdata) : user's own Google account, REST v3
```

Removed: `v1/theme`, `v1/splash` colour fields, `类037` painter, `类039` presets,
`demo_mode` bypass.

---

## 3. Phase A — remove the colouring (make it simple)

1. **Delete** `theme/类037.java`, `theme/类039.java`.
2. **Rewrite** `theme/类036.java` → a ~50-line `Brand`-style reader:
   `primary/secondary/background/surface/outline/textPrimary/textMuted/chart()`
   return `ContextCompat.getColor(ctx, R.color.brand_primary)` etc. (the XML palette
   `res/values/colors.xml` becomes the single source of truth, day + `values-night`).
   `refresh()` keeps **only** `GET v1/app/config` for: app name, logo,
   `support_whatsapp`, `notifications`, `receipt_template`. No colour is read from
   the network; no prefs write for colours; no `invalidate()`.
3. **Drop the network half:** `v1/theme` and `v1/splash` calls, `refreshSplash*`,
   `applySplash`, `getThemePrimary/Secondary/Key/Version` prefs
   (`util/类046.java:262-276, 308-309`), and the splash colour parsing in
   `ui/类043.renderAndAnimate()` (kept as static branded artwork).
4. **Call sites to touch (11 files, mechanical):** `类001.java:40,67`,
   `auth/类004.java:79`, `sync/类034.java:203`, `ui/类042.java:437-447`
   (`refreshTheme()` + `recreate()` — delete the whole thing), `ui/类043.java:86`,
   `ui/notifications/类081.java:37-38`, `类082.java:57`, `ui/plans/类083.java:68,111,144`,
   `类085.java:264`, `ui/reports/类089.java:196`, `ui/settings/类092.java:153,184`,
   `ui/support/类093.java:32`.
5. **Keep:** `类038` (light/dark/system), all drawables, `@color/brand_*` — visual
   result stays the current orange LUXE look, now coming from XML only.

*Net: −750 lines of theme machinery, −2 HTTP calls per launch, one repaint source.*

## 4. Phase B — billing system with forced expiry

1. **Close the bypass:** delete `demo_mode` (`util/类046.java:156-157`,
   `ui/类041.java:39,63,82-84`, `ui/类043.java:66-70`, `ui/类042.java:338,353,486`).
   Every route goes through `类012`. (If a demo is wanted later, it becomes a
   server-issued time-boxed licence instead of a local flag.)
2. **Uniform gate:** every activity that can mutate or show business data calls
   `类075.protect(this)` (add to `类085` plans, `类079`, `类073`, `类076` skip, etc. —
   today only 5 do).
3. **Hard-stop at expiry, not at banner level:** in `ui/billing` the *Print Receipt*
   / save path consults the cached licence snapshot; if `EXPIRED`/`REVOKED`/
   `BLOCKED`/`SUSPENDED` → route to `类076` instead of saving, so no bill can be
   written between `onResume()` checks.
4. **Fail-closed grace:** keep the 24 h grace only for a server-confirmed ACTIVE
   licence (already the rule in `类012.offlineGraceAllows`), and re-check on
   `onStart` (currently `onResume` only in `类042`).
5. **Expired page polish (`类076`):** server message verbatim, plan name + expiry
   date + days-left countdown reused from `类085`, and a single primary action
   "Renew on WhatsApp" (pre-filled with store, plan, device id — `类056.signature`).
   No path back into the app except "Sign out".
6. **Server side (Q1):** licences table keyed by `license_key` + `plan` +
   `expires_at_ms`; `unlocked` only when ACTIVE and not expired; the API **also**
   rejects `v1/sync/push`, `v1/license/confirm`, etc. with `LICENSE_EXPIRED` so the
   client can't fake it. `v1/app/config` publishes `support_whatsapp`.

## 5. Phase C — device-id accounting ("device id ka hisab")

1. `auth/类003` stays as-is (opaque sha256, already sent in `X-Device-Id`).
2. New **`Settings → Device`** row: device id (first 8 chars + copy button),
   device name, OS, licence plan, days left, last server check — all local +
   cached server data. (Today the id appears only inside the WhatsApp signature.)
3. New screen **`Device access`** (`GET v1/devices`): this shop's bound devices,
   "this device" badge, `last_seen`, per-device status, and **seat usage**
   `1 of 2 devices` driven by the plan (`max_devices`). `POST v1/devices/deactivate`
   to release a seat (e.g. phone lost/replaced) — the server then returns
   `DEVICE_BLOCKED` for that device and `类014` already maps it to `BLOCKED`.
4. Server: `app_devices(app_id, device_hash, device_name, os_version, app_version,
   first_seen_at, last_seen_at, status)`; register binds; `v1/license/status`
   returns `device_seat`; a licence check from an unbound/over-limit device is
   refused (`DEVICE_LIMIT` / `DEVICE_MISMATCH`) instead of silently accepted.

## 6. Phase D — Google Drive backup that actually works

**Fixes in the app**
1. `app/build.gradle`: apply `id 'com.google.gms.google-services'`
   (+ `classpath 'com.google.gms:google-services:4.4.2'` in the root build file) so
   `google-services.json` produces `default_web_client_id`; keep
   `play-services-auth`.
2. `类006.signInClient`: build options from `GoogleSignInOptions.DEFAULT_SIGN_IN` +
   `requestIdToken(default_web_client_id)` + `requestScopes(drive.appdata)` +
   `requestServerAuthCode` **off** (not needed); `GoogleApiAvailability` check stays.
3. Replace `GoogleAuthUtil.getToken` (silent-fail source) with
   `GoogleSignInClient`-held account + `Task<GoogleSignInAccount>` and
   `GoogleAuthUtil` only as fallback; on `IOException` containing
   `UserRecoverableAuthException` → surface the resolution intent in Settings
   instead of a generic "Backup failed" toast. Today **every** failure path prints
   the same generic string (`类006.java:106`), which is why it looked broken.
4. Show the real reason + retry state, `account email`, `last backup`, `file size`,
   and add "Test connection" (`Drive about/query`) so setup is debuggable.
5. **Restore portability:** key material currently lives only in the Android
   Keystore → an after-reinstall restore is impossible. Proposal: keep the Keystore
   key for the automatic weekly copy **and** let the manual "Backup now / Restore"
   accept an optional passphrase (`PBKDF2-HMAC-SHA256` 210 000 iters → AES-256-GCM,
   KDF id stored in the header) so the same file can be restored on a new phone.
6. Keep `PRAGMA integrity_check` + safety copy, keep latest-only single file.

**What you must create (credentials — nothing secret is invented by me)**
* GCP project (can be the existing `quicktap-pos`) → enable **Google Sign-In API**
  and **Google Drive API**; OAuth consent screen (External) with scope
  `https://www.googleapis.com/auth/drive.appdata`.
* **OAuth client ID → Android**: package `com.quicktap.pos` + **SHA-1 of the debug
  keystore AND of your release keystore**.
* **OAuth client ID → Web** (used as the server client id) → goes into the new
  `google-services.json` (`oauth_client[0].client_info.client_id`).
* Send me the regenerated `google-services.json` (it is not a secret) and I put it
  in `app/`. **No API key is required for Drive appDataFolder** — the OAuth token is
  enough; an API key is optional.

## 7. Phase E — remove what is not "normal billing"

Recommendation: delete the whole paid Cloud Sync add-on (15 `cloudsync/*` classes,
`ui/cloudsync/CloudSyncActivity`, `activity_cloud_sync.xml`, `ic_cloud_sync.xml`,
`action_cloud_sync` in `res/menu/top_app_bar.xml`, `CloudSyncHooks` calls in
`data/类009.java` (14), `CloudSyncWorker`/`Gate` schedule lines, `类034.java` routing
branch, proguard keeps) — **or** keep it and just fix the missing manifest
registration + Settings card so it stops crashing. This is Q2 below; I will not
delete 3 000 lines before you confirm.

Not touched: printing, Room schema (v11 — `fallbackToDestructiveMigration()` is the
biggest hazard here, so **no** `AppDatabase` migration in any phase), reports,
expenses/ledger, biometrics, device-owner/kiosk code.

---

## 8. Verification I can do here

No JDK / Android SDK in this sandbox (`java` not found, no `gradlew`), so:
1. Static checks: brace/import/reference greps, no dangling `类0xx` references,
   `R.color` ids exist, manifest registrations exist for every launched activity.
2. A small python script to assert (a) no `v1/theme`/`v1/splash`/`demo_mode`
   references remain, (b) every `startActivity(X.class)` target is in the manifest,
   (c) `res/values/*.xml` parse.
3. Optional: add `.github/workflows/android.yml` (gradle `assembleDebug`) so the APK
   builds on push — the README already promises it and it doesn't exist.
4. `PHASE_BILLING_MANUAL_TESTS.md` — the click-through checklist (expiry, grace,
   device seats, Drive connect/backup/restore on a real device).

## 9. Order of work

A (colouring removal, −750 lines) → B (billing/forced expiry + demo hole) →
C (device accounting) → D (Drive) → E (Cloud Sync decision) → docs + changelog +
version bump `1.1.0 → 1.2.0` (`versionCode 3`).

Each phase is a separate commit, so any one of them can be dropped or reviewed
alone. Estimated ~20 files edited, ~5 deleted, 2 new small screens.

---

## 10. Decisions I need before writing code

1. **Backend:** is the billing API (`aifinzo.pro/api`) something I may write here
   (new `billing-api/` PHP + MySQL for Hostinger, matching the exact contract above),
   or is it owned by another repo and I only ship the Android side + a server patch
   spec? Nothing on the server changes from this repo either way.
2. **Cloud Sync:** delete the paid add-on entirely, or keep it and fix its broken
   wiring?
3. **Drive restore portability:** optional passphrase for cross-device restore
   (recommended), or keep the strictly device-bound keystore key?
4. **Firebase/`net/类018` routing:** keep the remote `api_base_url` indirection, or
   hard-wire the base URL in `BuildConfig` and drop Firebase + the bootstrap call
   (simpler, one less dependency; endpoint change then needs a new APK)?
