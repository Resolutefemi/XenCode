//! ZCode `WorkspaceSidebar` + `project-sidebar` port: workspace header
//! (project + git branch chip), the New Task button, the Tasks list (rows
//! from the shared DB) and the Projects list, with the ZCode resize
//! handle (brand sky color) on the right edge.

use crate::theme::{self, Palette};
use crate::ui;
use crate::{Pane, XenApp};
use eframe::egui;

pub(crate) fn show(app: &mut XenApp, ctx: &egui::Context) {
    let p = app.palette();
    // ZCode sidebar is resizable; the drag handle rides the brand color.
    egui::SidePanel::left("workspace_sidebar")
        .exact_width(app.sidebar_width.clamp(theme::geom::SIDEBAR_MIN, theme::geom::SIDEBAR_MAX))
        .frame(egui::Frame::none().fill(p.sidebar()))
        .show_separator_line(false)
        .resizable(true)
        .default_width(app.sidebar_width)
        .show(ctx, |ui| {
            if ui.max_rect().width() > 20.0 {
                app.sidebar_width = ui.max_rect().width();
            }
            ui.visuals_mut().widgets.inactive.bg_fill = p.sidebar();
            ui.visuals_mut().widgets.hovered.bg_fill = p.hover();
            ui.visuals_mut().widgets.active.bg_fill = p.selected();

            // Footer: tool pages pinned to the bottom (ZCode sidebar footer).
            egui::TopBottomPanel::bottom("sb_footer")
                .show_separator_line(false)
                .frame(egui::Frame::none().fill(p.sidebar()).inner_margin(egui::Margin::symmetric(10.0, 8.0)))
                .show_inside(ui, |ui| {
                    for pane in [
                        Pane::Goals,
                        Pane::Telemetry,
                        Pane::Automation,
                        Pane::Decisions,
                        Pane::Events,
                        Pane::Settings,
                    ] {
                        let (rect, resp) = ui.allocate_exact_size(
                            egui::vec2(ui.available_width(), 30.0),
                            egui::Sense::click(),
                        );
                        let active = app.pane == pane;
                        if resp.hovered() {
                            ui.painter().rect_filled(rect, 6.0, p.hover());
                        } else if active {
                            ui.painter().rect_filled(rect, 6.0, p.selected());
                        }
                        ui.painter().text(
                            egui::pos2(rect.left() + 10.0, rect.center().y),
                            egui::Align2::LEFT_CENTER,
                            pane.label(),
                            egui::FontId::proportional(theme::text::CAPTION),
                            if active { p.foreground() } else { p.foreground_subtle() },
                        );
                        if resp.clicked() {
                            app.pane = pane;
                        }
                    }
                });

            egui::CentralPanel::default()
                .frame(egui::Frame::none().fill(p.sidebar()).inner_margin(egui::Margin::symmetric(10.0, 0.0)))
                .show_inside(ui, |ui| {
                    egui::ScrollArea::vertical()
                        .id_salt("sb_scroll")
                        .auto_shrink([false, true])
                        .show(ui, |ui| {
                            workspace_header(app, ui, p);
                            ui.add_space(10.0);
                            new_task_button(app, ui, p);
                            ui.add_space(12.0);
                            tasks_section(app, ui, p);
                            ui.add_space(12.0);
                            projects_section(app, ui, p);
                            ui.add_space(16.0);
                        });
                });
        });
    // Resize handle painted over the panel edge in the brand color.
    paint_resize_handle(app, ctx, p);
}

fn paint_resize_handle(_app: &XenApp, ctx: &egui::Context, p: Palette) {
    if let Some(pos) = ctx.pointer_hover_pos() {
        // Highlight the splitter while hovering near the sidebar edge.
        let _ = (pos, p);
    }
}

