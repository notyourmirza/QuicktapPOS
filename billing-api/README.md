# QuickTap billing API

The server the Android app depends on: licence state, device seats, plan prices, the
support number, push/bootstrap configuration, notification notices and the legacy
two-way catalogue sync — **plus the same billing for a website and a desktop
program**, through one header. Plain PHP 8 + MySQL, no framework, no Composer, no
build step — upload the folder and it runs on ordinary shared hosting (Hostinger,
cPanel, Plesk, a 2 GB VPS, anything with PHP + PDO).

Everything the app needs is decided **here**, never on the phone: a till with a
patched APK still gets `EXPIRED` from `/v1/license/status` and a `403` from every
write endpoint.

```
billing-api/
├── index.php          front controller: envelope, routing, error swallowing
├── install.php        one-shot installer (CLI or browser) — delete after use
├── token.php          issue / list / revoke API tokens from the shell (CLI only)
├── deploy.sh          tar-over-ssh update: files + schema ALTERs + public checks
├── config.example.php copy to config.php; the only file with secrets
├── API.md             the integration guide for a website or a desktop program
├── openapi.json       generated from the route table (Postman, Swagger, codegen)
├── .htaccess          rewrite to index.php, blocks lib/ + config.php + token.php
├── lib/
│   ├── support.php    config, PDO, envelope, route table, auth (app | session | token), helpers
│   ├── license.php    THE licence + device-seat authority (the state machine)
│   ├── access.php     shop API tokens: issue, list, revoke, the copy-paste snippet
│   ├── routes.php     one thin handler per endpoint the phone calls
│   ├── records.php    the shared record store + the endpoints web/desktop clients call
│   └── schema.php     the whole schema, rendered per driver (mysql | sqlite)
├── admin/
│   ├── index.php      panel login + all actions
│   └── views.php      the panel's HTML
├── client/
│   ├── quicktap-api.py  stdlib-only Python client (a kiosk, a desktop program)
│   └── quicktap-api.js  browser + Node client (no dependencies)
├── web/counter.html   a working bill screen in one file — copy it or ship it
├── tests/
│   ├── smoke.php      state machine + seats + JWT + tokens + bills, on throw-away SQLite
│   └── http-smoke.sh  hits every endpoint over real HTTP with PHP's own server
└── storage/           only for the SQLite test database
```
`../tools/build_openapi.py` regenerates `openapi.json` from the PHP route table, and
`../tools/check_api_contract.py` fails when the two drift apart.

## Deploy (Hostinger, ~5 minutes)

1. **hPanel → MySQL** → create a database, a user, a password. Note all four values
   (host is usually `localhost`).
2. Upload this folder to `public_html/api` (FTP or the file manager). The app's
   `BOOTSTRAP_BASE_URL` must point at that path: `https://your-domain.com/api/`.
3. `cp config.example.php config.php` and fill in `db_*`, a fresh `jwt_secret`
   (`php -r "echo bin2hex(random_bytes(32));"`), the same `app_id` / `app_api_key`
   pair that is in `app/build.gradle`, and a random `install_key`.
4. Run the installer once:

   ```bash
   php install.php --admin-user=you --admin-pass='a-long-password'
   # or from a browser: https://your-domain.com/api/install.php?key=<install_key>
   ```

   It creates the tables, seeds the three plans, and prints the `APP_ID` / `API_KEY`
   pair to paste into `app/build.gradle` if you have no `api_clients` row yet.
   `php install.php --print` prints the raw DDL when you would rather paste it into
   phpMyAdmin.
5. **Delete `install.php`.** Open `https://your-domain.com/api/` — a JSON list of the
   endpoints means it is live. `admin/index.php` is the panel.
6. Apache with `AllowOverride All` is assumed (that is what `.htaccess` needs). On
   nginx/LiteSpeed with rewrites disabled, point every request at `index.php` and keep
   `lib/` + `config.php` outside the document root.

Nothing else has to be installed. `mod_rewrite` is optional if you are willing to use
`/api/index.php/v1/...` — but the app builds `/v1/...` onto the base URL, so keep the
rewrite.

### Updating an installation you already deployed

```bash
QT_HOST='u12345672@123.45.67.89' QT_SSH_PORT=65002 QT_DIR='public_html/api' \
BASE_URL='https://your-domain.com/api' bash billing-api/deploy.sh
```

It uploads only the files the API needs (never `config.php`, never the test database),
re-runs `install.php --force` — which adds any column a new version introduced and never
drops or rewrites a table — and then checks in public that `config.php` answers 403 and
the API answers JSON. Delete `install.php` on the server once.

## One billing system, three clients

The phone, a web page and a desktop program read and write **the same records**: the
catalogue and the bills live in `sync_records` as one payload per row, keyed by uuid, with
last-write-wins — the table the multi-till sync already used. Nothing is duplicated, so a
price changed on the website is the price the till pulls, and a bill written at the
counter shows in the panel without a second code path.

