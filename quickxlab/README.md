# quickxlab — the portal, the panel and the API

One Laravel 12 application in place of three things: the QuickXLab public site, the billing
admin panel, and the JSON API the Android till (and any website or desktop program with a shop
token) talks to. One MySQL database — the one the till already fills.

`../QUICKXLAB_SETUP.md` is the runbook for the host (upload, `.env`, `migrate`, `db:seed`,
verification, rollback). This file is the map of the code.

## Layout

```
app/
  Http/Controllers/
    ApiController.php        the JSON envelope, input helpers, the shared `ctx`
    SiteController.php       home / plans / software / download / support / signup / developers
    AccountController.php    a shop owner's own screen: licence, seats, tokens, printed name
    Admin/                   Auth, Dashboard, Shop, Device, Billing, Token, Report, Content
    Api/                     PhoneController (bootstrap, auth, licence), SyncController,
                             ShopController (catalog, bills, reports — token or till session)
  Http/Middleware/           ApiLevel (`api.level:app|user|shop[:licence]`), EnsureAdmin,
                             EnsureShopUser
  Support/
    Billing.php              the whole engine: licence state machine, seats, keys, tokens,
                             records store, bill maths, invoice numbering, audit, settings
    Gate.php                 who is calling — build creds, a till's JWT or a `qt_pat_…` token
    ApiException.php          a refusal with an HTTP status and a machine-readable `code`
    helpers.php               the Blade formatters (money, ago, state pills, endpoint_help)
config/billing.php           the deployment knobs (.env), mirrored in .env.example
database/migrations/         create-only, `Schema::hasTable`-guarded — see "Schema" below
resources/views/             layouts/site, layouts/admin, site/*, account/*, admin/*
routes/                      api.php (the v1 contract), web.php (site + /account + /admin),
                             console.php (licence:remind, billing:seed-panel, api:token)
public/                      index.php, css/, js/, assets/, apk/  ← what the doc root serves
```

## Rules the code keeps

* **No Eloquent models.** Every read and write is `DB::table(...)` against the tables the
  plain-PHP API created, because the column names are the contract — the till, the old panel
  and `billing-api/lib/schema.php` all agree on them, and a model would be a second opinion.
* **`billing-api/lib/schema.php` is the schema authority.** The migrations copy it table for
  table; if a column is added there, it is added as a guarded `ADD COLUMN` in
  `2024_01_01_000200_add_columns_the_old_api_made.php`.
* **`shop_id` never comes from a request body.** It comes from the token or the till's session,
  which is what makes one token unable to read another shop.
* **Answers always use the envelope** `{success, message, data, code, timestamp}`, `data`
  always an object, `Cache-Control: no-store`. `bootstrap/app.php` renders exceptions into it
  too, so a till never sees HTML and never guesses.
* **A licence decision is made in one place** (`Billing::state()`), and `payload()` emits
  exactly the keys `../../LICENSE_API.md` documents — `tools/check_laravel_mirror.py` fails the
  build if that set drifts by one key.
* **Nothing deletes business history.** Blocking a shop revokes sessions; disabling a user
  sets `is_active = 0`; unpublishing a page sets `is_active = 0`; `migrate`'s `down()` and the
  seeders refuse to drop.

## Routes

```bash
php artisan route:list --path=api      # the 26 v1 endpoints, with their auth level per route
php artisan route:list --path=admin
```

`GET /api/` answers the endpoint list as JSON — the fastest way to see whether the app, the
router and the database are all awake.

## Commands

```bash
php artisan billing:seed-panel --admin-user=owner --admin-pass='long-random'
php artisan api:token 3 --label=kiosk --scopes=read,write --days=30
php artisan api:token --revoke=7
php artisan licence:remind --days=3
```

## Checks (run these in the repo root, no PHP or Gradle needed)

```bash
python3 tools/check_laravel_mirror.py   # routes↔controllers, view/route/helper existence,
                                        # Blade balance, envelope + licence keys, imports,
                                        # middleware aliases, and parity with openapi.json
python3 tools/check_api_contract.py     # billing-api ↔ app call sites (the old host stays true)
python3 tools/build_openapi.py --check  # openapi.json still matches the route table
```

The first is the one that matters here: it is what catches a typo'd `route('admin.shop.act')`
or a `DB::` without its `use` line in a sandbox where PHP cannot be run.