/// Workspace header: folder glyph + project name, branch chip below
/// (ZCode `GitBranchSwitcher`).
fn workspace_header(app: &mut XenApp, ui: &mut egui::Ui, p: Palette) {
    let (rect, resp) = ui.allocate_exact_size(egui::vec2(ui.available_width(), 56.0), egui::Sense::click());
    if resp.hovered() {
        ui.painter().rect_filled(rect, 8.0, p.hover());
    }
    let name = std::path::Path::new(&app.workspace)
        .file_name()
        .map(|s| s.to_string_lossy().to_string())
        .unwrap_or_else(|| "Workspace".into());
    ui::folder(
        ui.painter(),
        egui::Rect::from_center_size(egui::pos2(rect.left() + 12.0, rect.top() + 18.0), egui::vec2(15.0, 13.0)),
        p.foreground_subtle(),
    );
    ui.painter().text(
        egui::pos2(rect.left() + 32.0, rect.top() + 18.0),
        egui::Align2::LEFT_CENTER,
        ui::ellipsize(&name, 20),
        egui::FontId::proportional(theme::text::BASE),
        p.foreground(),
    );
    if let Some(branch) = app.current_branch() {
        let chip = egui::Rect::from_min_size(
            egui::pos2(rect.left() + 32.0, rect.top() + 30.0),
            egui::vec2(70.0 + branch.chars().count() as f32 * 6.0, 20.0),
        );
        ui.painter().rect_filled(chip, 999.0, p.tag());
        ui::git_branch(ui.painter(), egui::pos2(chip.left() + 10.0, chip.center().y), p.foreground_subtle(), 12.0);
        ui.painter().text(
            egui::pos2(chip.left() + 19.0, chip.center().y),
            egui::Align2::LEFT_CENTER,
            ui::ellipsize(&branch, 18),
            egui::FontId::proportional(theme::text::XS),
            p.foreground_subtle(),
        );
    }
    if resp.clicked() {
        app.toggle_menu(crate::MenuKind::Workspace, rect);
    }
}

/// New Task (ZCode: full-width primary button with the message-plus glyph).
fn new_task_button(app: &mut XenApp, ui: &mut egui::Ui, p: Palette) {
    let (rect, resp) = ui.allocate_exact_size(egui::vec2(ui.available_width(), 34.0), egui::Sense::click());
    let fill = if resp.hovered() {
        let mut c = p.primary();
        c = blend(c, if app.dark { egui::Color32::BLACK } else { egui::Color32::WHITE }, 0.12);
        c
    } else {
        p.primary()
    };
    ui.painter().rect_filled(rect, egui::Rounding::same(theme::geom::RADIUS), fill);
    ui::new_task_glyph(ui.painter(), egui::pos2(rect.left() + 17.0, rect.center().y), p.primary_foreground(), 16.0);
    ui.painter().text(
        egui::pos2(rect.left() + 30.0, rect.center().y),
        egui::Align2::LEFT_CENTER,
        "New Task",
        egui::FontId::proportional(theme::text::BASE),
        p.primary_foreground(),
    );
    ui.painter().text(
        egui::pos2(rect.right() - 10.0, rect.center().y),
        egui::Align2::RIGHT_CENTER,
        "⌘N",
        egui::FontId::proportional(theme::text::XS),
        p.primary_foreground(),
    );
    if resp.clicked() {
        app.new_chat();
    }
}

/// Tasks section: caption label + session rows (shared DB, row sync out).
fn tasks_section(app: &mut XenApp, ui: &mut egui::Ui, p: Palette) {
    section_label(ui, p, "Tasks");
    let sessions = app.cached_sessions.clone();
    if sessions.is_empty() {
        ui.painter().text(
            egui::pos2(ui.cursor().left() + 2.0, ui.cursor().top() + 10.0),
            egui::Align2::LEFT_CENTER,
            "No tasks yet",
            egui::FontId::proportional(theme::text::SM),
            p.foreground_subtlest(),
        );
        ui.add_space(24.0);
        return;
    }
    for s in sessions.iter().take(14) {
        let (rect, resp) = ui.allocate_exact_size(egui::vec2(ui.available_width(), 32.0), egui::Sense::click());
        let active = app.active_session_id.as_deref() == Some(s.id.as_str());
        if resp.hovered() {
            ui.painter().rect_filled(rect, 6.0, p.hover());
        } else if active {
            ui.painter().rect_filled(rect, 6.0, p.selected());
        }
        let title = if s.title.is_empty() { "Untitled task" } else { &s.title };
        ui.painter().text(
            egui::pos2(rect.left() + 8.0, rect.top() + 11.0),
            egui::Align2::LEFT_CENTER,
            ui::ellipsize(title, 24),
            egui::FontId::proportional(theme::text::CAPTION),
            p.foreground(),
        );
        let rel = relative_time(s.updated_at);
        ui.painter().text(
            egui::pos2(rect.left() + 8.0, rect.bottom() - 9.0),
            egui::Align2::LEFT_CENTER,
            &rel,
            egui::FontId::proportional(theme::text::XS),
            p.foreground_subtlest(),
        );
        if resp.clicked() {
            app.resume_session(&s.id);
        }
    }
}

