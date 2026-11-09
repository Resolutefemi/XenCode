//! Chat composer (ChatPromptEditor + V4ComposerToolbar).
//! Shell: rounded-2xl border-[0.5px] bg-input p-3, rows gap-3.
//! Toolbar: [+ menu] [mode selector] ... [context ring] [model] [send].

use crate::fonts::{self, Weight};
use crate::gfx::{Canvas, Rect};
use crate::theme::T;

pub enum SendState {
    Send,
    Stop,
}

pub struct ComposerOpts<'a> {
    pub placeholder: &'static str,
    pub typed: Option<&'a str>,
    pub context_header: bool,
    pub mode: &'static str,
    pub mode_icon: &'static str,
    pub mode_color: tiny_skia::Color,
    pub model: &'static str,
    pub send: SendState,
    pub ctx_ring: Option<f32>,
}

pub const HEIGHT: f32 = 128.0;

pub fn draw(c: &mut Canvas, x: f32, y: f32, w: f32, o: &ComposerOpts<'_>) {
    let r = Rect::new(x, y, w, HEIGHT);
    // shell: rounded-2xl (16), 0.5px border, bg-input
    c.card(r, 16.0, T::CARD(), T::BORDER(), 0.5);
    let pad = 12.0;
    let mut cy = y + pad;

    // context header: project + branch
    if o.context_header {
        let hy = cy + 14.0;
        c.icon("folder-open", x + pad, hy - 8.0, 16.0, T::FG_SUBTLE());
        let mut hx = x + pad + 22.0;
        hx += fonts::draw_mid(c, hx, hy, "xencode", 13.0, T::FG_SUBTLE(), Weight::Normal, false) + 4.0;
        c.icon("chevron-down", hx, hy - 6.0, 12.0, T::FG_SUBTLEST());
        hx += 20.0;
        c.icon("git-branch", hx, hy - 8.0, 16.0, T::FG_SUBTLE());
        hx += 22.0;
        hx += fonts::draw_mid(c, hx, hy, "main", 13.0, T::FG_SUBTLE(), Weight::Normal, false) + 4.0;
        c.icon("chevron-down", hx, hy - 6.0, 12.0, T::FG_SUBTLEST());
        cy += 28.0 + 12.0;
    }

    // input row
    let iy = cy + 14.0;
    match o.typed {
        Some(text) => {
            fonts::draw(c, x + pad + 2.0, iy - 10.0, text, 14.0, T::FG(), Weight::Normal, false);
        }
        None => {
            fonts::draw(c, x + pad + 2.0, iy - 10.0, o.placeholder, 14.0, T::FG_SUBTLEST(), Weight::Normal, false);
        }
    }

    // toolbar row (h-7 items) pinned to bottom
    let ty = y + HEIGHT - pad - 14.0;
    let mut lx = x + pad;
    c.icon("plus", lx, ty - 8.0, 16.0, T::FG_SUBTLE());
    lx += 24.0;
    // mode selector
    c.icon(o.mode_icon, lx, ty - 8.0, 16.0, o.mode_color);
    lx += 22.0;
    lx += fonts::draw_mid(c, lx, ty, o.mode, 14.0, o.mode_color, Weight::Normal, false) + 4.0;
    c.icon("chevron-down", lx, ty - 6.0, 12.0, o.mode_color);

    // trailing: context ring, model, send
    let send_x = x + w - pad - 28.0;
    let mut rx = send_x - 8.0;
    // model selector
    let model_w = fonts::measure(o.model, 13.0, Weight::Normal, false);
    rx -= model_w;
    fonts::draw_mid(c, rx, ty, o.model, 13.0, T::FG_SUBTLE(), Weight::Normal, false);
    rx -= 20.0;
    c.icon("package", rx, ty - 7.0, 14.0, T::FG_SUBTLE());
    rx -= 18.0;
    c.icon("chevron-down", rx, ty - 6.0, 12.0, T::FG_SUBTLEST());
    if let Some(frac) = o.ctx_ring {
        rx -= 22.0;
        c.ring(rx + 8.0, ty, 8.0, 2.0, frac, T::SURFACE_HOVER(), T::BRAND());
    }

    // send / stop button: rounded-full bg-brand
    match o.send {
        SendState::Send => {
            c.fill_circle(send_x + 14.0, ty, 14.0, T::BRAND());
            c.icon("arrow-up", send_x + 6.0, ty - 8.0, 16.0, T::FG_INVERSE());
        }
        SendState::Stop => {
            c.fill_circle(send_x + 14.0, ty, 14.0, T::SECONDARY());
            c.icon_d_fill("M8 8h8v8H8z", send_x + 6.0, ty - 8.0, 16.0, T::FG());
        }
    }
}
