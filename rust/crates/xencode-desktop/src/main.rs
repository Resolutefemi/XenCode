//! XenCode Desktop — Rust GUI for Windows, macOS and Linux.
//!
//! The UI is a faithful port of the DeepSeek Harness (`dsh`) Web UI design
//! system — same surfaces, type scale, radii, spacing and interaction
//! patterns (see `theme.rs`); only the brand glyph differs (XenCode logo).
//!
//! Layout: a sidebar (brand, New chat, shared-DB conversations, panels,
//! settings) and a centered conversation column with a dsh-style composer
//! (attachments, mode chips, round send button). Panes: Chat (agent, plan
//! mode, image attachments, session resume), Goals (DAG + swimlanes),
//! Telemetry (XP / spec radar), Automation (.mjs runner), Decisions (cards),
//! Events (sync log), Settings.
//!
//! Screenshot tour: with `XENCODE_UI_TOUR=<dir>` the app walks every page
//! and writes `<dir>/marker.txt` so an external capture can screenshot each
//! page (used by scripts/capture_ui_pages.py for docs/screenshots/pc/).

mod theme;

use eframe::egui;
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{mpsc, Arc, Mutex};
use xencode_core::permissions::PermissionMode;

use theme::*;

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
    let tour = std::env::var("XENCODE_UI_TOUR").map(PathBuf::from).ok();
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
            theme::install(cc);
            let mut app = XenApp::new(workspace);
            app.init_logo(&cc.egui_ctx);
            app.tour = tour.map(|dir| Tour { dir, step: 0, entered: std::time::Instant::now(), written: false });
            Ok(Box::new(app))
        }),
    )
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

impl Pane {
    fn label(self) -> &'static str {
        match self {
            Pane::Chat => "Chat",
            Pane::Goals => "Goals",
            Pane::Telemetry => "Telemetry",
            Pane::Automation => "Automation",
            Pane::Decisions => "Decisions",
            Pane::Events => "Events",
            Pane::Settings => "Settings",
        }
    }
    fn accent(self) -> egui::Color32 {
        match self {
            Pane::Chat => BRAND_400,
            Pane::Goals => BRAND_500,
            Pane::Telemetry => SUCCESS,
            Pane::Automation => WARN,
            Pane::Decisions => VIOLET,
            Pane::Events => ERROR,
            Pane::Settings => TEXT_3,
        }
    }
    fn from_index(i: usize) -> Option<Self> {
        Some(match i {
            0 => Pane::Chat,
            1 => Pane::Goals,
            2 => Pane::Telemetry,
            3 => Pane::Automation,
            4 => Pane::Decisions,
            5 => Pane::Events,
            6 => Pane::Settings,
            _ => return None,
        })
    }
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
#[derive(Clone)]
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

/// Screenshot tour state (env `XENCODE_UI_TOUR`): walks every page and
/// publishes a marker file per page for the external capture script.
struct Tour {
    dir: PathBuf,
    step: usize,
    entered: std::time::Instant,
    written: bool,
}

const TOUR_STEPS: [&str; 8] = [
    "01_home",
    "02_chat",
    "03_goals",
    "04_telemetry",
    "05_automation",
    "06_decisions",
    "07_events",
    "08_settings",
];

struct XenApp {
    workspace: PathBuf,
    pane: Pane,
    chat_input: String,
    chat_log: Vec<ChatEntry>,
    /// Images staged for the next message.
    pending_images: Vec<PathBuf>,
    /// Decoded image thumbnails, memoized by path (None = failed to decode).
    thumbnails: HashMap<String, Option<egui::TextureHandle>>,
    /// Brand texture (window icon decoded once for in-app use).
    logo: Option<egui::TextureHandle>,
    /// Current permission mode; mirrored to the worker through `mode_shared`.
    mode: PermissionMode,
    mode_shared: Arc<Mutex<PermissionMode>>,
    worker: Option<ChatWorker>,
    /// Busy→idle edge detector (session refresh + status line).
    was_busy: bool,
    /// Shared-database writer (aicode_agent_db); None when the DB cannot be
    /// opened — the chat keeps working without persistence.
    persister: Option<std::sync::Arc<xencode_core::store::chatlog::ChatPersister>>,
    automation_script: String,
    automation_out: String,
    settings_note: String,
    status: String,
    chat_note: String,
    /// Row-sync session picker state: sidebar conversation rows (id, title,
    /// mode, updated_at, tokens) refreshed on start and after each turn.
    cached_sessions: Vec<(String, String, String, i64, i64)>,
    active_session_id: Option<String>,
    active_session_title: Option<String>,
    /// Expanded tool cards in the transcript (index → open).
    open_tools: std::collections::HashSet<u64>,
    tour: Option<Tour>,
}

