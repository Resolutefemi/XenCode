//! ZCode `WorkspaceShellLayout.tsx` + `DesktopTopOverlay.tsx` port: the
//! desktop chrome (top overlay with logo, sidebar toggle and window
//! controls), the workspace sidebar and the main view dispatch.

use crate::theme::{self, Palette};
use crate::ui::{panels, sidebar, conversation};
use crate::{MenuKind, XenApp};
use eframe::egui;
use eframe::egui::ViewportCommand;

/// Shell entry, called once per frame from `XenApp::update`.
pub(crate) fn show(app: &mut XenApp, ctx: &egui::Context) {
    top_overlay(app, ctx);

    if app.sidebar_open {
        sidebar::show(app, ctx);
    }

    // Main view (ZCode `<main class="flex h-full min-h-0 flex-1 flex-col
    // bg-background">`): the conversation workspace or a tool page.
    match app.pane {
        crate::Pane::Chat => conversation::show(app, ctx),
        pane => panels::show(app, ctx, pane),
    }

    // Popover menus render above everything.
    render_menus(app, ctx);
}

/// Desktop top overlay: sidebar toggle + brand, window controls right.
/// ZCode: 40px strip on --color-header (#ffffff zai-light / #202020 dark)
/// with a hairline bottom border.
fn top_overlay(app: &mut XenApp, ctx: &egui::Context) {
    let p = app.palette();
    egui::TopBottomPanel::top("top_overlay")
        .exact_height(theme::geom::TOP_OVERLAY_H)
        .frame(egui::Frame::none().fill(p.header()))
        .show_separator_line(false)
        .show(ctx, |ui| {
            let rect = ui.max_rect();
            let cy = rect.center().y;

            // Sidebar toggle (panel-left glyph).
            let toggle_rect = egui::Rect::from_center_size(egui::pos2(rect.left() + 22.0, cy), egui::vec2(28.0, 28.0));
            let resp = ui.interact(toggle_rect, egui::Id::new("overlay_sidebar_toggle"), egui::Sense::click());
            if resp.hovered() {
                ui.painter().rect_filled(toggle_rect, 6.0, p.hover());
            }
            crate::ui::sidebar_toggle(
                ui.painter(),
                egui::Rect::from_center_size(toggle_rect.center(), egui::vec2(15.0, 15.0)),
                p.foreground_subtle(),
            );
            if resp.clicked() {
                app.sidebar_open = !app.sidebar_open;
            }

            // Brand: XenCode logo + workspace name. The glyph is the only
            // visual difference from ZCode (X instead of Z).
            let mut x = toggle_rect.right() + 10.0;
            if let Some(tex) = &app.logo {
                egui::Image::from_texture(egui::load::SizedTexture::new(tex.id(), egui::vec2(22.0, 22.0)))
                    .rounding(egui::Rounding::same(5.0))
                    .paint_at(ui, egui::Rect::from_center_size(egui::pos2(x + 11.0, cy), egui::vec2(22.0, 22.0)));
            }
            x += 28.0;
            let ws_name = std::path::Path::new(&app.workspace)
                .file_name()
                .map(|s| s.to_string_lossy().to_string())
                .unwrap_or_else(|| "XenCode".into());
            ui.painter().text(
                egui::pos2(x, cy),
                egui::Align2::LEFT_CENTER,
                crate::ui::ellipsize(&ws_name, 30),
                egui::FontId::proportional(theme::text::BASE),
                p.foreground(),
            );

            // Window controls right: theme toggle, minimize, maximize, close.
            let close_r = egui::Rect::from_center_size(egui::pos2(rect.right() - 30.0, cy), egui::vec2(28.0, 28.0));
            let close_resp = ui.interact(close_r, egui::Id::new("win_close"), egui::Sense::click());
            if close_resp.hovered() {
                ui.painter().rect_filled(close_r, 6.0, p.destructive());
            }
            crate::ui::close_x(
                ui.painter(),
                close_r.center(),
                if close_resp.hovered() { p.primary_foreground() } else { p.foreground_subtle() },
                12.0,
            );
            if close_resp.clicked() {
                ctx.send_viewport_cmd(ViewportCommand::Close);
            }
            let max_r = egui::Rect::from_center_size(egui::pos2(close_r.left() - 6.0 - 14.0, cy), egui::vec2(28.0, 28.0));
            let max_resp = ui.interact(max_r, egui::Id::new("win_max"), egui::Sense::click());
            if max_resp.hovered() {
                ui.painter().rect_filled(max_r, 6.0, p.hover());
            }
            ui.painter().rect_stroke(
                egui::Rect::from_center_size(max_r.center(), egui::vec2(10.0, 10.0)),
                2.0,
                egui::Stroke::new(1.5_f32, p.foreground_subtle()),
            );
            if max_resp.clicked() {
                let maximized = ui.input(|i| i.viewport().maximized.unwrap_or(false));
                ctx.send_viewport_cmd(ViewportCommand::Maximized(!maximized));
            }
            let min_r = egui::Rect::from_center_size(egui::pos2(max_r.left() - 6.0 - 14.0, cy), egui::vec2(28.0, 28.0));
            let min_resp = ui.interact(min_r, egui::Id::new("win_min"), egui::Sense::click());
            if min_resp.hovered() {
                ui.painter().rect_filled(min_r, 6.0, p.hover());
            }
            ui.painter().line_segment(
                [
                    egui::pos2(min_r.center().x - 5.0, min_r.center().y),
                    egui::pos2(min_r.center().x + 5.0, min_r.center().y),
                ],
                egui::Stroke::new(1.5_f32, p.foreground_subtle()),
            );
            if min_resp.clicked() {
                ctx.send_viewport_cmd(ViewportCommand::Minimized(true));
            }
            // Theme toggle (sun/moon) left of the window controls.
            let theme_r = egui::Rect::from_center_size(egui::pos2(min_r.left() - 8.0 - 14.0, cy), egui::vec2(28.0, 28.0));
            let theme_resp = ui.interact(theme_r, egui::Id::new("theme_toggle"), egui::Sense::click());
            if theme_resp.hovered() {
                ui.painter().rect_filled(theme_r, 6.0, p.hover());
            }
            if app.dark {
                crate::ui::sun(ui.painter(), theme_r.center(), p.foreground_subtle(), 16.0);
            } else {
                crate::ui::moon(ui.painter(), theme_r.center(), p.foreground_subtle(), 16.0);
            }
            if theme_resp.clicked() {
                app.dark = !app.dark;
                app.apply_theme(ctx);
            }

            // Hairline bottom border.
            ui.painter().line_segment([rect.left_bottom(), rect.right_bottom()], crate::ui::hairline(p));
        });
}

