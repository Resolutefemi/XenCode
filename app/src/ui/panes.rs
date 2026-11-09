//! Side panes and the bottom-docked terminal.
//! Git pane: GitPane.tsx / GitPaneChangeCard.tsx
//! Terminal: Terminal.tsx (tabs "Terminal" header, xterm body, zai-dark ANSI)

use crate::fonts::{self, Weight};
use crate::gfx::{Canvas, Rect};
use crate::theme::T;

pub const PANE_RADIUS: f32 = 6.0;

fn pane_frame(c: &mut Canvas, r: Rect) {
    c.card(r, PANE_RADIUS, T::BG(), T::BORDER(), 0.5);
}

fn tab_strip(c: &mut Canvas, r: Rect, active_tab: &str) {
    // tab strip h-12 + border-b
    c.line(r.x, r.y + 48.0, r.right(), r.y + 48.0, 1.0, T::DIVIDER());
    // active tab: h-7 rounded-lg bg-selected, pl-2 pr-1, close X
    let tab_w = fonts::measure(active_tab, 13.0, Weight::Bold, false) + 10.0 + 10.0 + 14.0;
    let tab = Rect::new(r.x + 8.0, r.y + 10.0, tab_w, 28.0);
    c.fill_rrect(tab, 6.0, T::SELECTED());
    fonts::draw_mid(c, tab.x + 10.0, tab.cy(), active_tab, 13.0, T::FG(), Weight::Bold, false);
    c.icon("x", tab.right() - 16.0, tab.cy() - 5.0, 10.0, T::FG_SUBTLEST());
    // pane toggle on the right
    c.icon("panel-right", r.right() - 26.0, r.y + 16.0, 16.0, T::FG_SUBTLE());
}

// ---------------------------------------------------------------------------
// Git pane
// ---------------------------------------------------------------------------

struct Change {
    dir: &'static str,
    name: &'static str,
    added: u32,
    removed: u32,
}

const CHANGES: &[Change] = &[
    Change { dir: "rust/crates/xencode-app/src/ui/", name: "terminal.rs", added: 142, removed: 8 },
    Change { dir: "packages/ui/src/app-shell/", name: "WorkspaceShellLayout.tsx", added: 96, removed: 12 },
    Change { dir: "packages/ui/src/", name: "Terminal.tsx", added: 41, removed: 5 },
    Change { dir: "", name: "README.md", added: 6, removed: 1 },
];

const DIFF: &[(&str, bool)] = &[
    ("@@ -118,7 +118,14 @@", false),
    ("    pub fn toggle(&mut self) {", false),
    ("+       if self.docked {", true),
    ("+           self.frame.set_height(self.frame.height() / 2);", true),
    ("-           self.hide();", false),
    ("+           self.collapse();", true),
    ("        self.visible = !self.visible;", false),
];

pub fn draw_git_pane(c: &mut Canvas, r: Rect) {
    pane_frame(c, r);
    tab_strip(c, r, "Git");

    // header: source select + refresh
    let hy = r.y + 48.0 + 10.0;
    let sel_w = 168.0;
    let sel = Rect::new(r.x + 12.0, hy, sel_w, 32.0);
    c.card(sel, 8.0, T::BG(), T::BORDER(), 1.0);
    fonts::draw_mid(c, sel.x + 10.0, sel.cy(), "All branch changes", 13.0, T::FG(), Weight::Normal, false);
    c.icon("chevron-down", sel.right() - 20.0, sel.cy() - 6.0, 12.0, T::FG_SUBTLEST());
    // refresh button
    let ref_x = sel.right() + 8.0;
    c.icon("refresh-cw", ref_x, hy + 8.0, 14.0, T::FG_SUBTLE());
    fonts::draw_mid(c, ref_x + 20.0, hy + 16.0, "Refresh", 13.0, T::FG_SUBTLE(), Weight::Normal, false);

    // change cards
    let mut y = hy + 32.0 + 10.0;
    for (i, change) in CHANGES.iter().enumerate() {
        let expanded = i == 0;
        let row = Rect::new(r.x + 8.0, y, r.w - 16.0, 32.0);
        c.fill_rrect(row, 8.0, T::SURFACE());
        c.icon("file-code", row.x + 10.0, y + 9.0, 14.0, T::FG_SUBTLEST());
        let add_s = format!("+{}", change.added);
        let rem_s = format!("-{}", change.removed);
        let aw = fonts::measure(&add_s, 12.0, Weight::Bold, true);
        let rw = fonts::measure(&rem_s, 12.0, Weight::Bold, true);
        // fit dir + name into the space left of the counts
        let name_w = fonts::measure(change.name, 13.0, Weight::Normal, false);
        let avail = row.w - 32.0 - (aw + rw + 20.0) - 30.0;
        let mut x = row.x + 32.0;
        if !change.dir.is_empty() {
            let mut dir = change.dir;
            let dw = fonts::measure(dir, 11.0, Weight::Normal, false);
            if dw + name_w > avail {
                dir = "\u{2026}/";
            }
            x += fonts::draw_mid(c, x, row.cy(), dir, 11.0, T::FG_SUBTLEST(), Weight::Normal, false);
        }
        fonts::draw_mid(c, x, row.cy(), change.name, 13.0, T::FG(), Weight::Normal, false);
        let rx = row.right() - 28.0;
        fonts::draw_mid(c, rx - aw - rw - 8.0, row.cy(), &add_s, 12.0, T::GIT_ADDED(), Weight::Bold, true);
        fonts::draw_mid(c, rx - rw, row.cy(), &rem_s, 12.0, T::GIT_DELETED(), Weight::Bold, true);
        c.icon("chevron-down", row.right() - 20.0, row.cy() - 7.0, 14.0, T::FG_SUBTLEST());
        y += 32.0;

        if expanded {
            // diff preview
            let diff_h = DIFF.len() as f32 * 19.0 + 8.0;
            let dr = Rect::new(r.x + 8.0, y, r.w - 16.0, diff_h);
            c.fill_rrect(dr, 8.0, T::SURFACE());
            let mut dy = dr.y + 4.0;
            for (text, added) in DIFF {
                let bg = if text.starts_with("@@") {
                    None
                } else if *added {
                    Some(T::DIFF_ADD_BG())
                } else if text.starts_with('-') {
                    Some(T::DIFF_DEL_BG())
                } else {
                    None
                };
                if let Some(bg) = bg {
                    c.fill_rect(Rect::new(dr.x + 4.0, dy, dr.w - 8.0, 19.0), bg);
                }
                let color = if text.starts_with("@@") {
                    T::ICON_BLUE()
                } else if *added {
                    T::GIT_ADDED()
                } else if text.starts_with('-') {
                    T::GIT_DELETED()
                } else {
                    T::FG_SUBTLE()
                };
                fonts::draw(c, dr.x + 12.0, dy + 2.0, text, 11.0, color, Weight::Normal, true);
                dy += 19.0;
            }
            y += diff_h + 4.0;
        } else {
            y += 2.0;
        }
    }
}

