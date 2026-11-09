//! Text shaping/rasterizing on top of ab_glyph.
//! UI font: Inter 400/500/600/700 (vendored, closest free match to the
//! `ui-sans-serif, system-ui` stack ZCode renders with on macOS/Windows).
//! Mono: DejaVu Sans Mono (≈ the Consolas slot of the ZCode --font-mono stack),
//! with a runtime CJK fallback (Noto Sans SC) for non-Latin glyphs.

use crate::gfx::Canvas;
use ab_glyph::{Font, FontVec, Glyph, PxScale};
use std::sync::OnceLock;
use tiny_skia::Color;

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Weight {
    Normal,   // 400
    Medium,   // 500 (Tailwind font-medium)
    SemiBold, // 600 (Tailwind font-semibold)
    Bold,     // 700
}

struct Fonts {
    ui: [FontVec; 4],       // [400, 500, 600, 700]
    mono: [FontVec; 2],     // [400, 700]
    ui_cjk: Option<FontVec>, // runtime fallback for CJK glyphs
}

static FONTS: OnceLock<Fonts> = OnceLock::new();

const UI_400: &[u8] = include_bytes!("../assets/fonts/Inter-Regular.ttf");
const UI_500: &[u8] = include_bytes!("../assets/fonts/Inter-Medium.ttf");
const UI_600: &[u8] = include_bytes!("../assets/fonts/Inter-SemiBold.ttf");
const UI_700: &[u8] = include_bytes!("../assets/fonts/Inter-Bold.ttf");

fn load_bytes(bytes: &[u8]) -> Option<FontVec> {
    FontVec::try_from_vec(bytes.to_vec()).ok()
}

fn load(paths: &[&str]) -> Option<FontVec> {
    for p in paths {
        if let Ok(bytes) = std::fs::read(p) {
            if let Some(f) = load_bytes(&bytes) {
                return Some(f);
            }
        }
    }
    None
}

fn build_fonts() -> Fonts {
    let ui = [
        load_bytes(UI_400).expect("Inter-Regular missing"),
        load_bytes(UI_500).expect("Inter-Medium missing"),
        load_bytes(UI_600).expect("Inter-SemiBold missing"),
        load_bytes(UI_700).expect("Inter-Bold missing"),
    ];
    let mono = [
        load(&[
            "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf",
            "/usr/share/fonts/truetype/chinese/SarasaMonoSC-Regular.ttf",
        ])
        .or_else(|| load_bytes(UI_400))
        .expect("mono font not found"),
        load(&[
            "/usr/share/fonts/truetype/dejavu/DejaVuSansMono-Bold.ttf",
            "/usr/share/fonts/truetype/chinese/SarasaMonoSC-Bold.ttf",
        ])
        .or_else(|| load_bytes(UI_700))
        .expect("mono bold font not found"),
    ];
    let ui_cjk = load(&[
        "/usr/share/fonts/truetype/chinese/NotoSansSC-Regular.ttf",
        "/usr/share/fonts/truetype/noto-serif-sc/NotoSerifSC-Regular.ttf",
    ]);
    Fonts { ui, mono, ui_cjk }
}

fn fonts() -> &'static Fonts {
    FONTS.get_or_init(build_fonts)
}

fn ui_index(weight: Weight) -> usize {
    match weight {
        Weight::Normal => 0,
        Weight::Medium => 1,
        Weight::SemiBold => 2,
        Weight::Bold => 3,
    }
}

/// Resolve the font + glyph id for a char, with CJK fallback for the UI face.
fn resolve(mono: bool, weight: Weight, ch: char) -> (&'static FontVec, ab_glyph::GlyphId) {
    let f = fonts();
    let font: &FontVec = if mono {
        &f.mono[if weight == Weight::Bold { 1 } else { 0 }]
    } else {
        &f.ui[ui_index(weight)]
    };
    let id = font.glyph_id(ch);
    if id.0 == 0 && !mono && !ch.is_control() && ch != ' ' {
        if let Some(cjk) = &f.ui_cjk {
            let cid = cjk.glyph_id(ch);
            if cid.0 != 0 {
                return (cjk, cid);
            }
        }
    }
    (font, id)
}

struct Metrics {
    ascent: f32,
    descent: f32, // negative
    k: f32,       // device px per font unit
}

fn metrics(font: &FontVec, px_dev: f32) -> Metrics {
    let k = px_dev / font.height_unscaled();
    Metrics {
        ascent: font.ascent_unscaled() * k,
        descent: font.descent_unscaled() * k,
        k,
    }
}

pub fn measure(text: &str, px: f32, weight: Weight, mono: bool) -> f32 {
    let px_dev = px;
    let mut w = 0.0f32;
    for ch in text.chars() {
        let (font, id) = resolve(mono, weight, ch);
        let k = px_dev / font.height_unscaled();
        w += font.h_advance_unscaled(id) * k;
    }
    w
}

pub fn line_height(px: f32, mono: bool) -> f32 {
    if mono {
        px * 1.55
    } else {
        px * 1.45
    }
}

