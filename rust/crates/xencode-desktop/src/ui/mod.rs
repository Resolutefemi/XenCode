//! XenCode Desktop UI: the ZCode (@zcode/ui) workspace shell ported to
//! egui. Module tree mirrors the ZCode package layout:
//!
//!   ZCode source (Apache-2.0, zai-org/ZCode)      → XenCode Rust
//!   packages/ui/src/app-shell/WorkspaceShellLayout → ui::app_shell
//!   packages/ui/src/WorkspaceSidebar + project-…   → ui::sidebar
//!   packages/ui/src/v4 (conversation timeline)     → ui::conversation
//!   packages/ui/src/LexicalChatInput + chat-input-…→ ui::composer
//!   settings / git / terminal panes                → ui::panels
//!
//! Colors come from theme::tokens (generated). This module holds the
//! shared primitives and icon painters (Lucide-style 16px strokes).

pub mod app_shell;
pub mod composer;
pub mod conversation;
pub mod markdown;
pub mod panels;
pub mod sidebar;

use crate::theme::{self, Palette};
use eframe::egui;

/// ZCode conversation column width (max-w ~ 46rem content area).
pub const COLUMN: f32 = 720.0;

#[allow(dead_code)]
pub(crate) fn ellipsize(s: &str, max_chars: usize) -> String {
    if s.chars().count() <= max_chars {
        s.to_string()
    } else {
        let cut: String = s.chars().take(max_chars.saturating_sub(1)).collect();
        format!("{cut}…")
    }
}

/// 1px hairline stroke in the token border color.
#[allow(dead_code)]
pub(crate) fn hairline(p: Palette) -> egui::Stroke {
    egui::Stroke::new(1.0_f32, p.border())
}

/// 1px hairline stroke in an explicit color (composer input-border etc).
#[allow(dead_code)]
pub(crate) fn hairline_color(c: egui::Color32) -> egui::Stroke {
    egui::Stroke::new(1.0_f32, c)
}

/// Card surface: bg-card, 1px border, rounded-lg (ZCode `card` class).
#[allow(dead_code)]
pub(crate) fn card(p: Palette, ui: &mut egui::Ui, add: impl FnOnce(&mut egui::Ui)) {
    egui::Frame::default()
        .fill(p.card())
        .rounding(egui::Rounding::same(theme::geom::RADIUS))
        .stroke(hairline(p))
        .inner_margin(egui::Margin::symmetric(14.0, 12.0))
        .show(ui, |ui| {
            ui.set_min_width(ui.available_width());
            add(ui);
        });
}

/// Code surface inside a card (mono block on the surface token).
#[allow(dead_code)]
pub(crate) fn code_block(p: Palette, ui: &mut egui::Ui, add: impl FnOnce(&mut egui::Ui)) {
    egui::Frame::default()
        .fill(p.surface())
        .rounding(egui::Rounding::same(6.0))
        .inner_margin(egui::Margin::symmetric(10.0, 8.0))
        .show(ui, |ui| {
            ui.set_min_width(ui.available_width());
            add(ui);
        });
}

/// Primary button: bg-primary / fg-primary-foreground, rounded-lg.
#[allow(dead_code)]
pub(crate) fn primary(p: Palette, ui: &mut egui::Ui, label: &str) -> egui::Response {
    let btn = egui::Button::new(
        egui::RichText::new(label).size(theme::text::BASE).color(p.primary_foreground()),
    )
    .fill(p.primary())
    .rounding(egui::Rounding::same(theme::geom::RADIUS));
    ui.add(btn)
}

/// Ghost button: transparent fill, border hairline, foreground text.
#[allow(dead_code)]
pub(crate) fn ghost(p: Palette, ui: &mut egui::Ui, label: &str) -> egui::Response {
    let btn = egui::Button::new(
        egui::RichText::new(label).size(theme::text::SM).color(p.foreground()),
    )
    .fill(egui::Color32::TRANSPARENT)
    .stroke(hairline(p))
    .rounding(egui::Rounding::same(theme::geom::RADIUS));
    ui.add(btn)
}