impl XenApp {
    fn new(workspace: PathBuf) -> Self {
        let persister = xencode_core::store::chatlog::ChatPersister::open_default();
        let cached_sessions = persister
            .as_ref()
            .map(|p| {
                p.recent_sessions(30)
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
        Self {
            workspace,
            pane: Pane::Chat,
            chat_input: String::new(),
            chat_log: vec![],
            pending_images: vec![],
            thumbnails: HashMap::new(),
            logo: None,
            mode: PermissionMode::Yolo,
            mode_shared: Arc::new(Mutex::new(PermissionMode::Yolo)),
            worker: None,
            was_busy: false,
            persister,
            automation_script: String::new(),
            automation_out: String::new(),
            settings_note: String::new(),
            status: "Idle".into(),
            chat_note: String::new(),
            cached_sessions,
            active_session_id: None,
            active_session_title: None,
            open_tools: std::collections::HashSet::new(),
            tour: None,
        }
    }

    fn init_logo(&mut self, ctx: &egui::Context) {
        if let Some(img) = image::load_from_memory(ICON_PNG).ok() {
            let rgba = img.to_rgba8();
            let (w, h) = rgba.dimensions();
            let color = egui::ColorImage::from_rgba_unmultiplied([w as usize, h as usize], &rgba);
            self.logo = Some(ctx.load_texture("xencode-logo", color, egui::TextureOptions::LINEAR));
        }
    }

    fn refresh_sessions(&mut self) {
        if let Some(p) = &self.persister {
            self.cached_sessions = p
                .recent_sessions(30)
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
                .collect();
        }
    }

    /// Starts a fresh conversation: clears the transcript and rebinds the
    /// worker so the next turn opens a new shared-DB session.
    fn new_chat(&mut self) {
        self.chat_log.clear();
        self.pending_images.clear();
        self.open_tools.clear();
        self.active_session_id = None;
        self.active_session_title = None;
        self.chat_note.clear();
        self.status = "Idle".into();
        self.worker = None; // dropping the sender ends the old worker loop
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

    fn tick_tour(&mut self, ctx: &egui::Context) {
        if self.tour.is_none() {
            return;
        }
        ctx.request_repaint_after(std::time::Duration::from_millis(33));
        enum Action {
            None,
            Publish(usize),
            PublishAndSeed(usize),
            Finish,
        }
        let dwell = std::time::Duration::from_millis(1800);
        let action = {
            let t = self.tour.as_mut().unwrap();
            if !t.written {
                if t.entered.elapsed() < std::time::Duration::from_millis(900) {
                    Action::None // let the first frame render before publishing
                } else {
                    t.written = true;
                    t.entered = std::time::Instant::now();
                    Action::Publish(0)
                }
            } else if t.entered.elapsed() < dwell {
                Action::None
            } else if t.step + 1 >= TOUR_STEPS.len() {
                Action::Finish
            } else {
                t.step += 1;
                t.entered = std::time::Instant::now();
                if TOUR_STEPS[t.step] == "02_chat" {
                    Action::PublishAndSeed(t.step)
                } else {
                    Action::Publish(t.step)
                }
            }
        };
        let dir = self.tour.as_ref().unwrap().dir.clone();
        match action {
            Action::None => {}
            Action::Publish(step) => {
                self.apply_tour_step(TOUR_STEPS[step], false);
                let _ = std::fs::write(dir.join("marker.txt"), TOUR_STEPS[step]);
            }
            Action::PublishAndSeed(step) => {
                self.apply_tour_step(TOUR_STEPS[step], true);
                let _ = std::fs::write(dir.join("marker.txt"), TOUR_STEPS[step]);
            }
            Action::Finish => {
                let _ = std::fs::write(dir.join("marker.txt"), "DONE");
                let t = self.tour.as_mut().unwrap();
                t.step = TOUR_STEPS.len() - 1;
                t.entered = std::time::Instant::now() + dwell; // park here
            }
        }
    }

    fn apply_tour_step(&mut self, step: &str, seed: bool) {
        match step {
            "01_home" => {
                self.pane = Pane::Chat;
                self.chat_log.clear();
                self.open_tools.clear();
                self.active_session_id = None;
                self.active_session_title = None;
            }
            "02_chat" => {
                self.pane = Pane::Chat;
                if seed {
                    self.seed_demo_transcript();
                }
            }
            _ => {
                self.pane = match step {
                    "03_goals" => Pane::Goals,
                    "04_telemetry" => Pane::Telemetry,
                    "05_automation" => Pane::Automation,
                    "06_decisions" => Pane::Decisions,
                    "07_events" => Pane::Events,
                    _ => Pane::Settings,
                };
            }
        }
    }

    /// In-memory demo transcript for the screenshot tour (no DB writes).
    fn seed_demo_transcript(&mut self) {
        self.chat_log.clear();
        self.chat_log.push(ChatEntry::new(
            "user",
            "Refactor the auth module to use the shared session store — keep the public API stable and add tests.",
        ));
        self.chat_log.push(ChatEntry::new("tool", "ast.symbols: mapped 14 call sites across auth/*.rs"));
        self.chat_log.push(ChatEntry::new("tool", "git.worktreeCreate: xencode/plan-auth-refactor"));
        self.chat_log.push(ChatEntry::new(
            "agent",
            "Here is the plan:\n\n1. Introduce SessionStore behind the existing trait — no public API changes.\n2. Migrate auth::legacy to the store in three call sites (login, refresh, logout).\n3. Wire the shared-DB persister so the same rows sync to the mobile app.\n\nTests: 12 added, all green — cargo test exit 0.",
        ));
    }
}

impl eframe::App for XenApp {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        // Keyboard page switching (dsh-style shortcuts): Ctrl+1..7.
        for (i, key) in [
            egui::Key::Num1,
            egui::Key::Num2,
            egui::Key::Num3,
            egui::Key::Num4,
            egui::Key::Num5,
            egui::Key::Num6,
            egui::Key::Num7,
        ]
        .into_iter()
        .enumerate()
        {
            if ctx.input(|i| i.modifiers.ctrl && i.key_pressed(key)) {
                if let Some(pane) = Pane::from_index(i) {
                    self.pane = pane;
                }
            }
        }

        self.tick_tour(ctx);

        // Sidebar: brand, New chat, shared-DB conversations, panels, settings.
        egui::SidePanel::left("sidebar")
            .exact_width(264.0)
            .frame(egui::Frame::none().fill(SIDEBAR))
            .show(ctx, |ui| {
                egui::TopBottomPanel::bottom("sb_footer")
                    .show_separator_line(false)
                    .frame(egui::Frame::none().fill(SIDEBAR).inner_margin(egui::Margin::same(8.0)))
                    .show_inside(ui, |ui| {
                        ui.separator();
                        ui.add_space(4.0);
                        // Settings row (dsh keeps it in the sidebar footer).
                        if nav_row(ui, self.pane == Pane::Settings, "Settings", Pane::Settings.accent()).clicked() {
                            self.pane = Pane::Settings;
                        }
                        ui.label(
                            egui::RichText::new(format!(
                                "{} · v{}",
                                self.workspace.display(),
                                env!("CARGO_PKG_VERSION")
                            ))
                            .size(10.5)
                            .color(TEXT_DIM),
                        );
                    });

                egui::TopBottomPanel::bottom("sb_panels")
                    .show_separator_line(false)
                    .frame(egui::Frame::none().fill(SIDEBAR).inner_margin(egui::Margin::same(8.0)))
                    .show_inside(ui, |ui| {
                        ui.add_space(2.0);
                        section(ui, "PANELS");
                        ui.add_space(2.0);
                        for pane in [Pane::Goals, Pane::Telemetry, Pane::Automation, Pane::Decisions, Pane::Events] {
                            if nav_row(ui, self.pane == pane, pane.label(), pane.accent()).clicked() {
                                self.pane = pane;
                            }
                        }
                    });

                egui::CentralPanel::default()
                    .frame(egui::Frame::none().fill(SIDEBAR).inner_margin(egui::Margin::same(8.0)))
                    .show_inside(ui, |ui| {
                        // Brand row: XenCode glyph + name (logo is the only
                        // visual difference from dsh, per brand guidelines).
                        ui.add_space(4.0);
                        ui.horizontal(|ui| {
                            if let Some(tex) = &self.logo {
                                ui.add(
                                    egui::Image::from_texture(egui::load::SizedTexture::new(tex.id(), egui::vec2(24.0, 24.0)))
                                        .rounding(6.0),
                                );
                            }
                            ui.label(egui::RichText::new("XenCode").size(15.0).color(TEXT_1));
                        });
                        ui.add_space(12.0);

                        // New chat — the dsh header action.
                        let (rect, resp) =
                            ui.allocate_exact_size(egui::vec2(ui.available_width(), 32.0), egui::Sense::click());
                        let fill = if resp.hovered() { HOVER } else { egui::Color32::TRANSPARENT };
                        ui.painter().rect_filled(rect, R_SM, fill);
                        ui.painter().text(
                            egui::pos2(rect.left() + 16.0, rect.center().y),
                            egui::Align2::LEFT_CENTER,
                            "+",
                            egui::FontId::proportional(16.0),
                            TEXT_1,
                        );
                        ui.painter().text(
                            egui::pos2(rect.left() + 32.0, rect.center().y),
                            egui::Align2::LEFT_CENTER,
                            "New chat",
                            egui::FontId::proportional(13.5),
                            TEXT_1,
                        );
                        if resp.clicked() {
                            self.new_chat();
                        }
                        ui.add_space(12.0);

                        section(ui, "CONVERSATIONS");
                        ui.add_space(4.0);
                        egui::ScrollArea::vertical().id_salt("sessions").auto_shrink([false, true]).show(ui, |ui| {
                            if self.cached_sessions.is_empty() {
                                ui.label(
                                    egui::RichText::new("No conversations yet.\nChats are stored in the shared database and sync with the mobile app.")
                                        .size(11.5)
                                        .color(TEXT_4),
                                );
                            }
                            let sessions = self.cached_sessions.clone();
                            for (id, title, mode, updated_at, tokens) in &sessions {
                                let selected = self.active_session_id.as_deref() == Some(id.as_str());
                                let sub = format!("{mode} · {} · {tokens} tok", fmt_age(*updated_at));
                                if session_row(ui, selected, title, &sub).clicked() {
                                    let id = id.clone();
                                    self.resume_session(&id);
                                }
                            }
                        });
                    });
            });

        // Center column.
        match self.pane {
            Pane::Chat => self.chat_page(ctx),
            pane => self.panel_page(ctx, pane),
        }

        // Live chat stream updates → repaint while busy; refresh the
        // conversation list on the busy→idle edge.
        let busy_now = self.worker.as_ref().map(|w| w.busy.load(Ordering::SeqCst)).unwrap_or(false);
        if busy_now {
            ctx.request_repaint();
        }
        if self.was_busy && !busy_now {
            self.refresh_sessions();
        }
        self.was_busy = busy_now;
    }
}

impl XenApp {
    // ── Chat ───────────────────────────────────────────────────────────────

    fn chat_page(&mut self, ctx: &egui::Context) {
        egui::TopBottomPanel::bottom("composer")
            .frame(egui::Frame::none().fill(BG_BASE))
            .show(ctx, |ui| self.composer(ui));

        egui::CentralPanel::default()
            .frame(egui::Frame::none().fill(BG_BASE))
            .show(ctx, |ui| {
                // Conversation header: title + live status (dsh top row).
                ui.horizontal(|ui| {
                    ui.add_space(16.0);
                    ui.add_space(8.0);
                    let title = self
                        .active_session_title
                        .clone()
                        .unwrap_or_else(|| "New conversation".into());
                    ui.label(egui::RichText::new(title).size(14.5).color(TEXT_1));
                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                        ui.add_space(16.0);
                        let (dot_color, label) = if self.status == "thinking…" {
                            (WARN, "Working…".to_string())
                        } else {
                            (SUCCESS, self.status.clone())
                        };
                        let (r, _) = ui.allocate_exact_size(egui::vec2(8.0, 8.0), egui::Sense::hover());
                        ui.painter().circle_filled(r.center(), 3.5, dot_color);
                        ui.label(egui::RichText::new(label).size(12.0).color(TEXT_4));
                    });
                });
                ui.separator();
                ui.add_space(2.0);

                // Plan-mode banner (dsh info surface).
                if self.mode == PermissionMode::Plan {
                    ui.horizontal(|ui| {
                        ui.add_space(16.0);
                        let avail = ui.available_width() - 32.0;
                        egui::Frame::none()
                            .fill(BRAND_800)
                            .rounding(egui::Rounding::same(R_MD))
                            .inner_margin(egui::Margin::symmetric(14.0, 10.0))
                            .show(ui, |ui| {
                                ui.set_min_width(avail);
                                ui.label(egui::RichText::new("Plan mode").size(13.0).color(TEXT_1).strong());
                                ui.label(
                                    egui::RichText::new(
                                        "XenCode will research and propose a plan, not edit files. Read/search, web fetch, skills and todos stay available. Approve the plan, switch back to Build, and say “go ahead” to execute it.",
                                    )
                                    .size(12.0)
                                    .color(TEXT_2),
                                );
                            });
                    });
                    ui.add_space(4.0);
                }

                let busy = self.worker.as_ref().map(|w| w.busy.load(Ordering::SeqCst)).unwrap_or(false);
                let streamed = self
                    .worker
                    .as_ref()
                    .map(|w| w.stream.lock().unwrap().clone())
                    .unwrap_or_default();

                // Flush the streamed turn into the log when the worker idles.
                if !busy {
                    let failed = self.worker.as_ref().and_then(|w| w.failure.lock().unwrap().clone());
                    if let Some(entry) = self.chat_log.last_mut() {
                        if entry.role == "stream" {
                            entry.text = streamed.clone();
                            if let Some(f) = &failed {
                                entry.text = format!("{}\n! {f}", entry.text);
                            }
                            entry.role = "agent".into();
                            self.status = "Idle".into();
                        }
                    } else if let Some(f) = &failed {
                        self.chat_log.push(ChatEntry::new("agent", format!("! {f}")));
                    }
                }

                egui::ScrollArea::vertical()
                    .id_salt("chat")
                    .stick_to_bottom(true)
                    .auto_shrink([false, true])
                    .show(ui, |ui| {
                        if self.chat_log.is_empty() {
                            self.empty_state(ui);
                        } else {
                            let max_w = COLUMN.min(ui.available_width() - 32.0);
                            ui.with_layout(egui::Layout::top_down(egui::Align::Center), |ui| {
                                ui.set_max_width(max_w);
                                ui.add_space(18.0);
                                let entries = self.chat_log.clone();
                                for (idx, entry) in entries.iter().enumerate() {
                                    match entry.role.as_str() {
                                        "user" => self.user_bubble(ui, entry),
                                        "tool" => self.tool_card(ui, idx as u64, entry),
                                        "stream" => {
                                            ui.with_layout(
                                                egui::Layout::left_to_right(egui::Align::TOP),
                                                |ui| {
                                                    self.agent_avatar(ui);
                                                    let dots = if busy { " …" } else { "" };
                                                    ui.label(
                                                        egui::RichText::new(format!("{}{dots}", entry.text))
                                                            .size(14.0)
                                                            .color(if busy { TEXT_2 } else { TEXT_1 }),
                                                    );
                                                },
                                            );
                                        }
                                        _ => self.agent_message(ui, entry),
                                    }
                                    ui.add_space(14.0);
                                }
                                ui.add_space(10.0);
                            });
                        }
                    });
            });
    }

