//! Minimal geometric icons drawn with egui painters.
//! Fidelity note: ZCode uses Lucide icons; these are matching geometric
//! approximations (same stroke weight 1.6, same optical size) until we
//! embed Lucide vector paths.

// the full set is ported ahead of the screens that use them
#![allow(dead_code)]

use egui::{Color32, Painter, Pos2, Rect, Response, Sense, Stroke, Vec2};

const STROKE: f32 = 1.6;

pub fn plus(p: &Painter, c: Rect, color: Color32) {
    let center = c.center();
    let r = c.size().min_elem() * 0.32;
    p.line_segment(
        [center - Vec2::X * r, center + Vec2::X * r],
        Stroke::new(STROKE, color),
    );
    p.line_segment(
        [center - Vec2::Y * r, center + Vec2::Y * r],
        Stroke::new(STROKE, color),
    );
}

pub fn chevron_left(p: &Painter, c: Rect, color: Color32) {
    let center = c.center();
    let s = c.size().min_elem() * 0.3;
    p.add(egui::Shape::line(
        vec![
            Pos2::new(center.x + s * 0.6, center.y - s),
            Pos2::new(center.x - s * 0.6, center.y),
            Pos2::new(center.x + s * 0.6, center.y + s),
        ],
        Stroke::new(STROKE, color),
    ));
}

pub fn chevron_down(p: &Painter, c: Rect, color: Color32) {
    let center = c.center();
    let s = c.size().min_elem() * 0.3;
    p.add(egui::Shape::line(
        vec![
            Pos2::new(center.x - s, center.y - s * 0.6),
            Pos2::new(center.x, center.y + s * 0.6),
            Pos2::new(center.x + s, center.y - s * 0.6),
        ],
        Stroke::new(STROKE, color),
    ));
}

pub fn chevron_right(p: &Painter, c: Rect, color: Color32) {
    let center = c.center();
    let s = c.size().min_elem() * 0.3;
    p.add(egui::Shape::line(
        vec![
            Pos2::new(center.x - s * 0.6, center.y - s),
            Pos2::new(center.x + s * 0.6, center.y),
            Pos2::new(center.x - s * 0.6, center.y + s),
        ],
        Stroke::new(STROKE, color),
    ));
}

pub fn folder(p: &Painter, c: Rect, color: Color32) {
    let r = c.shrink2(Vec2::new(c.width() * 0.15, c.height() * 0.22));
    let tab_w = r.width() * 0.42;
    let tab_h = r.height() * 0.22;
    let mut pts = vec![
        Pos2::new(r.left(), r.top() + tab_h),
        Pos2::new(r.left(), r.bottom()),
        Pos2::new(r.right(), r.bottom()),
        Pos2::new(r.right(), r.top() + tab_h),
        Pos2::new(r.left() + tab_w, r.top() + tab_h),
        Pos2::new(r.left() + tab_w + r.height() * 0.10, r.top()),
        Pos2::new(r.left(), r.top()),
    ];
    pts.pop(); // close shape visually: keep top-left implicit
    p.add(egui::Shape::line(pts, Stroke::new(STROKE, color)));
}

pub fn gear(p: &Painter, c: Rect, color: Color32) {
    let center = c.center();
    let r = c.size().min_elem() * 0.34;
    p.circle_stroke(center, r * 0.45, Stroke::new(STROKE, color));
    for i in 0..8 {
        let a = i as f32 * std::f32::consts::TAU / 8.0 + 0.39;
        let dir = Vec2::new(a.cos(), a.sin());
        p.line_segment(
            [center + dir * (r * 0.68), center + dir * r],
            Stroke::new(STROKE, color),
        );
    }
}

pub fn kebab(p: &Painter, c: Rect, color: Color32) {
    let center = c.center();
    let r = c.size().min_elem() * 0.08;
    for (dx, dy) in [(-1.0f32, 0.0), (0.0, 0.0), (1.0, 0.0)] {
        p.circle_filled(
            Pos2::new(center.x + dx * r * 4.0, center.y + dy * r * 4.0),
            r,
            color,
        );
    }
}

pub fn clock(p: &Painter, c: Rect, color: Color32) {
    let center = c.center();
    let r = c.size().min_elem() * 0.36;
    p.circle_stroke(center, r, Stroke::new(STROKE, color));
    p.line_segment(
        [center, center + Vec2::new(0.0, -r * 0.62)],
        Stroke::new(STROKE, color),
    );
    p.line_segment(
        [center, center + Vec2::new(r * 0.5, r * 0.18)],
        Stroke::new(STROKE, color),
    );
}

