#!/usr/bin/env python3
"""Resolve ZCode (@zcode/ui) CSS design tokens into concrete sRGB values.

Source of truth: reference/ZCode/packages/ui/src/styles.css
  - `@theme { ... }`  = light theme base tokens
  - `.dark { ... }`   = dark theme overrides
  - `:root { ... }`   = a few runtime extras

Tailwind v4 palette (oklch) values come from the tailwindcss theme.css
vendored next to this script. var() references and color-mix(in oklab|srgb)
are resolved with the same math a browser uses (premultiplied alpha,
oklab interpolation).

Usage:
  python3 zcode_theme_tokens.py --zcode /path/to/ZCode \
      [--rust out.rs] [--json out.json]
"""

from __future__ import annotations

import argparse
import json
import math
import re
import sys
from pathlib import Path


# ── OKLab / OKLCH math ──────────────────────────────────────────────────────

def oklch_to_linear_srgb(L: float, C: float, H_deg: float) -> tuple[float, float, float]:
    h = math.radians(H_deg)
    a = C * math.cos(h)
    b = C * math.sin(h)
    l_ = L + 0.3963377774 * a + 0.2158037573 * b
    m_ = L - 0.1055613458 * a - 0.0638541728 * b
    s_ = L - 0.0894841775 * a - 1.2914855480 * b
    l = l_ ** 3
    m = m_ ** 3
    s = s_ ** 3
    r = +4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s
    g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s
    b2 = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
    return r, g, b2


def srgb_to_oklab(r255: float, g255: float, b255: float) -> tuple[float, float, float]:
    def srgb_to_linear(c: float) -> float:
        c = c / 255.0
        return c / 12.92 if c <= 0.04045 else ((c + 0.055) / 1.055) ** 2.4

    r = srgb_to_linear(r255)
    g = srgb_to_linear(g255)
    b = srgb_to_linear(b255)
    l_ = math.cbrt(0.4122214708 * r + 0.5363325363 * g + 0.0514459929 * b)
    m_ = math.cbrt(0.2119034982 * r + 0.6806995451 * g + 0.1073969566 * b)
    s_ = math.cbrt(0.0883024619 * r + 0.2817188376 * g + 0.6299787005 * b)
    L = 0.2104542553 * l_ + 0.7936177850 * m_ - 0.0040720468 * s_
    a = 1.9779984951 * l_ - 2.4285922050 * m_ + 0.4505937099 * s_
    b2 = 0.0259040371 * l_ + 0.7827717662 * m_ - 0.8086757660 * s_
    return L, a, b2


def gamma_encode(c: float) -> float:
    c = min(1.0, max(0.0, c))
    return 12.92 * c if c <= 0.0031308 else 1.055 * (c ** (1 / 2.4)) - 0.055


def oklab_to_srgb255(L: float, a: float, b: float) -> tuple[float, float, float]:
    l_ = L + 0.3963377774 * a + 0.2158037573 * b
    m_ = L - 0.1055613458 * a - 0.0638541728 * b
    s_ = L - 0.0894841775 * a - 1.2914855480 * b
    l = l_ ** 3
    m = m_ ** 3
    s = s_ ** 3
    r = +4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s
    g = -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s
    b2 = -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s
    return (
        round(gamma_encode(r) * 255.0),
        round(gamma_encode(g) * 255.0),
        round(gamma_encode(b2) * 255.0),
    )


# ── Color model ─────────────────────────────────────────────────────────────

