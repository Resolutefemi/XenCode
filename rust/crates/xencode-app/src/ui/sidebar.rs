//! Left sidebar — faithful to ZCode main screen: New task, Automations,
//! Plugin Marketplace, Projects, Tasks, user footer.

use egui::{Color32, FontId, Pos2, Rect, Rounding, Sense, Vec2};

use crate::icons;
use crate::theme;

fn row(
    ui: &mut egui::Ui,
    height: f32,
    selected: bool,
    paint_row: impl FnOnce(&egui::Painter, Rect, bool),
) -> egui::Response {
    let avail = ui.available_width();
    let (rect, resp) = ui.allocate_exact_size(Vec2::new(avail, height), Sense::click());
    if ui.is_rect_visible(rect) {
        let p = ui.painter();
        if selected {
            p.rect_filled(rect, Rounding::same(theme::RADIUS_ROW), theme::SURFACE);
        } else if resp.hovered() {
            p.rect_filled(
                rect,
                Rounding::same(theme::RADIUS_ROW),
                theme::SURFACE_HOVER,
            );
        }
        paint_row(p, rect, resp.hovered() || selected);
    }
    resp
}

fn text(p: &egui::Painter, pos: Pos2, anchor: egui::Align2, size: f32, color: Color32, s: &str) {
    p.text(pos, anchor, s, FontId::proportional(size), color);
}

