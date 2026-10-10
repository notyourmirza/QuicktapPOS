#!/usr/bin/env bash
# Deploy or update the QuickXLab portal (site + panel + /api) on a shared host.
#
#   ./quickxlab/deploy.sh --host=u12345672@145.79.25.20 --port=65002 \
#                         --dir=public_html --url=https://quickxlab.com
#
# Run it from a machine that has this repository checked out. It is deliberately
# non-destructive, in this order:
#
#   1. preflight over SSH: PHP >= 8.2, pdo_mysql, composer, and what is already in the target
#   2. (first install, opt-in) move the files currently in --dir into --dir/tem — a move
#   3. pack the app — never .env, never vendor (unless --with-vendor), never the folders that
#      hold live state on the server (storage logs, caches, public/apk/*.apk)
#   4. extract over the top; the server's own .env, apk folder, assets and logs are copied
#      back afterwards, so an update cannot lose a published APK or a real config
#   5. composer, artisan key, migrate --force (create-only, guarded), db:seed --force (fills
#      holes only), caches, permissions
#   6. verify from outside: / is 200, /api/ answers, /admin/login answers, .env and
#      storage/logs are NOT readable, and /api/v1/app/bootstrap accepts the build
#      credentials that are in the server's .env — which is the till's own first request
#
# Flags
#   --host=user@host   required (Hostinger: hPanel -> Advanced -> SSH)
#   --port=22          ssh port
#   --dir=public_html  the document root; the app lives inside it and the root .htaccess
#                      rewrites requests into public/
#   --url=https://…    public base URL, used for the verification calls
#   --with-vendor      ship your local vendor/ instead of running composer on the host
#   --no-composer      host has no composer (combine with --with-vendor)
#   --skip-migrate     push files only; touch no schema (a hotfix)
#   --move-old-site    first install only: move what is in --dir into --dir/tem
#   --check            verify an existing deployment; upload nothing, change nothing
#   --dry-run          print what would be packed and run; change nothing
#   -h, --help         this text
#
# After step 6 is green: publish api_base_url in Firebase Remote Config (that is what moves
# the existing tills), then build the APK. Docs: ../QUICKXLAB_SETUP.md.

set -euo pipefail

HOST=''
PORT='22'
DIR='public_html'
URL=''
WITH_VENDOR=0
NO_COMPOSER=0
SKIP_MIGRATE=0
CHECK_ONLY=0
DRY_RUN=0
MOVE_OLD=0

LOCAL_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

for arg in "$@"; do
  case "$arg" in
    --host=*) HOST="${arg#*=}" ;;
    --port=*) PORT="${arg#*=}" ;;
    --dir=*) DIR="${arg#*=}" ;;
    --url=*) URL="${arg#*=}" ;;
    --with-vendor) WITH_VENDOR=1 ;;
    --no-composer) NO_COMPOSER=1 ;;
    --skip-migrate) SKIP_MIGRATE=1 ;;
    --check) CHECK_ONLY=1 ;;
    --dry-run) DRY_RUN=1 ;;
    --move-old-site) MOVE_OLD=1 ;;
    -h|--help) sed -n '2,44p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) printf 'unknown option: %s (see --help)\n' "$arg" >&2; exit 2 ;;
  esac
done

if [[ -z "$HOST" && "$DRY_RUN" == 1 ]]; then
  HOST='dryrun@localhost'   # --dry-run never connects; a placeholder keeps the output honest
elif [[ -z "$HOST" ]]; then
  cat >&2 <<'TEXT'
Tell me where to put it:
  ./quickxlab/deploy.sh --host=user@host --port=65002 --dir=public_html --url=https://quickxlab.com

On Hostinger: hPanel -> Advanced -> SSH gives user, host and port. `--dir=public_html` is the
document root; the app lives inside it and its own .htaccess rewrites every request into
public/. `--url` is used only for the verification calls at the end.
TEXT
  exit 2
fi

