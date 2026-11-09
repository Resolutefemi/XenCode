//! XenCode — 100% Rust native desktop GUI (eframe/egui).
//! Milestone 3: conversation view (chat timeline + composer + working orbit)
//! alongside the draft screen, settings and the live PTY terminal dock,
//! faithful to the ZCode design tokens and layout.

mod fonts;
mod icons;
mod markdown;
mod pty;
mod session;
mod term;
mod theme;
mod ui;

use egui::{Rect, Vec2};

pub use session::{Msg, Task, ToolState};
pub use ui::settings::Section as SettingsSection;

#[derive(Clone, Copy, PartialEq, Eq, Default)]
pub enum View {
    /// Draft screen: greeting + centered composer + suggested prompts.
    #[default]
    Draft,
    /// Conversation view for `open_task`.
    Conversation,
}

#[derive(Clone, Copy, PartialEq, Eq, Default)]
pub enum ThemeMode {
    System,
    #[default]
    Dark,
    Light,
}

pub struct AppState {
    pub project: String,
    pub project_path: String,
    pub git_branch: Option<String>,
    pub view: View,
    pub tasks: Vec<Task>,
    pub open_task: Option<usize>,
    pub draft: String,
    pub last_submitted: String,
    pub full_access: bool,
    pub model: String,
    pub settings: ui::settings::SettingsState,
    pub theme: ThemeMode,
    // composer shared state
    pub composer_focused: bool,
    pub model_picker_open: bool,
    pub model_chip_rect: Option<Rect>,
    // terminal dock
    pub term: Option<pty::TermSession>,
    pub term_focus: bool,
    pub term_scroll: i64,
    pub dock_collapsed: bool,
    pub dock_height: f32,
    pub term_error: Option<String>,
    hour: u8,
}

impl AppState {
    /// ZCode draft greeting (`ConversationDraftEmptyState`): hour boundaries
    /// [5, 9, 12, 14, 18, 23] with the exact en-US strings.
    pub fn greeting(&self) -> &'static str {
        match self.hour {
            5..=8 => "Morning, ready when you are",
            9..=11 => "Morning, how can I help?",
            12..=13 => "Noon break?",
            14..=17 => "Good afternoon! Leave the rest to me.",
            18..=22 => "Evening, nice work today",
            _ => "It's late—remember to take care of yourself.",
        }
    }

    pub fn open_task(&mut self, idx: usize) {
        self.open_task = Some(idx);
        self.view = View::Conversation;
    }

    pub fn new_task(&mut self) {
        self.view = View::Draft;
        self.open_task = None;
        self.draft.clear();
    }

    /// Submit from the draft composer: create the task, seed the first turn.
    pub fn submit_draft(&mut self) {
        let text = self.last_submitted.clone();
        if text.trim().is_empty() {
            return;
        }
        let mut task = Task::new(&text);
        task.push_user_and_begin(&text, session::now_secs());
        self.tasks.insert(0, task);
        self.open_task = Some(0);
        self.view = View::Conversation;
    }
}

impl Default for AppState {
    fn default() -> Self {
        let hour = chrono_hour();
        let cwd = std::env::current_dir()
            .map(|p| p.to_string_lossy().to_string())
            .unwrap_or_default();
        let project = std::path::Path::new(&cwd)
            .file_name()
            .map(|n| n.to_string_lossy().to_string())
            .unwrap_or_else(|| "workspace".to_string());
        let git_branch = git_branch_of(&cwd);
        Self {
            project,
            project_path: cwd,
            git_branch,
            view: View::default(),
            tasks: Vec::new(),
            open_task: None,
            draft: String::new(),
            last_submitted: String::new(),
            full_access: true,
            model: "GLM-5.3".to_string(),
            settings: ui::settings::SettingsState::default(),
            theme: ThemeMode::default(),
            composer_focused: false,
            model_picker_open: false,
            model_chip_rect: None,
            term: None,
            term_focus: false,
            term_scroll: 0,
            dock_collapsed: false,
            dock_height: theme::TERMINAL_DOCK_HEIGHT,
            term_error: None,
            hour,
        }
    }
}