    fn agent_avatar(&self, ui: &mut egui::Ui) {
        if let Some(tex) = &self.logo {
            ui.add(
                egui::Image::from_texture(egui::load::SizedTexture::new(tex.id(), egui::vec2(22.0, 22.0)))
                    .rounding(6.0),
            );
            ui.add_space(8.0);
        }
    }

    fn user_bubble(&mut self, ui: &mut egui::Ui, entry: &ChatEntry) {
        let max_text_w = (ui.available_width() * 0.72).max(280.0);
        let galley = ui.fonts(|f| {
            f.layout(entry.text.clone(), egui::FontId::proportional(14.0), TEXT_1, max_text_w)
        });
        let pad_x = 14.0;
        let pad_y = 10.0;
        let w = (galley.size().x + pad_x * 2.0).min(max_text_w + pad_x * 2.0);
        ui.with_layout(egui::Layout::top_down(egui::Align::Max), |ui| {
            ui.allocate_ui_with_layout(
                egui::vec2(w, 20.0),
                egui::Layout::top_down(egui::Align::LEFT),
                |ui| {
                    egui::Frame::none()
                        .fill(LAYER_2)
                        .rounding(egui::Rounding::same(R_LG))
                        .inner_margin(egui::Margin::symmetric(pad_x, pad_y))
                        .show(ui, |ui| {
                            if !entry.images.is_empty() {
                                ui.horizontal(|ui| {
                                    for img in &entry.images {
                                        self.thumbnail(ui, img, 96.0);
                                    }
                                });
                            }
                            ui.add(egui::Label::new(egui::WidgetText::Galley(galley.clone())));
                        });
                },
            );
        });
    }

