//! Markdown rendering: pulldown-cmark events to egui rich text.
//!
//! Supports headings, paragraphs with inline styles (bold, italic, code,
//! links), ordered/unordered lists (nested), block quotes, thematic rules,
//! fenced code blocks with a language chip + copy button, and tables.

use crate::theme::{self, palette};
use std::collections::VecDeque;

use egui::text::LayoutJob;
use egui::{Color32, FontId, Label, Stroke, TextFormat, Ui};
use pulldown_cmark::{CodeBlockKind, Event, Options, Parser, Tag, TagEnd};

/// One inline run of text with its accumulated style.
#[derive(Default, Clone)]
struct Run {
    text: String,
    bold: bool,
    italic: bool,
    code: bool,
    link: Option<String>,
}

/// Inline content collected as styled runs.
#[derive(Default, Clone)]
struct InlineCtx {
    runs: Vec<Run>,
}

impl InlineCtx {
    fn is_empty(&self) -> bool {
        self.runs.iter().all(|r| r.text.is_empty())
    }

    fn force_bold(mut self) -> Self {
        for r in &mut self.runs {
            r.bold = true;
        }
        self
    }
}

fn run_format(run: &Run, base: &FontId, color: Color32) -> TextFormat {
    if run.code {
        TextFormat {
            font_id: FontId::monospace(base.size - 0.5),
            color: palette::CODE_TEXT,
            background: palette::INLINE_CODE_BG,
            ..Default::default()
        }
    } else {
        let font_id = if run.bold {
            FontId::new(base.size, theme::families::bold())
        } else {
            base.clone()
        };
        let color = if run.italic { Color32::LIGHT_GRAY } else { color };
        let mut fmt = TextFormat {
            font_id,
            color,
            ..Default::default()
        };
        if run.link.is_some() {
            fmt.color = palette::LINK;
            fmt.underline = Stroke::new(1.0_f32, palette::LINK);
        }
        fmt
    }
}

fn layout_job(ctx: &InlineCtx, base: FontId, color: Color32, max_width: f32) -> LayoutJob {
    let mut job = LayoutJob::default();
    let mut text = String::new();
    for run in &ctx.runs {
        if run.text.is_empty() {
            continue;
        }
        let start = text.len();
        text.push_str(&run.text);
        job.sections.push(egui::text::LayoutSection {
            leading_space: 0.0,
            byte_range: start..text.len(),
            format: run_format(run, &base, color),
        });
    }
    job.text = text;
    job.wrap.max_width = max_width;
    job.wrap.break_anywhere = false;
    job
}

/// A parsed block, ready to render.
enum Block {
    Paragraph(InlineCtx),
    Heading(pulldown_cmark::HeadingLevel, InlineCtx),
    Code { lang: String, code: String },
    Item { ctx: InlineCtx, marker: String, indent: f32 },
    Quote(String),
    Table { rows: Vec<Vec<String>> },
    Rule,
}

