//! ZCode tool pages port: each page is a header (title + caption) over a
//! centered column of cards (`bg-card`, hairline, rounded-lg). The data and
//! actions are the XenCode backend (goal DAG runner, XP ledger, telemetry,
//! automation engine, decision store, event log, provider config).

use crate::theme::{self, Palette};
use crate::ui::{card, code_block, ghost, hairline, pill, primary, COLUMN};
use crate::{Pane, XenApp};
use eframe::egui;

pub(crate) fn show(app: &mut XenApp, ctx: &egui::Context, pane: Pane) {
    let p = app.palette();
    egui::CentralPanel::default()
        .frame(egui::Frame::none().fill(p.background()))
        .show(ctx, |ui| {
            // Page header (ZCode settings-title scale).
            let (rect, _) = ui.allocate_exact_size(egui::vec2(ui.available_width(), 68.0), egui::Sense::hover());
            ui.painter().text(
                egui::pos2(rect.left() + 24.0, rect.top() + 26.0),
                egui::Align2::LEFT_CENTER,
                pane.label(),
                egui::FontId::proportional(theme::text::SETTINGS_TITLE),
                p.foreground(),
            );
            ui.painter().text(
                egui::pos2(rect.left() + 24.0, rect.top() + 50.0),
                egui::Align2::LEFT_CENTER,
                pane_caption(pane),
                egui::FontId::proportional(theme::text::SM),
                p.foreground_subtlest(),
            );
            ui.painter()
                .line_segment([rect.left_bottom(), rect.right_bottom()], hairline(p));
            egui::ScrollArea::vertical()
                .id_salt("panel")
                .auto_shrink([false, true])
                .show(ui, |ui| {
                    let max_w = COLUMN.min(ui.available_width() - 48.0);
                    let col_x = ui.cursor().left() + (ui.available_width() - max_w) / 2.0;
                    ui.allocate_new_ui(
                        egui::UiBuilder::new().max_rect(egui::Rect::from_min_size(
                            egui::pos2(col_x, ui.cursor().top()),
                            egui::vec2(max_w, ui.available_height()),
                        )),
                        |ui| {
                            ui.add_space(16.0);
                            match pane {
                                Pane::Goals => page_goals(app, ui, p),
                                Pane::Telemetry => page_telemetry(app, ui, p),
                                Pane::Automation => page_automation(app, ui, p),
                                Pane::Decisions => page_decisions(app, ui, p),
                                Pane::Events => page_events(app, ui, p),
                                Pane::Settings => page_settings(app, ui, p),
                                Pane::Chat => {}
                            }
                            ui.add_space(24.0);
                        },
                    );
                });
        });
}

fn pane_caption(pane: Pane) -> &'static str {
    match pane {
        Pane::Goals => "Goal DAG, swimlanes and GOAL_DAG.json",
        Pane::Telemetry => "XP, streaks and the spec radar, from the shared DB",
        Pane::Automation => ".mjs scripts on the embedded rquickjs engine",
        Pane::Decisions => "Decision cards awaiting a call",
        Pane::Events => "Bi-directional mobile and desktop sync log",
        Pane::Settings => "Providers, workspace tools and paths",
        Pane::Chat => "",
    }
}

fn page_goals(app: &mut XenApp, ui: &mut egui::Ui, p: Palette) {
    let dag = xencode_core::goal::file::load(&app.workspace);
    card(p, ui, |ui| {
        ui.label(egui::RichText::new("Goal DAG").size(theme::text::BASE).color(p.foreground()).strong());
        ui.add_space(6.0);
        code_block(p, ui, |ui| {
            ui.monospace(egui::RichText::new(dag.render()).size(theme::text::SM).color(p.foreground_subtle()));
        });
        ui.add_space(8.0);
        ui.label(egui::RichText::new(dag.summary_counts()).size(theme::text::CAPTION).color(p.foreground_subtle()));
    });
    ui.add_space(10.0);
    card(p, ui, |ui| {
        ui.label(egui::RichText::new("Run").size(theme::text::BASE).color(p.foreground()).strong());
        ui.add_space(6.0);
        ui.horizontal(|ui| {
            if primary(p, ui, "Run READY nodes").clicked() {
                app.status = "running goals…".into();
                let ws = app.workspace.clone();
                std::thread::spawn(move || {
                    let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
                    let summary = rt.block_on(xencode_core::goal::runner::run_ready(&ws, 1));
                    println!("{summary}");
                });
            }
            if ghost(p, ui, "Parallel swimlanes ×4").clicked() {
                app.status = "running swimlanes…".into();
                let ws = app.workspace.clone();
                std::thread::spawn(move || {
                    let rt = tokio::runtime::Builder::new_multi_thread().enable_all().build().unwrap();
                    let summary = rt.block_on(xencode_core::goal::runner::run_ready(&ws, 4));
                    println!("{summary}");
                });
            }
        });
    });
}

