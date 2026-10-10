# QuickTap billing — API for websites and desktop programs

The same billing engine the Android till uses, callable from anything that speaks HTTPS
and JSON: a restaurant's own website, a kiosk page, a Windows/Linux counter program, an
Excel report, a WhatsApp bot. One shop, one token, one set of records — no second
database to keep in sync, and no second licence to enforce.

This is the integration guide. The machine-readable description is
[`openapi.json`](openapi.json) (import it into Postman, Insomnia, or
`openapi-generator` for a typed client in your language). The phone↔server contract,
which this file deliberately does not repeat, is in [../LICENSE_API.md](../LICENSE_API.md).

---

## 60-second start

The shop's administrator creates a token in **panel → API access**, ticking *read* or
*read,write*. That single header is the whole authentication:

```bash
TOKEN="qt_pat_…"
API="https://your-domain.com/api"

curl -s "$API/v1/shop" -H "X-Api-Token: $TOKEN"          # who am I, may this shop bill?
curl -s "$API/v1/catalog" -H "X-Api-Token: $TOKEN"        # categories + products
curl -s "$API/v1/bills?from=$(date +%F)" -H "X-Api-Token: $TOKEN"   # today's bills
```

Writing a bill — you send lines, the server works out the money:

```bash
curl -s -X POST "$API/v1/bills" \
  -H "X-Api-Token: $TOKEN" -H "Content-Type: application/json" \
  -d '{
        "order_type": "TAKE_AWAY",
        "customer_name": "Bilal",
        "payment_method": "CASH",
        "status": "paid",
        "items": [
          {"name": "Chicken Roll", "price": 250, "qty": 2, "product_uuid": "9f2c…"},
          {"name": "Soft Drink",   "price": 90,  "qty": 1}
        ]
      }'
```

```json
{ "success": true, "message": "Bill saved.", "code": "OK",
  "data": { "bill": { "uuid": "b1d0…", "invoice_no": "INV-20251010-014",
      "subtotal": 590, "discount": 0, "tax": 0, "total": 590, "status": "paid",
      "items": [ … ], "created_at_ms": 1760050000000, "date": "2025-10-10" },
    "stock_reduced": [ { "uuid": "9f2c…", "name": "Chicken Roll", "stock": 38 } ] } }
```

That bill is in the same store the till pushes into, so **panel → Shops**, `v1/reports/*`
and any other client see it immediately. The two sold products had their stock lowered in
the catalogue, and the till pulls that on its next **Sync now**.

## Authentication, in full

| Header | Who uses it | Notes |
|---|---|---|
| `X-Api-Token: qt_pat_…` | website, desktop, script | one header, names the shop, scopes `read` / `read,write`, revocable, optionally expiring |
| `Authorization: Bearer qt_pat_…` | same | accepted for browsers that prefer it |
| `X-App-Id` + `X-Api-Key` | the Android app | the build pair from `app/build.gradle` |
| `Authorization: Bearer <jwt>` | the Android app | the till's session; also grants `v1/*` shop access for that shop |
| `X-Device-Id` | the Android app | which till is calling; a token request uses `token:<id>` instead |

There is **no** per-endpoint key, no signature, no SDK requirement. HTTPS only.

A token is a **shop key, not a session**: it reaches `v1/shop`, `v1/catalog`, `v1/bills`
and `v1/reports` and nothing else. Calling `v1/license/…`, `v1/devices/…`,
`v1/settings/…` or the sign-in endpoints with a token answers
`403 SCOPE_DENIED` — so a token that leaks off a web server cannot extend a licence,
free a device seat or lock a till out.

A read-only token can call every `GET`; a `POST` with it returns
`403 SCOPE_DENIED`, so a public pricing page can be given a token that physically
cannot bill.

## Envelope, errors, retries

Every response is `{success, message, data, code, timestamp}`. Show `message` to the
user verbatim — it is written for a shopkeeper, not for a log. Branch on `code`:

| HTTP | `code` | What to do |
|---|---|---|
| 200 | `OK` | carry on |
| 401 | `NO_TOKEN` / `TOKEN_INVALID` | the token is missing or wrong — check the header, then ask for a new one |
| 401 | `TOKEN_EXPIRED` / `TOKEN_REVOKED` | ask the administrator to re-issue |
| 403 | `SCOPE_DENIED` | this token is read-only |
| 403 | `LICENSE_EXPIRED` / `LICENSE_PENDING` / `LICENSE_REVOKED` | **the shop's licence does not allow writes** — show the message and stop billing. The same rule the phones obey. |
| 403 | `ACCOUNT_BLOCKED` | the account was blocked in the panel |
| 409 | `DEVICE_LIMIT` | all device seats are used (only relevant to a till) |
| 413 | `BATCH_TOO_LARGE` | send at most `sync_batch_limit` records per push |
| 422 | `NO_ITEMS` / `BAD_QTY` / `BAD_PRICE` / `BAD_DISCOUNT` / `BAD_RECORD` | fix the body; the message says which field |
| 500 | `SERVER_ERROR` | safe to retry; the write was not applied |

**Retrying is safe.** A bill is keyed by `uuid`: send the same `uuid` again and the
server replaces the record instead of duplicating it. Omit it and the server generates one
— so if you retry, generate the uuid yourself (a random hex string is fine). The same
applies to products and categories.

Stock follows the same rule: `product_uuid` on a line moves that product's stock by the
**difference** to what the uuid held before, so a resubmitted bill changes nothing and an
edit that drops a line gives the units back. The answer lists what moved:
`stock_reduced[] = {uuid, name, stock, delta}`. A product whose `stock` is `-1` or absent
is not tracked and is never touched.

