//! XenCode Desktop theme, the DeepSeek Harness (`dsh`) design system,
//! calibrated against the shipped desktop app screenshot (open-deepseek-
//! harness-app, dark theme) pixel for pixel. Only the brand glyph differs
//! (XenCode logo).

use eframe::egui;

// ── Surfaces (sampled from the shipped dsh dark theme) ─────────────────────
/// Main area background (`--dsw-alias-bg-base`, neutral-bluish-950).
pub const BG_BASE: egui::Color32 = egui::Color32::from_rgb(21, 21, 23);
/// Sidebar fill (`--dsw-specific-sidebar-fill`, neutral-bluish-900).
pub const SIDEBAR: egui::Color32 = egui::Color32::from_rgb(27, 27, 28);
/// Composer card / bubbles / settings cards (`--dsw-specific-input-major`, 850).
pub const CARD: egui::Color32 = egui::Color32::from_rgb(44, 44, 46);
/// Nested controls: attach circle, inputs inside cards (neutral-bluish-800).
pub const CARD_HIGHER: egui::Color32 = egui::Color32::from_rgb(53, 54, 56);
/// Nested control hover.
pub const CARD_HIGHER_HOVER: egui::Color32 = egui::Color32::from_rgb(66, 67, 70);
/// New Session bar (`--dsw-alias-button-elevated-fill`, ~neutral-bluish-750).
pub const ELEVATED: egui::Color32 = egui::Color32::from_rgb(68, 70, 74);
/// New Session bar hover.
pub const ELEVATED_HOVER: egui::Color32 = egui::Color32::from_rgb(80, 82, 86);
/// Dark code blocks (`--dsw-alias-markdown-code-block`, 900).
pub const CODE_BG: egui::Color32 = egui::Color32::from_rgb(27, 27, 28);
/// Menus / popovers (elevated input surface).
pub const MENU: egui::Color32 = egui::Color32::from_rgb(44, 44, 46);

// ── Text (`--dsw-alias-label-*`, dark ramp) ────────────────────────────────
pub const TEXT_1: egui::Color32 = egui::Color32::from_rgb(249, 250, 251); // primary
pub const TEXT_2: egui::Color32 = egui::Color32::from_rgb(207, 211, 214); // secondary
pub const TEXT_3: egui::Color32 = egui::Color32::from_rgb(173, 178, 184); // tertiary
pub const TEXT_4: egui::Color32 = egui::Color32::from_rgb(129, 133, 140); // caption
pub const TEXT_DIM: egui::Color32 = egui::Color32::from_rgb(67, 69, 74); // dimmed
pub const INK: egui::Color32 = egui::Color32::from_rgb(21, 21, 23); // on light fills

// ── Brand blue (dsh deepseek scale) ────────────────────────────────────────
/// Send button / caret (`--dsw-alias-button-info-fill` dark, #679EFE).
pub const SEND_BLUE: egui::Color32 = egui::Color32::from_rgb(103, 158, 254);
/// Send button over an empty draft: info-fill at 0.4 opacity on the card,
/// exactly as the shipped home screenshot renders it.
pub const SEND_BLUE_DIM: egui::Color32 = egui::Color32::from_rgb(67, 90, 130);
/// Links / info text (deepseek-400 dark).
pub const BLUE_400: egui::Color32 = egui::Color32::from_rgb(122, 170, 255);
/// Business primary (deepseek-500), caret and focus accents.
pub const BLUE_500: egui::Color32 = egui::Color32::from_rgb(65, 118, 230);
/// Preview badge fill (`--dsw-alias-state-business-tertiary` = deepseek-800).
pub const BADGE_BG: egui::Color32 = egui::Color32::from_rgb(52, 65, 91);
/// Preview badge text.
pub const BADGE_TEXT: egui::Color32 = egui::Color32::from_rgb(201, 217, 236);

// ── States ─────────────────────────────────────────────────────────────────
pub const SUCCESS: egui::Color32 = egui::Color32::from_rgb(34, 197, 94);
pub const SUCCESS_DIM: egui::Color32 = egui::Color32::from_rgb(35, 60, 44);
pub const WARN: egui::Color32 = egui::Color32::from_rgb(245, 158, 11);
pub const WARN_DIM: egui::Color32 = egui::Color32::from_rgb(39, 36, 31);
pub const ERROR: egui::Color32 = egui::Color32::from_rgb(242, 90, 90);
pub const ERROR_DIM: egui::Color32 = egui::Color32::from_rgb(60, 31, 27);
pub const VIOLET: egui::Color32 = egui::Color32::from_rgb(167, 139, 250);

