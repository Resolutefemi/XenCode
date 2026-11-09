//! Center workspace — topbar, time-based greeting, composer, quick action
//! chips, terminal dock. Faithful to ZCode main screen layout.

use egui::{Color32, FontId, Pos2, Rect, Rounding, Sense, Stroke, Vec2};

use crate::icons;
use crate::theme;

fn text(p: &egui::Painter, pos: Pos2, anchor: egui::Align2, size: f32, color: Color32, s: &str) {
    p.text(pos, anchor, s, FontId::proportional(size), color);
}

fn chip(ui: &mut egui::Ui, label: &str, icon: impl FnOnce(&egui::Painter, Rect, Color32)) -> bool {
    let want_w = label.len() as f32 * 6.4 + 44.0;
    let (rect, resp) = ui.allocate_exact_size(Vec2::new(want_w, 30.0), Sense::click());
    if ui.is_rect_visible(rect) {
        let p = ui.painter();
        p.rect_filled(
            rect,
            Rounding::same(theme::RADIUS_CHIP),
            if resp.hovered() { theme::SURFACE_HOVER } else { theme::SURFACE },
        );
        p.rect_stroke(rect, Rounding::same(theme::RADIUS_CHIP), Stroke::new(1.0_f32, theme::BORDER));
        let ir = Rect::from_center_size(
            Pos2::new(rect.left() + 16.0, rect.center().y),
            Vec2::splat(13.0),
        );
        icon(p, ir, theme::FG_SUBTLE);
        text(
            p,
            Pos2::new(rect.left() + 28.0, rect.center().y),
            egui::Align2::LEFT_CENTER,
            12.5,
            if resp.hovered() { theme::FG } else { theme::FG_SUBTLE },
            label,
        );
    }
    resp.clicked()
}

fn toggle(ui: &mut egui::Ui, on: &mut bool, label: &str) -> egui::Response {
    let (rect, resp) = ui.allocate_exact_size(Vec2::new(72.0, 24.0), Sense::click());
    if ui.is_rect_visible(rect) {
        let p = ui.painter();
        let track = Rect::from_min_size(
            Pos2::new(rect.left() + 2.0, rect.center().y - 9.0),
            Vec2::new(30.0, 18.0),
        );
        p.rect_filled(
            track,
            Rounding::same(9.0),
            if *on { theme::BRAND } else { theme::NEUTRAL_700 },
        );
        let knob_x = if *on { track.right() - 9.0 } else { track.left() + 9.0 };
        p.circle_filled(Pos2::new(knob_x, track.center().y), 7.0, theme::WHITE);
        text(
            p,
            Pos2::new(track.right() + 8.0, rect.center().y),
            egui::Align2::LEFT_CENTER,
            12.5,
            theme::FG_SUBTLE,
            label,
        );
    }
    if resp.clicked() {
        *on = !*on;
    }
    resp
}