[[ -n "$URL" ]] || URL="https://${HOST##*@}"
SSH=(ssh -p "$PORT" -o ConnectTimeout=20 -o BatchMode=yes "$HOST")

say() { printf '\n\033[1m▸ %s\033[0m\n' "$*"; }
die() { printf '\n\033[31m✗ %s\033[0m\n' "$*" >&2; exit 1; }

# One place that decides between doing and printing, so --dry-run cannot miss a step.
remote() {
  if [[ "$DRY_RUN" == 1 ]]; then
    printf '   would run on %s: %s\n' "$HOST" "$(printf '%s' "$1" | tr -s ' \n' ' ' | cut -c1-160)"
  else
    "${SSH[@]}" "$@"
  fi
}

# --------------------------------------------------------------------------- 1 preflight
say '1/6  what the host looks like'
if [[ "$DRY_RUN" == 1 ]]; then
  printf '   would ssh %s and read php version, pdo_mysql, composer, and ls %s\n' "$HOST" "$DIR"
  PREFLIGHT=''
else
  PREFLIGHT="$("${SSH[@]}" '
    php -r "echo PHP_MAJOR_VERSION.\".\".PHP_MINOR_VERSION.\"\n\";" 2>/dev/null || echo php-missing
    php -m 2>/dev/null | tr "\n" " " | grep -o pdo_mysql | head -1
    command -v composer >/dev/null 2>&1 && composer --version 2>/dev/null | head -1 || echo no-composer
    for f in .env artisan public/index.php; do [ -e "$1/$f" ] && echo "have:$f"; done
    echo "entries: $(ls "$1" 2>/dev/null | wc -l)"
  ' _ "$DIR" 2>&1)" || die "ssh to $HOST failed. Check the port ($PORT), that your key is
  installed (ssh-copy-id -p $PORT $HOST), and that Shell access is enabled in hPanel.
  Nothing was changed."
  printf '%s\n' "$PREFLIGHT" | sed 's/^/   /'

  case "$(printf '%s\n' "$PREFLIGHT" | head -1)" in
    php-missing) die 'this shell has no `php` on PATH — without PHP 8.2+ Laravel 12 will not run' ;;
    8.2*|8.3*|8.4*|8.5*) : ;;
    *) die "PHP $(printf '%s\n' "$PREFLIGHT" | head -1) found; Laravel 12 needs 8.2 or newer" ;;
  esac
  printf '%s\n' "$PREFLIGHT" | grep -q pdo_mysql \
    || die 'php has no pdo_mysql: hPanel -> PHP -> Extensions, enable it and re-run'
  if [[ "$WITH_VENDOR" == 0 && "$NO_COMPOSER" == 0 && "$CHECK_ONLY" == 0 ]]; then
    if printf '%s\n' "$PREFLIGHT" | grep -q no-composer; then
      die 'composer is not on PATH. Enable it, or pass --with-vendor to ship vendor/ from your machine'
    fi
  fi
  if printf '%s\n' "$PREFLIGHT" | grep -q 'have:public/index.php'; then
    say 'target already holds an app — this is an update'
  elif [[ "$MOVE_OLD" == 0 ]] && ! printf '%s\n' "$PREFLIGHT" | grep -q 'entries: 0'; then
    say 'target is not empty and holds no app yet'
    printf '   If that is the OLD site, re-run with --move-old-site (it moves files to %s/tem,\n   it deletes nothing). If you already uploaded by hand, this run will just update it.\n' "$DIR"
  fi
fi

# ------------------------------------------------------------------ 2 old site into tem/
if [[ "$MOVE_OLD" == 1 ]]; then
  say '2/6  moving what is in '"$DIR"' into '"$DIR"'/tem (a move, never a delete)'
  remote '
    cd "$1" 2>/dev/null || exit 0
    mkdir -p tem
    for f in * .htaccess .user.ini; do
      [ -e "$f" ] || continue
      [ "$f" = tem ] && continue
      mv -- "$f" tem/ 2>/dev/null || true
    done
    # The old site keeps its config.php with the database password in it. Do not let
    # anyone read it over HTTP while it sits in the document root.
    if [ -d tem ]; then
      cat > tem/.htaccess <<HTX