    fn agent_message(&mut self, ui: &mut egui::Ui, entry: &ChatEntry) {
        ui.with_layout(egui::Layout::left_to_right(egui::Align::TOP), |ui| {
            self.agent_avatar(ui);
            ui.vertical(|ui| {
                if !entry.images.is_empty() {
                    ui.horizontal(|ui| {
                        for img in &entry.images {
                            self.thumbnail(ui, img, 220.0);
                        }
                    });
                }
                ui.label(egui::RichText::new(&entry.text).size(14.0).color(TEXT_1));
            });
        });
    }

    /// Collapsible tool-call card (dsh turn-trigger styling).
    fn tool_card(&mut self, ui: &mut egui::Ui, idx: u64, entry: &ChatEntry) {
        let open = self.open_tools.contains(&idx);
        let (name, body) = match entry.text.split_once(": ") {
            Some((n, b)) => (n.to_string(), b.to_string()),
            None => (entry.text.clone(), String::new()),
        };
        let w = (ui.available_width() * 0.86).min(COLUMN).max(300.0);
        ui.allocate_ui_with_layout(egui::vec2(w, 20.0), egui::Layout::top_down(egui::Align::LEFT), |ui| {
            egui::Frame::none()
                .fill(LAYER_2)
                .rounding(egui::Rounding::same(R_SM))
                .stroke(egui::Stroke::new(1.0_f32, BORDER_1))
                .inner_margin(egui::Margin::symmetric(10.0, 7.0))
                .show(ui, |ui| {
                    ui.with_layout(egui::Layout::left_to_right(egui::Align::Center), |ui| {
                        // Painted chevron (open/closed).
                        let (r, resp) = ui.allocate_exact_size(egui::vec2(14.0, 14.0), egui::Sense::click());
                        let c = r.center();
                        let color = TEXT_3;
                        if open {
                            let pts = [egui::pos2(c.x - 4.0, c.y - 1.5), egui::pos2(c.x + 4.0, c.y - 1.5), egui::pos2(c.x, c.y + 3.5)];
                            ui.painter().add(egui::Shape::convex_polygon(pts.to_vec(), color, egui::Stroke::NONE));
                        } else {
                            let pts = [egui::pos2(c.x - 1.5, c.y - 4.0), egui::pos2(c.x + 3.5, c.y), egui::pos2(c.x - 1.5, c.y + 4.0)];
                            ui.painter().add(egui::Shape::convex_polygon(pts.to_vec(), color, egui::Stroke::NONE));
                        }
                        if resp.clicked() {
                            if open {
                                self.open_tools.remove(&idx);
                            } else {
                                self.open_tools.insert(idx);
                            }
                        }
                        let (r, _) = ui.allocate_exact_size(egui::vec2(7.0, 7.0), egui::Sense::hover());
                        ui.painter().circle_filled(r.center(), 2.5, SUCCESS);
                        ui.label(
                            egui::RichText::new(format!("{} {}", name, if open { "— done" } else { "" }))
                                .size(12.5)
                                .color(TEXT_3),
                        );
                    });
                    if open && !body.is_empty() {
                        ui.add_space(4.0);
                        ui.label(egui::RichText::new(body).size(12.0).color(TEXT_2).monospace());
                    }
                });
        });
    }

