//! Topbar — workspace title + path, right-side action icons (shared by the
//! draft and conversation views).

use egui::{Align2, Color32, FontId, Pos2, Sense, Stroke, Vec2};

use crate::icons;
use crate::theme;
use crate::AppState;

fn text(p: &egui::Painter, pos: Pos2, anchor: Align2, size: f32, color: Color32, s: &str) {
    p.text(pos, anchor, s, FontId::proportional(size), color);
}

pub fn render(ui: &mut egui::Ui, state: &mut AppState) {
    let (tb, _) = ui.allocate_exact_size(
        Vec2::new(ui.available_width(), theme::TOPBAR_HEIGHT),
        Sense::hover(),
    );
    {
        let p = ui.painter();
        p.line_segment(
            [
                Pos2::new(tb.left(), tb.bottom()),
                Pos2::new(tb.right(), tb.bottom()),
            ],
            Stroke::new(1.0_f32, theme::BORDER),
        );
        text(
            p,
            Pos2::new(tb.left() + 16.0, tb.center().y),
            Align2::LEFT_CENTER,
            theme::TEXT_SM,
            theme::FG,
            &state.project,
        );
        text(
            p,
            Pos2::new(
                tb.left() + 24.0 + state.project.len() as f32 * 7.2,
                tb.center().y,
            ),
            Align2::LEFT_CENTER,
            theme::TEXT_CAPTION,
            theme::FG_SUBTLEST,
            &state.project_path,
        );
    }
    ui.horizontal(|ui| {
        ui.add_space(ui.available_width() - 108.0);
        let _ = icons::icon_button(ui, 28.0, icons::clock, theme::FG_SUBTLE);
        let _ = icons::icon_button(ui, 28.0, icons::sparkle, theme::FG_SUBTLE);
        let _ = icons::icon_button(ui, 28.0, icons::kebab, theme::FG_SUBTLE);
    });
}
