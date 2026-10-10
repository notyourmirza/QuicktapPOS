# QuickTap billing API

The server the Android app depends on: licence state, device seats, plan prices, the
support number, push/bootstrap configuration, notification notices and the legacy
two-way catalogue sync. Plain PHP 8 + MySQL, no framework, no Composer, no build
step — upload the folder and it runs on ordinary shared hosting (Hostinger, cPanel,
Plesk, a 2 GB VPS, anything with PHP + PDO).

Everything the app needs is decided **here**, never on the phone: a till with a
patched APK still gets `EXPIRED` from `/v1/license/status` and a `403` from every
write endpoint.

```
billing-api/
├── index.php          front controller: envelope, routing, error swallowing
├── install.php        one-shot installer (CLI or browser) — delete after use
├── config.example.php copy to config.php; the only file with secrets
├── .htaccess          rewrite to index.php, blocks lib/ + config.php
├── lib/
│   ├── support.php    config, PDO, envelope, route table, app-credential check, JWT, helpers
│   ├── license.php    THE licence + device-seat authority (the state machine)
│   ├── routes.php     one thin handler per endpoint
│   └── schema.php     the whole schema, rendered per driver (mysql | sqlite)
├── admin/
│   ├── index.php      panel login + all actions
│   └── views.php      the panel's HTML
├── tests/
│   ├── smoke.php      state-machine + seat + JWT checks on throw-away SQLite
│   └── http-smoke.sh  hits every endpoint over real HTTP with PHP's own server
└── storage/           only for the SQLite test database
```

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

Nineteen, and they are the same nineteen the app calls —
`../../tools/check_api_contract.py` fails the build if the two sides ever drift.
`GET /` prints the live list. The full contract, including every field name the app
parses, is [../LICENSE_API.md](../LICENSE_API.md).

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
| `POST v1/sync/push`, `GET v1/sync/pull` | user + **active licence** | legacy multi-till catalogue sync |

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
never push data into the shop's history.

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
  **Activity** — sign-ins, activations, blocks, seat changes.

## Testing

```bash
php tests/smoke.php          # schema + licence state machine + seats + JWT, SQLite, no web server
bash tests/http-smoke.sh     # starts php -S, registers a shop, walks every endpoint
```

`tests/smoke.php` needs no config: it writes a throw-away SQLite file into `/tmp` and
points `QT_CONFIG` at it, then asserts that PENDING → ACTIVE → EXPIRED → REVOKED →
BLOCKED behave, that seats count correctly, and that the payload still carries the exact
field names the Android parser reads. Run it after touching `lib/`.

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
* The panel has one login and no "remember me"; put it behind a second factor at the
  host level (Hostinger's 2FA, or an HTTP basic-auth prompt on `admin/`) if you keep it
  public.
* `config.php`, `lib/`, `storage/` and dotfiles are denied by `.htaccess`; verify with
  `curl -i https://host/api/config.php` (must be 403) after deploying.
* Consider a rate limit in front of `v1/auth/login` (a plugin, or LiteSpeed/Nginx
  `limit_req`) — the endpoint already costs a bcrypt verification per attempt.