/// Small status pill (tag surface + tinted foreground).
#[allow(dead_code)]
pub(crate) fn pill(_p: Palette, ui: &mut egui::Ui, label: &str, bg: egui::Color32, fg: egui::Color32) {
    egui::Frame::default()
        .fill(bg)
        .rounding(egui::Rounding::same(999.0))
        .inner_margin(egui::Margin::symmetric(8.0, 2.0))
        .show(ui, |ui| {
            ui.label(egui::RichText::new(label).size(theme::text::XS).color(fg));
        });
}

// ── Icon painters (Lucide geometry, 16px grid) ─────────────────────────────

#[allow(dead_code)]
pub(crate) fn chevron_down(painter: &egui::Painter, c: egui::Pos2, color: egui::Color32, s: f32) {
    let h = s / 2.0;
    painter.line_segment(
        [egui::pos2(c.x - h * 0.6, c.y - h * 0.25), egui::pos2(c.x, c.y + h * 0.3)],
        egui::Stroke::new(1.6_f32, color),
    );
    painter.line_segment(
        [egui::pos2(c.x, c.y + h * 0.3), egui::pos2(c.x + h * 0.6, c.y - h * 0.25)],
        egui::Stroke::new(1.6_f32, color),
    );
}

#[allow(dead_code)]
pub(crate) fn chevron_right(painter: &egui::Painter, c: egui::Pos2, color: egui::Color32, s: f32) {
    let h = s / 2.0;
    painter.line_segment(
        [egui::pos2(c.x - h * 0.25, c.y - h * 0.6), egui::pos2(c.x + h * 0.3, c.y)],
        egui::Stroke::new(1.6_f32, color),
    );
    painter.line_segment(
        [egui::pos2(c.x + h * 0.3, c.y), egui::pos2(c.x - h * 0.25, c.y + h * 0.6)],
        egui::Stroke::new(1.6_f32, color),
    );
}

#[allow(dead_code)]
pub(crate) fn plus(painter: &egui::Painter, c: egui::Pos2, color: egui::Color32, s: f32) {
    let h = s / 2.0;
    painter.line_segment(
        [egui::pos2(c.x - h, c.y), egui::pos2(c.x + h, c.y)],
        egui::Stroke::new(1.8_f32, color),
    );
    painter.line_segment(
        [egui::pos2(c.x, c.y - h), egui::pos2(c.x, c.y + h)],
        egui::Stroke::new(1.8_f32, color),
    );
}

#[allow(dead_code)]
pub(crate) fn close_x(painter: &egui::Painter, c: egui::Pos2, color: egui::Color32, s: f32) {
    let h = s / 2.0;
    painter.line_segment(
        [egui::pos2(c.x - h, c.y - h), egui::pos2(c.x + h, c.y + h)],
        egui::Stroke::new(1.6_f32, color),
    );
    painter.line_segment(
        [egui::pos2(c.x - h, c.y + h), egui::pos2(c.x + h, c.y - h)],
        egui::Stroke::new(1.6_f32, color),
    );
}

/// Arrow-up (ZCode send glyph).
#[allow(dead_code)]
pub(crate) fn arrow_up(painter: &egui::Painter, c: egui::Pos2, color: egui::Color32, s: f32) {
    let h = s / 2.0;
    painter.line_segment(
        [egui::pos2(c.x, c.y + h), egui::pos2(c.x, c.y - h + 1.0)],
        egui::Stroke::new(2.0_f32, color),
    );
    painter.line_segment(
        [egui::pos2(c.x - h * 0.55, c.y - h * 0.25), egui::pos2(c.x, c.y - h)],
        egui::Stroke::new(2.0_f32, color),
    );
    painter.line_segment(
        [egui::pos2(c.x, c.y - h), egui::pos2(c.x + h * 0.55, c.y - h * 0.25)],
        egui::Stroke::new(2.0_f32, color),
    );
}

#[allow(dead_code)]
pub(crate) fn arrow_left(painter: &egui::Painter, c: egui::Pos2, color: egui::Color32, s: f32) {
    let h = s / 2.0;
    painter.line_segment(
        [egui::pos2(c.x - h * 0.4, c.y), egui::pos2(c.x + h * 0.55, c.y)],
        egui::Stroke::new(1.8_f32, color),
    );
    painter.line_segment(
        [egui::pos2(c.x - h * 0.4, c.y), egui::pos2(c.x - h * 0.05, c.y - h * 0.5)],
        egui::Stroke::new(1.8_f32, color),
    );
    painter.line_segment(
        [egui::pos2(c.x - h * 0.4, c.y), egui::pos2(c.x - h * 0.05, c.y + h * 0.5)],
        egui::Stroke::new(1.8_f32, color),
    );
}

