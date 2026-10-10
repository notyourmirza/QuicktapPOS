# QuickTap POS — Changelog

## 1.3.1 (versionCode 5) — the whole business moves to `quickxlab.com`

The billing service, the admin panel and the public site were three things on two hosts. They
are now **one Laravel 12 application** (`quickxlab/`) on one MySQL database: the till's JSON
contract, the counter a shopkeeper sees in a browser, the desktop/website token API and the
marketing site — same rows, same licence rules, one deploy.

### New: `quickxlab/deploy.sh` — the portal on the host in one command

- One command deploys or updates the whole thing over ssh: it reads the host's PHP first (8.2+,
  `pdo_mysql`), **moves** whatever is in `public_html` into `public_html/tem` rather than deleting
  anything, packs the app without `.env`, caches or logs, copies the host's own `.env`,
  `public/apk`, `public/assets` and `storage/logs` back over the new code, runs
  `composer install`, `key:generate` (only if `.env` has no key), `migrate --force`,
  `db:seed --force` (fills holes, never overwrites) and the caches, then verifies from outside —
  `/`, `/api/`, `/admin/login` answering, `/.env` and `/storage/logs` refusing, and a real
  `POST /api/v1/app/bootstrap` using the pair read from the host's own `.env`, which is the call
  that says whether a till will accept this server. `--dry-run` prints the plan; `--check`
  re-verifies an existing deploy and changes nothing; `--with-vendor` covers a host with no
  composer. An interrupted run is repaired by running it again.
- It refuses to guess about the database. `quickxlab.com` did not resolve to the same machine as
  `aifinzo.pro` — where the billing MySQL currently lives (`145.79.25.20`) — and one lookup even
  returned a second address for it, so the doc makes you confirm on the box
  (`mysql -h127.0.0.1 -uquicktap -p -e "select count(*) from quicktap.shops;"`) instead of trusting
  an IP from a chat. So the
  probe is `php artisan db:show` **before** anything is written, and `QUICKXLAB_SETUP.md` opens with
  options A/B/C (run the portal next to the database; reach across via Remote MySQL; or move the
  database) plus the warning that matters most: two live copies of one billing database is the only
  genuinely dangerous choice.

### New: `quickxlab/` (Laravel 12 + MySQL, Blade, no build step)

- **The API is Laravel now.** All 26 `v1/*` endpoints are ported on the same paths and with
  the same envelope (`success, message, data, code, timestamp`) and the same `code` values, so
  an existing APK, `billing-api/web/counter.html` and both client libraries keep working
  against `https://quickxlab.com/api/`. `tools/check_laravel_mirror.py` proves the port loses
  no path: it diffs the Laravel route table against `billing-api/openapi.json`.
- **Same engine, same rules.** `app/Support/Billing.php` carries the licence state machine,
  seat maths, key generation, token hashing, invoice numbering and stock diffing across
  unchanged; `app/Support/Gate.php` resolves either a till's JWT or a shop's `qt_pat_…` token,
  enforces `read`/`write` scopes and keeps a token inside `v1/shop|catalog|bills|reports`.
  Writes still need a live licence, and `X-App-Id`/`X-Api-Key` are still required for the
  app-only endpoints.
- **Admin panel** at `/admin` — dashboard (who expires in 7 days, today's bills and revenue,
  open tickets), shops (search by shop *or* owner username, licence history, extend/lifetime/
  revoke, block/suspend, plan, currency, printed name, owner password reset, per-shop audit),
  devices and seats (free a seat, block a till), licence codes (batch-generate, or attach one
  straight to a shop), plans (price, yearly price, seats, feature lines — the app's store screen
  reads the same rows), API tokens, bills with CSV export, revenue per day, audit log, site
  pages, notices, app settings and APK publishing, and the support queue. It signs in against
  the `admin_users` table the old panel created, so an existing login keeps working.
- **Public site in Blade**, rebuilt from what was live: hero, a live counter that ticks over
  *today's real* platform totals from `sync_records`, packages from the `plans` table, field
  photos, the offline-first hardware/trust section, the referral block, the 3-minute setup
  flow and the support desk — plus `/plans`, `/download` (offers exactly the APK the panel
  uploaded), `/software?slug=…` from editable `site_pages`, `/support` (writes
  `support_tickets`) and `/developers`, whose endpoint table is rendered from the live route
  list so it can never document a route that does not exist.
- **`/account`, the shop owner's own screen**: licence state and days left, seat list with a
  self-service **free seat** for a lost phone (revokes that till's sessions in the same
  request), today's totals, recent bills, the printed receipt name, and create/revoke their own
  API tokens. A shop can read and rename, never extend.
- **Sign-up on the site creates an app account.** `Billing::createShop()` is the one code path
  for `/signup` and `POST v1/auth/register`, so a shop that registered in a browser signs in on
  the phone with those credentials and gets the same PENDING-licence rules.
- **Deploy is `migrate` + `db:seed`, both non-destructive**: every `CREATE TABLE` is guarded by
  `Schema::hasTable`, added columns by `Schema::hasColumn`, and the seeder only fills holes.
  `QUICKXLAB_SETUP.md` is the runbook — including moving the previous site to
  `public_html/tem/` and a rollback that deletes nothing.