fn page_telemetry(app: &mut XenApp, ui: &mut egui::Ui, p: Palette) {
    let now = xencode_core::now_ms();
    match xencode_core::store::xp::DbXpLedger::open_default() {
        Ok(xp) => {
            card(p, ui, |ui| {
                ui.label(egui::RichText::new("XP telemetry").size(theme::text::BASE).color(p.foreground()).strong());
                ui.add_space(6.0);
                code_block(p, ui, |ui| {
                    ui.monospace(
                        egui::RichText::new(xencode_core::telemetry::XpSink::summary(&xp, now).render())
                            .size(theme::text::SM)
                            .color(p.foreground_subtle()),
                    );
                });
                if let Some(pr) = &app.persister {
                    if let Some((calls, errors, in_tok, out_tok)) = pr.llm_totals() {
                        ui.add_space(6.0);
                        ui.label(
                            egui::RichText::new(format!(
                                "tokens (shared DB): in {in_tok} · out {out_tok} · {calls} llm calls ({errors} errored)"
                            ))
                            .size(theme::text::CAPTION)
                            .color(p.foreground_subtle()),
                        );
                    }
                }
                ui.add_space(4.0);
                ui.label(
                    egui::RichText::new(format!(
                        "shared DB: {}",
                        xencode_core::store::SharedStore::default_db_path().display()
                    ))
                    .size(theme::text::XS)
                    .color(p.foreground_subtlest()),
                );
            });
        }
        Err(_) => {
            let xp = xencode_core::telemetry::XpLedger::default();
            card(p, ui, |ui| {
                code_block(p, ui, |ui| {
                    ui.monospace(
                        egui::RichText::new(xp.render_summary(now)).size(theme::text::SM).color(p.foreground_subtle()),
                    );
                });
                ui.label(
                    egui::RichText::new("! shared database unavailable, showing the legacy file ledger")
                        .size(theme::text::SM)
                        .color(p.destructive()),
                );
            });
        }
    }
    ui.add_space(10.0);
    card(p, ui, |ui| {
        ui.label(egui::RichText::new("Spec radar").size(theme::text::BASE).color(p.foreground()).strong());
        ui.add_space(6.0);
        code_block(p, ui, |ui| {
            ui.monospace(
                egui::RichText::new(xencode_core::telemetry::Telemetry::load(&app.workspace).render_radar())
                    .size(theme::text::SM)
                    .color(p.foreground_subtle()),
            );
        });
    });
}

fn page_automation(app: &mut XenApp, ui: &mut egui::Ui, p: Palette) {
    card(p, ui, |ui| {
        ui.label(egui::RichText::new("Run a script").size(theme::text::BASE).color(p.foreground()).strong());
        ui.add_space(4.0);
        ui.label(
            egui::RichText::new(
                "import { fs, ast, pty, git, sync } from \"xencode:core\"; export function main() { … }",
            )
            .size(theme::text::SM)
            .color(p.foreground_subtle())
            .monospace(),
        );
        ui.add_space(8.0);
        ui.horizontal(|ui| {
            let se = ui.add(
                egui::TextEdit::singleline(&mut app.automation_script)
                    .hint_text("script path relative to workspace")
                    .desired_width(ui.available_width() - 110.0),
            );
            if primary(p, ui, "Run").clicked()
                || (se.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter)))
            {
                let path = app.workspace.join(app.automation_script.trim());
                let engine = xencode_automation::MjsEngine::new(&app.workspace);
                app.automation_out = match engine.run_file(&path, None) {
                    Ok(res) => xencode_automation::render_result(&res),
                    Err(e) => format!("! {e}"),
                };
            }
        });
    });
    ui.add_space(10.0);
    card(p, ui, |ui| {
        ui.label(egui::RichText::new("Output").size(theme::text::BASE).color(p.foreground()).strong());
        ui.add_space(6.0);
        code_block(p, ui, |ui| {
            ui.monospace(
                egui::RichText::new(&app.automation_out).size(theme::text::SM).color(p.foreground_subtle()),
            );
        });
    });
}