/// Popover menus (ZCode `bg-popover` anchored menus).
fn render_menus(app: &mut XenApp, ctx: &egui::Context) {
    let Some(menu) = app.open_menu.as_ref() else { return };
    let p = app.palette();
    let id = egui::Id::new("popover_menu");
    let anchor = menu.anchor;
    let kind = menu.kind;
    let below = egui::pos2(anchor.left_bottom().x, anchor.left_bottom().y + 4.0);
    let mut popup_rect = egui::Rect::NOTHING;
    egui::Area::new(id)
        .order(egui::Order::Foreground)
        .fixed_pos(below)
        .interactable(true)
        .show(ctx, |ui| {
            egui::Frame::default()
                .fill(p.popover())
                .rounding(egui::Rounding::same(theme::geom::RADIUS))
                .stroke(crate::ui::hairline(p))
                .inner_margin(egui::Margin::symmetric(6.0, 6.0))
                .show(ui, |ui| {
                    ui.set_min_width(anchor.width().max(200.0).min(280.0));
                    ui.set_max_width(300.0);
                    match kind {
                        MenuKind::Workspace => menu_workspace(app, ui, p),
                        MenuKind::Mode => menu_mode(app, ui, p),
                        MenuKind::Model => menu_model(app, ui, p),
                    }
                });
            popup_rect = ui.max_rect();
        });
    // Close on any click that did not land inside the popup (rows that act
    // close the menu themselves).
    if ctx.input(|i| i.pointer.any_click()) {
        let pos = ctx.pointer_hover_pos().unwrap_or(egui::Pos2::new(f32::INFINITY, f32::INFINITY));
        if !popup_rect.contains(pos) {
            app.open_menu = None;
        }
    }
}