pub fn paperclip(p: &Painter, c: Rect, color: Color32) {
    let center = c.center();
    let h = c.height() * 0.34;
    p.add(egui::Shape::line(
        vec![
            Pos2::new(center.x - h * 0.25, center.y + h),
            Pos2::new(center.x - h * 0.25, center.y - h * 0.6),
            Pos2::new(center.x + h * 0.35, center.y - h),
            Pos2::new(center.x + h * 0.35, center.y + h * 0.35),
            Pos2::new(center.x - h * 0.02, center.y + h * 0.62),
        ],
        Stroke::new(STROKE, color),
    ));
}

pub fn arrow_up(p: &Painter, c: Rect, color: Color32) {
    let center = c.center();
    let s = c.size().min_elem() * 0.38;
    p.line_segment(
        [center - Vec2::Y * s, center + Vec2::Y * s],
        Stroke::new(STROKE + 0.4, color),
    );
    p.add(egui::Shape::line(
        vec![
            Pos2::new(center.x - s * 0.7, center.y + s * 0.1),
            Pos2::new(center.x, center.y - s),
            Pos2::new(center.x + s * 0.7, center.y + s * 0.1),
        ],
        Stroke::new(STROKE + 0.4, color),
    ));
}

/// Four-point sparkle (assistant / AI actions)
pub fn sparkle(p: &Painter, c: Rect, color: Color32) {
    let center = c.center();
    let s = c.size().min_elem() * 0.42;
    let pts = [
        Pos2::new(center.x, center.y - s),
        Pos2::new(center.x + s * 0.28, center.y - s * 0.28),
        Pos2::new(center.x + s, center.y),
        Pos2::new(center.x + s * 0.28, center.y + s * 0.28),
        Pos2::new(center.x, center.y + s),
        Pos2::new(center.x - s * 0.28, center.y + s * 0.28),
        Pos2::new(center.x - s, center.y),
        Pos2::new(center.x - s * 0.28, center.y - s * 0.28),
    ];
    p.add(egui::Shape::convex_polygon(
        pts.to_vec(),
        color,
        Stroke::NONE,
    ));
}

pub fn zap(p: &Painter, c: Rect, color: Color32) {
    let center = c.center();
    let s = c.size().min_elem() * 0.4;
    p.add(egui::Shape::convex_polygon(
        Vec::from([
            Pos2::new(center.x + s * 0.35, center.y - s),
            Pos2::new(center.x - s * 0.55, center.y + s * 0.12),
            Pos2::new(center.x - s * 0.02, center.y + s * 0.12),
            Pos2::new(center.x - s * 0.35, center.y + s),
            Pos2::new(center.x + s * 0.55, center.y - s * 0.12),
            Pos2::new(center.x + s * 0.02, center.y - s * 0.12),
        ]),
        color,
        Stroke::NONE,
    ));
}

/// XenCode logo mark: white X with two lightning cuts (from the brand webp).
pub fn x_logo(p: &Painter, c: Rect, color: Color32) {
    let s = c.size().min_elem();
    let w = s * 0.26; // bar thickness
    let half = s * 0.44;
    let center = c.center();
    let stroke = Stroke::new(w, color);
    // two crossing bars
    p.line_segment(
        [
            center + Vec2::new(-half, -half) * 0.92,
            center + Vec2::new(half, half) * 0.92,
        ],
        stroke,
    );
    p.line_segment(
        [
            center + Vec2::new(half, -half) * 0.92,
            center + Vec2::new(-half, half) * 0.92,
        ],
        stroke,
    );
    // lightning cuts (background-colored slits, matching the brand mark)
    let cut = Stroke::new(w * 0.30, Color32::from_rgb(0x0a, 0x0a, 0x0a));
    p.add(egui::Shape::line(
        vec![
            Pos2::new(center.x - half * 0.52, center.y + half * 0.86),
            Pos2::new(center.x - half * 0.10, center.y + half * 0.05),
            Pos2::new(center.x - half * 0.46, center.y + half * 0.14),
        ],
        cut,
    ));
    p.add(egui::Shape::line(
        vec![
            Pos2::new(center.x + half * 0.60, center.y - half * 0.80),
            Pos2::new(center.x + half * 0.12, center.y + half * 0.10),
            Pos2::new(center.x + half * 0.52, center.y + half * 0.02),
        ],
        cut,
    ));
}

// ---------- settings nav icons (Lucide-style, stroke 1.6) ----------

pub fn sliders(p: &Painter, c: Rect, color: Color32) {
    let s = Stroke::new(STROKE, color);
    let (l, _cx, r) = (c.left() + 3.0, c.center().x, c.right() - 3.0);
    for (y, kx) in [
        (c.top() + c.height() * 0.22, l + c.width() * 0.45),
        (c.center().y, l + c.width() * 0.20),
        (c.bottom() - c.height() * 0.22, l + c.width() * 0.62),
    ] {
        p.line_segment([Pos2::new(l, y), Pos2::new(r, y)], s);
        p.circle_filled(Pos2::new(kx, y), 2.6, crate::theme::NEUTRAL_950);
        p.circle_stroke(Pos2::new(kx, y), 2.6, s);
    }
}

