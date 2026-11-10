//! The XenBrowser tab (Phase 6.2) — a real browser surface inside XenCode.
//!
//! Chrome runs headless (or headed) as a Space; its screencast frames are
//! composited into the native renderer: omnibox toolbar on top, viewport
//! below, space status underneath. This mirrors ZCode's browser tab visual
//! language — same tokens, same 28px ghost icon buttons, same dividers.

use crate::fonts::{self, Weight};
use crate::gfx::{Canvas, Rect};
use crate::theme::T;
use crate::ui::shell::draw_icon_button;
use xencode_browser::Frame;

/// Toolbar height (px back/forward/reload + omnibox + menu).
pub const TOOLBAR_H: f32 = 44.0;
/// Status strip under the viewport.
pub const STATUS_H: f32 = 26.0;

/// State of the browser tab — the same struct a windowed GUI would own.
pub struct BrowserPage<'a> {
    pub url: &'a str,
    pub title: &'a str,
    /// One-line status for the footer (space kind, headless flag, errors).
    pub status: &'a str,
    pub loading: bool,
    pub secure: bool,
    /// Latest screencast frame (None → letterboxed empty viewport).
    pub frame: Option<&'a Frame>,
}

/// Draw the browser surface into `area` (the workspace panel content rect).
pub fn draw(c: &mut Canvas, area: Rect, page: &BrowserPage<'_>) {
    // Toolbar row.
    let toolbar = Rect::new(area.x, area.y, area.w, TOOLBAR_H);
    draw_toolbar(c, toolbar, page);
    c.line(
        toolbar.x,
        toolbar.bottom(),
        toolbar.right(),
        toolbar.bottom(),
        1.0,
        T::DIVIDER(),
    );

    // Viewport + status strip.
    let viewport = Rect::new(
        area.x,
        toolbar.bottom(),
        area.w,
        area.h - TOOLBAR_H - STATUS_H,
    );
    draw_viewport(c, viewport, page);

    let status = Rect::new(area.x, viewport.bottom(), area.w, STATUS_H);
    draw_status(c, status, page);
}

fn draw_toolbar(c: &mut Canvas, r: Rect, page: &BrowserPage<'_>) {
    let cy = r.cy();
    let btn_y = cy - 14.0;
    // nav cluster
    draw_icon_button(c, r.x + 8.0, btn_y, 28.0, "arrow-left", T::FG_SUBTLE(), false);
    draw_icon_button(c, r.x + 38.0, btn_y, 28.0, "arrow-right", T::FG_SUBTLEST(), false);
    draw_icon_button(
        c,
        r.x + 68.0,
        btn_y,
        28.0,
        "refresh-cw",
        if page.loading { T::FG() } else { T::FG_SUBTLE() },
        false,
    );

    // Omnibox: rounded field with scheme icon + url, centered text.
    let box_x = r.x + 104.0;
    let box_w = r.w - 104.0 - 40.0;
    let omnibox = Rect::new(box_x, cy - 15.0, box_w, 30.0);
    c.fill_rrect(omnibox, 8.0, T::CARD());
    c.stroke_rrect(omnibox, 8.0, 1.0, T::BORDER());
    let icon = if page.secure { "shield-check" } else { "globe" };
    let icon_color = if page.secure { T::SUCCESS() } else { T::FG_SUBTLE() };
    c.icon(icon, omnibox.x + 10.0, cy - 7.0, 14.0, icon_color);
    let text_x = omnibox.x + 32.0;
    // Clip the URL to the omnibox: draw until the right padding would overflow.
    let max_w = omnibox.w - 44.0;
    let shown = fit_text(page.url, max_w, 12.0);
    fonts::draw_mid(c, text_x, cy, &shown, 12.0, T::FG(), Weight::Normal, false);
    if page.loading {
        let dot_x = omnibox.right() - 16.0;
        c.fill_circle(dot_x, cy, 2.5, T::SKY());
    }

    // Right: menu.
    draw_icon_button(c, r.right() - 34.0, btn_y, 28.0, "ellipsis", T::FG_SUBTLE(), false);
}