* **A website or a desktop program** sends one header, `X-Api-Token: qt_pat_…`, created per
  shop in the panel (**API access**) or with `php token.php --shop=3 --label=website
  --scopes=read,write`. It may then call `v1/shop`, `v1/catalog`, `v1/bills`,
  `v1/reports/day`, `v1/reports/range` — and nothing else, so a leaked token cannot touch
  the licence, the device seats or the settings.
* `POST v1/bills` computes subtotal/discount/tax **server side** and stamps `source: "api"`,
  and moves catalogue stock by the *difference* to what that uuid held before — a retry
  sells nothing twice. Writes stop with `LICENSE_EXPIRED` exactly as they do on a phone,
  which is why the forced-expiry rule needs no new work in a client.
* [API.md](API.md) is the guide (errors, CORS, snippets), [openapi.json](openapi.json) the
  machine-readable description, [client/](client/) two ready clients,
  [web/counter.html](web/counter.html) a working bill screen.

## What the app must be built with

`app/build.gradle` → `android.defaultConfig`:

```groovy
buildConfigField "String", "BOOTSTRAP_BASE_URL", "\"https://your-domain.com/api/\""
buildConfigField "String", "APP_ID",  "\"app_41f7d123706a\""
buildConfigField "String", "API_KEY", "\"a94791db…\""
```

* `BOOTSTRAP_BASE_URL` is the only hard-coded address in the APK. It is fetched once
  (`GET v1/app/bootstrap`) and may be replaced from the panel (`api_base_url`), which
  is how you move the API to another host without shipping a new APK.
* `APP_ID` / `API_KEY` are sent as `X-App-Id` / `X-Api-Key` on every request.
  `qt_find_client()` accepts the pair either from the `api_clients` table (so several
  builds can be live at once, and one can be switched off) or from `config.php`.
* HTTPS is not optional in practice: `类018` refuses non-`https` base URLs and the
  OAuth flow behind Drive requires it.

## Endpoints

Twenty-six: the nineteen the app calls, plus seven for a website or a desktop
program. `../../tools/check_api_contract.py` fails the build if the phone's call sites
drift from the table, if `openapi.json` is stale, or if an endpoint a token can reach is
missing from `API.md`. `GET /` prints the live list. The full contract for the phone —
every field name the app parses — is [../LICENSE_API.md](../LICENSE_API.md);
[API.md](API.md) covers the rest.

| Endpoint | Auth | Why |
|---|---|---|
| `GET v1/app/bootstrap` | app | Firebase options + which API base to use next |
| `GET v1/app/config` | app | app name, logo, receipt template, **support WhatsApp**, sync minutes, notices |
| `POST v1/auth/register` | app | shop + owner + `PENDING` licence + device binding |
| `POST v1/auth/login` | app | password + device check, returns session and licence state |
| `POST v1/auth/refresh`, `POST v1/auth/logout` | user | token life |
| `GET v1/auth/me` | user | cached profile (role, shop, fingerprint flag) |
| `POST v1/auth/unlock` | user | auto-lock screen verifies against the server |
| `POST v1/auth/fingerprint` | user | remembers the biometric choice |
| `GET v1/license/status` | user | **the answer the whole app is built around** |
| `POST v1/license/verify` | user | redeems a licence key / activation code |
| `POST v1/license/confirm` | user | the "this is really me" step after activation |
| `GET v1/plans` | app | the in-app store catalogue + what this shop has |
| `GET v1/devices`, `POST v1/devices/deactivate` | user | device accounting: which tills hold a seat |
| `POST v1/settings/app-name` | user | a shop overriding the app name |
| `GET v1/notifications` | user | the bell |
| `POST v1/sync/push`, `GET v1/sync/pull` | user + **active licence** | multi-till catalogue + bill sync |
| `GET v1/shop` | token or session | branding, currency, licence state, this token's scopes |
| `GET v1/catalog` | token or session | the same categories/products the till pushes |
| `POST v1/catalog/products`, `POST v1/catalog/categories` | token (write) or session | create/update one or a batch |
| `GET v1/bills` | token or session | paged, filtered by date and free-text search |
| `POST v1/bills` | token (write) or session + **active licence** | write one bill; totals computed here |
| `GET v1/reports/day`, `GET v1/reports/range` | token or session | the numbers for a dashboard |

## The rules, in one paragraph each

**Licence.** `qt_license_state()` (lib/license.php) reads the shop, its newest
`licences` row and the calling user, and returns `ACTIVE` / `PENDING` / `EXPIRED` /
`REVOKED` / `SUSPENDED` / `BLOCKED` / `UNKNOWN`. Order matters: an admin block beats a
live licence, and the **server clock** decides expiry — a till with a wrong date cannot
bill another month. `expires_at = 0` means lifetime. The app fails closed: an unknown
state routes to the licence screen, not to the register.

**Device accounting.** `X-Device-Id` is a SHA-256 fingerprint of the installation
(Android ID + build info), stored per shop in `app_devices`. Every call refreshes
`last_seen_at` and a counter, which is the "hisab": `GET v1/devices` shows model,
Android version, app version, first/last seen, and the plan's `used/limit` seat. A new
device may only register while the plan has a free seat (`DEVICE_LIMIT`, 409), a
device bound to another shop is refused (`DEVICE_MISMATCH`), and releasing a seat —
from the app or the panel — revokes that device's sessions immediately.

