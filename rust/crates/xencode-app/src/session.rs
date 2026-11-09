//! Session model — tasks, turns and rows for the conversation timeline.
//! Row shapes mirror the ZCode v4 timeline projections (user input bubble,
//! assistant text rows, tool rows, the running "Working" indicator).

use egui::Color32;

use crate::theme;

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum ToolState {
    Pending,
    Running,
    Completed,
    Denied,
    Error,
}

impl ToolState {
    /// ZCode tool.tsx status labels.
    pub fn label(self) -> &'static str {
        match self {
            ToolState::Pending => "Pending",
            ToolState::Running => "Running",
            ToolState::Completed => "Completed",
            ToolState::Denied => "Denied",
            ToolState::Error => "Error",
        }
    }

    pub fn color(self) -> Color32 {
        match self {
            ToolState::Pending => theme::FG_SUBTLE,
            ToolState::Running => theme::WARNING,
            ToolState::Completed => theme::SUCCESS,
            ToolState::Denied => theme::TRAJECTORY_REASONING,
            ToolState::Error => theme::DESTRUCTIVE,
        }
    }
}

#[derive(Clone)]
pub enum Msg {
    /// User input bubble (right-aligned, surface card, small top-right corner).
    User { text: String },
    /// Assistant markdown text row (plain, full width).
    Assistant { text: String },
    /// Tool call row (collapsible card in ZCode; compact row here until the
    /// real agent core feeds it structured payloads).
    Tool {
        name: String,
        detail: String,
        state: ToolState,
    },
    /// Running indicator: pixel orbit + "Working" + elapsed timer.
    Working { started_secs: f64 },
}

#[derive(Clone)]
pub struct Task {
    pub title: String,
    pub color: Color32,
    pub messages: Vec<Msg>,
    /// Expanded state per tool row (collapsible cards).
    pub tool_expanded: Vec<bool>,
    /// True while the task is producing its next turn (shows the orbit row).
    pub working: bool,
    pub working_since: Option<f64>,
}

impl Task {
    pub fn new(prompt: &str) -> Self {
        let first = prompt.lines().next().unwrap_or("").trim();
        let title = if first.chars().count() > 40 {
            format!("{}…", first.chars().take(40).collect::<String>())
        } else if first.is_empty() {
            "New task".to_string()
        } else {
            first.to_string()
        };
        Self {
            title,
            color: theme::IDLE_TASK,
            messages: Vec::new(),
            tool_expanded: Vec::new(),
            working: false,
            working_since: None,
        }
    }

    pub fn push_user_and_begin(&mut self, text: &str, now_secs: f64) {
        self.messages.push(Msg::User {
            text: text.to_string(),
        });
        self.working = true;
        self.working_since = Some(now_secs);
    }
}

pub fn now_secs() -> f64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs_f64())
        .unwrap_or(0.0)
}
