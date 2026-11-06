//! ZCode conversation workspace port (`v4` timeline): the empty-task home
//! (greeting + centered composer) and the transcript (user bubbles, tool
//! blocks, assistant text) with the composer docked at the bottom.

use crate::theme::{self, Palette};
use crate::ui::{composer, COLUMN};
use crate::XenApp;
use eframe::egui;
use std::sync::atomic::Ordering;

pub(crate) fn show(app: &mut XenApp, ctx: &egui::Context) {
    let p = app.palette();
    let has_transcript = !app.chat_log.is_empty();

    if has_transcript {
        // Docked composer at the bottom of the main view.
        egui::TopBottomPanel::bottom("composer_dock")
            .frame(
                egui::Frame::none()
                    .fill(p.background())
                    .inner_margin(egui::Margin { left: 16.0, right: 16.0, top: 0.0, bottom: 12.0 }),
            )
            .show_separator_line(false)
            .show(ctx, |ui| {
                ui.with_layout(egui::Layout::top_down(egui::Align::Center), |ui| {
                    ui.set_max_width(COLUMN + 8.0);
                    if app.mode == xencode_core::permissions::PermissionMode::Plan {
                        plan_notice(app, ui);
                        ui.add_space(6.0);
                    }
                    composer::card(app, ui, false);
                });
            });
    }

    egui::CentralPanel::default()
        .frame(egui::Frame::none().fill(p.background()))
        .show(ctx, |ui| {
            if has_transcript {
                transcript(app, ui, p);
            } else {
                empty_task_home(app, ui, p);
            }
        });
}

/// Plan-mode notice above the composer (ZCode interaction-ask surface).
fn plan_notice(app: &XenApp, ui: &mut egui::Ui) {
    let p = app.palette();
    egui::Frame::default()
        .fill(p.accent())
        .rounding(egui::Rounding::same(8.0))
        .inner_margin(egui::Margin::symmetric(10.0, 6.0))
        .show(ui, |ui| {
            ui.set_min_width(ui.available_width());
            ui.label(
                egui::RichText::new(
                    "Plan mode: XenCode researches and proposes a plan without editing files. Approve the plan, switch back to Agent mode and say go ahead to execute it.",
                )
                .size(theme::text::SM)
                .color(p.foreground()),
            );
        });
}

/// The empty-task home: greeting + centered composer card + starter hints
/// (ZCode new-task view geometry).
fn empty_task_home(app: &mut XenApp, ui: &mut egui::Ui, p: Palette) {
    let avail = ui.max_rect();
    let card_h = composer::measure(app, true);
    let stack_h = 40.0 + 14.0 + card_h + 18.0 + 26.0;
    let top = ((avail.height() - stack_h) / 2.0).max(48.0);

    // Greeting (text-ui-xl, centered).
    ui.painter().text(
        egui::pos2(avail.center().x, avail.top() + top + 20.0),
        egui::Align2::CENTER_CENTER,
        "What can I do for you?",
        egui::FontId::proportional(theme::text::XL),
        p.foreground(),
    );

    // Composer card, centered at the ZCode column width.
    let w = COLUMN.min(avail.width() - 32.0);
    let card_rect = egui::Rect::from_min_size(
        egui::pos2(avail.center().x - w / 2.0, avail.top() + top + 40.0 + 14.0),
        egui::vec2(w, card_h),
    );
    composer::card_at(app, ui, card_rect, true);

    // Starter hints row.
    let hints = ["Explain this workspace", "Write a feature", "Fix a bug"];
    let mut x = avail.center().x - (hints.len() as f32 * 130.0 + 16.0) / 2.0;
    for h in hints {
        let rect = egui::Rect::from_min_size(egui::pos2(x, card_rect.bottom() + 18.0), egui::vec2(130.0, 26.0));
        let resp = ui.interact(rect, egui::Id::new(format!("hint_{h}")), egui::Sense::click());
        if resp.hovered() {
            ui.painter().rect_filled(rect, 999.0, p.hover());
        } else {
            ui.painter().rect_stroke(rect, 999.0, crate::ui::hairline(p));
        }
        ui.painter().text(
            rect.center(),
            egui::Align2::CENTER_CENTER,
            h,
            egui::FontId::proportional(theme::text::CAPTION),
            p.foreground_subtle(),
        );
        if resp.clicked() {
            app.chat_input = h.to_string();
        }
        x += 130.0 + 8.0;
    }

    // Session resume note (row-sync feedback), quietly above the composer.
    if !app.chat_note.is_empty() {
        ui.painter().text(
            egui::pos2(avail.center().x, card_rect.bottom() + 54.0),
            egui::Align2::CENTER_CENTER,
            crate::ui::ellipsize(&app.chat_note, 90),
            egui::FontId::proportional(theme::text::XS),
            p.foreground_subtlest(),
        );
    }
}

