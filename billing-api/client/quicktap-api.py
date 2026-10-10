"""QuickTap billing — a stdlib-only client for the shop API.

Desktop programs, kiosks and scripts on Windows, Linux or macOS can drop this single
file next to their code; it needs nothing from pip, so it survives a locked-down PC.

    from quicktap_api import QuickTap

    qt = QuickTap("https://your-domain.com/api", "qt_pat_…")
    for product in qt.products():
        print(product["name"], product["price"])

    qt.save_bill(items=[{"name": "Chicken Roll", "price": 250, "qty": 2}],
                 payment_method="CASH", status="paid")

Every method raises QuickTapError with the shop-facing message when the server refuses
(expired licence, read-only token, bad body) — that message is meant to be shown.
"""

from __future__ import annotations

import json
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Dict, Iterable, Iterator, List, Optional

__all__ = ["QuickTap", "QuickTapError"]
__version__ = "1.3.0"


class QuickTapError(RuntimeError):
    """A refusal from the billing server: `.code` is the machine-readable part."""

    def __init__(self, message: str, code: str = "ERROR", status: int = 0):
        super().__init__(message)
        self.code = code
        self.status = status


class QuickTap:
    def __init__(self, base_url: str, token: Optional[str] = None,
                 app_id: Optional[str] = None, api_key: Optional[str] = None,
                 device_id: Optional[str] = None, timeout: float = 15.0):
        """Token alone is enough for a shop. app_id/api_key are what the Android build
        uses; both may be sent together if you are proxying for a till."""
        if not token and not (app_id and api_key):
            raise ValueError("pass token=… (recommended) or app_id= and api_key=")
        self.base_url = base_url.rstrip("/") + "/"
        self.token = token
        self.app_id = app_id
        self.api_key = api_key
        self.device_id = device_id
        self.timeout = timeout

    # ------------------------------------------------------------------ plumbing

    def _headers(self) -> Dict[str, str]:
        headers = {"Accept": "application/json", "Content-Type": "application/json"}
        if self.token:
            headers["X-Api-Token"] = self.token
        if self.app_id:
            headers["X-App-Id"] = self.app_id
        if self.api_key:
            headers["X-Api-Key"] = self.api_key
        if self.device_id:
            headers["X-Device-Id"] = self.device_id
        return headers

    def _call(self, path: str, payload: Optional[Dict[str, Any]] = None,
              params: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        url = self.base_url + path.lstrip("/")
        if params:
            query = {k: v for k, v in params.items() if v is not None and v != ""}
            if query:
                url += "?" + urllib.parse.urlencode(query)
        data = None if payload is None else json.dumps(payload).encode("utf-8")
        request = urllib.request.Request(url, data=data, headers=self._headers(),
                                        method="POST" if data is not None else "GET")
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                raw = response.read().decode("utf-8")
                status = response.status
        except urllib.error.HTTPError as failure:      # 4xx/5xx still carry the envelope
            raw = failure.read().decode("utf-8", "replace")
            status = failure.code
        except urllib.error.URLError as failure:
            raise QuickTapError("The billing server is unreachable: %s" % failure.reason,
                                "OFFLINE", 0) from failure
        try:
            body = json.loads(raw)
        except ValueError as failure:
            raise QuickTapError("The billing server answered with something that is not "
                                "JSON (HTTP %s)." % status, "BAD_RESPONSE", status) from failure
        if not body.get("success"):
            raise QuickTapError(body.get("message") or "The billing server refused the request.",
                                body.get("code") or "ERROR", status)
        return body.get("data") or {}

    # ---------------------------------------------------------------------- shop

    def shop(self) -> Dict[str, Any]:
        """Branding, currency, the licence state and this token's scopes."""
        return self._call("v1/shop")

    def may_bill(self) -> bool:
        """False when the shop's licence is not ACTIVE — ask before printing a bill."""
        return bool(self.shop()["licence"]["unlocked"])

    # ----------------------------------------------------------------- catalogue

    def categories(self, include_deleted: bool = False) -> List[Dict[str, Any]]:
        return self._call("v1/catalog", params={"include_deleted": 1 if include_deleted else None}
                          ).get("categories", [])

    def products(self, include_deleted: bool = False) -> List[Dict[str, Any]]:
        return self._call("v1/catalog", params={"include_deleted": 1 if include_deleted else None}
                          ).get("products", [])

    def save_product(self, **fields: Any) -> Dict[str, Any]:
        data = self._call("v1/catalog/products", fields)
        return data["products"][0]

    def save_products(self, rows: Iterable[Dict[str, Any]]) -> List[Dict[str, Any]]:
        return self._call("v1/catalog/products", {"products": list(rows)})["products"]

    def save_category(self, **fields: Any) -> Dict[str, Any]:
        return self._call("v1/catalog/categories", fields)["categories"][0]

    # --------------------------------------------------------------------- bills

    def bills(self, date_from: Optional[str] = None, date_to: Optional[str] = None,
              query: Optional[str] = None, limit: int = 50, offset: int = 0
              ) -> Dict[str, Any]:
        return self._call("v1/bills", params={"from": date_from, "to": date_to, "q": query,
                                              "limit": limit, "offset": offset})

    def iter_bills(self, date_from: Optional[str] = None, date_to: Optional[str] = None,
                   page: int = 200) -> Iterator[Dict[str, Any]]:
        """Pages through a range without the caller counting offsets."""
        offset = 0
        while True:
            data = self.bills(date_from, date_to, limit=page, offset=offset)
            rows = data.get("bills", [])
            if not rows:
                return
            for row in rows:
                yield row
            if len(rows) < page:
                return
            offset += len(rows)

    def save_bill(self, items: Iterable[Dict[str, Any]], **fields: Any) -> Dict[str, Any]:
        """`items` are {name, price, qty[, product_uuid]}; totals come back from the server."""
        payload = dict(fields)
        payload["items"] = list(items)
        return self._call("v1/bills", payload)["bill"]

    # ------------------------------------------------------------------- reports

    def report_day(self, date: Optional[str] = None) -> Dict[str, Any]:
        return self._call("v1/reports/day", params={"date": date})

    def report_range(self, date_from: str, date_to: str) -> Dict[str, Any]:
        return self._call("v1/reports/range", params={"from": date_from, "to": date_to})