// ---------------------------------------------------------------------------
// Bottom-docked terminal (AnimatedTerminalPanel + Terminal.tsx)
// ---------------------------------------------------------------------------

pub fn draw_terminal_dock(c: &mut Canvas, r: Rect) {
    // docked under the conversation: rounded bottom corners, top border
    c.fill_rrect_radii(r, 0.0, 0.0, PANE_RADIUS, PANE_RADIUS, T::BG());
    c.line(r.x, r.y + 0.5, r.right(), r.y + 0.5, 1.0, T::DIVIDER());

    // header: title + shell label + tab + actions
    let hy = r.y + 8.0;
    fonts::draw_mid(c, r.x + 12.0, hy + 12.0, "Terminal", 13.0, T::FG(), Weight::Bold, false);
    let tw = fonts::measure("Terminal", 13.0, Weight::Bold, false);
    fonts::draw_mid(c, r.x + 12.0 + tw + 10.0, hy + 12.0, "powershell", 12.0, T::FG_SUBTLE(), Weight::Normal, false);
    // tab
    let tab_x = r.x + 12.0 + tw + 10.0 + fonts::measure("powershell", 12.0, Weight::Normal, false) + 18.0;
    let tab_w = fonts::measure("xencode 1", 13.0, Weight::Bold, false) + 20.0 + 14.0;
    let tab = Rect::new(tab_x, hy, tab_w, 24.0);
    c.fill_rrect(tab, 6.0, T::SELECTED());
    fonts::draw_mid(c, tab.x + 8.0, tab.cy(), "xencode 1", 12.0, T::FG(), Weight::Bold, false);
    c.icon("x", tab.right() - 14.0, tab.cy() - 5.0, 10.0, T::FG_SUBTLEST());
    // actions right: new terminal + close pane
    c.icon("plus", r.right() - 60.0, hy + 4.0, 16.0, T::FG_SUBTLE());
    c.icon("x", r.right() - 30.0, hy + 4.0, 16.0, T::FG_SUBTLE());

    // body: terminal text (PowerShell prompt, cargo output)
    let cyan = T::TERM_CYAN();
    let blue = T::TERM_BLUE();
    let white = T::TERM_BRIGHT_WHITE();
    let green = T::TERM_GREEN();
    let dim = T::TERM_BRIGHT_BLACK();
    let gray = T::TERM_WHITE();
    let lines: &[&[(&'static str, tiny_skia::Color)]] = &[
        &[("PS ", cyan), ("C:\\dev\\XenCode> ", blue), ("cargo build --release", white)],
        &[("   ", dim), ("Compiling", green), (" xencode-app v3.17.2 (", gray), ("C:\\dev\\XenCode\\rust", gray), (")", gray)],
        &[("    ", dim), ("Finished", green), (" `release` profile [optimized] target(s) in 8.42s", gray)],
        &[("", gray)],
        &[("PS ", cyan), ("C:\\dev\\XenCode> ", blue), ("cargo run -- --screenshot .\\shots", white)],
        &[("Rendering", green), (" splash ", gray), ("...", dim), (" ok", green)],
        &[("Rendering", green), (" home ", gray), ("...", dim), (" ok", green)],
        &[("", gray)],
        &[("PS ", cyan), ("C:\\dev\\XenCode> ", blue)],
    ];
    let lh = 18.5;
    let mut ty = r.y + 46.0;
    for line in lines {
        let mut x = r.x + 16.0;
        for (text, color) in *line {
            if !text.is_empty() {
                x += fonts::draw(c, x, ty, text, 13.0, *color, Weight::Normal, true);
            }
        }
        ty += lh;
    }
    // block cursor after last prompt
    let mut x = r.x + 16.0;
    for (text, _) in lines[lines.len() - 1] {
        x += fonts::measure(text, 13.0, Weight::Normal, true);
    }
    c.fill_rect(Rect::new(x + 1.0, ty - lh + 2.0, 8.0, 16.0), T::TERM_BRIGHT_WHITE());
}