/// The transcript timeline (centered column, stick to bottom).
fn transcript(app: &mut XenApp, ui: &mut egui::Ui, p: Palette) {
    let busy = app.worker.as_ref().map(|w| w.busy.load(Ordering::SeqCst)).unwrap_or(false);
    let streamed = app
        .worker
        .as_ref()
        .map(|w| w.stream.lock().unwrap().clone())
        .unwrap_or_default();

    // Flush the streamed turn into the log when the worker idles.
    if !busy {
        let failed = app.worker.as_ref().and_then(|w| w.failure.lock().unwrap().clone());
        if let Some(entry) = app.chat_log.last_mut() {
            if entry.role == "stream" {
                entry.text = streamed.clone();
                if let Some(f) = &failed {
                    entry.text = format!("{}\n! {f}", entry.text);
                }
                entry.role = "agent".into();
                app.status = "Idle".into();
            }
        } else if let Some(f) = &failed {
            app.chat_log.push(crate::ChatEntry::new("agent", format!("! {f}")));
        }
    }

    // Conversation header: task title + status (ZCode workspace header).
    let (hrect, _) = ui.allocate_exact_size(egui::vec2(ui.available_width(), 48.0), egui::Sense::hover());
    let title = app
        .active_session_title
        .clone()
        .unwrap_or_else(|| "New task".into());
    ui.painter().text(
        egui::pos2(hrect.left() + 8.0, hrect.center().y),
        egui::Align2::LEFT_CENTER,
        crate::ui::ellipsize(&title, 40),
        egui::FontId::proportional(theme::text::BASE),
        p.foreground(),
    );
    let working = busy || app.status == "thinking…";
    let (dot, label) = if working { (p.warning(), "Working…") } else { (p.success(), app.status.as_str()) };
    ui.painter().circle_filled(egui::pos2(hrect.right() - 60.0, hrect.center().y), 3.5, dot);
    ui.painter().text(
        egui::pos2(hrect.right() - 8.0, hrect.center().y),
        egui::Align2::RIGHT_CENTER,
        label,
        egui::FontId::proportional(theme::text::XS),
        p.foreground_subtlest(),
    );

    egui::ScrollArea::vertical()
        .id_salt("chat")
        .stick_to_bottom(true)
        .auto_shrink([false, true])
        .show(ui, |ui| {
            let max_w = COLUMN.min(ui.available_width() - 16.0);
            let col_x = ui.cursor().left() + (ui.available_width() - max_w) / 2.0;
            ui.allocate_new_ui(
                egui::UiBuilder::new().max_rect(egui::Rect::from_min_size(
                    egui::pos2(col_x, ui.cursor().top()),
                    egui::vec2(max_w, ui.available_height()),
                )),
                |ui| {
                    ui.add_space(12.0);
                    let entries = app.chat_log.clone();
                    for (idx, entry) in entries.iter().enumerate() {
                        match entry.role.as_str() {
                            "user" => user_bubble(app, ui, p, entry),
                            "tool" => tool_block(app, ui, p, idx as u64, entry),
                            "stream" => {
                                let dots = if busy { " …" } else { "" };
                                ui.label(
                                    egui::RichText::new(format!("{}{dots}", entry.text))
                                        .size(theme::text::BASE)
                                        .color(if busy { p.foreground_subtle() } else { p.foreground() }),
                                );
                            }
                            _ => {
                                ui.label(
                                    egui::RichText::new(&entry.text)
                                        .size(theme::text::BASE)
                                        .color(p.foreground()),
                                );
                            }
                        }
                        ui.add_space(12.0);
                    }
                    if !app.chat_note.is_empty() {
                        ui.label(
                            egui::RichText::new(&app.chat_note)
                                .size(theme::text::XS)
                                .color(p.foreground_subtlest()),
                        );
                    }
                    ui.add_space(10.0);
                },
            );
        });
}

