//! Software canvas: rounded rects, strokes, gradients, SVG-path fills/strokes,
//! lucide icons and the XenCode X logo — all drawn in LOGICAL px coordinates,
//! scaled by `s` (device pixel ratio) at raster time.

use crate::icons;
use crate::svgpath;
use crate::theme::T;
use tiny_skia::{
    Color, FillRule, GradientStop, LinearGradient, LineCap, LineJoin, Paint, PathBuilder, Pixmap,
    Point, Shader, SpreadMode, Stroke, Transform,
};

#[derive(Clone, Copy, Debug)]
pub struct Rect {
    pub x: f32,
    pub y: f32,
    pub w: f32,
    pub h: f32,
}

impl Rect {
    pub fn new(x: f32, y: f32, w: f32, h: f32) -> Self {
        Rect { x, y, w, h }
    }
    pub fn cx(&self) -> f32 {
        self.x + self.w / 2.0
    }
    pub fn cy(&self) -> f32 {
        self.y + self.h / 2.0
    }
    pub fn right(&self) -> f32 {
        self.x + self.w
    }
    pub fn bottom(&self) -> f32 {
        self.y + self.h
    }
    pub fn inset(&self, d: f32) -> Rect {
        Rect::new(self.x + d, self.y + d, self.w - 2.0 * d, self.h - 2.0 * d)
    }
}

pub struct Canvas {
    pub px: Pixmap,
    pub s: f32,
    pub w: f32,
    pub h: f32,
}

const KAPPA: f32 = 0.5522847498;

/// Rounded-rect path with per-corner radii (tl, tr, br, bl), logical units.
fn rrect_path(x: f32, y: f32, w: f32, h: f32, tl: f32, tr: f32, br: f32, bl: f32) -> Option<tiny_skia::Path> {
    let tl = tl.max(0.0).min(w / 2.0).min(h / 2.0);
    let tr = tr.max(0.0).min(w / 2.0).min(h / 2.0);
    let br = br.max(0.0).min(w / 2.0).min(h / 2.0);
    let bl = bl.max(0.0).min(w / 2.0).min(h / 2.0);
    let mut pb = PathBuilder::new();
    pb.move_to(x + tl, y);
    pb.line_to(x + w - tr, y);
    if tr > 0.0 {
        let k = KAPPA * tr;
        pb.cubic_to(x + w - tr + k, y, x + w, y + tr - k, x + w, y + tr);
    }
    pb.line_to(x + w, y + h - br);
    if br > 0.0 {
        let k = KAPPA * br;
        pb.cubic_to(x + w, y + h - br + k, x + w - br + k, y + h, x + w - br, y + h);
    }
    pb.line_to(x + bl, y + h);
    if bl > 0.0 {
        let k = KAPPA * bl;
        pb.cubic_to(x + bl - k, y + h, x, y + h - bl + k, x, y + h - bl);
    }
    pb.line_to(x, y + tl);
    if tl > 0.0 {
        let k = KAPPA * tl;
        pb.cubic_to(x, y + tl - k, x + tl - k, y, x + tl, y);
    }
    pb.close();
    pb.finish()
}

fn paint(color: Color) -> Paint<'static> {
    let mut p = Paint::default();
    p.shader = Shader::SolidColor(color);
    p.anti_alias = true;
    p
}

fn stroke(width: f32) -> Stroke {
    Stroke {
        width,
        line_cap: LineCap::Round,
        line_join: LineJoin::Round,
        ..Default::default()
    }
}

impl Canvas {
    pub fn new(w: f32, h: f32, s: f32) -> Self {
        let px = Pixmap::new((w * s) as u32, (h * s) as u32).expect("pixmap");
        Canvas { px, s, w, h }
    }

    pub fn tr(&self) -> Transform {
        Transform::from_scale(self.s, self.s)
    }

    pub fn fill_all(&mut self, color: Color) {
        self.px.fill(color);
    }

    pub fn fill_rect(&mut self, r: Rect, color: Color) {
        let path = rrect_path(r.x, r.y, r.w, r.h, 0.0, 0.0, 0.0, 0.0).unwrap();
        self.px.fill_path(&path, &paint(color), FillRule::Winding, self.tr(), None);
    }

    pub fn fill_rrect(&mut self, r: Rect, radius: f32, color: Color) {
        self.fill_rrect_radii(r, radius, radius, radius, radius, color);
    }

