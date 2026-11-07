//! The conversation transcript: user bubbles, markdown assistant messages,
//! collapsible reasoning, ZCode-style tool-call rows and system lines.

use crate::icons::{glyph, ToolKind, ToolStatus};
use crate::markdown;
use crate::theme::{self, palette};
use egui::{Align, FontId, Ui};

/// One rendered row of the transcript.
#[derive(Clone, Debug)]
pub enum ChatItem {
    User {
        text: String,
    },
    Assistant {
        text: String,
    },
    Reasoning {
        text: String,
        seconds: u32,
        open: bool,
    },
    Tool {
        kind: ToolKind,
        name: String,
        title: String,
        status: ToolStatus,
        detail: String,
        open: bool,
    },
    System {
        text: String,
    },
    Error {
        text: String,
    },
}

impl ChatItem {
    pub fn user(text: impl Into<String>) -> Self {
        ChatItem::User { text: text.into() }
    }

    pub fn assistant(text: impl Into<String>) -> Self {
        ChatItem::Assistant { text: text.into() }
    }

    pub fn reasoning(text: impl Into<String>, seconds: u32) -> Self {
        ChatItem::Reasoning {
            text: text.into(),
            seconds,
            open: false,
        }
    }

    pub fn tool(
        kind: ToolKind,
        name: impl Into<String>,
        title: impl Into<String>,
        status: ToolStatus,
        detail: impl Into<String>,
    ) -> Self {
        ChatItem::Tool {
            kind,
            name: name.into(),
            title: title.into(),
            status,
            detail: detail.into(),
            open: false,
        }
    }

    pub fn system(text: impl Into<String>) -> Self {
        ChatItem::System { text: text.into() }
    }

    fn approx_tokens(&self) -> usize {
        let text = match self {
            ChatItem::User { text }
            | ChatItem::Assistant { text }
            | ChatItem::System { text }
            | ChatItem::Error { text } => text,
            ChatItem::Reasoning { text, .. } => text,
            ChatItem::Tool { title, .. } => title,
        };
        text.len() / 4 + detail_len(self)
    }
}

fn detail_len(item: &ChatItem) -> usize {
    match item {
        ChatItem::Tool { detail, .. } => detail.len() / 4,
        _ => 0,
    }
}

/// Rough context usage estimate (chars/4 against a 128k window).
pub fn context_usage_percent(items: &[ChatItem], streaming: &str) -> u32 {
    let used: usize =
        items.iter().map(ChatItem::approx_tokens).sum::<usize>() + streaming.len() / 4 + 1_200; // system prompt + tool schemas estimate
    let pct = (used as f64 / 128_000.0 * 100.0).ceil() as u32;
    pct.min(100)
}

/// Render one transcript item.
pub fn item_ui(ui: &mut Ui, item: &mut ChatItem, streaming: bool) {
    match item {
        ChatItem::User { text } => {
            ui.with_layout(egui::Layout::right_to_left(Align::Min), |ui| {
                egui::Frame::default()
                    .fill(palette::BUBBLE_USER)
                    .rounding(egui::Rounding {
                        nw: 12.0,
                        ne: 12.0,
                        sw: 12.0,
                        se: 2.0,
                    })
                    .inner_margin(egui::Margin::symmetric(14.0, 10.0))
                    .show(ui, |ui| {
                        ui.set_max_width(560.0);
                        ui.style_mut().override_font_id = Some(FontId::proportional(14.0));
                        ui.add(egui::Label::new(text.as_str()).wrap().selectable(true));
                    });
            });
        }
        ChatItem::Assistant { text } => {
            if streaming {
                let mut job = egui::text::LayoutJob::simple(
                    format!("{text} ▍"),
                    FontId::proportional(14.0),
                    palette::ON_BACKGROUND,
                    ui.available_width() - 8.0,
                );
                if let Some(last) = job.sections.last_mut() {
                    last.format.color = palette::STREAMING_CURSOR;
                }
                ui.add(egui::Label::new(job).wrap().selectable(true));
            } else {
                markdown::markdown(ui, text);
            }
        }
        ChatItem::Reasoning {
            text,
            seconds,
            open,
        } => {
            let header = format!("{} Thought for {}s", glyph::CLOCK, seconds);
            let header_color = palette::REASONING;
            egui::Frame::default()
                .fill(palette::SURFACE_VARIANT)
                .rounding(egui::Rounding::same(8.0))
                .inner_margin(egui::Margin::symmetric(10.0, 6.0))
                .show(ui, |ui| {
                    ui.set_width(ui.available_width());
                    ui.horizontal(|ui| {
                        let toggle = ui.small_button(if *open {
                            glyph::CHEVRON_DOWN
                        } else {
                            glyph::CHEVRON_RIGHT
                        });
                        ui.style_mut().override_font_id = Some(FontId::proportional(12.0));
                        ui.colored_label(header_color, header);
                        if toggle.clicked() {
                            *open = !*open;
                        }
                    });
                    if *open {
                        ui.add_space(2.0);
                        ui.style_mut().override_font_id = Some(FontId::proportional(12.5));
                        ui.add(
                            egui::Label::new(
                                egui::RichText::new(text.as_str())
                                    .color(palette::ON_SURFACE_VARIANT),
                            )
                            .wrap()
                            .selectable(true),
                        );
                    }
                });
        }
        ChatItem::Tool {
            kind,
            name,
            title,
            status,
            detail,
            open,
        } => {
            tool_row(ui, *kind, name, title, *status, detail, open);
        }
        ChatItem::System { text } => {
            egui::Frame::default()
                .fill(palette::SURFACE_VARIANT)
                .rounding(egui::Rounding::same(8.0))
                .inner_margin(egui::Margin::symmetric(12.0, 8.0))
                .show(ui, |ui| {
                    ui.set_width(ui.available_width());
                    ui.style_mut().override_font_id = Some(FontId::monospace(12.0));
                    ui.add(
                        egui::Label::new(
                            egui::RichText::new(text.as_str()).color(palette::ON_SURFACE_VARIANT),
                        )
                        .wrap()
                        .selectable(true),
                    );
                });
        }
        ChatItem::Error { text } => {
            egui::Frame::default()
                .fill(palette::ERROR_CONTAINER)
                .rounding(egui::Rounding::same(8.0))
                .inner_margin(egui::Margin::symmetric(12.0, 8.0))
                .show(ui, |ui| {
                    ui.set_width(ui.available_width());
                    ui.style_mut().override_font_id = Some(FontId::monospace(12.0));
                    ui.colored_label(palette::ON_ERROR_CONTAINER, format!("⚠ {text}"));
                });
        }
    }
}

