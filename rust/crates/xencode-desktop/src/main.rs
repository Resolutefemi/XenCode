//! XenCode Desktop — Rust GUI for Windows, macOS and Linux.
//!
//! Panes: Chat (agent), Goals (DAG + swimlanes), Telemetry (XP / spec radar),
//! Automation (.mjs runner), Decisions (cards), Events (sync log), Settings.
//! The window/taskbar icon comes from `art/xencode-icon-512.png` on every OS.

use eframe::egui;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{mpsc, Arc, Mutex};


const ICON_PNG: &[u8] = include_bytes!("../../../../art/xencode-icon-512.png");

fn load_icon() -> Option<Arc<egui::IconData>> {
    let img = image::load_from_memory(ICON_PNG).ok()?.into_rgba8();
    let (w, h) = img.dimensions();
    Some(Arc::new(egui::IconData { width: w, height: h, rgba: img.into_raw() }))
}

fn main() -> eframe::Result<()> {
    let workspace = std::env::var("XENCODE_WORKSPACE")
        .map(PathBuf::from)
        .unwrap_or_else(|_| std::env::current_dir().unwrap_or_else(|_| PathBuf::from(".")));
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_title("XenCode")
            .with_inner_size([1280.0, 820.0])
            .with_min_inner_size([900.0, 600.0])
            .with_icon(load_icon().expect("embedded icon is valid PNG")),
        ..Default::default()
    };
    eframe::run_native(
        "XenCode",
        options,
        Box::new(move |cc| {
            egui_extras_style(cc);
            Ok(Box::new(XenApp::new(workspace)))
        }),
    )
}

fn egui_extras_style(cc: &eframe::CreationContext<'_>) {
    let mut visuals = egui::Visuals::dark();
    visuals.panel_fill = egui::Color32::from_rgb(16, 18, 24);
    visuals.window_fill = egui::Color32::from_rgb(20, 22, 30);
    visuals.selection.bg_fill = egui::Color32::from_rgb(96, 118, 255);
    cc.egui_ctx.set_visuals(visuals);
}

#[derive(PartialEq, Clone, Copy)]
enum Pane {
    Chat,
    Goals,
    Telemetry,
    Automation,
    Decisions,
    Events,
    Settings,
}

struct ChatWorker {
    tx: mpsc::Sender<String>,
    stream: Arc<Mutex<String>>,
    busy: Arc<AtomicBool>,
    failure: Arc<Mutex<Option<String>>>,
}

struct XenApp {
    workspace: PathBuf,
    pane: Pane,
    chat_input: String,
    chat_log: Vec<(String, String)>, // (role, text)
    worker: Option<ChatWorker>,
    automation_script: String,
    automation_out: String,
    settings_note: String,
    status: String,
}

impl XenApp {
    fn new(workspace: PathBuf) -> Self {
        Self {
            workspace,
            pane: Pane::Chat,
            chat_input: String::new(),
            chat_log: vec![],
            worker: None,
            automation_script: String::new(),
            automation_out: String::new(),
            settings_note: String::new(),
            status: "idle".into(),
        }
    }

    fn ensure_worker(&mut self) {
        if self.worker.is_some() {
            return;
        }
        let workspace = self.workspace.clone();
        let (tx, rx) = mpsc::channel::<String>();
        let stream: Arc<Mutex<String>> = Arc::new(Mutex::new(String::new()));
        let busy = Arc::new(AtomicBool::new(false));
        let failure: Arc<Mutex<Option<String>>> = Arc::new(Mutex::new(None));
        let stream2 = stream.clone();
        let busy2 = busy.clone();
        let failure2 = failure.clone();
        std::thread::spawn(move || {
            let rt = tokio::runtime::Builder::new_multi_thread().enable_all().build().expect("tokio runtime");
            let cfg = xencode_core::config::load_config().unwrap_or_default();
            let mut session = match xencode_core::agent::session_from_config(
                &workspace,
                &cfg,
                xencode_core::permissions::PermissionMode::Yolo,
                true,
            ) {
                Ok(s) => s,
                Err(e) => {
                    *failure2.lock().unwrap() = Some(e);
                    busy2.store(false, Ordering::SeqCst);
                    for _ in rx.iter() {}
                    return;
                }
            };
            for prompt in rx.iter() {
                busy2.store(true, Ordering::SeqCst);
                stream2.lock().unwrap().clear();
                *failure2.lock().unwrap() = None;
                let stream = stream2.clone();
                let out = rt.block_on(session.send(&prompt, &mut |d| {
                    stream.lock().unwrap().push_str(d);
                }, &mut |_| {}));
                if out.is_none() {
                    *failure2.lock().unwrap() = session.last_failure.clone();
                }
                busy2.store(false, Ordering::SeqCst);
            }
        });
        self.worker = Some(ChatWorker { tx, stream, busy, failure });
    }
}

