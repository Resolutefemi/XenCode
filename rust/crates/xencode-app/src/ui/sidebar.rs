//! Project sidebar (ProjectSidebar, 264px): New task / Automations /
//! Plugin Marketplace, "Task list" header with search+filter, project/task
//! rows, footer. Fidelity: project-sidebar/index.tsx + ProjectSidebarNavigation.

use crate::fonts::{self, Weight};
use crate::gfx::{Canvas, Rect};
use crate::theme::T;

const PAD: f32 = 8.0; // px-2
const ROW_X: f32 = PAD;

struct TaskRow {
    title: &'static str,
    time: &'static str,
    active: bool,
}

struct Project {
    name: &'static str,
    tasks: &'static [TaskRow],
}

const PROJECTS: &[Project] = &[
    Project {
        name: "xencode",
        tasks: &[
            TaskRow { title: "Rewrite the GUI in pure Rust", time: "2m", active: true },
            TaskRow { title: "Rebrand the desktop installer icons", time: "1h", active: false },
            TaskRow { title: "Set up the Rust workspace", time: "3h", active: false },
        ],
    },
    Project {
        name: "docs-site",
        tasks: &[TaskRow { title: "Write the quickstart guide", time: "5h", active: false }],
    },
];

pub fn draw(c: &mut Canvas) {
    let content_top = 56.0; // below the top overlay
    let footer_top = crate::ui::shell::WIN_H - crate::ui::shell::INSET - 52.0;
    let mut y = content_top + 12.0; // py-3
    let row_w = crate::ui::shell::SIDEBAR_W - ROW_X * 2.0;

    // New task button (full-width h-8 row)
    draw_row(c, ROW_X, y, row_w, 32.0, false);
    c.icon("message-circle-plus", ROW_X + 10.0, y + 8.0, 16.0, T::FG_SUBTLE());
    fonts::draw_mid(c, ROW_X + 34.0, y + 16.0, "New task", 14.0, T::FG(), Weight::Normal, false);
    y += 32.0 + 4.0;

    // Automations / Plugin Marketplace (ghost lg, h-9)
    for (icon, label) in [("calendar-clock", "Automations"), ("blocks", "Plugin Marketplace")] {
        draw_row(c, ROW_X, y, row_w, 36.0, false);
        c.icon(icon, ROW_X + 10.0, y + 10.0, 16.0, T::FG_SUBTLE());
        fonts::draw_mid(c, ROW_X + 34.0, y + 18.0, label, 14.0, T::FG(), Weight::Normal, false);
        y += 36.0 + 4.0;
    }

    y += 8.0;
    // divider + pt-3
    c.line(0.0, y, crate::ui::shell::SIDEBAR_W, y, 1.0, T::DIVIDER());
    y += 12.0;

    // List header: "Task list" + search / filter icon buttons
    fonts::draw_mid(c, ROW_X + 2.0, y + 12.0, "Task list", 14.0, T::FG_SUBTLE(), Weight::Bold, false);
    let bx = crate::ui::shell::SIDEBAR_W - 8.0 - 24.0;
    draw_icon_24(c, bx, y, "list-filter");
    draw_icon_24(c, bx - 26.0, y, "search");
    y += 32.0;

    // project sections
    for (pi, project) in PROJECTS.iter().enumerate() {
        // project node
        draw_row(c, ROW_X, y, row_w, 32.0, false);
        c.icon("folder", ROW_X + 10.0, y + 8.0, 16.0, T::FG_SUBTLE());
        fonts::draw_mid(c, ROW_X + 34.0, y + 16.0, project.name, 14.0, T::FG(), Weight::Bold, false);
        y += 32.0;

        for task in project.tasks {
            draw_row(c, ROW_X, y, row_w, 30.0, task.active);
            let dot_x = ROW_X + 16.0;
            if task.active {
                c.fill_circle(dot_x, y + 15.0, 3.0, T::ICON_BLUE());
            } else {
                c.fill_circle(dot_x, y + 15.0, 3.0, T::FG_SUBTLEST());
            }
            let title_color = if task.active { T::FG() } else { T::FG_SUBTLE() };
            fonts::draw_mid(c, dot_x + 12.0, y + 15.0, task.title, 13.0, title_color, Weight::Normal, false);
            let tw = fonts::measure(task.time, 11.0, Weight::Normal, false);
            fonts::draw_mid(c, ROW_X + row_w - 10.0 - tw, y + 15.0, task.time, 11.0, T::FG_SUBTLEST(), Weight::Normal, false);
            y += 30.0;
        }
        if pi + 1 < PROJECTS.len() {
            y += 12.0; // gap-3 between sections
        }
    }
    let _ = footer_top;
}

fn draw_row(c: &mut Canvas, x: f32, y: f32, w: f32, h: f32, active: bool) {
    if active {
        c.fill_rrect(Rect::new(x, y, w, h), 8.0, T::SELECTED());
    }
}

fn draw_icon_24(c: &mut Canvas, x: f32, y: f32, icon: &str) {
    c.icon(icon, x + 5.0, y + 5.0, 14.0, T::FG_SUBTLE());
}