Require all denied
<IfModule !mod_authz_core.c>
  Order deny,allow
  Deny from all
</IfModule>
HTX
      if [ -f tem/config.php ]; then chmod 600 tem/config.php; fi
    fi
    echo "   tem/ now holds $(ls tem | wc -l) entries — the old site is reachable at /tem/"
  ' _ "$DIR"
fi

# ------------------------------------------------------------------------------- 3 pack
PACK_LIST=(artisan bootstrap composer.json config public resources routes storage database
           .env.example .gitignore .htaccess)
EXCLUDES=(--exclude=.env --exclude='public/apk/*.apk' --exclude='storage/logs/*'
           --exclude='storage/framework/cache/data/*' --exclude='storage/framework/sessions/*'
           --exclude='storage/framework/views/*' --exclude=node_modules --exclude=.git)
[[ "$WITH_VENDOR" == 1 ]] || EXCLUDES+=(--exclude=vendor)

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
TAR="$WORK/quickxlab.tar"

say '3/6  packing'
if [[ "$WITH_VENDOR" == 1 && ! -d "$LOCAL_DIR/vendor" ]]; then
  die '--with-vendor needs a local vendor/: cd quickxlab && composer install --no-dev --optimize-autoloader'
fi
EXISTING=()
for item in "${PACK_LIST[@]}"; do
  [[ -e "$LOCAL_DIR/$item" ]] && EXISTING+=("$item")