/// Render a markdown document into `ui`.
pub fn markdown(ui: &mut Ui, md: &str) {
    let max_width = ui.available_width() - 4.0;
    let parser = Parser::new_ext(md, Options::all());

    for block in BlockIter::new(parser) {
        match block {
            Block::Paragraph(ctx) => {
                if !ctx.is_empty() {
                    let job = layout_job(&ctx, FontId::proportional(14.0), palette::ON_BACKGROUND, max_width);
                    ui.add(Label::new(job).wrap());
                }
            }
            Block::Heading(level, ctx) => {
                let size = match level {
                    pulldown_cmark::HeadingLevel::H1 => 22.0,
                    pulldown_cmark::HeadingLevel::H2 => 19.0,
                    pulldown_cmark::HeadingLevel::H3 => 17.0,
                    pulldown_cmark::HeadingLevel::H4 => 15.5,
                    _ => 14.5,
                };
                ui.add_space(4.0);
                let ctx = ctx.force_bold();
                let job = layout_job(&ctx, FontId::proportional(size), palette::ON_BACKGROUND, max_width);
                ui.add(Label::new(job).wrap());
                ui.add_space(2.0);
            }
            Block::Code { lang, code } => {
                code_block(ui, &lang, code.trim_end_matches('\n'));
            }
            Block::Item { ctx, marker, indent } => {
                ui.horizontal_wrapped(|ui| {
                    ui.add_space(indent);
                    ui.style_mut().override_font_id = Some(FontId::proportional(14.0));
                    ui.colored_label(palette::PRIMARY, marker);
                    let job =
                        layout_job(&ctx, FontId::proportional(14.0), palette::ON_BACKGROUND, max_width - indent - 16.0);
                    ui.add(Label::new(job).wrap());
                });
            }
            Block::Quote(inner_md) => {
                let resp = egui::Frame::default()
                    .fill(palette::SURFACE_VARIANT)
                    .stroke(Stroke::new(1.0_f32, palette::OUTLINE_VARIANT))
                    .rounding(egui::Rounding::same(6.0))
                    .inner_margin(egui::Margin { left: 14.0, right: 10.0, top: 8.0, bottom: 8.0 })
                    .show(ui, |ui| {
                        ui.set_max_width(ui.available_width());
                        markdown(ui, &inner_md);
                    })
                    .response;
                let rect = resp.rect;
                ui.painter().vline(
                    rect.left() + 4.0,
                    (rect.top() + 4.0)..=(rect.bottom() - 4.0),
                    Stroke::new(3.0_f32, palette::REASONING),
                );
            }
            Block::Table { rows } => {
                if !rows.is_empty() {
                    let cols = rows.iter().map(|r| r.len()).max().unwrap_or(0).max(1);
                    egui::Grid::new(ui.id().with("md_table"))
                        .num_columns(cols)
                        .striped(true)
                        .spacing(egui::vec2(14.0, 6.0))
                        .show(ui, |ui| {
                            for (ri, row) in rows.iter().enumerate() {
                                for cell in row {
                                    if ri == 0 {
                                        ui.style_mut().override_font_id =
                                            Some(FontId::new(13.0, theme::families::bold()));
                                        ui.colored_label(palette::ON_BACKGROUND, cell);
                                    } else {
                                        ui.style_mut().override_font_id = Some(FontId::proportional(13.0));
                                        ui.colored_label(palette::ON_SURFACE_VARIANT, cell);
                                    }
                                }
                                ui.end_row();
                            }
                        });
                    ui.add_space(4.0);
                }
            }
            Block::Rule => {
                ui.add(egui::Separator::default().horizontal());
            }
        }
    }
}

/// Turns a pulldown-cmark parser into a stream of renderable blocks.
struct BlockIter<'a> {
    parser: Parser<'a>,
    list_stack: Vec<Option<u64>>,
    buf: Vec<Event<'a>>,
    pending: VecDeque<Block>,
}

impl<'a> BlockIter<'a> {
    fn new(parser: Parser<'a>) -> Self {
        Self { parser, list_stack: Vec::new(), buf: Vec::new(), pending: VecDeque::new() }
    }
}

impl<'a> Iterator for BlockIter<'a> {
    type Item = Block;

