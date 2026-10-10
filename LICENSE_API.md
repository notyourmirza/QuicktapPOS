# QuickTap POS — licence / billing API contract

The Android app talks to **one** HTTP origin for everything except Google Drive: the
billing API. It is implemented in this repository at
[`billing-api/`](billing-api/README.md) — plain PHP 8 + MySQL, deployable on shared
hosting — and this file is the contract it must keep.

Nothing here is a proposal: every field name below is the name the app actually reads,
taken from `auth/类004.java`, `license/类013.java`, `license/类014.java`, `net/类016.java`,
`net/类018.java`, `sync/类034.java`, `theme/类036.java`, `ui/plans/类085.java`,
`ui/notifications/类081.java` and `license/DeviceRegistry.java`.
`python3 tools/check_api_contract.py` fails when the two sides drift, and
`billing-api/tests/http-smoke.sh` walks the whole flow over real HTTP.

## Every request

```
POST /v1/auth/login HTTP/1.1
Host: your-domain.com
Content-Type: application/json
Accept: application/json
X-App-Id: app_41f7d123706a          # BuildConfig.APP_ID
X-Api-Key: a94791db…                # BuildConfig.API_KEY
X-App-Version: 1.2.0                 # BuildConfig.VERSION_NAME
X-Device-Id: 0f1e2d…                # sha256 fingerprint, auth/类003.id()
Authorization: Bearer <access_token> # only when the app has a session
```

