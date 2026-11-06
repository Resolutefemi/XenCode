//! ZCode `LexicalChatInput` + `chat-input-toolbar` port: the composer card
//! (bg-card, rounded-lg, hairline border) with the staged-attachment strip,
//! the draft surface and the control row (attach circle, mode chip, model
//! seat, send button).

use crate::theme;
use crate::ui;
use crate::{MenuKind, XenApp};
use eframe::egui;
use std::sync::atomic::Ordering;

/// Height the card will occupy (hero variant keeps a taller draft floor).
pub(crate) fn measure(app: &XenApp, hero: bool) -> f32 {
    let rows = app.chat_input.lines().count().max(1).min(14);
    let floor = if hero { 52.0 } else { 40.0 };
    let input_h = (rows as f32 * 24.0 + 4.0).max(floor);
    let attach_h = if app.pending_images.is_empty() { 0.0 } else { 68.0 };
    8.0 + attach_h + input_h + 12.0 + 32.0 + 6.0
}

/// Docked composer (wraps `card_at` at the current cursor).
pub(crate) fn card(app: &mut XenApp, ui: &mut egui::Ui, hero: bool) {
    let h = measure(app, hero);
    let w = ui.available_width();
    let rect = egui::Rect::from_min_size(ui.cursor().left_top(), egui::vec2(w, h));
    card_at(app, ui, rect, hero);
    ui.allocate_exact_size(egui::vec2(w, h + 8.0), egui::Sense::hover());
}

