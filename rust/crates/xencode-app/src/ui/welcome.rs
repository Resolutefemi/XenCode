//! Draft screen — ZCode centered empty layout: time-based greeting,
//! composer (max-w-2xl), suggested prompts, terminal dock.

use egui::{Align2, Color32, FontId, Pos2, Rect, Rounding, Sense, Vec2};

use crate::icons;
use crate::theme;
use crate::AppState;

use super::composer::{self, Mode};
use super::topbar;

fn text(p: &egui::Painter, pos: Pos2, anchor: Align2, size: f32, color: Color32, s: &str) {
    p.text(pos, anchor, s, FontId::proportional(size), color);
}

/// Suggested prompt row: icon + label, hover surface, fills the draft.
fn suggested_row(
    ui: &mut egui::Ui,
    width: f32,
    label: &str,
    icon: impl FnOnce(&egui::Painter, Rect, Color32),
) -> bool {
    let (rect, resp) = ui.allocate_exact_size(Vec2::new(width, 36.0), Sense::click());
    if ui.is_rect_visible(rect) {
        let p = ui.painter();
        if resp.hovered() {
            p.rect_filled(rect, Rounding::same(theme::RADIUS_ROW), theme::SURFACE);
        }
        icon(
            p,
            Rect::from_center_size(
                Pos2::new(rect.left() + 18.0, rect.center().y),
                Vec2::splat(16.0),
            ),
            theme::FG_SUBTLE,
        );
        text(
            p,
            Pos2::new(rect.left() + 34.0, rect.center().y),
            Align2::LEFT_CENTER,
            theme::TEXT_BASE,
            if resp.hovered() { theme::FG } else { theme::FG_SUBTLE },
            label,
        );
    }
    resp.clicked()
}

pub fn render(ctx: &egui::Context, state: &mut AppState) {
    let mut sent = false;

    egui::CentralPanel::default()
        .frame(egui::Frame::none().fill(theme::BG))
        .show(ctx, |ui| {
            // ============ topbar ============
            topbar::render(ui, state);

            // ============ centered draft column ============
            let avail = ui.available_rect_before_wrap();
            let dock_h = if state.dock_collapsed { 36.0 } else { state.dock_height };
            let center_h = avail.height() - dock_h;

            let col_w = avail.width().min(theme::DRAFT_WIDTH);
            let x0 = avail.left() + (avail.width() - col_w) / 2.0;

            // rough content height for vertical centering (gap-4 rhythm)
            let est_h = 40.0 + 16.0 + 210.0 + 16.0 + 80.0;
            let y0 = avail.top() + ((center_h - est_h) / 2.0).max(16.0);

            ui.allocate_new_ui(
                egui::UiBuilder::new().max_rect(Rect::from_min_size(
                    Pos2::new(x0, y0),
                    Vec2::new(col_w, center_h),
                )),
                |ui| {
                    // greeting (clamp 20-30px per ZCode; 26 at desktop widths)
                    let (gr, _) = ui.allocate_exact_size(Vec2::new(col_w, 40.0), Sense::hover());
                    text(
                        ui.painter(),
                        Pos2::new(gr.center().x, gr.center().y),
                        Align2::CENTER_CENTER,
                        26.0,
                        theme::FG,
                        state.greeting(),
                    );
                    ui.add_space(16.0);

                    // composer (draft surface: workspace header + input card)
                    let r = composer::show(ui, state, Mode::Draft);
                    if r.sent {
                        sent = true;
                    }

                    ui.add_space(16.0);

                    // suggested prompts (exact en-US strings)
                    if suggested_row(
                        ui,
                        col_w,
                        "Review commits from the last 7 days",
                        icons::refresh,
                    ) {
                        state.draft = "Review Git commits from the last 7 days in this workspace, summarize the main changes, and identify potential risks.".to_string();
                    }
                    ui.add_space(4.0);
                    if suggested_row(ui, col_w, "Create a PDF", icons::file_text) {
                        state.draft =
                            "Create a PDF document based on the contents of the current workspace."
                                .to_string();
                    }
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

    if sent {
        state.submit_draft();
    }
}