pub fn palette(p: &Painter, c: Rect, color: Color32) {
    let s = Stroke::new(STROKE, color);
    let center = c.center();
    let r = c.size().min_elem() * 0.36;
    p.circle_stroke(center, r, s);
    for (i, a) in [0.6f32, 1.6, 2.6, 3.8, 4.9].iter().enumerate() {
        let ang = a - std::f32::consts::FRAC_PI_2;
        let inner = r - if i == 4 { 0.0 } else { 3.2 };
        p.line_segment(
            [
                center + Vec2::new(ang.cos() * inner * 0.45, ang.sin() * inner * 0.45),
                center + Vec2::new(ang.cos() * inner, ang.sin() * inner),
            ],
            s,
        );
    }
}

pub fn package(p: &Painter, c: Rect, color: Color32) {
    let s = Stroke::new(STROKE, color);
    let cx = c.center().x;
    let top = c.top() + c.height() * 0.18;
    let bot = c.bottom() - c.height() * 0.18;
    let (l, r) = (cx - c.width() * 0.3, cx + c.width() * 0.3);
    p.add(egui::Shape::line(
        vec![
            Pos2::new(cx, top - c.height() * 0.02),
            Pos2::new(r, top + c.height() * 0.09),
            Pos2::new(r, bot - c.height() * 0.09),
            Pos2::new(cx, bot),
            Pos2::new(l, bot - c.height() * 0.09),
            Pos2::new(l, top + c.height() * 0.09),
            Pos2::new(cx, top - c.height() * 0.02),
        ],
        s,
    ));
    p.line_segment(
        [
            Pos2::new(l, top + c.height() * 0.09),
            Pos2::new(cx, top + c.height() * 0.2),
        ],
        s,
    );
    p.line_segment(
        [
            Pos2::new(cx, top + c.height() * 0.2),
            Pos2::new(r, top + c.height() * 0.09),
        ],
        s,
    );
    p.line_segment(
        [Pos2::new(cx, top + c.height() * 0.2), Pos2::new(cx, bot)],
        s,
    );
}

pub fn globe(p: &Painter, c: Rect, color: Color32) {
    let s = Stroke::new(STROKE, color);
    let center = c.center();
    let r = c.size().min_elem() * 0.36;
    p.circle_stroke(center, r, s);
    p.line_segment(
        [
            Pos2::new(center.x - r, center.y),
            Pos2::new(center.x + r, center.y),
        ],
        s,
    );
    let squish = |dy: f32| Vec2::new((r * r - dy * dy).max(0.0).sqrt() * 0.8, dy);
    let dy1 = r * 0.62;
    p.add(egui::Shape::line(
        vec![center - squish(dy1), center + squish(dy1)],
        s,
    ));
    p.add(egui::Shape::line(
        vec![center - squish(-dy1), center + squish(-dy1)],
        s,
    ));
}

pub fn hard_drive(p: &Painter, c: Rect, color: Color32) {
    let s = Stroke::new(STROKE, color);
    let (l, r) = (c.left() + c.width() * 0.18, c.right() - c.width() * 0.18);
    let m = c.center().y;
    let (t, b) = (c.top() + c.height() * 0.2, c.bottom() - c.height() * 0.2);
    p.line_segment([Pos2::new(l, t), Pos2::new(r, t)], s);
    p.line_segment([Pos2::new(l, b), Pos2::new(r, b)], s);
    p.line_segment([Pos2::new(l, t), Pos2::new(l, b)], s);
    p.line_segment([Pos2::new(r, t), Pos2::new(r, b)], s);
    p.line_segment([Pos2::new(l, m), Pos2::new(r, m)], s);
    p.circle_filled(Pos2::new(r - 4.0, m + c.height() * 0.14), 1.5, color);
    p.circle_filled(Pos2::new(r - 9.0, m + c.height() * 0.14), 1.5, color);
}

pub fn monitor(p: &Painter, c: Rect, color: Color32) {
    let s = Stroke::new(STROKE, color);
    let (l, r) = (c.left() + c.width() * 0.14, c.right() - c.width() * 0.14);
    let (t, b) = (
        c.top() + c.height() * 0.16,
        c.center().y - c.height() * 0.06,
    );
    p.line_segment([Pos2::new(l, t), Pos2::new(r, t)], s);
    p.line_segment([Pos2::new(l, b), Pos2::new(r, b)], s);
    p.line_segment([Pos2::new(l, t), Pos2::new(l, b)], s);
    p.line_segment([Pos2::new(r, t), Pos2::new(r, b)], s);
    p.line_segment(
        [
            Pos2::new(c.center().x, b),
            Pos2::new(c.center().x, c.bottom() - c.height() * 0.16),
        ],
        s,
    );
    p.line_segment(
        [
            Pos2::new(
                c.center().x - c.width() * 0.14,
                c.bottom() - c.height() * 0.16,
            ),
            Pos2::new(
                c.center().x + c.width() * 0.14,
                c.bottom() - c.height() * 0.16,
            ),
        ],
        s,
    );
}