/// Draw a single-line run. `y` is the TOP of the line box (baseline is derived
/// from the font metrics). Returns the advance width in logical px.
pub fn draw(
    c: &mut Canvas,
    x: f32,
    y: f32,
    text: &str,
    px: f32,
    color: Color,
    weight: Weight,
    mono: bool,
) -> f32 {
    if text.is_empty() {
        return 0.0;
    }
    let s = c.s;
    let px_dev = px * s;

    // pre-resolve glyphs (per-char font may switch for CJK)
    let mut runs: Vec<(&'static FontVec, ab_glyph::GlyphId, f32)> = Vec::with_capacity(text.len());
    let mut w_dev = 0.0f32;
    for ch in text.chars() {
        let (font, id) = resolve(mono, weight, ch);
        let k = px_dev / font.height_unscaled();
        let adv = font.h_advance_unscaled(id) * k;
        runs.push((font, id, adv));
        w_dev += adv;
    }
    if w_dev <= 0.0 {
        return 0.0;
    }

    let first = runs[0].0;
    let m = metrics(first, px_dev);
    let baseline = y * s + m.ascent;

    let bw = (w_dev.ceil() as i32 + 8).max(1) as usize;
    let bh = ((m.ascent - m.descent).ceil() as i32 + 8).max(1) as usize;
    let bx0 = (x * s).floor() as i32 - 4;
    let by0 = baseline.floor() as i32 - m.ascent.ceil() as i32 - 4;

    let mut cov = vec![0.0f32; bw * bh];
    let mut pen = x * s;
    for (font, id, adv) in &runs {
        let scale = PxScale::from(px_dev);
        let glyph = Glyph { id: *id, scale, position: ab_glyph::Point { x: pen, y: baseline } };
        if let Some(outlined) = font.outline_glyph(glyph) {
            let pb = outlined.px_bounds();
            outlined.draw(|gx, gy, gcov| {
                let ix = pb.min.x as i32 + gx as i32 - bx0;
                let iy = pb.min.y as i32 + gy as i32 - by0;
                if ix >= 0 && iy >= 0 {
                    let (ix, iy) = (ix as usize, iy as usize);
                    if ix < bw && iy < bh {
                        let idx = iy * bw + ix;
                        cov[idx] += gcov;
                    }
                }
            });
        }
        pen += *adv;
    }

    // composite the coverage buffer once (prevents double-blending overlaps)
    let (cr, cg, cb, ca) = (color.red(), color.green(), color.blue(), color.alpha());
    let ox = bx0.max(0) as u32;
    let oy = by0.max(0) as u32;
    let (width, height) = (c.px.width(), c.px.height());
    let pixels = c.px.pixels_mut();
    for iy in 0..bh {
        let py = oy + iy as u32;
        if py >= height {
            break;
        }
        for ix in 0..bw {
            let px_x = ox + ix as u32;
            if px_x >= width {
                break;
            }
            let a = cov[iy * bw + ix];
            if a <= 0.0 {
                continue;
            }
            let a = (a * ca).min(1.0);
            let q = &mut pixels[(py * width + px_x) as usize];
            let da = q.alpha() as f32 / 255.0;
            let out_a = a + da * (1.0 - a);
            if out_a <= 0.0 {
                continue;
            }
            let blend = |sc: f32, dc: f32| -> u8 {
                let v = (sc * a + dc * da * (1.0 - a)) / out_a;
                (v * 255.0).round().clamp(0.0, 255.0) as u8
            };
            let nr = blend(cr, q.red() as f32 / 255.0);
            let ng = blend(cg, q.green() as f32 / 255.0);
            let nb = blend(cb, q.blue() as f32 / 255.0);
            let out_a8 = (out_a * 255.0).round().clamp(0.0, 255.0) as u8;
            *q = tiny_skia::ColorU8::from_rgba(nr, ng, nb, out_a8).premultiply();
        }
    }

    w_dev / s
}

/// Draw text vertically centered on `cy` (optical centering).
pub fn draw_mid(
    c: &mut Canvas,
    x: f32,
    cy: f32,
    text: &str,
    px: f32,
    color: Color,
    weight: Weight,
    mono: bool,
) -> f32 {
    let top = cy - px * 0.72;
    draw(c, x, top, text, px, color, weight, mono)
}

/// Draw text centered horizontally in [x, x+w], vertically centered on cy.
pub fn draw_center(
    c: &mut Canvas,
    x: f32,
    w: f32,
    cy: f32,
    text: &str,
    px: f32,
    color: Color,
    weight: Weight,
    mono: bool,
) -> f32 {
    let tw = measure(text, px, weight, mono);
    draw_mid(c, x + (w - tw) / 2.0, cy, text, px, color, weight, mono)
}

/// Greedy word wrap.
pub fn wrap(text: &str, max_w: f32, px: f32, weight: Weight, mono: bool) -> Vec<String> {
    let mut lines = Vec::new();
    for para in text.split('\n') {
        let mut cur = String::new();
        for word in para.split(' ') {
            let candidate = if cur.is_empty() { word.to_string() } else { format!("{cur} {word}") };
            if measure(&candidate, px, weight, mono) <= max_w || cur.is_empty() {
                cur = candidate;
            } else {
                lines.push(std::mem::take(&mut cur));
                cur = word.to_string();
            }
        }
        lines.push(cur);
    }
    lines
}

pub fn wrap_width(lines: &[String], px: f32, weight: Weight, mono: bool) -> f32 {
    lines.iter().map(|l| measure(l, px, weight, mono)).fold(0.0f32, f32::max)
}
