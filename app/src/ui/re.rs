//! The XenRE tab (Phase 7.1 + 7.2) — a native binary-inspection surface.
//!
//! Left rail: sections + function list. Main area: one of five views —
//! Hex (offset / bytes / ASCII gutter, mono), Strings (offset + text),
//! Symbols (address + name + kind), Imports (dll!name) and Package
//! (APK/IPA bundle summary: identity rows, permission/component chips,
//! dex + native-lib stats). Same visual language as the browser tab:
//! 44px toolbar, 28px ghost icon buttons, mono font for anything
//! address-shaped, dividers from the theme tokens.

use crate::fonts::{self, Weight};
use crate::gfx::{Canvas, Rect};
use crate::theme::T;

/// Toolbar height (file icon + name + format chips + view tabs).
pub const TOOLBAR_H: f32 = 44.0;
/// Status strip under the content.
pub const STATUS_H: f32 = 26.0;
/// Left rail width (sections / function list).
pub const RAIL_W: f32 = 232.0;
/// View tabs.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReTab {
    Hex,
    Strings,
    Symbols,
    Imports,
    Package,
    /// Xbox 360 XEX container identity (Phase 7.3) — same KV+chips layout
    /// as Package, fed from the XEX structure parse.
    Xex,
}

impl ReTab {
    pub fn label(self) -> &'static str {
        match self {
            ReTab::Hex => "Hex",
            ReTab::Strings => "Strings",
            ReTab::Symbols => "Symbols",
            ReTab::Imports => "Imports",
            ReTab::Package => "Package",
            ReTab::Xex => "XEX",
        }
    }
    pub const ALL: [ReTab; 6] = [
        ReTab::Hex,
        ReTab::Strings,
        ReTab::Symbols,
        ReTab::Imports,
        ReTab::Package,
        ReTab::Xex,
    ];
}

/// One extracted string for the Strings view.
pub struct ReString<'a> {
    pub offset: u64,
    pub text: &'a str,
    pub utf16: bool,
}

/// One symbol for the Symbols view.
pub struct ReSym<'a> {
    pub va: u64,
    pub name: &'a str,
    pub kind: &'a str,
}

/// One import for the Imports view.
pub struct ReImport<'a> {
    pub dll: &'a str,
    pub name: &'a str,
}

/// One section for the left rail.
pub struct ReSection<'a> {
    pub name: &'a str,
    pub va: Option<u64>,
    pub size: u64,
    pub exec: bool,
}

/// One function for the left rail.
pub struct ReFunction<'a> {
    pub va: u64,
    pub name: &'a str,
}

/// One key/value row in the Package view.
pub struct ReKv<'a> {
    pub key: &'a str,
    pub value: &'a str,
}

/// One chip in the Package view (permission / component / abi / dex stat).
pub struct ReChip<'a> {
    pub label: &'a str,
    /// sky chips for identity-ish tags, subtle for the rest.
    pub tone: ReChipTone,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReChipTone {
    Sky,
    Subtle,
    Green,
}

/// Render-only state of the RE tab — the same struct a windowed GUI owns.
pub struct RePage<'a> {
    pub file: &'a str,
    pub format: &'a str,
    pub arch: &'a str,
    pub kind: &'a str,
    pub entry: &'a str,
    pub size: u64,
    pub tab: ReTab,
    /// Pre-rendered hex rows (`render_hex_row` output).
    pub hex: &'a [String],
    pub strings: &'a [ReString<'a>],
    pub symbols: &'a [ReSym<'a>],
    pub imports: &'a [ReImport<'a>],
    pub sections: &'a [ReSection<'a>],
    pub functions: &'a [ReFunction<'a>],
    /// Package view (APK/IPA): empty when the file is a plain binary.
    pub package_rows: &'a [ReKv<'a>],
    pub package_chips: &'a [ReChip<'a>],
    /// One-line footer status.
    pub status: &'a str,
}