pub fn keyboard(p: &Painter, c: Rect, color: Color32) {
    let s = Stroke::new(STROKE, color);
    let (l, r) = (c.left() + c.width() * 0.1, c.right() - c.width() * 0.1);
    let (t, b) = (c.top() + c.height() * 0.24, c.bottom() - c.height() * 0.24);
    p.line_segment([Pos2::new(l, t), Pos2::new(r, t)], s);
    p.line_segment([Pos2::new(l, b), Pos2::new(r, b)], s);
    p.line_segment([Pos2::new(l, t), Pos2::new(l, b)], s);
    p.line_segment([Pos2::new(r, t), Pos2::new(r, b)], s);
    for i in 0..5 {
        let x = l + 5.0 + i as f32 * ((r - l - 10.0) / 4.0);
        p.circle_filled(Pos2::new(x, t + (b - t) * 0.3), 1.3, color);
    }
    p.line_segment(
        [
            Pos2::new(l + 8.0, t + (b - t) * 0.68),
            Pos2::new(r - 8.0, t + (b - t) * 0.68),
        ],
        s,
    );
}

pub fn user(p: &Painter, c: Rect, color: Color32) {
    let s = Stroke::new(STROKE, color);
    let center = c.center();
    p.circle_stroke(
        Pos2::new(center.x, center.y - c.height() * 0.16),
        c.width() * 0.15,
        s,
    );
    let cy = c.bottom() - c.height() * 0.22;
    let rx = c.width() * 0.3;
    p.add(egui::Shape::line(
        vec![
            Pos2::new(center.x - rx, cy),
            Pos2::new(center.x - rx * 0.55, cy - c.height() * 0.12),
            Pos2::new(center.x + rx * 0.55, cy - c.height() * 0.12),
            Pos2::new(center.x + rx, cy),
        ],
        s,
    ));
}

pub fn brain(p: &Painter, c: Rect, color: Color32) {
    let s = Stroke::new(STROKE, color);
    let center = c.center();
    let r = c.size().min_elem() * 0.32;
    p.circle_stroke(
        Pos2::new(center.x - r * 0.5, center.y - r * 0.3),
        r * 0.62,
        s,
    );
    p.circle_stroke(
        Pos2::new(center.x + r * 0.5, center.y - r * 0.3),
        r * 0.62,
        s,
    );
    p.circle_stroke(
        Pos2::new(center.x - r * 0.45, center.y + r * 0.45),
        r * 0.5,
        s,
    );
    p.circle_stroke(
        Pos2::new(center.x + r * 0.45, center.y + r * 0.45),
        r * 0.5,
        s,
    );
}

pub fn bot(p: &Painter, c: Rect, color: Color32) {
    let s = Stroke::new(STROKE, color);
    let (l, r) = (c.left() + c.width() * 0.16, c.right() - c.width() * 0.16);
    let (t, b) = (c.top() + c.height() * 0.3, c.bottom() - c.height() * 0.24);
    p.line_segment([Pos2::new(l, t), Pos2::new(r, t)], s);
    p.line_segment([Pos2::new(l, b), Pos2::new(r, b)], s);
    p.line_segment([Pos2::new(l, t), Pos2::new(l, b)], s);
    p.line_segment([Pos2::new(r, t), Pos2::new(r, b)], s);
    p.line_segment(
        [
            Pos2::new(c.center().x, t),
            Pos2::new(c.center().x, t - c.height() * 0.08),
        ],
        s,
    );
    p.circle_filled(Pos2::new(c.center().x, t - c.height() * 0.10), 1.6, color);
    p.circle_filled(Pos2::new(l + (r - l) * 0.3, t + (b - t) * 0.5), 1.8, color);
    p.circle_filled(Pos2::new(l + (r - l) * 0.7, t + (b - t) * 0.5), 1.8, color);
    p.line_segment(
        [
            Pos2::new(l + (r - l) * 0.38, b),
            Pos2::new(l + (r - l) * 0.38, b + c.height() * 0.06),
        ],
        s,
    );
    p.line_segment(
        [
            Pos2::new(l + (r - l) * 0.62, b),
            Pos2::new(l + (r - l) * 0.62, b + c.height() * 0.06),
        ],
        s,
    );
}

