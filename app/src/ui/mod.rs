//! Page composition — assembles the shell, sidebar, header and page content
//! exactly like WorkspaceShellLayout for each screenshot.

pub mod chat;
pub mod composer;
pub mod panes;
pub mod settings;
pub mod shell;
pub mod sidebar;
pub mod splash;
pub mod welcome;

use crate::gfx::{Canvas, Rect};
use crate::ui::shell::{HeaderTitle, HEADER_H};

/// Home / draft empty state (the screen you see before starting a task).
pub fn home(c: &mut Canvas) {
    shell::draw_backdrop(c);
    let panel = shell::panel_rect();
    shell::draw_panel(c, panel);
    shell::draw_top_overlay(c);
    shell::draw_header(
        c,
        panel,
        &HeaderTitle::Draft { project: "xencode", branch: "main" },
        false,
        false,
    );
    sidebar::draw(c);
    shell::draw_sidebar_footer(c);
    welcome::draw(
        c,
        Rect::new(panel.x, panel.y + HEADER_H, panel.w, panel.h - HEADER_H),
        19, // evening greeting
    );
}

/// Active conversation with markdown, code block, tool row.
pub fn chat(c: &mut Canvas) {
    shell::draw_backdrop(c);
    let panel = shell::panel_rect();
    shell::draw_panel(c, panel);
    shell::draw_top_overlay(c);
    shell::draw_header(
        c,
        panel,
        &HeaderTitle::Task {
            project: "xencode",
            title: "Bottom-docked terminal",
            summary: Some("main · 4 changed files"),
        },
        false,
        false,
    );
    sidebar::draw(c);
    shell::draw_sidebar_footer(c);
    chat::draw(
        c,
        Rect::new(panel.x, panel.y + HEADER_H, panel.w, panel.h - HEADER_H),
    );
}

/// Settings → Appearance (full-page tab).
pub fn settings(c: &mut Canvas) {
    settings::draw(c);
}

/// Git side pane open next to the conversation.
pub fn git(c: &mut Canvas) {
    shell::draw_backdrop(c);
    let pane_w = 424.0;
    let panel = Rect::new(
        shell::SIDEBAR_W + shell::GAP,
        shell::INSET,
        shell::WIN_W - shell::SIDEBAR_W - shell::GAP - shell::INSET - pane_w - shell::GAP,
        shell::WIN_H - shell::INSET * 2.0,
    );
    let pane = Rect::new(panel.right() + shell::GAP, shell::INSET, pane_w, shell::WIN_H - shell::INSET * 2.0);
    shell::draw_panel(c, panel);
    shell::draw_top_overlay(c);
    shell::draw_header(
        c,
        panel,
        &HeaderTitle::Task {
            project: "xencode",
            title: "Bottom-docked terminal",
            summary: Some("main · 4 changed files"),
        },
        false,
        true,
    );
    sidebar::draw(c);
    shell::draw_sidebar_footer(c);
    // condensed conversation behind (greeting + composer)
    welcome::draw(
        c,
        Rect::new(panel.x, panel.y + HEADER_H, panel.w, panel.h - HEADER_H - 0.0),
        19,
    );
    panes::draw_git_pane(c, pane);
}

/// Terminal docked at the bottom of the conversation column.
pub fn terminal(c: &mut Canvas) {
    shell::draw_backdrop(c);
    let panel = shell::panel_rect();
    shell::draw_panel(c, panel);
    shell::draw_top_overlay(c);
    shell::draw_header(
        c,
        panel,
        &HeaderTitle::Task {
            project: "xencode",
            title: "Bottom-docked terminal",
            summary: None,
        },
        true,
        false,
    );
    sidebar::draw(c);
    shell::draw_sidebar_footer(c);

    let dock_h = 236.0;
    let conv = Rect::new(panel.x, panel.y + HEADER_H, panel.w, panel.h - HEADER_H - dock_h);
    // divider between conversation and terminal comes from the dock border
    welcome::draw(c, conv, 19);
    let dock = Rect::new(panel.x, panel.bottom() - dock_h, panel.w, dock_h);
    panes::draw_terminal_dock(c, dock);
}
