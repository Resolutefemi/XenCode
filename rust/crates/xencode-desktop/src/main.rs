//! XenCode Desktop, Rust GUI for Windows, macOS and Linux.
//!
//! The UI is an exact port of the DeepSeek Harness (`dsh`) desktop design:
//! same surfaces, palette, geometry, component shapes and interaction
//! patterns as the shipped app (sidebar with New Session + Workspaces,
//! hero home with the composer card, docked conversation composer, entry
//! pages). Only the brand glyph differs (XenCode logo).
//!
//! Panes: Chat (agent, plan mode, image attachments, session resume),
//! Goals (DAG + swimlanes), Telemetry (XP / spec radar), Automation (.mjs
//! runner), Decisions (cards), Events (sync log), Settings. Global panels
//! live behind the Workspaces filter button and the Settings page, exactly
//! like dsh's panel launchers.
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
            .with_inner_size([1280.0, 835.0])
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
    fn sub(self) -> &'static str {
        match self {
            Pane::Chat => "",
            Pane::Goals => "Goal DAG, swimlanes and GOAL_DAG.json",
            Pane::Telemetry => "XP, streaks and the spec radar, from the shared DB",
            Pane::Automation => ".mjs scripts on the embedded rquickjs engine",
            Pane::Decisions => "Decision cards awaiting a call",
            Pane::Events => "Bi-directional mobile and desktop sync log",
            Pane::Settings => "Providers, workspace tools and paths",
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
    role: String, // user | stream | agent | tool
    text: String,
    images: Vec<PathBuf>,
}

impl ChatEntry {
    fn new(role: &str, text: impl Into<String>) -> Self {
        Self { role: role.into(), text: text.into(), images: vec![] }
    }
}

/// Sidebar session row from the shared DB (workspace-aware).
#[derive(Clone)]
struct SidebarSession {
    id: String,
    title: String,
    mode: String,
    updated_at: i64,
    tokens: i64,
    workspace: String,
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

/// Which popover is currently open (anchored to the button's rect).
#[derive(Clone, Copy, PartialEq)]
enum MenuKind {
    HeroWorkspace,
    HeroMode,
    ModelSeat,
    Panels,
}

struct OpenMenu {
    kind: MenuKind,
    anchor: egui::Rect,
}

/// dsh permission-preset chip labels (the shipped preset names).
fn preset_label(mode: PermissionMode) -> &'static str {
    match mode {
        PermissionMode::Confirm => "Workspace Write",
        PermissionMode::Plan => "Plan mode",
        PermissionMode::Yolo => "Full Access",
        PermissionMode::ReadOnly => "Workspace Read",
    }
}

struct XenApp {
    workspace: PathBuf,
    /// Workspace history shown in the sidebar (front = most recent).
    workspaces: Vec<String>,
    sidebar_open: bool,
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
    /// Reasoning effort label shown in the model seat.
    effort: String,
    worker: Option<ChatWorker>,
    /// Busy→idle edge detector (session refresh + status line).
    was_busy: bool,
    /// Shared-database writer (aicode_agent_db); None when the DB cannot be
    /// opened, the chat keeps working without persistence.
    persister: Option<std::sync::Arc<xencode_core::store::chatlog::ChatPersister>>,
    automation_script: String,
    automation_out: String,
    settings_note: String,
    status: String,
    chat_note: String,
    /// Row-sync session picker state: sidebar conversation rows refreshed on
    /// start and after each turn.
    cached_sessions: Vec<SidebarSession>,
    active_session_id: Option<String>,
    active_session_title: Option<String>,
    /// Expanded tool cards in the transcript (index → open).
    open_tools: std::collections::HashSet<u64>,
    /// Sidebar workspace search field.
    ws_search_open: bool,
    ws_search: String,
    /// Providers snapshot for the model seat menu.
    providers: Vec<(String, String, String)>, // name, model, base_url
    active_provider: Option<String>,
    tour: Option<Tour>,
    open_menu: Option<OpenMenu>,
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
        Self {
            workspace,
            workspaces,
            sidebar_open: true,
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
            ws_search_open: false,
            ws_search: String::new(),
            providers,
            active_provider,
            tour: None,
            open_menu: None,
        }
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

