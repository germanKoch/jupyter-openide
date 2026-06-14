#!/usr/bin/env python3
"""Static analyzer for Jupyter notebook cells.

Reads JSON from stdin:  {"cells": [{"id": "...", "source": "..."}, ...]}
Writes JSON to stdout:  {"diagnostics": [{"cellId","line","col","endCol",
                                          "severity","message","code"}, ...]}

`line`/`col`/`endCol` are 0-based and measured in Unicode code points, relative
to the originating cell. The analyzer is defensive: on any internal failure it
emits an empty diagnostics list and exits 0 so the editor UI never breaks.
"""

import sys
import os
import json
import re
import ast
import importlib.util

# Names injected by IPython at runtime that must not be flagged as undefined.
IPYTHON_NAMES = {
    "get_ipython", "display", "In", "Out", "exit", "quit",
    "_", "__", "___", "_i", "_ii", "_iii", "_dh", "_sh", "_oh",
}

# IPython-specific syntax that is not valid Python.
_ASSIGN_SHELL = re.compile(r"^(\s*\w+\s*=\s*)[!%]")          # x = !ls / x = %magic
_LINE_MAGIC = re.compile(r"^\s*[!%?]")                        # !cmd / %magic / ?help
_HELP_LINE = re.compile(r"^[A-Za-z_]\S*\?\??$")               # obj? / obj.attr??


def byte_to_char(line, byte_col):
    """Convert a UTF-8 byte offset (Python ast col_offset) to a code-point index."""
    if byte_col <= 0:
        return 0
    try:
        b = line.encode("utf-8")
        if byte_col >= len(b):
            return len(line)
        return len(b[:byte_col].decode("utf-8", errors="ignore"))
    except Exception:
        return byte_col


def _bracket_delta(line):
    return (line.count("(") + line.count("[") + line.count("{")
            - line.count(")") - line.count("]") - line.count("}"))


def neutralize_cell(source):
    """Return (processed_lines, original_lines) with IPython magics neutralized.

    Magic / shell / help lines are replaced so the combined source stays
    parseable Python while preserving line counts. Magics are only recognized at
    the start of a logical line (bracket depth 0, not a backslash continuation)
    so that operator-led continuation lines like `    % 3)` are left intact.
    """
    original = source.split("\n")
    processed = []
    cell_magic = source.strip().startswith("%%")
    depth = 0
    prev_continues = False
    for line in original:
        if cell_magic:
            processed.append("")
            continue
        s = line.strip()
        if not s:
            processed.append(line)
        elif depth > 0 or prev_continues:
            processed.append(line)  # continuation line: never a magic
        else:
            m = _ASSIGN_SHELL.match(line)
            if m:
                processed.append(m.group(1) + "None")
            elif _LINE_MAGIC.match(line):
                processed.append("")
            elif _HELP_LINE.match(s) and "#" not in s:
                processed.append("")
            else:
                processed.append(line)
        appended = processed[-1]
        depth = max(0, depth + _bracket_delta(appended))
        prev_continues = appended.rstrip().endswith("\\")
    return processed, original


def add_diag(diags, line_map, combined, combined_lineno, col_byte, name,
             severity, message, code):
    """Map a 1-based combined line number to a cell and append a diagnostic."""
    idx = combined_lineno - 1
    if idx < 0 or idx >= len(line_map):
        return
    cid, local = line_map[idx]
    if cid is None:
        return
    line_text = combined[idx] if 0 <= idx < len(combined) else ""
    col = byte_to_char(line_text, col_byte)
    if name:
        end_col = col + len(name)
    else:
        end_col = len(line_text) if len(line_text) > col else col + 1
    diags.append({
        "cellId": cid,
        "line": local,
        "col": col,
        "endCol": end_col,
        "severity": severity,
        "message": message,
        "code": code,
    })


def run_pyflakes(tree, filename):
    """Return list of pyflakes Message objects, or None if pyflakes absent."""
    try:
        from pyflakes.checker import Checker
    except Exception:
        return None
    try:
        checker = Checker(tree, filename=filename)
        checker.messages.sort(key=lambda m: m.lineno)
        return checker.messages
    except Exception:
        return None


def classify(message):
    """Map a pyflakes message to (severity, code, name_or_None)."""
    cls = type(message).__name__
    args = getattr(message, "message_args", ()) or ()
    name = args[0] if args else None
    if cls == "UndefinedName":
        return ("error", "undefined-name", name if isinstance(name, str) else None)
    return ("warning", cls, name if isinstance(name, str) else None)


