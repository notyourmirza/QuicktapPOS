#!/usr/bin/env bash
# Deploy or update the QuickTap billing API on a shared host (Hostinger or alike).
#
#   QT_HOST='u12345672@123.45.67.89' QT_SSH_PORT=65002 \
#   QT_DIR='public_html/api' bash billing-api/deploy.sh
#
# What it does, in order:
#   1. packs the files the API needs — never config.php, never the sqlite test database
#   2. unpacks them into $QT_DIR on the server, keeping config.php where it is
#   3. re-runs install.php --force, which adds any column a new version introduced
#      and never drops or rewrites a table, so shop data is untouched
#   4. checks the two things that must be true in public: config.php is not readable,
#      and the API answers
#
# First install only: copy config.example.php to config.php on the server, fill in the
# database credentials, jwt_secret, app_id/app_api_key and install_key, then run
# `php install.php --admin-user=… --admin-pass=…` there and delete install.php.
# Re-run this script for every later version.

set -euo pipefail

QT_HOST="${QT_HOST:-}"
QT_SSH_PORT="${QT_SSH_PORT:-22}"
QT_DIR="${QT_DIR:-public_html/api}"
LOCAL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_URL="${BASE_URL:-}"

if [[ -z "$QT_HOST" ]]; then
  cat <<'TEXT' >&2
Set where to put it:
  QT_HOST='user@host' QT_SSH_PORT=65002 QT_DIR='public_html/api' bash deploy.sh
On Hostinger: hPanel → Advanced → SSH gives the user, host and port; public_html/api
is the folder the app's BOOTSTRAP_BASE_URL then points at
(https://your-domain.com/api/).
TEXT
  exit 2
fi

SSH=("ssh" "-p" "$QT_SSH_PORT" "-o" "ConnectTimeout=15" "$QT_HOST")
SCP=("scp" "-P" "$QT_SSH_PORT" "-o" "ConnectTimeout=15")

say() { printf '\n\033[1m▸ %s\033[0m\n' "$*"; }

PACK=(index.php install.php token.php config.example.php .htaccess README.md API.md
      openapi.json lib admin client web tests storage)
[[ -f "$LOCAL_DIR/config.php" ]] && say 'warning: your local config.php is deliberately NOT uploaded'

say '1/4  packing'
WORK="$(mktemp -d)"
tar -C "$LOCAL_DIR" -cf "$WORK/quicktap-api.tar" \
    --exclude='config.php' --exclude='storage/*.sqlite' --exclude='storage/*.log' \
    "${PACK[@]}"
printf '   %s of files\n' "$(wc -c <"$WORK/quicktap-api.tar" | tr -d ' ')"

say '2/4  uploading into '"$QT_DIR"' (config.php stays as it is)'
"${SSH[@]}" "mkdir -p '$QT_DIR' && if [ -f '$QT_DIR/config.php' ]; then cp '$QT_DIR/config.php' '$QT_DIR/config.php.bak'; fi"
cat "$WORK/quicktap-api.tar" | "${SSH[@]}" "cd '$QT_DIR' && tar -xf - \
  && chmod -R u+rwX,go+rX . && mkdir -p storage \
  && chmod u+rwX,g+rwX,o+rX storage \
  && if [ -f config.php.bak ]; then mv config.php.bak config.php; fi"
rm -rf "$WORK"

say '3/4  schema: adding whatever this version needs'
"${SSH[@]}" "cd '$QT_DIR' && php install.php --force" \
  || say 'install.php --force failed — run it by hand over SSH and read the error'

say '4/4  the two things that must be true in public'
if [[ -n "$BASE_URL" ]]; then
  code="$(curl -s -o /dev/null -w '%{http_code}' "${BASE_URL}/config.php" || echo 000)"
  if [[ "$code" == "403" || "$code" == "404" ]]; then
    printf '   ✓ config.php is not readable (%s)\n' "$code"
  else
    printf '   ✗ config.php answered %s — fix .htaccess before you go on\n' "$code"
    exit 1
  fi
  body="$(curl -s "${BASE_URL}/" || true)"
  if printf '%s' "$body" | grep -q 'QuickTap billing API is running'; then
    printf '   ✓ the API answers\n'
  else
    printf '   ✗ %s did not report the API: %s\n' "$BASE_URL" "$(printf '%s' "$body" | head -c 200)"
    exit 1
  fi
else
  say 'set BASE_URL=https://your-domain.com/api to have deploy.sh check the public side'
fi

cat <<TEXT

Done. Left to do by hand, once:
  ssh -p $QT_SSH_PORT $QT_HOST "cd $QT_DIR && rm -f install.php"
  php token.php --shop=<id> --label=website --scopes=read,write     # a website or desktop client
  BASE_URL=https://your-domain.com/api bash tests/http-smoke.sh     # if you want proof

The app needs no restart: it re-reads v1/app/bootstrap on the next launch.
TEXT