/// User message: right-aligned bubble on --color-user-message-bubble.
fn user_bubble(app: &mut XenApp, ui: &mut egui::Ui, p: Palette, entry: &crate::ChatEntry) {
    let max_text_w = (ui.available_width() * 0.75).max(280.0);
    let galley = ui.fonts(|f| {
        f.layout(
            entry.text.clone(),
            egui::FontId::proportional(theme::text::BASE),
            p.foreground(),
            max_text_w,
        )
    });
    let pad_x = 14.0;
    let pad_y = 10.0;
    let w = (galley.size().x + pad_x * 2.0).min(max_text_w + pad_x * 2.0);
    ui.with_layout(egui::Layout::top_down(egui::Align::Max), |ui| {
        ui.allocate_ui_with_layout(
            egui::vec2(w, 20.0),
            egui::Layout::top_down(egui::Align::LEFT),
            |ui| {
                egui::Frame::default()
                    .fill(p.user_message_bubble())
                    .rounding(egui::Rounding::same(theme::geom::RADIUS))
                    .inner_margin(egui::Margin::symmetric(pad_x, pad_y))
                    .show(ui, |ui| {
                        if !entry.images.is_empty() {
                            ui.horizontal(|ui| {
                                for img in &entry.images {
                                    app.thumbnail(ui, img, 96.0);
                                }
                            });
                        }
                        ui.add(egui::Label::new(egui::WidgetText::Galley(galley.clone())));
                    });
            },
        );
    });
}

/// Tool call: collapsible block on the surface token (ZCode ToolCallBlocks).
fn tool_block(app: &mut XenApp, ui: &mut egui::Ui, p: Palette, idx: u64, entry: &crate::ChatEntry) {
    let open = app.open_tools.contains(&idx);
    let (name, body) = match entry.text.split_once(": ") {
        Some((n, b)) => (n.to_string(), b.to_string()),
        None => (entry.text.clone(), String::new()),
    };
    let w = (ui.available_width() * 0.9).min(COLUMN).max(300.0);
    ui.allocate_ui_with_layout(egui::vec2(w, 20.0), egui::Layout::top_down(egui::Align::LEFT), |ui| {
        egui::Frame::default()
            .fill(p.surface())
            .rounding(egui::Rounding::same(8.0))
            .stroke(crate::ui::hairline(p))
            .inner_margin(egui::Margin::symmetric(10.0, 7.0))
            .show(ui, |ui| {
                ui.with_layout(egui::Layout::left_to_right(egui::Align::Center), |ui| {
                    let (r, resp) = ui.allocate_exact_size(egui::vec2(14.0, 14.0), egui::Sense::click());
                    let c = r.center();
                    let color = p.foreground_subtlest();
                    if open {
                        let pts = [
                            egui::pos2(c.x - 4.0, c.y - 1.5),
                            egui::pos2(c.x + 4.0, c.y - 1.5),
                            egui::pos2(c.x, c.y + 3.5),
                        ];
                        ui.painter().add(egui::Shape::convex_polygon(pts.to_vec(), color, egui::Stroke::NONE));
                    } else {
                        let pts = [
                            egui::pos2(c.x - 1.5, c.y - 4.0),
                            egui::pos2(c.x + 3.5, c.y),
                            egui::pos2(c.x - 1.5, c.y + 4.0),
                        ];
                        ui.painter().add(egui::Shape::convex_polygon(pts.to_vec(), color, egui::Stroke::NONE));
                    }
                    if resp.clicked() {
                        if open {
                            app.open_tools.remove(&idx);
                        } else {
                            app.open_tools.insert(idx);
                        }
                    }
                    let (r, _) = ui.allocate_exact_size(egui::vec2(7.0, 7.0), egui::Sense::hover());
                    ui.painter().circle_filled(r.center(), 2.5, p.success());
                    ui.label(
                        egui::RichText::new(if open { format!("{name} done") } else { name.clone() })
                            .size(theme::text::SM)
                            .color(p.foreground_subtle()),
                    );
                });
                if open && !body.is_empty() {
                    ui.add_space(4.0);
                    ui.label(
                        egui::RichText::new(&body)
                            .size(theme::text::SM)
                            .color(p.foreground_subtle())
                            .monospace(),
                    );
                }
            });
    });
}
