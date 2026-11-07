//! XenCode desktop theme tokens.
//!
//! The palette mirrors the Compose Desktop `XenPalette` one-to-one so both
//! front ends stay in brand sync: near-black shell, lifted surfaces, white
//! text and the signature lightning green.

use egui::{Color32, Rounding, Stroke, Style, Visuals};

/// Brand palette (token-precise copy of `desktop/src/main/kotlin/com/xencode/desktop/Theme.kt`).
/// Complete token table; not every token is referenced yet, keep them all for fidelity.
#[allow(dead_code)]
pub mod palette {
    use egui::Color32;

    pub const BACKGROUND: Color32 = Color32::from_rgb(0x0A, 0x0C, 0x0E);
    pub const SURFACE: Color32 = Color32::from_rgb(0x10, 0x14, 0x18);
    pub const SURFACE_VARIANT: Color32 = Color32::from_rgb(0x1A, 0x20, 0x26);
    pub const PRIMARY: Color32 = Color32::from_rgb(0x6E, 0xE7, 0xA0);
    pub const ON_PRIMARY: Color32 = Color32::from_rgb(0x06, 0x13, 0x0C);
    pub const PRIMARY_CONTAINER: Color32 = Color32::from_rgb(0x17, 0x36, 0x22);
    pub const ON_PRIMARY_CONTAINER: Color32 = Color32::from_rgb(0xC9, 0xF6, 0xDC);
    pub const SECONDARY: Color32 = Color32::from_rgb(0xE6, 0xEB, 0xF0);
    pub const ON_SECONDARY: Color32 = Color32::from_rgb(0x14, 0x18, 0x1C);
    pub const ON_BACKGROUND: Color32 = Color32::from_rgb(0xE9, 0xED, 0xF1);
    pub const ON_SURFACE_VARIANT: Color32 = Color32::from_rgb(0x9A, 0xA7, 0xB0);
    pub const OUTLINE: Color32 = Color32::from_rgb(0x2A, 0x32, 0x3A);
    pub const OUTLINE_VARIANT: Color32 = Color32::from_rgb(0x20, 0x26, 0x2C);
    pub const ERROR: Color32 = Color32::from_rgb(0xFF, 0x7A, 0x7A);
    pub const ON_ERROR: Color32 = Color32::from_rgb(0x2A, 0x08, 0x08);
    pub const ERROR_CONTAINER: Color32 = Color32::from_rgb(0x3A, 0x14, 0x14);
    pub const ON_ERROR_CONTAINER: Color32 = Color32::from_rgb(0xFF, 0xD2, 0xD2);

    // Extensions beyond the Compose palette (desktop-only tokens).
    pub const LINK: Color32 = Color32::from_rgb(0x7A, 0xB8, 0xFF);
    pub const CODE_BG: Color32 = Color32::from_rgb(0x0D, 0x11, 0x15);
    pub const CODE_TEXT: Color32 = Color32::from_rgb(0xD7, 0xE3, 0xDA);
    pub const INLINE_CODE_BG: Color32 = Color32::from_rgb(0x18, 0x1F, 0x24);
    pub const WARNING: Color32 = Color32::from_rgb(0xFF, 0xD2, 0x7A);
    pub const DIFF_ADD: Color32 = Color32::from_rgb(0x56, 0xD3, 0x8A);
    pub const DIFF_DEL: Color32 = Color32::from_rgb(0xFF, 0x8A, 0x8A);
    pub const REASONING: Color32 = Color32::from_rgb(0xB9, 0xA7, 0xE8);
    pub const BUBBLE_USER: Color32 = Color32::from_rgb(0x14, 0x2B, 0x1E);
    pub const STREAMING_CURSOR: Color32 = PRIMARY;
}

/// Font families registered by [`crate::fonts::install`].
pub mod families {
    use egui::FontFamily;

    /// Bold companion family ("Noto Sans SC Bold" + DejaVu Sans Bold).
    pub fn bold() -> FontFamily {
        FontFamily::Name("XenBold".into())
    }
}

/// Apply the XenCode dark style to a context.
pub fn apply(ctx: &egui::Context) {
    let mut style = Style::default();
    let mut v = Visuals::dark();

    v.panel_fill = palette::SURFACE;
    v.window_fill = palette::SURFACE;
    v.extreme_bg_color = palette::BACKGROUND;
    v.faint_bg_color = palette::SURFACE_VARIANT;
    v.code_bg_color = palette::CODE_BG;
    v.hyperlink_color = palette::LINK;
    v.selection.bg_fill = palette::PRIMARY;
    v.selection.stroke = Stroke::new(1.0_f32, palette::ON_PRIMARY);

    v.widgets.noninteractive.bg_fill = palette::SURFACE;
    v.widgets.noninteractive.fg_stroke = Stroke::new(1.0_f32, palette::ON_SURFACE_VARIANT);
    v.widgets.noninteractive.bg_stroke = Stroke::new(1.0_f32, palette::OUTLINE);
    v.widgets.noninteractive.rounding = Rounding::same(6.0);

    v.widgets.inactive.bg_fill = palette::SURFACE_VARIANT;
    v.widgets.inactive.fg_stroke = Stroke::new(1.0_f32, palette::ON_BACKGROUND);
    v.widgets.inactive.bg_stroke = Stroke::new(1.0_f32, palette::OUTLINE_VARIANT);
    v.widgets.inactive.rounding = Rounding::same(6.0);

    v.widgets.hovered.bg_fill = palette::SURFACE_VARIANT;
    v.widgets.hovered.fg_stroke = Stroke::new(1.2_f32, palette::PRIMARY);
    v.widgets.hovered.bg_stroke = Stroke::new(1.0_f32, palette::OUTLINE);
    v.widgets.hovered.rounding = Rounding::same(6.0);

    v.widgets.active.bg_fill = palette::PRIMARY_CONTAINER;
    v.widgets.active.fg_stroke = Stroke::new(1.2_f32, palette::ON_PRIMARY_CONTAINER);
    v.widgets.active.bg_stroke = Stroke::new(1.0_f32, palette::PRIMARY);
    v.widgets.active.rounding = Rounding::same(6.0);

    v.widgets.open.bg_fill = palette::SURFACE_VARIANT;
    v.widgets.open.fg_stroke = Stroke::new(1.0_f32, palette::ON_BACKGROUND);
    v.widgets.open.rounding = Rounding::same(6.0);

    style.visuals = v;
    style.spacing.item_spacing = egui::vec2(8.0, 8.0);
    style.spacing.button_padding = egui::vec2(10.0, 5.0);
    ctx.set_style(style);
}

/// Helper: a chip (small pill of text) used across panes.
pub fn chip(ui: &mut egui::Ui, text: &str, fg: Color32, bg: Color32) {
    egui::Frame::default()
        .fill(bg)
        .rounding(Rounding::same(20.0))
        .inner_margin(egui::Margin::symmetric(8.0, 3.0))
        .show(ui, |ui| {
            ui.style_mut().override_font_id = Some(egui::FontId::proportional(11.0));
            ui.colored_label(fg, text);
        });
}

/// Helper: section header inside a pane.
pub fn section_header(ui: &mut egui::Ui, text: &str) {
    ui.add_space(4.0);
    ui.style_mut().override_font_id = Some(egui::FontId::proportional(11.0));
    ui.colored_label(palette::ON_SURFACE_VARIANT, text.to_uppercase());
    ui.add_space(2.0);
}