    fn next(&mut self) -> Option<Block> {
        if let Some(block) = self.pending.pop_front() {
            return Some(block);
        }
        loop {
            let ev = self.parser.next()?;
            match ev {
                Event::Start(Tag::Paragraph) => {
                    collect_inline(&mut self.parser, &mut self.buf);
                    return Some(Block::Paragraph(inline_ctx_from(&self.buf)));
                }
                Event::Start(Tag::Heading { level, .. }) => {
                    collect_inline(&mut self.parser, &mut self.buf);
                    return Some(Block::Heading(level, inline_ctx_from(&self.buf)));
                }
                Event::Start(Tag::CodeBlock(kind)) => {
                    let lang = match kind {
                        CodeBlockKind::Fenced(info) => info
                            .split(|c: char| c == ',' || c.is_whitespace())
                            .next()
                            .unwrap_or("")
                            .to_string(),
                        CodeBlockKind::Indented => String::new(),
                    };
                    let mut code = String::new();
                    while let Some(ev) = self.parser.next() {
                        match ev {
                            Event::End(TagEnd::CodeBlock) => break,
                            Event::Text(t) => code.push_str(&t),
                            Event::SoftBreak | Event::HardBreak => code.push('\n'),
                            _ => {}
                        }
                    }
                    return Some(Block::Code { lang, code });
                }
                Event::Start(Tag::List(start)) => {
                    self.list_stack.push(start);
                }
                Event::End(TagEnd::List(_)) => {
                    self.list_stack.pop();
                }
                Event::Start(Tag::Item) => {
                    let (ctx, marker, indent) =
                        collect_item(&mut self.parser, &mut self.list_stack, &mut self.pending);
                    return Some(Block::Item { ctx, marker, indent });
                }
                Event::Start(Tag::BlockQuote(_)) => {
                    let mut inner: Vec<Event> = Vec::new();
                    let mut depth = 0usize;
                    loop {
                        match self.parser.next() {
                            Some(Event::Start(Tag::BlockQuote(k))) => {
                                depth += 1;
                                inner.push(Event::Start(Tag::BlockQuote(k)));
                            }
                            Some(Event::End(TagEnd::BlockQuote(_))) => {
                                if depth == 0 {
                                    break;
                                }
                                depth -= 1;
                                inner.push(Event::End(TagEnd::BlockQuote(None)));
                            }
                            Some(other) => inner.push(other),
                            None => break,
                        }
                    }
                    return Some(Block::Quote(events_to_md(&inner)));
                }
                Event::Start(Tag::Table(_)) => {
                    let mut rows: Vec<Vec<String>> = Vec::new();
                    let mut cur_row: Vec<String> = Vec::new();
                    let mut cur_cell = String::new();
                    let mut cell_depth = 0usize;
                    while let Some(ev) = self.parser.next() {
                        match ev {
                            Event::Start(Tag::TableCell) => {
                                cell_depth += 1;
                                if cell_depth == 1 {
                                    cur_cell.clear();
                                }
                            }
                            Event::End(TagEnd::TableCell) => {
                                cell_depth = cell_depth.saturating_sub(1);
                                if cell_depth == 0 {
                                    cur_row.push(std::mem::take(&mut cur_cell));
                                }
                            }
                            Event::Text(t) => cur_cell.push_str(&t),
                            Event::Code(t) => cur_cell.push_str(&t),
                            Event::SoftBreak => cur_cell.push(' '),
                            Event::End(TagEnd::TableHead) | Event::End(TagEnd::TableRow) => {
                                if !cur_row.is_empty() {
                                    rows.push(std::mem::take(&mut cur_row));
                                }
                            }
                            Event::End(TagEnd::Table) => {
                                if !cur_row.is_empty() {
                                    rows.push(std::mem::take(&mut cur_row));
                                }
                                break;
                            }
                            _ => {}
                        }
                    }
                    return Some(Block::Table { rows });
                }
                Event::Rule => return Some(Block::Rule),
                _ => {
                    // Stray events (definitions, metadata, list ends) are skipped.
                }
            }
        }
    }
}