fn draw_viewport(c: &mut Canvas, r: Rect, page: &BrowserPage<'_>) {
    // Letterbox background (page canvas behind the frame).
    c.fill_rect(r, T::BLACK());
    let Some(frame) = page.frame else {
        // Empty viewport: centered muted globe + hint.
        c.icon("globe", r.cx() - 16.0, r.cy() - 34.0, 32.0, T::FG_SUBTLEST());
        let hint = if page.status.is_empty() {
            "no frame yet — start a space"
        } else {
            page.status
        };
        let w = fonts::measure(hint, 12.0, Weight::Normal, false);
        fonts::draw_mid(c, r.cx() - w / 2.0, r.cy() + 2.0, hint, 12.0, T::FG_SUBTLEST(), Weight::Normal, false);
        return;
    };
    c.blit_rgba(&frame.rgba, frame.width, frame.height, r, 0.0);
}

fn draw_status(c: &mut Canvas, r: Rect, page: &BrowserPage<'_>) {
    let cy = r.cy();
    fonts::draw_mid(c, r.x + 10.0, cy, page.status, 11.0, T::FG_SUBTLEST(), Weight::Normal, false);
    if !page.title.is_empty() {
        let title = fit_text(page.title, r.w * 0.4, 11.0);
        let tw = fonts::measure(&title, 11.0, Weight::Normal, false);
        fonts::draw_mid(c, r.right() - 10.0 - tw, cy, &title, 11.0, T::FG_SUBTLEST(), Weight::Normal, false);
    }
}

/// Truncate a string (with ellipsis) so it fits `max_w` logical px.
fn fit_text(text: &str, max_w: f32, px: f32) -> String {
    if fonts::measure(text, px, Weight::Normal, false) <= max_w {
        return text.to_string();
    }
    let chars: Vec<char> = text.chars().collect();
    let mut lo = 0usize;
    let mut hi = chars.len();
    while lo < hi {
        let mid = (lo + hi + 1) / 2;
        let candidate: String = chars[..mid].iter().collect::<String>() + "…";
        if fonts::measure(&candidate, px, Weight::Normal, false) <= max_w {
            lo = mid;
        } else {
            hi = mid - 1;
        }
    }
    let mut out: String = chars[..lo].iter().collect();
    if !out.is_empty() {
        out.push('…');
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn fit_text_respects_budget_and_marks_ellipsis() {
        let long = "https://example.com/some/very/long/path/that/does/not/fit?query=1";
        let fitted = fit_text(long, 120.0, 12.0);
        assert!(fitted.ends_with('…'));
        assert!(fonts::measure(&fitted, 12.0, Weight::Normal, false) <= 120.0 + 1.0);
        // short strings pass through untouched
        assert_eq!(fit_text("short", 500.0, 12.0), "short");
    }

    #[test]
    fn browser_page_draws_without_a_frame() {
        let page = BrowserPage {
            url: "https://example.com",
            title: "Example",
            status: "clean-room space · headless",
            loading: false,
            secure: true,
            frame: None,
        };
        let mut c = Canvas::new(1200.0, 800.0, 2.0);
        draw(&mut c, Rect::new(0.0, 0.0, 1200.0, 800.0), &page);
    }

    #[test]
    fn browser_page_draws_with_a_frame() {
        let (w, h) = (8u32, 6u32);
        let frame = Frame::opaque(w, h, [180u8, 30, 30, 255].repeat((w * h) as usize));
        let page = BrowserPage {
            url: "https://example.com/very/long/url/that/will/need/truncation/for/sure",
            title: "A reasonably long document title",
            status: "space · 1280×661",
            loading: true,
            secure: false,
            frame: Some(&frame),
        };
        let mut c = Canvas::new(1200.0, 800.0, 2.0);
        draw(&mut c, Rect::new(268.0, 52.0, 928.0, 740.0), &page);
        // The contain-fit frame must have painted its red pixels somewhere in
        // the viewport region (scan, don't hand-compute an index).
        let data = c.px.data();
        let stride = c.px.width() as usize;
        let mut found = false;
        for y in (96.0 * 2.0) as usize..((96.0 + 670.0) * 2.0) as usize {
            for x in (268.0 * 2.0) as usize..((268.0 + 928.0) * 2.0) as usize {
                let i = (y * stride + x) * 4;
                if data[i] == 180 && data[i + 1] == 30 && data[i + 2] == 30 {
                    found = true;
                    break;
                }
            }
            if found {
                break;
            }
        }
        assert!(found, "frame pixels must appear in the viewport");
    }
}