/// Draw the RE surface into `area` (the workspace panel content rect).
pub fn draw(c: &mut Canvas, area: Rect, page: &RePage<'_>) {
    let toolbar = Rect::new(area.x, area.y, area.w, TOOLBAR_H);
    draw_toolbar(c, toolbar, page);
    c.line(
        toolbar.x,
        toolbar.bottom(),
        toolbar.right(),
        toolbar.bottom(),
        1.0,
        T::DIVIDER(),
    );

    let body_h = area.h - TOOLBAR_H - STATUS_H;
    let rail = Rect::new(area.x, toolbar.bottom(), RAIL_W, body_h);
    draw_rail(c, rail, page);
    c.line(
        rail.right(),
        rail.y,
        rail.right(),
        rail.bottom(),
        1.0,
        T::DIVIDER(),
    );

    let content = Rect::new(rail.right(), rail.y, area.w - RAIL_W, body_h);
    match page.tab {
        ReTab::Hex => draw_hex(c, content, page),
        ReTab::Strings => draw_strings(c, content, page),
        ReTab::Symbols => draw_symbols(c, content, page),
        ReTab::Imports => draw_imports(c, content, page),
        ReTab::Package => draw_package(c, content, page),
        ReTab::Xex => draw_xex(c, content, page),
    }

    let status = Rect::new(area.x, content.bottom(), area.w, STATUS_H);
    c.line(status.x, status.y, status.right(), status.y, 1.0, T::DIVIDER());
    fonts::draw_mid(
        c,
        status.x + 10.0,
        status.cy(),
        page.status,
        11.0,
        T::FG_SUBTLEST(),
        Weight::Normal,
        false,
    );
    // Right side: size + entry.
    let right = format!("{} · entry {}", human_size(page.size), page.entry);
    let w = fonts::measure(&right, 11.0, Weight::Normal, false);
    fonts::draw_mid(
        c,
        status.right() - 10.0 - w,
        status.cy(),
        &right,
        11.0,
        T::FG_SUBTLEST(),
        Weight::Normal,
        false,
    );
}

fn draw_toolbar(c: &mut Canvas, r: Rect, page: &RePage<'_>) {
    let cy = r.cy();
    c.icon("file-code", r.x + 14.0, cy - 8.0, 16.0, T::FG_SUBTLE());
    // File name (basename only).
    let base = page
        .file
        .rsplit('/')
        .next()
        .unwrap_or(page.file);
    let shown = fit_text(base, 180.0, 13.0);
    let mut x = r.x + 38.0 + fonts::draw_mid(
        c,
        r.x + 38.0,
        cy,
        &shown,
        13.0,
        T::FG(),
        Weight::Normal,
        false,
    );

    // Format / arch chips.
    for (label, color) in [
        (page.format, T::SKY()),
        (page.arch, T::FG_SUBTLE()),
    ] {
        if label.is_empty() {
            continue;
        }
        let w = fonts::measure(label, 10.0, Weight::Normal, false) + 14.0;
        let chip = Rect::new(x + 8.0, cy - 9.0, w, 18.0);
        c.fill_rrect(chip, 9.0, T::SECONDARY());
        fonts::draw_mid(
            c,
            chip.x + 7.0,
            cy,
            label,
            10.0,
            color,
            Weight::Normal,
            false,
        );
        x = chip.right();
    }

    // View tabs, right-aligned.
    let mut tw = 0.0;
    for tab in ReTab::ALL {
        tw += fonts::measure(tab.label(), 12.0, Weight::Normal, false) + 28.0;
    }
    let mut tx = r.right() - tw - 10.0;
    for tab in ReTab::ALL {
        let active = tab == page.tab;
        let lw = fonts::measure(tab.label(), 12.0, Weight::Normal, false);
        let color = if active { T::FG() } else { T::FG_SUBTLEST() };
        let _ = fonts::draw_mid(c, tx, cy, tab.label(), 12.0, color, Weight::Normal, false);
        if active {
            c.fill_rect(
                Rect::new(tx, r.bottom() - 2.0, lw, 2.0),
                T::FG(),
            );
        }
        tx += lw + 28.0;
    }
}

