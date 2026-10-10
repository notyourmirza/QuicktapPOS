#!/usr/bin/env python3
"""Static checks for quickxlab/ — the Laravel port of billing-api.

There is no PHP runtime in this sandbox, so a wrong method name, a typo'd route name or a
missing view is caught here instead of on the host at 1 a.m. Six passes:

1. every `Route::` in quickxlab/routes/{api,web}.php points at a controller method that exists
2. every path in billing-api/openapi.json is served by the Laravel API router (the port must
   not lose an endpoint the APK already calls)
3. every `view('a.b')` resolves to a .blade.php file, and every `route('name')` in a Blade
   file exists in the route table
4. every helper called in Blade is defined in app/Support/helpers.php
5. Blade block directives balance (@foreach/@endforeach, @if/@endif, ...)
6. the JSON envelope and the licence-state keys still match LICENSE_API.md / the old API

Usage: python3 tools/check_laravel_mirror.py            # quiet on success
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "quickxlab"
API = ROOT / "billing-api"

ROUTE_RE = re.compile(
    r"Route::(get|post|put|patch|delete)\(\s*'([^']*)'\s*,\s*\[([A-Za-z0-9_\\]+)::class\s*,\s*'([^']+)'\]"
)
GROUP_PREFIX_RE = re.compile(r"Route::prefix\(\s*'([^']+)'")
VIEW_RE = re.compile(r"""view\(\s*'([a-z0-9_.]+)'""")
BLADE_ROUTE_RE = re.compile(r"""route\(\s*'([a-z0-9_.\-]+)'""")
PHP_CALL_IN_BLADE_RE = re.compile(r"([a-z_][a-z0-9_]*)\s*\(")
METHOD_RE = re.compile(r"function\s+(\w+)\s*\(")

#: the app may only ever see these keys in a licence payload (LICENSE_API.md)
LICENSE_KEYS = {
    "license_status", "account_status", "unlocked", "confirmed", "request_status",
    "duration_label", "expires_at_ms", "server_time_ms", "server_time", "days_left",
    "plan", "device_seat",
}
ENVELOPE_KEYS = {"success", "message", "data", "code", "timestamp"}

failures: list[str] = []


def fail(message: str) -> None:
    failures.append(message)


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8") if path.is_file() else ""


def strip_php(source: str) -> str:
    """Comments out — a `Route::get` written in prose must not read as a route. Strings stay
    intact, because the paths and route names are exactly what the checks compare."""
    source = re.sub(r"/\*.*?\*/", "", source, flags=re.S)
    source = re.sub(r"\n\s*//[^\n]*", "", source)
    return source


def controller_class(short: str, source: str) -> Path | None:
    """`[ShopController::class, 'x']` -> quickxlab/app/Http/Controllers/**/ShopController.php."""
    use = re.search(r"use\s+(App\\Http\\Controllers[A-Za-z0-9_\\]*\\%s);" % short, source)
    if use:
        relative = use.group(1).replace("App\\", "app/").replace("\\", "/") + ".php"
        path = APP / relative
        return path if path.is_file() else None
    matches = list((APP / "app/Http/Controllers").rglob(f"{short}.php"))
    return matches[0] if len(matches) == 1 else None


# ---------------------------------------------------------------- 1 + 2: routes

#: routes/api.php registers through a local helper, so both shapes are parsed.
ON_RE = re.compile(
    r"\$on\(\s*'(\w+)',\s*'([^']+)'\s*,\s*([A-Za-z0-9_\\]+)::class\s*,\s*'(\w+)'\s*,\s*'(\w+)'\s*(,\s*true)?\s*\)")


def walk_groups(source: str):
    """Yield (path, name, controller, method) with the group prefixes/names applied.

    Laravel groups nest as `Route::prefix('x')->name('y.')->group(function () { ... });`, so
    a brace-depth stack is enough to know which prefix a line sits inside — the same idea the
    router uses, which is what makes the names collected here the names `route()` resolves.
    `routes/api.php` registers through a local `$on()` helper instead, and both shapes are
    understood: one parser that knows only `Route::get(...)` would silently "pass" while the
    real router had 26 endpoints to serve.
    """
    stack = [{"path": "", "name": "", "depth": -1}]
    depth = 0
    for line in source.splitlines():
        prefix = re.search(r"Route::prefix\(\s*'([^']*)'", line)
        named = re.search(r"^Route::name\(\s*'([^']*)'\s*\)->group", line) or \
            (re.search(r"->name\(\s*'([^']+)'\s*\)->group", line) if "Group" in line or "->group" in line else None)
        if prefix or (named and "->group" in line):
            stack.append({
                "path": stack[-1]["path"] + (prefix.group(1).strip("/") + "/" if prefix else ""),
                "name": stack[-1]["name"] + (named.group(1) if named else ""),
                "depth": depth,
            })

        name = ""
        found = re.search(r"->name\(\s*'([^']+)'", line)
        if found:
            name = stack[-1]["name"] + found.group(1)

        match = ROUTE_RE.search(line)
        if match:
            verb, path, controller, method = match.groups()
            yield (verb.upper(), stack[-1]["path"] + path.strip("/"), name, controller, method)

        on = ON_RE.search(line)
        if on:
            verb, on_path, controller, method, level, licence = on.groups()
            # The helper prefixes 'v1/' inside the closure, so the public path is one the
            # checker has to rebuild rather than read off the line.
            yield (verb.upper(), "api/v1/" + on_path, "api." + on_path.replace("/", "."),
                   controller, method)
            yield ("LEVEL", "v1/" + on_path, level + (":licence" if licence else ""), "", "")

        depth += line.count("{") - line.count("}")
        while len(stack) > 1 and depth <= stack[-1]["depth"]:
            stack.pop()


def route_table() -> dict[str, tuple[str, str]]:
    """path -> (name, 'Controller::method') for both route files."""
    table: dict[str, tuple[str, str]] = {}
    for file in ("routes/api.php", "routes/web.php"):
        # GET /signup and POST /signup are two routes with two names: the verb is part of
        # the key, or the second one quietly erases the first.
        for verb, path, name, controller, method in walk_groups(strip_php(read(APP / file))):
            if verb == "LEVEL":
                continue
            table[f"{verb} {path}"] = (name, f"{controller}::{method}")
    return table


def api_levels() -> dict[str, str]:
    """api path -> the declared auth level, straight from the route file."""
    levels: dict[str, str] = {}
    for verb, path, extra, _c, _m in walk_groups(strip_php(read(APP / "routes/api.php"))):
        if verb == "LEVEL":
            levels[path] = extra
    return levels


def route_names() -> set[str]:
    return {name for name, _ in route_table().values() if name}


def check_routes() -> int:
    count = 0
    for key, (name, target) in route_table().items():
        path = key.split(" ", 1)[1]
        count += 1
        short = target.split("::")[0].split("\\")[-1]
        file = controller_class(short, read(APP / ("routes/api.php" if path.startswith("api/") else "routes/web.php")))
        if file is None:
            fail(f"no controller file for {target} (route {path})")
            continue
        method = target.split("::")[1]
        if method not in set(METHOD_RE.findall(read(file))):
            fail(f"{file.relative_to(APP)}: {method}() is not defined (route {path}{', ' + name if name else ''})")
        if path.startswith("api/") and not api_levels().get(path[len("api/"):]):
            fail(f"route {path} has no auth level — every endpoint must declare app/user/shop")
    if count < 30:
        fail(f"only {count} routes parsed from routes/*.php — the parser or the router changed")
    return count


def check_openapi_parity() -> tuple[int, int]:
    spec_path = API / "openapi.json"
    if not spec_path.is_file():
        fail("billing-api/openapi.json is missing — the parity check is the point of this file")
        return 0, 0
    spec = json.loads(read(spec_path))
    want = {path.lstrip("/") for path in spec.get("paths", {})}
    have = {key.split(" ", 1)[1][len("api/"):] for key in route_table() if " api/" in key}

    missing = sorted(want - have)
    if missing:
        fail(f"the Laravel API does not serve {len(missing)} path(s) the APK calls: {', '.join(missing)}")
    return len(want) - len(missing), len(have - want)


# ---------------------------------------------------------------- 3: views

def check_views() -> int:
    count = 0
    for path in sorted((APP / "app").rglob("*.php")):
        source = strip_php(read(path))
        for name in VIEW_RE.findall(source):
            count += 1
            file = APP / "resources/views" / (name.replace(".", "/") + ".blade.php")
            if not file.is_file():
                fail(f"{path.relative_to(APP)} renders '{name}' but {file.relative_to(APP)} does not exist")
    return count


def check_blade() -> None:
    names = route_names()
    helpers = set(re.findall(r"function (\w+)\(", read(APP / "app/Support/helpers.php")))
    laravel_builtins = {
        "strtotime", "str_replace", "end", "reset", "array_slice", "array_values", "array_keys",
        "array_merge", "array_filter", "array_map", "array_sum", "array_column", "array_reverse",
        "array_unique", "is_array", "is_numeric", "is_string", "max", "min", "sort", "usort",
        "htmlspecialchars", "ucfirst", "strtolower", "strtoupper", "nl2br", "number_format",
        "e", "url", "asset", "route", "view", "old", "csrf_token", "config", "date",
        "request", "session", "number_format", "str_repeat", "strlen", "count", "max",
        "min", "implode", "explode", "trim", "strtoupper", "strtolower", "nl2br", "round",
        "array_column", "array_reverse", "array_sum", "is_int", "int", "float", "empty",
        "preg_replace", "substr", "rawurlencode", "urlencode", "sprintf", "in_array",
        "collect", "value", "optional", "abort", "back", "bcrypt", "str_contains",
        "str_starts_with", "selected", "checked", "endif", "endforeach", "endforelse",
        "endisset", "endunless", "endphp", "endauth", "endguest", "endcan", "json",
    }
    views = sorted((APP / "resources/views").rglob("*.blade.php"))
    for path in views:
        text = read(path)
        relative = path.relative_to(APP)

        for target in re.findall(r"route\(\s*'([a-z0-9_.\-]+)'", text):
            if target not in names:
                fail(f"{relative}: route('{target}') is not defined in routes/*.php")

        # @if/@foreach/... must balance, or the page dies at render time on a cold cache.
        for opener, closer in (("@foreach", "@endforeach"), ("@if", "@endif"),
                               ("@forelse", "@endforelse"), ("@section", "@endsection"),
                               ("@php", "@endphp")):
            if opener == "@section":
                # `@section('title', 'x')` needs no @endsection; `@section('content')` does.
                opens = len(re.findall(r"@section\s*\('[^']*'\s*\)", text))
                closes = text.count("@endsection")
            elif opener == "@php":
                opens = len(re.findall(r"@php\b", text))
                closes = text.count("@endphp")
            else:
                opens = len(re.findall(re.escape(opener) + r"\s*\(", text))
                closes = text.count(closer)
            if opens != closes:
                fail(f"{relative}: {opens} {opener} vs {closes} {closer}")

        # Every function called from a Blade echo must exist somewhere.
        for chunk in re.findall(r"\{\{(.*?)\}\}", text, flags=re.S):
            chunk = re.sub(r"'(?:\\.|[^'\\])*'", "''", chunk)
            for match in re.finditer(r"(?<![A-Za-z0-9_$\\])([a-z_][a-z0-9_]*)\s*\(", chunk):
                name = match.group(1)
                if chunk[:match.start()].endswith(("->", "::")):
                    continue
                if name in laravel_builtins or name in helpers:
                    continue
                if name in ("array", "list", "isset", "unset", "print", "echo", "new", "return"):
                    continue
                fail(f"{relative}: Blade calls undefined helper '{name}()'")

    if not (APP / "public/css/quicktap.css").is_file():
        fail("layouts reference css/quicktap.css but public/css/quicktap.css is missing")
    if not (APP / "public/js/quicktap.js").is_file():
        fail("layouts reference js/quicktap.js but public/js/quicktap.js is missing")


# ---------------------------------------------------------------- 4: contract

def check_contract() -> None:
    billing = read(APP / "app/Support/Billing.php")
    payload = re.search(r"public static function payload\(array \$state\).*?\n    \}", billing, flags=re.S)
    if not payload:
        fail("Billing::payload() is gone — the app's licence screen has nothing to read")
        return
    # Only the outer array's keys belong to the contract; `plan` and `device_seat` nest.
    keys = set(re.findall(r"^            '([a-z_]+)'\s*=>", payload.group(0), flags=re.M))
    if keys != LICENSE_KEYS:
        fail(f"Billing::payload() keys drifted from LICENSE_API.md: "
             f"missing {sorted(LICENSE_KEYS - keys)}, extra {sorted(keys - LICENSE_KEYS)}")

    controller = read(APP / "app/Http/Controllers/Api/ApiController.php")
    envelope = set(re.findall(r"'(success|message|data|code|timestamp)'\s*=>", controller))
    if not envelope <= ENVELOPE_KEYS:
        fail(f"ApiController puts unexpected keys in the envelope: {sorted(envelope - ENVELOPE_KEYS)}")
    if envelope != ENVELOPE_KEYS:
        fail(f"ApiController envelope is missing {sorted(ENVELOPE_KEYS - envelope)}")

    # The status a till can force-expire on must be the word the app understands.
    if '"EXPIRED"' not in billing and "'EXPIRED'" not in billing:
        fail("Billing no longer produces the EXPIRED state the app's forced page listens for")

    for snippet, why in (
        ("X-Api-Key", "build credentials must be read from headers"),
        ("qt_pat_", "personal access tokens must be recognised"),
    ):
        if snippet not in read(APP / "app/Support/Gate.php"):
            fail(f"Gate.php lost '{snippet}' — {why}")


def check_files_on_disk() -> None:
    for relative in (
        "artisan", "composer.json", "bootstrap/app.php", "bootstrap/providers.php",
        "public/index.php", ".env.example", "config/billing.php", "routes/api.php",
        "routes/web.php", "routes/console.php", "app/Support/Billing.php",
        "app/Support/Gate.php", "app/Support/helpers.php", "app/Support/ApiException.php",
        "database/migrations/2024_01_01_000000_create_billing_tables.php",
        "database/seeders/DatabaseSeeder.php",
    ):
        if not (APP / relative).is_file():
            fail(f"quickxlab/{relative} is missing")
    for directory in ("storage/framework/views", "storage/logs", "bootstrap/cache", "public/apk"):
        if not (APP / directory).is_dir():
            fail(f"quickxlab/{directory} must exist (Laravel writes there; it is gitignored, not absent)")


# ---------------------------------------------------------------- 5: PHP sanity

#: classes a file may reference through the short name only if it imports them
IMPORTED = {
    "DB": "Illuminate\\Support\\Facades\\DB",
    "Schema": "Illuminate\\Support\\Facades\\Schema",
    "Hash": "Illuminate\\Support\\Facades\\Hash",
    "View": "Illuminate\\Support\\Facades\\View",
    "Route": "Illuminate\\Support\\Facades\\Route",
    "Str": "Illuminate\\Support\\Str",
    "Artisan": "Illuminate\\Support\\Facades\\Artisan",
    "Cache": "Illuminate\\Support\\Facades\\Cache",
    "Log": "Illuminate\\Support\\Facades\\Log",
    "Throwable": None,          # global
    "JsonResponse": "Illuminate\\Http\\JsonResponse",
    "RedirectResponse": "Illuminate\\Http\\RedirectResponse",
    "Request": "Illuminate\\Http\\Request",
    "Response": "Symfony\\Component\\HttpFoundation\\Response",
    "StreamedResponse": "Symfony\\Component\\HttpFoundation\\StreamedResponse",
    "UploadedFile": "Illuminate\\Http\\UploadedFile",
    "Closure": None,            # global
    "View": None,               # Illuminate\View\View or the returned type; both imported below
    "LengthAwarePaginator": "Illuminate\\Pagination\\LengthAwarePaginator",
    "Model": "Illuminate\\Database\\Eloquent\\Model",
    "Migration": "Illuminate\\Database\\Migrations\\Migration",
    "Seeder": "Illuminate\\Database\\Seeder",
    "ApiException": "App\\Support\\ApiException",
    "Billing": "App\\Support\\Billing",
    "Gate": "App\\Support\\Gate",
    "ServiceProvider": "Illuminate\\Support\\ServiceProvider",
}


def code_only(source: str) -> str:
    """Comments and string literals out — balance checks must not count braces inside copy."""
    out, i, n = [], 0, len(source)
    while i < n:
        ch = source[i]
        if ch == "/" and source[i:i + 2] == "//":
            j = source.find("\n", i)
            i = n if j < 0 else j
            continue
        if ch == "#":
            j = source.find("\n", i)
            i = n if j < 0 else j
            continue
        if ch == "/" and source[i:i + 2] == "/*":
            j = source.find("*/", i + 2)
            i = n if j < 0 else j + 2
            continue
        if ch in ("'", '"'):
            quote, i = ch, i + 1
            while i < n:
                if source[i] == "\\":
                    i += 2
                    continue
                if source[i] == quote:
                    i += 1
                    break
                i += 1
            out.append(quote + quote)
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def check_php_files() -> None:
    """Balance and import sanity for every PHP file the app ships.

    No PHP runtime here, and the failures that bite hardest on a shared host are the ones
    PHP only reports at load time: an unbalanced brace in a heredoc, a `DB::` with no
    `use` line, a `catch (Throwable)` in a file that never imported Throwable."""
    app_php = read(APP / "bootstrap/app.php")

    for path in sorted((APP / "app").rglob("*.php")) + sorted((APP / "routes").glob("*.php")) \
            + sorted((APP / "config").glob("*.php")) + sorted((APP / "bootstrap").glob("*.php")) \
            + sorted((APP / "database").rglob("*.php")) + [APP / "public/index.php", APP / "artisan"]:
        if not path.is_file():
            continue
        raw = read(path)
        source = code_only(raw)
        relative = path.relative_to(APP)

        for opener, closer in (("{", "}"), ("(", ")"), ("[", "]")):
            if source.count(opener) != source.count(closer):
                fail(f"{relative}: {source.count(opener)} '{opener}' vs {source.count(closer)} "
                     f"'{closer}' — unbalanced")

        # `use App\Support\Billing;` imports the LAST segment — that is the name to match.
        imported = set()
        for match in re.finditer(r"^\s*use\s+([A-Za-z0-9_\\]+)(?:\s+as\s+(\w+))?;", source, flags=re.M):
            imported.add(match.group(2) or match.group(1).split("\\")[-1])

        own = re.search(r"^\s*namespace\s+([A-Za-z0-9_\\]+);", source, flags=re.M)
        own_ns = own.group(1) if own else ""
        body = re.sub(r"^\s*use\s+[^;]+;", "", source, flags=re.M)
        for short, full in sorted(set(IMPORTED.items())):
            if not re.search(r"(?<![A-Za-z0-9_$\\:])" + re.escape(short) + r"::", body):
                continue
            if short in imported:
                continue
            # `App\Support\Billing` calling `ApiException::` needs no import: same namespace.
            if full and own_ns and full.rsplit("\\", 1)[0] == own_ns:
                continue
            if re.search(r"\\" + re.escape(short) + r"::", body):
                continue   # written fully qualified on purpose
            hint = f" (add `use {full};`)" if full else ""
            fail(f"{relative}: uses {short}:: without importing it{hint}")

        lines = raw.split("\n")
        if lines and lines[0].startswith("#!"):
            lines = lines[1:]
        if path.suffix == ".php" and (not lines or not lines[0].startswith("<?php")):
            fail(f"{relative}: a .php file must start with `<?php`")
        if not raw.endswith("\n"):
            fail(f"{relative}: no trailing newline")

    # Middleware aliases the route files use must be registered in bootstrap/app.php.
    block = re.search(r"->alias\(\[(.*?)\]\s*\)\s*;", app_php, flags=re.S)
    aliases = set(re.findall(r"'([a-z0-9_.\-]+)'\s*=>", block.group(1))) if block else set()
    if not aliases:
        fail("bootstrap/app.php registers no middleware aliases at all")
    used = set()
    for file in ("routes/api.php", "routes/web.php"):
        text = read(APP / file)
        used |= set(re.findall(r"middleware\(\s*'([a-z0-9_.\-]+)", text))
    for middle in sorted(used):
        head = middle.split(":")[0]
        if head in ("throttle", "web", "api", "auth", "guest", "verified"):
            continue
        if head not in aliases:
            fail(f"routes use middleware '{middle}' which is not aliased in bootstrap/app.php")
    for alias in ("api.level", "admin", "account"):
        if alias not in aliases:
            fail(f"bootstrap/app.php does not alias '{alias}' — the route file depends on it")

    # Gate and Billing methods the rest of the app calls must exist.
    for support, label in (("app/Support/Gate.php", "Gate"), ("app/Support/Billing.php", "Billing")):
        text = read(APP / support)
        have = set(re.findall(r"function\s+(\w+)\s*\(", text))
        callers = list((APP / "app").rglob("*.php")) + list((APP / "routes").rglob("*.php")) \
            + list((APP / "database").rglob("*.php"))
        wanted = {match.group(1) for file in callers for match in
                  re.finditer(label + r"::(\w+)\(", read(file))}
        for name in sorted(wanted - have):
            fail(f"{label}::{name}() is called but not defined in {support}")

    # ApiException is the only way a handler refuses a request; it must carry a status.
    api_exception = read(APP / "app/Support/ApiException.php")
    if "status" not in api_exception or "errorCode" not in api_exception \
            or "never" not in api_exception:
        fail("ApiException lost ->$status / ->$errorCode — bootstrap/app.php renders the "
             "envelope from them, and handlers refuse through deny()")

def main() -> int:
    if not APP.is_dir():
        print("quickxlab/ not found — nothing to check")
        return 0

    check_files_on_disk()
    served = check_routes()
    wanted, extras = check_openapi_parity()
    views = check_views()
    check_blade()
    check_contract()
    check_php_files()

    if failures:
        for message in failures:
            print(f"FAIL  {message}")
        print(f"\n{len(failures)} problem(s) in quickxlab/.")
        return 1

    print(f"OK — {served} Laravel routes wired, {wanted} API paths match openapi.json "
          f"({extras} extra), {views} rendered views, Blade helpers and envelope intact.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