// ── Strokes (white-alpha hairlines, dark theme) ────────────────────────────
/// `--dsw-alias-border-l2` (12%): composer card stroke.
pub fn BORDER_2() -> egui::Color32 { egui::Color32::from_white_alpha(31) }
/// `--dsw-alias-border-l3` (16%): sidebar divider, New Session stroke.
pub fn BORDER_3() -> egui::Color32 { egui::Color32::from_white_alpha(41) }
/// `--dsw-alias-border-l1` (8%): card strokes inside content.
pub fn BORDER_1() -> egui::Color32 { egui::Color32::from_white_alpha(20) }
/// `--dsw-alias-interactive-bg-hover` (8%): row hover fills.
pub fn HOVER() -> egui::Color32 { egui::Color32::from_white_alpha(20) }
/// Selected row fill (14%).
pub fn ACTIVE() -> egui::Color32 { egui::Color32::from_white_alpha(36) }

// ── Radii (`--dsw-radius-*`) ───────────────────────────────────────────────
pub const R_SM: f32 = 8.0;
pub const R_MD: f32 = 12.0;
pub const R_LG: f32 = 16.0;
/// Composer card and hero surfaces (`--dsw-radius-panel`).
pub const R_PANEL: f32 = 28.0;

/// Sidebar geometry (`ui-layout/columns.ts` defaults, scaled to the capture
/// window): expanded 260, collapsed rail 56.
pub const SIDEBAR_W: f32 = 260.0;
pub const RAIL_W: f32 = 56.0;
/// Conversation / composer column (matches the shipped screenshot's card).
pub const COLUMN: f32 = 640.0;

/// A 1px hairline in the given color.
pub fn hairline(color: egui::Color32) -> egui::Stroke {
    egui::Stroke::new(1.0, color)
}

/// Installs the dsh design system into the egui context: dark visuals,
/// surface colors, widget states, spacing and the dsh type scale.
pub fn install(cc: &eframe::CreationContext<'_>) {
    let mut style = egui::Style::default();
    style.text_styles = [
        (egui::TextStyle::Heading, egui::FontId::proportional(19.0)),
        (egui::TextStyle::Body, egui::FontId::proportional(14.0)),
        (egui::TextStyle::Monospace, egui::FontId::monospace(12.5)),
        (egui::TextStyle::Button, egui::FontId::proportional(13.5)),
        (egui::TextStyle::Small, egui::FontId::proportional(11.5)),
    ]
    .into_iter()
    .collect();
    let v = &mut style.visuals;
    *v = egui::Visuals::dark();
    v.dark_mode = true;
    v.override_text_color = Some(TEXT_1);
    v.panel_fill = BG_BASE;
    v.window_fill = CARD;
    v.extreme_bg_color = CODE_BG;
    v.faint_bg_color = CARD;
    v.selection.bg_fill = egui::Color32::from_rgba_premultiplied(18, 34, 66, 96);
    v.selection.stroke = egui::Stroke::new(1.0, BLUE_400);
    v.window_stroke = hairline(BORDER_1());
    v.window_rounding = egui::Rounding::same(R_LG);
    v.menu_rounding = egui::Rounding::same(R_MD);
    v.popup_shadow = egui::epaint::Shadow::NONE;
    v.window_shadow = egui::epaint::Shadow::NONE;

    v.widgets.noninteractive.bg_fill = CARD;
    v.widgets.noninteractive.bg_stroke = hairline(BORDER_1());
    v.widgets.noninteractive.fg_stroke = egui::Stroke::new(1.0, TEXT_2);
    v.widgets.noninteractive.rounding = egui::Rounding::same(R_SM);

    v.widgets.inactive.bg_fill = HOVER();
    v.widgets.inactive.bg_stroke = egui::Stroke::NONE;
    v.widgets.inactive.fg_stroke = egui::Stroke::new(1.0, TEXT_2);
    v.widgets.inactive.rounding = egui::Rounding::same(R_SM);

    v.widgets.hovered.bg_fill = ACTIVE();
    v.widgets.hovered.bg_stroke = egui::Stroke::NONE;
    v.widgets.hovered.fg_stroke = egui::Stroke::new(1.0, TEXT_1);
    v.widgets.hovered.rounding = egui::Rounding::same(R_SM);

    v.widgets.active.bg_fill = ACTIVE();
    v.widgets.active.fg_stroke = egui::Stroke::new(1.0, TEXT_1);
    v.widgets.active.rounding = egui::Rounding::same(R_SM);

    v.widgets.open.bg_fill = HOVER();
    v.widgets.open.rounding = egui::Rounding::same(R_SM);

    style.spacing.button_padding = egui::vec2(10.0, 5.0);
    style.spacing.item_spacing = egui::vec2(8.0, 8.0);
    style.spacing.interact_size = egui::vec2(40.0, 30.0);
    style.spacing.menu_margin = egui::Margin::same(6.0);
    style.spacing.scroll.bar_width = 8.0;
    style.spacing.scroll.bar_inner_margin = 3.0;
    style.spacing.scroll.bar_outer_margin = 2.0;
    cc.egui_ctx.set_style(style);
}