fn draw_rail(c: &mut Canvas, r: Rect, page: &RePage<'_>) {
    c.fill_rect(r, T::PANEL());
    let mut y = r.y + 10.0;

    // Sections header.
    fonts::draw_mid(
        c,
        r.x + 12.0,
        y + 7.0,
        "SECTIONS",
        10.0,
        T::FG_SUBTLEST(),
        Weight::Normal,
        false,
    );
    y += 26.0;
    for s in page.sections.iter().take(6) {
        let color = if s.exec { T::GIT_ADDED() } else { T::FG_SUBTLE() };
        c.icon(
            if s.exec { "file-code" } else { "file" },
            r.x + 12.0,
            y + 4.0,
            12.0,
            T::FG_SUBTLEST(),
        );
        let mut x = r.x + 30.0 + fonts::draw_mid(
            c,
            r.x + 30.0,
            y + 10.0,
            s.name,
            11.0,
            color,
            Weight::Normal,
            false,
        );
        let _ = x;
        let size_s = human_size(s.size);
        let sw = fonts::measure(&size_s, 10.0, Weight::Normal, true);
        let _ = x;
        if r.right() - sw - 10.0 > r.x + 30.0 {
            x = r.right() - sw - 10.0;
            let _ = fonts::draw_mid(c, x, y + 10.0, &size_s, 10.0, T::FG_SUBTLEST(), Weight::Normal, true);
        }
        y += 20.0;
    }

    y += 8.0;
    c.line(r.x + 10.0, y, r.right() - 10.0, y, 1.0, T::DIVIDER());
    y += 8.0;

    // Functions header.
    fonts::draw_mid(
        c,
        r.x + 12.0,
        y + 7.0,
        "FUNCTIONS",
        10.0,
        T::FG_SUBTLEST(),
        Weight::Normal,
        false,
    );
    y += 24.0;
    let list_h = r.bottom() - y - 8.0;
    let rows = (list_h / 20.0).floor() as usize;
    for f in page.functions.iter().take(rows) {
        let addr = format!("{:08x}", f.va);
        let _ = fonts::draw_mid(
            c,
            r.x + 12.0,
            y + 10.0,
            &addr,
            10.0,
            T::SKY(),
            Weight::Normal,
            true,
        );
        let addr_w = fonts::measure("00000000", 10.0, Weight::Normal, true);
        let name = fit_text(f.name, r.w - 30.0 - addr_w, 11.0);
        let _ = fonts::draw_mid(
            c,
            r.x + 18.0 + addr_w,
            y + 10.0,
            &name,
            11.0,
            T::FG(),
            Weight::Normal,
            false,
        );
        y += 20.0;
    }
    if rows < page.functions.len() {
        let more = format!("+{} more", page.functions.len() - rows);
        fonts::draw_mid(
            c,
            r.x + 12.0,
            (y + 8.0).min(r.bottom() - 8.0),
            &more,
            10.0,
            T::FG_SUBTLEST(),
            Weight::Normal,
            false,
        );
    }
}

fn draw_hex(c: &mut Canvas, r: Rect, page: &RePage<'_>) {
    if page.hex.is_empty() {
        empty_view(c, r, "hex", "no bytes mapped at the entry point");
        return;
    }
    let lh = fonts::line_height(12.0, true);
    let rows = ((r.h - 16.0) / lh).floor() as usize;
    let mut y = r.y + 8.0;
    for line in page.hex.iter().take(rows) {
        // Offset in sky, rest neutral.
        let (off, rest) = line.split_once("  ").unwrap_or((line.as_str(), ""));
        let _ = fonts::draw_mid(c, r.x + 12.0, y + lh / 2.0, off, 12.0, T::SKY(), Weight::Normal, true);
        let off_w = fonts::measure("00000000", 12.0, Weight::Normal, true);
        let _ = fonts::draw_mid(
            c,
            r.x + 12.0 + off_w + 8.0,
            y + lh / 2.0,
            rest,
            12.0,
            T::FG(),
            Weight::Normal,
            true,
        );
        y += lh;
    }
}

