//! XenCode Desktop, Rust GUI for Windows, macOS and Linux.
//!
//! The UI is a port of the ZCode (@zcode/ui) workspace shell (Apache-2.0,
//! zai-org/ZCode): same surfaces, palette, typography and interaction
//! patterns as the shipped desktop app. Only the brand glyph differs
//! (XenCode logo, art/xencode-icon-512.png) and the backend talks to the
//! XenCode shared database and agent core instead of the ZCode server.
//!
//! Shell: desktop top overlay (logo, sidebar toggle, window controls),
//! workspace sidebar (New Task, Tasks from the shared DB, Projects),
//! conversation view with the composer card, and the tool pages (Goals,
//! Telemetry, Automation, Decisions, Events, Settings).
//!
//! Screenshot tour: with `XENCODE_UI_TOUR=<dir>` the app walks every page
//! and writes `<dir>/marker.txt` for the external capture script
//! (scripts/capture_ui_pages.py for docs/screenshots/pc/).

mod cloud;
mod theme;
mod ui;

use eframe::egui;
use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{mpsc, Arc, Mutex};
use theme::Mode as ThemeMode;
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
    let tour = std::env::var("XENCODE_UI_TOUR").map(PathBuf::from).ok();
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_title("XenCode")
            .with_inner_size([1360.0, 850.0])
            .with_min_inner_size([960.0, 620.0])
            .with_icon(load_icon().expect("embedded icon is valid PNG")),
        ..Default::default()
    };
    eframe::run_native(
        "XenCode",
        options,
        Box::new(move |cc| {
            let mut app = XenApp::new(workspace);
            app.init_logo(&cc.egui_ctx);
            app.apply_theme(&cc.egui_ctx);
            app.tour = tour.map(|dir| Tour {
                dir,
                step: 0,
                entered: std::time::Instant::now(),
                written: false,
            });
            Ok(Box::new(app))
        }),
    )
}

/// Main-view pages (ZCode IA: chat workspace + tool pages).
#[derive(PartialEq, Clone, Copy)]
pub(crate) enum Pane {
    Chat,
    Goals,
    Telemetry,
    Automation,
    Decisions,
    Events,
    Settings,
}