// ── dsh-shaped building blocks ─────────────────────────────────────────────

/// A dsh content card: 850 fill, 16px radius, hairline border.
pub fn card(ui: &mut egui::Ui, add: impl FnOnce(&mut egui::Ui)) {
    egui::Frame::none()
        .fill(CARD)
        .rounding(egui::Rounding::same(R_LG))
        .stroke(hairline(BORDER_1()))
        .inner_margin(egui::Margin::same(14.0))
        .show(ui, |ui| {
            ui.set_min_width(ui.available_width());
            add(ui);
        });
}

/// A dark code/terminal block (900 fill, 8px radius).
pub fn code_block(ui: &mut egui::Ui, add: impl FnOnce(&mut egui::Ui)) {
    egui::Frame::none()
        .fill(CODE_BG)
        .rounding(egui::Rounding::same(R_SM))
        .stroke(hairline(BORDER_1()))
        .inner_margin(egui::Margin::same(12.0))
        .show(ui, |ui| {
            ui.set_min_width(ui.available_width());
            add(ui);
        });
}

/// Pill-shaped badge (dsh 999 radius, mono caption).
pub fn pill(ui: &mut egui::Ui, text: &str, fill: egui::Color32, ink: egui::Color32) {
    let width = 16.0 + text.chars().count() as f32 * 7.2;
    let (rect, _) = ui.allocate_exact_size(egui::vec2(width, 20.0), egui::Sense::hover());
    ui.painter().rect_filled(rect, 10.0, fill);
    ui.painter().text(
        rect.center(),
        egui::Align2::CENTER_CENTER,
        text,
        egui::FontId::monospace(11.5),
        ink,
    );
}

/// Ghost button (dsh button-ghost): transparent, hover wash, secondary ink.
pub fn ghost(ui: &mut egui::Ui, label: &str) -> egui::Response {
    ui.add(egui::Button::new(egui::RichText::new(label).color(TEXT_2)).frame(true))
}

/// Primary button (dsh button-primary: near-white fill, ink glyph).
pub fn primary(ui: &mut egui::Ui, label: &str) -> egui::Response {
    ui.add(
        egui::Button::new(egui::RichText::new(label).color(INK).strong())
            .fill(TEXT_1)
            .rounding(egui::Rounding::same(R_SM)),
    )
}

/// One menu entry inside a dsh popover; returns true when clicked.
pub fn menu_entry(ui: &mut egui::Ui, label: &str, selected: bool) -> bool {
    let (rect, resp) =
        ui.allocate_exact_size(egui::vec2(ui.available_width(), 28.0), egui::Sense::click());
    let fill = if resp.hovered() {
        HOVER()
    } else {
        egui::Color32::TRANSPARENT
    };
    ui.painter().rect_filled(rect, R_SM, fill);
    ui.painter().text(
        egui::pos2(rect.left() + 10.0, rect.center().y),
        egui::Align2::LEFT_CENTER,
        label,
        egui::FontId::proportional(13.0),
        if selected { TEXT_1 } else { TEXT_2 },
    );
    if selected {
        ui.painter().circle_filled(egui::pos2(rect.right() - 14.0, rect.center().y), 3.0, SEND_BLUE);
    }
    resp.clicked()
}