/// The composer card at an explicit rect (hero + docked variants).
pub(crate) fn card_at(app: &mut XenApp, ui: &mut egui::Ui, rect: egui::Rect, hero: bool) {
    let p = app.palette();
    let busy = app.worker.as_ref().map(|w| w.busy.load(Ordering::SeqCst)).unwrap_or(false);
    ui.painter().rect_filled(rect, egui::Rounding::same(theme::geom::RADIUS), p.card());
    ui.painter().rect_stroke(rect, egui::Rounding::same(theme::geom::RADIUS), ui::hairline(p));

    let mut inner = egui::Ui::new(
        ui.ctx().clone(),
        egui::LayerId::new(egui::Order::Background, egui::Id::new("composer_inner")),
        egui::Id::new("composer_ui"),
        egui::UiBuilder::new().max_rect(rect),
    );
    inner.set_clip_rect(rect);
    let mut y = rect.top() + 8.0;

    // Staged attachments strip (image previews with remove buttons).
    if !app.pending_images.is_empty() {
        let staged = app.pending_images.clone();
        let mut keep: Vec<std::path::PathBuf> = vec![];
        let mut x = rect.left() + 12.0;
        for img in &staged {
            let r = egui::Rect::from_min_size(egui::pos2(x, y), egui::vec2(56.0, 56.0));
            app.thumbnail_at(&mut inner, img, r);
            let x_btn =
                egui::Rect::from_center_size(egui::pos2(r.right() - 2.0, r.top() + 2.0), egui::vec2(16.0, 16.0));
            let resp = inner.interact(x_btn, egui::Id::new(format!("rm_{}", img.display())), egui::Sense::click());
            inner.painter().circle_filled(x_btn.center(), 7.0, p.secondary());
            inner.painter().text(
                x_btn.center(),
                egui::Align2::CENTER_CENTER,
                "×",
                egui::FontId::proportional(theme::text::SM),
                p.foreground(),
            );
            if resp.clicked() {
                // dropped: not pushed to keep
            } else {
                keep.push(img.clone());
            }
            x += 64.0;
        }
        app.pending_images = keep;
        y += 68.0;
    }

    // Draft surface.
    let rows = app.chat_input.lines().count().max(1).min(14);
    let floor = if hero { 52.0 } else { 40.0 };
    let input_h = (rows as f32 * 24.0 + 4.0).max(floor);
    let surface_h = rows as f32 * 24.0 + 4.0;
    let input_rect = egui::Rect::from_min_size(egui::pos2(rect.left(), y), egui::vec2(rect.width(), surface_h));
    let edit = inner.allocate_new_ui(egui::UiBuilder::new().max_rect(input_rect), |ui| {
        ui.add(
            egui::TextEdit::multiline(&mut app.chat_input)
                .hint_text(if hero {
                    "Describe what you want to build"
                } else {
                    "Message or run a task, / commands, @ files or sessions"
                })
                .desired_width(rect.width() - 22.0)
                .desired_rows(1)
                .frame(false)
                .margin(egui::Margin { left: 14.0, right: 8.0, top: 4.0, bottom: 0.0 })
                .font(egui::TextStyle::Body),
        )
    });
    if edit.response.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter) && !i.modifiers.shift) {
        app.send_current();
    }
    y += input_h + 12.0;

    // Control row.
    let row_rect = egui::Rect::from_min_max(
        egui::pos2(rect.left(), y),
        egui::pos2(rect.right(), rect.bottom() - 6.0),
    );
    let row_c = egui::pos2(0.0, row_rect.center().y);

    // Attach circle (28px, surface fill, paperclip glyph).
    let add_rect = egui::Rect::from_center_size(egui::pos2(rect.left() + 22.0, row_c.y), egui::vec2(28.0, 28.0));
    let add_resp = inner.interact(add_rect, egui::Id::new("composer_add"), egui::Sense::click());
    inner
        .painter()
        .circle_filled(add_rect.center(), 14.0, if add_resp.hovered() { p.surface_hover() } else { p.surface() });
    ui::paperclip(inner.painter(), add_rect.center(), p.foreground_subtle(), 15.0);
    if add_resp.clicked() {
        if let Some(paths) = rfd::FileDialog::new()
            .add_filter("Images", &["png", "jpg", "jpeg", "gif", "webp", "bmp"])
            .pick_files()
        {
            app.pending_images.extend(paths);
        }
    }

    // Mode chip (permission preset + chevron, ZCode ChatModeSwitchControl).
    let mode_label = crate::preset_label(app.mode);
    let chip_x = add_rect.right() + 10.0;
    let label_w = inner.fonts(|f| {
        f.layout_no_wrap(mode_label.to_string(), egui::FontId::proportional(theme::text::CAPTION), p.foreground_subtle())
            .size()
            .x
    });
    let chip_rect =
        egui::Rect::from_min_size(egui::pos2(chip_x, row_c.y - 14.0), egui::vec2(label_w + 34.0, 28.0));
    let chip_resp = inner.interact(chip_rect, egui::Id::new("composer_mode"), egui::Sense::click());
    if chip_resp.hovered() {
        inner.painter().rect_filled(chip_rect, 6.0, p.hover());
    }
    inner.painter().text(
        egui::pos2(chip_rect.left() + 10.0, row_c.y),
        egui::Align2::LEFT_CENTER,
        mode_label,
        egui::FontId::proportional(theme::text::CAPTION),
        p.foreground_subtle(),
    );
    ui::chevron_down(inner.painter(), egui::pos2(chip_rect.right() - 10.0, row_c.y), p.foreground_subtlest(), 12.0);
    if chip_resp.clicked() {
        app.toggle_menu(MenuKind::Mode, chip_rect);
    }

    // Model seat (model + effort + chevron) on the right (ComposerModelMenu).
    let model_label = app
        .providers
        .iter()
        .find(|(name, _, _)| Some(name) == app.active_provider.as_ref())
        .map(|(_, model, _)| model.clone())
        .unwrap_or_else(|| "No provider".into());
    let effort_w = inner.fonts(|f| {
        f.layout_no_wrap(app.effort.clone(), egui::FontId::proportional(theme::text::XS), p.foreground_subtlest())
            .size()
            .x
    });
    let model_w = inner.fonts(|f| {
        f.layout_no_wrap(model_label.clone(), egui::FontId::proportional(theme::text::CAPTION), p.foreground_subtle())
            .size()
            .x
    });
    let seat_w = model_w + 8.0 + effort_w + 22.0;
    let send_cx = rect.right() - 24.0;
    let seat_rect = egui::Rect::from_min_size(
        egui::pos2(send_cx - 16.0 - seat_w, row_c.y - 14.0),
        egui::vec2(seat_w, 28.0),
    );
    let seat_resp = inner.interact(seat_rect, egui::Id::new("composer_model"), egui::Sense::click());
    if seat_resp.hovered() {
        inner.painter().rect_filled(seat_rect, 6.0, p.hover());
    }
    inner.painter().text(
        egui::pos2(seat_rect.left(), row_c.y),
        egui::Align2::LEFT_CENTER,
        &model_label,
        egui::FontId::proportional(theme::text::CAPTION),
        p.foreground_subtle(),
    );
    inner.painter().text(
        egui::pos2(seat_rect.left() + model_w + 8.0, row_c.y),
        egui::Align2::LEFT_CENTER,
        &app.effort,
        egui::FontId::proportional(theme::text::XS),
        p.foreground_subtlest(),
    );
    ui::chevron_down(inner.painter(), egui::pos2(seat_rect.right() - 9.0, row_c.y), p.foreground_subtlest(), 12.0);
    if seat_resp.clicked() {
        app.toggle_menu(MenuKind::Model, seat_rect);
    }

    // Send button: 28px rounded-md, bg-primary with the arrow-up glyph
    // (ZCode send); muted over an empty draft or while busy.
    let send_rect = egui::Rect::from_center_size(egui::pos2(send_cx, row_c.y), egui::vec2(28.0, 28.0));
    let send_resp = inner.interact(send_rect, egui::Id::new("composer_send"), egui::Sense::click());
    let enabled = !busy && (!app.chat_input.trim().is_empty() || !app.pending_images.is_empty());
    let fill = if enabled { p.primary() } else { p.secondary() };
    if send_resp.hovered() && enabled {
        inner
            .painter()
            .rect_filled(send_rect, egui::Rounding::same(8.0), blend(p.primary(), if p.mode == crate::theme::Mode::Dark { egui::Color32::WHITE } else { egui::Color32::BLACK }, 0.15));
    } else {
        inner.painter().rect_filled(send_rect, egui::Rounding::same(8.0), fill);
    }
    if busy {
        let c = send_rect.center();
        inner
            .painter()
            .rect_filled(egui::Rect::from_center_size(c, egui::vec2(10.0, 10.0)), 2.0, p.primary_foreground());
    } else {
        ui::arrow_up(inner.painter(), send_rect.center(), p.primary_foreground(), 14.0);
    }
    if enabled && send_resp.clicked() {
        app.send_current();
    }
}

/// Alpha blend toward `toward` (send-button hover tint).
fn blend(c: egui::Color32, toward: egui::Color32, k: f32) -> egui::Color32 {
    let e = |a: u8, b: u8| (a as f32 * (1.0 - k) + b as f32 * k) as u8;
    egui::Color32::from_rgba_unmultiplied(e(c.r(), toward.r()), e(c.g(), toward.g()), e(c.b(), toward.b()), c.a())
}
