//! Bottom terminal dock — renders the live PTY grid (crate::pty + crate::term)
//! cell by cell, handles keyboard input and scrollback, and keeps the PTY size
//! in sync with the dock size.

use egui::{Align2, Color32, FontFamily, FontId, Pos2, Rect, Rounding, Sense, Stroke, Vec2};

use crate::icons;
use crate::pty::TermSession;
use crate::term::Cell;
use crate::theme;
use crate::AppState;

const LINE_H: f32 = 16.0;
const FONT_SIZE: f32 = 12.5;

type RunStyle = (Color32, Color32, bool); // (fg, bg, underline)

fn cell_width(p: &egui::Painter) -> f32 {
    let galley = p.layout_no_wrap(
        "M".to_string(),
        FontId::new(FONT_SIZE, FontFamily::Monospace),
        Color32::WHITE,
    );
    galley.size().x.max(7.0)
}

/// Draw one row as grouped style runs (bg rect + text + underline).
fn draw_row(p: &egui::Painter, x0: f32, y: f32, cells: &[Cell], max_w: f32, font: FontId) {
    let cw = cell_width(p);
    let mut run = String::new();
    let mut run_style: Option<RunStyle> = None;
    let mut run_x = x0;
    let flush = |run: &mut String,
                 style: &mut Option<RunStyle>,
                 run_x: &mut f32,
                 p: &egui::Painter,
                 end_x: f32| {
        if let Some((fg, bg, underline)) = style.take() {
            if !run.is_empty() {
                if bg != Color32::TRANSPARENT {
                    p.rect_filled(
                        Rect::from_min_max(Pos2::new(*run_x, y), Pos2::new(end_x, y + LINE_H)),
                        Rounding::ZERO,
                        bg,
                    );
                }
                p.text(
                    Pos2::new(*run_x, y + LINE_H * 0.5),
                    Align2::LEFT_CENTER,
                    run.as_str(),
                    font.clone(),
                    fg,
                );
                if underline {
                    p.line_segment(
                        [
                            Pos2::new(*run_x, y + LINE_H - 1.0),
                            Pos2::new(end_x, y + LINE_H - 1.0),
                        ],
                        Stroke::new(1.0_f32, fg),
                    );
                }
            }
            run.clear();
        }
    };

    for cell in cells {
        let style = (cell.fg, cell.bg, cell.underline);
        match run_style {
            Some(s) if s == style => {}
            _ => {
                let end_x = run_x + run.chars().count() as f32 * cw;
                flush(&mut run, &mut run_style, &mut run_x, p, end_x);
                run_style = Some(style);
            }
        }
        run.push(cell.ch);
        if run_x + run.chars().count() as f32 * cw > x0 + max_w {
            break;
        }
    }
    let end_x = run_x + run.chars().count() as f32 * cw;
    flush(&mut run, &mut run_style, &mut run_x, p, end_x);
}