fn draw_strings(c: &mut Canvas, r: Rect, page: &RePage<'_>) {
    if page.strings.is_empty() {
        empty_view(c, r, "search", "no strings — run `xencode re strings` for the full scan");
        return;
    }
    let lh = fonts::line_height(12.0, false);
    let rows = ((r.h - 16.0) / lh).floor() as usize;
    let mut y = r.y + 8.0;
    for s in page.strings.iter().take(rows) {
        let off = format!("{:08x}", s.offset);
        let _ = fonts::draw_mid(c, r.x + 12.0, y + lh / 2.0, &off, 11.0, T::SKY(), Weight::Normal, true);
        let off_w = fonts::measure("00000000", 11.0, Weight::Normal, true);
        if s.utf16 {
            let tag_w = 22.0;
            let tag = Rect::new(r.x + 12.0 + off_w + 8.0, y + lh / 2.0 - 8.0, tag_w, 16.0);
            c.fill_rrect(tag, 8.0, T::SECONDARY());
            fonts::draw_mid(c, tag.x + 5.0, y + lh / 2.0, "u16", 9.0, T::ICON_BLUE(), Weight::Normal, false);
            let text = fit_text(s.text, r.w - off_w - 60.0, 12.0);
            let _ = fonts::draw_mid(
                c,
                tag.right() + 8.0,
                y + lh / 2.0,
                &text,
                12.0,
                T::FG(),
                Weight::Normal,
                false,
            );
        } else {
            let text = fit_text(s.text, r.w - off_w - 30.0, 12.0);
            let _ = fonts::draw_mid(
                c,
                r.x + 12.0 + off_w + 8.0,
                y + lh / 2.0,
                &text,
                12.0,
                T::FG(),
                Weight::Normal,
                false,
            );
        }
        y += lh;
    }
}

fn draw_symbols(c: &mut Canvas, r: Rect, page: &RePage<'_>) {
    if page.symbols.is_empty() {
        empty_view(c, r, "list-filter", "stripped image — no symbol table");
        return;
    }
    let lh = fonts::line_height(12.0, false);
    let rows = ((r.h - 16.0) / lh).floor() as usize;
    let mut y = r.y + 8.0;
    for s in page.symbols.iter().take(rows) {
        let va = format!("{:08x}", s.va);
        let _ = fonts::draw_mid(c, r.x + 12.0, y + lh / 2.0, &va, 11.0, T::SKY(), Weight::Normal, true);
        let va_w = fonts::measure("00000000", 11.0, Weight::Normal, true);
        let kw = fonts::measure(s.kind, 10.0, Weight::Normal, true) + 12.0;
        let name = fit_text(s.name, r.w - va_w - kw - 44.0, 12.0);
        let _ = fonts::draw_mid(
            c,
            r.x + 12.0 + va_w + 10.0,
            y + lh / 2.0,
            &name,
            12.0,
            T::FG(),
            Weight::Normal,
            false,
        );
        let chip = Rect::new(r.right() - kw - 14.0, y + lh / 2.0 - 8.0, kw, 16.0);
        c.fill_rrect(chip, 8.0, T::SECONDARY());
        fonts::draw_mid(
            c,
            chip.x + 6.0,
            y + lh / 2.0,
            s.kind,
            10.0,
            T::FG_SUBTLE(),
            Weight::Normal,
            true,
        );
        y += lh;
    }
}

fn draw_imports(c: &mut Canvas, r: Rect, page: &RePage<'_>) {
    if page.imports.is_empty() {
        empty_view(c, r, "cable", "no import table (static image)");
        return;
    }
    let lh = fonts::line_height(12.0, false);
    let rows = ((r.h - 16.0) / lh).floor() as usize;
    let mut y = r.y + 8.0;
    for imp in page.imports.iter().take(rows) {
        let full = format!("{}!{}", imp.dll, imp.name);
        let shown = fit_text(&full, r.w - 24.0, 12.0);
        // dll part subtle, name bright — split on the rendered boundary.
        let dll_s = fit_text(imp.dll, (r.w - 24.0) * 0.4, 12.0);
        let _ = fonts::draw_mid(
            c,
            r.x + 12.0,
            y + lh / 2.0,
            &dll_s,
            12.0,
            T::FG_SUBTLE(),
            Weight::Normal,
            false,
        );
        let dw = fonts::measure(&dll_s, 12.0, Weight::Normal, false);
        let _ = fonts::draw_mid(
            c,
            r.x + 12.0 + dw,
            y + lh / 2.0,
            &format!("!{}", imp.name),
            12.0,
            T::FG(),
            Weight::Normal,
            false,
        );
        let _ = shown;
        y += lh;
    }
}