#[allow(dead_code)]
pub(crate) fn arrow_right(painter: &egui::Painter, c: egui::Pos2, color: egui::Color32, s: f32) {
    let h = s / 2.0;
    painter.line_segment(
        [egui::pos2(c.x - h * 0.55, c.y), egui::pos2(c.x + h * 0.4, c.y)],
        egui::Stroke::new(1.8_f32, color),
    );
    painter.line_segment(
        [egui::pos2(c.x + h * 0.4, c.y), egui::pos2(c.x + h * 0.05, c.y - h * 0.5)],
        egui::Stroke::new(1.8_f32, color),
    );
    painter.line_segment(
        [egui::pos2(c.x + h * 0.4, c.y), egui::pos2(c.x + h * 0.05, c.y + h * 0.5)],
        egui::Stroke::new(1.8_f32, color),
    );
}

/// Panel-left toggle (ZCode sidebar toggle glyph).
#[allow(dead_code)]
pub(crate) fn sidebar_toggle(painter: &egui::Painter, rect: egui::Rect, color: egui::Color32) {
    painter.rect_stroke(rect, 3.0_f32, egui::Stroke::new(1.6_f32, color));
    painter.line_segment(
        [egui::pos2(rect.left() + rect.width() * 0.33, rect.top()), egui::pos2(rect.left() + rect.width() * 0.33, rect.bottom())],
        egui::Stroke::new(1.6_f32, color),
    );
}

/// Gear (settings).
#[allow(dead_code)]
pub(crate) fn gear(painter: &egui::Painter, c: egui::Pos2, color: egui::Color32, s: f32) {
    let r = s / 2.0;
    painter.circle_stroke(c, r * 0.55_f32, egui::Stroke::new(1.6_f32, color));
    for i in 0..8 {
        let a = std::f32::consts::TAU * (i as f32) / 8.0;
        let p1 = egui::pos2(c.x + a.cos() * r * 0.78, c.y + a.sin() * r * 0.78);
        let p2 = egui::pos2(c.x + a.cos() * r, c.y + a.sin() * r);
        painter.line_segment([p1, p2], egui::Stroke::new(1.6_f32, color));
    }
}

/// Folder (workspace).
#[allow(dead_code)]
pub(crate) fn folder(painter: &egui::Painter, rect: egui::Rect, color: egui::Color32) {
    let r = rect.shrink(2.0);
    painter.line_segment(
        [r.left_top(), r.right_top()],
        egui::Stroke::new(1.6_f32, color),
    );
    painter.line_segment(
        [r.left_top(), egui::pos2(r.left(), r.bottom())],
        egui::Stroke::new(1.6_f32, color),
    );
    painter.line_segment(
        [egui::pos2(r.left(), r.bottom()), egui::pos2(r.right(), r.bottom())],
        egui::Stroke::new(1.6_f32, color),
    );
    painter.line_segment(
        [egui::pos2(r.right(), r.bottom()), egui::pos2(r.right(), r.top() + 4.0)],
        egui::Stroke::new(1.6_f32, color),
    );
    painter.line_segment(
        [egui::pos2(r.right(), r.top() + 4.0), egui::pos2(r.left() + r.width() * 0.35, r.top() + 4.0)],
        egui::Stroke::new(1.6_f32, color),
    );
}

/// Git branch glyph.
#[allow(dead_code)]
pub(crate) fn git_branch(painter: &egui::Painter, c: egui::Pos2, color: egui::Color32, s: f32) {
    let r = s / 2.0;
    painter.circle_stroke(egui::pos2(c.x - r * 0.45_f32, c.y - r * 0.6_f32), 2.2_f32, egui::Stroke::new(1.5_f32, color));
    painter.circle_stroke(egui::pos2(c.x - r * 0.45_f32, c.y + r * 0.6_f32), 2.2_f32, egui::Stroke::new(1.5_f32, color));
    painter.circle_stroke(egui::pos2(c.x + r * 0.5_f32, c.y - r * 0.6_f32), 2.2_f32, egui::Stroke::new(1.5_f32, color));
    painter.line_segment(
        [egui::pos2(c.x - r * 0.45, c.y - r * 0.35), egui::pos2(c.x - r * 0.45, c.y + r * 0.35)],
        egui::Stroke::new(1.5_f32, color),
    );
    painter.line_segment(
        [egui::pos2(c.x - r * 0.45, c.y - r * 0.6), egui::pos2(c.x + r * 0.5, c.y - r * 0.6)],
        egui::Stroke::new(1.5_f32, color),
    );
}