Timestamps (`created_at`, `updated_at`, `deleted_at`) are accepted as **epoch millis** or
**`YYYY-MM-DD HH:MM:SS`** and echoed back as the sender wrote them, because the till reads
both. Dates in queries (`?from=2025-10-01`) accept both too.

## The endpoints a client needs

| Method + path | Auth | Body / query | Returns in `data` |
|---|---|---|---|
| `GET v1/shop` | token or session | – | `shop{…branding, currency, support_whatsapp}`, `licence{…}`, `token{scopes}` |
| `GET v1/catalog` | token or session | `q`, `include_deleted` | `categories[]`, `products[]`, `counts{}` |
| `POST v1/catalog/products` | write | one product, or `{products:[…]}` | `products[]` as stored |
| `POST v1/catalog/categories` | write | one category, or `{categories:[…]}` | `categories[]` |
| `GET v1/bills` | token or session | `from`, `to`, `q`, `limit`, `offset` | `bills[]` (newest first), `total` |
| `POST v1/bills` | write | lines + optional discount/tax/customer | `bill{…}`, `stock_reduced[]` |
| `GET v1/reports/day` | token or session | `date` | revenue, paid/unpaid, average, `by_payment_method`, `by_order_type`, `top_products`, `hours[24]` |
| `GET v1/reports/range` | token or session | `from`, `to` | the same plus `days[]` |
| `POST v1/sync/push`, `GET v1/sync/pull` | till session only | see LICENSE_API.md | for the phone; a shop token is refused here on purpose |

Product fields: `uuid, category_uuid, name, price, barcode, stock, is_active(0/1),
updated_at, deleted_at`. `stock = -1` means "not counted", and a bill never changes it.
Bill fields: see `BillRecord` in `openapi.json` — every one of them is optional on write
except `items`.

## From a browser

Same-origin calls need nothing special. If the page is served from a **different**
origin, put that exact origin in `billing-api/config.php`:

```php
'cors_origin' => 'https://order.example.com',
```

`OPTIONS` preflight, the custom headers and `Content-Type` are already handled. Do not set
`*` on a host with real shops on it: it lets any page that finds a leaked token use it.

The repository ships a working single-file page you can copy or hand to a designer:
[`web/counter.html`](web/counter.html) — product grid, cart, discount, print, and it bills
through `POST v1/bills`. No build step, no framework; open it, paste the base URL and the
token, it remembers them in `localStorage`.

## From Python (a counter, a kiosk, a scraper)

```python
import json, urllib.request

BASE, TOKEN = "https://your-domain.com/api", "qt_pat_…"

def call(path, payload=None):
    request = urllib.request.Request(
        BASE + path,
        data=None if payload is None else json.dumps(payload).encode(),
        headers={"X-Api-Token": TOKEN, "Content-Type": "application/json"})
    with urllib.request.urlopen(request, timeout=15) as response:
        body = json.load(response)
    if not body["success"]:
        raise RuntimeError(body["code"] + ": " + body["message"])
    return body["data"]

catalogue = call("/v1/catalog")
bill = call("/v1/bills", {
    "order_type": "DINE_IN", "table_no": "4", "status": "paid",
    "items": [{"name": n["name"], "price": n["price"], "qty": 1,
               "product_uuid": n["uuid"]} for n in catalogue["products"][:3]],
})
print(bill["bill"]["invoice_no"], bill["bill"]["total"])
```

`client/quicktap-api.py` in this repository is that helper plus paging, so a desktop
program can `from quicktap_api import QuickTap`. For C# / Java / Go / PHP, generate a
client from `openapi.json` — the schemas are complete enough to compile against.

## Receipts, printing and money rules

- The server never rounds differently from the till: `total = subtotal − discount + tax`,
  both rounded to 2 decimals, discount clamped to the subtotal (422 above it).
- Send `discount_percent`/`tax_percent` instead of absolute values and the server
  converts — so a website and the till cannot disagree about a 5 % tax.
- Print from the client. `web/counter.html` uses the browser's own print dialog with a
  58 mm/80 mm stylesheet, which is the least-maintenance path; ESC/POS printing stays a
  phone feature because that is where the printer is.

## What a web/PC bill does *not* do

The phone remains the till of record for **its own** bills: reports inside the app read
its local Room database, and `v1/sync/pull` currently delivers catalogue changes only. A
bill created from the website therefore shows up in the panel, in `v1/bills` and in
`v1/reports/*`, decrements stock that the till then pulls, and prints a receipt only where
it was created. If you need those bills visible in the app's own history, say so and we
add the reverse direction (`orders` handling in the pull) — it is a deliberate open
point, not an oversight, because writing into the till's ledger has to be done with the
printer and invoice numbering in mind.

## House rules for integrators

1. Never put a token in a URL, a screenshot, or client-side source that is public. A
   pricing page should get a **read-only** token; if it must be public, proxy it.
2. One token per program, labelled clearly (`"website"`, `"kiosk-1"`, `"accounting-sync"`),
   so the panel shows who used what and revoking never breaks a second integration.
3. Issue tokens from **panel → API access** or, over SSH, `php token.php
   --shop=<id|name> --label=website --scopes=read,write` (`--print-token` for a deploy
   script, `--list` to see who used what, `--revoke=<id>` to stop one). Never share one
   token between two programs.
4. Poll at a sane rate. `v1/reports/day` is cheap (one pass over one day); `v1/bills` with
   a wide range is not meant for a 5-second refresh loop.
5. All timestamps from `v1/*` are milliseconds; `sync_records` datetimes are server-local —
   use `created_at_ms` when you sort.
