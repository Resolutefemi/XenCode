//! Settings full-page tab (SettingsPage.tsx + settingsCodePreview.tsx).
//! Grid: 268px nav + content. Windows: inline window controls top-right.

use crate::fonts::{self, Weight};
use crate::gfx::{Canvas, Rect};
use crate::theme::T;
use crate::ui::shell;

struct NavItem {
    icon: &'static str,
    label: &'static str,
    active: bool,
    beta: bool,
}

const NAV: &[(&str, &[NavItem])] = &[
    (
        "Basics",
        &[
            NavItem { icon: "settings", label: "General", active: false, beta: false },
            NavItem { icon: "palette", label: "Appearance", active: true, beta: false },
            NavItem { icon: "package", label: "Model settings", active: false, beta: false },
            NavItem { icon: "globe", label: "Browser Use", active: false, beta: false },
            NavItem { icon: "hard-drive", label: "Remote devices", active: false, beta: false },
            NavItem { icon: "monitor", label: "Computer Use", active: false, beta: false },
            NavItem { icon: "keyboard", label: "Keyboard Shortcuts", active: false, beta: false },
        ],
    ),
    (
        "Agent capabilities",
        &[
            NavItem { icon: "user", label: "Personalization", active: false, beta: false },
            NavItem { icon: "brain", label: "Memory", active: false, beta: false },
            NavItem { icon: "bot", label: "Subagents", active: false, beta: false },
            NavItem { icon: "blocks", label: "Plugins", active: false, beta: false },
            NavItem { icon: "cable", label: "MCP Servers", active: false, beta: false },
            NavItem { icon: "wand-sparkles", label: "Skills", active: false, beta: false },
            NavItem { icon: "terminal", label: "Commands", active: false, beta: false },
            NavItem { icon: "alarm-clock", label: "Automations", active: false, beta: true },
            NavItem { icon: "anchor", label: "Hooks", active: false, beta: false },
        ],
    ),
    (
        "Data and statistics",
        &[
            NavItem { icon: "refresh-cw", label: "Data sync", active: false, beta: false },
            NavItem { icon: "bar-chart-3", label: "Usage stats", active: false, beta: false },
            NavItem { icon: "folders", label: "Projects", active: false, beta: false },
            NavItem { icon: "archive", label: "Archive management", active: false, beta: false },
        ],
    ),
];