/// Message-circle-plus (ZCode New Task glyph).
#[allow(dead_code)]
pub(crate) fn new_task_glyph(painter: &egui::Painter, c: egui::Pos2, color: egui::Color32, s: f32) {
    let r = s / 2.0;
    // chat bubble
    painter.line_segment(
        [egui::pos2(c.x - r * 0.8, c.y - r * 0.5), egui::pos2(c.x + r * 0.8, c.y - r * 0.5)],
        egui::Stroke::new(1.6_f32, color),
    );
    painter.line_segment(
        [egui::pos2(c.x - r * 0.8, c.y - r * 0.5), egui::pos2(c.x - r * 0.8, c.y + r * 0.3)],
        egui::Stroke::new(1.6_f32, color),
    );
    painter.line_segment(
        [egui::pos2(c.x - r * 0.8, c.y + r * 0.3), egui::pos2(c.x - r * 0.4, c.y + r * 0.3)],
        egui::Stroke::new(1.6_f32, color),
    );
    painter.line_segment(
        [egui::pos2(c.x - r * 0.4, c.y + r * 0.3), egui::pos2(c.x - r * 0.6, c.y + r * 0.6)],
        egui::Stroke::new(1.6_f32, color),
    );
    painter.line_segment(
        [egui::pos2(c.x - r * 0.6, c.y + r * 0.6), egui::pos2(c.x - r * 0.2, c.y + r * 0.3)],
        egui::Stroke::new(1.6_f32, color),
    );
    painter.line_segment(
        [egui::pos2(c.x - r * 0.2, c.y + r * 0.3), egui::pos2(c.x + r * 0.8, c.y + r * 0.3)],
        egui::Stroke::new(1.6_f32, color),
    );
    painter.line_segment(
        [egui::pos2(c.x + r * 0.8, c.y + r * 0.3), egui::pos2(c.x + r * 0.8, c.y - r * 0.5)],
        egui::Stroke::new(1.6_f32, color),
    );
    // plus
    painter.line_segment(
        [egui::pos2(c.x - r * 0.3, c.y - r * 0.1), egui::pos2(c.x + r * 0.3, c.y - r * 0.1)],
        egui::Stroke::new(1.6_f32, color),
    );
    painter.line_segment(
        [egui::pos2(c.x, c.y - r * 0.4), egui::pos2(c.x, c.y + r * 0.2)],
        egui::Stroke::new(1.6_f32, color),
    );
}

/// Sun / moon (theme toggle).
#[allow(dead_code)]
pub(crate) fn sun(painter: &egui::Painter, c: egui::Pos2, color: egui::Color32, s: f32) {
    let r = s / 2.6;
    painter.circle_stroke(c, r, egui::Stroke::new(1.6_f32, color));
    for i in 0..8 {
        let a = std::f32::consts::TAU * (i as f32) / 8.0;
        painter.line_segment(
            [
                egui::pos2(c.x + a.cos() * (r + 2.0), c.y + a.sin() * (r + 2.0)),
                egui::pos2(c.x + a.cos() * (r + 4.5), c.y + a.sin() * (r + 4.5)),
            ],
            egui::Stroke::new(1.6_f32, color),
        );
    }
}

#[allow(dead_code)]
pub(crate) fn moon(painter: &egui::Painter, c: egui::Pos2, color: egui::Color32, s: f32) {
    let r = s / 2.2;
    painter.line_segment(
        [
            egui::pos2(c.x - r * 0.2, c.y - r),
            egui::pos2(c.x + r * 0.6, c.y - r * 0.2),
        ],
        egui::Stroke::new(1.6_f32, color),
    );
    painter.line_segment(
        [
            egui::pos2(c.x + r * 0.6, c.y - r * 0.2),
            egui::pos2(c.x + r * 0.2, c.y + r),
        ],
        egui::Stroke::new(1.6_f32, color),
    );
    painter.line_segment(
        [
            egui::pos2(c.x + r * 0.2, c.y + r),
            egui::pos2(c.x - r * 0.8, c.y),
        ],
        egui::Stroke::new(1.6_f32, color),
    );
    painter.line_segment(
        [
            egui::pos2(c.x - r * 0.8, c.y),
            egui::pos2(c.x - r * 0.2, c.y - r),
        ],
        egui::Stroke::new(1.6_f32, color),
    );
}

