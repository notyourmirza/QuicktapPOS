# quickxlab.com — Laravel portal + API on the hosting

`quickxlab/` is the new home of everything that was the plain-PHP billing API: the JSON
contract the Android till calls, the panel that sells licences, and the public QuickTap site.
One Laravel 12 app, one MySQL database, one document root.

It uses the **same database the current `billing-api/` uses**. Nothing is migrated, nothing is
renamed, nothing is deleted — the two can run side by side on the same data for as long as you
like. That is the whole rollback plan.

| | |
|---|---|
| Code | `quickxlab/` in this repo (this folder is uploaded as-is) |
| Database | the existing billing DB (`shops`, `licences`, `app_devices`, `sync_records`, …) |
| Doc root | `public_html/` — the app lives *inside* it, `public/` is where requests land |
| Old site | moved to `public_html/tem/` by you, on purpose, before upload |
| API base URL | `https://quickxlab.com/api/` (APK: `BOOTSTRAP_BASE_URL` in `app/build.gradle`) |
| Panel | `https://quickxlab.com/admin` |
| Shop login | `https://quickxlab.com/login` |

---

## 0. Paste this first — it tells you what you are working with

```bash
php -v; command -v composer && composer --version; command -v mysql; \
echo "docroot: $HOME/public_html"; ls -a "$HOME/public_html" | head -20
```

You need **PHP 8.2+** (`php -v`), **composer** (if it is missing:
`php -r "copy('https://getcomposer.org/composer-stable.phar', 'composer.phar');"` and then use
`php composer.phar` everywhere below), and the right to edit `public_html`. If `ls` shows the
live site's files, you are in the right place.

## 1. Back up the database before touching anything

```bash
mkdir -p ~/backups && mysqldump -h 127.0.0.1 -U quicktap -p --single-transaction \
  quicktap_billing > ~/backups/billing-$(date +%F-%H%M).sql && ls -lh ~/backups
```

Use the DB name/user from your current `billing-api/config.php` (`'database' => [...]`).
If the host gives you no `mysqldump`, run **cPanel → Backup Wizard → Download a MySQL
Database Backup** instead — that is the same safety net.

## 2. Move the old site into `tem/` (nothing is deleted)

```bash
cd ~/public_html && mkdir -p tem && for f in *; do [ "$f" = "tem" ] || mv -- "$f" tem/; done; \
for f in .htaccess .user.ini; do [ -f "tem/$f" ] || mv -- "$f" tem/ 2>/dev/null; done; ls -a
```

Check the old site is still readable at `https://quickxlab.com/tem/` **after step 4** (the root
`.htaccess` deliberately leaves `/tem/` alone). Keep `tem/assets/` — step 5 copies the images
the new home page uses.

## 3. Upload the app

Either **cPanel → File Manager → Upload** the contents of `quickxlab/` into `public_html/`
(zip it first, upload, then Extract inside `public_html`), or from a checkout of this repo:

```bash
git clone git@github.com:notyourmirza/QuicktapPOS.git ~/qt && cp -a ~/qt/quickxlab/. ~/public_html/ && ls ~/public_html
```

You should see `artisan`, `app`, `bootstrap`, `config`, `public`, `routes`, `storage`,
`.env.example`, `.htaccess`.

## 4. Dependencies, `.env`, key

```bash
cd ~/public_html && composer install --no-dev --optimize-autoloader --no-interaction
cp -n .env.example .env && php artisan key:generate
```

Now edit `.env` — four values must be right, everything else has a sane default:

```bash
DB_DATABASE=quicktap_billing     # the SAME database billing-api uses
DB_USERNAME=quicktap
DB_PASSWORD=***                  # from billing-api/config.php
BILLING_APP_ID=app_41f7d123706a  # must equal APP_ID in app/build.gradle
BILLING_APP_API_KEY=***          # must equal API_KEY in app/build.gradle
BILLING_JWT_SECRET=***            # php -r "echo bin2hex(random_bytes(32));"
APP_URL=https://quickxlab.com
```