enum RowCtl {
    Select(&'static str),
    Switch(bool),
    Number(&'static str),
}

pub fn draw(c: &mut Canvas) {
    shell::draw_backdrop(c);
    let r = Rect::new(shell::INSET, shell::INSET, shell::WIN_W - shell::INSET * 2.0, shell::WIN_H - shell::INSET * 2.0);
    c.card(r, shell::PANEL_RADIUS, T::BG(), T::BORDER(), 0.5);

    // ---- left nav (268px) ----------------------------------------------
    let nav_w = 268.0;
    let mut y = r.y + 50.0;
    for (group, items) in NAV {
        fonts::draw(c, 14.0, y, group, 11.0, T::FG_SUBTLEST(), Weight::Bold, false);
        y += 19.0;
        for item in *items {
            let row = Rect::new(12.0, y, nav_w - 24.0, 29.0);
            if item.active {
                c.fill_rrect(row, 8.0, T::SELECTED());
            }
            let color = if item.active { T::FG() } else { T::FG_SUBTLE() };
            c.icon(item.icon, row.x + 10.0, y + 8.0, 16.0, color);
            let mut x = row.x + 34.0;
            x += fonts::draw_mid(c, x, row.cy(), item.label, 14.0, color, Weight::Normal, false);
            if item.beta {
                let bw = fonts::measure("Beta", 10.0, Weight::Bold, false) + 10.0;
                let badge = Rect::new(x + 8.0, row.cy() - 8.0, bw, 16.0);
                c.fill_rrect(badge, 4.0, T::SECONDARY());
                fonts::draw_mid(c, badge.x + 5.0, row.cy(), "Beta", 10.0, T::FG_SUBTLE(), Weight::Bold, false);
            }
            y += 29.0;
        }
        y += 10.0;
    }

    // nav footer: avatar + back-to-workspace
    let fy = r.bottom() - 52.0;
    c.fill_circle(30.0, fy + 26.0, 14.0, T::SELECTED());
    c.stroke_circle(30.0, fy + 26.0, 14.0, 1.0, T::BORDER());
    c.icon("user", 23.0, fy + 19.0, 14.0, T::FG_SUBTLE());
    fonts::draw_mid(c, 50.0, fy + 26.0, "Resolute Femi", 13.0, T::FG(), Weight::Normal, false);
    c.icon("arrow-left", 232.0, fy + 18.0, 16.0, T::FG_SUBTLE());

    // ---- content --------------------------------------------------------
    let cx = 268.0 + 36.0;
    let cw = 560.0;
    // window controls top-right
    shell::window_controls(c, r.right() - 10.0, r.y + 12.0);

    let mut y = r.y + 40.0;
    fonts::draw(c, cx, y, "Appearance", 24.0, T::FG(), Weight::Bold, false);
    y += 44.0;

    // Section: Interface Setting
    fonts::draw(c, cx, y, "Interface Setting", 16.0, T::FG(), Weight::Bold, false);
    y += 26.0;
    fonts::draw(c, cx, y, "Choose the app theme and interface text size.", 12.5, T::FG_SUBTLE(), Weight::Normal, false);
    y += 26.0;

    // Card: App theme / UI font size
    let card = Rect::new(cx, y, cw, 64.0 + 1.0 + 64.0);
    c.card(card, 12.0, T::CARD(), T::DIVIDER(), 1.0);
    let row1 = Rect::new(cx, y, cw, 64.0);
    draw_setting_row(c, row1.x + 16.0, row1.y + 24.0, "App theme");
    draw_setting_desc(c, row1.x + 16.0, row1.y + 43.0, "Choose light, dark, or follow the system theme.");
    select_trigger(c, row1.right() - 200.0 - 16.0, row1.cy() - 18.0, 200.0, "moon", "Dark");
    c.line(cx + 16.0, y + 64.0, cx + cw - 16.0, y + 64.0, 1.0, T::DIVIDER());
    let row2 = Rect::new(cx, y + 65.0, cw, 63.0);
    draw_setting_row(c, row2.x + 16.0, row2.y + 24.0, "UI font size");
    draw_setting_desc(c, row2.x + 16.0, row2.y + 43.0, "Set the base interface text size (12-20 px).");
    number_input(c, row2.right() - 100.0 - 16.0, row2.cy() - 18.0, 100.0, "14", "px");
    y += card.h + 28.0;

    // Section: Code settings
    fonts::draw(c, cx, y, "Code settings", 16.0, T::FG(), Weight::Bold, false);
    y += 26.0;
    fonts::draw(c, cx, y, "Control how code blocks are highlighted and laid out.", 12.5, T::FG_SUBTLE(), Weight::Normal, false);
    y += 26.0;

    let rows: [(&str, RowCtl); 4] = [
        ("Dark code theme", RowCtl::Select("github-dark")),
        ("Show line numbers", RowCtl::Switch(true)),
        ("Wrap long lines", RowCtl::Switch(false)),
        ("Code font size", RowCtl::Number("14")),
    ];
    let rh = 56.0;
    let card = Rect::new(cx, y, cw, rh * rows.len() as f32 + 1.0);
    c.card(card, 12.0, T::CARD(), T::DIVIDER(), 1.0);
    for (i, (label, ctl)) in rows.iter().enumerate() {
        let row = Rect::new(cx, y + i as f32 * rh, cw, rh);
        fonts::draw_mid(c, cx + 16.0, row.cy(), label, 14.0, T::FG(), Weight::Normal, false);
        match ctl {
            RowCtl::Select(v) => select_trigger(c, row.right() - 200.0 - 16.0, row.cy() - 18.0, 200.0, "palette", v),
            RowCtl::Switch(on) => switch(c, row.right() - 16.0 - 36.0, row.cy() - 10.0, *on),
            RowCtl::Number(v) => number_input(c, row.right() - 100.0 - 16.0, row.cy() - 18.0, 100.0, v, "px"),
        }
        if i + 1 < rows.len() {
            c.line(cx + 16.0, y + (i + 1) as f32 * rh, cx + cw - 16.0, y + (i + 1) as f32 * rh, 1.0, T::DIVIDER());
        }
    }
    y += card.h + 28.0;

    // Section: Code preview (Dark preview)
    fonts::draw(c, cx, y, "Code preview", 16.0, T::FG(), Weight::Bold, false);
    y += 26.0;
    fonts::draw(c, cx, y, "Dark preview", 12.0, T::FG_SUBTLE(), Weight::Normal, false);
    y += 22.0;
    let pv = Rect::new(cx, y, cw, 110.0);
    c.card(pv, 12.0, T::BG(), T::BORDER(), 0.5);
    c.icon("file-code", pv.x + 12.0, y + 8.0, 13.0, T::FG_SUBTLEST());
    fonts::draw_mid(c, pv.x + 31.0, y + 15.0, "main.rs", 11.0, T::FG_SUBTLEST(), Weight::Normal, true);
    c.icon("copy", pv.right() - 20.0, y + 9.0, 13.0, T::FG_SUBTLEST());
    c.line(pv.x + 0.5, y + 28.0, pv.right() - 0.5, y + 28.0, 1.0, T::DIVIDER());
    let kw = T::CODE_KW();
    let fn_c = T::CODE_FN();
    let plain = T::CODE_PLAIN();
    let str_c = T::CODE_STR();
    let prev: &[&[(&'static str, tiny_skia::Color)]] = &[
        &[("fn ", kw), ("main", fn_c), ("() {", plain)],
        &[("    xencode::start(", plain), ("\"gui\"", str_c), (");", plain)],
        &[("}", plain)],
    ];
    let mut py = y + 34.0;
    for line in prev {
        let mut x = pv.x + 14.0;
        for (text, color) in *line {
            x += fonts::draw(c, x, py, text, 12.0, *color, Weight::Normal, true);
        }
        py += 19.0;
    }
}

fn draw_setting_row(c: &mut Canvas, x: f32, y: f32, label: &str) {
    fonts::draw_mid(c, x, y, label, 14.0, T::FG(), Weight::Normal, false);
}

fn draw_setting_desc(c: &mut Canvas, x: f32, y: f32, desc: &str) {
    fonts::draw_mid(c, x, y, desc, 12.0, T::FG_SUBTLE(), Weight::Normal, false);
}

fn select_trigger(c: &mut Canvas, x: f32, y: f32, w: f32, icon: &str, value: &str) {
    let r = Rect::new(x, y, w, 36.0);
    c.card(r, 8.0, T::BG(), T::BORDER(), 1.0);
    c.icon(icon, x + 10.0, y + 10.0, 14.0, T::FG_SUBTLE());
    fonts::draw_mid(c, x + 32.0, r.cy(), value, 13.0, T::FG(), Weight::Normal, false);
    c.icon("chevron-down", x + w - 22.0, r.cy() - 6.0, 12.0, T::FG_SUBTLEST());
}

fn number_input(c: &mut Canvas, x: f32, y: f32, w: f32, value: &str, suffix: &str) {
    let r = Rect::new(x, y, w, 36.0);
    c.card(r, 8.0, T::BG(), T::BORDER(), 1.0);
    fonts::draw_mid(c, x + 12.0, r.cy(), value, 13.0, T::FG(), Weight::Normal, false);
    let vw = fonts::measure(value, 13.0, Weight::Normal, false);
    fonts::draw_mid(c, x + 12.0 + vw + 6.0, r.cy(), suffix, 11.0, T::FG_SUBTLEST(), Weight::Normal, false);
}

fn switch(c: &mut Canvas, x: f32, y: f32, on: bool) {
    let r = Rect::new(x, y, 36.0, 20.0);
    if on {
        c.fill_rrect(r, 10.0, T::BRAND());
        c.fill_circle(x + 26.0, y + 10.0, 8.0, T::FG_INVERSE());
    } else {
        c.fill_rrect(r, 10.0, T::SECONDARY());
        c.fill_circle(x + 10.0, y + 10.0, 8.0, T::FG());
    }
}