def fallback_undefined(tree):
    """Minimal undefined-name pass used when pyflakes is unavailable.

    Conservatively collects every name bound anywhere, then flags Name loads
    that are unbound, not builtins, and not IPython names. Over-collecting only
    suppresses warnings, never invents false positives.
    """
    import builtins as _builtins
    bound = set(dir(_builtins)) | IPYTHON_NAMES
    MatchAs = getattr(ast, "MatchAs", None)
    MatchStar = getattr(ast, "MatchStar", None)
    MatchMapping = getattr(ast, "MatchMapping", None)
    for node in ast.walk(tree):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef, ast.ClassDef)):
            bound.add(node.name)
        elif isinstance(node, ast.Import):
            for alias in node.names:
                bound.add((alias.asname or alias.name).split(".")[0])
        elif isinstance(node, ast.ImportFrom):
            for alias in node.names:
                if alias.name == "*":
                    return []  # star import: cannot reason, bail out
                bound.add(alias.asname or alias.name)
        elif isinstance(node, ast.Name) and isinstance(node.ctx, ast.Store):
            bound.add(node.id)
        elif isinstance(node, ast.arg):
            bound.add(node.arg)
        elif isinstance(node, ast.ExceptHandler) and node.name:
            bound.add(node.name)
        elif MatchAs is not None and isinstance(node, MatchAs) and node.name:
            bound.add(node.name)
        elif MatchStar is not None and isinstance(node, MatchStar) and node.name:
            bound.add(node.name)
        elif MatchMapping is not None and isinstance(node, MatchMapping) and node.rest:
            bound.add(node.rest)
    out = []
    for node in ast.walk(tree):
        if isinstance(node, ast.Name) and isinstance(node.ctx, ast.Load):
            if node.id not in bound:
                out.append((node.lineno, node.col_offset, node.id))
    return out


def check_imports(tree, line_map, combined, diags):
    """Flag top-level imports whose package cannot be located on sys.path."""
    checked = {}
    for node in ast.walk(tree):
        tops = []
        if isinstance(node, ast.Import):
            for alias in node.names:
                tops.append(alias.name.split(".")[0])
        elif isinstance(node, ast.ImportFrom):
            if (node.level or 0) > 0 or not node.module:
                continue
            tops.append(node.module.split(".")[0])
        else:
            continue
        for top in tops:
            if not top:
                continue
            if top not in checked:
                try:
                    checked[top] = importlib.util.find_spec(top) is not None
                except Exception:
                    checked[top] = True  # uncertain -> don't flag
            if not checked[top]:
                add_diag(diags, line_map, combined, node.lineno, 0, None,
                         "warning", "No module named '%s'" % top, "unresolved-import")


def analyze(cells):
    diags = []
    filename = "<notebook>"

    # Pass 1: per-cell syntax check. A broken cell is reported locally and
    # excluded from the cross-cell semantic pass so other cells still analyze.
    processed_cells = []  # (cid, processed_lines, original_lines, broken)
    for cell in cells:
        cid = cell.get("id")
        src = cell.get("source", "") or ""
        processed, original = neutralize_cell(src)
        broken = False
        try:
            ast.parse("\n".join(processed), "<cell>")
        except SyntaxError as e:
            broken = True
            local = max(0, (e.lineno or 1) - 1)
            ptext = processed[local] if 0 <= local < len(processed) else ""
            col = byte_to_char(ptext, max(0, (e.offset or 1) - 1))
            ll = len(original[local]) if 0 <= local < len(original) else 0
            diags.append({
                "cellId": cid,
                "line": local,
                "col": col,
                "endCol": ll if ll > col else col + 1,
                "severity": "error",
                "message": e.msg or "invalid syntax",
                "code": "syntax-error",
            })
        except Exception:
            pass
        processed_cells.append((cid, processed, original, broken))

    # Pass 2: combined source from non-broken cells, with an origin map.
    combined = []
    line_map = []
    for cid, processed, original, broken in processed_cells:
        for local_idx, pline in enumerate(processed):
            if broken:
                combined.append("")
                line_map.append((None, -1))
            else:
                combined.append(pline)
                line_map.append((cid, local_idx))
        combined.append("")  # blank separator, never reported
        line_map.append((None, -1))
    combined_src = "\n".join(combined)

    try:
        tree = ast.parse(combined_src, filename)
    except SyntaxError:
        return diags  # keep per-cell syntax diagnostics

    messages = run_pyflakes(tree, filename)
    if messages is not None:
        for m in messages:
            severity, code, name = classify(m)
            if code == "undefined-name" and name in IPYTHON_NAMES:
                continue
            col = getattr(m, "col", 0) or 0
            underline = name if code == "undefined-name" else None
            try:
                text = m.message % m.message_args
            except Exception:
                text = m.message
            add_diag(diags, line_map, combined, m.lineno, col, underline,
                     severity, text, code)
    else:
        for lineno, col, name in fallback_undefined(tree):
            if name in IPYTHON_NAMES:
                continue
            add_diag(diags, line_map, combined, lineno, col, name,
                     "error", "undefined name '%s'" % name, "undefined-name")

    check_imports(tree, line_map, combined, diags)
    return diags


def main():
    try:
        cwd = os.getcwd()
        if cwd not in sys.path:
            sys.path.insert(0, cwd)
        raw = sys.stdin.read()
        payload = json.loads(raw) if raw.strip() else {}
        cells = payload.get("cells", [])
        diags = analyze(cells)
        sys.stdout.write(json.dumps({"diagnostics": diags}))
    except Exception:
        sys.stdout.write(json.dumps({"diagnostics": []}))


if __name__ == "__main__":
    main()
