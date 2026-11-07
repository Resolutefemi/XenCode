//! The right dock: tab strip + content host for the Git, Terminal and Files panes.

pub mod files;
pub mod git;
pub mod terminal;

use crate::theme::palette;
use egui::Ui;

/// Which dock tab is active.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum DockTab {
    Git,
    Terminal,
    Files,
}

impl DockTab {
    pub fn label(self) -> &'static str {
        match self {
            DockTab::Git => "Git",
            DockTab::Terminal => "Terminal",
            DockTab::Files => "Files",
        }
    }

    pub const ALL: [DockTab; 3] = [DockTab::Git, DockTab::Terminal, DockTab::Files];
}

/// State for the whole dock.
pub struct DockState {
    pub open: bool,
    pub tab: DockTab,
    pub git: git::GitPane,
    pub terminal: terminal::TerminalPane,
    pub files: files::FilesPane,
}

impl DockState {
    pub fn new(workspace: &str) -> Self {
        DockState {
            open: false,
            tab: DockTab::Git,
            git: git::GitPane::new(workspace),
            terminal: terminal::TerminalPane::new(workspace),
            files: files::FilesPane::new(workspace),
        }
    }

    pub fn open_tab(&mut self, tab: DockTab) {
        self.open = true;
        self.tab = tab;
        if tab == DockTab::Git {
            self.git.request_refresh();
        }
    }

}

/// Render the dock content for the active tab.
pub fn dock_ui(ui: &mut Ui, state: &mut DockState) {
    // Tab strip.
    ui.horizontal(|ui| {
        for tab in DockTab::ALL {
            let selected = state.tab == tab;
            let label = if selected {
                egui::RichText::new(tab.label()).color(palette::ON_PRIMARY).strong()
            } else {
                egui::RichText::new(tab.label()).color(palette::ON_SURFACE_VARIANT)
            };
            if ui
                .add(egui::Button::new(label).fill(if selected { palette::PRIMARY } else { palette::SURFACE_VARIANT }).small())
                .clicked()
            {
                state.tab = tab;
                if tab == DockTab::Git {
                    state.git.request_refresh();
                }
            }
        }
        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
            if ui.small_button("✕").clicked() {
                state.open = false;
            }
        });
    });
    ui.separator();

    match state.tab {
        DockTab::Git => state.git.ui(ui),
        DockTab::Terminal => state.terminal.ui(ui),
        DockTab::Files => state.files.ui(ui),
    }
}