pub fn blocks(p: &Painter, c: Rect, color: Color32) {
    let s = Stroke::new(STROKE, color);
    let cx = c.center().x;
    let cy = c.center().y;
    let w = c.width() * 0.26;
    let h = c.height() * 0.2;
    // two top blocks + one bottom
    for (dx, dy) in [(-w * 0.55, -h * 0.9), (w * 0.55, -h * 0.9), (0.0, h * 0.9)] {
        let (bl, br, bt, bb) = (
            cx + dx - w * 0.5,
            cx + dx + w * 0.5,
            cy + dy - h * 0.5,
            cy + dy + h * 0.5,
        );
        p.add(egui::Shape::line(
            vec![
                Pos2::new(bl, bt),
                Pos2::new(br, bt),
                Pos2::new(br, bb),
                Pos2::new(bl, bb),
                Pos2::new(bl, bt),
            ],
            s,
        ));
    }
}

pub fn cable(p: &Painter, c: Rect, color: Color32) {
    let s = Stroke::new(STROKE, color);
    let cy = c.center().y;
    let (l, r) = (c.left() + c.width() * 0.12, c.right() - c.width() * 0.12);
    p.line_segment([Pos2::new(l, cy), Pos2::new(l + c.width() * 0.2, cy)], s);
    p.line_segment([Pos2::new(r - c.width() * 0.2, cy), Pos2::new(r, cy)], s);
    p.circle_stroke(Pos2::new(l + c.width() * 0.28, cy), c.height() * 0.16, s);
    p.circle_stroke(Pos2::new(r - c.width() * 0.28, cy), c.height() * 0.16, s);
    p.line_segment(
        [
            Pos2::new(l + c.width() * 0.44, cy),
            Pos2::new(r - c.width() * 0.44, cy),
        ],
        s,
    );
}

pub fn wand(p: &Painter, c: Rect, color: Color32) {
    let s = Stroke::new(STROKE, color);
    let (t, b) = (c.top() + c.height() * 0.18, c.bottom() - c.height() * 0.18);
    let dx = (b - t) * 0.55;
    p.line_segment(
        [
            Pos2::new(c.left() + c.width() * 0.16, b),
            Pos2::new(c.left() + c.width() * 0.16 + dx, b - dx),
        ],
        s,
    );
    // sparkle at tip
    let tip = Pos2::new(c.left() + c.width() * 0.16 + dx, b - dx);
    for (ax, ay) in [(0.0f32, -1.0), (0.0, 1.0), (-1.0, 0.0), (1.0, 0.0)] {
        p.line_segment([tip, tip + Vec2::new(ax * 5.0, ay * 5.0)], s);
    }
    p.line_segment(
        [
            Pos2::new(c.right() - c.width() * 0.24, c.top() + c.height() * 0.3),
            Pos2::new(c.right() - c.width() * 0.24, c.top() + c.height() * 0.42),
        ],
        s,
    );
    p.line_segment(
        [
            Pos2::new(c.right() - c.width() * 0.30, c.top() + c.height() * 0.36),
            Pos2::new(c.right() - c.width() * 0.18, c.top() + c.height() * 0.36),
        ],
        s,
    );
}

pub fn terminal_square(p: &Painter, c: Rect, color: Color32) {
    let s = Stroke::new(STROKE, color);
    let (l, r) = (c.left() + c.width() * 0.12, c.right() - c.width() * 0.12);
    let (t, b) = (c.top() + c.height() * 0.14, c.bottom() - c.height() * 0.14);
    p.line_segment([Pos2::new(l, t), Pos2::new(r, t)], s);
    p.line_segment([Pos2::new(l, b), Pos2::new(r, b)], s);
    p.line_segment([Pos2::new(l, t), Pos2::new(l, b)], s);
    p.line_segment([Pos2::new(r, t), Pos2::new(r, b)], s);
    p.add(egui::Shape::line(
        vec![
            Pos2::new(l + 5.0, t + (b - t) * 0.32),
            Pos2::new(l + 9.5, t + (b - t) * 0.5),
            Pos2::new(l + 5.0, t + (b - t) * 0.68),
        ],
        s,
    ));
    p.line_segment(
        [
            Pos2::new(l + 12.0, t + (b - t) * 0.72),
            Pos2::new(l + 20.0, t + (b - t) * 0.72),
        ],
        s,
    );
}

pub fn anchor(p: &Painter, c: Rect, color: Color32) {
    let s = Stroke::new(STROKE, color);
    let cx = c.center().x;
    p.circle_stroke(
        Pos2::new(cx, c.top() + c.height() * 0.24),
        c.height() * 0.09,
        s,
    );
    p.line_segment(
        [
            Pos2::new(cx, c.top() + c.height() * 0.33),
            Pos2::new(cx, c.bottom() - c.height() * 0.2),
        ],
        s,
    );
    let base = c.bottom() - c.height() * 0.2;
    p.add(egui::Shape::line(
        vec![
            Pos2::new(cx - c.width() * 0.24, c.top() + c.height() * 0.42),
            Pos2::new(cx + c.width() * 0.24, c.top() + c.height() * 0.42),
        ],
        s,
    ));
    p.add(egui::Shape::line(
        bezier_points(
            Pos2::new(cx, base),
            Pos2::new(cx - c.width() * 0.3, base),
            Pos2::new(cx - c.width() * 0.3, base - c.height() * 0.28),
            Pos2::new(cx, base - c.height() * 0.28),
            14,
        ),
        s,
    ));
    p.add(egui::Shape::line(
        bezier_points(
            Pos2::new(cx, base),
            Pos2::new(cx + c.width() * 0.3, base),
            Pos2::new(cx + c.width() * 0.3, base - c.height() * 0.28),
            Pos2::new(cx, base - c.height() * 0.28),
            14,
        ),
        s,
    ));
}

