//! XenCode Desktop — Rust GUI for Windows, macOS and Linux.
//!
//! Panes: Chat (agent, plan mode, image attachments, session resume), Goals
//! (DAG + swimlanes), Telemetry (XP / spec radar), Automation (.mjs runner),
//! Decisions (cards), Events (sync log), Settings. The window/taskbar icon
//! comes from `art/xencode-icon-512.png` on every OS.

use eframe::egui;
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{mpsc, Arc, Mutex};
use xencode_core::permissions::PermissionMode;

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

/// Messages the chat pane sends to the agent worker thread.
enum ChatMsg {
    Turn {
        text: String,
        images: Vec<PathBuf>,
    },
    /// Row sync in: adopt a shared-DB session (its rows become the model
    /// history) so the next turns append to it.
    Resume {
        session_id: String,
    },
}

/// One rendered transcript row.
struct ChatEntry {
    role: String, // user | stream | agent | tool | system
    text: String,
    images: Vec<PathBuf>,
}

impl ChatEntry {
    fn new(role: &str, text: impl Into<String>) -> Self {
        Self { role: role.into(), text: text.into(), images: vec![] }
    }
}

struct ChatWorker {
    tx: mpsc::Sender<ChatMsg>,
    stream: Arc<Mutex<String>>,
    busy: Arc<AtomicBool>,
    failure: Arc<Mutex<Option<String>>>,
}

struct XenApp {
    workspace: PathBuf,
    pane: Pane,
    chat_input: String,
    chat_log: Vec<ChatEntry>,
    /// Images staged for the next message.
    pending_images: Vec<PathBuf>,
    /// Decoded image thumbnails, memoized by path (None = failed to decode).
    thumbnails: HashMap<String, Option<egui::TextureHandle>>,
    /// Current permission mode; mirrored to the worker through `mode_shared`.
    mode: PermissionMode,
    mode_shared: Arc<Mutex<PermissionMode>>,
    worker: Option<ChatWorker>,
    /// Shared-database writer (aicode_agent_db); None when the DB cannot be
    /// opened — the chat keeps working without persistence.
    persister: Option<std::sync::Arc<xencode_core::store::chatlog::ChatPersister>>,
    automation_script: String,
    automation_out: String,
    settings_note: String,
    status: String,
    chat_note: String,
    /// Row-sync session picker: popup open flag + cached recent shared-DB sessions
    /// (id, title, mode, updated_at, tokens) refreshed every time the picker opens.
    sessions_open: bool,
    cached_sessions: Vec<(String, String, String, i64, i64)>,
}

impl XenApp {
    fn new(workspace: PathBuf) -> Self {
        Self {
            workspace,
            pane: Pane::Chat,
            chat_input: String::new(),
            chat_log: vec![],
            pending_images: vec![],
            thumbnails: HashMap::new(),
            mode: PermissionMode::Yolo,
            mode_shared: Arc::new(Mutex::new(PermissionMode::Yolo)),
            worker: None,
            persister: xencode_core::store::chatlog::ChatPersister::open_default(),
            automation_script: String::new(),
            automation_out: String::new(),
            settings_note: String::new(),
            status: "idle".into(),
            chat_note: String::new(),
            sessions_open: false,
            cached_sessions: vec![],
        }
    }

    /// Switches the permission mode everywhere at once: the UI chips, the
    /// worker's live session and the shared-DB `chat_sessions.mode` column.
    fn set_mode(&mut self, mode: PermissionMode) {
        self.mode = mode;
        *self.mode_shared.lock().expect("mode shared") = mode;
        if let Some(p) = &self.persister {
            p.set_permission_mode(mode);
        }
    }