done
[[ ${#EXISTING[@]} -gt 0 ]] || die "nothing to pack under $LOCAL_DIR — is this the repo root?"
printf '   packing: %s\n' "${EXISTING[*]}"
tar -C "$LOCAL_DIR" -cf "$TAR" "${EXCLUDES[@]}" "${EXISTING[@]}"
printf '   %s of files (no .env, no logs, no caches%s)\n' \
  "$(du -h "$TAR" | cut -f1)" "$( [[ "$WITH_VENDOR" == 1 ]] && echo ', vendor/ included')"

if [[ "$DRY_RUN" == 1 ]]; then
  printf '\n--dry-run: nothing was uploaded. The run would have been:\n'
  printf '   extract into %s, then: composer install, artisan key:generate, migrate --force,\n   db:seed --force, config/route/view/event cache, chmod 775 storage bootstrap/cache\n' "$DIR"
  printf '   then verify %s/api/ and %s/admin/login with curl\n' "$URL" "$URL"
  exit 0
fi

# --------------------------------------------------------------------------- 4 upload
if [[ "$CHECK_ONLY" == 1 ]]; then
  say '4/6  skipped (--check uploads nothing and changes nothing)'
else
  say '4/6  uploading into '"$DIR"
  remote '
    set -e
    mkdir -p "$1"
    cd "$1"
    for keep in .env public/apk public/assets storage/logs; do
      [ -e "$keep" ] || continue
      mkdir -p ".qtkeep/$(dirname "$keep")"
      cp -a "$keep" ".qtkeep/$keep"
    done
    tar -xf -
    mkdir -p storage/framework/cache/data storage/framework/sessions storage/framework/views \
             storage/logs bootstrap/cache public/apk
    for keep in .env public/apk public/assets storage/logs; do
      [ -e ".qtkeep/$keep" ] || continue
      if [ -d ".qtkeep/$keep" ]; then cp -a ".qtkeep/$keep/." "$keep/"; else cp -f ".qtkeep/$keep" "$keep"; fi
    done
    rm -rf .qtkeep
  ' _ "$DIR" < "$TAR"

  # --------------------------------------------------------------- 5 install + schema
  say '5/6  dependencies, key, schema, caches'
  DO_MIGRATE=skip
  if [[ "$SKIP_MIGRATE" == 0 && "$CHECK_ONLY" == 0 ]]; then DO_MIGRATE=migrate; fi
  DEPS=install
  if [[ "$WITH_VENDOR" == 1 ]]; then DEPS=vendor; fi
  if [[ "$NO_COMPOSER" == 1 ]]; then DEPS=novendor; fi
  remote '
    set -e
    cd "$1"
    if [ ! -d vendor ]; then
      if [ -f composer.lock ]; then
        composer install --no-dev --optimize-autoloader --no-interaction --no-progress
      else
        echo "   no composer.lock yet — resolving once (commit the file it writes)"
        composer update --no-dev --optimize-autoloader --no-interaction --no-progress
      fi
    else
      echo "   vendor/ present — composer skipped"
    fi
    if [ ! -f .env ]; then
      cp .env.example .env
      echo "   .env created from .env.example — set DB_* and BILLING_* in it, then re-run:  nano .env"
    fi
    grep -q "^APP_KEY=base64:" .env || php artisan key:generate --force
    # Before anything writes: can this server actually reach that MySQL database? The billing
    # database lives wherever billing-api/config.php says it does, and if the portal is being
    # deployed on a different machine than the API, DB_HOST=127.0.0.1 is simply the wrong host.
    if ! php artisan db:show >/dev/null 2>&1; then
      echo "   the database is not reachable from this host. Three things to check:"
      echo "   1. DB_HOST must name the server that HOLDS the billing database. If the portal"
      echo "      is on a different machine than the old billing-api, 127.0.0.1 is the wrong"
      echo "      host: use that server and add this one under hPanel -> Databases ->"
      echo "      Remote MySQL, or move the database here instead (QUICKXLAB_SETUP.md, A/B/C)."
      echo "   2. The user must exist for that host: GRANT on quicktap@%"
      echo "      (a user created only for localhost cannot connect remotely)."
      echo "   3. Do not keep two live copies: if you import a dump, the old host must stop"
      echo "      writing it, or the same shops will diverge within a day."
      exit 1
    fi
    if [ "$2" = migrate ]; then
      php artisan migrate --force
      php artisan db:seed --force
    else
      echo "   schema untouched (--check or --skip-migrate)"
    fi
    chmod -R 775 storage bootstrap/cache public/apk 2>/dev/null || true
    php artisan optimize:clear >/dev/null 2>&1 || true
    # Caching is an optimisation, not a requirement: a failure here must be reported,
    # not turned into a half-deployed site that `set -e` walks out on.
    ( php artisan config:cache >/dev/null && php artisan route:cache >/dev/null \
        && php artisan view:cache >/dev/null && php artisan event:cache >/dev/null ) \
      || echo "   ! a cache step failed (site still works) - run  php artisan optimize:clear  and cache by hand"
    php artisan about --only=environment 2>/dev/null | sed "s/^/   /" || true
  ' _ "$DIR" "$DO_MIGRATE" || die 'the remote step failed — its output is above. Fix that and
  re-run: every step here is safe to repeat.'
fi

# ------------------------------------------------------------------------- 6 verify
say '6/6  what must be true from outside (and the first request a till makes)'
code() { curl -k -s -o /dev/null -w '%{http_code}' --max-time 25 "$1" || echo 000; }

for pair in "GET /=/" "GET /api/=/api/" "GET /admin/login=/admin/login" \
            "GET /.env=/.env" "GET /storage/logs=/storage/logs" \
            "GET /tem/=tem/"; do
  label="${pair%%=*}"; path="${pair##*=}"
  status="$(code "$URL$path")"
  case "$path" in
    /.env|/storage/logs)
      printf '   %-22s %s (want 403/404)\n' "$label" "$status"
      if [[ "$status" == 200 ]]; then
        die "$path is readable over HTTP: fix .htaccess before any shop uses this host"
      fi
      ;;
    tem/)
      printf '   %-22s %s (the archived old site; 200 or 403 are both fine)\n' "$label" "$status"
      ;;
    *)
      printf '   %-22s %s (want 200)\n' "$label" "$status"
      if [[ "$status" == "000" ]]; then
        printf '     ! %s did not answer: DNS or the SSL certificate is not settled yet\n' "$URL"
      fi
      ;;
  esac