    fn refresh_sessions(&mut self) {
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
    fn add_workspace(&mut self) {
        if let Some(dir) = rfd::FileDialog::new().pick_folder() {
            let path = dir.display().to_string();
            self.workspaces.retain(|w| w != &path);
            self.workspaces.insert(0, path);
            self.workspaces.truncate(12);
            self.save_workspace_history();
        }
    }

    /// Switches the active workspace: rebinding sessions, goals and the
    /// agent worker to the new directory (dsh workspace switch).
    fn switch_workspace(&mut self, path: String) {
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
    fn new_chat(&mut self) {
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
            // session's xp sink is the DB ledger, see session_from_config).
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
        self.chat_log.push(ChatEntry::new("tool", "ast.symbols: mapped 14 call sites across auth/*.rs"));
        self.chat_log.push(ChatEntry::new("tool", "git.worktreeCreate: xencode/plan-auth-refactor"));
        self.chat_log.push(ChatEntry::new(
            "agent",
            "Here is the plan:\n\n1. Introduce SessionStore behind the existing trait, with no public API changes.\n2. Migrate auth::legacy to the store in three call sites (login, refresh, logout).\n3. Wire the shared-DB persister so the same rows sync to the mobile app.\n\nTests: 12 added, all green. cargo test exits 0.",
        ));
        self.active_session_title = Some("Refactor auth to the shared session store".into());
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

        if self.sidebar_open {
            self.sidebar(ctx);
        } else {
            self.rail(ctx);
        }

        // Center column.
        match self.pane {
            Pane::Chat => self.chat_page(ctx),
            pane => self.panel_page(ctx, pane),
        }

        // Popovers render above everything; any click closes them.
        self.render_open_menu(ctx);

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
    // ── Sidebar (the dsh sidebar: brand, New Session, Workspaces) ─────────

    fn sidebar(&mut self, ctx: &egui::Context) {
        egui::SidePanel::left("sidebar")
            .exact_width(SIDEBAR_W)
            .frame(egui::Frame::none().fill(SIDEBAR))
            .show(ctx, |ui| {
                // Footer first: Settings pinned to the bottom.
                egui::TopBottomPanel::bottom("sb_footer")
                    .show_separator_line(false)
                    .frame(egui::Frame::none().fill(SIDEBAR).inner_margin(egui::Margin { left: 8.0, right: 8.0, top: 4.0, bottom: 10.0 }))
                    .show_inside(ui, |ui| {
                        let (rect, resp) =
                            ui.allocate_exact_size(egui::vec2(ui.available_width(), 36.0), egui::Sense::click());
                        let fill = if resp.hovered() { HOVER() } else { egui::Color32::TRANSPARENT };
                        ui.painter().rect_filled(rect, R_MD, fill);
                        gear(ui.painter(), egui::pos2(rect.left() + 17.0, rect.center().y), TEXT_3, 16.0);
                        ui.painter().text(
                            egui::pos2(rect.left() + 34.0, rect.center().y),
                            egui::Align2::LEFT_CENTER,
                            "Settings",
                            egui::FontId::proportional(14.0),
                            TEXT_1,
                        );
                        if resp.clicked() {
                            self.pane = Pane::Settings;
                            self.open_menu = None;
                        }
                    });

                egui::CentralPanel::default()
                    .frame(egui::Frame::none().fill(SIDEBAR).inner_margin(egui::Margin { left: 8.0, right: 8.0, top: 0.0, bottom: 0.0 }))
                    .show_inside(ui, |ui| {
                        // Brand row: XenCode glyph + name, panel toggle right.
                        // The logo is the only visual difference from dsh.
                        let brand_h = 52.0;
                        let (rect, _) = ui.allocate_exact_size(egui::vec2(ui.available_width(), brand_h), egui::Sense::hover());
                        if let Some(tex) = &self.logo {
                            egui::Image::from_texture(egui::load::SizedTexture::new(tex.id(), egui::vec2(26.0, 26.0)))
                                .rounding(egui::Rounding::same(7.0))
                                .paint_at(ui, egui::Rect::from_center_size(egui::pos2(rect.left() + 22.0, rect.center().y), egui::vec2(26.0, 26.0)));
                        }
                        ui.painter().text(
                            egui::pos2(rect.left() + 40.0, rect.center().y),
                            egui::Align2::LEFT_CENTER,
                            "XenCode",
                            egui::FontId::proportional(17.0),
                            TEXT_1,
                        );
                        let toggle_rect =
                            egui::Rect::from_center_size(egui::pos2(rect.right() - 22.0, rect.center().y), egui::vec2(28.0, 28.0));
                        let toggle_resp = ui.interact(toggle_rect, egui::Id::new("sb_toggle"), egui::Sense::click());
                        if toggle_resp.hovered() {
                            ui.painter().rect_filled(toggle_rect, R_SM, HOVER());
                        }
                        panel_icon(ui.painter(), toggle_rect.shrink(6.0), TEXT_3);
                        if toggle_resp.clicked() {
                            self.sidebar_open = false;
                        }

                        ui.add_space(4.0);

                        // New Session: 38px elevated bar, 12px radius.
                        let ns_w = ui.available_width();
                        let (ns_rect, ns_resp) =
                            ui.allocate_exact_size(egui::vec2(ns_w, 38.0), egui::Sense::click());
                        ui.painter().rect_filled(ns_rect, R_MD, if ns_resp.hovered() { ELEVATED_HOVER } else { ELEVATED });
                        ui.painter().rect_stroke(ns_rect, R_MD, hairline(BORDER_3()));
                        let c = ns_rect.center();
                        let plus_c = egui::pos2(c.x - 46.0, c.y);
                        ui.painter().circle_stroke(plus_c, 8.0, egui::Stroke::new(1.3, TEXT_1));
                        plus(ui.painter(), plus_c, TEXT_1, 8.0);
                        ui.painter().text(
                            egui::pos2(c.x + 10.0, c.y),
                            egui::Align2::CENTER_CENTER,
                            "New Session",
                            egui::FontId::proportional(14.0),
                            TEXT_1,
                        );
                        if ns_resp.clicked() {
                            self.new_chat();
                        }

                        ui.add_space(10.0);

                        // Workspaces header: label + search / filter / add.
                        let (hdr_rect, _) =
                            ui.allocate_exact_size(egui::vec2(ui.available_width(), 28.0), egui::Sense::hover());
                        ui.painter().text(
                            egui::pos2(hdr_rect.left() + 4.0, hdr_rect.center().y),
                            egui::Align2::LEFT_CENTER,
                            "Workspaces",
                            egui::FontId::proportional(13.5),
                            TEXT_4,
                        );
                        let icon_size = 26.0;
                        let mut icons: Vec<(&str, egui::Rect)> = vec![];
                        for (n, name) in ["ws_add", "ws_panels", "ws_search"].into_iter().enumerate() {
                            let r = egui::Rect::from_center_size(
                                egui::pos2(hdr_rect.right() - 13.0 - n as f32 * (icon_size + 2.0), hdr_rect.center().y),
                                egui::vec2(icon_size, icon_size),
                            );
                            icons.push((name, r));
                        }
                        for (name, r) in &icons {
                            let resp = ui.interact(*r, egui::Id::new(*name), egui::Sense::click());
                            if resp.hovered() {
                                ui.painter().rect_filled(*r, R_SM, HOVER());
                            }
                            match *name {
                                "ws_search" => search(ui.painter(), r.center(), TEXT_3, 16.0),
                                "ws_panels" => sliders(ui.painter(), r.center(), TEXT_3, 16.0),
                                _ => folder_plus(ui.painter(), r.shrink(5.0), TEXT_3),
                            }
                            if resp.clicked() {
                                match *name {
                                    "ws_search" => {
                                        self.ws_search_open = !self.ws_search_open;
                                        if !self.ws_search_open {
                                            self.ws_search.clear();
                                        }
                                    }
                                    "ws_panels" => {
                                        self.toggle_menu(MenuKind::Panels, *r);
                                    }
                                    _ => self.add_workspace(),
                                }
                            }
                        }
                        ui.add_space(4.0);
                        if self.ws_search_open {
                            let se = ui.add(
                                egui::TextEdit::singleline(&mut self.ws_search)
                                    .hint_text("Search workspaces")
                                    .desired_width(ui.available_width())
                                    .font(egui::TextStyle::Small),
                            );
                            se.request_focus();
                            ui.add_space(4.0);
                        }

                        // Workspace rows; the active workspace lists its
                        // shared-DB sessions beneath it.
                        egui::ScrollArea::vertical()
                            .id_salt("workspaces")
                            .auto_shrink([false, true])
                            .show(ui, |ui| {
                                let needle = self.ws_search.to_lowercase();
                                let workspaces = self.workspaces.clone();
                                let current = self.workspace.display().to_string();
                                for ws in &workspaces {
                                    let name = std::path::Path::new(ws)
                                        .file_name()
                                        .map(|s| s.to_string_lossy().to_string())
                                        .unwrap_or_else(|| ws.clone());
                                    if !needle.is_empty() && !name.to_lowercase().contains(&needle) {
                                        continue;
                                    }
                                    let selected = *ws == current;
                                    let (rect, resp) = ui.allocate_exact_size(
                                        egui::vec2(ui.available_width(), 36.0),
                                        egui::Sense::click(),
                                    );
                                    let fill = if resp.hovered() { HOVER() } else { egui::Color32::TRANSPARENT };
                                    ui.painter().rect_filled(rect, R_MD, fill);
                                    folder(ui.painter(), egui::Rect::from_center_size(egui::pos2(rect.left() + 17.0, rect.center().y), egui::vec2(16.0, 16.0)), TEXT_3, selected);
                                    ui.painter().text(
                                        egui::pos2(rect.left() + 34.0, rect.center().y),
                                        egui::Align2::LEFT_CENTER,
                                        name,
                                        egui::FontId::proportional(14.0),
                                        TEXT_1,
                                    );
                                    ui.add_space(8.0);
                                    if resp.clicked() && !selected {
                                        let ws = ws.clone();
                                        self.switch_workspace(ws);
                                    }
                                    // Shared-DB sessions of the active workspace.
                                    if selected {
                                        let sessions: Vec<SidebarSession> = self
                                            .cached_sessions
                                            .iter()
                                            .filter(|s| s.workspace == *ws || s.workspace.is_empty())
                                            .take(12)
                                            .cloned()
                                            .collect();
                                        for s in &sessions {
                                            let is_active =
                                                self.active_session_id.as_deref() == Some(s.id.as_str());
                                            let (srect, sresp) = ui.allocate_exact_size(
                                                egui::vec2(ui.available_width(), 40.0),
                                                egui::Sense::click(),
                                            );
                                            let sfill = if is_active {
                                                ACTIVE()
                                            } else if sresp.hovered() {
                                                HOVER()
                                            } else {
                                                egui::Color32::TRANSPARENT
                                            };
                                            ui.painter().rect_filled(srect, R_MD, sfill);
                                            ui.painter().text(
                                                egui::pos2(srect.left() + 34.0, srect.top() + 14.0),
                                                egui::Align2::LEFT_CENTER,
                                                ellipsize(&s.title, 24),
                                                egui::FontId::proportional(13.0),
                                                if is_active { TEXT_1 } else { TEXT_2 },
                                            );
                                            ui.painter().text(
                                                egui::pos2(srect.left() + 34.0, srect.top() + 29.0),
                                                egui::Align2::LEFT_CENTER,
                                                format!("{} · {} · {} tok", s.mode.to_lowercase(), fmt_age(s.updated_at), s.tokens),
                                                egui::FontId::proportional(10.5),
                                                TEXT_4,
                                            );
                                            if sresp.clicked() {
                                                let id = s.id.clone();
                                                self.resume_session(&id);
                                            }
                                        }
                                    }
                                }
                            });
                    });
            });
    }

    /// The collapsed sidebar rail (56px): brand mark, New Session, Settings.
    fn rail(&mut self, ctx: &egui::Context) {
        egui::SidePanel::left("rail")
            .exact_width(RAIL_W)
            .frame(egui::Frame::none().fill(SIDEBAR))
            .show(ctx, |ui| {
                ui.add_space(12.0);
                // Brand mark doubles as the expand toggle (dsh rail behavior).
                if let Some(tex) = &self.logo {
                    let (rect, resp) =
                        ui.allocate_exact_size(egui::vec2(RAIL_W, 36.0), egui::Sense::click());
                    if resp.hovered() {
                        ui.painter().rect_filled(rect, R_MD, HOVER());
                    }
                    egui::Image::from_texture(egui::load::SizedTexture::new(tex.id(), egui::vec2(24.0, 24.0)))
                        .rounding(egui::Rounding::same(6.0))
                        .paint_at(ui, egui::Rect::from_center_size(rect.center(), egui::vec2(24.0, 24.0)));
                    if resp.clicked() {
                        self.sidebar_open = true;
                    }
                }
                ui.add_space(12.0);
                let (rect, resp) = ui.allocate_exact_size(egui::vec2(RAIL_W, 36.0), egui::Sense::click());
                if resp.hovered() {
                    ui.painter().rect_filled(rect, R_MD, HOVER());
                }
                plus(ui.painter(), rect.center(), TEXT_1, 16.0);
                if resp.clicked() {
                    self.new_chat();
                }
                ui.with_layout(egui::Layout::bottom_up(egui::Align::LEFT), |ui| {
                    ui.add_space(10.0);
                    let (rect, resp) = ui.allocate_exact_size(egui::vec2(RAIL_W, 36.0), egui::Sense::click());
                    if resp.hovered() {
                        ui.painter().rect_filled(rect, R_MD, HOVER());
                    }
                    gear(ui.painter(), rect.center(), TEXT_3, 16.0);
                    if resp.clicked() {
                        self.pane = Pane::Settings;
                    }
                });
            });
    }

    fn toggle_menu(&mut self, kind: MenuKind, anchor: egui::Rect) {
        if self.open_menu.as_ref().map(|m| m.kind) == Some(kind) {
            self.open_menu = None;
        } else {
            self.open_menu = Some(OpenMenu { kind, anchor });
        }
    }

    /// Renders the open popover (dsh menu surface: elevated fill, 12px
    /// radius, hairline border). Any click anywhere closes it.
    fn render_open_menu(&mut self, ctx: &egui::Context) {
        let Some(menu) = self.open_menu.take() else { return };
        let width = match menu.kind {
            MenuKind::ModelSeat => 260.0,
            _ => 240.0,
        };
        let pos = match menu.kind {
            // Hero menus open upward-ish from the chip; keep them inside the window.
            MenuKind::HeroWorkspace | MenuKind::HeroMode => {
                egui::pos2(menu.anchor.left(), (menu.anchor.bottom() + 6.0).min(ctx.screen_rect().bottom() - 200.0))
            }
            _ => egui::pos2(menu.anchor.left(), menu.anchor.bottom() + 6.0),
        };
        let response = egui::Area::new(egui::Id::new("popover"))
            .order(egui::Order::Foreground)
            .fixed_pos(pos)
            .show(ctx, |ui| {
                egui::Frame::none()
                    .fill(MENU)
                    .rounding(egui::Rounding::same(R_MD))
                    .stroke(hairline(BORDER_2()))
                    .inner_margin(egui::Margin::same(6.0))
                    .show(ui, |ui| {
                        ui.set_min_width(width);
                        match menu.kind {
                            MenuKind::HeroMode => {
                                let modes = [
                                    (PermissionMode::Confirm, "Workspace Write"),
                                    (PermissionMode::Plan, "Plan mode"),
                                    (PermissionMode::Yolo, "Full Access"),
                                    (PermissionMode::ReadOnly, "Workspace Read"),
                                ];
                                for (mode, label) in modes {
                                    if menu_entry(ui, label, self.mode == mode) {
                                        self.set_mode(mode);
                                    }
                                }
                            }
                            MenuKind::HeroWorkspace => {
                                let workspaces = self.workspaces.clone();
                                let current = self.workspace.display().to_string();
                                if workspaces.is_empty() {
                                    ui.label(egui::RichText::new("No workspaces yet").size(12.5).color(TEXT_4));
                                }
                                for ws in workspaces {
                                    let name = std::path::Path::new(&ws)
                                        .file_name()
                                        .map(|s| s.to_string_lossy().to_string())
                                        .unwrap_or_else(|| ws.clone());
                                    if menu_entry(ui, &name, ws == current) {
                                        self.switch_workspace(ws);
                                    }
                                }
                            }
                            MenuKind::ModelSeat => {
                                let providers = self.providers.clone();
                                let active = self.active_provider.clone();
                                for (name, model, _url) in &providers {
                                    if menu_entry(ui, &format!("{name} · {model}"), Some(name) == active.as_ref()) {
                                        if let Some(cfg) = xencode_core::config::load_config().ok() {
                                            let mut c = cfg;
                                            c.active = Some(name.clone());
                                            match xencode_core::config::save_config(&c) {
                                                Ok(()) => {
                                                    self.active_provider = Some(name.clone());
                                                    self.settings_note = format!("active provider: {name}");
                                                }
                                                Err(e) => self.settings_note = format!("! {e}"),
                                            }
                                        }
                                    }
                                }
                                if providers.is_empty() {
                                    ui.label(
                                        egui::RichText::new("No providers configured yet")
                                            .size(12.5)
                                            .color(TEXT_4),
                                    );
                                }
                                ui.add_space(4.0);
                                for effort in ["High", "Medium", "Low"] {
                                    if menu_entry(ui, effort, self.effort == effort) {
                                        self.effort = effort.into();
                                    }
                                }
                            }
                            MenuKind::Panels => {
                                for pane in [
                                    Pane::Goals,
                                    Pane::Telemetry,
                                    Pane::Automation,
                                    Pane::Decisions,
                                    Pane::Events,
                                    Pane::Settings,
                                ] {
                                    if menu_entry(ui, pane.label(), self.pane == pane) {
                                        self.pane = pane;
                                    }
                                }
                            }
                        }
                    });
            });
        let _ = response;
    }
}

/// Renders a session's `updated_at` (Room stores epoch millis; older rows may
/// carry seconds) as a compact relative age for the session rows.
fn fmt_age(value: i64) -> String {
    if value <= 0 {
        return "n/a".into();
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

fn ellipsize(s: &str, max_chars: usize) -> String {
    if s.chars().count() <= max_chars {
        s.to_string()
    } else {
        let cut: String = s.chars().take(max_chars.saturating_sub(1)).collect();
        format!("{cut}…")
    }
}

impl XenApp {
    // ── Chat: hero home + conversation ─────────────────────────────────────

    fn chat_page(&mut self, ctx: &egui::Context) {
        let has_transcript = !self.chat_log.is_empty();
        if has_transcript {
            egui::TopBottomPanel::bottom("composer")
                .frame(egui::Frame::none().fill(BG_BASE).inner_margin(egui::Margin { left: 0.0, right: 0.0, top: 0.0, bottom: 4.0 }))
                .show(ctx, |ui| {
                    ui.with_layout(egui::Layout::top_down(egui::Align::Center), |ui| {
                        ui.set_max_width(COLUMN + 32.0);
                        if self.mode == PermissionMode::Plan {
                            self.plan_notice(ui);
                            ui.add_space(6.0);
                        }
                        self.composer_card(ui, false);
                    });
                });
        }

        egui::CentralPanel::default()
            .frame(egui::Frame::none().fill(BG_BASE))
            .show(ctx, |ui| {
                if has_transcript {
                    self.conversation_header(ui);
                    self.transcript(ui);
                } else {
                    self.hero(ui);
                }
            });
    }

    /// dsh conversation header: session title + live status, hairline below.
    fn conversation_header(&mut self, ui: &mut egui::Ui) {
        let (rect, _) = ui.allocate_exact_size(egui::vec2(ui.available_width(), 52.0), egui::Sense::hover());
        let title = self
            .active_session_title
            .clone()
            .unwrap_or_else(|| "New conversation".into());
        ui.painter().text(
            egui::pos2(rect.left() + 20.0, rect.center().y),
            egui::Align2::LEFT_CENTER,
            ellipsize(&title, 48),
            egui::FontId::proportional(14.0),
            TEXT_1,
        );
        let (dot, label) = if self.status == "thinking…" { (WARN, "Working…") } else { (SUCCESS, self.status.as_str()) };
        ui.painter().circle_filled(egui::pos2(rect.right() - 74.0, rect.center().y), 3.5, dot);
        ui.painter().text(
            egui::pos2(rect.right() - 20.0, rect.center().y),
            egui::Align2::RIGHT_CENTER,
            label,
            egui::FontId::proportional(12.0),
            TEXT_4,
        );
        ui.painter().line_segment(
            [egui::pos2(rect.left(), rect.bottom()), egui::pos2(rect.right(), rect.bottom())],
            hairline(BORDER_1()),
        );
    }

    /// The home hero: brand mark + "Into the Unknown" + Preview badge, the
    /// workspace / mode chip row, and the centered composer card over a
    /// soft radial glow. Geometry mirrors the shipped dsh screenshot.
    fn hero(&mut self, ui: &mut egui::Ui) {
        let avail = ui.max_rect();
        let center_x = avail.center().x;
        let card_h = self.card_height(ui, true);
        let stack_h = 32.0 + 12.0 + 28.0 + 12.0 + card_h;
        let top = ((avail.height() - stack_h) / 2.0).max(40.0);
        let stack_center = egui::pos2(center_x, top + stack_h / 2.0);

        // Ambient glow behind the hero (paint first, content covers it).
        hero_glow(ui.painter(), stack_center, 460.0, 260.0);

        let mut cursor = avail.left() + (avail.width() - COLUMN).max(0.0) / 2.0;
        let mut y = avail.top() + top;

        // Headline: logo + title + Preview badge (one centered line).
        let title = "Into the Unknown";
        let title_w = ui.fonts(|f| f.layout_no_wrap(title.to_string(), egui::FontId::proportional(26.0), TEXT_1).size().x);
        let mark_w = 30.0;
        let badge_w = 62.0;
        let line_w = mark_w + 10.0 + title_w + 7.0 + badge_w;
        let mut x = center_x - line_w / 2.0;
        let line_c = egui::pos2(0.0, y + 16.0);
        if let Some(tex) = &self.logo {
            let r = egui::Rect::from_center_size(egui::pos2(x + mark_w / 2.0, line_c.y), egui::vec2(mark_w, mark_w));
            egui::Image::from_texture(egui::load::SizedTexture::new(tex.id(), egui::vec2(mark_w, mark_w)))
                .rounding(egui::Rounding::same(8.0))
                .paint_at(ui, r);
        }
        x += mark_w + 10.0;
        ui.painter().text(
            egui::pos2(x, line_c.y),
            egui::Align2::LEFT_CENTER,
            title,
            egui::FontId::proportional(26.0),
            TEXT_1,
        );
        x += title_w + 7.0;
        // Preview badge: navy pill, mono caption, riding the title top.
        let badge_rect = egui::Rect::from_min_size(egui::pos2(x, y + 4.0), egui::vec2(badge_w, 20.0));
        ui.painter().rect_filled(badge_rect, 10.0, BADGE_BG);
        ui.painter().rect_stroke(badge_rect, 10.0, hairline(BORDER_1()));
        ui.painter().text(
            badge_rect.center(),
            egui::Align2::CENTER_CENTER,
            "Preview",
            egui::FontId::monospace(11.5),
            BADGE_TEXT,
        );
        y += 32.0 + 12.0;

        // Chip row: workspace + mode, left-aligned to the card with 8px pad.
        let row_x = cursor + 8.0;
        let ws_name = std::path::Path::new(&self.workspace)
            .file_name()
            .map(|s| s.to_string_lossy().to_string())
            .unwrap_or_else(|| "Choose workspace".into());
        let mut x = row_x;
        let ws_w = 20.0 + 6.0 + ui.fonts(|f| f.layout_no_wrap(ws_name.clone(), egui::FontId::proportional(13.0), TEXT_1).size().x) + 18.0;
        let ws_rect = egui::Rect::from_min_size(egui::pos2(x, y), egui::vec2(ws_w, 28.0));
        let ws_resp = ui.interact(ws_rect, egui::Id::new("hero_ws"), egui::Sense::click());
        if ws_resp.hovered() || self.open_menu.as_ref().map(|m| m.kind) == Some(MenuKind::HeroWorkspace) {
            ui.painter().rect_filled(ws_rect, R_SM, HOVER());
        }
        folder(ui.painter(), egui::Rect::from_center_size(egui::pos2(x + 10.0, y + 14.0), egui::vec2(16.0, 16.0)), TEXT_1, true);
        ui.painter().text(egui::pos2(x + 22.0, y + 14.0), egui::Align2::LEFT_CENTER, &ws_name, egui::FontId::proportional(13.0), TEXT_1);
        chevron_down(ui.painter(), egui::pos2(ws_rect.right() - 9.0, y + 14.0), TEXT_4, 12.0);
        if ws_resp.clicked() {
            self.toggle_menu(MenuKind::HeroWorkspace, ws_rect);
        }
        x = ws_rect.right() + 12.0;
        let mode_label = self.mode_label();
        let mode_w = 22.0 + 6.0 + ui.fonts(|f| f.layout_no_wrap(mode_label.clone(), egui::FontId::proportional(13.0), TEXT_1).size().x) + 18.0;
        let mode_rect = egui::Rect::from_min_size(egui::pos2(x, y), egui::vec2(mode_w, 28.0));
        let mode_resp = ui.interact(mode_rect, egui::Id::new("hero_mode"), egui::Sense::click());
        if mode_resp.hovered() || self.open_menu.as_ref().map(|m| m.kind) == Some(MenuKind::HeroMode) {
            ui.painter().rect_filled(mode_rect, R_SM, HOVER());
        }
        bolt_mode(ui.painter(), egui::pos2(x + 10.0, y + 14.0), TEXT_1, 14.0);
        ui.painter().text(egui::pos2(x + 22.0, y + 14.0), egui::Align2::LEFT_CENTER, &mode_label, egui::FontId::proportional(13.0), TEXT_1);
        chevron_down(ui.painter(), egui::pos2(mode_rect.right() - 9.0, y + 14.0), TEXT_4, 12.0);
        if mode_resp.clicked() {
            self.toggle_menu(MenuKind::HeroMode, mode_rect);
        }
        y += 28.0 + 12.0;

        // The composer card (hero variant).
        let card_rect = egui::Rect::from_min_size(egui::pos2(cursor, y), egui::vec2(COLUMN, card_h));
        self.composer_card_at(ui, card_rect, true);
    }

    fn mode_label(&self) -> String {
        match self.mode {
            PermissionMode::Plan => "Plan mode".into(),
            PermissionMode::Yolo => "Full Access".into(),
            PermissionMode::ReadOnly => "Workspace Read".into(),
            PermissionMode::Confirm => "Standard mode".into(),
        }
    }

    fn card_height(&self, ui: &egui::Ui, hero: bool) -> f32 {
        let rows = self.chat_input.lines().count().max(1).min(14);
        let floor = if hero { 52.0 } else { 36.0 };
        let input_h = (rows as f32 * 24.0 + 4.0).max(floor);
        let attach_h = if self.pending_images.is_empty() { 0.0 } else { 68.0 };
        let _ = ui;
        8.0 + attach_h + input_h + 12.0 + 34.0 + 6.0
    }

    /// dsh plan-mode notice (info surface above the card).
    fn plan_notice(&mut self, ui: &mut egui::Ui) {
        egui::Frame::none()
            .fill(HOVER())
            .rounding(egui::Rounding::same(R_MD))
            .inner_margin(egui::Margin::symmetric(10.0, 5.0))
            .show(ui, |ui| {
                ui.set_min_width(ui.available_width());
                ui.label(
                    egui::RichText::new(
                        "Plan mode: XenCode researches and proposes a plan without editing files. Approve the plan, switch back to Standard mode and say go ahead to execute it.",
                    )
                    .size(12.0)
                    .color(TEXT_2),
                );
            });
    }

    /// The composer card at the bottom of the conversation (docked variant).
    fn composer_card(&mut self, ui: &mut egui::Ui, hero: bool) {
        let h = self.card_height(ui, hero);
        let w = COLUMN.min(ui.available_width());
        let x = ui.cursor().left() + (ui.available_width() - w) / 2.0;
        let rect = egui::Rect::from_min_size(egui::pos2(x, ui.cursor().top()), egui::vec2(w, h));
        self.composer_card_at(ui, rect, hero);
        ui.allocate_exact_size(egui::vec2(ui.available_width(), h + 8.0), egui::Sense::hover());
    }

    /// The dsh composer card: 28px radius, 850 fill, hairline stroke, the
    /// draft surface, then the control row (+ attach, permission preset,
    /// model seat, round send button).
    fn composer_card_at(&mut self, ui: &mut egui::Ui, rect: egui::Rect, hero: bool) {
        let bg_busy = self.worker.as_ref().map(|w| w.busy.load(Ordering::SeqCst)).unwrap_or(false);
        ui.painter().rect_filled(rect, R_PANEL, CARD);
        ui.painter().rect_stroke(rect, R_PANEL, hairline(BORDER_2()));

        let mut inner = egui::Ui::new(
            ui.ctx().clone(),
            egui::LayerId::new(egui::Order::Background, egui::Id::new("composer_inner")),
            egui::Id::new("composer_ui"),
            egui::UiBuilder::new().max_rect(rect),
        );
        inner.set_clip_rect(rect);
        let mut y = rect.top() + 8.0;

        // Staged attachments strip.
        if !self.pending_images.is_empty() {
            let staged = self.pending_images.clone();
            let mut keep: Vec<PathBuf> = vec![];
            let mut x = rect.left() + 12.0;
            for img in &staged {
                let r = egui::Rect::from_min_size(egui::pos2(x, y), egui::vec2(56.0, 56.0));
                self.thumbnail_at(&mut inner, img, r);
                let x_btn = egui::Rect::from_center_size(egui::pos2(r.right() - 2.0, r.top() + 2.0), egui::vec2(16.0, 16.0));
                let resp = inner.interact(x_btn, egui::Id::new(format!("rm_{}", img.display())), egui::Sense::click());
                inner.painter().circle_filled(x_btn.center(), 7.0, CARD_HIGHER);
                inner.painter().text(x_btn.center(), egui::Align2::CENTER_CENTER, "×", egui::FontId::proportional(10.0), TEXT_2);
                if resp.clicked() {
                    // dropped: not pushed to keep
                } else {
                    keep.push(img.clone());
                }
                x += 64.0;
            }
            self.pending_images = keep;
            y += 68.0;
        }

        // Draft surface. The dsh hint rides the caption ink; the hero keeps
        // its 52px floor but the text sits at the top (4px pad).
        let rows = self.chat_input.lines().count().max(1).min(14);
        let floor = if hero { 52.0 } else { 36.0 };
        let input_h = (rows as f32 * 24.0 + 4.0).max(floor);
        let surface_h = rows as f32 * 24.0 + 4.0;
        let input_rect = egui::Rect::from_min_size(egui::pos2(rect.left(), y), egui::vec2(rect.width(), surface_h));
        let edit = inner.allocate_ui_at_rect(input_rect, |ui| {
            ui.add(
                egui::TextEdit::multiline(&mut self.chat_input)
                    .hint_text(if hero {
                        "Describe what you want to build"
                    } else {
                        "Message or run a task, / commands, @ files or sessions"
                    })
                    .desired_width(rect.width() - 22.0)
                    .desired_rows(1)
                    .frame(false)
                    .margin(egui::Margin { left: 14.0, right: 8.0, top: 4.0, bottom: 0.0 })
                    .font(egui::TextStyle::Body),
            )
        });
        if edit.response.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter) && !i.modifiers.shift) {
            self.send_current();
        }
        y += input_h + 12.0;
        let _ = surface_h;

        // Control row.
        let row_top = y;
        let row_rect = egui::Rect::from_min_max(egui::pos2(rect.left(), row_top), egui::pos2(rect.right(), rect.bottom() - 6.0));
        let row_c = egui::pos2(0.0, row_rect.center().y);

        // Attach circle (28px, selector fill).
        let add_rect = egui::Rect::from_center_size(egui::pos2(rect.left() + 22.0, row_c.y), egui::vec2(28.0, 28.0));
        let add_resp = inner.interact(add_rect, egui::Id::new("composer_add"), egui::Sense::click());
        inner.painter().circle_filled(add_rect.center(), 14.0, if add_resp.hovered() { CARD_HIGHER_HOVER } else { CARD_HIGHER });
        plus(inner.painter(), add_rect.center(), TEXT_1, 14.0);
        if add_resp.clicked() {
            if let Some(paths) = rfd::FileDialog::new()
                .add_filter("Images", &["png", "jpg", "jpeg", "gif", "webp", "bmp"])
                .pick_files()
            {
                self.pending_images.extend(paths);
            }
        }

        // Permission preset chip (shield + label + chevron).
        let mode_label = preset_label(self.mode);
        let chip_x = add_rect.right() + 12.0;
        let label_w = inner.fonts(|f| f.layout_no_wrap(mode_label.to_string(), egui::FontId::proportional(13.0), TEXT_2).size().x);
        let chip_rect = egui::Rect::from_min_size(egui::pos2(chip_x, row_c.y - 14.0), egui::vec2(22.0 + label_w + 20.0, 28.0));
        let chip_resp = inner.interact(chip_rect, egui::Id::new("composer_mode"), egui::Sense::click());
        if chip_resp.hovered() {
            inner.painter().rect_filled(chip_rect, R_SM, HOVER());
        }
        shield(inner.painter(), egui::pos2(chip_rect.left() + 11.0, row_c.y), TEXT_3, 14.0);
        inner.painter().text(egui::pos2(chip_rect.left() + 22.0, row_c.y), egui::Align2::LEFT_CENTER, mode_label, egui::FontId::proportional(13.0), TEXT_2);
        chevron_down(inner.painter(), egui::pos2(chip_rect.right() - 10.0, row_c.y), TEXT_4, 12.0);
        if chip_resp.clicked() {
            self.toggle_menu(MenuKind::HeroMode, chip_rect);
        }

        // Model seat (model + effort + chevron) on the right.
        let model_label = self
            .providers
            .iter()
            .find(|(name, _, _)| Some(name) == self.active_provider.as_ref())
            .map(|(_, model, _)| model.clone())
            .unwrap_or_else(|| "DeepSeek-V4-Pro".into());
        let effort_w = inner.fonts(|f| f.layout_no_wrap(self.effort.clone(), egui::FontId::proportional(12.0), TEXT_4).size().x);
        let model_w = inner.fonts(|f| f.layout_no_wrap(model_label.clone(), egui::FontId::proportional(13.0), TEXT_2).size().x);
        let seat_w = model_w + 8.0 + effort_w + 18.0;
        let seat_rect = egui::Rect::from_min_size(
            egui::pos2(send_rect_left(rect, 34.0) - 12.0 - seat_w, row_c.y - 14.0),
            egui::vec2(seat_w, 28.0),
        );
        let seat_resp = inner.interact(seat_rect, egui::Id::new("composer_model"), egui::Sense::click());
        if seat_resp.hovered() {
            inner.painter().rect_filled(seat_rect, R_SM, HOVER());
        }
        inner.painter().text(egui::pos2(seat_rect.left(), row_c.y), egui::Align2::LEFT_CENTER, &model_label, egui::FontId::proportional(13.0), TEXT_2);
        inner.painter().text(egui::pos2(seat_rect.left() + model_w + 8.0, row_c.y), egui::Align2::LEFT_CENTER, &self.effort, egui::FontId::proportional(12.0), TEXT_4);
        chevron_down(inner.painter(), egui::pos2(seat_rect.right() - 9.0, row_c.y), TEXT_4, 12.0);
        if seat_resp.clicked() {
            self.toggle_menu(MenuKind::ModelSeat, seat_rect);
        }

        // Send button: 34px info-fill circle; over an empty draft it sits at
        // 0.4 opacity exactly like the shipped home screenshot.
        let send_rect = egui::Rect::from_center_size(egui::pos2(rect.right() - 25.0, row_c.y), egui::vec2(34.0, 34.0));
        let send_resp = inner.interact(send_rect, egui::Id::new("composer_send"), egui::Sense::click());
        let enabled = !bg_busy && (!self.chat_input.trim().is_empty() || !self.pending_images.is_empty());
        let fill = if enabled { SEND_BLUE } else { SEND_BLUE_DIM };
        inner.painter().circle_filled(send_rect.center(), 17.0, fill);
        let arrow_ink = if enabled { egui::Color32::WHITE } else { egui::Color32::from_rgb(168, 180, 192) };
        if bg_busy {
            let c = send_rect.center();
            inner.painter().rect_filled(egui::Rect::from_center_size(c, egui::vec2(10.0, 10.0)), 2.0, egui::Color32::WHITE);
        } else {
            arrow_up(inner.painter(), send_rect.center(), arrow_ink, 16.0);
        }
        if enabled && send_resp.clicked() {
            self.send_current();
        }
    }

    /// The transcript column (dsh conversation width, centered).
    fn transcript(&mut self, ui: &mut egui::Ui) {
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
                let max_w = COLUMN.min(ui.available_width() - 32.0);
                let col_x = ui.cursor().left() + (ui.available_width() - max_w) / 2.0;
                ui.allocate_ui_at_rect(
                    egui::Rect::from_min_size(egui::pos2(col_x, ui.cursor().top()), egui::vec2(max_w, ui.available_height())),
                    |ui| {
                    ui.add_space(16.0);
                    let entries = self.chat_log.clone();
                    for (idx, entry) in entries.iter().enumerate() {
                        match entry.role.as_str() {
                            "user" => self.user_bubble(ui, entry),
                            "tool" => self.tool_card(ui, idx as u64, entry),
                            "stream" => {
                                let dots = if busy { " …" } else { "" };
                                ui.label(
                                    egui::RichText::new(format!("{}{dots}", entry.text))
                                        .size(14.0)
                                        .color(if busy { TEXT_2 } else { TEXT_1 }),
                                );
                            }
                            _ => {
                                ui.label(egui::RichText::new(&entry.text).size(14.0).color(TEXT_1));
                            }
                        }
                        ui.add_space(14.0);
                    }
                    ui.add_space(10.0);
                    }
                );
            });
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
                        .fill(CARD)
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

    /// Collapsible tool-call card (dark code surface).
    fn tool_card(&mut self, ui: &mut egui::Ui, idx: u64, entry: &ChatEntry) {
        let open = self.open_tools.contains(&idx);
        let (name, body) = match entry.text.split_once(": ") {
            Some((n, b)) => (n.to_string(), b.to_string()),
            None => (entry.text.clone(), String::new()),
        };
        let w = (ui.available_width() * 0.86).min(COLUMN).max(300.0);
        ui.allocate_ui_with_layout(egui::vec2(w, 20.0), egui::Layout::top_down(egui::Align::LEFT), |ui| {
            egui::Frame::none()
                .fill(CODE_BG)
                .rounding(egui::Rounding::same(R_MD))
                .stroke(hairline(BORDER_1()))
                .inner_margin(egui::Margin::symmetric(10.0, 7.0))
                .show(ui, |ui| {
                    ui.with_layout(egui::Layout::left_to_right(egui::Align::Center), |ui| {
                        let (r, resp) = ui.allocate_exact_size(egui::vec2(14.0, 14.0), egui::Sense::click());
                        let c = r.center();
                        let color = TEXT_4;
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
                            egui::RichText::new(format!("{} {}", name, if open { "done" } else { "" }))
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
            let size = tex.size_vec2();
            let scale = (max_w / size.x).min(1.0);
            ui.add(
                egui::Image::from_texture(egui::load::SizedTexture::new(tex.id(), size * scale))
                    .rounding(R_SM),
            );
        }
    }

    fn thumbnail_at(&mut self, ui: &mut egui::Ui, path: &std::path::Path, rect: egui::Rect) {
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
                .rounding(egui::Rounding::same(R_SM))
                .paint_at(ui, egui::Rect::from_center_size(rect.center(), draw));
        }
    }

    // ── Row sync (shared DB) ───────────────────────────────────────────────

    /// Row sync in: adopt a specific shared-DB session (picked in the
    /// sidebar), its rows become the transcript and the next turns append
    /// to the same `chat_sessions`/`agent_messages` rows, on phone or PC.
    fn resume_session(&mut self, id: &str) {
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

    // ── Entry pages (dsh page pattern: header + centered column) ──────────

    fn panel_page(&mut self, ctx: &egui::Context, pane: Pane) {
        egui::CentralPanel::default()
            .frame(egui::Frame::none().fill(BG_BASE))
            .show(ctx, |ui| {
                // Page header: title + caption, hairline below.
                let (rect, _) = ui.allocate_exact_size(egui::vec2(ui.available_width(), 64.0), egui::Sense::hover());
                ui.painter().text(
                    egui::pos2(rect.left() + 24.0, rect.top() + 24.0),
                    egui::Align2::LEFT_CENTER,
                    pane.label(),
                    egui::FontId::proportional(16.0),
                    TEXT_1,
                );
                ui.painter().text(
                    egui::pos2(rect.left() + 24.0, rect.top() + 44.0),
                    egui::Align2::LEFT_CENTER,
                    pane.sub(),
                    egui::FontId::proportional(11.5),
                    TEXT_4,
                );
                ui.painter().line_segment(
                    [egui::pos2(rect.left(), rect.bottom()), egui::pos2(rect.right(), rect.bottom())],
                    hairline(BORDER_1()),
                );
                egui::ScrollArea::vertical()
                    .id_salt("panel")
                    .auto_shrink([false, true])
                    .show(ui, |ui| {
                        let max_w = COLUMN.min(ui.available_width() - 48.0);
                        let col_x = ui.cursor().left() + (ui.available_width() - max_w) / 2.0;
                        ui.allocate_ui_at_rect(
                            egui::Rect::from_min_size(egui::pos2(col_x, ui.cursor().top()), egui::vec2(max_w, ui.available_height())),
                            |ui| {
                            ui.add_space(16.0);
                            match pane {
                                Pane::Goals => self.page_goals(ui),
                                Pane::Telemetry => self.page_telemetry(ui),
                                Pane::Automation => self.page_automation(ui),
                                Pane::Decisions => self.page_decisions(ui),
                                Pane::Events => self.page_events(ui),
                                Pane::Settings => self.page_settings(ui),
                                Pane::Chat => {}
                            }
                            ui.add_space(24.0);
                            }
                        );
                    });
            });
    }

    fn page_goals(&mut self, ui: &mut egui::Ui) {
        let dag = xencode_core::goal::file::load(&self.workspace);
        card(ui, |ui| {
            ui.label(egui::RichText::new("Goal DAG").size(13.0).color(TEXT_1).strong());
            ui.add_space(6.0);
            code_block(ui, |ui| {
                ui.monospace(egui::RichText::new(dag.render()).size(12.0).color(TEXT_2));
            });
            ui.add_space(8.0);
            ui.label(egui::RichText::new(dag.summary_counts()).size(12.5).color(TEXT_3));
        });
        ui.add_space(10.0);
        card(ui, |ui| {
            ui.label(egui::RichText::new("Run").size(13.0).color(TEXT_1).strong());
            ui.add_space(6.0);
            ui.horizontal(|ui| {
                if primary(ui, "Run READY nodes").clicked() {
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
                    ui.label(egui::RichText::new("XP telemetry").size(13.0).color(TEXT_1).strong());
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
                    ui.label(egui::RichText::new("! shared database unavailable, showing the legacy file ledger").size(12.0).color(ERROR));
                });
            }
        }
        ui.add_space(10.0);
        card(ui, |ui| {
            ui.label(egui::RichText::new("Spec radar").size(13.0).color(TEXT_1).strong());
            ui.add_space(6.0);
            code_block(ui, |ui| {
                ui.monospace(egui::RichText::new(xencode_core::telemetry::Telemetry::load(&self.workspace).render_radar()).size(12.0).color(TEXT_2));
            });
        });
    }

    fn page_automation(&mut self, ui: &mut egui::Ui) {
        card(ui, |ui| {
            ui.label(egui::RichText::new("Run a script").size(13.0).color(TEXT_1).strong());
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
            ui.label(egui::RichText::new("Output").size(13.0).color(TEXT_1).strong());
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
                    ui.label(egui::RichText::new(&card_data.title).size(13.0).color(TEXT_1).strong());
                    ui.label(egui::RichText::new(&card_data.id).size(11.0).color(TEXT_4).monospace());
                    if card_data.chosen_index.is_none() {
                        pill(ui, "PENDING", WARN_DIM, WARN);
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
            ui.label(egui::RichText::new("Providers").size(13.0).color(TEXT_1).strong());
            ui.add_space(6.0);
            for p in &cfg.providers {
                ui.horizontal(|ui| {
                    ui.label(egui::RichText::new(&p.name).size(13.0).color(TEXT_1));
                    ui.label(egui::RichText::new(p.kind.as_str()).size(12.0).color(TEXT_3));
                    if Some(&p.name) == cfg.active.as_ref() {
                        pill(ui, "ACTIVE()", SUCCESS_DIM, SUCCESS);
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
                        "No providers yet. Add one with: xencode config set-provider <name> --base-url … --api-key … --model …",
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
                ui.label(egui::RichText::new(&self.settings_note).size(12.0).color(BLUE_400));
            }
        });
        ui.add_space(10.0);
        card(ui, |ui| {
            ui.label(egui::RichText::new("Workspace tools").size(13.0).color(TEXT_1).strong());
            ui.add_space(6.0);
            ui.horizontal(|ui| {
                for pane in [Pane::Goals, Pane::Telemetry, Pane::Automation] {
                    if ghost(ui, pane.label()).clicked() {
                        self.pane = pane;
                    }
                }
            });
            ui.horizontal(|ui| {
                for pane in [Pane::Decisions, Pane::Events] {
                    if ghost(ui, pane.label()).clicked() {
                        self.pane = pane;
                    }
                }
            });
        });
        ui.add_space(10.0);
        card(ui, |ui| {
            ui.label(egui::RichText::new("Paths").size(13.0).color(TEXT_1).strong());
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

/// Left edge of the send circle inside the composer card (28px right pad).
fn send_rect_left(card: egui::Rect, diameter: f32) -> f32 {
    card.right() - 25.0 - diameter / 2.0
}

#[cfg(test)]
mod tests {
    #[test]
    fn icon_is_embedded() {
        // PNG magic
        assert_eq!(&super::ICON_PNG[..8], &[0x89, b'P', b'N', b'G', 0x0D, 0x0A, 0x1A, 0x0A]);
    }

    #[test]
    fn preset_labels_match_dsh() {
        use xencode_core::permissions::PermissionMode;
        assert_eq!(super::preset_label(PermissionMode::Confirm), "Workspace Write");
        assert_eq!(super::preset_label(PermissionMode::Plan), "Plan mode");
    }
}
