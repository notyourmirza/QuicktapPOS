#!/usr/bin/env python3
"""Static contract check for billing-api/ (the PHP backend) — no PHP needed.

The sandbox has no `php` binary, so this script is what stands in for a build:
it reads the app's HTTP call sites, the PHP route table, the handler and helper
function definitions, and the table names inside every SQL string, and reports
where the two sides disagree.

    python3 tools/check_api_contract.py            # full check
    python3 tools/check_api_contract.py -v         # also list every call site

Exit code 0 = contract holds. Anything printed under FAIL must be fixed.
"""

from __future__ import annotations

import argparse
import re
import sys
from collections import defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
JAVA = ROOT / "app" / "src" / "main" / "java"
API = ROOT / "billing-api"
LIB = API / "lib"
ADMIN = API / "admin"

FUNCTION_RE = re.compile(r"^\s*function\s+([a-z_][a-z0-9_]*)\s*\(", re.MULTILINE)
CALL_RE = re.compile(r"(?<![\w$])\b(qt_[a-z0-9_]*)\s*\(")
ROUTE_ROW_RE = re.compile(r"""^\s*'(?P<verb>GET|POST)\s+(?P<path>v1/[a-z0-9/_-]+)'\s*=>\s*\[\s*'(?P<handler>\w+)'""", re.MULTILINE)
JAVA_CALL_RE = re.compile(r"类016\.(?P<verb>get|post)\(\s*[^,]+,\s*\"(?P<path>[^\"]+)\"")
# The bootstrap fetch is the one raw HttpURLConnection in the app, so it is matched
# apart: <base> + "v1/app/bootstrap".
JAVA_URL_RE = re.compile(r"new URL\([^;]*?\+\s*\"(?P<path>v1/[^\"]+)\"")
JAVA_LITERAL_RE = re.compile(r"\"(?P<path>v1/[a-z0-9/_-]+)\"")
# Only uppercase keywords inside an SQL-looking string: prose that mentions
# "from the app" must not be read as a table reference.
TABLE_RE = re.compile(r"""\b(?:FROM|JOIN|INTO|UPDATE)\s+`?(?P<table>[a-z_]{3,})`?\b""")
SQL_STRING_RE = re.compile(r"""(['"])(?P<body>(?:[^'"\\]|\\.)*?(?:SELECT|INSERT INTO|UPDATE |DELETE FROM)(?:[^'"\\]|\\.)*?)\1""")
SCHEMA_TABLE_RE = re.compile(r"^\s{8}'(?P<table>[a-z_]+)'\s=>\s\[$", re.MULTILINE)
CONFIG_KEY_RE = re.compile(
    r"\$config\[\s*'(?P<key>[a-z_0-9]+)'\s*\]|\['config'\]\[\s*'(?P<key2>[a-z_0-9]+)'\s*\]")
EXAMPLE_KEY_RE = re.compile(r"^\s{4}'(?P<key>[a-z_0-9]+)'\s=>", re.MULTILINE)

PHP_BUILTINS = {
    # things that look like qt_* calls but are language/array syntax
    "qt_",
}


def strip_php(code: str, keep_strings: bool = True) -> str:
    """Blank comments and heredocs; strings are kept (route paths and SQL live in
    them) unless keep_strings is false, which is what the brace checker wants."""
    out = []
    i, n = 0, len(code)
    while i < n:
        ch = code[i]
        two = code[i:i + 2]
        if two == "//" or ch == "#":
            j = code.find("\n", i)
            j = n if j == -1 else j
            out.append(" " * (j - i))
            i = j
        elif two == "/*":
            j = code.find("*/", i + 2)
            j = n if j == -1 else j + 2
            out.append(" " * (j - i))
            i = j
        elif code[i:i + 3] == "<<<":
            match = re.match(r"<<<\s*([\"']?)(\w+)\1", code[i:])
            if not match:
                out.append(" ")
                i += 1
                continue
            label = match.group(2)
            start = i + match.end()
            j = start
            while True:
                found = code.find("\n" + label, j)
                if found == -1:
                    j = n
                    break
                end_of_line = code.find("\n", found + 1)
                end_of_line = n if end_of_line == -1 else end_of_line
                if code[found + 1:end_of_line].strip("; \t") == label:
                    j = end_of_line
                    break
                j = found + 1
            out.append(" " * (j - i))
            i = j
        elif ch in "\"'":
            quote = ch
            j = i + 1
            while j < n:
                if code[j] == "\\":
                    j += 2
                    continue
                if code[j] == quote:
                    j += 1
                    break
                j += 1
            body = code[i:j]
            out.append(body if keep_strings else " " * len(body))
            i = j
        else:
            out.append(ch)
            i += 1
    return "".join(out)


def balance(code: str) -> list[str]:
    """Brace/paren balance, ignoring string contents, comments and heredoc bodies."""
    clean = strip_php(code, keep_strings=False)
    problems = []
    stack = []
    pairs = {")": "(", "]": "[", "}": "{"}
    line = 1
    for index, char in enumerate(clean):
        if char == "\n":
            line += 1
        elif char in "([{":
            stack.append((char, line))
        elif char in pairs:
            if not stack:
                problems.append(f"line {line}: stray '{char}'")
            elif stack[-1][0] != pairs[char]:
                problems.append(f"line {line}: '{char}' closes '{stack[-1][0]}' opened on line {stack[-1][1]}")
                stack.pop()
            else:
                stack.pop()
    for char, opened in stack:
        problems.append(f"line {opened}: '{char}' never closed")
    return problems


