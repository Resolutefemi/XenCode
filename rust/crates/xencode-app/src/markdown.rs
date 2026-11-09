//! Markdown-lite renderer for assistant text rows.
//!
//! Faithful scope for this milestone: paragraphs, ATX headings, bullet and
//! numbered lists, **bold**, *italic*, `inline code` and fenced code blocks —
//! the subset ZCode's Streamdown pipeline renders for plain assistant turns.
//! Tables, images, math and Mermaid land with the richer renderer pass.

use egui::{
    Align, Color32, FontId, FontFamily, Pos2, Rounding, Sense, Stroke, TextFormat, Vec2,
    text::LayoutJob,
};

use crate::theme;

fn base(size: f32) -> FontId {
    FontId::proportional(size)
}

/// Registered bold face (see fonts.rs); falls back to the regular stack when
/// no bold face is found on the system.
fn bold(size: f32) -> FontId {
    FontId::new(size, FontFamily::Name("Bold".into()))
}

fn mono(size: f32) -> FontId {
    FontId::monospace(size)
}

/// Parse inline runs (bold / italic / inline code) into a LayoutJob.
fn inline_job(text: &str, size: f32, color: Color32, wrap_width: f32) -> LayoutJob {
    let mut job = LayoutJob::default();
    let mut plain = String::new();
    let mut rest = text;

    while !rest.is_empty() {
        // find the earliest marker of any kind
        let mut best: Option<(usize, &'static str)> = None;
        for marker in ["**", "`", "*"] {
            if let Some(i) = rest.find(marker) {
                if best.is_none_or(|(bi, _)| i < bi) {
                    best = Some((i, marker));
                }
            }
        }
        let (idx, marker) = match best {
            Some(x) => x,
            None => break,
        };

        let after = &rest[idx + marker.len()..];
        let close = match marker {
            "`" => after.find('`'),
            "**" => after.find("**"),
            "*" => after.find('*'),
            _ => None,
        };
        let close = match close {
            Some(c) if c > 0 => c,
            _ => {
                // no closer: emit the marker as plain text and move past it
                plain.push_str(&rest[..idx + marker.len()]);
                rest = after;
                continue;
            }
        };

        plain.push_str(&rest[..idx]);
        if !plain.is_empty() {
            job.append(
                &std::mem::take(&mut plain),
                0.0,
                TextFormat::simple(base(size), color),
            );
        }
        let inner = &after[..close];
        let fmt = match marker {
            "`" => TextFormat {
                font_id: mono(size - 1.5),
                color: theme::BRAND_BRIGHT,
                background: theme::CARD,
                ..TextFormat::default()
            },
            "**" => TextFormat::simple(bold(size), color),
            _ => {
                let mut f = TextFormat::simple(base(size), color);
                f.italics = true;
                f
            }
        };
        job.append(inner, 0.0, fmt);
        rest = &after[close + marker.len()..];
    }

    plain.push_str(rest);
    if !plain.is_empty() || job.sections.is_empty() {
        job.append(&plain, 0.0, TextFormat::simple(base(size), color));
    }
    job.wrap.max_width = wrap_width.max(60.0);
    job.halign = Align::LEFT;
    job
}

/// One laid-out block with optional background paint (code fences).
struct Block {
    galley: std::sync::Arc<egui::Galley>,
    size: Vec2,
    bg: Option<Color32>,
    /// bullet dot to the left of the text (list rows)
    bullet: bool,
    /// space below this block
    after: f32,
}

/// Render a markdown-ish string into the ui, wrapped at `width`.
pub fn render(ui: &mut egui::Ui, text: &str, width: f32) {
    let mut blocks: Vec<Block> = Vec::new();
    let mut lines = text.lines().peekable();

    while let Some(line) = lines.peek() {
        let line = *line;
        if line.trim().is_empty() {
            lines.next();
            continue;
        }

        // fenced code block
        if line.trim_start().starts_with("```") {
            lines.next();
            let mut code: Vec<String> = Vec::new();
            for l in lines.by_ref() {
                if l.trim_start().starts_with("```") {
                    break;
                }
                code.push(l.to_string());
            }
            let code_text = code.join("\n");
            let job = LayoutJob::simple(
                code_text,
                mono(theme::TEXT_SM),
                theme::FG,
                (width - 24.0).max(120.0),
            );
            let galley = ui.painter().layout_job(job);
            let size = Vec2::new(
                (galley.size().x + 24.0).min(width).max(160.0),
                galley.size().y + 20.0,
            );
            blocks.push(Block { galley, size, bg: Some(theme::CARD), bullet: false, after: 12.0 });
            continue;
        }

        let trimmed = line.trim_start();

        // heading
        if let Some(rest) = trimmed.strip_prefix('#') {
            let level = rest.chars().take_while(|c| *c == '#').count();
            let body = rest.trim_start_matches('#').trim();
            let hsize = match level {
                1 => 19.0,
                2 => 17.0,
                3 => 16.0,
                _ => 15.0,
            };
            let job = inline_job(body, hsize, theme::FG, width);
            let galley = ui.painter().layout_job(job);
            let size = galley.size();
            blocks.push(Block {
                galley,
                size,
                bg: None,
                bullet: false,
                after: 8.0,
            });
            lines.next();
            continue;
        }

        // bullets / numbered list (collect consecutive items)
        if trimmed.starts_with("- ")
            || trimmed.starts_with("* ")
            || list_item(trimmed).is_some()
        {
            let mut items: Vec<(bool, String)> = Vec::new(); // (numbered, body)
            loop {
                match lines.peek() {
                    Some(l)
                        if l.trim_start().starts_with("- ")
                            || l.trim_start().starts_with("* ")
                            || list_item(l.trim_start()).is_some() =>
                    {
                        let t = l.trim_start();
                        if let Some((n, body)) = list_item(t) {
                            items.push((true, format!("{n}. {body}")));
                        } else {
                            let body = t
                                .strip_prefix("- ")
                                .or_else(|| t.strip_prefix("* "))
                                .unwrap_or("");
                            items.push((false, body.to_string()));
                        }
                        lines.next();
                    }
                    _ => break,
                }
            }
            for (_, item) in items {
                let indent = 18.0;
                let job = inline_job(&item, theme::TEXT_BASE, theme::FG, width - indent);
                let galley = ui.painter().layout_job(job);
                let row_h = galley.size().y.max(20.0);
                blocks.push(Block {
                    galley,
                    size: Vec2::new(width - indent, row_h),
                    bg: None,
                    bullet: true,
                    after: 4.0,
                });
            }
            continue;
        }

        // paragraph: gather consecutive plain lines
        let mut para = String::from(trimmed);
        lines.next();
        loop {
            match lines.peek() {
                Some(l)
                    if !l.trim().is_empty()
                        && !l.trim_start().starts_with("```")
                        && !l.trim_start().starts_with('#')
                        && !l.trim_start().starts_with("- ")
                        && !l.trim_start().starts_with("* ")
                        && list_item(l.trim_start()).is_none() =>
                {
                    para.push(' ');
                    para.push_str(l.trim());
                    lines.next();
                }
                _ => break,
            }
        }
        let job = inline_job(&para, theme::TEXT_BASE, theme::FG, width);
        let galley = ui.painter().layout_job(job);
        let size = galley.size();
        blocks.push(Block {
            galley,
            size,
            bg: None,
            bullet: false,
            after: 10.0,
        });
    }

    // draw blocks
    for block in &blocks {
        let (rect, _) = ui.allocate_exact_size(block.size, Sense::hover());
        if ui.is_rect_visible(rect) {
            let p = ui.painter();
            if let Some(bg) = block.bg {
                p.rect_filled(rect, Rounding::same(8.0), bg);
                p.rect_stroke(rect, Rounding::same(8.0), Stroke::new(0.5_f32, theme::BORDER));
            }
            let inner = if block.bg.is_some() {
                rect.shrink2(Vec2::new(12.0, 10.0))
            } else {
                rect
            };
            p.galley(
                Pos2::new(inner.left(), inner.top()),
                block.galley.clone(),
                theme::FG,
            );
            if block.bullet {
                let cy = rect.top() + galley_first_line_center(rect.height());
                p.circle_filled(
                    Pos2::new(rect.left() - 12.0, cy),
                    2.2,
                    theme::FG_SUBTLE,
                );
            }
        }
        ui.add_space(block.after);
    }
}

/// Vertical center of the first text line (for bullet dot alignment).
fn galley_first_line_center(row_height: f32) -> f32 {
    // rows are vertically top-aligned; single-line rows center at half height
    row_height * 0.5
}

/// "1." style list item detection.
fn list_item(t: &str) -> Option<(String, String)> {
    let (head, rest) = t.split_once('.')?;
    if head.len() <= 3 && head.chars().all(|c| c.is_ascii_digit()) {
        let rest = rest.trim_start();
        if !rest.is_empty() {
            return Some((head.to_string(), rest.to_string()));
        }
    }
    None
}
