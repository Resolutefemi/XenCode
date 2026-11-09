//! Splash / startup loading (RootStartupLoading.tsx + desktop index.html):
//! 96px gradient badge with the white X + "Starting XenCode".

use crate::fonts::{self, Weight};
use crate::gfx::{Canvas, Rect};
use crate::theme::T;

pub fn draw(c: &mut Canvas) {
    c.fill_all(T::BG());

    let size = 96.0;
    let cx = c.w / 2.0;
    let cy = c.h / 2.0 - 24.0;
    let r = Rect::new(cx - size / 2.0, cy - size / 2.0, size, size);

    // badge: rounded-3xl gradient + 1px inner ring + soft shadow halo
    c.fill_rrect(Rect::new(r.x - 6.0, r.y + 10.0, r.w + 12.0, r.h + 8.0), 26.0, tiny_skia::Color::from_rgba8(0, 0, 0, 60));
    c.fill_rrect_vgrad(r, 24.0, T::SPLASH_TOP(), T::SPLASH_BOTTOM());
    c.stroke_rrect(r.inset(0.5), 24.0, 1.0, T::SPLASH_RING());

    // white X (56px)
    c.x_logo(cx - 28.0, cy - 28.0, 56.0, T::WHITE());

    // label
    let label = "Starting XenCode";
    let lw = fonts::measure(label, 14.0, Weight::Normal, false);
    fonts::draw_mid(c, cx - lw / 2.0, cy + size / 2.0 + 34.0, label, 14.0, T::FG(), Weight::Normal, false);
}