pub fn refresh(p: &Painter, c: Rect, color: Color32) {
    let s = Stroke::new(STROKE, color);
    let center = c.center();
    let r = c.size().min_elem() * 0.34;
    p.add(egui::Shape::line(arc_points(center, r, -1.1, 3.9, 24), s));
    // arrowhead at arc end
    let ang = 3.9f32;
    let tip = center + Vec2::new(ang.cos() * r, ang.sin() * r);
    p.add(egui::Shape::line(
        vec![tip, tip + Vec2::new(-2.6, -4.4), tip + Vec2::new(4.6, -3.2)],
        s,
    ));
}

pub fn bar_chart(p: &Painter, c: Rect, color: Color32) {
    let s = Stroke::new(STROKE, color);
    let b = c.bottom() - c.height() * 0.24;
    let w = c.width();
    for (i, h) in [0.18f32, 0.34, 0.5].iter().enumerate() {
        let x = c.left() + w * 0.24 + i as f32 * w * 0.2;
        p.line_segment([Pos2::new(x, b), Pos2::new(x, b - c.height() * h)], s);
    }
}

pub fn folders(p: &Painter, c: Rect, color: Color32) {
    // back folder + front folder
    let _s = Stroke::new(STROKE, color);
    let mut back = c.shrink2(Vec2::new(c.width() * 0.1, c.height() * 0.18));
    back.set_top(c.top() + c.height() * 0.14);
    folder(p, back.translate(Vec2::new(2.0, 2.0)), color);
    let front = Rect::from_min_size(
        Pos2::new(c.left() + c.width() * 0.06, c.top() + c.height() * 0.34),
        Vec2::new(c.width() * 0.72, c.height() * 0.52),
    );
    folder(p, front, color);
}

pub fn archive(p: &Painter, c: Rect, color: Color32) {
    let s = Stroke::new(STROKE, color);
    let (l, r) = (c.left() + c.width() * 0.14, c.right() - c.width() * 0.14);
    let (t, b) = (c.top() + c.height() * 0.2, c.bottom() - c.height() * 0.2);
    p.line_segment([Pos2::new(l, t), Pos2::new(r, t)], s);
    p.line_segment(
        [
            Pos2::new(l, t + c.height() * 0.14),
            Pos2::new(r, t + c.height() * 0.14),
        ],
        s,
    );
    p.line_segment([Pos2::new(l, t), Pos2::new(l, t + c.height() * 0.14)], s);
    p.line_segment([Pos2::new(r, t), Pos2::new(r, t + c.height() * 0.14)], s);
    // box below
    let bl = l + c.width() * 0.08;
    let br = r - c.width() * 0.08;
    let bt = t + c.height() * 0.24;
    p.line_segment([Pos2::new(bl, bt), Pos2::new(bl, b)], s);
    p.line_segment([Pos2::new(br, bt), Pos2::new(br, b)], s);
    p.line_segment([Pos2::new(bl, b), Pos2::new(br, b)], s);
    p.line_segment([Pos2::new(bl, bt), Pos2::new(bl, bt)], s);
    p.line_segment(
        [
            Pos2::new(cx_mid(bl, br) - 4.0, (bt + b) * 0.5),
            Pos2::new(cx_mid(bl, br) + 4.0, (bt + b) * 0.5),
        ],
        s,
    );
}

fn cx_mid(a: f32, b: f32) -> f32 {
    (a + b) * 0.5
}

/// Sampled circular arc as a polyline (egui 0.29 has no Shape::arc constructor).
fn arc_points(center: Pos2, r: f32, start: f32, end: f32, n: usize) -> Vec<Pos2> {
    (0..=n)
        .map(|i| {
            let a = start + (end - start) * i as f32 / n as f32;
            Pos2::new(center.x + a.cos() * r, center.y + a.sin() * r)
        })
        .collect()
}

/// Sampled cubic bezier as a polyline.
fn bezier_points(p0: Pos2, c1: Pos2, c2: Pos2, p1: Pos2, n: usize) -> Vec<Pos2> {
    (0..=n)
        .map(|i| {
            let t = i as f32 / n as f32;
            let u = 1.0 - t;
            Pos2::new(
                u * u * u * p0.x
                    + 3.0 * u * u * t * c1.x
                    + 3.0 * u * t * t * c2.x
                    + t * t * t * p1.x,
                u * u * u * p0.y
                    + 3.0 * u * u * t * c1.y
                    + 3.0 * u * t * t * c2.y
                    + t * t * t * p1.y,
            )
        })
        .collect()
}

