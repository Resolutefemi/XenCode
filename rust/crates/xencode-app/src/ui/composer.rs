//! The composer — shared by the draft (welcome) and conversation views.
//!
//! Port of ZCode's ChatPromptEditor stacked shell:
//! `rounded-2xl border-[0.5px] border-input-border bg-input p-3`, focus-within
//! switches the border to brand and the fill to input-focused (neutral-950);
//! editor on top, toolbar row below (leading: action menu + mode selector,
//! trailing: round brand send button).
//!
//! Draft mode additionally wraps it in the `rounded-2xl bg-surface` surface
//! with the workspace context header (workspace menu + Git branch).

use egui::{Color32, FontId, Pos2, Rect, Rounding, Sense, Stroke, Vec2};

use crate::icons;
use crate::theme::{self, blend};

pub const PLACEHOLDER_DRAFT: &str =
    "Ask XenCode anything, @ to add context, / for commands or capabilities";
pub const PLACEHOLDER_FOLLOW_UP: &str = "Ask for follow-up changes";

#[derive(Clone, Copy, PartialEq)]
pub enum Mode {
    Draft,
    Conversation,
}

pub struct ComposerResponse {
    pub sent: bool,
}

fn text(
    p: &egui::Painter,
    pos: Pos2,
    anchor: egui::Align2,
    size: f32,
    color: Color32,
    s: &str,
) {
    p.text(pos, anchor, s, FontId::proportional(size), color);
}

fn toggle(ui: &mut egui::Ui, on: &mut bool, label: &str) -> egui::Response {
    let (rect, resp) = ui.allocate_exact_size(Vec2::new(label.len() as f32 * 6.2 + 46.0, 24.0), Sense::click());
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
            theme::TEXT_CAPTION,
            theme::FG_SUBTLE,
            label,
        );
    }
    if resp.clicked() {
        *on = !*on;
    }
    resp
}