    pub fn fill_rrect_radii(&mut self, r: Rect, tl: f32, tr: f32, br: f32, bl: f32, color: Color) {
        let Some(path) = rrect_path(r.x, r.y, r.w, r.h, tl, tr, br, bl) else { return };
        self.px.fill_path(&path, &paint(color), FillRule::Winding, self.tr(), None);
    }

    pub fn stroke_rrect(&mut self, r: Rect, radius: f32, width: f32, color: Color) {
        let Some(path) = rrect_path(r.x, r.y, r.w, r.h, radius, radius, radius, radius) else { return };
        self.px.stroke_path(&path, &paint(color), &stroke(width), self.tr(), None);
    }

    /// Fill + stroke in one call so the border doesn't halve the fill.
    pub fn card(&mut self, r: Rect, radius: f32, fill: Color, border: Color, bw: f32) {
        self.fill_rrect(r, radius, fill);
        self.stroke_rrect(r, radius, bw, border);
    }

    pub fn fill_circle(&mut self, cx: f32, cy: f32, radius: f32, color: Color) {
        self.fill_rrect(Rect::new(cx - radius, cy - radius, radius * 2.0, radius * 2.0), radius, color);
    }

    pub fn stroke_circle(&mut self, cx: f32, cy: f32, radius: f32, width: f32, color: Color) {
        self.stroke_rrect(
            Rect::new(cx - radius, cy - radius, radius * 2.0, radius * 2.0),
            radius,
            width,
            color,
        );
    }

    pub fn line(&mut self, x1: f32, y1: f32, x2: f32, y2: f32, width: f32, color: Color) {
        let mut pb = PathBuilder::new();
        pb.move_to(x1, y1);
        pb.line_to(x2, y2);
        let Some(path) = pb.finish() else { return };
        self.px.stroke_path(&path, &paint(color), &stroke(width), self.tr(), None);
    }

    pub fn fill_d(&mut self, d: &str, tr: Transform, color: Color) {
        let Some(path) = svgpath::build(d, tr) else { return };
        self.px.fill_path(&path, &paint(color), FillRule::Winding, Transform::identity(), None);
    }

    pub fn stroke_d(&mut self, d: &str, tr: Transform, width: f32, color: Color) {
        let Some(path) = svgpath::build(d, tr) else { return };
        self.px.stroke_path(&path, &paint(color), &stroke(width), Transform::identity(), None);
    }

    fn stroke_d_shader(&mut self, d: &str, tr: Transform, width: f32, shader: Shader) {
        let Some(path) = svgpath::build(d, tr) else { return };
        let mut p = Paint::default();
        p.shader = shader;
        p.anti_alias = true;
        self.px.stroke_path(&path, &p, &stroke(width), Transform::identity(), None);
    }

    /// Lucide icon: 24x24 stroke-2 grid fitted to (x, y, size), round caps/joins.
    pub fn icon(&mut self, name: &str, x: f32, y: f32, size: f32, color: Color) {
        let Some(d) = icons::icon_path(name) else {
            eprintln!("warning: unknown icon '{name}'");
            return;
        };
        self.icon_d(d, x, y, size, color);
    }

    pub fn icon_d(&mut self, d: &str, x: f32, y: f32, size: f32, color: Color) {
        let sc = size / 24.0;
        // device = (logical * sc + (x,y)) * s  -> explicit affine row
        let tr = Transform::from_row(
            self.s * sc, 0.0, 0.0, self.s * sc, x * self.s, y * self.s,
        );
        self.stroke_d(d, tr, 2.0, color);
    }

    pub fn icon_d_fill(&mut self, d: &str, x: f32, y: f32, size: f32, color: Color) {
        let sc = size / 24.0;
        let tr = Transform::from_row(
            self.s * sc, 0.0, 0.0, self.s * sc, x * self.s, y * self.s,
        );
        self.fill_d(d, tr, color);
    }

    /// Window control icons share the lucide 24-grid (2px strokes).
    pub fn window_icon(&mut self, d: &str, x: f32, y: f32, size: f32, color: Color) {
        self.icon_d(d, x, y, size, color);
    }

    /// XenCode X logo, filled, fitted into a square box.
    pub fn x_logo(&mut self, x: f32, y: f32, size: f32, color: Color) {
        let sc = size / 256.0;
        let tr = Transform::from_row(
            self.s * sc, 0.0, 0.0, self.s * sc, x * self.s, y * self.s,
        );
        self.fill_d(icons::X_LOGO_PATH, tr, color);
    }