pub fn render(ctx: &egui::Context, state: &mut crate::AppState) {
    let mut clicked_task: Option<usize> = None;
    egui::SidePanel::left("sidebar")
        .resizable(false)
        .exact_width(theme::SIDEBAR_WIDTH)
        .frame(
            egui::Frame::none()
                .fill(theme::SIDEBAR)
                .inner_margin(egui::Margin::symmetric(10.0, 8.0)),
        )
        .show(ctx, |ui| {
            // ---- header: logo + name + collapse ----
            ui.horizontal(|ui| {
                let (lrect, _) = ui.allocate_exact_size(Vec2::splat(26.0), Sense::hover());
                icons::x_logo(ui.painter(), lrect, theme::WHITE);
                text(
                    ui.painter(),
                    Pos2::new(lrect.right() + 8.0, lrect.center().y),
                    egui::Align2::LEFT_CENTER,
                    13.5,
                    theme::FG,
                    "XenCode",
                );
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    let (r, _) = ui.allocate_exact_size(Vec2::splat(24.0), Sense::click());
                    icons::chevron_left(ui.painter(), r, theme::FG_SUBTLE);
                });
            });

            ui.add_space(6.0);

            // ---- New task ----
            if row(ui, 32.0, false, |p, r, _| {
                icons::plus(
                    p,
                    Rect::from_center_size(
                        Pos2::new(r.left() + 18.0, r.center().y),
                        Vec2::splat(16.0),
                    ),
                    theme::FG,
                );
                text(
                    p,
                    Pos2::new(r.left() + 34.0, r.center().y),
                    egui::Align2::LEFT_CENTER,
                    13.0,
                    theme::FG,
                    "New task",
                );
            })
            .clicked()
            {
                state.new_task();
            }

            // ---- Automations ----
            row(ui, 32.0, false, |p, r, _| {
                icons::zap(
                    p,
                    Rect::from_center_size(
                        Pos2::new(r.left() + 18.0, r.center().y),
                        Vec2::splat(16.0),
                    ),
                    theme::FG_SUBTLE,
                );
                text(
                    p,
                    Pos2::new(r.left() + 34.0, r.center().y),
                    egui::Align2::LEFT_CENTER,
                    13.0,
                    theme::FG,
                    "Automations",
                );
            })
            .clicked();

            // ---- Plugin Marketplace ----
            row(ui, 32.0, false, |p, r, _| {
                icons::sparkle(
                    p,
                    Rect::from_center_size(
                        Pos2::new(r.left() + 18.0, r.center().y),
                        Vec2::splat(16.0),
                    ),
                    theme::FG_SUBTLE,
                );
                text(
                    p,
                    Pos2::new(r.left() + 34.0, r.center().y),
                    egui::Align2::LEFT_CENTER,
                    13.0,
                    theme::FG,
                    "Plugin Marketplace",
                );
            })
            .clicked();

            ui.add_space(10.0);

            // ---- Projects ----
            text(
                ui.painter(),
                Pos2::new(ui.min_rect().left() + 6.0, ui.cursor().top() + 8.0),
                egui::Align2::LEFT_CENTER,
                11.0,
                theme::FG_SUBTLEST,
                "PROJECTS",
            );
            ui.add_space(20.0);

            for name in ["Tokenaires", "Xen Code"] {
                row(ui, 30.0, false, |p, r, hot| {
                    icons::folder(
                        p,
                        Rect::from_center_size(
                            Pos2::new(r.left() + 18.0, r.center().y),
                            Vec2::splat(16.0),
                        ),
                        if hot { theme::FG } else { theme::FG_SUBTLE },
                    );
                    text(
                        p,
                        Pos2::new(r.left() + 34.0, r.center().y),
                        egui::Align2::LEFT_CENTER,
                        13.0,
                        theme::FG,
                        name,
                    );
                    let k = Rect::from_min_max(
                        Pos2::new(r.right() - 36.0, r.top()),
                        Pos2::new(r.right() - 18.0, r.bottom()),
                    );
                    icons::kebab(
                        p,
                        k.intersect(r),
                        if hot {
                            theme::FG_SUBTLE
                        } else {
                            theme::FG_SUBTLEST
                        },
                    );
                });
            }

            let _ = row(ui, 30.0, false, |p, r, _| {
                icons::plus(
                    p,
                    Rect::from_center_size(
                        Pos2::new(r.left() + 18.0, r.center().y),
                        Vec2::splat(16.0),
                    ),
                    theme::FG_SUBTLEST,
                );
                text(
                    p,
                    Pos2::new(r.left() + 34.0, r.center().y),
                    egui::Align2::LEFT_CENTER,
                    13.0,
                    theme::FG_SUBTLE,
                    "Add project",
                );
            });

            ui.add_space(10.0);

            // ---- Tasks ----
            text(
                ui.painter(),
                Pos2::new(ui.min_rect().left() + 6.0, ui.cursor().top() + 8.0),
                egui::Align2::LEFT_CENTER,
                11.0,
                theme::FG_SUBTLEST,
                "TASKS",
            );
            ui.add_space(20.0);

            for (i, task) in state.tasks.iter().enumerate() {
                let selected = state.open_task == Some(i);
                // truncate to the row width (272 - 2*10 padding - 34 indent - 12 slack)
                let max_chars = ((theme::SIDEBAR_WIDTH - 66.0) / 6.5) as usize;
                let title: String = if task.title.chars().count() > max_chars {
                    format!("{}…", task.title.chars().take(max_chars - 1).collect::<String>())
                } else {
                    task.title.clone()
                };
                let resp = row(ui, 30.0, selected, |p, r, _| {
                    p.circle_filled(Pos2::new(r.left() + 18.0, r.center().y), 3.0, task.color);
                    text(
                        p,
                        Pos2::new(r.left() + 34.0, r.center().y),
                        egui::Align2::LEFT_CENTER,
                        13.0,
                        theme::FG,
                        &title,
                    );
                });
                if resp.clicked() {
                    clicked_task = Some(i);
                }
            }
            if let Some(i) = clicked_task {
                state.open_task(i);
            }

            ui.add_space(6.0);

            // ---- footer: user + settings ----
            ui.with_layout(egui::Layout::bottom_up(egui::Align::Min), |ui| {
                let footer_h = 40.0;
                let avail = ui.available_size();
                let (rect, _) =
                    ui.allocate_exact_size(Vec2::new(avail.x, footer_h), Sense::hover());
                let p = &ui.painter().clone();
                // avatar
                let av = Rect::from_center_size(
                    Pos2::new(rect.left() + 16.0, rect.center().y),
                    Vec2::splat(24.0),
                );
                p.circle_filled(av.center(), 12.0, theme::BRAND);
                text(
                    p,
                    av.center(),
                    egui::Align2::CENTER_CENTER,
                    9.5,
                    theme::WHITE,
                    "RF",
                );
                text(
                    p,
                    Pos2::new(av.right() + 8.0, rect.center().y),
                    egui::Align2::LEFT_CENTER,
                    12.5,
                    theme::FG,
                    "Resolute Femi",
                );
                let g = Rect::from_min_max(
                    Pos2::new(rect.right() - 56.0, rect.top()),
                    Pos2::new(rect.right() - 14.0, rect.bottom()),
                );
                let gr = Rect::from_center_size(
                    Pos2::new(g.right() - 8.0, rect.center().y),
                    Vec2::splat(22.0),
                );
                let gresp = ui.allocate_rect(gr, Sense::click());
                if gresp.hovered() {
                    p.rect_filled(gr, 6.0, theme::SURFACE_HOVER);
                }
                icons::gear(
                    p,
                    gr.shrink(4.0),
                    if gresp.hovered() {
                        theme::FG
                    } else {
                        theme::FG_SUBTLE
                    },
                );
                if gresp.clicked() {
                    state.settings.open = true;
                }
            });
        });
}