/// Paperclip (attach).
#[allow(dead_code)]
pub(crate) fn paperclip(painter: &egui::Painter, c: egui::Pos2, color: egui::Color32, s: f32) {
    let h = s / 2.0;
    painter.line_segment(
        [egui::pos2(c.x - h * 0.5, c.y + h * 0.7), egui::pos2(c.x + h * 0.4, c.y - h * 0.5)],
        egui::Stroke::new(1.7_f32, color),
    );
    painter.line_segment(
        [egui::pos2(c.x - h * 0.5, c.y + h * 0.7), egui::pos2(c.x - h * 0.5 + 2.0, c.y + h * 0.7)],
        egui::Stroke::new(1.7_f32, color),
    );
    painter.line_segment(
        [egui::pos2(c.x + h * 0.4, c.y - h * 0.5), egui::pos2(c.x + h * 0.1, c.y - h * 0.9)],
        egui::Stroke::new(1.7_f32, color),
    );
    painter.line_segment(
        [egui::pos2(c.x + h * 0.1, c.y - h * 0.9), egui::pos2(c.x - h * 0.4, c.y - h * 0.1)],
        egui::Stroke::new(1.7_f32, color),
    );
    painter.line_segment(
        [egui::pos2(c.x - h * 0.4, c.y - h * 0.1), egui::pos2(c.x + h * 0.1, c.y + h * 0.6)],
        egui::Stroke::new(1.7_f32, color),
    );
}

// --- ToolCallBlocks kind glyphs (lucide stand-ins at 16px stroke weight) ---

/// Document (read_file): page outline + text lines.
#[allow(dead_code)]
pub(crate) fn icon_doc(pr: &egui::Painter, c: egui::Pos2, color: egui::Color32) {
    let s = egui::Stroke::new(1.3_f32, color);
    pr.rect_stroke(
        egui::Rect::from_center_size(c, egui::vec2(10.0, 12.0)),
        2.0,
        s,
    );
    for dy in [-2.5, 0.5, 3.5] {
        pr.line_segment(
            [egui::pos2(c.x - 3.0, c.y + dy), egui::pos2(c.x + 3.0, c.y + dy)],
            s,
        );
    }
}

/// Pencil (edit_file / write_file).
#[allow(dead_code)]
pub(crate) fn icon_pencil(pr: &egui::Painter, c: egui::Pos2, color: egui::Color32) {
    let s = egui::Stroke::new(1.3_f32, color);
    let d = 5.0_f32;
    pr.line_segment(
        [egui::pos2(c.x - d, c.y + d), egui::pos2(c.x + d * 0.6, c.y - d * 0.6)],
        s,
    );
    pr.line_segment(
        [egui::pos2(c.x - d, c.y + d), egui::pos2(c.x - d + 1.0, c.y + d - 4.0)],
        s,
    );
    pr.line_segment(
        [egui::pos2(c.x + d * 0.6, c.y - d * 0.6), egui::pos2(c.x + d, c.y - d)],
        s,
    );
    pr.line_segment(
        [egui::pos2(c.x - d + 1.0, c.y + d - 4.0), egui::pos2(c.x + d, c.y - d)],
        s,
    );
}

/// Terminal (run_command): prompt chevron + caret.
#[allow(dead_code)]
pub(crate) fn icon_terminal(pr: &egui::Painter, c: egui::Pos2, color: egui::Color32) {
    let s = egui::Stroke::new(1.3_f32, color);
    pr.rect_stroke(
        egui::Rect::from_center_size(c, egui::vec2(12.0, 10.0)),
        2.0,
        s,
    );
    let x0 = c.x - 4.0;
    let y = c.y - 1.0;
    pr.line_segment([egui::pos2(x0, y - 2.0), egui::pos2(x0 + 2.5, y)], s);
    pr.line_segment([egui::pos2(x0 + 2.5, y), egui::pos2(x0, y + 2.0)], s);
    pr.line_segment(
        [egui::pos2(x0 + 4.0, y + 2.5), egui::pos2(x0 + 7.0, y + 2.5)],
        s,
    );
}