#[allow(clippy::too_many_lines)]
pub fn render_dock(ui: &mut egui::Ui, state: &mut AppState, dock: Rect) {
    let ctx = ui.ctx().clone();
    let p = &ui.painter().clone();
    p.rect_filled(dock, Rounding::ZERO, theme::NEUTRAL_950);
    p.line_segment(
        [dock.left_top(), dock.right_top()],
        Stroke::new(1.0_f32, theme::BORDER),
    );

    // ---------- header ----------
    let header_h = 36.0;
    let label = state
        .term
        .as_ref()
        .map(|t| t.label.as_str())
        .unwrap_or("TERMINAL");
    p.text(
        Pos2::new(dock.left() + 14.0, dock.top() + header_h * 0.5),
        Align2::LEFT_CENTER,
        label,
        FontId::proportional(10.5),
        theme::FG_SUBTLEST,
    );

    // right header buttons: clear + collapse
    let btn = |ui: &mut egui::Ui, x: f32, icon: fn(&egui::Painter, Rect, Color32)| -> bool {
        let r =
            Rect::from_center_size(Pos2::new(x, dock.top() + header_h * 0.5), Vec2::splat(24.0));
        let resp = ui.allocate_rect(r, Sense::click());
        let hot = resp.hovered();
        if hot {
            ui.painter().rect_filled(r, 6.0, theme::SURFACE_HOVER);
        }
        icon(
            ui.painter(),
            r.shrink(6.0),
            if hot { theme::FG } else { theme::FG_SUBTLE },
        );
        resp.clicked()
    };
    let clear_clicked = btn(ui, dock.right() - 52.0, icons::trash);
    let collapse_clicked = btn(ui, dock.right() - 24.0, icons::chevron_down);
    if clear_clicked {
        if let Some(t) = state.term.as_mut() {
            t.clear();
        }
        state.term_scroll = 0;
    }
    if collapse_clicked {
        state.dock_collapsed = !state.dock_collapsed;
    }

    if state.dock_collapsed {
        return;
    }

    // ---------- grid area ----------
    let grid_rect = Rect::from_min_max(
        Pos2::new(dock.left() + 12.0, dock.top() + header_h),
        Pos2::new(dock.right() - 12.0, dock.bottom() - 8.0),
    );
    let cw = cell_width(p);
    let cols = ((grid_rect.width() / cw).floor() as usize).max(8);
    let rows = ((grid_rect.height() / LINE_H).floor() as usize).max(2);

    // spawn lazily on first frame
    if state.term.is_none() {
        match TermSession::spawn(cols, rows, &state.project_path, ctx.clone()) {
            Ok(session) => state.term = Some(session),
            Err(e) => {
                state.term_error = Some(e.to_string());
            }
        }
    }
    if let Some(t) = state.term.as_mut() {
        t.resize(cols, rows);
    }

    let grid_rect_resp = ui.allocate_rect(grid_rect, Sense::click());
    if grid_rect_resp.clicked() {
        state.term_focus = true;
    }

    // unfocus when clicking outside the dock
    if ctx.input(|i| i.pointer.any_click()) {
        let pos = ctx.input(|i| i.pointer.interact_pos());
        if let Some(pos) = pos {
            if !dock.contains(pos) {
                state.term_focus = false;
            }
        }
    }

    // wheel scrollback
    if grid_rect_resp.hovered() {
        let dy = ctx.input(|i| i.raw_scroll_delta.y);
        if dy != 0.0 {
            let sb_len = state
                .term
                .as_ref()
                .map(|t| t.core.lock().map(|c| c.grid.scrollback.len()).unwrap_or(0))
                .unwrap_or(0) as i64;
            state.term_scroll = (state.term_scroll + (dy / 24.0) as i64).clamp(0, sb_len);
        }
    }

    // keyboard input -> PTY
    if state.term_focus {
        let mut out: Vec<u8> = Vec::new();
        let events = ctx.input(|i| i.events.clone());
        for ev in events {
            match ev {
                egui::Event::Text(t) => {
                    if t == "\u{1b}" {
                        out.extend_from_slice(b"\x1b");
                    } else {
                        out.extend_from_slice(t.as_bytes());
                    }
                }
                egui::Event::Paste(s) => out.extend_from_slice(s.as_bytes()),
                egui::Event::Key {
                    key,
                    pressed: true,
                    modifiers,
                    ..
                } => {
                    let seq: &[u8] = match key {
                        egui::Key::Enter => b"\r",
                        egui::Key::Backspace => b"\x7f",
                        egui::Key::Tab if modifiers.shift => b"\x1b[Z",
                        egui::Key::Tab => b"\t",
                        egui::Key::Escape => b"\x1b",
                        egui::Key::ArrowUp => b"\x1b[A",
                        egui::Key::ArrowDown => b"\x1b[B",
                        egui::Key::ArrowRight => b"\x1b[C",
                        egui::Key::ArrowLeft => b"\x1b[D",
                        egui::Key::Home => b"\x1b[H",
                        egui::Key::End => b"\x1b[F",
                        egui::Key::PageUp => b"\x1b[5~",
                        egui::Key::PageDown => b"\x1b[6~",
                        egui::Key::Delete => b"\x1b[3~",
                        _ => &[],
                    };
                    if !seq.is_empty() {
                        out.extend_from_slice(seq);
                    } else if modifiers.ctrl {
                        match key {
                            egui::Key::C => out.push(0x03),
                            egui::Key::D => out.push(0x04),
                            egui::Key::L => out.push(0x0c),
                            egui::Key::U => out.push(0x15),
                            egui::Key::W => out.push(0x17),
                            egui::Key::Z => out.push(0x1a),
                            _ => {}
                        }
                    }
                }
                _ => {}
            }
        }
        if !out.is_empty() {
            if let Some(t) = state.term.as_mut() {
                t.write(&out);
            }
            state.term_scroll = 0;
        }
    }

    // ---------- draw grid ----------
    let Some(term) = state.term.as_ref() else {
        return;
    };
    let Ok(core) = term.core.lock() else { return };
    let g = &core.grid;
    let font = FontId::new(FONT_SIZE, FontFamily::Monospace);

    let sb: &Vec<Vec<Cell>> = &g.scrollback;
    let total = sb.len() + g.rows;
    let max_scroll = sb.len() as i64;
    state.term_scroll = state.term_scroll.clamp(0, max_scroll);
    let visible_top = total as i64 - rows as i64 - state.term_scroll;
    let visible_top = visible_top.max(0) as usize;

    for vis in 0..rows {
        let line_idx = visible_top + vis;
        let y = grid_rect.top() + vis as f32 * LINE_H;
        if y + LINE_H > grid_rect.bottom() {
            break;
        }
        if line_idx < sb.len() {
            draw_row(
                p,
                grid_rect.left(),
                y,
                &sb[line_idx],
                grid_rect.width(),
                font.clone(),
            );
        } else {
            let row = line_idx - sb.len();
            if row < g.rows {
                let cells = &g.cells[row * g.cols..(row + 1) * g.cols];
                draw_row(
                    p,
                    grid_rect.left(),
                    y,
                    cells,
                    grid_rect.width(),
                    font.clone(),
                );
            }
        }
    }

    // cursor block (only when viewing the live screen)
    if state.term_scroll == 0 && !g.closed && g.cursor_y < g.rows {
        let blink = (ctx.input(|i| i.time) % 1.2) < 0.72;
        if blink {
            // cursor's line index in the (scrollback ++ live grid) space
            let cursor_line = sb.len() as i64 + g.cursor_y as i64;
            let cy = grid_rect.top() + (cursor_line - visible_top as i64) as f32 * LINE_H;
            let cx = grid_rect.left() + g.cursor_x as f32 * cw;
            if cy >= grid_rect.top() && cy + LINE_H <= grid_rect.bottom() {
                let r = Rect::from_min_size(Pos2::new(cx, cy), Vec2::new(cw, LINE_H));
                p.rect_filled(r, Rounding::ZERO, theme::FG_SUBTLE);
                if let Some(cell) = g.cells.get(g.cursor_y * g.cols + g.cursor_x) {
                    if cell.ch != ' ' {
                        p.text(
                            r.center(),
                            Align2::CENTER_CENTER,
                            cell.ch.to_string(),
                            font,
                            theme::NEUTRAL_950,
                        );
                    }
                }
            }
        }
    }

    // spawn failure notice
    if let Some(err) = &state.term_error {
        p.text(
            Pos2::new(grid_rect.left() + 4.0, grid_rect.center().y),
            Align2::LEFT_CENTER,
            format!("terminal: {err}"),
            FontId::proportional(11.5),
            theme::WARNING,
        );
    }
}