    fn ensure_worker(&mut self) {
        if self.worker.is_some() {
            return;
        }
        let workspace = self.workspace.clone();
        let (tx, rx) = mpsc::channel::<ChatMsg>();
        let stream: Arc<Mutex<String>> = Arc::new(Mutex::new(String::new()));
        let busy = Arc::new(AtomicBool::new(false));
        let failure: Arc<Mutex<Option<String>>> = Arc::new(Mutex::new(None));
        let stream2 = stream.clone();
        let busy2 = busy.clone();
        let failure2 = failure.clone();
        let persister = self.persister.clone();
        let mode_shared = self.mode_shared.clone();
        std::thread::spawn(move || {
            let rt = tokio::runtime::Builder::new_multi_thread().enable_all().build().expect("tokio runtime");
            let cfg = xencode_core::config::load_config().unwrap_or_default();
            let initial_mode = *mode_shared.lock().expect("mode shared");
            let mut session = match xencode_core::agent::session_from_config(
                &workspace,
                &cfg,
                initial_mode,
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
            // Shared-database persistence: session + USER row per prompt
            // (with `AgentAttachment` metadata when images are attached),
            // TOOL rows through the agent hook, ASSISTANT row + llm_call
            // record + token totals after the turn, XP in xp_events (the
            // session's xp sink is the DB ledger — see session_from_config).
            let ws_str = workspace.display().to_string();
            if let Some(p) = &persister {
                let p2 = p.clone();
                session.on_tool_result = Some(Box::new(move |name, args, result| {
                    p2.tool_row(name, args, result);
                }));
            }
            for msg in rx.iter() {
                match msg {
                    ChatMsg::Resume { session_id } => {
                        if let Some(p) = &persister {
                            if let Some(resumed) = p.session_by_id(&session_id) {
                                p.resume(&resumed.session);
                                session.messages =
                                    xencode_core::store::chatlog::ChatPersister::rows_to_history(&resumed.rows);
                            }
                        }
                    }
                    ChatMsg::Turn { text, images } => {
                        busy2.store(true, Ordering::SeqCst);
                        stream2.lock().unwrap().clear();
                        *failure2.lock().unwrap() = None;
                        // Mode may have changed since the last turn (chips).
                        let mode = *mode_shared.lock().expect("mode shared");
                        session.permission.mode = mode;
                        let parts: Vec<xencode_core::chat::ImagePart> = images
                            .iter()
                            .map(|p| xencode_core::chat::ImagePart::from_path(p.display().to_string()))
                            .collect();
                        if let Some(p) = &persister {
                            p.user_turn_with_attachments(
                                &ws_str,
                                &session.provider.name,
                                session.provider.model.as_str(),
                                &text,
                                &parts,
                            );
                        }
                        let started = std::time::Instant::now();
                        let stream = stream2.clone();
                        let out = rt.block_on(session.send_with_images(&text, &parts, &mut |d| {
                            stream.lock().unwrap().push_str(d);
                        }, &mut |_| {}));
                        let duration = started.elapsed().as_millis() as i64;
                        if let Some(p) = &persister {
                            match &out {
                                Some(reply) => p.assistant_turn(
                                    &ws_str,
                                    &session.provider.name,
                                    session.provider.model.as_str(),
                                    reply,
                                    session.last_turn_usage,
                                    duration,
                                ),
                                None => {
                                    let failure = session.last_failure.clone().unwrap_or_else(|| "empty reply".into());
                                    p.failed_turn(&session.provider.name, session.provider.model.as_str(), &failure, session.last_turn_usage, duration);
                                }
                            }
                        }
                        if out.is_none() {
                            *failure2.lock().unwrap() = session.last_failure.clone();
                        }
                        busy2.store(false, Ordering::SeqCst);
                    }
                }
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
                if let Some(entry) = self.chat_log.last_mut() {
                    if entry.role == "stream" {
                        entry.text = streamed.clone();
                        if let Some(f) = &failed {
                            entry.text = format!("{}\n! {f}", entry.text);
                        }
                        entry.role = "agent".into();
                        self.status = "idle".into();
                    }
                } else if let Some(f) = &failed {
                    self.chat_log.push(ChatEntry::new("agent", format!("! {f}")));
                }
            }
        }

        // Mode chips + resume row: the single control strip of the chat pane.
        ui.horizontal(|ui| {
            ui.label("Mode:");
            for (mode, label, hint) in [
                (PermissionMode::Confirm, "🛠 Build", "confirm each mutating tool"),
                (PermissionMode::Plan, "🗓 Plan", "research + propose, never mutate"),
                (PermissionMode::Yolo, "⚡ Yolo", "no prompts"),
                (PermissionMode::ReadOnly, "👁 Read-only", "no workspace mutation"),
            ] {
                if ui
                    .selectable_label(self.mode == mode, format!("{label}  "))
                    .on_hover_text(hint)
                    .clicked()
                {
                    self.set_mode(mode);
                }
            }
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                let resume = ui.button("⟲ Resume latest (shared DB)").on_hover_text(
                    "Load the most recent session from aicode_agent_db (phone or PC) and continue it here",
                );
                if resume.clicked() {
                    self.resume_latest();
                }
                let picker = ui
                    .button("🕘 Sessions ▾")
                    .on_hover_text(
                        "Row sync in: pick ANY session from the shared DB (created on the phone or the PC) \
                         and continue its rows here",
                    );
                if picker.clicked() {
                    self.cached_sessions = self
                        .persister
                        .as_ref()
                        .map(|p| {
                            p.recent_sessions(12)
                                .into_iter()
                                .map(|s| {
                                    (
                                        s.id,
                                        s.title,
                                        s.mode,
                                        s.updated_at,
                                        s.total_input_tokens + s.total_output_tokens,
                                    )
                                })
                                .collect()
                        })
                        .unwrap_or_default();
                    self.sessions_open = !self.sessions_open;
                }
            });
        });

        // Shared-DB session picker (row sync in): list recent sessions with title,
        // mode, recency and tokens; clicking one adopts its rows as the transcript.
        if self.sessions_open {
            let sessions = self.cached_sessions.clone();
            egui::Frame::group(ui.style()).show(ui, |ui| {
                ui.set_min_width(ui.available_width());
                if sessions.is_empty() {
                    ui.label("no sessions in the shared database yet");
                } else {
                    for (id, title, mode, updated_at, tokens) in &sessions {
                        let label = format!(
                            "💬 {title} — {mode} · {} · {} tokens",
                            fmt_age(*updated_at),
                            tokens
                        );
                        if ui.selectable_label(false, label).clicked() {
                            let id = id.clone();
                            self.sessions_open = false;
                            self.resume_session(&id);
                        }
                    }
                }
            });
            ui.add_space(4.0);
        }

        if self.mode == PermissionMode::Plan {
            egui::CollapsingHeader::new("🗓 Plan mode — XenCode will research and propose a plan, not edit files")
                .default_open(false)
                .show(ui, |ui| {
                    ui.label(
                        "Read/search/glob/grep, web_fetch, skills and todo_write stay available; writes, edits and \
                         shell commands are denied. Approve the plan, switch back to Build, and say “go ahead” to execute it.",
                    );
                });
        }
        if !self.chat_note.is_empty() {
            ui.label(egui::RichText::new(&self.chat_note).small().color(egui::Color32::from_rgb(150, 200, 255)));
        }
        ui.separator();

        egui::ScrollArea::vertical().stick_to_bottom(true).show(ui, |ui| {
            for entry in &self.chat_log {
                let (name, color) = match entry.role.as_str() {
                    "user" => ("You", egui::Color32::from_rgb(190, 200, 255)),
                    "tool" => ("Tool", egui::Color32::from_rgb(255, 210, 120)),
                    _ => ("XenCode", egui::Color32::from_rgb(140, 220, 170)),
                };
                ui.group(|ui| {
                    ui.label(egui::RichText::new(name).strong().color(color));
                    for img in &entry.images {
                        thumbnail(ui, &mut self.thumbnails, img);
                    }
                    ui.label(&entry.text);
                });
            }
        });
        ui.separator();
        egui::TopBottomPanel::bottom("chat_input").exact_height(96.0).show_inside(ui, |ui| {
            // Staged attachments: thumbnails with a remove button each.
            if !self.pending_images.is_empty() {
                ui.horizontal(|ui| {
                    ui.label("Attached:");
                    let staged = self.pending_images.clone();
                    let mut keep: Vec<PathBuf> = vec![];
                    for img in &staged {
                        ui.group(|ui| {
                            thumbnail(ui, &mut self.thumbnails, img);
                            if ui.small_button("✕").on_hover_text("remove").clicked() {
                                // dropped — not pushed to `keep`
                            } else {
                                keep.push(img.clone());
                            }
                        });
                    }
                    self.pending_images = keep;
                });
            }
            ui.horizontal(|ui| {
                if ui.button("📎").on_hover_text("Attach images (png / jpg / gif / webp / bmp, max 8 MiB each)").clicked() {
                    if let Some(paths) = rfd::FileDialog::new()
                        .add_filter("Images", &["png", "jpg", "jpeg", "gif", "webp", "bmp"])
                        .pick_files()
                    {
                        self.pending_images.extend(paths);
                    }
                }
                let send = ui.add(egui::TextEdit::singleline(&mut self.chat_input).hint_text("Ask XenCode to build something…  (Enter to send)").desired_width(ui.available_width() - 90.0));
                let send_clicked = ui.button("Send ▶").clicked() || (send.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter)));
                if send_clicked && (!self.chat_input.trim().is_empty() || !self.pending_images.is_empty()) {
                    self.ensure_worker();
                    let prompt = if self.chat_input.trim().is_empty() {
                        "(see attached images)".to_string()
                    } else {
                        self.chat_input.trim().to_string()
                    };
                    let images = std::mem::take(&mut self.pending_images);
                    self.chat_input.clear();
                    let mut entry = ChatEntry::new("user", prompt.clone());
                    entry.images = images.clone();
                    self.chat_log.push(entry);
                    self.chat_log.push(ChatEntry::new("stream", ""));
                    self.status = "thinking…".into();
                    if let Some(w) = &self.worker {
                        let _ = w.tx.send(ChatMsg::Turn { text: prompt, images });
                    }
                }
            });
        });
    }

    /// Row sync in: load the latest shared-DB session into the transcript and
    /// bind the agent to it, so the conversation continues on the same rows.
    fn resume_latest(&mut self) {
        let Some(p) = self.persister.clone() else {
            self.chat_note = "shared database unavailable — chat works, but nothing is persisted".into();
            return;
        };
        let Some(resumed) = p.latest_session() else {
            self.chat_note = "no sessions in the shared database yet".into();
            return;
        };
        self.bind_resumed(resumed);
    }

    /// Row sync in: adopt a specific shared-DB session (picked in the Sessions
    /// list) — its rows become the transcript and the next turns append to the
    /// same `chat_sessions`/`agent_messages` rows, on phone or PC.
    fn resume_session(&mut self, id: &str) {
        let Some(p) = self.persister.clone() else {
            self.chat_note = "shared database unavailable — chat works, but nothing is persisted".into();
            return;
        };
        let Some(resumed) = p.session_by_id(id) else {
            self.chat_note = format!("session `{id}` not found in the shared database");
            return;
        };
        self.bind_resumed(resumed);
    }

    /// Adopts a loaded session: replay its rows into the chat log and hand the
    /// worker the session id so persistence continues on the same rows.
    fn bind_resumed(&mut self, resumed: xencode_core::store::chatlog::ResumedSession) {
        self.chat_log.clear();
        for row in &resumed.rows {
            if row.is_compacted || row.is_context_summary || row.is_compaction_marker || row.is_context_excluded {
                continue;
            }
            match row.role.as_str() {
                "USER" => {
                    let mut entry = ChatEntry::new("user", row.content.clone());
                    entry.images = xencode_core::store::chatlog::attachments_from_json(
                        row.attachments_json.as_deref(),
                    )
                    .into_iter()
                    .map(|part| PathBuf::from(part.path))
                    .collect();
                    self.chat_log.push(entry);
                }
                "ASSISTANT" if row.tool_calls_json.is_none() && !row.content.trim().is_empty() => {
                    self.chat_log.push(ChatEntry::new("agent", row.content.clone()));
                }
                "TOOL" => {
                    let name = row.tool_name.clone().unwrap_or_else(|| "tool".into());
                    self.chat_log.push(ChatEntry::new("tool", format!("{name}: {}", row.content)));
                }
                _ => {}
            }
        }
        self.ensure_worker();
        if let Some(w) = &self.worker {
            let _ = w.tx.send(ChatMsg::Resume { session_id: resumed.session.id.clone() });
        }
        self.chat_note = format!(
            "resumed `{}` ({} rows, {} tokens) from the shared DB",
            resumed.session.title,
            resumed.rows.len(),
            resumed.session.total_input_tokens + resumed.session.total_output_tokens
        );
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
        // XP comes from the shared database (xp_events in aicode_agent_db —
        // the same table the Android app writes); the legacy JSON ledger is
        // imported on first use. Falls back to the file ledger offline.
        let now = xencode_core::now_ms();
        match xencode_core::store::xp::DbXpLedger::open_default() {
            Ok(xp) => {
                ui.monospace(xencode_core::telemetry::XpSink::summary(&xp, now).render());
                if let Some(p) = &self.persister {
                    if let Some((calls, errors, in_tok, out_tok)) = p.llm_totals() {
                        ui.monospace(format!(
                            "tokens (shared DB): in {in_tok} · out {out_tok} · {calls} llm calls ({errors} errored)"
                        ));
                    }
                }
                ui.monospace(format!("shared DB: {}", xencode_core::store::SharedStore::default_db_path().display()));
            }
            Err(_) => {
                let xp = xencode_core::telemetry::XpLedger::default();
                ui.monospace(xp.render_summary(now));
                ui.monospace("! shared database unavailable — showing the legacy file ledger");
            }
        }
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

/// Renders one image thumbnail (max 320 px wide), decoding and caching the
/// texture on first use. Undecodable/missing files are silently skipped —
/// the agent turn still works, only the preview is absent.
fn thumbnail(
    ui: &mut egui::Ui,
    cache: &mut HashMap<String, Option<egui::TextureHandle>>,
    path: &std::path::Path,
) {
    let key = path.display().to_string();
    if !cache.contains_key(&key) {
        let tex = image::open(path).ok().map(|img| {
            let rgba = img.to_rgba8();
            let (w, h) = rgba.dimensions();
            let color = egui::ColorImage::from_rgba_unmultiplied([w as usize, h as usize], &rgba);
            ui.ctx().load_texture(format!("img:{key}"), color, egui::TextureOptions::LINEAR)
        });
        cache.insert(key.clone(), tex);
    }
    if let Some(Some(tex)) = cache.get(&key) {
        ui.add(
            egui::Image::from_texture(egui::load::SizedTexture::new(tex.id(), tex.size_vec2()))
                .max_width(320.0)
                .rounding(6.0),
        );
    }
}

/// Renders a session's `updated_at` (Room stores epoch millis; older rows may
/// carry seconds) as a compact relative age for the Sessions picker.
fn fmt_age(value: i64) -> String {
    if value <= 0 {
        return "—".into();
    }
    let millis = if value > 1_000_000_000_000 { value } else { value * 1000 };
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(millis);
    let secs = ((now - millis) / 1000).max(0);
    if secs < 60 {
        format!("{secs}s ago")
    } else if secs < 3600 {
        format!("{}m ago", secs / 60)
    } else if secs < 86_400 {
        format!("{}h ago", secs / 3600)
    } else {
        format!("{}d ago", secs / 86_400)
    }
}