/// Package view (Phase 7.2): bundle identity rows, then chip flow of
/// permissions / components / abis / dex stats.
fn draw_package(c: &mut Canvas, r: Rect, page: &RePage<'_>) {
    draw_kv_chips(
        c,
        r,
        page.package_rows,
        page.package_chips,
        "package",
        "not an APK/IPA — no bundle manifest",
    );
}

/// The XEX identity view (Phase 7.3) — same KV+chips machinery, fed from
/// the XEX container structure parse.
fn draw_xex(c: &mut Canvas, r: Rect, page: &RePage<'_>) {
    draw_kv_chips(
        c,
        r,
        page.package_rows,
        page.package_chips,
        "xex",
        "not an XEX container — Xbox 360 images only",
    );
}

/// Shared key/value rows + wrapping chip flow behind Package and XEX.
fn draw_kv_chips(
    c: &mut Canvas,
    r: Rect,
    rows: &[ReKv<'_>],
    chips: &[ReChip<'_>],
    _icon: &str,
    empty_hint: &str,
) {
    if rows.is_empty() && chips.is_empty() {
        empty_view(c, r, "package", empty_hint);
        return;
    }
    let lh = fonts::line_height(12.0, false);
    let max_rows = ((r.h - 16.0) / lh).floor() as usize;

    // Key/value rows first.
    let mut y = r.y + 8.0;
    let mut drawn = 0usize;
    for kv in rows.iter() {
        if drawn >= max_rows {
            break;
        }
        let kw = 84.0;
        let _ = fonts::draw_mid(c, r.x + 12.0, y + lh / 2.0, kv.key, 11.0, T::SKY(), Weight::Normal, true);
        let value = fit_text(kv.value, r.w - kw - 36.0, 12.0);
        let _ = fonts::draw_mid(
            c,
            r.x + 12.0 + kw,
            y + lh / 2.0,
            &value,
            12.0,
            T::FG(),
            Weight::Normal,
            false,
        );
        y += lh;
        drawn += 1;
    }

    // Chip flow under the rows.
    if drawn < max_rows && !chips.is_empty() {
        y += 6.0;
        let mut x = r.x + 12.0;
        for chip in chips.iter() {
            let w = fonts::measure(chip.label, 10.0, Weight::Normal, false) + 16.0;
            if x + w > r.right() - 12.0 {
                // wrap
                x = r.x + 12.0;
                y += 22.0;
                if y + 16.0 > r.bottom() - 8.0 {
                    break;
                }
            }
            let rect = Rect::new(x, y, w, 18.0);
            c.fill_rrect(rect, 9.0, T::SECONDARY());
            let color = match chip.tone {
                ReChipTone::Sky => T::SKY(),
                ReChipTone::Subtle => T::FG_SUBTLE(),
                ReChipTone::Green => T::GIT_ADDED(),
            };
            fonts::draw_mid(c, rect.x + 8.0, y + 9.0, chip.label, 10.0, color, Weight::Normal, false);
            x += w + 6.0;
        }
    }

    // Truncation hint when rows didn't fit.
    if rows.len() > drawn {
        let more = format!(
            "+{} more rows — `xencode re apk/ipa/xex --json` for the full set",
            rows.len() - drawn
        );
        fonts::draw_mid(
            c,
            r.x + 12.0,
            (r.bottom() - 12.0).max(r.y + 12.0),
            &more,
            10.0,
            T::FG_SUBTLEST(),
            Weight::Normal,
            false,
        );
    }
}

fn empty_view(c: &mut Canvas, r: Rect, icon: &str, hint: &str) {
    c.icon(icon, r.cx() - 12.0, r.cy() - 30.0, 24.0, T::FG_SUBTLEST());
    let w = fonts::measure(hint, 12.0, Weight::Normal, false);
    fonts::draw_mid(
        c,
        r.cx() - w / 2.0,
        r.cy() + 6.0,
        hint,
        12.0,
        T::FG_SUBTLEST(),
        Weight::Normal,
        false,
    );
}

fn human_size(n: u64) -> String {
    if n >= 1024 * 1024 {
        format!("{:.1} MB", n as f64 / (1024.0 * 1024.0))
    } else if n >= 1024 {
        format!("{:.1} KB", n as f64 / 1024.0)
    } else {
        format!("{n} B")
    }
}

/// Truncate a string (with ellipsis) so it fits `max_w` logical px.
fn fit_text(text: &str, max_w: f32, px: f32) -> String {
    if fonts::measure(text, px, Weight::Normal, false) <= max_w {
        return text.to_string();
    }
    let chars: Vec<char> = text.chars().collect();
    let mut lo = 0usize;
    let mut hi = chars.len();
    while lo < hi {
        let mid = (lo + hi + 1) / 2;
        let candidate: String = chars[..mid].iter().collect::<String>() + "…";
        if fonts::measure(&candidate, px, Weight::Normal, false) <= max_w {
            lo = mid;
        } else {
            hi = mid - 1;
        }
    }
    let mut out: String = chars[..lo].iter().collect();
    if !out.is_empty() {
        out.push('…');
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sample_page() -> RePage<'static> {
        static HEX: [String; 2] = [
            String::new(),
            String::new(),
        ];
        // statics can't be inline here — use leaked boxes instead.
        let hex: &'static [String] = Box::leak(
            vec![
                "00400078  55 48 89 e5 48 83  |UH..H.|".to_string(),
                "00400088  c3                |.|".to_string(),
            ]
            .into_boxed_slice(),
        );
        let strings: &'static [ReString<'static>] = Box::leak(
            vec![
                ReString { offset: 0x90, text: "hello", utf16: false },
                ReString { offset: 0x96, text: "résumé", utf16: true },
            ]
            .into_boxed_slice(),
        );
        let symbols: &'static [ReSym<'static>] = Box::leak(
            vec![
                ReSym { va: 0x400078, name: "main", kind: "Func" },
                ReSym {
                    va: 0x400900,
                    name: "a_very_long_symbol_name_that_will_need_truncation_for_sure",
                    kind: "Func",
                },
            ]
            .into_boxed_slice(),
        );
        let imports: &'static [ReImport<'static>] = Box::leak(
            vec![
                ReImport { dll: "libc.so.6", name: "__libc_start_main" },
                ReImport { dll: "libm.so.6", name: "sqrt" },
            ]
            .into_boxed_slice(),
        );
        let sections: &'static [ReSection<'static>] = Box::leak(
            vec![
                ReSection { name: ".text", va: Some(0x400078), size: 9, exec: true },
                ReSection { name: ".rodata", va: Some(0x400090), size: 32, exec: false },
            ]
            .into_boxed_slice(),
        );
        let functions: &'static [ReFunction<'static>] = Box::leak(
            vec![ReFunction { va: 0x400078, name: "main" }].into_boxed_slice(),
        );
        let package_rows: &'static [ReKv<'static>] = Box::leak(
            vec![
                ReKv { key: "package", value: "com.xen.demo" },
                ReKv { key: "version", value: "0.7.0 (code 7)" },
                ReKv { key: "sdk", value: "min 24 \u{b7} target 34" },
                ReKv { key: "label", value: "XenDemo" },
            ]
            .into_boxed_slice(),
        );
        let package_chips: &'static [ReChip<'static>] = Box::leak(
            vec![
                ReChip { label: "android.permission.INTERNET", tone: ReChipTone::Subtle },
                ReChip { label: "android.permission.CAMERA", tone: ReChipTone::Subtle },
                ReChip { label: "activity com.xen.demo.App [launcher]", tone: ReChipTone::Sky },
                ReChip { label: "service com.xen.demo.SyncService", tone: ReChipTone::Sky },
                ReChip { label: "arm64-v8a", tone: ReChipTone::Green },
                ReChip { label: "x86_64", tone: ReChipTone::Green },
                ReChip { label: "dex: 2 classes / 3 methods", tone: ReChipTone::Subtle },
                ReChip { label: "signed v1+v2", tone: ReChipTone::Green },
            ]
            .into_boxed_slice(),
        );
        let _ = HEX;
        RePage {
            file: "/tmp/sample-binary-with-a-really-long-name-that-needs-truncation.elf",
            format: "ELF",
            arch: "x86_64",
            kind: "executable",
            entry: "0x00400078",
            size: 4096,
            tab: ReTab::Hex,
            hex,
            strings,
            symbols,
            imports,
            sections,
            functions,
            package_rows,
            package_chips,
            status: "analyzed in 4ms · xenre",
        }
    }

    #[test]
    fn re_tab_draws_all_views() {
        for tab in ReTab::ALL {
            let mut page = sample_page();
            page.tab = tab;
            let mut c = Canvas::new(1200.0, 800.0, 2.0);
            draw(&mut c, Rect::new(268.0, 52.0, 928.0, 740.0), &page);
        }
    }

    #[test]
    fn re_tab_draws_empty_views() {
        let mut page = sample_page();
        page.hex = &[];
        page.strings = &[];
        page.symbols = &[];
        page.imports = &[];
        for tab in ReTab::ALL {
            page.tab = tab;
            let mut c = Canvas::new(1200.0, 800.0, 2.0);
            draw(&mut c, Rect::new(268.0, 52.0, 928.0, 740.0), &page);
        }
    }

    #[test]
    fn xex_tab_renders_identity_rows_and_chips() {
        // Phase 7.3: the XEX identity view reuses the KV+chips machinery.
        let rows: &'static [ReKv<'static>] = Box::leak(
            vec![
                ReKv { key: "magic", value: "XEX2" },
                ReKv { key: "image", value: "0xae0000 @ load 0x82000000 (174 pages × 64.0 KB)" },
                ReKv { key: "entry", value: "0x821cba90" },
                ReKv { key: "codec", value: "enc aes · comp basic" },
                ReKv { key: "title", value: "0x535107db (SQ)" },
            ]
            .into_boxed_slice(),
        );
        let chips: &'static [ReChip<'static>] = Box::leak(
            vec![
                ReChip { label: "code 0x82010000 3.0 MB", tone: ReChipTone::Green },
                ReChip { label: "data 0x82040000 1.5 MB", tone: ReChipTone::Sky },
                ReChip { label: "import xboxkrnl.exe", tone: ReChipTone::Sky },
                ReChip { label: "XAPILIB 2.0.6534.0", tone: ReChipTone::Subtle },
                ReChip { label: "encrypted — structure only (no keys bundled)", tone: ReChipTone::Subtle },
            ]
            .into_boxed_slice(),
        );
        let mut page = sample_page();
        page.format = "XEX";
        page.arch = "ppc64";
        page.kind = "xbox 360";
        page.tab = ReTab::Xex;
        page.package_rows = rows;
        page.package_chips = chips;
        let mut c = Canvas::new(1200.0, 800.0, 2.0);
        draw(&mut c, Rect::new(268.0, 52.0, 928.0, 740.0), &page);
    }

    #[test]
    fn human_size_formatting() {
        assert_eq!(human_size(512), "512 B");
        assert_eq!(human_size(2048), "2.0 KB");
        assert_eq!(human_size(3 * 1024 * 1024), "3.0 MB");
    }

    #[test]
    fn fit_text_marks_ellipsis() {
        let fitted = fit_text("a_very_long_symbol_name_that_will_need_truncation_for_sure", 120.0, 11.0);
        assert!(fitted.ends_with('…'));
        assert!(fonts::measure(&fitted, 11.0, Weight::Normal, false) <= 121.0);
    }
}