class Color:
    __slots__ = ("r", "g", "b", "a")  # 0..255, a 0..1

    def __init__(self, r: float, g: float, b: float, a: float = 1.0):
        self.r, self.g, self.b, self.a = r, g, b, a

    def hex(self) -> str:
        return "#{:02x}{:02x}{:02x}{:02x}".format(
            round(self.r), round(self.g), round(self.b), round(self.a * 255.0)
        )

    @staticmethod
    def from_hex(s: str) -> "Color":
        s = s.strip()
        if s.startswith("#"):
            s = s[1:]
        if len(s) == 3:
            s = "".join(c * 2 for c in s)
        if len(s) == 6:
            r, g, b, a = s[0:2], s[2:4], s[4:6], "ff"
        else:
            r, g, b, a = s[0:2], s[2:4], s[4:6], s[6:8]
        return Color(int(r, 16), int(g, 16), int(b, 16), int(a, 16) / 255.0)

    def mix(self, other: "Color", pct: float, space: str) -> "Color":
        """color-mix: self at pct%, other at (100-pct)%. Premultiplied."""
        w1 = pct / 100.0
        w2 = 1.0 - w1
        a = self.a * w1 + other.a * w2
        if space == "oklab":
            L1, a1, b1 = srgb_to_oklab(self.r, self.g, self.b)
            L2, a2, b2 = srgb_to_oklab(other.r, other.g, other.b)
            L1, a1, b1 = L1 * self.a, a1 * self.a, b1 * self.a
            L2, a2, b2 = L2 * other.a, a2 * other.a, b2 * other.a
            L, aa, bb = L1 * w1 + L2 * w2, a1 * w1 + a2 * w2, b1 * w1 + b2 * w2
            if a > 0:
                L, aa, bb = L / a, aa / a, bb / a
            r, g, b = oklab_to_srgb255(L, aa, bb)
            return Color(r, g, b, a)
        # srgb: interpolate gamma-encoded channels, premultiplied
        def ch(c1: float, c2: float, a1: float, a2: float) -> float:
            v = (c1 * a1) * w1 + (c2 * a2) * w2
            return v / a if a > 0 else 0.0

        r = ch(self.r, other.r, self.a, other.a)
        g = ch(self.g, other.g, self.a, other.a)
        b = ch(self.b, other.b, self.a, other.a)
        return Color(r, g, b, a)


TRANSPARENT = Color(0, 0, 0, 0.0)
WHITE = Color(255, 255, 255, 1.0)
BLACK = Color(0, 0, 0, 1.0)


# ── CSS parsing ─────────────────────────────────────────────────────────────

def strip_comments(css: str) -> str:
    return re.sub(r"/\*.*?\*/", "", css, flags=re.S)


def extract_block(css: str, selector: str) -> dict[str, str]:
    """Extract `selector { --tok: val; ... }` declarations (handles nesting)."""
    idx = css.find(selector)
    if idx < 0:
        return {}
    brace = css.find("{", idx)
    if brace < 0:
        return {}
    depth = 1
    end = brace + 1
    while end < len(css) and depth > 0:
        if css[end] == "{":
            depth += 1
        elif css[end] == "}":
            depth -= 1
        end += 1
    body = css[brace + 1 : end - 1]
    out: dict[str, str] = {}
    for m in re.finditer(r"--([\w-]+)\s*:\s*([^;]+);", body):
        out[m.group(1)] = re.sub(r"\s+", " ", m.group(2)).strip()
    return out


def parse_tw_palette(css: str) -> dict[str, Color]:
    palette: dict[str, Color] = {}
    for m in re.finditer(r"--color-([\w-]+):\s*([^;]+);", css):
        name, val = m.group(1), m.group(2).strip()
        if val.startswith("#"):
            palette[name] = Color.from_hex(val)
            continue
        ok = re.match(r"oklch\(\s*([\d.]+)%\s+([\d.]+|none)\s+([\d.]+|none)\s*\)", val)
        if ok:
            L = float(ok.group(1)) / 100.0
            C = 0.0 if ok.group(2) == "none" else float(ok.group(2))
            H = 0.0 if ok.group(3) == "none" else float(ok.group(3))
            r, g, b = oklch_to_linear_srgb(L, C, H)
            palette[name] = Color(
                gamma_encode(r) * 255.0, gamma_encode(g) * 255.0, gamma_encode(b) * 255.0
            )
            continue
        ok = re.match(r"oklch\(\s*([\d.]+)%\s+([\d.e-]+|none)\s*\)", val)  # gray: no hue
        if ok:
            L = float(ok.group(1)) / 100.0
            C = 0.0 if ok.group(2) == "none" else float(ok.group(2))
            r, g, b = oklch_to_linear_srgb(L, C, 0.0)
            palette[name] = Color(
                gamma_encode(r) * 255.0, gamma_encode(g) * 255.0, gamma_encode(b) * 255.0
            )
    return palette


VAR_RE = re.compile(r"var\(--([\w-]+)\)")
MIX_RE = re.compile(r"color-mix\(\s*in\s+(oklab|srgb)\s*,\s*(.+?)\s*,\s*(.+?)\s*\)")