    /// The dsh empty state: brand glyph, greeting and suggestion chips.
    fn empty_state(&mut self, ui: &mut egui::Ui) {
        let avail_h = ui.available_height();
        ui.with_layout(egui::Layout::top_down(egui::Align::Center), |ui| {
            ui.add_space((avail_h * 0.17).max(40.0));
            if let Some(tex) = &self.logo {
                ui.add(
                    egui::Image::from_texture(egui::load::SizedTexture::new(tex.id(), egui::vec2(64.0, 64.0)))
                        .rounding(16.0),
                );
            }
            ui.add_space(18.0);
            ui.label(egui::RichText::new("Where should we start?").size(22.0).color(TEXT_1).strong());
            ui.add_space(8.0);
            ui.set_max_width(520.0);
            ui.label(
                egui::RichText::new(
                    "XenCode is an autonomous coding agent. Describe a goal, attach images, or resume a conversation from the sidebar — everything syncs with the mobile app through the shared database.",
                )
                .size(13.0)
                .color(TEXT_3),
            );
            ui.add_space(20.0);
            ui.set_max_width(620.0);
            ui.horizontal(|ui| {
                for suggestion in [
                    "Explore this codebase and write a plan",
                    "Fix the failing tests",
                    "Add a feature end-to-end",
                ] {
                    let w = ui
                        .fonts(|f| {
                            f.layout_no_wrap(suggestion.to_string(), egui::FontId::proportional(12.5), TEXT_2)
                                .size()
                                .x
                        })
                        + 26.0;
                    let (rect, resp) = ui.allocate_exact_size(egui::vec2(w, 30.0), egui::Sense::click());
                    let fill = if resp.hovered() { HOVER } else { LAYER_2 };
                    ui.painter().rect_filled(rect, 15.0, fill);
                    ui.painter().rect_stroke(rect, 15.0, egui::Stroke::new(1.0_f32, BORDER_2));
                    ui.painter().text(
                        rect.center(),
                        egui::Align2::CENTER_CENTER,
                        suggestion,
                        egui::FontId::proportional(12.5),
                        TEXT_2,
                    );
                    if resp.clicked() {
                        self.chat_input = suggestion.to_string();
                    }
                }
            });
        });
    }