impl eframe::App for XenApp {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        // Side navigation
        egui::SidePanel::left("nav").exact_width(170.0).show(ctx, |ui| {
            ui.add_space(12.0);
            ui.heading(egui::RichText::new("⬢ XenCode").color(egui::Color32::from_rgb(120, 150, 255)).size(22.0));
            ui.add_space(12.0);
            ui.separator();
            for (pane, label) in [
                (Pane::Chat, "💬 Chat"),
                (Pane::Goals, "🎯 Goals"),
                (Pane::Telemetry, "📈 Telemetry"),
                (Pane::Automation, "⚙ Automation"),
                (Pane::Decisions, "❓ Decisions"),
                (Pane::Events, "📡 Events"),
                (Pane::Settings, "🔧 Settings"),
            ] {
                if ui.selectable_label(self.pane == pane, label).clicked() {
                    self.pane = pane;
                }
            }
            ui.with_layout(egui::Layout::bottom_up(egui::Align::LEFT), |ui| {
                ui.separator();
                ui.monospace(format!("{} · v{}", self.workspace.display(), env!("CARGO_PKG_VERSION")));
            });
        });

        egui::CentralPanel::default().show(ctx, |ui| match self.pane {
            Pane::Chat => self.pane_chat(ui),
            Pane::Goals => self.pane_goals(ui),
            Pane::Telemetry => self.pane_telemetry(ui),
            Pane::Automation => self.pane_automation(ui),
            Pane::Decisions => self.pane_decisions(ui),
            Pane::Events => self.pane_events(ui),
            Pane::Settings => self.pane_settings(ui),
        });

        // Live chat stream updates → repaint while busy.
        if let Some(w) = &self.worker {
            if w.busy.load(Ordering::SeqCst) {
                ctx.request_repaint();
            }
        }
    }
}