pub fn arrow_left(p: &Painter, c: Rect, color: Color32) {
    let center = c.center();
    let s = c.size().min_elem() * 0.38;
    let stroke = Stroke::new(STROKE, color);
    p.line_segment([center + Vec2::X * s, center - Vec2::X * s], stroke);
    p.add(egui::Shape::line(
        vec![
            Pos2::new(center.x - s * 0.1, center.y - s * 0.75),
            Pos2::new(center.x - s, center.y),
            Pos2::new(center.x - s * 0.1, center.y + s * 0.75),
        ],
        stroke,
    ));
}

pub fn sun(p: &Painter, c: Rect, color: Color32) {
    let s = Stroke::new(STROKE, color);
    let center = c.center();
    let r = c.size().min_elem() * 0.22;
    p.circle_stroke(center, r, s);
    for i in 0..8 {
        let a = i as f32 * std::f32::consts::TAU / 8.0;
        let d = Vec2::new(a.cos(), a.sin());
        p.line_segment([center + d * (r + 2.2), center + d * (r + 5.4)], s);
    }
}

pub fn moon(p: &Painter, c: Rect, color: Color32) {
    let s = Stroke::new(STROKE, color);
    let center = c.center();
    let r = c.size().min_elem() * 0.34;
    p.add(egui::Shape::line(arc_points(center, r, 0.9, 5.4, 24), s));
    p.add(egui::Shape::line(
        arc_points(
            center + Vec2::new(r * 0.52, -r * 0.5),
            r * 0.62,
            3.4,
            5.5,
            18,
        ),
        s,
    ));
}

pub fn check(p: &Painter, c: Rect, color: Color32) {
    let center = c.center();
    let s = c.size().min_elem() * 0.3;
    p.add(egui::Shape::line(
        vec![
            Pos2::new(center.x - s, center.y + s * 0.1),
            Pos2::new(center.x - s * 0.2, center.y + s * 0.85),
            Pos2::new(center.x + s, center.y - s * 0.7),
        ],
        Stroke::new(STROKE + 0.2, color),
    ));
}

pub fn x(p: &Painter, c: Rect, color: Color32) {
    let center = c.center();
    let s = c.size().min_elem() * 0.32;
    p.line_segment(
        [center - Vec2::new(s, s), center + Vec2::new(s, s)],
        Stroke::new(STROKE, color),
    );
    p.line_segment(
        [center + Vec2::new(s, -s), center + Vec2::new(-s, s)],
        Stroke::new(STROKE, color),
    );
}

pub fn trash(p: &Painter, c: Rect, color: Color32) {
    let s = Stroke::new(STROKE, color);
    let (l, r) = (c.left() + c.width() * 0.26, c.right() - c.width() * 0.26);
    let t = c.top() + c.height() * 0.3;
    let b = c.bottom() - c.height() * 0.18;
    let cx = c.center().x;
    p.line_segment(
        [
            Pos2::new(cx - c.width() * 0.16, c.top() + c.height() * 0.2),
            Pos2::new(cx + c.width() * 0.16, c.top() + c.height() * 0.2),
        ],
        s,
    );
    p.line_segment(
        [
            Pos2::new(cx, c.top() + c.height() * 0.12),
            Pos2::new(cx, c.top() + c.height() * 0.2),
        ],
        s,
    );
    p.line_segment([Pos2::new(l, t), Pos2::new(r, t)], s);
    p.line_segment([Pos2::new(l + 2.0, t), Pos2::new(l + 3.4, b)], s);
    p.line_segment([Pos2::new(r - 2.0, t), Pos2::new(r - 3.4, b)], s);
    p.line_segment([Pos2::new(l + 3.4, b), Pos2::new(r - 3.4, b)], s);
    p.line_segment(
        [Pos2::new(cx - 3.0, t + 4.0), Pos2::new(cx - 3.0, b - 3.0)],
        s,
    );
    p.line_segment(
        [Pos2::new(cx + 3.0, t + 4.0), Pos2::new(cx + 3.0, b - 3.0)],
        s,
    );
}

/// Icon-button: square hover target with centered icon.
pub fn icon_button(
    ui: &mut egui::Ui,
    size: f32,
    paint: impl FnOnce(&Painter, Rect, Color32),
    icon_color: Color32,
) -> Response {
    let (rect, resp) = ui.allocate_exact_size(Vec2::splat(size), Sense::click());
    if ui.is_rect_visible(rect) {
        let p = ui.painter();
        if resp.hovered() {
            p.rect_filled(rect.shrink(2.0), 6.0, crate::theme::SURFACE_HOVER);
        }
        paint(p, rect.shrink(size * 0.22), icon_color);
    }
    resp
}