pub fn render(ctx: &egui::Context, state: &mut crate::AppState) {
    egui::CentralPanel::default()
        .frame(egui::Frame::none().fill(theme::BG))
        .show(ctx, |ui| {
            // ============ topbar ============
            let (tb, _) = ui
                .allocate_exact_size(Vec2::new(ui.available_width(), theme::TOPBAR_HEIGHT), Sense::hover());
            {
                let p = ui.painter();
                p.line_segment(
                    [Pos2::new(tb.left(), tb.bottom()), Pos2::new(tb.right(), tb.bottom())],
                    Stroke::new(1.0_f32, theme::BORDER),
                );
                text(
                    p,
                    Pos2::new(tb.left() + 16.0, tb.center().y),
                    egui::Align2::LEFT_CENTER,
                    13.0,
                    theme::FG,
                    &state.project,
                );
                text(
                    p,
                    Pos2::new(tb.left() + 16.0 + state.project.len() as f32 * 7.2 + 10.0, tb.center().y),
                    egui::Align2::LEFT_CENTER,
                    12.0,
                    theme::FG_SUBTLEST,
                    &state.project_path,
                );
                ui.painter();
            }

            // topbar right icons
            ui.horizontal(|ui| {
                ui.add_space(ui.available_width() - 108.0);
                let _ = icons::icon_button(ui, 28.0, icons::clock, theme::FG_SUBTLE);
                let _ = icons::icon_button(ui, 28.0, icons::sparkle, theme::FG_SUBTLE);
                let _ = icons::icon_button(ui, 28.0, icons::kebab, theme::FG_SUBTLE);
            });

            // ============ center: greeting + composer ============
            let center_w = (ui.available_width() - 48.0).min(720.0);
            let center_left = (ui.available_width() - center_w) / 2.0;

            ui.add_space(ui.available_height() * 0.13);

            // greeting
            ui.vertical_centered(|ui| {
                let (gr, _) = ui.allocate_exact_size(Vec2::new(ui.available_width(), 44.0), Sense::hover());
                text(
                    ui.painter(),
                    gr.center(),
                    egui::Align2::CENTER_CENTER,
                    25.0,
                    theme::FG,
                    &state.greeting(),
                );
            });

            ui.add_space(22.0);

            // composer card
            let mut composer = egui::Frame::none()
                .fill(theme::CARD)
                .rounding(Rounding::same(theme::RADIUS_CARD))
                .stroke(Stroke::new(1.0_f32, theme::BORDER))
                .inner_margin(egui::Margin::same(14.0));
            ui.allocate_ui_at_rect(
                Rect::from_min_size(Pos2::new(ui.min_rect().left() + center_left, ui.cursor().top()), Vec2::new(center_w, 10.0)),
                |ui| {
                    ui.set_min_size(Vec2::new(center_w, 10.0));
                    composer.show(ui, |ui| {
                        ui.set_width(ui.available_width());
                        let te = egui::TextEdit::multiline(&mut state.draft)
                            .hint_text("Ask XenCode anything, @ to add context")
                            .frame(false)
                            .desired_rows(3)
                            .desired_width(ui.available_width());
                        ui.add(te);
                        ui.add_space(10.0);
                        ui.horizontal(|ui| {
                            toggle(ui, &mut state.full_access, "Full access");
                            let _ = icons::icon_button(ui, 26.0, icons::paperclip, theme::FG_SUBTLE);
                            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                                // model picker chip
                                let (mr, mresp) = ui.allocate_exact_size(Vec2::new(104.0, 26.0), Sense::click());
                                {
                                    let p = ui.painter();
                                    p.rect_filled(mr, Rounding::same(8.0), if mresp.hovered() { theme::SURFACE_HOVER } else { Color32::TRANSPARENT });
                                    text(p, Pos2::new(mr.left() + 8.0, mr.center().y), egui::Align2::LEFT_CENTER, 12.0, theme::FG_SUBTLE, &state.model);
                                    let cd = Rect::from_center_size(Pos2::new(mr.right() - 12.0, mr.center().y), Vec2::splat(12.0));
                                    icons::chevron_down(p, cd, theme::FG_SUBTLE);
                                }
                                ui.add_space(6.0);
                                // send button
                                let (sr, sresp) = ui.allocate_exact_size(Vec2::splat(28.0), Sense::click());
                                {
                                    let p = ui.painter();
                                    let bg = if state.draft.is_empty() { theme::NEUTRAL_700 } else if sresp.hovered() { theme::BRAND_BRIGHT } else { theme::BRAND };
                                    p.rect_filled(sr, Rounding::same(8.0), bg);
                                    icons::arrow_up(p, sr.shrink(7.0), theme::WHITE);
                                }
                                if sresp.clicked() && !state.draft.trim().is_empty() {
                                    state.push_task_from_draft();
                                }
                            });
                        });
                    });
                },
            );

            ui.add_space(18.0);

            // quick action chips
            ui.allocate_ui_at_rect(
                Rect::from_min_size(Pos2::new(ui.min_rect().left() + center_left, ui.cursor().top()), Vec2::new(center_w, 32.0)),
                |ui| {
                    ui.set_min_size(Vec2::new(center_w, 32.0));
                    ui.horizontal(|ui| {
                        if chip(ui, "Weekly Summary", icons::sparkle) {}
                        ui.add_space(6.0);
                        if chip(ui, "Error Fix", icons::zap) {}
                        ui.add_space(6.0);
                        if chip(ui, "PPT Creation", icons::sparkle) {}
                        ui.add_space(6.0);
                        if chip(ui, "Idle-time task", icons::clock) {}
                    });
                },
            );

            // ============ terminal dock (bottom) ============
            ui.with_layout(egui::Layout::bottom_up(egui::Align::Min), |ui| {
                let dock_h = theme::TERMINAL_DOCK_HEIGHT;
                let avail_w = ui.available_width();
                let (dock, _) = ui.allocate_exact_size(Vec2::new(avail_w, dock_h), Sense::hover());
                let p = ui.painter();
                p.rect_filled(dock, Rounding::ZERO, theme::NEUTRAL_950);
                p.line_segment([dock.left_top(), dock.right_top()], Stroke::new(1.0_f32, theme::BORDER));
                // header
                text(
                    p,
                    Pos2::new(dock.left() + 14.0, dock.top() + 16.0),
                    egui::Align2::LEFT_CENTER,
                    10.5,
                    theme::FG_SUBTLEST,
                    "POWERSHELL",
                );
                // body lines (faithful to ZCode first-run terminal)
                let lines: Vec<(&str, Color32)> = vec![
                    ("PowerShell 7.6.2", theme::FG_SUBTLE),
                    ("Install the latest PowerShell for new features and improvements!", theme::FG_SUBTLEST),
                    ("", theme::FG_SUBTLEST),
                    ("PS C:\\Users\\ariyo\\XenCode\\rust> ", theme::FG_SUBTLE),
                ];
                let mut y = dock.top() + 40.0;
                for (s, c) in lines {
                    if !s.is_empty() {
                        text(p, Pos2::new(dock.left() + 14.0, y), egui::Align2::LEFT_CENTER, 12.5, c, s);
                    }
                    y += 22.0;
                }
                // caret
                let prompt_w = "PS C:\\Users\\ariyo\\XenCode\\rust> ".len() as f32 * 7.4;
                p.rect_filled(
                    Rect::from_min_size(Pos2::new(dock.left() + 14.0 + prompt_w, y - 14.0), Vec2::new(7.0, 14.0)),
                    Rounding::ZERO,
                    theme::FG_SUBTLE,
                );
            });
        });
}