/// One row of a popover menu; returns true when clicked.
fn menu_row(p: Palette, ui: &mut egui::Ui, label: &str, meta: Option<&str>, selected: bool, id: &str) -> bool {
    let (rect, resp) = ui.allocate_exact_size(
        egui::vec2(ui.available_width().min(288.0), 30.0),
        egui::Sense::click(),
    );
    if resp.hovered() {
        ui.painter().rect_filled(rect, 6.0, p.hover());
    } else if selected {
        ui.painter().rect_filled(rect, 6.0, p.selected());
    }
    ui.painter().text(
        egui::pos2(rect.left() + 10.0, rect.center().y),
        egui::Align2::LEFT_CENTER,
        crate::ui::ellipsize(label, 30),
        egui::FontId::proportional(theme::text::CAPTION),
        p.foreground(),
    );
    if let Some(m) = meta {
        ui.painter().text(
            egui::pos2(rect.right() - 10.0, rect.center().y),
            egui::Align2::RIGHT_CENTER,
            m,
            egui::FontId::proportional(theme::text::XS),
            p.foreground_subtlest(),
        );
    }
    let _ = id;
    resp.clicked()
}

fn menu_workspace(app: &mut XenApp, ui: &mut egui::Ui, p: Palette) {
    for ws in app.workspaces.clone() {
        let selected = ws == app.workspace.display().to_string();
        let name = std::path::Path::new(&ws)
            .file_name()
            .map(|s| s.to_string_lossy().to_string())
            .unwrap_or_else(|| ws.clone());
        if menu_row(p, ui, &name, Some(&crate::ui::ellipsize(&ws, 22)), selected, &format!("mws_{ws}")) {
            app.switch_workspace(ws);
            app.open_menu = None;
        }
    }
    ui.add_space(4.0);
    if menu_row(p, ui, "Open folder…", None, false, "mws_open") {
        app.add_workspace();
        app.open_menu = None;
    }
}

fn menu_mode(app: &mut XenApp, ui: &mut egui::Ui, p: Palette) {
    use xencode_core::permissions::PermissionMode;
    for (mode, label, hint) in [
        (PermissionMode::Confirm, "Agent", "writes need confirmation"),
        (PermissionMode::Plan, "Plan", "research and propose only"),
        (PermissionMode::Yolo, "Full Access", "no confirmations"),
        (PermissionMode::ReadOnly, "Read Only", "never writes"),
    ] {
        if menu_row(p, ui, label, Some(hint), app.mode == mode, &format!("mmode_{label}")) {
            app.set_mode(mode);
            app.open_menu = None;
        }
    }
}

fn menu_model(app: &mut XenApp, ui: &mut egui::Ui, p: Palette) {
    let providers = app.providers.clone();
    let active = app.active_provider.clone();
    if providers.is_empty() {
        ui.label(
            egui::RichText::new("No providers configured. Run: xencode config set-provider …")
                .size(theme::text::SM)
                .color(p.foreground_subtle()),
        );
        return;
    }
    for (name, model, _base) in providers {
        let selected = Some(&name) == active.as_ref();
        if menu_row(p, ui, &name, Some(&model), selected, &format!("mmodel_{name}")) {
            let mut c = xencode_core::config::load_config().unwrap_or_default();
            c.active = Some(name.clone());
            match xencode_core::config::save_config(&c) {
                Ok(()) => {
                    app.active_provider = Some(name);
                    app.worker = None; // rebind on next turn
                }
                Err(e) => app.chat_note = format!("! {e}"),
            }
            app.open_menu = None;
        }
    }
}