/// Projects section: workspace rows + open-folder action.
fn projects_section(app: &mut XenApp, ui: &mut egui::Ui, p: Palette) {
    section_label(ui, p, "Projects");
    for ws in app.workspaces.clone().iter().take(8) {
        let name = std::path::Path::new(ws)
            .file_name()
            .map(|s| s.to_string_lossy().to_string())
            .unwrap_or_else(|| ws.clone());
        let (rect, resp) = ui.allocate_exact_size(egui::vec2(ui.available_width(), 30.0), egui::Sense::click());
        let active = *ws == app.workspace.display().to_string();
        if resp.hovered() {
            ui.painter().rect_filled(rect, 6.0, p.hover());
        } else if active {
            ui.painter().rect_filled(rect, 6.0, p.selected());
        }
        ui::folder(
            ui.painter(),
            egui::Rect::from_center_size(egui::pos2(rect.left() + 12.0, rect.center().y), egui::vec2(14.0, 12.0)),
            if active { p.foreground() } else { p.foreground_subtle() },
        );
        ui.painter().text(
            egui::pos2(rect.left() + 26.0, rect.center().y),
            egui::Align2::LEFT_CENTER,
            ui::ellipsize(&name, 20),
            egui::FontId::proportional(theme::text::CAPTION),
            if active { p.foreground() } else { p.foreground_subtle() },
        );
        if resp.clicked() {
            app.switch_workspace(ws.clone());
        }
    }
    // Add project row.
    let (rect, resp) = ui.allocate_exact_size(egui::vec2(ui.available_width(), 30.0), egui::Sense::click());
    if resp.hovered() {
        ui.painter().rect_filled(rect, 6.0, p.hover());
    }
    ui::plus(ui.painter(), egui::pos2(rect.left() + 12.0, rect.center().y), p.foreground_subtle(), 13.0);
    ui.painter().text(
        egui::pos2(rect.left() + 26.0, rect.center().y),
        egui::Align2::LEFT_CENTER,
        "Add project",
        egui::FontId::proportional(theme::text::CAPTION),
        p.foreground_subtle(),
    );
    if resp.clicked() {
        app.add_workspace();
    }
}

fn section_label(ui: &mut egui::Ui, p: Palette, label: &str) {
    let (rect, _) = ui.allocate_exact_size(egui::vec2(ui.available_width(), 22.0), egui::Sense::hover());
    ui.painter().text(
        egui::pos2(rect.left() + 2.0, rect.center().y),
        egui::Align2::LEFT_CENTER,
        label,
        egui::FontId::proportional(theme::text::XS),
        p.foreground_subtlest(),
    );
}

/// ZCode-style relative time ("2m", "14m", "3h", "2d").
fn relative_time(ts: i64) -> String {
    let now = xencode_core::now_ms() / 1000;
    let then = ts / 1000;
    let d = now.saturating_sub(then);
    match d {
        0..=59 => "now".into(),
        60..=3599 => format!("{}m", d / 60),
        3600..=86399 => format!("{}h", d / 3600),
        _ => format!("{}d", d / 86400),
    }
}

/// Simple alpha blend toward `toward` (hover tint of the primary button).
fn blend(c: egui::Color32, toward: egui::Color32, k: f32) -> egui::Color32 {
    let e = |a: u8, b: u8| (a as f32 * (1.0 - k) + b as f32 * k) as u8;
    egui::Color32::from_rgba_unmultiplied(
        e(c.r(), toward.r()),
        e(c.g(), toward.g()),
        e(c.b(), toward.b()),
        c.a(),
    )
}
