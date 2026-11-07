//! The composer: input box, model selector, thinking-effort cycle chip,
//! context-usage chip and the send/stop controls.

use crate::theme::{self, palette};
use egui::{FontId, TextEdit, Ui};

/// Reasoning effort levels, cycled by clicking the chip (ZCode behavior).
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum Effort {
    Off,
    Low,
    Medium,
    High,
}

impl Effort {
    pub fn next(self) -> Self {
        match self {
            Effort::Off => Effort::Low,
            Effort::Low => Effort::Medium,
            Effort::Medium => Effort::High,
            Effort::High => Effort::Off,
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Effort::Off => "Off",
            Effort::Low => "Low",
            Effort::Medium => "Medium",
            Effort::High => "High",
        }
    }

    pub fn api_value(self) -> Option<&'static str> {
        match self {
            Effort::Off => None,
            Effort::Low => Some("low"),
            Effort::Medium => Some("medium"),
            Effort::High => Some("high"),
        }
    }
}

/// State + rendering for the message composer.
pub struct Composer {
    pub input: String,
    pub effort: Effort,
}

impl Default for Composer {
    fn default() -> Self {
        Self {
            input: String::new(),
            effort: Effort::Medium,
        }
    }
}

/// Result of a frame's composer interaction.
pub enum ComposerAction {
    None,
    Send(String),
    Stop,
}

impl Composer {
    /// Bundle of per-frame composer context (keeps the argument list sane).
    #[allow(clippy::too_many_arguments)]
    pub fn ui(
        &mut self,
        ui: &mut Ui,
        busy: bool,
        model: &str,
        models: &[String],
        context_pct: u32,
        cloud_label: &str,
        on_model: &mut dyn FnMut(String),
    ) -> ComposerAction {
        let mut action = ComposerAction::None;

        // Chips row.
        ui.horizontal(|ui| {
            // Model selector.
            egui::ComboBox::from_id_salt("model_selector")
                .selected_text(model)
                .width(220.0)
                .show_ui(ui, |ui| {
                    for m in models {
                        let mut selected = m == model;
                        ui.selectable_value(&mut selected, true, m.clone());
                        if selected && m != model {
                            on_model(m.clone());
                        }
                    }
                });

            // Effort cycle chip (brain glyph + level).
            let effort_label = format!(
                "{} Thinking: {}",
                crate::icons::glyph::BRAIN,
                self.effort.label()
            );
            if ui
                .add(
                    egui::Button::new(
                        egui::RichText::new(effort_label).color(palette::ON_PRIMARY_CONTAINER),
                    )
                    .fill(palette::PRIMARY_CONTAINER)
                    .small(),
                )
                .clicked()
            {
                self.effort = self.effort.next();
            }

            // Context usage chip.
            let (ctx_color, ctx_bg) = if context_pct < 60 {
                (palette::ON_SURFACE_VARIANT, palette::SURFACE_VARIANT)
            } else if context_pct < 85 {
                (palette::WARNING, palette::SURFACE_VARIANT)
            } else {
                (palette::ON_ERROR_CONTAINER, palette::ERROR_CONTAINER)
            };
            theme::chip(ui, &format!("◧ {context_pct}%"), ctx_color, ctx_bg);

            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                ui.style_mut().override_font_id = Some(FontId::proportional(11.0));
                ui.colored_label(palette::ON_SURFACE_VARIANT, cloud_label.to_string());
            });
        });

        ui.add_space(4.0);

        // Input row.
        let send_hint = !busy && !self.input.trim().is_empty();
        let desired_rows = self.input.lines().count().clamp(1, 8);
        let response = ui.add(
            TextEdit::multiline(&mut self.input)
                .desired_rows(desired_rows)
                .desired_width(ui.available_width())
                .hint_text(
                    "Message XenCode... Enter to send, Shift+Enter for a new line, / for commands",
                )
                .lock_focus(true),
        );
        if response.lost_focus()
            && ui.input(|i| i.key_pressed(egui::Key::Enter) && !i.modifiers.shift)
            && send_hint
        {
            action = ComposerAction::Send(self.input.trim().to_string());
            self.input.clear();
            response.request_focus();
        }

        ui.add_space(4.0);

        ui.horizontal(|ui| {
            ui.style_mut().override_font_id = Some(FontId::proportional(11.5));
            ui.colored_label(
                palette::ON_SURFACE_VARIANT,
                "Tools: read·write·edit·glob·grep·run + MCP   |   Enter sends   |   /help lists commands",
            );
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                if busy {
                    if ui.button("⏹ Stop").clicked() {
                        action = ComposerAction::Stop;
                    }
                } else {
                    let enabled = !self.input.trim().is_empty();
                    if ui
                        .add_enabled(enabled, egui::Button::new(
                            egui::RichText::new("Send ⏎").color(palette::ON_PRIMARY).strong(),
                        ).fill(palette::PRIMARY))
                        .clicked()
                    {
                        action = ComposerAction::Send(self.input.trim().to_string());
                        self.input.clear();
                    }
                }
            });
        });

        action
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn effort_cycles() {
        assert_eq!(Effort::Off.next(), Effort::Low);
        assert_eq!(Effort::Low.next(), Effort::Medium);
        assert_eq!(Effort::Medium.next(), Effort::High);
        assert_eq!(Effort::High.next(), Effort::Off);
        assert_eq!(Effort::Off.api_value(), None);
        assert_eq!(Effort::High.api_value(), Some("high"));
    }
}