/// Collect one list item; nested lists become their own blocks in `pending`
/// (rendered later, indented by stack depth).
fn collect_item<'a>(
    parser: &mut Parser<'a>,
    list_stack: &mut Vec<Option<u64>>,
    pending: &mut VecDeque<Block>,
) -> (InlineCtx, String, f32) {
    let mut events: Vec<Event> = Vec::new();
    let mut nested = 0usize;
    loop {
        match parser.next() {
            Some(Event::Start(Tag::List(s))) => {
                nested += 1;
                list_stack.push(s);
            }
            Some(Event::Start(Tag::Item)) if nested > 0 => {
                let inner = collect_item(parser, list_stack, pending);
                pending.push_back(Block::Item {
                    ctx: inner.0,
                    marker: inner.1,
                    indent: inner.2,
                });
            }
            Some(Event::End(TagEnd::List(_))) => {
                nested = nested.saturating_sub(1);
                list_stack.pop();
                if nested == 0 {
                    break;
                }
            }
            Some(Event::End(TagEnd::Item)) if nested == 0 => break,
            Some(other) => {
                if nested == 0 {
                    events.push(other);
                }
            }
            None => break,
        }
    }
    let marker = match list_stack.last().and_then(|s| *s) {
        Some(n) => format!("{n}. "),
        None => "- ".to_string(),
    };
    let indent = list_stack.len().saturating_sub(1) as f32 * 18.0;
    (inline_ctx_from(&events), marker, indent)
}

/// Consume events until the end of the current inline container (paragraph or heading).
fn collect_inline<'a>(parser: &mut Parser<'a>, buf: &mut Vec<Event<'a>>) {
    buf.clear();
    let mut depth = 0usize;
    while let Some(ev) = parser.next() {
        match &ev {
            Event::Start(Tag::Emphasis)
            | Event::Start(Tag::Strong)
            | Event::Start(Tag::Strikethrough)
            | Event::Start(Tag::Link { .. })
            | Event::Start(Tag::Image { .. }) => {
                depth += 1;
                buf.push(ev);
            }
            Event::End(TagEnd::Emphasis)
            | Event::End(TagEnd::Strong)
            | Event::End(TagEnd::Strikethrough)
            | Event::End(TagEnd::Link)
            | Event::End(TagEnd::Image) => {
                depth = depth.saturating_sub(1);
                buf.push(ev);
            }
            Event::End(TagEnd::Paragraph) | Event::End(TagEnd::Heading(_)) => {
                if depth == 0 {
                    return;
                }
                buf.push(ev);
            }
            _ => buf.push(ev),
        }
    }
}

/// Turn collected events into styled runs.
fn inline_ctx_from(events: &[Event]) -> InlineCtx {
    let mut ctx = InlineCtx::default();
    let mut cur = Run::default();
    let mut bold = 0usize;
    let mut italic = 0usize;

    let flush = |cur: &mut Run, runs: &mut Vec<Run>| {
        if !cur.text.is_empty() {
            runs.push(cur.clone());
        }
        cur.text.clear();
    };

    for ev in events {
        match ev {
            Event::Text(t) => cur.text.push_str(t),
            Event::Code(t) => {
                flush(&mut cur, &mut ctx.runs);
                cur.code = true;
                cur.text.push_str(t);
                flush(&mut cur, &mut ctx.runs);
                cur.code = false;
            }
            Event::SoftBreak | Event::HardBreak => cur.text.push('\n'),
            Event::Start(Tag::Strong) => {
                flush(&mut cur, &mut ctx.runs);
                bold += 1;
                cur.bold = true;
            }
            Event::End(TagEnd::Strong) => {
                bold = bold.saturating_sub(1);
                if bold == 0 {
                    flush(&mut cur, &mut ctx.runs);
                    cur.bold = false;
                }
            }
            Event::Start(Tag::Emphasis) => {
                flush(&mut cur, &mut ctx.runs);
                italic += 1;
                cur.italic = true;
            }
            Event::End(TagEnd::Emphasis) => {
                italic = italic.saturating_sub(1);
                if italic == 0 {
                    flush(&mut cur, &mut ctx.runs);
                    cur.italic = false;
                }
            }
            Event::Start(Tag::Link { dest_url, .. }) => {
                flush(&mut cur, &mut ctx.runs);
                cur.link = Some(dest_url.to_string());
            }
            Event::End(TagEnd::Link) => {
                flush(&mut cur, &mut ctx.runs);
                cur.link = None;
            }
            _ => {}
        }
    }
    flush(&mut cur, &mut ctx.runs);
    ctx
}

