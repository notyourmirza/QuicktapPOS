#!/usr/bin/env python3
"""Generate billing-api/openapi.json from the PHP route table.

The OpenAPI file is what makes the API usable from anywhere: Postman and Insomnia
import it, Swagger UI renders it, and openapi-generator can emit a client for the
language a desktop program is written in. Keeping it hand-written would mean it
drifts from the code within a week, so it is generated here instead — the route
table in billing-api/lib/support.php stays the single source of truth.

    python3 tools/build_openapi.py            # write billing-api/openapi.json
    python3 tools/build_openapi.py --check    # fail if the file is out of date

Notes carry the auth level and whether an active licence is required, because that is
the part every integrator has to know.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SUPPORT = ROOT / "billing-api" / "lib" / "support.php"
OUT = ROOT / "billing-api" / "openapi.json"

ROUTE_RE = re.compile(
    r"""^\s*'(?P<verb>GET|POST)\s+(?P<path>v1/[a-z0-9/_-]+)'\s*=>\s*"""
    r"""\[\s*'(?P<handler>\w+)',\s*'(?P<auth>app|user|shop)',\s*(?P<licence>true|false)\]""",
    re.MULTILINE)

# One short line per endpoint: what it is for, in integrator terms.
SUMMARY = {
    "v1/app/bootstrap": ("Start-up bootstrap", "Firebase options plus the API address to use from now on. Called once per install, from the hard-coded base URL."),
    "v1/app/config": ("Remote configuration", "App name, logo, receipt template, support WhatsApp number, sync interval and the notices. A signed-in till gets its own shop name."),
    "v1/auth/register": ("Create a shop", "Creates the shop, its owner, a PENDING licence row and the device binding in one call."),
    "v1/auth/login": ("Sign in", "Password plus device check; returns a session and the licence state."),
    "v1/auth/refresh": ("Renew the session", "Exchanges a refresh token for a new access token and rotates it."),
    "v1/auth/logout": ("End this session", "Revokes the refresh tokens of the calling device."),
    "v1/auth/me": ("Profile", "Role, name, shop and the fingerprint flag — the fields the app caches."),
    "v1/auth/unlock": ("Verify the password", "The auto-lock screen proves the password against the server."),
    "v1/auth/fingerprint": ("Fingerprint preference", "Remembers whether this cashier wants biometric unlock."),
    "v1/license/status": ("Licence state", "THE answer the whole app is built around: ACTIVE, PENDING, EXPIRED, REVOKED, SUSPENDED, BLOCKED or UNKNOWN."),
    "v1/license/verify": ("Redeem a licence key", "Activates a shop with its own key or an unused activation code. Reachable while PENDING or EXPIRED."),
    "v1/license/confirm": ("Confirm the licence", "The username + password step that finishes an activation on a device."),
    "v1/plans": ("Plan catalogue", "What the shop may buy, and what it has now, including the device seat."),
    "v1/devices": ("Tills of this shop", "Device accounting: model, versions, first/last seen, check count, and used/limit seats."),
    "v1/devices/deactivate": ("Free a device seat", "Releases this till (or a listed one) so a replacement can be linked; its sessions are revoked."),
    "v1/settings/app-name": ("Shop name override", "Per-shop app name shown on the login screen and printed on receipts."),
    "v1/notifications": ("Notices", "Active announcements for the notification bell."),
    "v1/sync/push": ("Push local changes", "The till uploads changed categories, products and bills; the server upserts by uuid."),
    "v1/sync/pull": ("Pull other devices' changes", "Everything that changed after the caller's cursor, plus shared settings."),
    "v1/shop": ("Shop profile (any client)", "Who this token belongs to: branding, currency, licence state and the token's scopes."),
    "v1/catalog": ("Catalogue (any client)", "Categories and products of the shop — the same rows the till pushes and pulls."),
    "v1/catalog/products": ("Save products", "Create or update one product, or a batch. Stock here reaches the till on its next sync."),
    "v1/catalog/categories": ("Save categories", "Create or update one category, or a batch."),
    "v1/bills": ("List bills", "Newest first, filtered by date range and free-text search over invoice, customer and items."),
    "v1/bills_save": ("Write a bill", "Placeholder"),
    "v1/reports/day": ("One day of trading", "Revenue, paid/unpaid, average bill, mix by payment method and order type, top products, hourly buckets."),
    "v1/reports/range": ("A period of trading", "The same numbers per day, for a week, a month or a quarter."),
}

# Paths whose body matters enough to describe.
BODIES = {
    "v1/auth/register": {
        "required": ["shop_name", "username", "password"],
        "properties": {
            "shop_name": {"type": "string", "maxLength": 60},
            "username": {"type": "string", "pattern": "^[a-z0-9._-]{4,32}$"},
            "password": {"type": "string", "minLength": 8},
            "full_name": {"type": "string"},
            "phone": {"type": "string", "description": "digits, optional leading +"},
            "device_name": {"type": "string"},
            "os_version": {"type": "string"},
            "app_version": {"type": "string"},
        },
    },
    "v1/auth/login": {
        "required": ["username", "password"],
        "properties": {
            "username": {"type": "string"},
            "password": {"type": "string"},
            "device_id": {"type": "string", "description": "also accepted from the X-Device-Id header"},
            "device_name": {"type": "string"},
        },
    },
    "v1/auth/refresh": {"required": ["refresh_token"], "properties": {"refresh_token": {"type": "string"}}},
    "v1/auth/unlock": {"required": ["password"], "properties": {"password": {"type": "string"}}},
    "v1/auth/fingerprint": {"required": ["enabled"], "properties": {"enabled": {"type": "boolean"}}},
    "v1/license/verify": {"required": ["license_key"], "properties": {"license_key": {"type": "string"}}},
    "v1/license/confirm": {
        "required": ["password"],
        "properties": {"username": {"type": "string"}, "password": {"type": "string"}},
    },
    "v1/devices/deactivate": {"properties": {"device_id": {"type": "string", "description": "blank = this device"}}},
    "v1/settings/app-name": {"required": ["app_name"], "properties": {"app_name": {"type": "string", "maxLength": 40}}},
    "v1/sync/push": {
        "properties": {
            "categories": {"type": "array", "items": {"$ref": "#/components/schemas/CategoryRecord"}},
            "products": {"type": "array", "items": {"$ref": "#/components/schemas/ProductRecord"}},
            "orders": {"type": "array", "items": {"$ref": "#/components/schemas/BillRecord"}},
        }
    },
    "v1/catalog/products": {
        "properties": {
            "uuid": {"type": "string", "description": "omit to create, send to update"},
            "name": {"type": "string"},
            "category_uuid": {"type": "string"},
            "price": {"type": "number", "minimum": 0},
            "barcode": {"type": "string"},
            "stock": {"type": "integer", "description": "-1 = not tracked"},
            "is_active": {"type": "integer", "enum": [0, 1]},
            "deleted_at": {"type": "string", "description": "any value = soft delete"},
            "products": {"type": "array", "items": {"type": "object"}, "description": "or a batch instead of one record"},
        }
    },
    "v1/catalog/categories": {
        "properties": {
            "uuid": {"type": "string"},
            "name": {"type": "string"},
            "sort_order": {"type": "integer"},
            "deleted_at": {"type": "string"},
            "categories": {"type": "array", "items": {"type": "object"}},
        }
    },
    "v1/bills": {
        "required": ["items"],
        "properties": {
            "uuid": {"type": "string", "description": "send the same uuid twice and the bill is replaced, not duplicated"},
            "invoice_no": {"type": "string", "description": "generated as INV-YYYYMMDD-### when omitted"},
            "order_type": {"type": "string", "enum": ["DINE_IN", "TAKE_AWAY", "DELIVERY"]},
            "table_no": {"type": "string"},
            "customer_name": {"type": "string"},
            "customer_phone": {"type": "string"},
            "customer_code": {"type": "integer"},
            "address": {"type": "string", "description": "delivery address, as the till stores it"},
            "notes": {"type": "string"},
            "status": {"type": "string", "enum": ["paid", "unpaid"]},
            "paid": {"type": "boolean"},
            "payment_method": {"type": "string", "enum": ["CASH", "CARD", "WALLET", "BANK", "CREDIT"]},
            "discount": {"type": "number", "minimum": 0},
            "discount_percent": {"type": "number", "minimum": 0, "maximum": 100},
            "tax": {"type": "number", "minimum": 0},
            "tax_percent": {"type": "number", "minimum": 0, "maximum": 100},
            "created_at": {"type": "string", "description": "epoch millis or 'YYYY-MM-DD HH:MM:SS'; default now"},
            "items": {"type": "array", "minItems": 1, "items": {"$ref": "#/components/schemas/BillLine"}},
        }
    },
}

QUERY_PARAMS = {
    "v1/sync/pull": [("since", "epoch millis; the caller's own cursor", "integer")],
    "v1/bills": [
        ("from", "YYYY-MM-DD or epoch millis (inclusive)", "string"),
        ("to", "YYYY-MM-DD or epoch millis (inclusive)", "string"),
        ("limit", "page size, max 500 (default 50)", "integer"),
        ("offset", "page offset", "integer"),
        ("q", "matches invoice no, customer name/phone and item names", "string"),
        ("include_deleted", "1 to include voided bills", "integer"),
    ],
    "v1/reports/day": [("date", "YYYY-MM-DD, default today", "string")],
    "v1/reports/range": [("from", "YYYY-MM-DD", "string"), ("to", "YYYY-MM-DD", "string")],
    "v1/catalog": [("q", "substring match inside the payload", "string"),
                   ("include_deleted", "1 to include deleted rows", "integer")],
}


def version() -> str:
    """The app's own versionName, so the description can never claim something else."""
    gradle = ROOT / "app" / "build.gradle"
    if gradle.is_file():
        match = re.search(r'versionName\s+"([^"]+)"', gradle.read_text(encoding="utf-8"))
        if match:
            return match.group(1)
    changelog = ROOT / "CHANGELOG.md"
    if changelog.is_file():
        match = re.search(r"^## (\d+\.\d+\.\d+)", changelog.read_text(encoding="utf-8"), re.MULTILINE)
        if match:
            return match.group(1)
    return "0.0.0"