impl Pane {
    pub(crate) fn label(self) -> &'static str {
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
pub(crate) enum ChatMsg {
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
pub(crate) struct ChatEntry {
    pub(crate) role: String, // user | stream | agent | tool
    pub(crate) text: String,
    pub(crate) images: Vec<PathBuf>,
}

impl ChatEntry {
    pub(crate) fn new(role: &str, text: impl Into<String>) -> Self {
        Self { role: role.into(), text: text.into(), images: vec![] }
    }
}

/// Sidebar session row from the shared DB (workspace-aware).
#[derive(Clone)]
#[allow(dead_code)] // full shared-DB row; later port phases surface mode/tokens/workspace badges
pub(crate) struct SidebarSession {
    pub(crate) id: String,
    pub(crate) title: String,
    pub(crate) mode: String,
    pub(crate) updated_at: i64,
    pub(crate) tokens: i64,
    pub(crate) workspace: String,
}

pub(crate) struct ChatWorker {
    pub(crate) tx: mpsc::Sender<ChatMsg>,
    pub(crate) stream: Arc<Mutex<String>>,
    pub(crate) busy: Arc<AtomicBool>,
    pub(crate) failure: Arc<Mutex<Option<String>>>,
}

/// Screenshot tour state (env `XENCODE_UI_TOUR`).
pub(crate) struct Tour {
    pub(crate) dir: PathBuf,
    pub(crate) step: usize,
    pub(crate) entered: std::time::Instant,
    pub(crate) written: bool,
}

pub(crate) const TOUR_STEPS: [&str; 8] = [
    "01_home",
    "02_chat",
    "03_goals",
    "04_telemetry",
    "05_automation",
    "06_decisions",
    "07_events",
    "08_settings",
];

/// Which popover menu is currently open.
#[derive(Clone, Copy, PartialEq)]
pub(crate) enum MenuKind {
    Workspace,
    Mode,
    Model,
}

pub(crate) struct OpenMenu {
    pub(crate) kind: MenuKind,
    pub(crate) anchor: egui::Rect,
}

/// ZCode permission-preset labels.
pub(crate) fn preset_label(mode: PermissionMode) -> &'static str {
    match mode {
        PermissionMode::Confirm => "Agent",
        PermissionMode::Plan => "Plan",
        PermissionMode::Yolo => "Full Access",
        PermissionMode::ReadOnly => "Read Only",
    }
}

pub(crate) struct XenApp {
    pub(crate) workspace: PathBuf,
    /// Workspace history shown in the sidebar (front = most recent).
    pub(crate) workspaces: Vec<String>,
    pub(crate) sidebar_open: bool,
    pub(crate) sidebar_width: f32,
    pub(crate) pane: Pane,
    pub(crate) chat_input: String,
    pub(crate) chat_log: Vec<ChatEntry>,
    /// Images staged for the next message.
    pub(crate) pending_images: Vec<PathBuf>,
    /// Decoded image thumbnails, memoized by path (None = failed to decode).
    pub(crate) thumbnails: HashMap<String, Option<egui::TextureHandle>>,
    /// Brand texture (window icon decoded once for in-app use).
    pub(crate) logo: Option<egui::TextureHandle>,
    /// Current permission mode; mirrored to the worker through `mode_shared`.
    pub(crate) mode: PermissionMode,
    pub(crate) mode_shared: Arc<Mutex<PermissionMode>>,
    /// Reasoning effort label shown in the model seat.
    pub(crate) effort: String,
    pub(crate) worker: Option<ChatWorker>,
    /// Busy→idle edge detector (session refresh + status line).
    pub(crate) was_busy: bool,
    /// Shared-database writer; None when the DB cannot be opened, the chat
    /// keeps working without persistence.
    pub(crate) persister: Option<std::sync::Arc<xencode_core::store::chatlog::ChatPersister>>,
    pub(crate) automation_script: String,
    pub(crate) automation_out: String,
    pub(crate) settings_note: String,
    pub(crate) status: String,
    pub(crate) chat_note: String,
    /// Row-sync session picker state: sidebar task rows refreshed on start
    /// and after each turn.
    pub(crate) cached_sessions: Vec<SidebarSession>,
    pub(crate) active_session_id: Option<String>,
    pub(crate) active_session_title: Option<String>,
    /// Expanded tool cards in the transcript (index → open).
    pub(crate) open_tools: std::collections::HashSet<u64>,
    /// Providers snapshot for the model seat menu.
    pub(crate) providers: Vec<(String, String, String)>, // name, model, base_url
    pub(crate) active_provider: Option<String>,
    /// Theme mode (ZCode ships light + dark; toggle in the top overlay).
    pub(crate) dark: bool,
    pub(crate) tour: Option<Tour>,
    pub(crate) open_menu: Option<OpenMenu>,
}

impl XenApp {
    fn new(workspace: PathBuf) -> Self {
        let persister = xencode_core::store::chatlog::ChatPersister::open_default();
        let cached_sessions = Self::map_sessions(&persister);
        let mut workspaces = Self::load_workspace_history();
        if !workspaces.iter().any(|w| w == &workspace.display().to_string()) {
            workspaces.insert(0, workspace.display().to_string());
        }
        let tour = std::env::var("XENCODE_UI_TOUR").is_ok();
        if tour {
            // Demo workspace rows for the screenshot tour only.
            workspaces = vec![
                workspace.join("docs").display().to_string(),
                workspace.join("Ungrouped").display().to_string(),
            ];
            workspaces.insert(0, workspace.display().to_string());
        }
        let cfg = xencode_core::config::load_config().unwrap_or_default();
        let providers = cfg
            .providers
            .iter()
            .map(|p| (p.name.clone(), p.model.clone(), p.base_url.clone()))
            .collect();
        let active_provider = cfg.active.clone();
        let dark = std::env::var("XENCODE_THEME").map(|v| v != "light").unwrap_or(true);
        Self {
            workspace,
            workspaces,
            sidebar_open: true,
            sidebar_width: theme::geom::SIDEBAR_DEFAULT,
            pane: Pane::Chat,
            chat_input: String::new(),
            chat_log: vec![],
            pending_images: vec![],
            thumbnails: HashMap::new(),
            logo: None,
            mode: PermissionMode::Confirm,
            mode_shared: Arc::new(Mutex::new(PermissionMode::Confirm)),
            effort: "High".into(),
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
            providers,
            active_provider,
            dark,
            tour: None,
            open_menu: None,
        }
    }

    pub(crate) fn palette(&self) -> theme::Palette {
        theme::Palette { mode: if self.dark { ThemeMode::Dark } else { ThemeMode::Light } }
    }

    pub(crate) fn apply_theme(&self, ctx: &egui::Context) {
        theme::install(ctx, if self.dark { ThemeMode::Dark } else { ThemeMode::Light });
        // Real bold weight for markdown headings and strong runs
        // (egui 0.29 bundles only a Light proportional face).
        ui::markdown::install_bold_font(ctx);
    }

