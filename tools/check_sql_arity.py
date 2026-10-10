"""Placeholder-arity check for the SQL helpers in billing-api/ — imported by
tools/check_api_contract.py, runnable on its own:

    python3 tools/check_sql_arity.py

A missing or extra `?` in a prepared statement is exactly the kind of bug that only
shows up when the handler runs, and this sandbox has no PHP to run it with. So the
shape is checked statically instead: for every qt_row() / qt_rows() / qt_run() call
whose statement is a single string literal, the number of `?` must equal the number of
elements in the binding array literal. Concatenated SQL and array_merge() bindings are
skipped, because they cannot be judged here.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path
from typing import List, Optional

ROOT = Path(__file__).resolve().parent.parent
API = ROOT / "billing-api"

CALL_RE = re.compile(r"\bqt_(row|rows|run)\s*\(")


def _scan_args(text: str, open_paren: int) -> List[str]:
    """Split a call's argument list at top-level commas, respecting quotes."""
    args: List[str] = []
    current: List[str] = []
    depth = 0
    quote = ""
    index = open_paren + 1
    while index < len(text):
        char = text[index]
        if quote:
            current.append(char)
            if char == "\\" and index + 1 < len(text):
                current.append(text[index + 1])
                index += 2
                continue
            if char == quote:
                quote = ""
            index += 1
            continue
        if char in "\"'":
            quote = char
            current.append(char)
            index += 1
            continue
        if char in "([{":
            depth += 1
        elif char in ")]}":
            if depth == 0:
                break
            depth -= 1
        elif char == "," and depth == 0:
            args.append("".join(current).strip())
            current = []
            index += 1
            continue
        current.append(char)
        index += 1
    args.append("".join(current).strip())
    return [arg for arg in args if arg]


def _array_len(expression: str) -> Optional[int]:
    """Length of a literal `[a, b]` array, or None when it is not one."""
    expression = expression.strip()
    if not expression.startswith("["):
        return None
    depth = 0
    quote = ""
    items = 0
    saw_content = False
    for index, char in enumerate(expression):
        if index == 0:
            continue
        if quote:
            if char == quote:
                quote = ""
            continue
        if char in "\"'":
            quote = char
            saw_content = True
            continue
        if char in "([{":
            depth += 1
            continue
        if char in ")]}":
            if depth == 0:
                if saw_content:
                    items += 1
                return items
            depth -= 1
            continue
        if char == "," and depth == 0:
            if saw_content:
                items += 1
            saw_content = False
            continue
        if not char.isspace():
            saw_content = True
    return None


def check_file(path: Path) -> List[str]:
    source = path.read_text(encoding="utf-8")
    problems: List[str] = []
    for match in CALL_RE.finditer(source):
        open_paren = source.index("(", match.start() + 3)
        args = _scan_args(source, open_paren)
        if not args:
            continue
        sql = args[0]
        if not re.fullmatch(r"'(?:[^'\\]|\\.)*'|\"(?:[^\"\\]|\\.)*\"", sql):
            continue                       # concatenated or a variable: not judgeable
        sql_body = sql[1:-1] if len(sql) > 1 else ""
        if not re.search(r"\b(SELECT|UPDATE|INSERT|DELETE)\b", sql_body, re.IGNORECASE):
            continue
        line = source[:match.start()].count("\n") + 1
        wanted = sql_body.count("?")
        if len(args) == 1:
            if wanted:
                problems.append(f"{path.name}:{line}: {wanted} '?' placeholder(s) but no "
                                f"bindings were passed")
            continue
        given = _array_len(args[1])
        if given is None:
            continue                       # a variable or array_merge()
        if given != wanted:
            problems.append(f"{path.name}:{line}: {wanted} '?' placeholder(s) but "
                            f"{given} binding(s) passed")
    return problems


def check(api_dir: Path = API) -> List[str]:
    """Problems for every PHP file of the API (empty list when they all match)."""
    problems: List[str] = []
    for path in sorted(api_dir.rglob("*.php")):
        problems.extend(check_file(path))
    return problems


if __name__ == "__main__":
    found = check()
    if found:
        print("SQL placeholder arity problems:")
        for problem in found:
            print("  · " + problem)
        sys.exit(1)
    print("OK — every literal SQL call in billing-api/ binds exactly as many values as it has '?'")
    sys.exit(0)
