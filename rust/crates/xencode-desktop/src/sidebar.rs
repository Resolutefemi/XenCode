//! Left sidebar: brand header, new chat, search, conversation list, footer.

use crate::theme::palette;
use crate::Conversation;
use egui::{FontId, Ui};

pub struct SidebarState {
    pub search: String,
}

impl Default for SidebarState {
    fn default() -> Self {
        Self { search: String::new() }
    }
}

/// Draw the sidebar; returns Some(index) when a conversation is selected,
/// or -1 when "New chat" is clicked.
pub fn sidebar_ui(
    ui: &mut Ui,
    state: &mut SidebarState,
    conversations: &mut [Conversation],
    active: usize,
    workspace: &str,
    cloud_label: &str,
) -> (i32, Option<usize>) {
    let mut select = None;
    let mut new_chat = false;

    // Brand header.
    ui.horizontal(|ui| {
        ui.style_mut().override_font_id = Some(FontId::proportional(20.0));
        ui.colored_label(palette::PRIMARY, crate::icons::glyph::BOLT);
        ui.style_mut().override_font_id = Some(FontId::new(15.0, crate::theme::families::bold()));
        ui.colored_label(palette::ON_BACKGROUND, "XenCode");
        ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
            ui.style_mut().override_font_id = Some(FontId::proportional(10.0));
            ui.colored_label(palette::ON_SURFACE_VARIANT, "v0.1 rust");
        });
    });
    ui.add_space(6.0);
    ui.separator();

    // New chat.
    if ui
        .add(egui::Button::new(
            egui::RichText::new(format!("{} New chat", crate::icons::glyph::PLUS)).color(palette::ON_PRIMARY).strong(),
        ).fill(palette::PRIMARY))
        .clicked()
    {
        new_chat = true;
    }
    ui.add_space(4.0);

    // Search.
    ui.add(
        egui::TextEdit::singleline(&mut state.search)
            .hint_text("Search...")
            .desired_width(ui.available_width()),
    );
    ui.add_space(6.0);

    // Conversation list.
    let needle = state.search.to_lowercase();
    let mut to_delete: Option<usize> = None;
    egui::ScrollArea::vertical().show(ui, |ui| {
        for (i, conv) in conversations.iter_mut().enumerate() {
            if !needle.is_empty()
                && !conv.title.to_lowercase().contains(&needle)
                && !conv.items.iter().any(|it| it.matches_text(&needle))
            {
                continue;
            }
            let is_active = i == active;
            let frame = if is_active {
                egui::Frame::default()
                    .fill(palette::SURFACE_VARIANT)
                    .rounding(egui::Rounding::same(8.0))
                    .inner_margin(egui::Margin::symmetric(10.0, 8.0))
                    .stroke(egui::Stroke::new(1.0_f32, palette::OUTLINE))
            } else {
                egui::Frame::default()
                    .fill(egui::Color32::TRANSPARENT)
                    .rounding(egui::Rounding::same(8.0))
                    .inner_margin(egui::Margin::symmetric(10.0, 8.0))
            };
            let response = frame
                .show(ui, |ui| {
                    ui.set_width(ui.available_width());
                    ui.horizontal(|ui| {
                        ui.style_mut().override_font_id = Some(FontId::proportional(12.5));
                        ui.colored_label(
                            if is_active { palette::PRIMARY } else { palette::ON_SURFACE_VARIANT },
                            if is_active { "❯" } else { "·" },
                        );
                        ui.style_mut().override_font_id = Some(FontId::proportional(12.5));
                        ui.add(
                            egui::Label::new(
                                egui::RichText::new(conv.title.as_str())
                                    .strong()
                                    .color(if is_active { palette::ON_BACKGROUND } else { palette::ON_SURFACE_VARIANT }),
                            )
                            .truncate(),
                        );
                        if is_active {
                            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                                if ui.small_button("✕").clicked() {
                                    to_delete = Some(i);
                                }
                            });
                        }
                    });
                })
                .response;
            if response.clicked() {
                select = Some(i);
            }
            response.on_hover_cursor(egui::CursorIcon::PointingHand);
        }
    });

    ui.with_layout(egui::Layout::bottom_up(egui::Align::LEFT), |ui| {
        ui.separator();
        ui.horizontal(|ui| {
            ui.colored_label(palette::PRIMARY, crate::icons::glyph::CLOUD);
            ui.style_mut().override_font_id = Some(FontId::proportional(11.0));
            ui.add(
                egui::Label::new(egui::RichText::new(cloud_label).color(palette::ON_SURFACE_VARIANT)).truncate(),
            );
        });
        ui.horizontal(|ui| {
            ui.colored_label(palette::ON_SURFACE_VARIANT, "⌂");
            ui.style_mut().override_font_id = Some(FontId::monospace(10.5));
            ui.add(
                egui::Label::new(egui::RichText::new(workspace).color(palette::ON_SURFACE_VARIANT)).truncate(),
            );
        });
    });

    let action = if new_chat { -1 } else { select.map(|i| i as i32).unwrap_or(-2) };
    (action, to_delete)
}
