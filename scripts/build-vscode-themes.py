#!/usr/bin/env python3
"""Merge the VSCode default themes (Dark+/Light+) into a single JSON file tm4e can load directly.

VSCode themes are JSONC (with comments and trailing commas) and inherit their base theme via "include",
while tm4e's IThemeSource neither resolves include nor tolerates comments. This script merges them offline at build time:
The base theme (dark_vs/light_vs) colors + tokenColors form the base layer, and the parent theme (dark_plus/light_plus)
tokenColors are appended after (same scope, later wins); semanticTokenColors are merged with override,
producing pure JSON written to app/src/main/assets/textmate/{dark_plus,light_plus}.json.

Re-run this script whenever a release or dependency upgrade needs refreshed colors.
"""
import json
import re
import urllib.request
from pathlib import Path

BASE = "https://raw.githubusercontent.com/microsoft/vscode/1.85.0/extensions/theme-defaults/themes/"
OUT_DIR = Path(__file__).resolve().parent.parent / "app/src/main/assets/textmate"

PAIRS = [
    ("dark_vs.json", "dark_plus.json", "dark_plus.json", "dark"),
    ("light_vs.json", "light_plus.json", "light_plus.json", "light"),
]


def strip_jsonc(text: str) -> str:
    # Scan character by character, stripping only // line comments and /* */ block comments outside strings,
    # to avoid damaging // inside strings like "vscode://schemas/...". Trailing commas are removed last.
    out = []
    i, n = 0, len(text)
    in_str = False
    while i < n:
        c = text[i]
        if in_str:
            out.append(c)
            if c == "\\" and i + 1 < n:
                out.append(text[i + 1])
                i += 2
                continue
            if c == '"':
                in_str = False
            i += 1
            continue
        if c == '"':
            in_str = True
            out.append(c)
            i += 1
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "/":
            i += 2
            while i < n and text[i] not in "\n\r":
                i += 1
            continue
        if c == "/" and i + 1 < n and text[i + 1] == "*":
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                i += 1
            i += 2
            continue
        out.append(c)
        i += 1
    cleaned = "".join(out)
    # Remove trailing commas inside objects/arrays
    cleaned = re.sub(r",(\s*[}\]])", r"\1", cleaned)
    return cleaned


def load(name: str) -> dict:
    raw = urllib.request.urlopen(BASE + name, timeout=30).read().decode("utf-8")
    return json.loads(strip_jsonc(raw), strict=False)


def merge(base_name: str, plus_name: str, out_name: str, kind: str) -> None:
    base = load(base_name)
    plus = load(plus_name)

    merged = {
        "name": plus.get("name", base.get("name")),
        "type": kind,
        "colors": base.get("colors", {}),
        "tokenColors": base.get("tokenColors", []) + plus.get("tokenColors", []),
        "semanticHighlighting": True,
        "semanticTokenColors": {
            **base.get("semanticTokenColors", {}),
            **plus.get("semanticTokenColors", {}),
        },
    }

    # sora's TextMateColorScheme reads the legacy keys editorIndentGuide.background(/activeBackground) to map
    # indent guide colors; VSCode 1.85 switched to new keys with a 1 suffix, so re-add the legacy keys for color lookup.
    colors = merged["colors"]
    if "editorIndentGuide.background1" in colors:
        colors.setdefault("editorIndentGuide.background", colors["editorIndentGuide.background1"])
    if "editorIndentGuide.activeBackground1" in colors:
        colors.setdefault("editorIndentGuide.activeBackground", colors["editorIndentGuide.activeBackground1"])

    out = OUT_DIR / out_name
    out.write_text(json.dumps(merged, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"{out_name}: {len(merged['tokenColors'])} token rules, {len(merged['colors'])} colors")


if __name__ == "__main__":
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    for args in PAIRS:
        merge(*args)