fn git_branch_of(cwd: &str) -> Option<String> {
    let out = std::process::Command::new("git")
        .args(["-C", cwd, "rev-parse", "--abbrev-ref", "HEAD"])
        .output()
        .ok()?;
    let s = String::from_utf8_lossy(&out.stdout).trim().to_string();
    if out.status.success() && !s.is_empty() {
        Some(s)
    } else {
        None
    }
}

fn chrono_hour() -> u8 {
    // Local wall-clock hour via chrono (Local timezone), falling back to UTC.
    chrono::Timelike::hour(&chrono::Local::now()) as u8
}

impl eframe::App for AppState {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        ui::sidebar::render(ctx, self);
        if self.settings.open {
            ui::settings::render(ctx, self);
        } else {
            match self.view {
                View::Draft => ui::welcome::render(ctx, self),
                View::Conversation => ui::chat::render(ctx, self),
            }
        }
        ui::composer::model_picker_popover(ctx, self);
    }
}

fn main() -> eframe::Result<()> {
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_title("XenCode")
            .with_inner_size([1500.0, 950.0])
            .with_min_inner_size([1100.0, 700.0]),
        ..Default::default()
    };

    eframe::run_native(
        "XenCode",
        options,
        Box::new(|cc| {
            fonts::install(&cc.egui_ctx);
            // Dark-only, ZCode token overrides
            let mut visuals = egui::Visuals::dark();
            visuals.panel_fill = theme::BG;
            visuals.window_fill = theme::BG;
            visuals.extreme_bg_color = theme::NEUTRAL_950;
            visuals.faint_bg_color = theme::SURFACE;
            visuals.selection.stroke = egui::Stroke::new(1.0_f32, theme::BRAND);
            visuals.widgets.noninteractive.bg_stroke = egui::Stroke::new(1.0_f32, theme::BORDER);
            visuals.widgets.noninteractive.fg_stroke = egui::Stroke::new(1.0_f32, theme::FG);
            visuals.widgets.inactive.fg_stroke = egui::Stroke::new(1.0_f32, theme::FG);
            visuals.widgets.hovered.bg_fill = theme::SURFACE_HOVER;
            visuals.widgets.hovered.fg_stroke = egui::Stroke::new(1.0_f32, theme::FG);
            let mut style = (*cc.egui_ctx.style()).clone();
            style.visuals = visuals;
            style.spacing.item_spacing = Vec2::new(0.0, 0.0);
            cc.egui_ctx.set_style(style);
            let mut app = AppState::default();
            // headless test hook: XENCODE_OPEN_SETTINGS=1 boots into the settings view
            if std::env::var("XENCODE_OPEN_SETTINGS").as_deref() == Ok("1") {
                app.settings.open = true;
            }
            // headless test hook: XENCODE_SEED_TASK=1 seeds one task so the
            // conversation view can be exercised in e2e screenshots
            if std::env::var("XENCODE_SEED_TASK").as_deref() == Ok("1") {
                let mut task = Task::new("Add a dark mode toggle to the settings page");
                task.messages.push(Msg::User {
                    text: "Add a dark mode toggle to the settings page".to_string(),
                });
                task.messages.push(Msg::Tool {
                    name: "Read".to_string(),
                    detail: "src/ui/settings.rs".to_string(),
                    state: ToolState::Completed,
                });
                task.messages.push(Msg::Tool {
                    name: "Edit".to_string(),
                    detail: "src/ui/settings.rs".to_string(),
                    state: ToolState::Running,
                });
                task.messages.push(Msg::Assistant {
                    text: "I found the theme handling in `settings.rs`.\n\n## Plan\n\n- Add a `ThemeMode` enum\n- Wire the **toggle** to `AppState.theme`\n\n```rust\nlet mode = ThemeMode::Dark;\n```\n\nThe toggle will persist on restart.".to_string(),
                });
                task.working = true;
                task.working_since = Some(session::now_secs() - 14.0);
                app.tasks.push(task);
                app.open_task = Some(0);
                app.view = View::Conversation;
            }
            Ok(Box::new(app))
        }),
    )
}