/// Magnifier (grep).
#[allow(dead_code)]
pub(crate) fn icon_magnifier(pr: &egui::Painter, c: egui::Pos2, color: egui::Color32) {
    let s = egui::Stroke::new(1.3_f32, color);
    pr.circle_stroke(c + egui::vec2(-1.0, -1.0), 3.5, s);
    pr.line_segment(
        [egui::pos2(c.x + 1.6, c.y + 1.6), egui::pos2(c.x + 4.5, c.y + 4.5)],
        s,
    );
}

/// Globe (web_fetch).
#[allow(dead_code)]
pub(crate) fn icon_globe(pr: &egui::Painter, c: egui::Pos2, color: egui::Color32) {
    let s = egui::Stroke::new(1.2_f32, color);
    pr.circle_stroke(c, 5.0, s);
    pr.line_segment(
        [egui::pos2(c.x - 5.0, c.y), egui::pos2(c.x + 5.0, c.y)],
        s,
    );
    pr.line_segment(
        [egui::pos2(c.x, c.y - 5.0), egui::pos2(c.x, c.y + 5.0)],
        s,
    );
    // Meridian arcs (approximated by two short chords).
    pr.line_segment(
        [egui::pos2(c.x - 3.0, c.y - 3.4), egui::pos2(c.x - 3.0, c.y + 3.4)],
        s,
    );
    pr.line_segment(
        [egui::pos2(c.x + 3.0, c.y - 3.4), egui::pos2(c.x + 3.0, c.y + 3.4)],
        s,
    );
}

/// Check inside a circle (todo tools).
#[allow(dead_code)]
pub(crate) fn icon_check_circle(pr: &egui::Painter, c: egui::Pos2, color: egui::Color32) {
    let s = egui::Stroke::new(1.2_f32, color);
    pr.circle_stroke(c, 5.0, s);
    let pts = [
        egui::pos2(c.x - 2.4, c.y + 0.2),
        egui::pos2(c.x - 0.6, c.y + 2.0),
        egui::pos2(c.x + 2.6, c.y - 1.8),
    ];
    pr.add(egui::Shape::line(pts.to_vec(), s));
}

/// Folder (list_files / glob).
#[allow(dead_code)]
pub(crate) fn icon_folder(pr: &egui::Painter, c: egui::Pos2, color: egui::Color32) {
    let s = egui::Stroke::new(1.3_f32, color);
    let r = egui::Rect::from_center_size(c, egui::vec2(12.0, 9.0));
    pr.rect_stroke(r, 2.0, s);
    pr.line_segment(
        [egui::pos2(r.left(), r.top() + 2.5), egui::pos2(r.left() + 4.0, r.top() + 2.5)],
        s,
    );
}

/// Trash (delete_file).
#[allow(dead_code)]
pub(crate) fn icon_trash(pr: &egui::Painter, c: egui::Pos2, color: egui::Color32) {
    let s = egui::Stroke::new(1.2_f32, color);
    pr.line_segment(
        [egui::pos2(c.x - 4.5, c.y - 3.0), egui::pos2(c.x + 4.5, c.y - 3.0)],
        s,
    );
    pr.line_segment(
        [egui::pos2(c.x - 1.5, c.y - 3.0), egui::pos2(c.x - 1.5, c.y - 5.0)],
        s,
    );
    pr.line_segment(
        [egui::pos2(c.x + 1.5, c.y - 3.0), egui::pos2(c.x + 1.5, c.y - 5.0)],
        s,
    );
    pr.line_segment(
        [egui::pos2(c.x - 1.5, c.y - 5.0), egui::pos2(c.x + 1.5, c.y - 5.0)],
        s,
    );
    pr.line_segment(
        [egui::pos2(c.x - 3.0, c.y - 3.0), egui::pos2(c.x - 2.5, c.y + 5.0)],
        s,
    );
    pr.line_segment(
        [egui::pos2(c.x + 3.0, c.y - 3.0), egui::pos2(c.x + 2.5, c.y + 5.0)],
        s,
    );
    pr.line_segment(
        [egui::pos2(c.x - 2.5, c.y + 5.0), egui::pos2(c.x + 2.5, c.y + 5.0)],
        s,
    );
}