### App

- `BOOTSTRAP_BASE_URL` → `https://quickxlab.com/api/`. It is the *last* fallback in `类018`'s
  order (Remote Config → cached last known-good → this constant), so existing installs move by
  publishing `api_base_url` in Remote Config — no new APK required, and `aifinzo.pro/api/`
  stays alive as a signpost on the same database.


## 1.3.0 (versionCode 4) — the same billing from a website and from a PC

The billing system was locked to the Android till. It is now one service the phone, a web
page and a desktop program share: the same records, the same catalogue, the same licence
gate — and no second database to keep in step.

### Server

- **Shop API tokens.** `api_tokens` holds `qt_pat_…` secrets as sha256 hashes with a label,
  scopes (`read`, `write`), optional expiry, `last_used_at` and `revoked_at`. A client sends
  one header — `X-Api-Token` — instead of the app credential pair plus a session.
  Issue, list and revoke them in the panel (**API access**) or from the shell with
  `php billing-api/token.php --shop=3 --label=website --scopes=read,write`.
- **Seven endpoints for external clients** (`auth => 'shop'`, i.e. a till session *or* a
  token): `GET v1/shop`, `GET v1/catalog`, `POST v1/catalog/products`,
  `POST v1/catalog/categories`, `GET|POST v1/bills`, `GET v1/reports/day`,
  `GET v1/reports/range`. The old 19 endpoints for the phone are unchanged.
- **`lib/records.php`** — one store for till and web: the shop's `sync_records` payloads,
  keyed by uuid, last-write-wins, now indexed by business time
  (`occurred_at` / `occurred_ms` + `idx_sync_occurred`). `install.php --force` adds the
  columns a new version needs with guarded `ALTER TABLE … ADD COLUMN` statements, so an
  already-deployed host is upgraded in place and nothing is dropped.
- A bill written over the API has its **totals computed by the server** from its lines
  (`total = subtotal − discount + tax`, discount clamped, `*_percent` accepted instead of
  absolute values), gets an `INV-YYYYMMDD-###` number when the client omits one, is stamped
  `source: "api"`, and moves catalogue stock **by the difference** to what that uuid held
  before — so retrying a flaky request never sells the same items twice, and deleting a line
  returns the units. Refusals a client must handle: `SCOPE_DENIED`, `TOKEN_INVALID`,
  `TOKEN_REVOKED`, `TOKEN_EXPIRED`, `NO_ITEMS`, `BAD_QTY`, `BAD_PRICE`, `BAD_DISCOUNT`.
- **Two rules are enforced in the router, not in the handlers**, so a new endpoint cannot be
  born forgetful: the licence gate (`qt_require_licence`) for every write, and — for tokens
  only — `qt_require_scope` plus `qt_token_may_reach`, which keeps a token inside
  `v1/shop|catalog|bills|reports`. A leaked token therefore cannot extend a licence, free a
  device seat or change settings, and an unpaid shop cannot bill from a browser either.

### App

- `类034.push` sends each bill with `table_no`, `notes`, `customer_code` and `deleted_at`,
  and every line with `product_uuid` (new `SyncDao.productUuidById()`), so a bill that
  started on a till now also decrements stock on the server. **`AppDatabase` stays at v11**:
  no entity, no version, no migration — a new `@Query` only.
- Timestamps travel verbatim (epoch millis or `Y-m-d H:i:s`) because `类034.millis()` already
  reads both, and the server normalises only its own index columns.

### Fixed

- **`lib/schema.php` was never loaded.** `install.php` (and `tests/smoke.php`) call
  `qt_schema_statements()` / `qt_schema_seed()`, which live in that file, so a first
  deploy died with *call to an undefined function* before it created a single table. It is
  required from `lib/support.php` now, and `tools/check_api_contract.py` fails the build if
  any file in `lib/` is ever left unrequired again.

### Docs, clients and tooling

- `billing-api/API.md` — the integration guide (60-second start, error table, CORS, retry
  and stock rules, Python/JS snippets, and the deliberate limits).
- `billing-api/openapi.json` — generated from the PHP route table by
  `tools/build_openapi.py`; `check_api_contract.py` now fails if the file, the route table
  and `API.md` ever disagree.
- `billing-api/client/quicktap-api.py` (stdlib only) and `quicktap-api.js` (browser + Node,
  no dependencies), `billing-api/web/counter.html` (a working one-file bill screen: product
  grid, cart, discount, print, today's summary), `billing-api/deploy.sh`
  (tar-over-ssh upload + schema upgrade + public checks).

### Known limit, on purpose

A bill created from the website lives in the shop's server-side records — visible in the
panel, in `v1/bills` and in the reports, and its stock changes reach the till — but it is
**not** inserted into the phone's own Room ledger, so it does not appear in the app's bill
history or reprint from the phone. Wiring that reverse direction (and the invoice numbering
that goes with it) is a deliberate next step rather than a silent half-measure.

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
