//! XenCode — 100% Rust native desktop GUI (eframe/egui).
//! Milestone 1: main workspace screen, faithful to the ZCode design tokens.

mod icons;
mod theme;
mod ui;

use egui::{Color32, Vec2};

pub struct TaskEntry {
    name: String,
    color: Color32,
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
        let title = if first.len() > 40 {
            format!("{}…", &first[..40])
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
        Self {
            project: "rust".to_string(),
            project_path: "C:\\Users\\ariyo\\XenCode\\rust".to_string(),
            draft: String::new(),
            full_access: true,
            model: "glm-5.3 Max".to_string(),
            tasks: vec![
                ("hello".to_string(), theme::SUCCESS),
                ("hello".to_string(), theme::FG_SUBTLEST),
                ("hello".to_string(), theme::FG_SUBTLEST),
            ],
            sidebar: SidebarState { selected_task: None },
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
        ui::welcome::render(ctx, self);
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
            // hint text color for the composer
            let mut fonts_tex: Vec<(String, f32)> = Vec::new();
            let _ = fonts_tex;
            Ok(Box::new(AppState::default()))
        }),
    )
}

// keep Pos2 import used (silences unused import warnings on some toolchains)