    /// Rough context-window fill for the composer meter. ZCode reports the
    /// provider usage counter; the desktop port estimates from transcript
    /// size (chars/4 tokens over a 200k window) until usage rows land.
    pub(crate) fn context_estimate(&self) -> f32 {
        let chars: usize = self.chat_log.iter().map(|e| e.text.len()).sum();
        (chars as f32 / 4.0 / 200_000.0).clamp(0.0, 1.0)
    }

    /// Reasoning-effort cycle (chat.toolbar.thoughtLevel values Off, Low,
    /// Medium, High); ZCode ThoughtLevelCycleControl in cycle mode.
    pub(crate) fn cycle_effort(&mut self) {
        const LEVELS: [&str; 4] = ["Off", "Low", "Medium", "High"];
        let next = LEVELS
            .iter()
            .position(|l| *l == self.effort)
            .map(|i| (i + 1) % LEVELS.len())
            .unwrap_or(0);
        self.effort = LEVELS[next].to_string();
    }

    fn map_sessions(persister: &Option<Arc<xencode_core::store::chatlog::ChatPersister>>) -> Vec<SidebarSession> {
        persister
            .as_ref()
            .map(|p| {
                p.recent_sessions(30)
                    .into_iter()
                    .map(|s| SidebarSession {
                        id: s.id,
                        title: s.title,
                        mode: s.mode,
                        updated_at: s.updated_at,
                        tokens: s.total_input_tokens + s.total_output_tokens,
                        workspace: s.workspace_path,
                    })
                    .collect()
            })
            .unwrap_or_default()
    }

    pub(crate) fn refresh_sessions(&mut self) {
        self.cached_sessions = Self::map_sessions(&self.persister);
    }

    fn workspace_history_path() -> PathBuf {
        xencode_core::config::config_path()
            .parent()
            .map(|d| d.join("desktop-workspaces.json"))
            .unwrap_or_else(|| PathBuf::from("desktop-workspaces.json"))
    }

    fn load_workspace_history() -> Vec<String> {
        std::fs::read_to_string(Self::workspace_history_path())
            .ok()
            .and_then(|s| serde_json::from_str::<Vec<String>>(&s).ok())
            .unwrap_or_default()
    }

    fn save_workspace_history(&self) {
        if let Ok(json) = serde_json::to_string(&self.workspaces) {
            let _ = std::fs::write(Self::workspace_history_path(), json);
        }
    }

    /// Adds a workspace folder to the sidebar (folder-plus flow).
    pub(crate) fn add_workspace(&mut self) {
        if let Some(dir) = rfd::FileDialog::new().pick_folder() {
            let path = dir.display().to_string();
            self.workspaces.retain(|w| w != &path);
            self.workspaces.insert(0, path);
            self.workspaces.truncate(12);
            self.save_workspace_history();
        }
    }

    /// Switches the active workspace: rebinding sessions, goals and the
    /// agent worker to the new directory.
    pub(crate) fn switch_workspace(&mut self, path: String) {
        self.workspaces.retain(|w| w != &path);
        self.workspaces.insert(0, path.clone());
        self.workspaces.truncate(12);
        self.save_workspace_history();
        self.workspace = PathBuf::from(&path);
        self.new_chat();
        self.refresh_sessions();
        self.automation_out.clear();
        self.status = "Idle".into();
    }

    /// Current git branch of the workspace (ZCode branch switcher chip).
    pub(crate) fn current_branch(&self) -> Option<String> {
        let head = std::fs::read_to_string(self.workspace.join(".git").join("HEAD")).ok()?;
        let ref_ = head.trim().strip_prefix("ref: refs/heads/")?;
        Some(ref_.to_string())
    }

    fn init_logo(&mut self, ctx: &egui::Context) {
        if let Some(img) = image::load_from_memory(ICON_PNG).ok() {
            let rgba = img.to_rgba8();
            let (w, h) = rgba.dimensions();
            let color = egui::ColorImage::from_rgba_unmultiplied([w as usize, h as usize], &rgba);
            self.logo = Some(ctx.load_texture("xencode-logo", color, egui::TextureOptions::LINEAR));
        }
    }

