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
            if resp.hovered() {
                theme::SURFACE_HOVER
            } else {
                theme::SURFACE
            },
        );
        p.rect_stroke(
            rect,
            Rounding::same(theme::RADIUS_CHIP),
            Stroke::new(1.0_f32, theme::BORDER),
        );
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
            if resp.hovered() {
                theme::FG
            } else {
                theme::FG_SUBTLE
            },
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
            if *on {
                theme::BRAND
            } else {
                theme::NEUTRAL_700
            },
        );
        let knob_x = if *on {
            track.right() - 9.0
        } else {
            track.left() + 9.0
        };
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
    let chip_rect: std::rc::Rc<std::cell::RefCell<Option<Rect>>> = Default::default();
    egui::CentralPanel::default()
        .frame(egui::Frame::none().fill(theme::BG))
        .show(ctx, |ui| {
            // ============ topbar ============
            let (tb, _) = ui.allocate_exact_size(
                Vec2::new(ui.available_width(), theme::TOPBAR_HEIGHT),
                Sense::hover(),
            );
            {
                let p = ui.painter();
                p.line_segment(
                    [
                        Pos2::new(tb.left(), tb.bottom()),
                        Pos2::new(tb.right(), tb.bottom()),
                    ],
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
                    Pos2::new(
                        tb.left() + 16.0 + state.project.len() as f32 * 7.2 + 10.0,
                        tb.center().y,
                    ),
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
                let (gr, _) =
                    ui.allocate_exact_size(Vec2::new(ui.available_width(), 44.0), Sense::hover());
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
            let composer = egui::Frame::none()
                .fill(theme::CARD)
                .rounding(Rounding::same(theme::RADIUS_CARD))
                .stroke(Stroke::new(1.0_f32, theme::BORDER))
                .inner_margin(egui::Margin::same(14.0));
            ui.allocate_new_ui(
                egui::UiBuilder::new().max_rect(Rect::from_min_size(
                    Pos2::new(ui.min_rect().left() + center_left, ui.cursor().top()),
                    Vec2::new(center_w, 10.0),
                )),
                |ui| {
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
                            let _ =
                                icons::icon_button(ui, 26.0, icons::paperclip, theme::FG_SUBTLE);
                            ui.with_layout(
                                egui::Layout::right_to_left(egui::Align::Center),
                                |ui| {
                                    // model picker chip — real builtin models from
                                    // config/provider/zcode-builtin.json (Z.ai Coding Plan)
                                    let chip_w = state.model.len() as f32 * 7.0 + 30.0;
                                    let (mr, mresp) = ui.allocate_exact_size(
                                        Vec2::new(chip_w, 26.0),
                                        Sense::click(),
                                    );
                                    {
                                        let p = ui.painter();
                                        p.rect_filled(
                                            mr,
                                            Rounding::same(8.0),
                                            if mresp.hovered() {
                                                theme::SURFACE_HOVER
                                            } else {
                                                Color32::TRANSPARENT
                                            },
                                        );
                                        text(
                                            p,
                                            Pos2::new(mr.left() + 8.0, mr.center().y),
                                            egui::Align2::LEFT_CENTER,
                                            12.0,
                                            theme::FG_SUBTLE,
                                            &state.model,
                                        );
                                        let cd = Rect::from_center_size(
                                            Pos2::new(mr.right() - 12.0, mr.center().y),
                                            Vec2::splat(12.0),
                                        );
                                        icons::chevron_down(p, cd, theme::FG_SUBTLE);
                                    }
                                    if mresp.clicked() {
                                        state.model_picker_open = !state.model_picker_open;
                                    }
                                    *chip_rect.borrow_mut() = Some(mr);
                                    ui.add_space(6.0);
                                    // send button
                                    let (sr, sresp) =
                                        ui.allocate_exact_size(Vec2::splat(28.0), Sense::click());
                                    {
                                        let p = ui.painter();
                                        let bg = if state.draft.is_empty() {
                                            theme::NEUTRAL_700
                                        } else if sresp.hovered() {
                                            theme::BRAND_BRIGHT
                                        } else {
                                            theme::BRAND
                                        };
                                        p.rect_filled(sr, Rounding::same(8.0), bg);
                                        icons::arrow_up(p, sr.shrink(7.0), theme::WHITE);
                                    }
                                    if sresp.clicked() && !state.draft.trim().is_empty() {
                                        state.push_task_from_draft();
                                    }
                                },
                            );
                        });
                    });
                },
            );

            ui.add_space(18.0);

            // quick action chips
            ui.allocate_new_ui(
                egui::UiBuilder::new().max_rect(Rect::from_min_size(
                    Pos2::new(ui.min_rect().left() + center_left, ui.cursor().top()),
                    Vec2::new(center_w, 32.0),
                )),
                |ui| {
                    ui.horizontal(|ui| {
                        chip(ui, "Weekly Summary", icons::sparkle);
                        ui.add_space(6.0);
                        chip(ui, "Error Fix", icons::zap);
                        ui.add_space(6.0);
                        chip(ui, "PPT Creation", icons::sparkle);
                        ui.add_space(6.0);
                        if chip(ui, "Idle-time task", icons::clock) {}
                    });
                },
            );

            // ============ terminal dock (bottom) ============
            ui.with_layout(egui::Layout::bottom_up(egui::Align::Min), |ui| {
                let dock_h = if state.dock_collapsed {
                    36.0
                } else {
                    state.dock_height
                };
                let (dock, _) =
                    ui.allocate_exact_size(Vec2::new(ui.available_width(), dock_h), Sense::hover());
                crate::ui::terminal::render_dock(ui, state, dock);
            });
        });

    // ---------- model picker popover ----------
    if state.model_picker_open {
        if let Some(chip) = *chip_rect.borrow() {
            // click-away backdrop (sits below the picker in draw order)
            egui::Area::new(egui::Id::new("model-picker-backdrop"))
                .order(egui::Order::Foreground)
                .fixed_pos(Pos2::ZERO)
                .show(ctx, |ui| {
                    let (whole, resp) = ui.allocate_exact_size(ui.available_size(), Sense::click());
                    if resp.clicked() {
                        state.model_picker_open = false;
                    }
                    let _ = whole;
                });

            egui::Area::new(egui::Id::new("model-picker"))
                .order(egui::Order::Foreground)
                .fixed_pos(Pos2::new(chip.center().x - 110.0, chip.bottom() + 4.0))
                .show(ctx, |ui| {
                    egui::Frame::none()
                        .fill(theme::CARD)
                        .rounding(Rounding::same(10.0))
                        .stroke(Stroke::new(1.0_f32, theme::BORDER))
                        .inner_margin(egui::Margin::same(4.0))
                        .show(ui, |ui| {
                            ui.set_width(224.0);
                            for model in ["GLM-5.3", "GLM-5.3-Flash"] {
                                let (r, resp) =
                                    ui.allocate_exact_size(Vec2::new(216.0, 30.0), Sense::click());
                                let p = ui.painter();
                                if resp.hovered() {
                                    p.rect_filled(r, Rounding::same(6.0), theme::SURFACE_HOVER);
                                }
                                let selected = state.model == model;
                                text(
                                    p,
                                    Pos2::new(r.left() + 10.0, r.center().y),
                                    egui::Align2::LEFT_CENTER,
                                    12.5,
                                    if selected {
                                        theme::FG
                                    } else {
                                        theme::FG_SUBTLE
                                    },
                                    model,
                                );
                                if selected {
                                    icons::check(
                                        p,
                                        Rect::from_center_size(
                                            Pos2::new(r.right() - 14.0, r.center().y),
                                            Vec2::splat(14.0),
                                        ),
                                        theme::BRAND,
                                    );
                                }
                                if resp.clicked() {
                                    state.model = model.to_string();
                                    state.model_picker_open = false;
                                }
                            }
                        });
                });
        }
    }
}