/// Chevron-down glyph (12px).
pub fn chevron_down(p: &egui::Painter, c: egui::Pos2, color: egui::Color32, size: f32) {
    let s = size / 2.0;
    p.add(egui::Shape::line(
        vec![
            egui::pos2(c.x - s, c.y - s / 2.0),
            egui::pos2(c.x, c.y + s / 2.0),
            egui::pos2(c.x + s, c.y - s / 2.0),
        ],
        egui::Stroke::new(1.4, color),
    ));
}

/// Plus glyph.
pub fn plus(p: &egui::Painter, c: egui::Pos2, color: egui::Color32, size: f32) {
    let s = size / 2.0;
    p.line_segment([egui::pos2(c.x - s, c.y), egui::pos2(c.x + s, c.y)], egui::Stroke::new(1.6, color));
    p.line_segment([egui::pos2(c.x, c.y - s), egui::pos2(c.x, c.y + s)], egui::Stroke::new(1.6, color));
}

/// Search glyph: circle + handle.
pub fn search(p: &egui::Painter, c: egui::Pos2, color: egui::Color32, size: f32) {
    let r = size * 0.32;
    p.circle_stroke(egui::pos2(c.x - size * 0.08, c.y - size * 0.08), r, egui::Stroke::new(1.5, color));
    p.line_segment(
        [egui::pos2(c.x + size * 0.14, c.y + size * 0.14), egui::pos2(c.x + size * 0.42, c.y + size * 0.42)],
        egui::Stroke::new(1.5, color),
    );
}

/// Sliders (filter) glyph: two lines with knobs.
pub fn sliders(p: &egui::Painter, c: egui::Pos2, color: egui::Color32, size: f32) {
    let s = size / 2.0;
    let y1 = c.y - s * 0.45;
    let y2 = c.y + s * 0.45;
    p.line_segment([egui::pos2(c.x - s, y1), egui::pos2(c.x + s, y1)], egui::Stroke::new(1.4, color));
    p.line_segment([egui::pos2(c.x - s, y2), egui::pos2(c.x + s, y2)], egui::Stroke::new(1.4, color));
    p.circle_filled(egui::pos2(c.x + s * 0.25, y1), 2.4, color);
    p.circle_filled(egui::pos2(c.x - s * 0.25, y2), 2.4, color);
}

/// Folder glyph (16px, stroke outline with tab).
pub fn folder(p: &egui::Painter, rect: egui::Rect, color: egui::Color32, open: bool) {
    let x = rect.left();
    let y = rect.top();
    let w = rect.width();
    let h = rect.height();
    let st = egui::Stroke::new(1.4, color);
    if open {
        // Back panel + tilted front flap.
        p.add(egui::Shape::line(
            vec![
                egui::pos2(x, y + h * 0.86),
                egui::pos2(x, y + h * 0.18),
                egui::pos2(x + w * 0.34, y + h * 0.18),
                egui::pos2(x + w * 0.44, y + h * 0.34),
                egui::pos2(x + w * 0.92, y + h * 0.34),
            ],
            st,
        ));
        p.add(egui::Shape::line(
            vec![
                egui::pos2(x + w * 0.10, y + h * 0.86),
                egui::pos2(x + w * 0.30, y + h * 0.44),
                egui::pos2(x + w * 0.98, y + h * 0.44),
                egui::pos2(x + w * 0.78, y + h * 0.86),
                egui::pos2(x + w * 0.10, y + h * 0.86),
            ],
            st,
        ));
    } else {
        p.add(egui::Shape::line(
            vec![
                egui::pos2(x, y + h * 0.84),
                egui::pos2(x, y + h * 0.16),
                egui::pos2(x + w * 0.36, y + h * 0.16),
                egui::pos2(x + w * 0.48, y + h * 0.34),
                egui::pos2(x + w * 0.96, y + h * 0.34),
                egui::pos2(x + w * 0.96, y + h * 0.84),
                egui::pos2(x, y + h * 0.84),
            ],
            st,
        ));
    }
}

/// Folder with a small plus (add workspace).
pub fn folder_plus(p: &egui::Painter, rect: egui::Rect, color: egui::Color32) {
    folder(p, rect, color, false);
    let c = egui::pos2(rect.right() - rect.width() * 0.08, rect.top() + rect.height() * 0.10);
    plus(p, c, color, 7.0);
}

