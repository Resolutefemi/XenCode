//! Active-task conversation: user bubble, reasoning row, markdown text,
//! code block, tool summary row, message toolbar, composer.

use crate::fonts::{self, Weight};
use crate::gfx::{Canvas, Rect};
use crate::theme::T;
use crate::ui::composer::{self, ComposerOpts, SendState};

pub fn draw(c: &mut Canvas, area: Rect) {
    let col_w = 768.0f32.min(area.w - 48.0);
    let col_x = area.cx() - col_w / 2.0;
    let mut y = area.y + 20.0;

    // ---- user bubble (right-aligned, max-w-xl) -------------------------
    let user_text = "Add a bottom-docked terminal to the conversation column and wire it to the same PTY session as the side pane.";
    let lines = fonts::wrap(user_text, 576.0 - 32.0, 14.0, Weight::Normal, false);
    let tw = fonts::wrap_width(&lines, 14.0, Weight::Normal, false);
    let bw = (tw + 32.0).min(576.0);
    let bh = 12.0 + lines.len() as f32 * fonts::line_height(14.0, false) + 12.0;
    let bx = col_x + col_w - bw;
    // rounded-xl with top-right 12px (styles.css override)
    c.fill_rrect_radii(Rect::new(bx, y, bw, bh), 16.0, 12.0, 16.0, 16.0, T::USER_BUBBLE());
    let mut ly = y + 12.0;
    for line in &lines {
        fonts::draw(c, bx + 16.0, ly, line, 14.0, T::FG(), Weight::Normal, false);
        ly += fonts::line_height(14.0, false);
    }
    y += bh + 24.0;

    // ---- reasoning (collapsed) -----------------------------------------
    c.icon("brain", col_x, y, 16.0, T::FG_SUBTLEST());
    let mut rx = col_x + 24.0;
    rx += fonts::draw_mid(c, rx, y + 8.0, "Thought", 13.0, T::FG_SUBTLE(), Weight::Normal, false) + 6.0;
    rx += fonts::draw_mid(c, rx, y + 8.0, "· a few seconds", 13.0, T::FG_SUBTLEST(), Weight::Normal, false) + 6.0;
    c.icon("chevron-right", rx, y, 14.0, T::FG_SUBTLEST());
    y += 32.0;

    // ---- assistant markdown text ---------------------------------------
    let body = "Done — the terminal now docks under the conversation and shares the same PTY session as the side pane. You can collapse it back from the header toggle.";
    let lines = fonts::wrap(body, col_w, 14.0, Weight::Normal, false);
    for line in &lines {
        fonts::draw(c, col_x, y, line, 14.0, T::FG(), Weight::Normal, false);
        y += fonts::line_height(14.0, false);
    }
    y += 12.0;

    // ---- code block (rounded-xl, header + copy) ------------------------
    let kw = T::CODE_KW();
    let plain = T::CODE_PLAIN();
    let fn_c = T::CODE_FN();
    let const_c = T::CODE_CONST();
    let comment = T::CODE_COMMENT();
    let str_c = T::CODE_STR();
    let seg = |s: &'static str, c: tiny_skia::Color| (s, c);
    let code: &[&[( &'static str, tiny_skia::Color)]] = &[
        &[seg("let ", kw), seg("pty ", plain), seg("= ", plain), seg("portable_pty", fn_c), seg("::", plain), seg("native_pty_system", fn_c), seg("();", plain)],
        &[seg("let ", kw), seg("term ", plain), seg("= ", plain), seg("TerminalPane", const_c), seg("::spawn", fn_c), seg("(pty, session)?;", plain)],
        &[seg("// keep the session alive while the pane is docked", comment)],
        &[seg("layout", plain), seg(".dock_bottom", fn_c), seg("(&mut term, ", plain), seg("DockSize", const_c), seg("::Half", const_c), seg(");", plain)],
        &[],
        &[seg("pane", plain), seg(".set_title", fn_c), seg("(", plain), seg("\"xencode 1\"", str_c), seg(");", plain)],
    ];
    let code_lh = 20.0;
    let header_h = 34.0;
    let cbh = header_h + 8.0 + code.len() as f32 * code_lh + 8.0;
    let cb = Rect::new(col_x, y, col_w, cbh);
    c.card(cb, 12.0, T::BG(), T::BORDER(), 0.5);
    // header
    c.icon("file-code", col_x + 12.0, y + 10.0, 14.0, T::FG_SUBTLEST());
    fonts::draw_mid(c, col_x + 32.0, y + 17.0, "rust", 12.0, T::FG_SUBTLEST(), Weight::Normal, true);
    c.icon("wrap-text", col_x + col_w - 40.0, y + 10.0, 14.0, T::FG_SUBTLEST());
    c.icon("copy", col_x + col_w - 20.0, y + 10.0, 14.0, T::FG_SUBTLEST());
    c.line(col_x + 0.5, y + header_h, col_x + col_w - 0.5, y + header_h, 1.0, T::DIVIDER());
    // body
    let mut cly = y + header_h + 4.0;
    for line in code {
        let mut cxx = col_x + 16.0;
        for (text, color) in *line {
            cxx += fonts::draw(c, cxx, cly, text, 13.0, *color, Weight::Normal, true);
        }
        cly += code_lh;
    }
    y += cbh + 16.0;

    // ---- tool summary row ----------------------------------------------
    let label = "Ran";
    let cmd = "cargo build --release";
    let tr_h = 40.0;
    let row = Rect::new(col_x, y, col_w, tr_h);
    c.card(row, 8.0, T::SURFACE(), T::DIVIDER(), 0.5);
    c.icon("square-terminal", col_x + 12.0, y + 12.0, 16.0, T::FG_SUBTLE());
    let mut tx = col_x + 36.0;
    tx += fonts::draw_mid(c, tx, y + 20.0, label, 13.0, T::FG(), Weight::Normal, false) + 10.0;
    fonts::draw_mid(c, tx, y + 20.0, cmd, 12.0, T::FG_SUBTLEST(), Weight::Normal, true);
    // progress pill
    let pill_w = 64.0;
    let pill_x = col_x + col_w - 12.0 - 16.0 - pill_w;
    c.fill_rrect(Rect::new(pill_x, y + tr_h / 2.0 - 2.0, pill_w, 4.0), 2.0, T::SURFACE_HOVER());
    c.fill_rrect(Rect::new(pill_x, y + tr_h / 2.0 - 2.0, pill_w * 0.6, 4.0), 2.0, T::BRAND());
    c.icon("chevron-right", col_x + col_w - 24.0, y + 13.0, 14.0, T::FG_SUBTLEST());
    y += tr_h;

    // ---- message toolbar (copy / like / dislike) -----------------------
    y += 14.0;
    for (i, icon) in ["copy", "thumbs-up", "thumbs-down"].iter().enumerate() {
        c.icon(icon, col_x + 2.0 + i as f32 * 24.0, y, 16.0, T::FG_SUBTLEST());
    }

    // ---- composer pinned to the bottom ---------------------------------
    let comp_y = area.bottom() - composer::HEIGHT - 16.0;
    composer::draw(
        c,
        col_x,
        comp_y,
        col_w,
        &ComposerOpts {
            placeholder: "Ask for follow-up changes",
            typed: None,
            context_header: false,
            mode: "Autonomous mode",
            mode_icon: "shield-check",
            mode_color: T::ICON_BLUE(),
            model: "GLM-5.3",
            send: SendState::Send,
            ctx_ring: Some(0.42),
        },
    );
}
