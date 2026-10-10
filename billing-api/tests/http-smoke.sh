#!/usr/bin/env bash
# End-to-end check of the billing API over real HTTP, with no MySQL and no Android.
#
#   bash billing-api/tests/http-smoke.sh
#
# It starts PHP's own web server, installs a throw-away SQLite database, then walks
# the exact sequence a till walks: register → (locked) → licence code → activate →
# confirm → push a catalogue → pull it back → release the seat → sign in again.
# Every assertion is a grep on the JSON answer, so a renamed field fails the run.

set -uo pipefail
cd "$(dirname "$0")/.."

PORT="${QT_SMOKE_PORT:-8711}"
BASE="http://127.0.0.1:${PORT}"
APP_ID="app_smoke"
API_KEY="key_smoke_0123456789abcdef"
# Same shape as the app's fingerprint: sha256 hex of the installation.
DEVICE_ID="0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0"
WORK="$(mktemp -d)"
NOW="$(date -u '+%Y-%m-%d %H:%M:%S')"
PASSED=0
FAILED=0

cleanup() {
  [[ -n "${SERVER_PID:-}" ]] && kill "${SERVER_PID}" 2>/dev/null
  wait "${SERVER_PID:-}" 2>/dev/null
  rm -rf "${WORK}"
}
trap cleanup EXIT

say()  { printf '%s\n' "$*"; }
pass() { PASSED=$((PASSED + 1)); printf '\033[32m✓\033[0m %s\n' "$1"; }
fail() { FAILED=$((FAILED + 1)); printf '\033[31m✗\033[0m %s\n' "$1"; }

# assert <label> <needle> <haystack>
assert_contains() {
  if printf '%s' "$3" | grep -q -- "$2"; then pass "$1"; else
    fail "$1 — expected to contain '$2'"
    printf '   got: %s\n' "$(printf '%s' "$3" | head -c 400)"
  fi
}

api() { # api <method> <path> [json-body] [token]
  local method="$1" path="$2" body="${3:-}" token="${4:-}"
  local args=(-s -X "$method"
    -H "X-App-Id: ${APP_ID}" -H "X-Api-Key: ${API_KEY}"
    -H "X-Device-Id: ${DEVICE_ID}" -H "X-App-Version: 1.2.0"
    -H 'Content-Type: application/json')
  [[ -n "$token" ]] && args+=(-H "Authorization: Bearer ${token}")
  [[ -n "$body" ]] && args+=(-d "$body")
  curl "${args[@]}" "${BASE}/${path}"
}

say 'QuickTap billing API — HTTP smoke test'
say '────────────────────────────────────────'

# ---------------------------------------------------------------- config + install
if [[ -f config.php ]]; then
  mv config.php "${WORK}/config.php.keep"
  say 'note: your existing config.php was moved aside and is restored at the end'
fi
cat > config.php <<PHP
<?php
return [
    'driver' => 'sqlite',
    'sqlite_path' => '${WORK}/smoke.sqlite',
    'jwt_secret' => 'http-smoke-secret-not-for-anything-real',
    'jwt_ttl' => 60,
    'app_id' => '${APP_ID}',
    'app_api_key' => '${API_KEY}',
    'trial_days' => 0,
    'default_currency' => 'PKR',
    'debug' => true,
];
PHP
if command -v php >/dev/null 2>&1; then
  say 'php: found'
else
  fail 'php is not installed — this test needs a PHP 8.1+ CLI'
  exit 1
fi
php install.php --no-client --admin-user=smoke --admin-pass='smoke-password-1' >"${WORK}/install.log" 2>&1
assert_contains 'install.php reports the schema' 'Schema ready' "$(cat "${WORK}/install.log")"

# ------------------------------------------------------------------- web server
php -S "127.0.0.1:${PORT}" index.php >"${WORK}/server.log" 2>&1 &
SERVER_PID=$!
for _ in $(seq 1 40); do
  curl -s -o /dev/null "${BASE}/" && break
  sleep 0.25
done

BODY="$(api GET '')"
assert_contains 'GET / lists the endpoints' '"endpoints"' "$BODY"
assert_contains 'GET / includes the sync endpoint' 'v1/sync/pull' "$BODY"