`BILLING_APP_API_KEY` is the header pair (`X-App-Id`, `X-Api-Key`) every till sends. Two hosts
on one DB can share one key. If you would rather the web host have its own, add a second row to
`api_clients` (`php artisan tinker` → one `DB::table('api_clients')->insert([...])`) and put
that pair in the next APK build.

## 5. Schema, first-run rows, permissions, assets

```bash
cd ~/public_html && php artisan migrate --force && php artisan db:seed --force
```

`migrate` is **create-only**: every table is skipped when it already exists, so your shop data
is untouched. It adds the two portal tables (`site_pages`, `support_tickets`) and any column the
old API added by hand (`occurred_ms`, `released_at`, …). `db:seed` fills holes only — plans,
`app_settings`, the build client and the panel login; it will not overwrite a price you already
set, and it refuses to create an admin unless `SEED_ADMIN_PASSWORD` is at least 10 characters.

```bash
mkdir -p storage/framework/{cache/data,sessions,views} storage/logs bootstrap/cache public/apk
chmod -R 775 storage bootstrap/cache public/apk && chown -R "$USER" storage bootstrap/cache public/apk
cp -a tem/assets public/assets 2>/dev/null; ls public/assets | head
php artisan config:cache && php artisan route:cache && php artisan view:cache
```

(`chmod -R 775`, never `777`. If `php artisan storage:link` tempts you — don't: the app reads
`public/apk` directly and a `public/storage` link would expose `storage/logs`.)

## 6. Check it works — before you touch the APK

```bash
cd ~/public_html && set -a && . ./.env && set +a     # so the two headers below are your own values
curl -s https://quickxlab.com/api/ | head -c 400; echo
curl -s -X POST https://quickxlab.com/api/v1/app/bootstrap \
  -H 'Content-Type: application/json' \
  -H "X-App-Id: $BILLING_APP_ID" -H "X-Api-Key: $BILLING_APP_API_KEY" \
  -d '{"device_id":"0123456789abcdef0123456789abcdef","app_version":"1.3.1","os_version":"test"}' | head -c 600; echo
curl -sI https://quickxlab.com/ | head -3; curl -s -o /dev/null -w '%{http_code}\n' https://quickxlab.com/admin/login
```

Expected: the base-URL index (a JSON list of endpoints), then a `{"success":true,...}`
bootstrap answer, then `200` and `302`. If the second line says `"code":"BAD_BUILD"` the header
pair in `.env` is not the one in `app/build.gradle`. If the first is a 404, the rewrite is not
active: `cat .htaccess` in `public_html` and make sure `mod_rewrite` is on (cPanel hosts: it is).

Then log in to `/admin`, open **Settings** and set:

* `api_base_url` → `https://quickxlab.com/api/` (this is what repoints existing tills — see 8)
* `support_whatsapp` → your number, `app_name`, `logo_url` = `/assets/aif-logo.png`
* **Plans** — the prices in the panel are now the only source: the site, the app's store
  screen and the licence rules all read those rows.

## 7. Cron (licence reminders + cache warm)

```bash
(crontab -l 2>/dev/null; echo '15 9 * * * cd $HOME/public_html && php artisan licence:remind --days=3 >> storage/logs/remind.log 2>&1'; \
 echo '*/5 * * * * cd $HOME/public_html && php artisan schedule:run > /dev/null 2>&1') | crontab - && crontab -l | tail -2
```

`licence:remind` prints the shops due in 3 days — the desk messages them on WhatsApp. No queue
worker is needed: `QUEUE_CONNECTION=sync`, so a licence code is applied the moment it is saved.

## 8. Existing installs (no new APK needed to move them)

A till never hard-codes the API beyond one bootstrap address. It calls
`POST {BOOTSTRAP_BASE_URL}v1/app/bootstrap`, and whatever `api_base_url` the answer carries — or
Firebase Remote Config's `api_base_url` — becomes its address until it changes again. So:

1. **Remote Config (preferred, instant):** in the Firebase project, set `api_base_url` to
   `https://quickxlab.com/api/` and publish. Every install repoints within a sync interval.
2. **Or leave the old host as a signpost:** keep `billing-api/` on `aifinzo.pro` answering
   bootstrap, and set its `config.php` `api_base_url` to `https://quickxlab.com/api/`. Old tills
   ask the old host, get the new address, and never come back. Keep it running — it is also the
   fallback when the new host is unreachable.

