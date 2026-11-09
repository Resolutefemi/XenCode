//! Conversation view — ZCode v4 timeline: centered max-w-4xl column,
//! right-aligned user bubbles (surface card, small top-right corner),
//! plain assistant markdown rows, tool rows, the running "Working" orbit,
//! composer docked at the bottom, terminal dock below.

use egui::{Align2, Color32, FontId, Pos2, Rect, Rounding, Sense, Stroke, Vec2};

use crate::icons;
use crate::session::{Msg, ToolState};
use crate::theme;
use crate::AppState;

use super::composer::{self, Mode};
use super::topbar;

fn text(p: &egui::Painter, pos: Pos2, anchor: Align2, size: f32, color: Color32, s: &str) {
    p.text(pos, anchor, s, FontId::proportional(size), color);
}

/// User input bubble — `rounded-xl rounded-tr-xs border border-border
/// bg-surface px-4 py-3 max-w-xl`, right-aligned.
fn user_bubble(ui: &mut egui::Ui, body: &str, column_w: f32) {
    let max_text_w = (column_w - 32.0).min(theme::USER_BUBBLE_MAX - 32.0);
    let galley = ui.painter().layout(
        body.to_string(),
        FontId::proportional(theme::TEXT_BASE),
        theme::FG,
        max_text_w,
    );
    let pad_x = 16.0;
    let pad_y = 12.0;
    let bubble_w = (galley.size().x + pad_x * 2.0).min(column_w);
    let bubble_h = galley.size().y + pad_y * 2.0;

    // right-aligned row
    let (row_rect, _) = ui.allocate_exact_size(Vec2::new(column_w, bubble_h), Sense::hover());
    let rect = Rect::from_min_size(
        Pos2::new(row_rect.right() - bubble_w, row_rect.top()),
        Vec2::new(bubble_w, bubble_h),
    );
    if ui.is_rect_visible(rect) {
        let p = ui.painter();
        // rounded-xl with a tiny top-right corner (rounded-tr-xs = 2px)
        let rounding = egui::Rounding {
            nw: 12.0,
            ne: 2.0,
            sw: 12.0,
            se: 12.0,
        };
        p.rect_filled(rect, rounding, theme::SURFACE);
        p.rect_stroke(rect, rounding, Stroke::new(1.0_f32, theme::BORDER));
        p.galley(
            Pos2::new(rect.left() + pad_x, rect.top() + pad_y),
            galley,
            theme::FG,
        );
    }
}

/// Tool row — `rounded-md border` collapsible card with a status badge.
fn tool_row(ui: &mut egui::Ui, name: &str, detail: &str, tool_state: ToolState, expanded: &mut bool) {
    let column_w = ui.available_width();
    let (rect, resp) = ui.allocate_exact_size(Vec2::new(column_w, 34.0), Sense::click());
    if ui.is_rect_visible(rect) {
        let p = ui.painter();
        if resp.hovered() {
            p.rect_filled(rect, Rounding::same(6.0), theme::SURFACE);
        }
        p.rect_stroke(rect, Rounding::same(6.0), Stroke::new(1.0_f32, theme::BORDER));
        icons::wrench(
            p,
            Rect::from_center_size(
                Pos2::new(rect.left() + 18.0, rect.center().y),
                Vec2::splat(14.0),
            ),
            theme::FG_SUBTLE,
        );
        text(
            p,
            Pos2::new(rect.left() + 32.0, rect.center().y),
            Align2::LEFT_CENTER,
            theme::TEXT_SM,
            theme::FG,
            name,
        );
        // detail, truncated
        let detail_x = rect.left() + 44.0 + name.len() as f32 * 6.8;
        let avail = (rect.right() - detail_x - 130.0).max(40.0);
        let chars = (avail / 6.2) as usize;
        let short: String = detail.chars().take(chars).collect();
        text(
            p,
            Pos2::new(detail_x, rect.center().y),
            Align2::LEFT_CENTER,
            theme::TEXT_CAPTION,
            theme::FG_SUBTLE,
            &short,
        );
        // status badge (rounded-full, bg-secondary)
        let badge_w = tool_state.label().len() as f32 * 6.0 + 34.0;
        let badge = Rect::from_min_size(
            Pos2::new(rect.right() - badge_w - 8.0, rect.center().y - 10.0),
            Vec2::new(badge_w, 20.0),
        );
        p.rect_filled(badge, Rounding::same(10.0), theme::SURFACE);
        let icon_c = Rect::from_center_size(
            Pos2::new(badge.left() + 12.0, badge.center().y),
            Vec2::splat(12.0),
        );
        match tool_state {
            ToolState::Completed => icons::check_circle(p, icon_c, tool_state.color()),
            ToolState::Error | ToolState::Denied => icons::x_circle(p, icon_c, tool_state.color()),
            ToolState::Pending => icons::circle(p, icon_c, tool_state.color()),
            ToolState::Running => icons::clock(p, icon_c, tool_state.color()),
        }
        text(
            p,
            Pos2::new(badge.left() + 22.0, badge.center().y),
            Align2::LEFT_CENTER,
            11.5,
            theme::FG_SUBTLE,
            tool_state.label(),
        );
    }
    if resp.clicked() {
        *expanded = !*expanded;
    }
    if *expanded && !detail.is_empty() {
        let job = egui::text::LayoutJob::simple(
            detail.to_string(),
            FontId::monospace(theme::TEXT_CAPTION),
            theme::FG_SUBTLE,
            (column_w - 40.0).max(100.0),
        );
        let galley = ui.painter().layout_job(job);
        let h = galley.size().y + 16.0;
        let (br, _) = ui.allocate_exact_size(Vec2::new(column_w, h), Sense::hover());
        let p = ui.painter();
        p.rect_filled(br, Rounding::same(6.0), theme::CARD);
        p.galley(Pos2::new(br.left() + 12.0, br.top() + 8.0), galley, theme::FG_SUBTLE);
    }
    ui.add_space(4.0);
}