    /// Draft-screen watermark: gradient-stroked X (replicates packages/ui/src/assets/Z.svg).
    pub fn x_watermark(&mut self, r: Rect) {
        // SVG: viewBox 436x360, path at translate(83.6 45.6) scale(1.05)
        let kx = r.w / 436.0;
        let ky = r.h / 360.0;
        let dev = self.s;
        let tr = Transform::from_row(
            1.05 * kx * dev,
            0.0,
            0.0,
            1.05 * ky * dev,
            (r.x + 83.6 * kx) * dev,
            (r.y + 45.6 * ky) * dev,
        );
        // faint halo (fake blur)
        self.stroke_d_shader(
            icons::X_LOGO_PATH,
            tr,
            6.0,
            Shader::SolidColor(Color::from_rgba8(0x44, 0x44, 0x44, 8)),
        );
        let stops = vec![
            GradientStop::new(0.0, Color::from_rgba8(0x44, 0x44, 0x44, 153)),
            GradientStop::new(0.5, Color::from_rgba8(0x2a, 0x2a, 0x2a, 77)),
            GradientStop::new(1.0, Color::from_rgba8(0x1a, 0x1a, 0x1a, 26)),
        ];
        let start = Point { x: (r.x + 217.97 * kx) * dev, y: (r.y + 20.5 * ky) * dev };
        let end = Point { x: (r.x + 217.97 * kx) * dev, y: (r.y + 340.5 * ky) * dev };
        if let Some(shader) = LinearGradient::new(start, end, stops, SpreadMode::Pad, Transform::identity()) {
            self.stroke_d_shader(icons::X_LOGO_PATH, tr, 1.0, shader);
        }
    }

    /// Vertical-gradient rounded rect (splash badge).
    pub fn fill_rrect_vgrad(&mut self, r: Rect, radius: f32, top: Color, bottom: Color) {
        let stops = vec![
            GradientStop::new(0.0, top),
            GradientStop::new(1.0, bottom),
        ];
        let start = Point { x: r.x, y: r.y };
        let end = Point { x: r.x, y: r.bottom() };
        let shader = LinearGradient::new(start, end, stops, SpreadMode::Pad, Transform::identity());
        let Some(path) = rrect_path(r.x, r.y, r.w, r.h, radius, radius, radius, radius) else { return };
        match shader {
            Some(sh) => {
                let mut p = Paint::default();
                p.shader = sh;
                p.anti_alias = true;
                // gradient coordinates are logical; map to device
                self.px.fill_path(&path, &p, FillRule::Winding, self.tr(), None);
            }
            None => self.fill_rrect(r, radius, top),
        }
    }

    /// Context-usage ring (ChatContextUsage): track + progress arc.
    pub fn ring(&mut self, cx: f32, cy: f32, radius: f32, width: f32, frac: f32, track: Color, fill: Color) {
        self.stroke_circle(cx, cy, radius, width, track);
        if frac <= 0.0 {
            return;
        }
        let frac = frac.min(1.0);
        let total = std::f32::consts::PI * 2.0 * frac;
        let steps = ((total / 0.2).ceil() as usize).max(2);
        let mut pb = PathBuilder::new();
        let start = -std::f32::consts::FRAC_PI_2;
        for i in 0..=steps {
            let a = start + total * (i as f32 / steps as f32);
            let (s_, c_) = a.sin_cos();
            let px_x = cx + radius * c_;
            let py = cy + radius * s_;
            if i == 0 {
                pb.move_to(px_x, py);
            } else {
                pb.line_to(px_x, py);
            }
        }
        if let Some(path) = pb.finish() {
            self.px.stroke_path(&path, &paint(fill), &stroke(width), self.tr(), None);
        }
    }

    /// Window titlebar logo badge: 20px rounded gradient square + white X.
    pub fn logo_badge(&mut self, x: f32, y: f32, size: f32) {
        self.fill_rrect_vgrad(Rect::new(x, y, size, size), size * 0.3, T::SPLASH_TOP(), T::SPLASH_BOTTOM());
        self.stroke_rrect(Rect::new(x, y, size, size), size * 0.3, 1.0, T::SPLASH_RING());
        let inner = size * 0.62;
        self.x_logo(x + (size - inner) / 2.0, y + (size - inner) / 2.0, inner, T::WHITE());
    }
}