Both hosts may point at the same database during the move; that is deliberate.

## 9. New build (the address baked into the APK)

`app/build.gradle` now ships `BOOTSTRAP_BASE_URL = "https://quickxlab.com/api/"` with
`versionCode 5` (`1.3.1`) — the compiled-in address is the *last* fallback in
`类018`'s order (Remote Config → cached last known-good → this constant), which is why moving it
is safe: an install that already resolved an address keeps using Remote Config's., then upload the APK through
**Panel → Settings → APK publish** (it writes `public/apk/` and updates the download page,
`min_app_version` and the release notes in one click).

## 10. Rollback (also nothing-destructive)

```bash
mkdir -p ~/app-off && cd ~/public_html && for f in * .htaccess; do [ "$f" = "tem" ] || mv -- "$f" ~/app-off/ 2>/dev/null; done
cd tem && for f in *; do mv -- "$f" ~/public_html/; done; ls ~/public_html | head
```

…then restore the DB only if you changed rows:
`mysql -h 127.0.0.1 -U quicktap -p quicktap_billing < ~/backups/billing-DATE.sql`.
The old `billing-api/` never stopped working, so the app and the panel come back with it.

## 11. Credentials: what you must create (and what you don't)

| Need it? | What | Where it lives |
|---|---|---|
| ✅ | MySQL DB + user (the **existing** billing DB) | `.env` `DB_*` |
| ✅ | `BILLING_APP_ID` / `BILLING_APP_API_KEY` — the build pair the till sends (`X-App-Id`, `X-Api-Key`) | `.env`, `app/build.gradle` (`APP_ID`, `API_KEY`) and one row in `api_clients` |
| ✅ | `BILLING_JWT_SECRET` — 64 hex chars, signs till sessions | `.env` only (rotating it signs everyone out) |
| ✅ | Panel login (`admin_users`) | `.env` `SEED_ADMIN_PASSWORD` once, or `php artisan billing:seed-panel --admin-user=…` |
| ⬜ | Firebase project (push + Remote Config) | already created for the app; **needed** for step 8.1 |
| ⬜ | Google OAuth (Android + Web clients), Drive scope | app-side only; the website does not sign in with Google |
| ⬜ | `FIREBASE_*` in `.env` | optional — only what `/v1/app/config` echoes back to the till |
| ⬜ | SMTP | optional; `MAIL_MAILER=log` writes reminders to the log instead |
| ❌ | Stripe/JazzCash/EasyPaisa API keys | none. Payment stays manual (Raast/bank), the panel sells codes — that is why no new key is ever needed to sell a licence |
| ❌ | API key for `/api/v1/*` | none. The till uses the build pair + its JWT; websites use `qt_pat_…` shop tokens |

## What the panel does (so nothing feels missing)

`Dashboard` (who expires, what was billed, open tickets) · `Shops` (search by shop or username,
licence history, extend/lifetime/revoke, block/suspend, plan, currency, printed name, owner
password reset, audit trail per shop) · `Devices & seats` (every till, free a seat, block) ·
`Licence codes` (batch-generate, attach one to a shop directly) · `Plans` (price, yearly price,
seats, feature lines — the app reads the same rows) · `API tokens` (per shop, `read`/`write`,
expiry, shown once, revoked from here or `/account`) · `Bills` (any shop, any range, `q=`, CSV
export) · `Revenue` (per-day totals) · `Activity` (the audit log) · `Pages`, `Notices`,
`Settings & APK`, `Support queue`.

`/privacy` and `/terms` are real pages (what the server keeps, what a licence means). A
`site_pages` row with the slug `privacy` or `terms` overrides them, so legal copy is a panel
edit too — but say so out loud before you change it: the built-in text describes what this code
actually does.

A shop owner sees a smaller version of the same thing at `/account`: their licence, their seats
(with self-service "free seat" for a lost phone), today's total, and their own tokens. They can
never extend themselves, and no page of theirs can write another shop's row — `shop_id` comes
from the session or the token, never from the request.