/// The "Working" running indicator — 3x3 pixel orbit + label + elapsed timer
/// (port of ChatLoadingPixels: 13px grid, 950ms cycle, 110ms stagger).
fn working_row(ui: &mut egui::Ui, started_secs: f64) {
    let column_w = ui.available_width();
    let (rect, _) = ui.allocate_exact_size(Vec2::new(column_w, 22.0), Sense::hover());
    if ui.is_rect_visible(rect) {
        let p = ui.painter();
        let now = crate::session::now_secs();
        let elapsed = (now - started_secs).max(0.0);

        // pixel orbit
        let grid_origin = Pos2::new(rect.left() + 2.0, rect.center().y - 6.5);
        let cell = 3.47_f32;
        let gap = 1.3_f32;
        let orbit = [0usize, 1, 2, 5, 8, 7, 6, 3];
        let t = ((now * 1000.0) as u64) % 950;
        let step = (t * orbit.len() as u64 / 950) as usize % orbit.len();
        for idx in 0..9 {
            let gx = (idx % 3) as f32;
            let gy = (idx / 3) as f32;
            let pos = Pos2::new(
                grid_origin.x + gx * (cell + gap),
                grid_origin.y + gy * (cell + gap),
            );
            let alpha = if idx == 4 {
                0.07
            } else if orbit[step] == idx {
                0.85
            } else {
                0.15
            };
            let c = fade(theme::FG, alpha);
            p.rect_filled(
                Rect::from_min_size(pos, Vec2::splat(cell)),
                Rounding::same(1.0),
                c,
            );
        }

        text(
            p,
            Pos2::new(grid_origin.x + 13.0 + 10.0, rect.center().y),
            Align2::LEFT_CENTER,
            theme::TEXT_BASE,
            theme::FG,
            "Working",
        );

        let secs = elapsed as u64;
        let label = if secs < 60 {
            format!("{secs}s")
        } else {
            format!("{}m {:02}s", secs / 60, secs % 60)
        };
        text(
            p,
            Pos2::new(grid_origin.x + 13.0 + 10.0 + 62.0, rect.center().y),
            Align2::LEFT_CENTER,
            theme::TEXT_CAPTION,
            theme::FG_SUBTLE,
            &label,
        );
    }
    ui.ctx().request_repaint_after(std::time::Duration::from_millis(100));
}

fn fade(c: Color32, alpha: f32) -> Color32 {
    Color32::from_rgba_unmultiplied(c.r(), c.g(), c.b(), (alpha * 255.0) as u8)
}

