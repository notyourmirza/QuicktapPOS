# QuickTap POS — billing edition

Offline-first billing and Bluetooth thermal printing for small restaurants, cafés,
bakeries, juice bars, tea stalls and retail shops, with a **server-decided licence**
(expiry really locks the till), **Google Drive backup** and **per-device accounting**.
The same billing engine answers a **website and a desktop program** too — one shop token,
one set of records, the same licence gate ([`billing-api/API.md`](billing-api/API.md)).

This edition is deliberately simple: the paid Cloud Sync add-on and the server-driven
colour/theme system were removed. What remains remotely controllable from the panel is
the billing state, the app name/logo/receipt template, the support WhatsApp number, the
plan catalogue, the sync interval and the notices in the bell.

## Stack

- Java 17, minSdk 21 / targetSdk 34, Material Design 3 (light + dark), ViewBinding
- Room (`AppDatabase` v11) — all business data stays on the device
- MVVM: Activity/Fragment → ViewModel → Repository → DAO
- Bluetooth ESC/POS printing over SPP (58 mm and 80 mm)
- One server for licence + config + billing records:
  [`billing-api/`](billing-api/README.md) (PHP 8 + MySQL), callable from the phone, a
  browser page or any desktop language that speaks HTTPS/JSON
- Google Drive REST v3 into `appDataFolder`, encrypted with the device keystore or a
  passphrase — [setup guide](DRIVE_BACKUP_SETUP.md)
- User-facing strings and resources are English; app-owned class names are Chinese
  identifier markers (`类NNN.java`) and the release build is R8-shrunk
  ([notes](SOURCE_PROTECTION.md))

## Build

Open the repository root in Android Studio and let it sync — there is no `gradlew`
checked in, so either let the IDE provide Gradle (8.7+) or use a locally installed
`gradle assembleDebug`. Before the first build:

1. `app/google-services.json` — replace the placeholder to enable Drive
   ([how](DRIVE_BACKUP_SETUP.md)). The app builds and runs without it; only the Drive
   card reports "needs setup".
2. `app/build.gradle` → `defaultConfig` — the three fields the server contract needs:

   ```groovy
   buildConfigField "String", "BOOTSTRAP_BASE_URL", "\"https://your-domain.com/api/\""
   buildConfigField "String", "APP_ID",  "\"app_41f7d123706a\""
   buildConfigField "String", "API_KEY", "\"…same pair as billing-api/config.php…\""
   ```
3. A signing config for release builds (your keystore, not committed).

## Run the billing server

```bash
cp billing-api/config.example.php billing-api/config.php   # fill in db_* + jwt_secret
php billing-api/install.php                                 # schema + plans + admin login
php billing-api/tests/smoke.php                             # licence state machine checks
```

Deploy the `billing-api` folder to `public_html/api` (or run
`bash billing-api/deploy.sh` for the next version), open `admin/index.php` and set the
support WhatsApp number, the plan prices and — if you want it — the Firebase and
`api_base_url` values. Full instructions, the endpoint table and the security notes are
in [`billing-api/README.md`](billing-api/README.md); the exact JSON the app parses is in
[`LICENSE_API.md`](LICENSE_API.md).

## Which keys and credentials you have to create

Nothing else can be generated for you — these are the accounts and secrets, and which side
needs them:

| What | Where you create it | Needed for |
|---|---|---|
| OAuth client **Android** (package `com.quicktap.pos`, with the debug **and** release SHA-1) | Google Cloud console → APIs & Services → Credentials | Drive backup sign-in |
| OAuth client **Web** (the "Web client ID" that goes into `DRIVE_WEB_CLIENT_ID`) | same screen | Drive backup from the app |
| Regenerated `app/google-services.json` | Cloud console → project settings | the Drive sign-in libraries |
| **Drive API** + **Google Sign-In API** enabled, consent screen with the `drive.appdata` scope | Cloud console | backups; while the app is in "Testing" add each shop's Gmail as a test user |
| *No* API key | — | `appDataFolder` works without one |
| `db_host` / `db_name` / `db_user` / `db_pass` | Hostinger hPanel → MySQL | the billing server |
| `jwt_secret` (`php -r "echo bin2hex(random_bytes(32));"`) | you, into `billing-api/config.php` | signing till sessions |
| `install_key` (any long random string) | you, same file | one-shot installer over HTTPS |
| `app_id` + `app_api_key` | you; the same pair already in `app/build.gradle` (`app_41f7d123706a` / `a94791db…`) | the build credential check |
| panel admin user + password | `php billing-api/install.php --admin-user=… --admin-pass=…` | the admin panel |
| shop API tokens `qt_pat_…` | panel → **API access**, or `php billing-api/token.php` | a website or desktop client |
| Firebase project (optional) | Firebase console | push notifications + Remote Config; the app runs without it |