* Base URL: `BOOTSTRAP_BASE_URL` in `app/build.gradle`, overridable by `api_base_url`
  from the panel — see [Firebase routing](#firebase-and-endpoint-routing) below.
* **HTTPS only.** `net/类018` refuses any other scheme, so a plain-HTTP host is not a
  supported configuration.
* Paths are appended to the base URL, so the base must end with `/`.

## Every response

```json
{ "success": true, "message": "…", "data": { }, "code": "OK", "timestamp": 1735689600000 }
```

`success` and `message` are what the app toasts; `code` is what it *branches* on;
`data` holds the payload. Errors are the same envelope with `success: false` and a
4xx/5xx status. Codes the app distinguishes:

| `code` | App reaction |
|---|---|
| `LICENSE_EXPIRED` | forced expired screen (no back door) |
| `LICENSE_PENDING`, `LICENSE_INACTIVE` | pending/activation screen |
| `LICENSE_REVOKED` | revoked screen |
| `ACCOUNT_BLOCKED`, `DEVICE_BLOCKED`, `USER_DISABLED`, `DEVICE_LIMIT` | blocked screen |
| `SHOP_INACTIVE` | suspended screen |
| `NO_TOKEN`, `TOKEN_EXPIRED`, `TOKEN_INVALID` | sign in again (one silent refresh + retry first) |
| `LICENSE_NOT_FOUND`, `LICENSE_FOREIGN`, `BAD_CREDENTIALS` | inline error on the form |
| `DEVICE_MISMATCH` | the till must sign in again on *its* device |
| anything unknown, no answer, or a non-200 | **fail closed**: the licence screen with the server's `message` shown verbatim |

The last row is the important one: the app never interprets silence as permission.

## Session

| Endpoint | Request body | `data` |
|---|---|---|
| `POST v1/auth/register` | `shop_name`, `username`, `password`, `full_name`, `phone`, `device_name`, `os_version`, `app_version` | `tokens{access_token,refresh_token,expires_in}`, `user{id,role,full_name,fingerprint_enabled}`, `shop{name,currency}` **plus the flat licence fields below** |
| `POST v1/auth/login` | `username`, `password`, `device_id`, `device_name`, `app_version`, `os_version` | same as register |
| `POST v1/auth/refresh` | `refresh_token` | `tokens{access_token,refresh_token,expires_in}`, `license_status` |
| `POST v1/auth/logout` | – | `revoked: true` |
| `GET v1/auth/me` | – | `role`, `full_name`, `shop_name`, `fingerprint_enabled` (0/1) — **flat**, not nested |
| `POST v1/auth/unlock` | `password` | `unlocked: true` |
| `POST v1/auth/fingerprint` | `enabled` (bool) | `fingerprint_enabled` (0/1) |

`fingerprint_enabled` is an **int** in every answer: the app reads `optInt`, a boolean
here would silently disable the biometric.

## Licence — the part that gates the app

`GET v1/license/status` (also returned by register, login, verify and confirm):

```json
{ "success": true, "message": "Licence active", "code": "OK", "data": {
  "license_status": "ACTIVE", "account_status": "ACTIVE", "request_status": "ACTIVE",
  "unlocked": true, "confirmed": true,
  "duration_label": "27 Days", "expires_at_ms": 1738281600000, "server_time_ms": 1735689600000,
  "days_left": 27,
  "plan": { "code": "growth", "name": "Growth" },
  "device_seat": { "used": 2, "limit": 3 }
} }
```

| Field | Meaning to the app |
|---|---|
| `license_status` | `ACTIVE` `PENDING` `EXPIRED` `REVOKED` `SUSPENDED` `BLOCKED` `UNKNOWN` — decides which full-screen state the till shows |
| `unlocked` | must be true **with** `ACTIVE` for selling (`license/类012.maySell`) |
| `confirmed` | `ACTIVE && unlocked && !confirmed` → the "confirm your username and password" dialog |
| `expires_at_ms` | drives the countdown on the plans and licence screens |
| `server_time_ms` | clock-drift detection; a till more than ~5 min off is warned about |
| `days_left` | `null` = lifetime; the app renders "Lifetime" |
| `device_seat` | shown as "This till (2 of 3 seats)" in Settings and on the Device-access screen |

`POST v1/license/verify` takes `license_key` (either a shop's own key or an unused
activation code) and is deliberately reachable while `PENDING`/`EXPIRED` — it is how a
shop gets back in. `POST v1/license/confirm` takes `username` + `password`.

`GET v1/plans` (no session needed) → `plans[{code,name,tagline,tag,price,price_yearly,
max_devices,features[]}]`, `currency`, and for a signed-in till `current{code,name,
status,days_left,ends_at,expires_at_ms,device_seat}`. `features` is an array of strings;
`ends_at` is parsed as `Y-m-d H:i:s` in the app, so keep that format. Purchasing is not
in the app any more: the button is hidden and a plan is assigned from the panel.

## Device accounting

| Endpoint | `data` |
|---|---|
| `GET v1/devices` | `devices[{device_id,device_name,model,os_version,app_version,status,first_seen_at_ms,last_seen_at_ms}]`, `seat{used,limit}`, `shop_name` |
| `POST v1/devices/deactivate` | `released` (device id), `seat{used,limit}`, `this_device` (bool) |

`status` is one of `ACTIVE` / `RELEASED` / `BLOCKED`. Timestamps are **milliseconds**
(`*_at_ms`), which is why the schema also keeps the raw `DATETIME` for the panel.
`_timestamp` fields are never required; the app defaults a missing one to `0` and prints
"never". Deactivating your own device is allowed and the app says so, because the seat is
then free for the replacement till.

## Configuration the app pulls

`GET v1/app/bootstrap` (fixed URL, no session):

```json
{ "data": { "firebase": { "app_id": "1:…:android:…", "api_key": "AIza…",
  "project_id": "quicktap-pos", "sender_id": "1234567890" },
  "api_base_url": "https://billing.your-domain.com/api/" } }
```

`GET v1/app/config` (also called pre-login; the token is attached when the till has one,
so a shop gets its own name back):

```json
{ "data": { "app_name": "Gulberg Grill", "logo_url": "", "receipt_template": "classic",
  "support": { "whatsapp": "923001234567" }, "support_whatsapp": "923001234567",
  "sync_minutes": 60, "notifications": [ { "id": 1, "title": "…", "body": "…",
  "level": "info", "created_at": "2025-01-01 00:00:00" } ] } }
```

`support.whatsapp` **and** the flat `support_whatsapp` are both read (the app accepts
either); the digits land in the "Contact support" WhatsApp button. `POST
v1/settings/app-name` with `app_name` lets a shop rename itself everywhere it prints.

`GET v1/notifications` (needs a session) → `notifications[]`, same shape as above.

## Catalogue sync (`Sync now`)

* `POST v1/sync/push` — body `{categories:[…], products:[…], orders:[…]}`; each record
  carries its own `uuid`, `updated_at` (`Y-m-d H:i:s`) and optional `deleted_at`.
  Response: `data.accepted{categories[],products[],orders[]}` listing the accepted uuids;
  the app marks exactly those rows as synced.
* `GET v1/sync/pull?since=<ms>` — `data{categories[],products[],orders[],settings{
  auto_lock_minutes,currency,receipt_footer,tax_percent},server_time}`.
  Last write wins per `uuid`, judged on the timestamp the device sent.
* Both are behind `qt_require_licence()`: an expired till cannot push anything into the
  shop's history, which is the property that makes offline grace safe.

## Firebase and endpoint routing

The APK stores exactly one address (`BOOTSTRAP_BASE_URL`). On start-up `net/类018` calls
`v1/app/bootstrap`, and the answer (or Firebase Remote Config's `api_base_url`) is cached
as the "last known good" base URL that every later call uses — so the API can move hosts
without a new build, while a phone that never reaches the network keeps the address it
was shipped with. Only `https` is accepted, and a bad bootstrap response is ignored
rather than trusted.