def read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("-v", "--verbose", action="store_true", help="list every call site")
    args = parser.parse_args()

    if not API.is_dir():
        print("billing-api/ is missing — nothing to check.")
        return 1

    failures: list[str] = []
    notes: list[str] = []
    php_files = sorted([p for p in API.rglob("*.php")])
    sources = {path: strip_php(read(path)) for path in php_files}

    # ---------------------------------------------------------------- 1. routes
    support = sources.get(LIB / "support.php", "")
    routes = {match.group("path"): (match.group("verb").upper(), match.group("handler"))
              for match in ROUTE_ROW_RE.finditer(support)}
    if not routes:
        failures.append("no routes found in billing-api/lib/support.php — the table did not parse")

    defined = defaultdict(set)
    for path, code in sources.items():
        for match in FUNCTION_RE.finditer(code):
            defined[match.group(1)].add(path.relative_to(ROOT))

    print(f"Route table: {len(routes)} endpoints")
    for route, (verb, handler) in sorted(routes.items()):
        missing = "" if handler in defined else "   <-- NO HANDLER FUNCTION"
        print(f"  {verb:4} {route:32} {handler}{missing}")
        if missing:
            failures.append(f"{verb} {route}: handler {handler}() is not defined")

    # ------------------------------------------------------ 2. app call sites
    call_sites: list[tuple[str, str, str]] = []          # verb, path, where
    referenced: set[str] = set()
    for source in sorted(JAVA.rglob("*.java")):
        text = read(source)
        where = str(source.relative_to(ROOT))
        for match in JAVA_CALL_RE.finditer(text):
            call_sites.append((match.group("verb").upper(), match.group("path").strip("/"), where))
        for match in JAVA_URL_RE.finditer(text):
            call_sites.append(("GET", match.group("path").strip("/"), where))
        referenced.update(literal.strip("/") for literal in JAVA_LITERAL_RE.findall(text))

    if args.verbose:
        print("\nCall sites:")
        seen = defaultdict(set)
        for verb, path, where in call_sites:
            seen[path].add(f"{verb} {where}")
        for path, where in sorted(seen.items()):
            print(f"  {path:32} " + ", ".join(sorted(where)))

    for verb, path, where in call_sites:
        entry = routes.get(path)
        if entry is None:
            failures.append(f"{where} calls {verb} {path} but the API has no such route")
        elif entry[0] != verb:
            failures.append(f"{where} calls {verb} {path}, route table expects {entry[0]}")
    for route in sorted(set(referenced) - set(routes)):
        if not any(known == route for known in routes):
            failures.append(f"app mentions {route} which the route table does not serve")
    for route in sorted(set(routes) - referenced):
        notes.append(f"route {routes[route][0]} {route} is never mentioned by the app")

    # --------------------------------------------------- 3. functions resolve
    for path, code in sources.items():
        for match in CALL_RE.finditer(code):
            name = match.group(1)
            if name in defined or name in PHP_BUILTINS:
                continue
            failures.append(f"{path.relative_to(ROOT)} calls {name}() which is defined nowhere")

    # ------------------------------------------------------- 4. tables exist
    schema = sources.get(LIB / "schema.php", "")
    tables = set(SCHEMA_TABLE_RE.findall(schema))
    if not tables:
        failures.append("billing-api/lib/schema.php: no tables parsed — check the declaration shape")
    known = {"shops", "users", "licences", "activation_codes", "app_devices", "refresh_tokens",
              "sync_records", "notifications", "audit_log", "admin_users", "api_clients",
              "app_settings", "plans"}
    for table in sorted(known - tables):
        failures.append(f"schema.php does not declare {table}, which the handlers query")
    for path, raw in sources.items():
        for literal in SQL_STRING_RE.finditer(raw):
            for match in TABLE_RE.finditer(literal.group("body")):
                table = match.group("table")
                if table in tables or table.startswith("sqlite_"):
                    continue
                failures.append(f"{path.relative_to(ROOT)} queries table '{table}' which is not in schema.php")

    # ------------------------------------------------- 5. config keys exist
    example = read(API / "config.example.php") if (API / "config.example.php").is_file() else ""
    example_keys = set(EXAMPLE_KEY_RE.findall(strip_php(example)))
    if not example_keys:
        failures.append("config.example.php: no keys parsed")
    used_keys = set()
    for code in sources.values():
        for match in CONFIG_KEY_RE.finditer(code):
            used_keys.add(match.group("key") or match.group("key2"))
    for key in sorted(used_keys - example_keys):
        failures.append(f"code reads config['{key}'] but config.example.php does not document it")
    for key in sorted(example_keys - used_keys):
        notes.append(f"config.example.php documents '{key}' which no code reads")

    # ------------------------------------------- 7. SQL placeholder arity (lib/)
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    try:
        import check_sql_arity
        for problem in check_sql_arity.check(API):
            failures.append("sql arity: " + problem)
    except ImportError as skipped:            # the standalone script is optional
        notes.append(f"check_sql_arity.py did not run: {skipped}")

    # ---------------------------------------------------- 6. balanced files
    for path, _ in sources.items():
        for problem in balance(read(path)):
            failures.append(f"{path.relative_to(ROOT)}: {problem}")

    # -------------------------------------------------------------- verdict
    if args.verbose and notes:
        print("\nNotes:")
        for note in sorted(set(notes)):
            print(f"  · {note}")
    if failures:
        print(f"\nFAIL — {len(failures)} problem(s):")
        for failure in dict.fromkeys(failures):
            print(f"  · {failure}")
        return 1
    print(f"\nOK — {len(routes)} routes, {len(call_sites)} app call sites, "
          f"{len(defined)} functions, {len(tables)} tables all line up."
          + (f" ({len(set(notes))} note(s), run with -v)" if notes else ""))
    return 0


if __name__ == "__main__":
    sys.exit(main())