impl XenApp {
    fn pane_chat(&mut self, ui: &mut egui::Ui) {
        // Flush streamed text into the log when the worker goes idle.
        if let Some(w) = &mut self.worker {
            if !w.busy.load(Ordering::SeqCst) {
                let streamed = w.stream.lock().unwrap().clone();
                let failed = w.failure.lock().unwrap().clone();
                if let Some((role, text)) = self.chat_log.last_mut() {
                    if role == "stream" {
                        *text = streamed.clone();
                        if let Some(f) = &failed {
                            *text = format!("{text}\n! {f}");
                        }
                        *role = "agent".into();
                        self.status = "idle".into();
                    }
                } else if let Some(f) = &failed {
                    self.chat_log.push(("agent".into(), format!("! {f}")));
                }
            }
        }

        egui::ScrollArea::vertical().stick_to_bottom(true).show(ui, |ui| {
            for (role, text) in &self.chat_log {
                let (name, color) = match role.as_str() {
                    "user" => ("You", egui::Color32::from_rgb(190, 200, 255)),
                    "tool" => ("Tool", egui::Color32::from_rgb(255, 210, 120)),
                    _ => ("XenCode", egui::Color32::from_rgb(140, 220, 170)),
                };
                ui.group(|ui| {
                    ui.label(egui::RichText::new(name).strong().color(color));
                    ui.label(text);
                });
            }
        });
        ui.separator();
        egui::TopBottomPanel::bottom("chat_input").exact_height(64.0).show_inside(ui, |ui| {
            ui.horizontal(|ui| {
                let send = ui.add(egui::TextEdit::singleline(&mut self.chat_input).hint_text("Ask XenCode to build something…  (Enter to send)").desired_width(ui.available_width() - 90.0));
                let send_clicked = ui.button("Send ▶").clicked() || (send.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter)));
                if send_clicked && !self.chat_input.trim().is_empty() {
                    self.ensure_worker();
                    let prompt = self.chat_input.trim().to_string();
                    self.chat_input.clear();
                    self.chat_log.push(("user".into(), prompt.clone()));
                    self.chat_log.push(("stream".into(), String::new()));
                    self.status = "thinking…".into();
                    if let Some(w) = &self.worker {
                        let _ = w.tx.send(prompt);
                    }
                }
            });
        });
    }

    fn pane_goals(&mut self, ui: &mut egui::Ui) {
        ui.heading("Goal DAG");
        ui.separator();
        let dag = xencode_core::goal::file::load(&self.workspace);
        egui::ScrollArea::vertical().show(ui, |ui| {
            ui.monospace(dag.render());
            ui.add_space(8.0);
            ui.label(dag.summary_counts());
        });
        ui.separator();
        ui.horizontal(|ui| {
            if ui.button("▶ Run READY nodes (sequential)").clicked() {
                self.status = "running goals…".into();
                let ws = self.workspace.clone();
                std::thread::spawn(move || {
                    let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
                    let summary = rt.block_on(xencode_core::goal::runner::run_ready(&ws, 1));
                    println!("{summary}");
                });
            }
            if ui.button("▶▶ Parallel swimlanes ×4").clicked() {
                self.status = "running swimlanes…".into();
                let ws = self.workspace.clone();
                std::thread::spawn(move || {
                    let rt = tokio::runtime::Builder::new_multi_thread().enable_all().build().unwrap();
                    let summary = rt.block_on(xencode_core::goal::runner::run_ready(&ws, 4));
                    println!("{summary}");
                });
            }
        });
    }

    fn pane_telemetry(&mut self, ui: &mut egui::Ui) {
        ui.heading("Telemetry — XP · streaks · spec radar");
        ui.separator();
        let xp = xencode_core::telemetry::XpLedger::default();
        ui.monospace(xp.render_summary(xencode_core::now_ms()));
        ui.add_space(12.0);
        ui.monospace(xencode_core::telemetry::Telemetry::load(&self.workspace).render_radar());
    }

    fn pane_automation(&mut self, ui: &mut egui::Ui) {
        ui.heading("Automation — .mjs scripts (embedded rquickjs)");
        ui.separator();
        ui.label("import { fs, ast, pty, git, sync } from \"xencode:core\"; export function main() { … }");
        let se = ui.add(egui::TextEdit::singleline(&mut self.automation_script).hint_text("script path relative to workspace").desired_width(500.0));
        if ui.button("Run ▶").clicked() || (se.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter))) {
            let path = self.workspace.join(self.automation_script.trim());
            let engine = xencode_automation::MjsEngine::new(&self.workspace);
            self.automation_out = match engine.run_file(&path, None) {
                Ok(res) => xencode_automation::render_result(&res),
                Err(e) => format!("! {e}"),
            };
        }
        ui.separator();
        egui::ScrollArea::vertical().show(ui, |ui| {
            ui.monospace(&self.automation_out);
        });
    }

    fn pane_decisions(&mut self, ui: &mut egui::Ui) {
        ui.heading("Decision cards");
        ui.separator();
        let store = xencode_core::state::decisions::DecisionStore::new(&self.workspace);
        let cards = store.all();
        egui::ScrollArea::vertical().show(ui, |ui| {
            for card in &cards {
                ui.group(|ui| {
                    ui.horizontal(|ui| {
                        ui.strong(&card.title);
                        ui.monospace(&card.id);
                        if card.chosen_index.is_none() {
                            ui.label(egui::RichText::new("PENDING").color(egui::Color32::from_rgb(255, 190, 90)));
                        }
                    });
                    if !card.body.is_empty() {
                        ui.label(&card.body);
                    }
                    ui.horizontal(|ui| {
                        for (i, opt) in card.options.iter().enumerate() {
                            if ui.button(opt.to_string()).clicked() {
                                let _ = store.resolve(&card.id, i);
                                xencode_core::state::events::EventLog::new(&self.workspace).emit_simple(
                                    xencode_core::state::events::types::DECISION_RESOLVED,
                                    Some(&card.title),
                                );
                            }
                        }
                    });
                });
            }
            if cards.is_empty() {
                ui.label("(no decision cards)");
            }
        });
    }

    fn pane_events(&mut self, ui: &mut egui::Ui) {
        ui.heading("Event log — bi-directional mobile ⇄ desktop sync");
        ui.separator();
        let log = xencode_core::state::events::EventLog::new(&self.workspace);
        egui::ScrollArea::vertical().stick_to_bottom(true).show(ui, |ui| {
            ui.monospace(log.render_tail(200));
        });
    }

    fn pane_settings(&mut self, ui: &mut egui::Ui) {
        ui.heading("Settings");
        ui.separator();
        let cfg = xencode_core::config::load_config().unwrap_or_default();
        ui.label(format!("config: {}", xencode_core::config::config_path().display()));
        ui.label(format!("workspace: {}", self.workspace.display()));
        ui.add_space(8.0);
        for p in &cfg.providers {
            ui.group(|ui| {
                ui.horizontal(|ui| {
                    ui.strong(&p.name);
                    ui.label(p.kind.as_str());
                    if Some(&p.name) == cfg.active.as_ref() {
                        ui.label(egui::RichText::new("ACTIVE").color(egui::Color32::from_rgb(140, 220, 170)));
                    }
                });
                ui.monospace(format!("{} · {}", p.base_url, p.model));
            });
        }
        if cfg.providers.is_empty() {
            ui.label("No providers yet — run: xencode config set-provider <name> --base-url … --api-key … --model …");
        }
        ui.add_space(8.0);
        ui.horizontal(|ui| {
            for p in &cfg.providers {
                if ui.button(format!("use {}", p.name)).clicked() {
                    let mut c = cfg.clone();
                    c.active = Some(p.name.clone());
                    match xencode_core::config::save_config(&c) {
                        Ok(()) => self.settings_note = format!("active provider: {}", p.name),
                        Err(e) => self.settings_note = format!("! {e}"),
                    }
                }
            }
        });
        if !self.settings_note.is_empty() {
            ui.label(&self.settings_note);
        }
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn icon_is_embedded() {
        // PNG magic
        assert_eq!(&super::ICON_PNG[..8], &[0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A]);
    }
}