// ---------- chat view icons (lucide-accurate strokes) ----------

/// lucide wrench
pub fn wrench(p: &Painter, c: Rect, color: Color32) {
    let s = Stroke::new(STROKE, color);
    let w = c.width();
    let h = c.height();
    // head: arc around (0.32w, 0.32h)
    let hc = Pos2::new(c.left() + w * 0.32, c.top() + h * 0.32);
    let hr = w * 0.17;
    p.circle_stroke(hc, hr, s);
    // handle: diagonal to bottom-right
    let a = Pos2::new(hc.x + hr * 0.5, hc.y + hr * 0.5);
    let b = Pos2::new(c.left() + w * 0.82, c.top() + h * 0.82);
    p.line_segment([a, b], Stroke::new(STROKE + 0.7, color));
    // jaw notch on the head
    p.line_segment(
        [
            Pos2::new(hc.x + hr * 0.9, hc.y - hr * 0.35),
            Pos2::new(hc.x + hr * 0.35, hc.y + hr * 0.9),
        ],
        Stroke::new(STROKE + 0.7, color),
    );
}

/// lucide circle-check
pub fn check_circle(p: &Painter, c: Rect, color: Color32) {
    p.circle_stroke(c.center(), c.size().min_elem() * 0.42, Stroke::new(STROKE, color));
    check(p, c.shrink(c.size().min_elem() * 0.16), color);
}

/// lucide circle-x
pub fn x_circle(p: &Painter, c: Rect, color: Color32) {
    p.circle_stroke(c.center(), c.size().min_elem() * 0.42, Stroke::new(STROKE, color));
    x(p, c.shrink(c.size().min_elem() * 0.3), color);
}

/// lucide circle (outline)
pub fn circle(p: &Painter, c: Rect, color: Color32) {
    p.circle_stroke(c.center(), c.size().min_elem() * 0.4, Stroke::new(STROKE, color));
}

/// lucide git-branch
pub fn git_branch(p: &Painter, c: Rect, color: Color32) {
    let s = Stroke::new(STROKE, color);
    let w = c.width();
    let h = c.height();
    let r = w * 0.09;
    // top-left dot, bottom dot, right dot
    let tl = Pos2::new(c.left() + w * 0.22, c.top() + h * 0.18);
    let bl = Pos2::new(c.left() + w * 0.22, c.top() + h * 0.84);
    let rt = Pos2::new(c.left() + w * 0.8, c.top() + h * 0.18);
    p.circle_filled(tl, r, color);
    p.circle_filled(bl, r, color);
    p.circle_filled(rt, r, color);
    // vertical line from tl down
    p.line_segment(
        [Pos2::new(tl.x, tl.y + r + 1.0), Pos2::new(bl.x, bl.y - r - 1.0)],
        s,
    );
    // curve from tl to rt (two segments approximating the arc)
    p.line_segment(
        [
            Pos2::new(tl.x + r, tl.y),
            Pos2::new(c.left() + w * 0.5, tl.y),
        ],
        s,
    );
    p.line_segment(
        [
            Pos2::new(c.left() + w * 0.5, tl.y),
            Pos2::new(
                c.left() + w * 0.5,
                c.top() + h * 0.52,
            ),
        ],
        s,
    );
    p.line_segment(
        [
            Pos2::new(c.left() + w * 0.5, c.top() + h * 0.52),
            Pos2::new(c.left() + w * 0.72, c.top() + h * 0.62),
        ],
        s,
    );
}

/// lucide file-text
pub fn file_text(p: &Painter, c: Rect, color: Color32) {
    let s = Stroke::new(STROKE, color);
    let x0 = c.left() + c.width() * 0.24;
    let x1 = c.right() - c.width() * 0.24;
    let y0 = c.top() + c.height() * 0.14;
    let yb = c.bottom() - c.height() * 0.14;
    // page with folded corner
    p.add(egui::Shape::line(
        vec![
            Pos2::new(x0 + c.width() * 0.18, y0),
            Pos2::new(x1 - c.width() * 0.18, y0),
            Pos2::new(x1, y0 + c.height() * 0.18),
            Pos2::new(x1, yb),
            Pos2::new(x0, yb),
            Pos2::new(x0, y0 + c.width() * 0.18),
            Pos2::new(x0 + c.width() * 0.18, y0),
        ],
        s,
    ));
    // text lines
    for (i, frac) in [0.45, 0.6, 0.75].into_iter().enumerate() {
        let y = c.top() + c.height() * frac;
        let end = if i == 2 { x1 - c.width() * 0.14 } else { x1 - c.width() * 0.08 };
        p.line_segment([Pos2::new(x0 + c.width() * 0.1, y), Pos2::new(end, y)], s);
    }
}