pub fn render(ctx: &egui::Context, state: &mut AppState) {
    // terminal dock (bottom-most)
    let dock_h = if state.dock_collapsed { 36.0 } else { state.dock_height };
    egui::TopBottomPanel::bottom("chat-terminal-dock")
        .exact_height(dock_h)
        .frame(egui::Frame::none().fill(theme::BG))
        .show(ctx, |ui| {
            let (dock, _) =
                ui.allocate_exact_size(Vec2::new(ui.available_width(), dock_h), Sense::hover());
            crate::ui::terminal::render_dock(ui, state, dock);
        });

    // composer dock above the terminal dock
    egui::TopBottomPanel::bottom("chat-composer-dock")
        .frame(egui::Frame::none().fill(theme::BG))
        .show(ctx, |ui| {
            ui.add_space(10.0);
            let col_w = ui.available_width().min(theme::CONTENT_WIDTH);
            let left = (ui.available_width() - col_w) / 2.0;
            ui.allocate_new_ui(
                egui::UiBuilder::new().max_rect(Rect::from_min_size(
                    Pos2::new(ui.cursor().left() + left, ui.cursor().top()),
                    Vec2::new(col_w, ui.available_height()),
                )),
                |ui| {
                    let resp = composer::show(ui, state, Mode::Conversation);
                    if resp.sent {
                        let text = state.last_submitted.clone();
                        if let Some(task) =
                            state.open_task.and_then(|i| state.tasks.get_mut(i))
                        {
                            if !text.trim().is_empty() {
                                task.push_user_and_begin(
                                    &text,
                                    crate::session::now_secs(),
                                );
                            }
                        }
                    }
                },
            );
            ui.add_space(10.0);
        });

    // timeline
    egui::CentralPanel::default()
        .frame(egui::Frame::none().fill(theme::BG))
        .show(ctx, |ui| {
            topbar::render(ui, state);

            egui::ScrollArea::vertical()
                .stick_to_bottom(true)
                .auto_shrink([false, false])
                .show(ui, |ui| {
                    let column_w = ui.available_width().min(theme::CONTENT_WIDTH);
                    let left = (ui.available_width() - column_w) / 2.0;
                    let inner_w = column_w - 32.0; // px-4 turn padding

                    ui.allocate_new_ui(
                        egui::UiBuilder::new().max_rect(Rect::from_min_size(
                            Pos2::new(ui.cursor().left() + left, ui.cursor().top()),
                            Vec2::new(column_w, ui.available_height()),
                        )),
                        |ui| {
                            ui.add_space(8.0);
                            render_turns(ui, state, inner_w);
                        },
                    );
                    ui.add_space(20.0); // pb-5
                });
        });

    composer::model_picker_popover(ctx, state);
}

fn render_turns(ui: &mut egui::Ui, state: &mut AppState, inner_w: f32) {
    let Some(idx) = state.open_task else {
        return;
    };
    let Some(task) = state.tasks.get_mut(idx) else {
        return;
    };
    let working_since = task.working_since;
    let working = task.working;

    let mut expand_changes: Vec<(usize, bool)> = Vec::new();
    for (mi, msg) in task.messages.iter().enumerate() {
        match msg {
            Msg::User { text: body } => {
                user_bubble(ui, body, inner_w);
                ui.add_space(theme::TURN_GAP);
            }
            Msg::Assistant { text: body } => {
                crate::markdown::render(ui, body, inner_w);
                ui.add_space(theme::TURN_GAP);
            }
            Msg::Tool { name, detail, state: ts } => {
                let mut ex = task.tool_expanded.get(mi).copied().unwrap_or(false);
                tool_row(ui, name, detail, *ts, &mut ex);
                expand_changes.push((mi, ex));
                ui.add_space(theme::WORK_GAP);
            }
            Msg::Working { started_secs: _ } => {}
        }
    }
    for (mi, ex) in expand_changes {
        if task.tool_expanded.get(mi) != Some(&ex) {
            if mi < task.tool_expanded.len() {
                task.tool_expanded[mi] = ex;
            } else {
                task.tool_expanded.resize(mi + 1, false);
                task.tool_expanded[mi] = ex;
            }
        }
    }
    // running indicator after the latest turn
    if working {
        if let Some(s) = working_since {
            working_row(ui, s);
        }
    }
}