fn page_decisions(app: &mut XenApp, ui: &mut egui::Ui, p: Palette) {
    let store = xencode_core::state::decisions::DecisionStore::new(&app.workspace);
    let cards = store.all();
    if cards.is_empty() {
        card(p, ui, |ui| {
            ui.label(
                egui::RichText::new("No decision cards yet.").size(theme::text::BASE).color(p.foreground_subtle()),
            );
        });
    }
    for card_data in &cards {
        card(p, ui, |ui| {
            ui.horizontal(|ui| {
                ui.label(egui::RichText::new(&card_data.title).size(theme::text::BASE).color(p.foreground()).strong());
                ui.label(
                    egui::RichText::new(&card_data.id).size(theme::text::XS).color(p.foreground_subtlest()).monospace(),
                );
                if card_data.chosen_index.is_none() {
                    pill(p, ui, "PENDING", p.warning(), p.primary_foreground());
                }
            });
            if !card_data.body.is_empty() {
                ui.label(egui::RichText::new(&card_data.body).size(theme::text::CAPTION).color(p.foreground_subtle()));
            }
            ui.add_space(4.0);
            ui.horizontal(|ui| {
                for (i, opt) in card_data.options.iter().enumerate() {
                    if ghost(p, ui, opt).clicked() {
                        let _ = store.resolve(&card_data.id, i);
                        xencode_core::state::events::EventLog::new(&app.workspace).emit_simple(
                            xencode_core::state::events::types::DECISION_RESOLVED,
                            Some(&card_data.title),
                        );
                    }
                }
            });
        });
        ui.add_space(10.0);
    }
}

fn page_events(app: &mut XenApp, ui: &mut egui::Ui, p: Palette) {
    let log = xencode_core::state::events::EventLog::new(&app.workspace);
    card(p, ui, |ui| {
        code_block(p, ui, |ui| {
            egui::ScrollArea::vertical()
                .id_salt("events")
                .stick_to_bottom(true)
                .max_height(ui.available_height().max(360.0))
                .show(ui, |ui| {
                    ui.monospace(
                        egui::RichText::new(log.render_tail(200)).size(theme::text::SM).color(p.foreground_subtle()),
                    );
                });
        });
    });
}

fn page_settings(app: &mut XenApp, ui: &mut egui::Ui, p: Palette) {
    let cfg = xencode_core::config::load_config().unwrap_or_default();
    card(p, ui, |ui| {
        ui.label(egui::RichText::new("Providers").size(theme::text::BASE).color(p.foreground()).strong());
        ui.add_space(6.0);
        for pr in &cfg.providers {
            ui.horizontal(|ui| {
                ui.label(egui::RichText::new(&pr.name).size(theme::text::BASE).color(p.foreground()));
                ui.label(egui::RichText::new(pr.kind.as_str()).size(theme::text::CAPTION).color(p.foreground_subtle()));
                if Some(&pr.name) == cfg.active.as_ref() {
                    pill(p, ui, "ACTIVE", p.success(), p.primary_foreground());
                }
            });
            ui.label(
                egui::RichText::new(format!("{} · {}", pr.base_url, pr.model))
                    .size(theme::text::XS)
                    .color(p.foreground_subtlest())
                    .monospace(),
            );
            ui.add_space(2.0);
        }
        if cfg.providers.is_empty() {
            ui.label(
                egui::RichText::new(
                    "No providers yet. Add one with: xencode config set-provider <name> --base-url … --api-key … --model …",
                )
                .size(theme::text::CAPTION)
                .color(p.foreground_subtle()),
            );
        }
        ui.add_space(6.0);
        ui.horizontal(|ui| {
            for pr in &cfg.providers {
                if ghost(p, ui, &format!("use {}", pr.name)).clicked() {
                    let mut c = cfg.clone();
                    c.active = Some(pr.name.clone());
                    match xencode_core::config::save_config(&c) {
                        Ok(()) => app.settings_note = format!("active provider: {}", pr.name),
                        Err(e) => app.settings_note = format!("! {e}"),
                    }
                }
            }
        });
        if !app.settings_note.is_empty() {
            ui.label(egui::RichText::new(&app.settings_note).size(theme::text::SM).color(p.brand()));
        }
    });
    ui.add_space(10.0);
    card(p, ui, |ui| {
        ui.label(egui::RichText::new("Workspace tools").size(theme::text::BASE).color(p.foreground()).strong());
        ui.add_space(6.0);
        ui.horizontal(|ui| {
            for pane in [Pane::Goals, Pane::Telemetry, Pane::Automation] {
                if ghost(p, ui, pane.label()).clicked() {
                    app.pane = pane;
                }
            }
        });
        ui.horizontal(|ui| {
            for pane in [Pane::Decisions, Pane::Events] {
                if ghost(p, ui, pane.label()).clicked() {
                    app.pane = pane;
                }
            }
        });
    });
    ui.add_space(10.0);
    card(p, ui, |ui| {
        ui.label(egui::RichText::new("Paths").size(theme::text::BASE).color(p.foreground()).strong());
        ui.add_space(6.0);
        ui.label(
            egui::RichText::new(format!("config: {}", xencode_core::config::config_path().display()))
                .size(theme::text::CAPTION)
                .color(p.foreground_subtle()),
        );
        ui.label(
            egui::RichText::new(format!("workspace: {}", app.workspace.display()))
                .size(theme::text::CAPTION)
                .color(p.foreground_subtle()),
        );
    });
}
