//! XenCode Desktop theme — a faithful port of the DeepSeek Harness (`dsh`)
//! Web UI design system to egui.
//!
//! Every color below is lifted verbatim from dsh's
//! `packages/client/ui-theme/src/styles/design-platform.css` (dark theme) and
//! `base.css` (radii / motion). Only the brand glyph differs (XenCode logo).

use eframe::egui;

// ── Surfaces (dsw-static-neutral-bluish-*) ─────────────────────────────────
/// `--dsw-alias-bg-base` = neutral-bluish-950
pub const BG_BASE: egui::Color32 = egui::Color32::from_rgb(21, 21, 23);
/// `--dsw-specific-sidebar-fill` = neutral-bluish-900
pub const SIDEBAR: egui::Color32 = egui::Color32::from_rgb(27, 27, 28);
/// `--dsw-alias-bg-layer-2` = neutral-bluish-850 (cards, bubbles, composer)
pub const LAYER_2: egui::Color32 = egui::Color32::from_rgb(44, 44, 46);
/// `--dsw-alias-bg-layer-3` = neutral-bluish-800 (nested surfaces)
pub const LAYER_3: egui::Color32 = egui::Color32::from_rgb(53, 54, 56);
/// `--dsw-specific-sidebar-nav-item-active` = neutral-bluish-750
pub const SIDEBAR_ACTIVE: egui::Color32 = egui::Color32::from_rgb(67, 69, 74);
/// `--dsw-menu-surface-fill` (opaque approximation)
pub const MENU: egui::Color32 = egui::Color32::from_rgb(48, 49, 54);
/// `--dsw-alias-markdown-code-block` = neutral-bluish-900 (dark code blocks)
pub const CODE_BG: egui::Color32 = egui::Color32::from_rgb(27, 27, 28);

// ── Text (dsw-alias-label-*) ───────────────────────────────────────────────
pub const TEXT_1: egui::Color32 = egui::Color32::from_rgb(249, 250, 251); // label-primary = bluish-50
pub const TEXT_2: egui::Color32 = egui::Color32::from_rgb(207, 211, 214); // label-secondary = bluish-300
pub const TEXT_3: egui::Color32 = egui::Color32::from_rgb(173, 178, 184); // label-tertiary = bluish-400
pub const TEXT_4: egui::Color32 = egui::Color32::from_rgb(129, 133, 140); // label-caption = bluish-600
pub const TEXT_DIM: egui::Color32 = egui::Color32::from_rgb(67, 69, 74); // label-dimmed = bluish-750
pub const INK_ON_LIGHT: egui::Color32 = egui::Color32::from_rgb(21, 21, 23);

// ── DeepSeek brand scale (dsw-static-deepseek-*) ───────────────────────────
pub const BRAND_100: egui::Color32 = egui::Color32::from_rgb(228, 237, 253);
pub const BRAND_400: egui::Color32 = egui::Color32::from_rgb(122, 170, 255); // links / info (dark)
pub const BRAND_450: egui::Color32 = egui::Color32::from_rgb(86, 134, 254); // brand-primary (dark)
pub const BRAND_500: egui::Color32 = egui::Color32::from_rgb(65, 118, 230); // state-business
pub const BRAND_800: egui::Color32 = egui::Color32::from_rgb(52, 65, 91); // business-tertiary (dark)

// ── States (dsw-static-green/amber/red/violet) ─────────────────────────────
pub const SUCCESS: egui::Color32 = egui::Color32::from_rgb(34, 197, 94);
pub const SUCCESS_DIM: egui::Color32 = egui::Color32::from_rgb(35, 60, 44);
pub const WARN: egui::Color32 = egui::Color32::from_rgb(245, 158, 11);
pub const WARN_DIM: egui::Color32 = egui::Color32::from_rgb(39, 36, 31);
pub const ERROR: egui::Color32 = egui::Color32::from_rgb(242, 90, 90);
pub const ERROR_DIM: egui::Color32 = egui::Color32::from_rgb(60, 31, 27);
pub const VIOLET: egui::Color32 = egui::Color32::from_rgb(167, 139, 250);