def resolve(token: str, decls: dict[str, str], tw: dict[str, Color], depth: int = 0):
    if depth > 24:
        return None
    raw = decls.get(token)
    if raw is None:
        if token in tw:
            return tw[token]
        # var(--color-neutral-50) -> token "color-neutral-50", tw key "neutral-50"
        stripped = token[6:] if token.startswith("color-") else token
        if stripped in tw:
            return tw[stripped]
        return None
    val = raw.strip()

    def resolve_value(v: str, d: int):
        v = v.strip()
        if v in ("transparent",):
            return TRANSPARENT
        if v in ("white", "#fff", "#ffffff"):
            return WHITE
        if v in ("black", "#000", "#000000"):
            return BLACK
        if v.startswith("#"):
            return Color.from_hex(v)
        if v.startswith("rgb(") or v.startswith("rgba("):
            nums = [float(x) for x in re.findall(r"[\d.]+", v)]
            if len(nums) == 3:
                return Color(nums[0], nums[1], nums[2], 1.0)
            if len(nums) == 4:
                return Color(nums[0], nums[1], nums[2], nums[3])
        m = VAR_RE.fullmatch(v)
        if m:
            return resolve(m.group(1), decls, tw, d + 1)
        mix = MIX_RE.fullmatch(v)
        if mix:
            space, c1, c2 = mix.group(1), mix.group(2), mix.group(3)
            pct = 50.0
            col1 = None
            m2 = re.match(r"(.+?)\s+([\d.]+)%$", c1)
            if m2:
                col1 = resolve_value(m2.group(1), d + 1)
                pct = float(m2.group(2))
            else:
                col1 = resolve_value(c1, d + 1)
            m3 = re.match(r"(.+?)\s+([\d.]+)%$", c2)
            if m3:
                col2 = resolve_value(m3.group(1), d + 1)
                pct = 100.0 - float(m3.group(2))
            else:
                col2 = resolve_value(c2, d + 1)
            if col1 is None or col2 is None:
                return None
            return col1.mix(col2, pct, space)
        return None

    return resolve_value(val, 0)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--zcode", required=True, help="path to ZCode repo")
    ap.add_argument("--tw", default=str(Path(__file__).parent / "tailwind-theme.css"))
    ap.add_argument("--rust", help="write Rust constants here")
    ap.add_argument("--json", help="write JSON here")
    args = ap.parse_args()

    styles = strip_comments(
        (Path(args.zcode) / "packages/ui/src/styles.css").read_text(encoding="utf-8")
    )
    tw_css = strip_comments(Path(args.tw).read_text(encoding="utf-8"))

    tw = parse_tw_palette(tw_css)
    theme = extract_block(styles, "@theme")
    root = extract_block(styles, ":root")
    dark = extract_block(styles, ".dark")

    light_decls = dict(theme)
    light_decls.update(root)
    dark_decls = dict(theme)
    dark_decls.update(dark)

    def solve(decls: dict[str, str]) -> dict[str, str]:
        out: dict[str, str] = {}
        for tok in decls:
            if tok.startswith(("text-", "font-", "tracking-", "shadow-", "animate-", "radius-", "spacing-")):
                continue  # non-color tokens handled in Rust by hand
            col = resolve(tok, decls, tw)
            if col is not None:
                out[tok] = col.hex()
        return out

    light = solve(light_decls)
    dark = solve(dark_decls)

    result = {"light": light, "dark": dark}
    if args.json:
        Path(args.json).write_text(json.dumps(result, indent=2, sort_keys=True), encoding="utf-8")
    if args.rust:
        lines = [
            "//! AUTO-GENERATED by scripts/port/zcode_theme_tokens.py from the",
            "//! ZCode (@zcode/ui) design tokens (Apache-2.0, zai-org/ZCode).",
            "//! Do not edit by hand; rerun the script to regenerate.",
            "",
        ]
        for mode, tokens in (("light", light), ("dark", dark)):
            mod = mode.lower()
            lines.append("#[allow(dead_code)]")
            lines.append(f"pub mod {mod} {{")
            lines.append("    use eframe::egui;")
            for tok in sorted(tokens):
                hexv = tokens[tok]
                r, g, b, a = (
                    int(hexv[1:3], 16),
                    int(hexv[3:5], 16),
                    int(hexv[5:7], 16),
                    int(hexv[7:9], 16),
                )
                if a == 255:
                    expr = f"egui::Color32::from_rgb({r}, {g}, {b})"
                else:
                    # Color32 stores premultiplied alpha; the unmultiplied
                    # constructor is not const in egui 0.29, so tokens are
                    # plain functions.
                    expr = f"egui::Color32::from_rgba_unmultiplied({r}, {g}, {b}, {a})"
                name = tok[6:] if tok.startswith("color-") else tok
                fn = "color_" + name.replace("-", "_")
                lines.append(f"    pub fn {fn}() -> egui::Color32 {{ {expr} }}")
            lines.append("}")
            lines.append("")
        Path(args.rust).write_text("\n".join(lines), encoding="utf-8")
    print(json.dumps({"light_count": len(light), "dark_count": len(dark)}))
    print(f"resolved {len(light)} light / {len(dark)} dark color tokens", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