/// ZCode ToolLayout-style row: chevron, icon, kind label, primary text, status.
fn tool_row(
    ui: &mut Ui,
    kind: ToolKind,
    name: &str,
    title: &str,
    status: ToolStatus,
    detail: &str,
    open: &mut bool,
) {
    let (status_word, status_color) = match status {
        ToolStatus::Running => ("Running", palette::WARNING),
        ToolStatus::Completed => ("Completed", palette::PRIMARY),
        ToolStatus::Failed => ("Failed", palette::ERROR),
    };

    egui::Frame::default()
        .fill(palette::SURFACE_VARIANT)
        .rounding(egui::Rounding::same(8.0))
        .stroke(egui::Stroke::new(1.0_f32, palette::OUTLINE_VARIANT))
        .inner_margin(egui::Margin::symmetric(10.0, 6.0))
        .show(ui, |ui| {
            ui.set_width(ui.available_width());
            ui.horizontal(|ui| {
                if ui
                    .small_button(if *open {
                        glyph::CHEVRON_DOWN
                    } else {
                        glyph::CHEVRON_RIGHT
                    })
                    .clicked()
                {
                    *open = !*open;
                }
                ui.style_mut().override_font_id = Some(FontId::proportional(13.0));
                ui.label(kind.glyph());
                theme::chip(
                    ui,
                    kind.label(),
                    palette::ON_PRIMARY_CONTAINER,
                    palette::PRIMARY_CONTAINER,
                );
                ui.style_mut().override_font_id = Some(FontId::proportional(13.0));
                ui.add(
                    egui::Label::new(egui::RichText::new(title).color(palette::ON_BACKGROUND))
                        .truncate(),
                );
                if !name.is_empty() && name != kind.label().to_lowercase() {
                    ui.style_mut().override_font_id = Some(FontId::monospace(10.5));
                    ui.colored_label(palette::ON_SURFACE_VARIANT, name.to_string());
                }
                ui.with_layout(egui::Layout::right_to_left(Align::Center), |ui| {
                    ui.style_mut().override_font_id = Some(FontId::proportional(11.0));
                    ui.colored_label(status_color, status_word);
                    match status {
                        ToolStatus::Running => ui.colored_label(palette::WARNING, glyph::CLOCK),
                        ToolStatus::Completed => ui.colored_label(palette::PRIMARY, glyph::CHECK),
                        ToolStatus::Failed => ui.colored_label(palette::ERROR, glyph::CROSS),
                    };
                });
            });
            if *open && !detail.is_empty() {
                ui.add_space(4.0);
                detail_ui(ui, detail);
            }
        });
}

/// Tool body: monospace output with per-line diff coloring.
fn detail_ui(ui: &mut Ui, detail: &str) {
    egui::ScrollArea::vertical()
        .max_height(260.0)
        .id_salt(ui.id().with("tool_detail"))
        .show(ui, |ui| {
            egui::Frame::default()
                .fill(palette::CODE_BG)
                .rounding(egui::Rounding::same(6.0))
                .inner_margin(egui::Margin::same(8.0))
                .show(ui, |ui| {
                    ui.set_width(ui.available_width());
                    ui.style_mut().override_font_id = Some(FontId::monospace(11.5));
                    for line in detail.lines() {
                        let color = if line.starts_with('+') {
                            palette::DIFF_ADD
                        } else if line.starts_with('-') {
                            palette::DIFF_DEL
                        } else {
                            palette::ON_SURFACE_VARIANT
                        };
                        ui.add(
                            egui::Label::new(egui::RichText::new(line.to_string()).color(color))
                                .truncate()
                                .selectable(true),
                        );
                    }
                });
        });
}