// ── Borders & interaction (dsw-alias-border-*/interactive-*) ───────────────
pub const BORDER_1: egui::Color32 = egui::Color32::from_rgba_premultiplied(15, 15, 15, 15); // border-l1 ≈ 6%
pub const BORDER_2: egui::Color32 = egui::Color32::from_rgba_premultiplied(31, 31, 31, 31); // border-l2 ≈ 12%
pub const BORDER_3: egui::Color32 = egui::Color32::from_rgba_premultiplied(41, 41, 41, 41); // border-l3 ≈ 16%
pub const HOVER: egui::Color32 = egui::Color32::from_rgba_premultiplied(20, 20, 20, 20); // interactive-hover ≈ 8%
pub const ACTIVE: egui::Color32 = egui::Color32::from_rgba_premultiplied(36, 36, 36, 36); // interactive-active ≈ 14%

// ── Radii (--dsw-radius-*) ─────────────────────────────────────────────────
pub const R_XS: f32 = 4.0;
pub const R_SM: f32 = 8.0;
pub const R_MD: f32 = 12.0;
pub const R_LG: f32 = 16.0;
pub const R_XL: f32 = 20.0;
pub const R_PANEL: f32 = 28.0;

/// dsh conversation column width (`max-w-3xl` equivalent).
pub const COLUMN: f32 = 760.0;

/// Installs the dsh design system into the egui context: dark visuals,
/// surface colors, widget states, spacing and type scale (14px body).
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
    v.window_fill = LAYER_2;
    v.extreme_bg_color = CODE_BG;
    v.faint_bg_color = LAYER_2;
    v.selection.bg_fill = egui::Color32::from_rgba_unmultiplied(65, 118, 230, 96);
    v.selection.stroke = egui::Stroke::new(1.0_f32, BRAND_400);
    v.window_stroke = egui::Stroke::new(1.0_f32, BORDER_1);
    v.window_rounding = egui::Rounding::same(R_LG);
    v.menu_rounding = egui::Rounding::same(R_MD);
    v.popup_shadow = egui::epaint::Shadow::NONE;
    v.window_shadow = egui::epaint::Shadow::NONE;

    v.widgets.noninteractive.bg_fill = LAYER_2;
    v.widgets.noninteractive.bg_stroke = egui::Stroke::new(1.0_f32, BORDER_1);
    v.widgets.noninteractive.fg_stroke = egui::Stroke::new(1.0_f32, TEXT_2);
    v.widgets.noninteractive.rounding = egui::Rounding::same(R_SM);

    v.widgets.inactive.bg_fill = HOVER;
    v.widgets.inactive.bg_stroke = egui::Stroke::NONE;
    v.widgets.inactive.fg_stroke = egui::Stroke::new(1.0_f32, TEXT_2);
    v.widgets.inactive.rounding = egui::Rounding::same(R_SM);

    v.widgets.hovered.bg_fill = ACTIVE;
    v.widgets.hovered.bg_stroke = egui::Stroke::NONE;
    v.widgets.hovered.fg_stroke = egui::Stroke::new(1.0_f32, TEXT_1);
    v.widgets.hovered.rounding = egui::Rounding::same(R_SM);

    v.widgets.active.bg_fill = ACTIVE;
    v.widgets.active.fg_stroke = egui::Stroke::new(1.0_f32, TEXT_1);
    v.widgets.active.rounding = egui::Rounding::same(R_SM);

    v.widgets.open.bg_fill = HOVER;
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

// ── Reusable dsh-shaped building blocks ────────────────────────────────────

/// A dsh settings/content card: layer-2 fill, 12px radius, hairline border.
pub fn card(ui: &mut egui::Ui, add: impl FnOnce(&mut egui::Ui)) {
    egui::Frame::none()
        .fill(LAYER_2)
        .rounding(egui::Rounding::same(R_MD))
        .stroke(egui::Stroke::new(1.0_f32, BORDER_1))
        .inner_margin(egui::Margin::same(14.0))
        .show(ui, |ui| {
            ui.set_min_width(ui.available_width());
            add(ui);
        });
}

/// A dark code/terminal block (`--dsw-alias-markdown-code-block`).
pub fn code_block(ui: &mut egui::Ui, add: impl FnOnce(&mut egui::Ui)) {
    egui::Frame::none()
        .fill(CODE_BG)
        .rounding(egui::Rounding::same(R_SM))
        .stroke(egui::Stroke::new(1.0_f32, BORDER_1))
        .inner_margin(egui::Margin::same(12.0))
        .show(ui, |ui| {
            add(ui);
        });
}

