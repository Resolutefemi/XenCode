//! XenCode — 100% Rust native desktop GUI (eframe/egui).
//! Milestone 2: main workspace screen + live PTY terminal dock + settings view,
//! faithful to the ZCode design tokens.

mod icons;
mod pty;
mod term;
mod theme;
mod ui;

use egui::{Color32, Vec2};

pub use ui::settings::Section as SettingsSection;

#[derive(Clone, Copy, PartialEq, Eq, Default)]
pub enum ThemeMode {
    System,
    #[default]
    Dark,
    Light,
}

pub struct SidebarState {
    pub selected_task: Option<usize>,
}

pub struct AppState {
    pub project: String,
    pub project_path: String,
    pub draft: String,
    pub full_access: bool,
    pub model: String,
    pub tasks: Vec<(String, Color32)>,
    pub sidebar: SidebarState,
    pub settings: ui::settings::SettingsState,
    pub theme: ThemeMode,
    // terminal dock
    pub term: Option<pty::TermSession>,
    pub term_focus: bool,
    pub term_scroll: i64,
    pub dock_collapsed: bool,
    pub dock_height: f32,
    pub term_error: Option<String>,
    // model picker
    pub model_picker_open: bool,
    hour: u8,
}

impl AppState {
    pub fn greeting(&self) -> String {
        let part = match self.hour {
            0..=11 => "Morning",
            12..=17 => "Afternoon",
            _ => "Evening",
        };
        format!("{part}, nice work today")
    }

    pub fn push_task_from_draft(&mut self) {
        let first = self.draft.lines().next().unwrap_or("").to_string();
        let title = if first.chars().count() > 40 {
            format!("{}…", first.chars().take(40).collect::<String>())
        } else {
            first
        };
        self.tasks.insert(0, (title, theme::BRAND_BRIGHT));
        self.draft.clear();
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
        Self {
            project,
            project_path: cwd,
            draft: String::new(),
            full_access: true,
            model: "GLM-5.3".to_string(),
            tasks: vec![
                ("hello".to_string(), theme::SUCCESS),
                ("hello".to_string(), theme::FG_SUBTLEST),
                ("hello".to_string(), theme::FG_SUBTLEST),
            ],
            sidebar: SidebarState {
                selected_task: None,
            },
            settings: ui::settings::SettingsState::default(),
            theme: ThemeMode::default(),
            term: None,
            term_focus: false,
            term_scroll: 0,
            dock_collapsed: false,
            dock_height: theme::TERMINAL_DOCK_HEIGHT,
            term_error: None,
            model_picker_open: false,
            hour,
        }
    }
}

fn chrono_hour() -> u8 {
    // Local wall-clock hour without pulling chrono: UTC hour is close enough
    // for the greeting; refined when the settings port lands (timezone setting).
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    ((secs / 3600) % 24) as u8
}

impl eframe::App for AppState {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        ui::sidebar::render(ctx, self);
        if self.settings.open {
            ui::settings::render(ctx, self);
        } else {
            ui::welcome::render(ctx, self);
        }
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
            Ok(Box::new(app))
        }),
    )
}