BODY="$(api GET 'v1/app/config')"
assert_contains 'config is served without a token' '"success":true' "$BODY"

BODY="$(curl -s -X GET "${BASE}/v1/app/config")"
assert_contains 'a build without credentials is refused' 'NO_API_KEY' "$BODY"

# -------------------------------------------------------------------- register
BODY="$(api POST v1/auth/register \
  "{\"shop_name\":\"Smoke Kebab\",\"username\":\"smokeowner\",\"password\":\"secret1234\",\"full_name\":\"Smoke Tester\",\"phone\":\"923001234567\",\"device_name\":\"Smoke test till\",\"os_version\":\"Android 14\"}")"
assert_contains 'register returns a session' '"access_token"' "$BODY"
assert_contains 'register says it waits for activation' 'waiting for licence' "$BODY"
TOKEN="$(printf '%s' "$BODY" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')"
REFRESH="$(printf '%s' "$BODY" | sed -n 's/.*"refresh_token":"\([^"]*\)".*/\1/p')"
[[ -n "$TOKEN" ]] && pass 'access token parsed' || fail 'no access token to test with'

BODY="$(api GET v1/license/status '' "$TOKEN")"
assert_contains 'a new shop is PENDING' '"license_status":"PENDING"' "$BODY"
assert_contains '  …and locked' '"unlocked":false' "$BODY"
assert_contains '  …with a one-device seat' '"device_seat":{"used":1,"limit":1}' "$BODY"

BODY="$(api POST v1/sync/push "{\"products\":[{\"uuid\":\"p-smoke-1\",\"name\":\"Chicken Roll\",\"price\":250,\"stock\":40,\"is_active\":1,\"updated_at\":\"${NOW}\"}]}" "$TOKEN")"
assert_contains 'writes are refused while PENDING' 'LICENSE_PENDING' "$BODY"

# ------------------------------------------------------------- activate + confirm
CODE="$(php -r 'require "lib/support.php"; $c="QT-SMOKE-".strtoupper(bin2hex(random_bytes(3))); qt_insert("activation_codes",["code"=>$c,"days"=>30,"note"=>"http smoke","created_at"=>qt_sql_now()]); echo $c;')"
assert_contains 'a code was generated' 'QT-SMOKE-' "$CODE"

BODY="$(api POST v1/license/verify "{\"license_key\":\"${CODE}\"}" "$TOKEN")"
assert_contains 'verifying the code activates the shop' '"license_status":"ACTIVE"' "$BODY"
assert_contains '  …and the app is unlocked' '"unlocked":true' "$BODY"
assert_contains '  …but confirmation is owed' '"confirmed":false' "$BODY"

BODY="$(api POST v1/license/confirm '{"username":"smokeowner","password":"secret1234"}' "$TOKEN")"
assert_contains 'confirming finishes the licence step' '"confirmed":true' "$BODY"

BODY="$(api POST v1/license/verify '{"license_key":"QT-NOPE-NOPE-NOPE-NOPE"}' "$TOKEN")"
assert_contains 'a made-up key is refused' 'LICENSE_NOT_FOUND' "$BODY"

# ------------------------------------------------------------------ sync round-trip
BODY="$(api POST v1/sync/push "{\"products\":[{\"uuid\":\"p-smoke-1\",\"name\":\"Chicken Roll\",\"price\":250,\"stock\":40,\"is_active\":1,\"updated_at\":\"${NOW}\"}],\"categories\":[{\"uuid\":\"c-smoke-1\",\"name\":\"Wraps\",\"sort_order\":1,\"updated_at\":\"${NOW}\"}]}" "$TOKEN")"
assert_contains 'push is accepted now' '"accepted"' "$BODY"
assert_contains '  …and names the product back' 'p-smoke-1' "$BODY"

BODY="$(api GET 'v1/sync/pull?since=0' '' "$TOKEN")"
assert_contains 'pull returns the settings block' '"settings"' "$BODY"
assert_contains '  …a server time' '"server_time"' "$BODY"
assert_contains '  …and the shop keeps its currency' 'PKR' "$BODY"

# --------------------------------------------------------------- devices + seats
BODY="$(api GET v1/devices '' "$TOKEN")"
assert_contains 'devices lists this till' 'Smoke test till' "$BODY"
assert_contains '  …marked as the current one' '"is_current":true' "$BODY"
assert_contains '  …with the seat filled' '"used":1,"limit":1' "$BODY"

BODY="$(api POST v1/devices/deactivate "{\"device_id\":\"${DEVICE_ID}\"}" "$TOKEN")"
assert_contains 'releasing the seat is reported' 'released' "$BODY"

BODY="$(api GET v1/devices '' "$TOKEN")"
assert_contains '  …and the seat is free again' '"used":0,"limit":1' "$BODY"

BODY="$(api POST v1/auth/login "{\"username\":\"smokeowner\",\"password\":\"secret1234\",\"device_id\":\"${DEVICE_ID}\",\"device_name\":\"Smoke test till\"}" "$TOKEN")"
assert_contains 'sign-in takes the seat back' '"access_token"' "$BODY"
TOKEN="$(printf '%s' "$BODY" | sed -n 's/.*"access_token":"\([^"]*\)".*/\1/p')"

BODY="$(api POST v1/auth/login '{"username":"smokeowner","password":"wrong-password"}' '')"
assert_contains 'a wrong password is refused' 'BAD_CREDENTIALS' "$BODY"

BODY="$(api POST v1/auth/refresh "{\"refresh_token\":\"${REFRESH}\"}" '')"
assert_contains 'the refresh token works once' '"access_token"' "$BODY"
BODY="$(api POST v1/auth/refresh "{\"refresh_token\":\"${REFRESH}\"}" '')"
assert_contains '  …and never twice' 'TOKEN_REVOKED' "$BODY"

# ------------------------------------------------------- settings, notices, plans
BODY="$(api POST v1/settings/app-name '{"app_name":"Gulberg Grill"}' "$TOKEN")"
assert_contains 'a shop can rename itself' '"app_name":"Gulberg Grill"' "$BODY"
BODY="$(api GET v1/app/config)"
assert_contains '  …and other tills see it' 'Gulberg Grill' "$BODY"

BODY="$(api GET v1/notifications '' "$TOKEN")"
assert_contains 'the bell answers' '"notifications":[]' "$BODY"

BODY="$(api GET v1/plans '' "$TOKEN")"
assert_contains 'the store lists three plans' '"plans"' "$BODY"
assert_contains '  …with the seat per plan' 'max_devices' "$BODY"

# ------------------------------------------------------------------ admin blocks
php -r 'require "lib/support.php"; qt_run("UPDATE shops SET status = ? WHERE id = 1", ["BLOCKED"]);'
BODY="$(api GET v1/license/status '' "$TOKEN")"
assert_contains 'a blocked shop is told so' '"license_status":"BLOCKED"' "$BODY"
BODY="$(api POST v1/sync/push '{"products":[]}' "$TOKEN")"
assert_contains '  …and nothing can be written' 'ACCOUNT_BLOCKED' "$BODY"
php -r 'require "lib/support.php"; qt_run("UPDATE shops SET status = ? WHERE id = 1", ["ACTIVE"]);'

# ------------------------------------------------------------ expiry (real clock)
php -r 'require "lib/support.php"; qt_run("UPDATE licences SET status = ?, expires_at = ? WHERE shop_id = 1", ["ACTIVE", time() - 60]);'
BODY="$(api GET v1/license/status '' "$TOKEN")"
assert_contains 'an expired licence reads EXPIRED' '"license_status":"EXPIRED"' "$BODY"
assert_contains '  …days_left clamped to zero' '"days_left":0' "$BODY"
php -r 'require "lib/support.php"; qt_extend_licence(1, 30, "smoke: restored");'
BODY="$(api GET v1/license/status '' "$TOKEN")"
assert_contains '  …and a renewal brings it back' '"license_status":"ACTIVE"' "$BODY"

say '────────────────────────────────────────'
say "$((PASSED + FAILED)) checks, ${PASSED} passed, ${FAILED} failed"

# restore whatever config the developer had here
if [[ -f "${WORK}/config.php.keep" ]]; then mv "${WORK}/config.php.keep" config.php; else rm -f config.php; fi
rm -f "${WORK}/smoke.sqlite"
[[ ${FAILED} -eq 0 ]] || exit 1