/// Full-width sidebar nav row: painted dot accent + label (dsh panel rows).
pub fn nav_row(ui: &mut egui::Ui, selected: bool, label: &str, accent: egui::Color32) -> egui::Response {
    let height = 30.0;
    let (rect, resp) = ui.allocate_exact_size(egui::vec2(ui.available_width(), height), egui::Sense::click());
    let fill = if selected {
        SIDEBAR_ACTIVE
    } else if resp.hovered() {
        HOVER
    } else {
        egui::Color32::TRANSPARENT
    };
    ui.painter().rect_filled(rect, R_SM, fill);
    ui.painter()
        .circle_filled(egui::pos2(rect.left() + 16.0, rect.center().y), 3.0, accent);
    ui.painter().text(
        egui::pos2(rect.left() + 28.0, rect.center().y),
        egui::Align2::LEFT_CENTER,
        label,
        egui::FontId::proportional(13.5),
        if selected { TEXT_1 } else { TEXT_2 },
    );
    resp
}

/// A shared-DB conversation row in the sidebar (title + recency caption).
pub fn session_row(ui: &mut egui::Ui, selected: bool, title: &str, sub: &str) -> egui::Response {
    let height = 42.0;
    let (rect, resp) = ui.allocate_exact_size(egui::vec2(ui.available_width(), height), egui::Sense::click());
    let fill = if selected {
        SIDEBAR_ACTIVE
    } else if resp.hovered() {
        HOVER
    } else {
        egui::Color32::TRANSPARENT
    };
    ui.painter().rect_filled(rect, R_SM, fill);
    let text_color = if selected { TEXT_1 } else { TEXT_2 };
    ui.painter().text(
        egui::pos2(rect.left() + 10.0, rect.top() + 15.0),
        egui::Align2::LEFT_CENTER,
        ellipsize(title, 34),
        egui::FontId::proportional(13.0),
        text_color,
    );
    ui.painter().text(
        egui::pos2(rect.left() + 10.0, rect.top() + 31.0),
        egui::Align2::LEFT_CENTER,
        ellipsize(sub, 40),
        egui::FontId::proportional(11.0),
        TEXT_4,
    );
    resp
}

/// Pill-shaped mode chip (dsh approval presets / toggles).
pub fn chip(ui: &mut egui::Ui, selected: bool, label: &str, dot: egui::Color32) -> egui::Response {
    let width = 22.0 + label.chars().count() as f32 * 7.3;
    let (rect, resp) = ui.allocate_exact_size(egui::vec2(width, 26.0), egui::Sense::click());
    let fill = if selected {
        ACTIVE
    } else if resp.hovered() {
        HOVER
    } else {
        egui::Color32::TRANSPARENT
    };
    ui.painter().rect_filled(rect, 13.0, fill);
    if selected {
        ui.painter().rect_filled(rect, 13.0, egui::Color32::TRANSPARENT);
        ui.painter()
            .rect_stroke(rect, 13.0, egui::Stroke::new(1.0_f32, BORDER_3));
    }
    ui.painter().circle_filled(
        egui::pos2(rect.left() + 13.0, rect.center().y),
        3.0,
        if selected { dot } else { TEXT_4 },
    );
    ui.painter().text(
        egui::pos2(rect.left() + 22.0, rect.center().y),
        egui::Align2::LEFT_CENTER,
        label,
        egui::FontId::proportional(12.5),
        if selected { TEXT_1 } else { TEXT_3 },
    );
    resp
}

/// Ghost button (dsh button-ghost): transparent, hover fill, hairline none.
pub fn ghost(ui: &mut egui::Ui, label: &str) -> egui::Response {
    ui.add(egui::Button::new(egui::RichText::new(label).color(TEXT_2)).frame(true))
}

/// Primary button (`--dsw-alias-button-primary-fill` = near-white in dark).
pub fn primary(ui: &mut egui::Ui, label: &str) -> egui::Response {
    ui.add(
        egui::Button::new(egui::RichText::new(label).color(INK_ON_LIGHT).strong())
            .fill(TEXT_1)
            .rounding(egui::Rounding::same(R_SM)),
    )
}

/// Section label ("Conversations") in dsh caption style.
pub fn section(ui: &mut egui::Ui, label: &str) {
    ui.label(egui::RichText::new(label).size(11.0).color(TEXT_4).strong());
}

fn ellipsize(s: &str, max_chars: usize) -> String {
    if s.chars().count() <= max_chars {
        s.to_string()
    } else {
        let cut: String = s.chars().take(max_chars.saturating_sub(1)).collect();
        format!("{cut}…")
    }
}