    fn composer(&mut self, ui: &mut egui::Ui) {
        let busy = self.worker.as_ref().map(|w| w.busy.load(Ordering::SeqCst)).unwrap_or(false);
        egui::Frame::none()
            .fill(BG_BASE)
            .inner_margin(egui::Margin::symmetric(0.0, 8.0))
            .show(ui, |ui| {
                let max_w = COLUMN.min(ui.available_width() - 32.0);
                ui.with_layout(egui::Layout::top_down(egui::Align::Center), |ui| {
                    ui.set_max_width(max_w);
                    if !self.chat_note.is_empty() {
                        ui.label(egui::RichText::new(&self.chat_note).size(12.0).color(BRAND_400));
                        ui.add_space(4.0);
                    }
                    egui::Frame::none()
                        .fill(LAYER_2)
                        .rounding(egui::Rounding::same(R_XL))
                        .stroke(egui::Stroke::new(1.0_f32, BORDER_2))
                        .inner_margin(egui::Margin::same(12.0))
                        .show(ui, |ui| {
                            // Staged attachments row.
                            if !self.pending_images.is_empty() {
                                ui.horizontal(|ui| {
                                    let staged = self.pending_images.clone();
                                    let mut keep: Vec<PathBuf> = vec![];
                                    for img in &staged {
                                        egui::Frame::none()
                                            .fill(LAYER_3)
                                            .rounding(egui::Rounding::same(R_SM))
                                            .inner_margin(egui::Margin::same(4.0))
                                            .show(ui, |ui| {
                                                ui.vertical(|ui| {
                                                    self.thumbnail(ui, img, 56.0);
                                                    if ui
                                                        .add(egui::Button::new(
                                                            egui::RichText::new("×").size(11.0).color(TEXT_3),
                                                        ))
                                                        .on_hover_text("Remove")
                                                        .clicked()
                                                    {
                                                        // dropped — not pushed to `keep`
                                                    } else {
                                                        keep.push(img.clone());
                                                    }
                                                });
                                            });
                                    }
                                    self.pending_images = keep;
                                });
                                ui.add_space(6.0);
                            }

                            let edit = ui.add(
                                egui::TextEdit::multiline(&mut self.chat_input)
                                    .hint_text("How can XenCode help today?")
                                    .desired_width(ui.available_width())
                                    .desired_rows(1)
                                    .frame(false)
                                    .font(egui::TextStyle::Body),
                            );
                            if edit.lost_focus()
                                && ui.input(|i| i.key_pressed(egui::Key::Enter) && !i.modifiers.shift)
                            {
                                self.send_current();
                            }

                            ui.add_space(6.0);
                            ui.separator();
                            ui.add_space(6.0);

                            ui.horizontal(|ui| {
                                // Attach images (icon + label in one control).
                                let (rect, resp) =
                                    ui.allocate_exact_size(egui::vec2(74.0, 26.0), egui::Sense::click());
                                let fill = if resp.hovered() { HOVER } else { egui::Color32::TRANSPARENT };
                                ui.painter().rect_filled(rect, R_SM, fill);
                                ui.painter().text(
                                    egui::pos2(rect.left() + 11.0, rect.center().y),
                                    egui::Align2::LEFT_CENTER,
                                    "+",
                                    egui::FontId::proportional(15.0),
                                    TEXT_2,
                                );
                                ui.painter().text(
                                    egui::pos2(rect.left() + 24.0, rect.center().y),
                                    egui::Align2::LEFT_CENTER,
                                    "Attach",
                                    egui::FontId::proportional(12.0),
                                    TEXT_3,
                                );
                                if resp.clicked() {
                                    if let Some(paths) = rfd::FileDialog::new()
                                        .add_filter("Images", &["png", "jpg", "jpeg", "gif", "webp", "bmp"])
                                        .pick_files()
                                    {
                                        self.pending_images.extend(paths);
                                    }
                                }
                                ui.add_space(8.0);
                                // Mode chips (dsh approval presets).
                                for (mode, label, dot) in [
                                    (PermissionMode::Confirm, "Build", BRAND_400),
                                    (PermissionMode::Plan, "Plan", VIOLET),
                                    (PermissionMode::Yolo, "Yolo", SUCCESS),
                                    (PermissionMode::ReadOnly, "Read-only", TEXT_4),
                                ] {
                                    if chip(ui, self.mode == mode, label, dot).clicked() {
                                        self.set_mode(mode);
                                    }
                                    ui.add_space(2.0);
                                }

                                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                                    // Round send button (brand fill in dark = near-white).
                                    let (rect, resp) =
                                        ui.allocate_exact_size(egui::vec2(34.0, 34.0), egui::Sense::click());
                                    let enabled = !busy
                                        && (!self.chat_input.trim().is_empty() || !self.pending_images.is_empty());
                                    let fill = if !enabled {
                                        LAYER_3
                                    } else if resp.hovered() {
                                        TEXT_1
                                    } else {
                                        TEXT_1
                                    };
                                    ui.painter().circle_filled(rect.center(), 17.0, fill);
                                    if busy {
                                        ui.painter().rect_filled(
                                            egui::Rect::from_center_size(rect.center(), egui::vec2(10.0, 10.0)),
                                            2.0,
                                            INK_ON_LIGHT,
                                        );
                                    } else {
                                        // Painted arrow (shaft + head) — no glyph dependency.
                                        let c = rect.center();
                                        let ink = if enabled { INK_ON_LIGHT } else { TEXT_4 };
                                        ui.painter().line_segment(
                                            [egui::pos2(c.x, c.y + 7.0), egui::pos2(c.x, c.y - 4.0)],
                                            egui::Stroke::new(2.0_f32, ink),
                                        );
                                        let head = [
                                            egui::pos2(c.x - 5.0, c.y - 1.0),
                                            egui::pos2(c.x + 5.0, c.y - 1.0),
                                            egui::pos2(c.x, c.y - 8.0),
                                        ];
                                        ui.painter().add(egui::Shape::convex_polygon(
                                            head.to_vec(),
                                            ink,
                                            egui::Stroke::NONE,
                                        ));
                                    }
                                    if enabled && resp.clicked() {
                                        self.send_current();
                                    }
                                });
                            });
                        });
                    ui.add_space(10.0);
                });
            });
    }

    fn send_current(&mut self) {
        if self.chat_input.trim().is_empty() && self.pending_images.is_empty() {
            return;
        }
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

    fn thumbnail(&mut self, ui: &mut egui::Ui, path: &std::path::Path, max_w: f32) {
        let key = path.display().to_string();
        if !self.thumbnails.contains_key(&key) {
            let tex = image::open(path).ok().map(|img| {
                let rgba = img.to_rgba8();
                let (w, h) = rgba.dimensions();
                let color = egui::ColorImage::from_rgba_unmultiplied([w as usize, h as usize], &rgba);
                ui.ctx()
                    .load_texture(format!("img:{key}"), color, egui::TextureOptions::LINEAR)
            });
            self.thumbnails.insert(key.clone(), tex);
        }
        if let Some(Some(tex)) = self.thumbnails.get(&key) {
            ui.add(
                egui::Image::from_texture(egui::load::SizedTexture::new(tex.id(), tex.size_vec2()))
                    .max_width(max_w)
                    .rounding(R_SM),
            );
        }
    }

    // ── Row sync (shared DB) ───────────────────────────────────────────────

    /// Row sync in: load the latest shared-DB session into the transcript and
    /// bind the agent to it, so the conversation continues on the same rows.
    #[allow(dead_code)] // CLI/API parity — the sidebar list uses resume_session
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

    /// Row sync in: adopt a specific shared-DB session (picked in the sidebar)
    /// — its rows become the transcript and the next turns append to the same
    /// `chat_sessions`/`agent_messages` rows, on phone or PC.
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
        self.open_tools.clear();
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
        self.active_session_id = Some(resumed.session.id.clone());
        self.active_session_title = Some(resumed.session.title.clone());
        self.pane = Pane::Chat;
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

    // ── Panels (dsh pinned-panel pages) ────────────────────────────────────

    fn panel_page(&mut self, ctx: &egui::Context, pane: Pane) {
        egui::CentralPanel::default()
            .frame(egui::Frame::none().fill(BG_BASE))
            .show(ctx, |ui| {
                ui.add_space(14.0);
                ui.horizontal(|ui| {
                    ui.add_space(20.0);
                    ui.vertical(|ui| {
                        let (title, sub) = match pane {
                            Pane::Goals => ("Goals", "Goal DAG · swimlanes · GOAL_DAG.json"),
                            Pane::Telemetry => ("Telemetry", "XP · streaks · spec radar — from the shared DB"),
                            Pane::Automation => ("Automation", ".mjs scripts on the embedded rquickjs engine"),
                            Pane::Decisions => ("Decisions", "Decision cards awaiting a call"),
                            Pane::Events => ("Events", "Bi-directional mobile ⇄ desktop sync log"),
                            Pane::Settings => ("Settings", "Providers · paths"),
                            Pane::Chat => unreachable!(),
                        };
                        ui.label(egui::RichText::new(title).size(18.0).color(TEXT_1).strong());
                        ui.label(egui::RichText::new(sub).size(12.0).color(TEXT_4));
                    });
                });
                ui.add_space(8.0);
                ui.separator();
                egui::ScrollArea::vertical()
                    .id_salt("panel")
                    .auto_shrink([false, true])
                    .show(ui, |ui| {
                        let max_w = COLUMN.min(ui.available_width() - 40.0);
                        ui.with_layout(egui::Layout::top_down(egui::Align::LEFT), |ui| {
                            ui.set_max_width(max_w);
                            ui.add_space(14.0);
                            match pane {
                                Pane::Goals => self.page_goals(ui),
                                Pane::Telemetry => self.page_telemetry(ui),
                                Pane::Automation => self.page_automation(ui),
                                Pane::Decisions => self.page_decisions(ui),
                                Pane::Events => self.page_events(ui),
                                Pane::Settings => self.page_settings(ui),
                                Pane::Chat => unreachable!(),
                            }
                            ui.add_space(24.0);
                        });
                    });
            });
    }

    fn page_goals(&mut self, ui: &mut egui::Ui) {
        let dag = xencode_core::goal::file::load(&self.workspace);
        card(ui, |ui| {
            ui.label(egui::RichText::new("Goal DAG").size(13.5).color(TEXT_1).strong());
            ui.add_space(6.0);
            code_block(ui, |ui| {
                ui.monospace(egui::RichText::new(dag.render()).size(12.0).color(TEXT_2));
            });
            ui.add_space(8.0);
            ui.label(egui::RichText::new(dag.summary_counts()).size(12.5).color(TEXT_3));
        });
        ui.add_space(10.0);
        card(ui, |ui| {
            ui.label(egui::RichText::new("Run").size(13.5).color(TEXT_1).strong());
            ui.add_space(6.0);
            ui.horizontal(|ui| {
                if primary(ui, "Run READY nodes (sequential)").clicked() {
                    self.status = "running goals…".into();
                    let ws = self.workspace.clone();
                    std::thread::spawn(move || {
                        let rt = tokio::runtime::Builder::new_current_thread().enable_all().build().unwrap();
                        let summary = rt.block_on(xencode_core::goal::runner::run_ready(&ws, 1));
                        println!("{summary}");
                    });
                }
                if ghost(ui, "Parallel swimlanes ×4").clicked() {
                    self.status = "running swimlanes…".into();
                    let ws = self.workspace.clone();
                    std::thread::spawn(move || {
                        let rt = tokio::runtime::Builder::new_multi_thread().enable_all().build().unwrap();
                        let summary = rt.block_on(xencode_core::goal::runner::run_ready(&ws, 4));
                        println!("{summary}");
                    });
                }
            });
        });
    }

    fn page_telemetry(&mut self, ui: &mut egui::Ui) {
        let now = xencode_core::now_ms();
        match xencode_core::store::xp::DbXpLedger::open_default() {
            Ok(xp) => {
                card(ui, |ui| {
                    ui.label(egui::RichText::new("XP — gamified telemetry").size(13.5).color(TEXT_1).strong());
                    ui.add_space(6.0);
                    code_block(ui, |ui| {
                        ui.monospace(egui::RichText::new(xencode_core::telemetry::XpSink::summary(&xp, now).render()).size(12.0).color(TEXT_2));
                    });
                    if let Some(p) = &self.persister {
                        if let Some((calls, errors, in_tok, out_tok)) = p.llm_totals() {
                            ui.add_space(6.0);
                            ui.label(
                                egui::RichText::new(format!(
                                    "tokens (shared DB): in {in_tok} · out {out_tok} · {calls} llm calls ({errors} errored)"
                                ))
                                .size(12.5)
                                .color(TEXT_3),
                            );
                        }
                    }
                    ui.add_space(4.0);
                    ui.label(
                        egui::RichText::new(format!(
                            "shared DB: {}",
                            xencode_core::store::SharedStore::default_db_path().display()
                        ))
                        .size(11.0)
                        .color(TEXT_4),
                    );
                });
            }
            Err(_) => {
                let xp = xencode_core::telemetry::XpLedger::default();
                card(ui, |ui| {
                    code_block(ui, |ui| {
                        ui.monospace(egui::RichText::new(xp.render_summary(now)).size(12.0).color(TEXT_2));
                    });
                    ui.label(egui::RichText::new("! shared database unavailable — showing the legacy file ledger").size(12.0).color(ERROR));
                });
            }
        }
        ui.add_space(10.0);
        card(ui, |ui| {
            ui.label(egui::RichText::new("Spec radar").size(13.5).color(TEXT_1).strong());
            ui.add_space(6.0);
            code_block(ui, |ui| {
                ui.monospace(egui::RichText::new(xencode_core::telemetry::Telemetry::load(&self.workspace).render_radar()).size(12.0).color(TEXT_2));
            });
        });
    }

    fn page_automation(&mut self, ui: &mut egui::Ui) {
        card(ui, |ui| {
            ui.label(egui::RichText::new("Run a script").size(13.5).color(TEXT_1).strong());
            ui.add_space(4.0);
            ui.label(
                egui::RichText::new("import { fs, ast, pty, git, sync } from \"xencode:core\"; export function main() { … }")
                    .size(12.0)
                    .color(TEXT_3)
                    .monospace(),
            );
            ui.add_space(8.0);
            ui.horizontal(|ui| {
                let se = ui.add(
                    egui::TextEdit::singleline(&mut self.automation_script)
                        .hint_text("script path relative to workspace")
                        .desired_width(ui.available_width() - 110.0),
                );
                if primary(ui, "Run").clicked()
                    || (se.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter)))
                {
                    let path = self.workspace.join(self.automation_script.trim());
                    let engine = xencode_automation::MjsEngine::new(&self.workspace);
                    self.automation_out = match engine.run_file(&path, None) {
                        Ok(res) => xencode_automation::render_result(&res),
                        Err(e) => format!("! {e}"),
                    };
                }
            });
        });
        ui.add_space(10.0);
        card(ui, |ui| {
            ui.label(egui::RichText::new("Output").size(13.5).color(TEXT_1).strong());
            ui.add_space(6.0);
            code_block(ui, |ui| {
                ui.monospace(egui::RichText::new(&self.automation_out).size(12.0).color(TEXT_2));
            });
        });
    }

    fn page_decisions(&mut self, ui: &mut egui::Ui) {
        let store = xencode_core::state::decisions::DecisionStore::new(&self.workspace);
        let cards = store.all();
        if cards.is_empty() {
            card(ui, |ui| {
                ui.label(egui::RichText::new("No decision cards yet.").size(13.0).color(TEXT_3));
            });
        }
        for card_data in &cards {
            card(ui, |ui| {
                ui.horizontal(|ui| {
                    ui.label(egui::RichText::new(&card_data.title).size(13.5).color(TEXT_1).strong());
                    ui.label(egui::RichText::new(&card_data.id).size(11.5).color(TEXT_4).monospace());
                    if card_data.chosen_index.is_none() {
                        egui::Frame::none()
                            .fill(WARN_DIM)
                            .rounding(egui::Rounding::same(9.0))
                            .inner_margin(egui::Margin::symmetric(8.0, 2.0))
                            .show(ui, |ui| {
                                ui.label(egui::RichText::new("PENDING").size(10.5).color(WARN).strong());
                            });
                    }
                });
                if !card_data.body.is_empty() {
                    ui.label(egui::RichText::new(&card_data.body).size(12.5).color(TEXT_2));
                }
                ui.add_space(4.0);
                ui.horizontal(|ui| {
                    for (i, opt) in card_data.options.iter().enumerate() {
                        if ghost(ui, opt).clicked() {
                            let _ = store.resolve(&card_data.id, i);
                            xencode_core::state::events::EventLog::new(&self.workspace).emit_simple(
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

    fn page_events(&mut self, ui: &mut egui::Ui) {
        let log = xencode_core::state::events::EventLog::new(&self.workspace);
        card(ui, |ui| {
            code_block(ui, |ui| {
                egui::ScrollArea::vertical()
                    .id_salt("events")
                    .stick_to_bottom(true)
                    .max_height(ui.available_height().max(360.0))
                    .show(ui, |ui| {
                        ui.monospace(egui::RichText::new(log.render_tail(200)).size(12.0).color(TEXT_2));
                    });
            });
        });
    }

    fn page_settings(&mut self, ui: &mut egui::Ui) {
        let cfg = xencode_core::config::load_config().unwrap_or_default();
        card(ui, |ui| {
            ui.label(egui::RichText::new("Providers").size(13.5).color(TEXT_1).strong());
            ui.add_space(6.0);
            for p in &cfg.providers {
                ui.horizontal(|ui| {
                    ui.label(egui::RichText::new(&p.name).size(13.0).color(TEXT_1));
                    ui.label(egui::RichText::new(p.kind.as_str()).size(12.0).color(TEXT_3));
                    if Some(&p.name) == cfg.active.as_ref() {
                        egui::Frame::none()
                            .fill(SUCCESS_DIM)
                            .rounding(egui::Rounding::same(9.0))
                            .inner_margin(egui::Margin::symmetric(8.0, 2.0))
                            .show(ui, |ui| {
                                ui.label(egui::RichText::new("ACTIVE").size(10.5).color(SUCCESS).strong());
                            });
                    }
                });
                ui.label(
                    egui::RichText::new(format!("{} · {}", p.base_url, p.model))
                        .size(11.5)
                        .color(TEXT_4)
                        .monospace(),
                );
                ui.add_space(2.0);
            }
            if cfg.providers.is_empty() {
                ui.label(
                    egui::RichText::new(
                        "No providers yet — run: xencode config set-provider <name> --base-url … --api-key … --model …",
                    )
                    .size(12.5)
                    .color(TEXT_3),
                );
            }
            ui.add_space(6.0);
            ui.horizontal(|ui| {
                for p in &cfg.providers {
                    if ghost(ui, &format!("use {}", p.name)).clicked() {
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
                ui.label(egui::RichText::new(&self.settings_note).size(12.0).color(BRAND_400));
            }
        });
        ui.add_space(10.0);
        card(ui, |ui| {
            ui.label(egui::RichText::new("Paths").size(13.5).color(TEXT_1).strong());
            ui.add_space(6.0);
            ui.label(
                egui::RichText::new(format!("config: {}", xencode_core::config::config_path().display()))
                    .size(12.0)
                    .color(TEXT_3),
            );
            ui.label(
                egui::RichText::new(format!("workspace: {}", self.workspace.display()))
                    .size(12.0)
                    .color(TEXT_3),
            );
        });
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

/// Renders a session's `updated_at` (Room stores epoch millis; older rows may
/// carry seconds) as a compact relative age for the Sessions rows.
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
