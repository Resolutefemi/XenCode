//! Minimal geometric icons drawn with egui painters.
//! Fidelity note: ZCode uses Lucide icons; these are matching geometric
//! approximations (same stroke weight 1.6, same optical size) until we
//! embed Lucide vector paths.

use egui::{Color32, Painter, Pos2, Rect, Response, Sense, Stroke, Vec2};

const STROKE: f32 = 1.6;

pub fn plus(p: &Painter, c: Rect, color: Color32) {
    let center = c.center();
    let r = c.size().min_elem() * 0.32;
    p.line_segment([center - Vec2::X * r, center + Vec2::X * r], Stroke::new(STROKE, color));
    p.line_segment([center - Vec2::Y * r, center + Vec2::Y * r], Stroke::new(STROKE, color));
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
        p.line_segment([center + dir * (r * 0.68), center + dir * r], Stroke::new(STROKE, color));
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
    p.line_segment([center - Vec2::Y * s, center + Vec2::Y * s], Stroke::new(STROKE + 0.4, color));
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
    p.add(egui::Shape::convex_polygon(pts.to_vec(), color, Stroke::NONE));
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
        [center + Vec2::new(-half, -half) * 0.92, center + Vec2::new(half, half) * 0.92],
        stroke,
    );
    p.line_segment(
        [center + Vec2::new(half, -half) * 0.92, center + Vec2::new(-half, half) * 0.92],
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

/// Icon-button: square hover target with centered icon.
pub fn icon_button(ui: &mut egui::Ui, size: f32, paint: impl FnOnce(&Painter, Rect, Color32), icon_color: Color32) -> Response {
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