/// Shield glyph (filled pentagon, permission presets).
pub fn shield(p: &egui::Painter, c: egui::Pos2, color: egui::Color32, size: f32) {
    let s = size / 2.0;
    let pts = vec![
        egui::pos2(c.x - s * 0.9, c.y - s * 0.8),
        egui::pos2(c.x + s * 0.9, c.y - s * 0.8),
        egui::pos2(c.x + s * 0.9, c.y + s * 0.1),
        egui::pos2(c.x, c.y + s * 0.9),
        egui::pos2(c.x - s * 0.9, c.y + s * 0.1),
    ];
    p.add(egui::Shape::convex_polygon(pts, color, egui::Stroke::NONE));
}

/// Bolt-in-circle glyph (agent preset seat).
pub fn bolt_mode(p: &egui::Painter, c: egui::Pos2, color: egui::Color32, size: f32) {
    let s = size / 2.0;
    p.circle_stroke(c, s * 0.95, egui::Stroke::new(1.3, color));
    let pts = vec![
        egui::pos2(c.x + s * 0.25, c.y - s * 0.62),
        egui::pos2(c.x - s * 0.38, c.y + s * 0.10),
        egui::pos2(c.x - s * 0.02, c.y + s * 0.10),
        egui::pos2(c.x - s * 0.25, c.y + s * 0.62),
        egui::pos2(c.x + s * 0.38, c.y - s * 0.10),
        egui::pos2(c.x + s * 0.02, c.y - s * 0.10),
    ];
    p.add(egui::Shape::convex_polygon(pts, color, egui::Stroke::NONE));
}

/// Gear glyph (settings).
pub fn gear(p: &egui::Painter, c: egui::Pos2, color: egui::Color32, size: f32) {
    let r = size * 0.34;
    p.circle_stroke(c, r, egui::Stroke::new(1.4, color));
    p.circle_filled(c, size * 0.10, color);
    for i in 0..8 {
        let a = i as f32 * std::f32::consts::TAU / 8.0;
        let d1 = r + 0.5;
        let d2 = size * 0.48;
        p.line_segment(
            [
                egui::pos2(c.x + a.cos() * d1, c.y + a.sin() * d1),
                egui::pos2(c.x + a.cos() * d2, c.y + a.sin() * d2),
            ],
            egui::Stroke::new(1.6, color),
        );
    }
}

/// Sidebar panel glyph (rounded rect with a vertical divider).
pub fn panel_icon(p: &egui::Painter, rect: egui::Rect, color: egui::Color32) {
    let st = egui::Stroke::new(1.4, color);
    p.rect_stroke(rect.shrink(1.0), 4.0, st);
    let x = rect.left() + rect.width() * 0.36;
    p.line_segment([egui::pos2(x, rect.top() + 3.0), egui::pos2(x, rect.bottom() - 3.0)], st);
}

/// Up-arrow send glyph (the dsh composer arrow path).
pub fn arrow_up(p: &egui::Painter, c: egui::Pos2, color: egui::Color32, size: f32) {
    let s = size / 2.0;
    p.line_segment(
        [egui::pos2(c.x, c.y + s * 0.8), egui::pos2(c.x, c.y - s * 0.45)],
        egui::Stroke::new(1.8, color),
    );
    let head = vec![
        egui::pos2(c.x - s * 0.55, c.y - s * 0.05),
        egui::pos2(c.x + s * 0.55, c.y - s * 0.05),
        egui::pos2(c.x, c.y - s * 0.75),
    ];
    p.add(egui::Shape::convex_polygon(head, color, egui::Stroke::NONE));
}

/// Radial glow behind the hero: layered alpha circles, peak +9 blue luminance
/// over the base, matching the shipped screenshot's ambient wash.
pub fn hero_glow(p: &egui::Painter, center: egui::Pos2, radius_x: f32, radius_y: f32) {
    let steps = 22;
    for i in 0..steps {
        let t = i as f32 / steps as f32;
        // Peak alpha ~14/255 of the glow tint at the center, fading to 0.
        let a = ((1.0 - t) * (1.0 - t) * 10.0) as u8;
        let rx = radius_x * (0.25 + 0.75 * t);
        let ry = radius_y * (0.25 + 0.75 * t);
        let rect = egui::Rect::from_center_size(center, egui::vec2(rx * 2.0, ry * 2.0));
        p.rect_filled(
            rect,
            egui::Rounding::same(rx.min(ry)),
            egui::Color32::from_rgba_premultiplied((58 * a as u32 / 255) as u8, (84 * a as u32 / 255) as u8, (138 * a as u32 / 255) as u8, a),
        );
    }
}
