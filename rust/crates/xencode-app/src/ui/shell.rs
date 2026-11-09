//! Desktop window shell: backdrop, conversation panel, top overlay (sidebar
//! column), workspace header with window controls, sidebar footer.
//! Fidelity source: DesktopTopOverlay.tsx / WorkspaceHeader.tsx /
//! DesktopWindowControls.tsx / WorkspaceShellLayout.tsx (Windows variant).

use crate::fonts::{self, Weight};
use crate::gfx::{Canvas, Rect};
use crate::theme::T;

pub const WIN_W: f32 = 1200.0;
pub const WIN_H: f32 = 800.0;
pub const SIDEBAR_W: f32 = 264.0;
pub const GAP: f32 = 4.0;
pub const INSET: f32 = 4.0;
pub const PANEL_RADIUS: f32 = 6.0; // --workspace-panel-radius, Windows
pub const HEADER_H: f32 = 48.0;

pub fn panel_rect() -> Rect {
    Rect::new(SIDEBAR_W + GAP, INSET, WIN_W - SIDEBAR_W - GAP - INSET, WIN_H - INSET * 2.0)
}

pub fn draw_backdrop(c: &mut Canvas) {
    c.fill_all(T::WIN_ALT());
}

/// The conversation column panel (bg-background, rounded 6px, 0.5px border).
pub fn draw_panel(c: &mut Canvas, r: Rect) {
    c.card(r, PANEL_RADIUS, T::BG(), T::BORDER(), 0.5);
}

/// Floating overlay strip over the sidebar column (Windows: top-1.5, h-12).
pub fn draw_top_overlay(c: &mut Canvas) {
    let y = 6.0;
    let bh = 28.0;
    // logo badge (cross-fades to PanelLeft on hover — static state: badge)
    c.logo_badge(10.0, y + (bh - 20.0) / 2.0, 20.0);
    let iy = y + (bh - 16.0) / 2.0;
    c.icon("arrow-left", 40.0, iy, 16.0, T::FG_SUBTLE());
    c.icon("arrow-right", 70.0, iy, 16.0, T::FG_SUBTLE());
    c.icon("message-circle-plus", 100.0, iy, 16.0, T::FG_SUBTLE());
}

pub enum HeaderTitle {
    /// Draft: project name + git summary
    Draft { project: &'static str, branch: &'static str },
    /// Active task: project + task title (+ optional change summary)
    Task { project: &'static str, title: &'static str, summary: Option<&'static str> },
}

/// Workspace header (h-12, border-b border-border/50) with window controls.
pub fn draw_header(c: &mut Canvas, panel: Rect, title: &HeaderTitle, terminal_open: bool, side_pane_open: bool) {
    let cy = panel.y + HEADER_H / 2.0;
    let mut x = panel.x + 10.0;
    match title {
        HeaderTitle::Draft { project, branch } => {
            x += fonts::draw_mid(c, x, cy, project, 14.0, T::FG(), Weight::Bold, false) + 10.0;
            c.icon("git-branch", x, cy - 7.0, 14.0, T::FG_SUBTLEST());
            x += 18.0;
            fonts::draw_mid(c, x, cy, branch, 12.0, T::FG_SUBTLEST(), Weight::Normal, false);
        }
        HeaderTitle::Task { project, title, summary } => {
            x += fonts::draw_mid(c, x, cy, project, 14.0, T::FG(), Weight::Bold, false) + 8.0;
            fonts::draw_mid(c, x, cy, title, 14.0, T::FG_SUBTLE(), Weight::Normal, false);
            if let Some(sum) = summary {
                let sw = fonts::measure(sum, 12.0, Weight::Normal, false);
                let wc_x0 = panel.right() - 8.0 - (28.0 * 3.0 + 2.0 * 2.0);
                fonts::draw_mid(c, wc_x0 - 98.0 - sw, cy, sum, 12.0, T::FG_SUBTLEST(), Weight::Normal, false);
            }
        }
    }

    // right cluster: terminal toggle, side-pane toggle, help + window controls
    let wc_x0 = panel.right() - 8.0 - (28.0 * 3.0 + 2.0 * 2.0);
    draw_icon_button(c, wc_x0 - 30.0, panel.y + 10.0, 28.0, "circle-help", T::FG_SUBTLE(), false);
    draw_icon_button(c, wc_x0 - 60.0, panel.y + 10.0, 28.0, "panel-right", T::FG_SUBTLE(), side_pane_open);
    draw_icon_button(c, wc_x0 - 90.0, panel.y + 10.0, 28.0, "square-terminal", T::FG_SUBTLE(), terminal_open);
    window_controls(c, panel.right() - 8.0, panel.y + 10.0);

    // border-b border-border/50
    c.line(panel.x, panel.y + HEADER_H, panel.right(), panel.y + HEADER_H, 1.0, T::DIVIDER());
}

/// Windows/Linux inline window controls (minimize / maximize / close), 28px ghost buttons.
pub fn window_controls(c: &mut Canvas, right_edge: f32, y: f32) {
    let size = 28.0;
    let gap = 2.0;
    let iy = y + (size - 16.0) / 2.0;
    let x0 = right_edge - size * 3.0 - gap * 2.0;
    c.icon("minus", x0 + 6.0, iy, 16.0, T::FG_SUBTLE());
    c.window_icon(crate::icons::WINDOW_MAXIMIZE, x0 + size + gap + 6.0, iy, 16.0, T::FG_SUBTLE());
    c.icon("x", x0 + (size + gap) * 2.0 + 6.0, iy, 16.0, T::FG_SUBTLE());
}

pub fn draw_icon_button(c: &mut Canvas, x: f32, y: f32, size: f32, icon: &str, color: tiny_skia::Color, active: bool) {
    if active {
        c.fill_rrect(Rect::new(x, y, size, size), 8.0, T::SELECTED());
    }
    let pad = (size - 16.0) / 2.0;
    c.icon(icon, x + pad, y + pad, 16.0, color);
}

/// Sidebar footer: account avatar + settings (WorkspaceSidebarFooter).
pub fn draw_sidebar_footer(c: &mut Canvas) {
    let h = 52.0;
    let y = WIN_H - INSET - h;
    let x = 16.0;
    // avatar
    c.fill_circle(x + 14.0, y + h / 2.0, 14.0, T::SELECTED());
    c.stroke_circle(x + 14.0, y + h / 2.0, 14.0, 1.0, T::BORDER());
    c.icon("user", x + 7.0, y + h / 2.0 - 7.0, 14.0, T::FG_SUBTLE());
    let tx = x + 34.0;
    fonts::draw_mid(c, tx, y + 20.0, "Resolute Femi", 13.0, T::FG(), Weight::Normal, false);
    fonts::draw_mid(c, tx, y + 36.0, "Individual plan", 11.0, T::FG_SUBTLEST(), Weight::Normal, false);
    // settings trigger
    let bx = 264.0 - 8.0 - 28.0;
    draw_icon_button(c, bx, y + (h - 28.0) / 2.0, 28.0, "settings", T::FG_SUBTLE(), false);
}