/// Render the composer. `width` is the full column width available.
pub fn show(ui: &mut egui::Ui, state: &mut crate::AppState, mode: Mode) -> ComposerResponse {
    let mut sent = false;

    // Enter (without Shift) submits when the editor had focus.
    let enter_now = ui.input(|i| i.key_pressed(egui::Key::Enter) && !i.modifiers.shift);
    let sendable = !state.draft.trim().is_empty();
    let want_send = enter_now && state.composer_focused && sendable;
    if want_send {
        // swallow the key so the multiline editor does not insert a newline
        ui.input_mut(|i| i.consume_key(egui::Modifiers::NONE, egui::Key::Enter));
    }

    // Draft mode: outer surface + workspace context header.
    let mut body = |ui: &mut egui::Ui, state: &mut crate::AppState| {
        let focused = state.composer_focused;
        let fill = if focused { theme::NEUTRAL_950 } else { theme::NEUTRAL_800 };
        let stroke_color = if focused { theme::BRAND } else { theme::BORDER };
        let stroke_w = if focused { 1.0_f32 } else { 0.5 };

        let frame = egui::Frame::none()
            .fill(fill)
            .rounding(Rounding::same(theme::RADIUS_CARD))
            .stroke(Stroke::new(stroke_w, stroke_color))
            .inner_margin(egui::Margin::same(12.0));

        frame.show(ui, |ui| {
            ui.set_width(ui.available_width());

            // editor
            let te = egui::TextEdit::multiline(&mut state.draft)
                .hint_text(
                    if mode == Mode::Draft {
                        PLACEHOLDER_DRAFT
                    } else {
                        PLACEHOLDER_FOLLOW_UP
                    },
                )
                .frame(false)
                .desired_rows(1)
                .desired_width(ui.available_width());
            let resp = ui.add(te);
            state.composer_focused = resp.has_focus();

            ui.add_space(10.0);

            // toolbar row
            ui.horizontal(|ui| {
                // leading: action menu (+)
                let _ = icons::icon_button(ui, 28.0, icons::plus, theme::FG_SUBTLE);
                ui.add_space(2.0);

                // model chip
                let chip_w = state.model.len() as f32 * 6.6 + 34.0;
                let (mr, mresp) = ui.allocate_exact_size(Vec2::new(chip_w, 26.0), Sense::click());
                {
                    let p = ui.painter();
                    if mresp.hovered() {
                        p.rect_filled(mr, Rounding::same(8.0), theme::SURFACE_HOVER);
                    }
                    text(
                        p,
                        Pos2::new(mr.left() + 8.0, mr.center().y),
                        egui::Align2::LEFT_CENTER,
                        theme::TEXT_CAPTION,
                        theme::FG_SUBTLE,
                        &state.model,
                    );
                    icons::chevron_down(
                        p,
                        Rect::from_center_size(
                            Pos2::new(mr.right() - 12.0, mr.center().y),
                            Vec2::splat(12.0),
                        ),
                        theme::FG_SUBTLE,
                    );
                }
                if mresp.clicked() {
                    state.model_picker_open = !state.model_picker_open;
                }
                state.model_chip_rect = Some(mr);

                ui.add_space(6.0);
                let _ = toggle(ui, &mut state.full_access, "Full access");

                // trailing: send button (rounded-full brand)
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    let (sr, sresp) = ui.allocate_exact_size(Vec2::splat(28.0), Sense::click());
                    {
                        let p = ui.painter();
                        let bg = if state.draft.trim().is_empty() {
                            // 40% brand over the input fill (deterministic blend;
                            // translucent fills render unreliable in epaint)
                            blend(theme::BRAND, theme::NEUTRAL_800, 0.4)
                        } else if sresp.hovered() {
                            theme::BRAND_BRIGHT
                        } else {
                            theme::BRAND
                        };
                        p.rect_filled(sr, Rounding::same(14.0), bg);
                        icons::arrow_up(p, sr.shrink(7.0), theme::WHITE);
                    }
                    if sresp.clicked() && !state.draft.trim().is_empty() {
                        sent = true;
                    }
                });
            });
        });
    };

    match mode {
        Mode::Draft => {
            // outer surface: rounded-2xl bg-surface shadow
            let surface = egui::Frame::none()
                .fill(theme::SURFACE)
                .rounding(Rounding::same(theme::RADIUS_CARD))
                .inner_margin(egui::Margin::same(6.0));
            surface.show(ui, |ui| {
                ui.set_width(ui.available_width());
                // workspace context header: project + git branch
                ui.horizontal(|ui| {
                    let (hr, _) =
                        ui.allocate_exact_size(Vec2::new(ui.available_width(), 26.0), Sense::hover());
                    let p = ui.painter();
                    icons::folder(
                        p,
                        Rect::from_center_size(
                            Pos2::new(hr.left() + 14.0, hr.center().y),
                            Vec2::splat(14.0),
                        ),
                        theme::FG_SUBTLE,
                    );
                    text(
                        p,
                        Pos2::new(hr.left() + 28.0, hr.center().y),
                        egui::Align2::LEFT_CENTER,
                        theme::TEXT_CAPTION,
                        theme::FG_SUBTLE,
                        &state.project,
                    );
                    if let Some(branch) = state.git_branch.clone() {
                        let branch_x = hr.left() + 36.0 + state.project.len() as f32 * 6.2;
                        icons::git_branch(
                            p,
                            Rect::from_center_size(
                                Pos2::new(branch_x, hr.center().y),
                                Vec2::splat(12.0),
                            ),
                            theme::FG_SUBTLEST,
                        );
                        text(
                            p,
                            Pos2::new(branch_x + 16.0, hr.center().y),
                            egui::Align2::LEFT_CENTER,
                            theme::TEXT_CAPTION,
                            theme::FG_SUBTLEST,
                            &branch,
                        );
                    }
                });
                body(ui, state);
            });
        }
        Mode::Conversation => {
            body(ui, state);
        }
    }

    if want_send {
        sent = true;
    }

    if sent {
        state.last_submitted = state.draft.clone();
        state.draft.clear();
    }
    ComposerResponse { sent }
}

/// Model picker popover — bg-popover card, rounded-lg, brand check on the
/// selected row. Rendered above everything; anchored at the chip.
pub fn model_picker_popover(ctx: &egui::Context, state: &mut crate::AppState) {
    if !state.model_picker_open {
        return;
    }
    let Some(chip) = state.model_chip_rect else {
        return;
    };

    // click-away backdrop
    egui::Area::new(egui::Id::new("model-picker-backdrop"))
        .order(egui::Order::Foreground)
        .fixed_pos(Pos2::ZERO)
        .show(ctx, |ui| {
            let (_, resp) = ui.allocate_exact_size(ui.available_size(), Sense::click());
            if resp.clicked() {
                state.model_picker_open = false;
            }
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
                            theme::TEXT_SM,
                            if selected { theme::FG } else { theme::FG_SUBTLE },
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
