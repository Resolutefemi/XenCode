//! Draft empty state: watermark logo + time greeting + composer + suggestion
//! chips (ConversationDraftEmptyState + ConversationDraftSuggestedPrompts).

use crate::fonts::{self, Weight};
use crate::gfx::{Canvas, Rect};
use crate::theme::T;
use crate::ui::composer::{self, ComposerOpts, SendState};

/// Greeting strings — chat.empty.greeting.* from packages/ui i18n en-US.
pub fn greeting_for_hour(h: u32) -> &'static str {
    match h {
        5..=8 => "Morning, ready when you are",
        9..=11 => "Morning, how can I help?",
        12..=13 => "Noon break?",
        14..=17 => "Good afternoon! Leave the rest to me.",
        18..=22 => "Evening, nice work today",
        _ => "It's late—remember to take care of yourself.",
    }
}

const CHIPS: &[(&str, &str)] = &[
    ("square-code", "Explain this codebase"),
    ("bug", "Fix a failing test"),
    ("wand-sparkles", "Write unit tests"),
    ("wrench", "Refactor a module"),
];

pub fn draw(c: &mut Canvas, area: Rect, hour: u32) {
    let col_w = 672.0f32.min(area.w - 48.0);
    let cx = area.cx();
    let col_x = cx - col_w / 2.0;

    let composer_h = composer::HEIGHT;
    let chip_h = 32.0;
    // block: greeting(36) + 32 + composer + 16 + chips
    let block_h = 36.0 + 32.0 + composer_h + 16.0 + chip_h;
    let top = area.y + (area.h - block_h) / 2.0;
    let greeting_cy = top + 18.0;

    // watermark behind the greeting (offset -mt-10)
    let wm_w = 400.0f32.min(area.w - 40.0);
    let wm_h = wm_w * 360.0 / 436.0;
    c.x_watermark(Rect::new(cx - wm_w / 2.0, greeting_cy - 40.0 - wm_h / 2.0, wm_w, wm_h));

    // greeting (auto-fit 20..30px; fits at 30px for this column)
    let greeting = greeting_for_hour(hour);
    let mut px = 30.0f32;
    while fonts::measure(greeting, px, Weight::Medium, false) > col_w - 32.0 && px > 20.0 {
        px -= 1.0;
    }
    let gw = fonts::measure(greeting, px, Weight::Medium, false);
    fonts::draw_mid(c, cx - gw / 2.0, greeting_cy, greeting, px, T::FG(), Weight::Medium, false);

    // composer
    let comp_y = top + 36.0 + 32.0;
    composer::draw(
        c,
        col_x,
        comp_y,
        col_w,
        &ComposerOpts {
            placeholder: "Ask XenCode anything, @ to add context, / for commands or capabilities",
            typed: None,
            context_header: true,
            mode: "Autonomous mode",
            mode_icon: "shield-check",
            mode_color: T::ICON_BLUE(),
            model: "GLM-5.3",
            send: SendState::Send,
            ctx_ring: None,
        },
    );

    // quick action chips (outline buttons h-8)
    let mut chips: Vec<f32> = Vec::new(); // widths
    let mut total = 0.0;
    for (_, label) in CHIPS {
        let w = 12.0 + 16.0 + 6.0 + fonts::measure(label, 13.0, Weight::Normal, false) + 12.0;
        chips.push(w);
        total += w;
    }
    total += 16.0 * (CHIPS.len() - 1) as f32;
    let mut x = cx - total / 2.0;
    let chip_y = comp_y + composer_h + 16.0;
    for ((icon, label), w) in CHIPS.iter().zip(chips.iter()) {
        let r = Rect::new(x, chip_y, *w, chip_h);
        c.card(r, 8.0, T::BG(), T::BORDER(), 1.0);
        c.icon(icon, x + 12.0, chip_y + 8.0, 16.0, T::FG_SUBTLE());
        fonts::draw_mid(c, x + 34.0, chip_y + chip_h / 2.0, label, 13.0, T::FG_SUBTLE(), Weight::Normal, false);
        x += *w + 16.0;
    }
}