/// Four-point spark (load_skill).
#[allow(dead_code)]
pub(crate) fn icon_spark(pr: &egui::Painter, c: egui::Pos2, color: egui::Color32) {
    let pts = [
        egui::pos2(c.x, c.y - 5.5),
        egui::pos2(c.x + 1.4, c.y - 1.4),
        egui::pos2(c.x + 5.5, c.y),
        egui::pos2(c.x + 1.4, c.y + 1.4),
        egui::pos2(c.x, c.y + 5.5),
        egui::pos2(c.x - 1.4, c.y + 1.4),
        egui::pos2(c.x - 5.5, c.y),
        egui::pos2(c.x - 1.4, c.y - 1.4),
    ];
    pr.add(egui::Shape::convex_polygon(
        pts.to_vec(),
        color.gamma_multiply(0.35),
        egui::Stroke::new(1.2_f32, color),
    ));
}

/// Speech bubble (ask tools).
#[allow(dead_code)]
pub(crate) fn icon_bubble(pr: &egui::Painter, c: egui::Pos2, color: egui::Color32) {
    let s = egui::Stroke::new(1.3_f32, color);
    let r = egui::Rect::from_min_max(egui::pos2(c.x - 5.5, c.y - 4.5), egui::pos2(c.x + 5.5, c.y + 2.5));
    pr.rect_stroke(r, 3.0, s);
    pr.line_segment(
        [egui::pos2(c.x - 2.0, c.y + 2.5), egui::pos2(c.x - 2.0, c.y + 5.5)],
        s,
    );
    pr.line_segment(
        [egui::pos2(c.x - 2.0, c.y + 5.5), egui::pos2(c.x + 1.0, c.y + 2.5)],
        s,
    );
}

/// Plug (MCP tools).
#[allow(dead_code)]
pub(crate) fn icon_plug(pr: &egui::Painter, c: egui::Pos2, color: egui::Color32) {
    let s = egui::Stroke::new(1.2_f32, color);
    pr.line_segment(
        [egui::pos2(c.x - 2.5, c.y - 5.0), egui::pos2(c.x - 2.5, c.y - 1.0)],
        s,
    );
    pr.line_segment(
        [egui::pos2(c.x + 2.5, c.y - 5.0), egui::pos2(c.x + 2.5, c.y - 1.0)],
        s,
    );
    pr.line_segment(
        [
            egui::pos2(c.x - 4.0, c.y - 1.0),
            egui::pos2(c.x + 4.0, c.y - 1.0),
        ],
        s,
    );
    pr.line_segment(
        [egui::pos2(c.x - 4.0, c.y - 1.0), egui::pos2(c.x - 4.0, c.y + 1.0)],
        s,
    );
    pr.line_segment(
        [egui::pos2(c.x + 4.0, c.y - 1.0), egui::pos2(c.x + 4.0, c.y + 1.0)],
        s,
    );
    pr.line_segment(
        [egui::pos2(c.x - 3.0, c.y + 1.0), egui::pos2(c.x - 3.0, c.y + 3.0)],
        s,
    );
    pr.line_segment(
        [egui::pos2(c.x + 3.0, c.y + 1.0), egui::pos2(c.x + 3.0, c.y + 3.0)],
        s,
    );
    pr.line_segment(
        [egui::pos2(c.x, c.y + 2.5), egui::pos2(c.x, c.y + 5.0)],
        s,
    );
}

/// Wrench (unmapped tools).
#[allow(dead_code)]
pub(crate) fn icon_wrench(pr: &egui::Painter, c: egui::Pos2, color: egui::Color32) {
    let s = egui::Stroke::new(1.3_f32, color);
    pr.line_segment(
        [egui::pos2(c.x - 3.5, c.y + 3.5), egui::pos2(c.x + 1.5, c.y - 1.5)],
        s,
    );
    pr.circle_stroke(c + egui::vec2(2.5, -2.5), 2.5, s);
    pr.line_segment(
        [egui::pos2(c.x - 5.0, c.y + 2.0), egui::pos2(c.x - 2.0, c.y + 5.0)],
        s,
    );
}