/// Rebuild a small markdown string from quote inner events for recursive rendering.
fn events_to_md(events: &[Event]) -> String {
    let mut out = String::new();
    for ev in events {
        match ev {
            Event::Text(t) => out.push_str(t),
            Event::Code(c) => {
                out.push('`');
                out.push_str(c);
                out.push('`');
            }
            Event::SoftBreak | Event::HardBreak => out.push('\n'),
            Event::Start(Tag::Emphasis) | Event::End(TagEnd::Emphasis) => out.push('*'),
            Event::Start(Tag::Strong) | Event::End(TagEnd::Strong) => out.push_str("**"),
            _ => {}
        }
    }
    out
}

/// A fenced code block: language chip header + monospace body + copy button.
pub fn code_block(ui: &mut Ui, lang: &str, code: &str) {
    egui::Frame::default()
        .fill(palette::CODE_BG)
        .stroke(Stroke::new(1.0_f32, palette::OUTLINE_VARIANT))
        .rounding(egui::Rounding::same(8.0))
        .inner_margin(egui::Margin::same(10.0))
        .show(ui, |ui| {
            ui.set_width(ui.available_width());
            ui.horizontal(|ui| {
                if !lang.is_empty() {
                    theme::chip(ui, lang, palette::ON_PRIMARY_CONTAINER, palette::PRIMARY_CONTAINER);
                } else {
                    theme::chip(ui, "text", palette::ON_SURFACE_VARIANT, palette::SURFACE_VARIANT);
                }
                ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                    if ui.small_button("Copy").clicked() {
                        ui.output_mut(|o| o.copied_text = code.to_owned());
                    }
                });
            });
            ui.add_space(4.0);
            let mut job = LayoutJob::default();
            job.text = code.to_owned();
            job.sections.push(egui::text::LayoutSection {
                leading_space: 0.0,
                byte_range: 0..code.len(),
                format: TextFormat {
                    font_id: FontId::monospace(12.5),
                    color: palette::CODE_TEXT,
                    ..Default::default()
                },
            });
            job.wrap.max_width = ui.available_width() - 8.0;
            job.wrap.break_anywhere = true;
            ui.add(Label::new(job).selectable(true));
        });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn iter_parses_paragraphs_and_code() {
        let md = "# Title\n\nHello **world** with `code`.\n\n```rust\nfn main() {}\n```\n";
        let mut count = 0;
        let mut saw_code = false;
        let parser = Parser::new_ext(md, Options::all());
        for b in BlockIter::new(parser) {
            match b {
                Block::Heading(..) => count += 1,
                Block::Paragraph(ctx) => {
                    assert!(ctx.runs.iter().any(|r| r.bold));
                    assert!(ctx.runs.iter().any(|r| r.code));
                    count += 1;
                }
                Block::Code { lang, code } => {
                    assert_eq!(lang, "rust");
                    assert!(code.contains("fn main()"));
                    saw_code = true;
                }
                _ => {}
            }
        }
        assert_eq!(count, 2);
        assert!(saw_code);
    }

    #[test]
    fn iter_parses_lists_and_tables() {
        let md = "- one\n- two\n  - nested\n\n| A | B |\n|---|---|\n| 1 | 2 |\n";
        let parser = Parser::new_ext(md, Options::all());
        let mut items = 0usize;
        let mut nested_indent: f32 = 0.0;
        let mut table_rows = 0usize;
        for b in BlockIter::new(parser) {
            match b {
                Block::Item { indent, .. } => {
                    items += 1;
                    nested_indent = nested_indent.max(indent);
                }
                Block::Table { rows } => table_rows = rows.len(),
                _ => {}
            }
        }
        assert_eq!(items, 3);
        assert!(nested_indent > 0.0);
        assert_eq!(table_rows, 2);
    }
}