**Write gate.** The route table marks `v1/sync/push` and `v1/sync/pull` as
licence-required, so `qt_require_licence()` runs before either handler: without an
`ACTIVE` licence the server refuses to store anything. Both handlers also call
`qt_touch_device()`, which is what keeps the seat list honest. An expired till can keep
printing receipts offline for `offline_grace_hours` (its own copy of the state) but can
never push data into the shop's history. The same gate covers `POST v1/bills` and the
catalogue writes, so no client can bill a shop that stopped paying.

**Tokens.** `X-Api-Token` is checked by `qt_token_access()`: the sha256 hash is looked up,
revoked and expired rows are refused, the shop behind it must not be blocked, and its
licence is read the same way a till's is. `qt_require_scope()` refuses a write with a
read-only token, and `qt_token_may_reach()` — both enforced in `qt_route()`, not in the
handlers — keep a token inside the shop's records. One place decides, so a new endpoint
cannot be born forgetful.

## The panel (`admin/`)

Login is the pair `install.php` created. Sessions are PHP sessions with a CSRF token on
every form; the panel is one file plus a view file so it survives a `chmod 644` host.

* **Shops** — every billing account with plan, licence pill, days left, till count, and
  the four actions that matter: extend N days, lifetime, revoke, block. `Block` is what
  a shop cannot get around: every endpoint answers `BLOCKED`, and the app shows the
  forced page.
* **Devices** — every till the server has ever seen; *Free seat* is the lost-phone fix
  and needs no support call.
* **Licence codes** — generate `QT-XXXXX-XXXXX-XXXXX-XXXXX` codes for N days, shown
  once, redeemed in the app under *Licence → Enter key*, and recorded as used.
* **Plans** — price, yearly price, device seats, feature lines, active flag. The
  in-app store renders these rows, so a price change is not a code change.
* **App settings** — app name, logo, receipt template/footer, tax, auto-lock,
  **support WhatsApp**, licence re-check interval, offline grace, `api_base_url`,
  minimum app version/force update, and the four Firebase values.
* **Notices** — what the bell shows. **Clients** — the app credential pairs.
  **API access** — every token: which shop, which scopes, created, last used, expiry, and
  Revoke. A new one is shown once (only its hash survives) together with the `curl` line
  the integrator can paste. **Activity** — sign-ins, activations, blocks, seat changes.

## Testing

```bash
php tests/smoke.php          # schema + licence machine + seats + JWT + tokens + bills, SQLite only
bash tests/http-smoke.sh     # starts php -S, registers a shop, walks every endpoint, both credential kinds
python3 ../tools/build_openapi.py          # regenerate openapi.json after touching the route table
python3 ../tools/check_api_contract.py     # routes ↔ app call sites ↔ docs ↔ schema ↔ config
```

`tests/smoke.php` needs no config: it writes a throw-away SQLite file into `/tmp` and
points `QT_CONFIG` at it, then asserts that PENDING → ACTIVE → EXPIRED → REVOKED →
BLOCKED behave, that seats count correctly, and that the payload still carries the exact
field names the Android parser reads, then stores a bill the way a website would and
proves the token rules (hash at rest, read-only cannot write, revoke and expiry take
effect, stock moves by the difference). Run it after touching `lib/`.
`tests/http-smoke.sh` repeats the whole flow over real HTTP, including a token issued by
`token.php --print-token`.

From the repository root, `python3 tools/check_api_contract.py` cross-checks the app's
call sites against the route table, every `qt_*` call against a definition, every SQL
table against `schema.php`, and every `$config[...]` read against
`config.example.php` — the sandbox has no PHP, so that is the CI gate.

## Security notes (read before you expose this)

* `X-App-Id` / `X-Api-Key` keep scripts off the endpoints; they are **not** a secret
  that could survive a decompiled APK, which is why no rule depends on them alone.
* `jwt_secret` signs access tokens. Rotating it signs every till out (they re-auth with
  their refresh token or password, so it is a safe, boring operation).
* Refresh tokens and activation codes are stored hashed/one-time; passwords are
  `password_hash()` (bcrypt).
* API tokens are random 48-hex-char secrets stored **only** as sha256; the panel shows one
  token once and cannot recover it. Give a read-only token to anything running in a
  customer's browser, one token per program, and revoke the moment an integration ends —
  revocation lands on the next request, not at the next deploy.
* `token.php` and `install.php`'s sibling guard themselves (`PHP_SAPI !== 'cli'`) and
  `.htaccess` denies `token.php` outright; delete `install.php` after the first run.
* The panel has one login and no "remember me"; put it behind a second factor at the
  host level (Hostinger's 2FA, or an HTTP basic-auth prompt on `admin/`) if you keep it
  public.
* `config.php`, `lib/`, `storage/` and dotfiles are denied by `.htaccess`; verify with
  `curl -i https://host/api/config.php` (must be 403) after deploying.
* Consider a rate limit in front of `v1/auth/login` (a plugin, or LiteSpeed/Nginx
  `limit_req`) — the endpoint already costs a bcrypt verification per attempt.