    /// Starts a fresh conversation: clears the transcript and rebinds the
    /// worker so the next turn opens a new shared-DB session.
    pub(crate) fn new_chat(&mut self) {
        self.chat_log.clear();
        self.pending_images.clear();
        self.open_tools.clear();
        self.active_session_id = None;
        self.active_session_title = None;
        self.chat_note.clear();
        self.status = "Idle".into();
        self.worker = None; // dropping the sender ends the old worker loop
        self.pane = Pane::Chat;
    }

    /// Switches the permission mode everywhere at once: the UI chips, the
    /// worker's live session and the shared-DB `chat_sessions.mode` column.
    pub(crate) fn set_mode(&mut self, mode: PermissionMode) {
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
            // record + token totals after the turn, XP in xp_events.
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
                self.mode = PermissionMode::Confirm;
                self.open_menu = None;
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
            "Refactor the auth module to use the shared session store, keep the public API stable and add tests.",
        ));
        self.chat_log.push(ChatEntry::new("tool", "read_file: crates/xencode-core/src/store/chatlog.rs"));
        self.chat_log.push(ChatEntry::new(
            "tool",
            "run_command: cargo test -p xencode-core --quiet",
        ));
        self.chat_log.push(ChatEntry::new(
            "tool",
            "edit_file: crates/xencode-core/src/agent.rs (+42 -7 lines)",
        ));
        self.chat_log.push(ChatEntry::new(
            "agent",
            "Here is the plan:\n\n1. Introduce SessionStore behind the existing trait, with no public API changes.\n2. Migrate auth::legacy to the store in three call sites (login, refresh, logout).\n3. Wire the shared-DB persister so the same rows sync to the mobile app.\n\nTests: 12 added, all green. cargo test exits 0.",
        ));
        self.active_session_title = Some("Refactor auth to the shared session store".into());
    }
}

impl eframe::App for XenApp {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        // Keyboard page switching: Ctrl+1..7.
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

        // The ZCode shell: top overlay + sidebar + main view.
        ui::app_shell::show(self, ctx);

        // Live chat stream updates → repaint while busy; refresh the
        // task list on the busy→idle edge.
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
    pub(crate) fn send_current(&mut self) {
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

    pub(crate) fn thumbnail(&mut self, ui: &mut egui::Ui, path: &std::path::Path, max_w: f32) {
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
            let size = tex.size_vec2();
            let scale = (max_w / size.x).min(1.0);
            ui.add(
                egui::Image::from_texture(egui::load::SizedTexture::new(tex.id(), size * scale))
                    .rounding(egui::Rounding::same(6.0)),
            );
        }
    }

    pub(crate) fn thumbnail_at(&mut self, ui: &mut egui::Ui, path: &std::path::Path, rect: egui::Rect) {
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
            let size = tex.size_vec2();
            let scale = (rect.width() / size.x).min(rect.height() / size.y);
            let draw = size * scale;
            egui::Image::from_texture(egui::load::SizedTexture::new(tex.id(), draw))
                .rounding(egui::Rounding::same(6.0))
                .paint_at(ui, egui::Rect::from_center_size(rect.center(), draw));
        }
    }

    // ── Row sync (shared DB) ───────────────────────────────────────────────

    /// Row sync in: adopt a specific shared-DB session (picked in the
    /// sidebar), its rows become the transcript and the next turns append
    /// to the same `chat_sessions`/`agent_messages` rows, on phone or PC.
    pub(crate) fn resume_session(&mut self, id: &str) {
        let Some(p) = self.persister.clone() else {
            self.chat_note = "shared database unavailable, chat works but nothing is persisted".into();
            return;
        };
        let Some(resumed) = p.session_by_id(id) else {
            self.chat_note = format!("session `{id}` not found in the shared database");
            return;
        };
        self.bind_resumed(resumed);
    }

    /// Adopts a loaded session: replay its rows into the chat log and hand
    /// the worker the session id so persistence continues on the same rows.
    pub(crate) fn bind_resumed(&mut self, resumed: xencode_core::store::chatlog::ResumedSession) {
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

    pub(crate) fn toggle_menu(&mut self, kind: MenuKind, anchor: egui::Rect) {
        self.open_menu = match self.open_menu.take() {
            Some(m) if m.kind == kind => None,
            _ => Some(OpenMenu { kind, anchor }),
        };
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn icon_is_embedded() {
        // PNG magic
        assert_eq!(&super::ICON_PNG[..8], &[0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A]);
    }

    #[test]
    fn preset_labels_match_zcode() {
        use xencode_core::permissions::PermissionMode;
        assert_eq!(super::preset_label(PermissionMode::Confirm), "Agent");
        assert_eq!(super::preset_label(PermissionMode::Plan), "Plan");
    }
}