done

if [[ "$CHECK_ONLY" == 1 ]]; then
say '   and on the host, for the record'
  remote '
    cd "$1" && php artisan migrate:status 2>&1 | tail -5 | sed "s/^/   /"
    php artisan route:list --path=api 2>&1 | sed -n "1,6p" | sed "s/^/   /"
  ' _ "$DIR" || true
fi

say 'and the bootstrap check: the pair in the host .env against the pair in the build'
CREDS="$("${SSH[@]}" '
  cd "$1" 2>/dev/null || { echo no-dir; exit 0; }
  ID="$(grep -m1 "^BILLING_APP_ID=" .env 2>/dev/null | cut -d= -f2- | tr -dc "A-Za-z0-9_.-")"
  KEY="$(grep -m1 "^BILLING_APP_API_KEY=" .env 2>/dev/null | cut -d= -f2- | tr -dc "A-Za-z0-9_.-")"
  { [ -n "$ID" ] && [ -n "$KEY" ]; } || { echo no-creds; exit 0; }
  printf "%s %s" "$ID" "$KEY"
' _ "$DIR" 2>/dev/null || echo ssh-failed)"

case "$CREDS" in
  no-creds|no-dir|ssh-failed|'')
    printf '   ! could not read BILLING_APP_ID / BILLING_APP_API_KEY from the host .env, so\n'
    printf '     bootstrap was not tested. Without those two the app gets BAD_BUILD on every\n'
    printf '     request — check .env, and that they equal APP_ID / API_KEY in app/build.gradle.\n'
    ;;
  *)
    ID="${CREDS%% *}"; KEY="${CREDS#* }"
    ANSWER="$(curl -k -s --max-time 30 -X POST "$URL/api/v1/app/bootstrap" \
      -H 'Content-Type: application/json' -H "X-App-Id: $ID" -H "X-Api-Key: $KEY" \
      -d '{"device_id":"0123456789abcdef0123456789abcdef","app_version":"1.3.1","os_version":"deploy check"}' || echo '')"
    if printf '%s' "$ANSWER" | grep -q '"success":true'; then
      printf '   POST /api/v1/app/bootstrap  ok — a till would accept this host\n'
      printf '   %s\n' "${ANSWER:0:220}"
    else
      printf '   POST /api/v1/app/bootstrap  PROBLEM: %s\n' "${ANSWER:0:220}"
      printf '     BAD_BUILD  -> the pair in .env is not the pair in app/build.gradle\n'
      printf '     404        -> the rewrite is not active: %s/.htaccess must exist\n' "$DIR"
      printf '     500 + DB   -> DB_* in .env is wrong, or `php artisan migrate --force` failed\n'
      printf '     000        -> this machine could not reach %s (DNS/SSL still settling)\n' "$URL"
    fi
    ;;
esac

cat <<TEXT

Next (in this order — each step is why the previous one is safe):
1. ${URL}/admin  ->  Settings: api_base_url=${URL}/api/ , support_whatsapp, logo, currency,
   plans.  Licences: approve the shops that are PENDING.
2. cron:
   (crontab -l 2>/dev/null; echo '*/5 * * * * cd \$HOME/'"$DIR"' && php artisan schedule:run > /dev/null 2>&1'; \\
    echo '15 9 * * * cd \$HOME/'"$DIR"' && php artisan licence:remind --days=3 >> storage/logs/remind.log 2>&1') | crontab -
3. Existing tills move when Firebase Remote Config publishes api_base_url=${URL}/api/ —
   no new APK needed for them. The old host can stay online as a signpost on the same DB.
4. Build the APK (BOOTSTRAP_BASE_URL is already https://quickxlab.com/api/) and upload it
   through Panel -> Settings -> APK publish. Only now.
5. If the host wrote a composer.lock, commit it:
   scp -P $PORT $HOST:$DIR/composer.lock quickxlab/ && git add quickxlab/composer.lock
TEXT

exit 0