The billing server needs **no** third-party key of any kind: no payment gateway, no
license-server vendor, no SMS provider. The licence rules are computed from your own MySQL
rows, which is what makes the expiry impossible to patch around on a phone.

## Use it from a website or a desktop program

One header per shop, created in the panel:

```bash
curl -s https://your-domain.com/api/v1/catalog -H "X-Api-Token: qt_pat_…"
```

That is the whole client contract: `v1/shop`, `v1/catalog` (+ `POST` to save products and
categories), `v1/bills` (read and write — totals computed by the server, stock moved by the
difference), `v1/reports/day` and `v1/reports/range`. A read-only token cannot bill, and
every write stops with `LICENSE_EXPIRED` when the shop stops paying, so a web page inherits
the forced-expiry behaviour instead of needing its own.

* [`billing-api/API.md`](billing-api/API.md) — errors, CORS, retry rules, Python/JS snippets
* [`billing-api/openapi.json`](billing-api/openapi.json) — import into Postman or a client generator
* [`billing-api/web/counter.html`](billing-api/web/counter.html) — a working bill screen, one file, no build step
* [`billing-api/client/`](billing-api/client/) — `quicktap-api.py` (stdlib only) and `quicktap-api.js` (browser + Node)

## Package map

```
com.quicktap.pos
├── data/          Room entities, DAOs, database, repository, models
├── license/       session + licence state, the gate every screen obeys, device registry
├── auth/          credentials, sign-in, session storage, device fingerprint
├── net/           HTTP layer (headers, envelope) + endpoint bootstrap
├── theme/         app name / logo / receipt template from the server (no colour control)
├── backup/        encrypted snapshots, Drive transport, weekly WorkManager job
├── sync/          legacy multi-till catalogue sync ("Sync now")
├── print/         ESC/POS byte builder, Bluetooth transport, print jobs
├── device/        printer discovery + connection
├── ui/
│   ├── billing/     home billing screen, product grid, cart, ViewModel
│   ├── dashboard/   today's numbers, recent bills, quick actions
│   ├── products/    product + category management
│   ├── history/     bill history, reprint, delete
│   ├── reports/     today / week / month, sales by product & category, CSV export
│   ├── license/     pending / expired / revoked / blocked pages + device access
│   ├── plans/       the plan catalogue (assigned by the administrator, no in-app payment)
│   └── settings/    store profile, tax, printer, backup/restore, Drive, support number
└── util/          prefs, executors, money/date formatting, CSV
```

## How the gate works

`GET v1/license/status` is the only authority. Every protected screen calls
`ui/license/类075.protect()` on resume, which shows the state banner **and** routes the
user to the forced page when the answer is not `ACTIVE`+`unlocked`+`confirmed`. Saving a
bill checks the same state locally, and the server refuses every write while the licence
is not active — so a patched APK still cannot push data. A till that cannot reach the
server keeps billing for `offline_grace_hours` (24 h by default) using its last
server-confirmed state, then falls back to the licence screen. See
[`PLAN_BILLING_SIMPLE.md`](PLAN_BILLING_SIMPLE.md) for the reasoning behind this shape.

## Device accounting

Each installation has a fingerprint (`auth/类003.id()`: SHA-256 of Android ID +
manufacturer/model/device, cached in prefs). The server records one row per till per shop
with name, Android version, app version, first/last seen and a check count, and enforces
the plan's seat limit. Shopkeeper view: **Settings → Device access**. Administrator view:
**panel → Devices** (free a seat, block a lost till). See `GET v1/devices` in
[`LICENSE_API.md`](LICENSE_API.md).

## Checks in this repository

```bash
python3 tools/check_sources.py       # layouts, manifest, ids, imports, cross-file references
python3 tools/check_api_contract.py  # app call sites vs the PHP route table, schema, config, docs
python3 tools/build_openapi.py --check  # openapi.json still matches the route table
```

Both must exit 0; they exist because the sandbox this project is developed in has no JDK
and no PHP, so they replace a compile step for everything that can be verified
statically. `billing-api/tests/` covers what needs a runtime.

## Notes

- Reports export as CSV, which Excel and Google Sheets open directly. No PDF export.
- Backup: Drive (one rolling encrypted copy + weekly automatic) **and** a
  `.qtpbackup` file via the system file picker. Restore always writes a safety copy of
  the current data first.
- Room schema is frozen at v11 with `fallbackToDestructiveMigration()` — no phase of
  this edition adds a migration or changes the schema, so an existing till upgrades by
  replacing code only.