def routes() -> list[tuple[str, str, str, str, bool]]:
    text = SUPPORT.read_text(encoding="utf-8")
    found = []
    for match in ROUTE_RE.finditer(text):
        found.append((match.group("verb").upper(), match.group("path"), match.group("handler"),
                      match.group("auth"), match.group("licence") == "true"))
    return found


def build() -> dict:
    document = {
        "openapi": "3.0.3",
        "info": {
            "title": "QuickTap POS billing API",
            "version": version(),
            "description": (
                "Licence, device seats, catalogue and bills for one shop, shared by the Android "
                "till, a website and a desktop program.\n\n"
                "**Two ways to authenticate.** The app sends `X-App-Id` + `X-Api-Key` and, after "
                "sign-in, `Authorization: Bearer <access_token>`. A website or desktop client sends a "
                "single `X-Api-Token: qt_pat_…` header, created per shop in the admin panel; it can be "
                "read-only.\n\n"
                "**Every answer uses one envelope:** "
                "`{\"success\":bool,\"message\":string,\"data\":mixed,\"code\":string,\"timestamp\":ms}`. "
                "Branch on `code`, show `message`. An unknown code must be treated as a refusal: the app "
                "does.\n\n"
                "**Money.** Totals of a bill written through `POST v1/bills` are calculated by the server "
                "from its lines, so no client can store an inconsistent bill. Stock of a sold product is "
                "decremented in the catalogue, and the till picks that up on its next sync."
            ),
            "contact": {"name": "QuickTap POS billing"},
            "license": {"name": "Proprietary"},
        },
        "servers": [{"url": "https://your-domain.com/api", "description": "your host; must end without a slash"}],
        "security": [{"AppHeaders": []}, {"ApiToken": []}],
        "paths": {},
        "components": {
            "securitySchemes": {
                "AppHeaders": {
                    "type": "apiKey", "in": "header", "name": "X-App-Id",
                    "description": "Build credentials: X-App-Id together with X-Api-Key.",
                },
                "ApiToken": {
                    "type": "apiKey", "in": "header", "name": "X-Api-Token",
                    "description": "A shop's `qt_pat_…` token from the admin panel (also accepted as `Authorization: Bearer qt_pat_…`).",
                },
                "PhoneSession": {
                    "type": "http", "scheme": "bearer", "bearerFormat": "JWT",
                    "description": "The access token a till received from login or register.",
                },
            },
            "schemas": {
                "Envelope": {
                    "type": "object",
                    "required": ["success", "message", "code"],
                    "properties": {
                        "success": {"type": "boolean"},
                        "message": {"type": "string", "description": "safe to show the user as-is"},
                        "code": {"type": "string", "description": "machine-readable, e.g. OK, LICENSE_EXPIRED, DEVICE_LIMIT"},
                        "data": {"nullable": True},
                        "timestamp": {"type": "integer", "format": "int64"},
                    },
                },
                "LicenceState": {
                    "type": "object",
                    "properties": {
                        "license_status": {"type": "string", "enum": ["ACTIVE", "PENDING", "EXPIRED", "REVOKED", "SUSPENDED", "BLOCKED", "UNKNOWN"]},
                        "unlocked": {"type": "boolean"},
                        "confirmed": {"type": "boolean"},
                        "duration_label": {"type": "string"},
                        "expires_at_ms": {"type": "integer", "format": "int64"},
                        "server_time_ms": {"type": "integer", "format": "int64"},
                        "days_left": {"type": "integer", "nullable": True, "description": "null = lifetime"},
                        "device_seat": {"type": "object", "properties": {"used": {"type": "integer"}, "limit": {"type": "integer"}}},
                    },
                },
                "CategoryRecord": {
                    "type": "object",
                    "properties": {
                        "uuid": {"type": "string"}, "name": {"type": "string"},
                        "sort_order": {"type": "integer"},
                        "updated_at": {"type": "string", "description": "epoch millis or 'YYYY-MM-DD HH:MM:SS'"},
                        "deleted_at": {"type": "string", "nullable": True},
                    },
                },
                "ProductRecord": {
                    "type": "object",
                    "properties": {
                        "uuid": {"type": "string"}, "category_uuid": {"type": "string"},
                        "name": {"type": "string"}, "price": {"type": "number"},
                        "barcode": {"type": "string", "nullable": True},
                        "stock": {"type": "integer", "description": "-1 = not tracked"},
                        "is_active": {"type": "integer", "enum": [0, 1]},
                        "updated_at": {"$ref": "#/components/schemas/CategoryRecord/properties/updated_at"},
                        "deleted_at": {"$ref": "#/components/schemas/CategoryRecord/properties/deleted_at"},
                    },
                },
                "BillLine": {
                    "type": "object",
                    "required": ["name", "price", "qty"],
                    "properties": {
                        "name": {"type": "string"},
                        "price": {"type": "number", "minimum": 0},
                        "qty": {"type": "integer", "minimum": 1},
                        "product_uuid": {"type": "string", "description": "send it and the product's stock is decremented"},
                        "line_total": {"type": "number", "description": "read-only echo of price × qty"},
                    },
                },
                "BillRecord": {
                    "type": "object",
                    "properties": {
                        "uuid": {"type": "string"},
                        "invoice_no": {"type": "string"},
                        "created_at": {"description": "epoch millis or 'YYYY-MM-DD HH:MM:SS'"},
                        "created_at_ms": {"type": "integer", "format": "int64"},
                        "date": {"type": "string", "format": "date"},
                        "order_type": {"type": "string"},
                        "table_no": {"type": "string"},
                        "customer_name": {"type": "string"},
                        "customer_phone": {"type": "string"},
                        "address": {"type": "string"},
                        "customer_code": {"type": "integer"},
            "address": {"type": "string", "description": "delivery address, as the till stores it"},
                        "notes": {"type": "string"},
                        "subtotal": {"type": "number"},
                        "discount": {"type": "number"},
                        "tax": {"type": "number"},
                        "total": {"type": "number"},
                        "status": {"type": "string", "enum": ["paid", "unpaid"]},
                        "payment_method": {"type": "string"},
                        "source": {"type": "string", "description": "'till' or 'api'"},
                        "items": {"type": "array", "items": {"$ref": "#/components/schemas/BillLine"}},
                    },
                },
                "Error": {"$ref": "#/components/schemas/Envelope"},
            },
            "responses": {
                "OK": {
                    "description": "Envelope with success=true; the data shape is described per endpoint.",
                    "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Envelope"}}},
                },
                "Denied": {
                    "description": "A rule refused the call. Read `code`, show `message`.",
                    "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Envelope"}}},
                },
            },
        },
    }

    seen = set()
    for verb, path, handler, auth, licence in routes():
        item = document["paths"].setdefault("/" + path, {})
        key = verb.lower()
        summary, description = SUMMARY.get(path, (path.replace("v1/", "").replace("/", " ").title(), ""))
        if path == "v1/bills" and verb == "POST":
            summary, description = SUMMARY["v1/bills_save"]
        operation = {
            "operationId": handler,
            "summary": summary,
            "tags": [path.split("/")[1] if not path.startswith("v1/sync/") else "sync"],
            "x-auth-level": auth,
            "x-requires-active-licence": licence,
            "responses": {
                "200": {"$ref": "#/components/responses/OK"},
                "401": {"$ref": "#/components/responses/Denied"},
                "403": {"$ref": "#/components/responses/Denied"},
                "409": {"$ref": "#/components/responses/Denied"},
            },
        }
        if description:
            operation["description"] = description
        if auth == "user":
            operation["security"] = [{"AppHeaders": []}, {"PhoneSession": []}]
        elif auth == "shop":
            operation["security"] = [{"ApiToken": []}, {"PhoneSession": []}]
        if licence:
            operation["description"] = (operation.get("description", "") +
                                        "\n\nNeeds an ACTIVE licence: a PENDING or expired shop gets "
                                        "`LICENSE_PENDING` / `LICENSE_EXPIRED` with HTTP 403.").strip()
        params = []
        for name, hint, kind in QUERY_PARAMS.get(path, []):
            params.append({"name": name, "in": "query", "required": False,
                           "description": hint, "schema": {"type": kind}})
        if params:
            operation["parameters"] = params
        body = BODIES.get(path)
        if body:
            operation["requestBody"] = {"required": True, "content": {"application/json": {
                "schema": {"type": "object", **body}}}}
        item[key] = operation
        seen.add((verb, path))

    # The bill write endpoint shares a path with the list endpoint, so the generator
    # has already attached the right operation; nothing else to do.
    return document


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="fail when openapi.json is out of date")
    args = parser.parse_args()

    document = build()
    rendered = json.dumps(document, indent=2, ensure_ascii=False) + "\n"
    route_count = len(document["paths"])

    if args.check:
        current = OUT.read_text(encoding="utf-8") if OUT.is_file() else ""
        if current.strip() != rendered.strip():
            print("billing-api/openapi.json is out of date — run: python3 tools/build_openapi.py")
            return 1
        print(f"OK — openapi.json matches the route table ({route_count} paths)")
        return 0

    OUT.write_text(rendered, encoding="utf-8")
    print(f"wrote {OUT.relative_to(ROOT)} with {route_count} paths, "
          f"{sum(len(v) for v in document['paths'].values())} operations")
    return 0


if __name__ == "__main__":
    sys.exit(main())
