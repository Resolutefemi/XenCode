//! ZCode `components/ai-elements` markdown port: the assistant-message
//! renderer from packages/ui (message.tsx, code-block.tsx, markdown-list,
//! markdown-table, Apache-2.0) rebuilt on egui + pulldown-cmark.
//!
//! Fidelity notes against the TSX sources:
//!   paragraphs `text-ui-base` with Streamdown inline styles (strong, em,
//!   strike, inline code on the tag token, links in brand), code blocks are
//!   CodeBlockContainer (`rounded-xl`, card surface, hairline stroke) with a
//!   `px-3 py-2` header (mono lowercase language label, wrap + copy ghost
//!   buttons) and a `p-2 pt-0 pb-3` body at 13px mono, headings use the
//!   text-ui scale, lists indent 1rem per level, tables get hairline rules
//!   and a header tint. Syntax colors approximate the bundled shiki theme
//!   (One Dark family on dark, a light-github palette on light).

use crate::theme::{self, Palette};
use crate::ui;
use eframe::egui;
use pulldown_cmark::{CodeBlockKind, Event, HeadingLevel, Options, Parser, Tag, TagEnd};
use std::ops::Range;
use std::sync::Arc;

/// Embedded bold sans face (egui 0.29 ships only a Light proportional, so
/// real weight needs a second family). FreeSans Bold, GPL + font exception.
const BOLD_TTF: &[u8] = include_bytes!("../../assets/FreeSansBold.ttf");

pub(crate) const BOLD_FAMILY: &str = "xen-bold";

/// Register the real-bold family on top of the given font definitions
/// (called from fonts::install so both share one FontDefinitions set).
pub(crate) fn install_bold_font(fonts: &mut egui::FontDefinitions) {
    fonts
        .font_data
        .insert(BOLD_FAMILY.to_owned(), egui::FontData::from_static(BOLD_TTF));
    fonts.families.insert(
        egui::FontFamily::Name(BOLD_FAMILY.into()),
        vec![
            BOLD_FAMILY.to_owned(),
            "XenSansSC".to_owned(),
            "XenDejaVu".to_owned(),
            "NotoEmoji-Regular".to_owned(),
            "emoji-icon-font".to_owned(),
        ],
    );
}

fn bold(size: f32) -> egui::FontId {
    egui::FontId::new(size, egui::FontFamily::Name(Arc::from(BOLD_FAMILY)))
}

/// One inline run of an assistant paragraph.
#[derive(Clone)]
struct Span {
    text: String,
    bold: bool,
    italic: bool,
    strike: bool,
    code: bool,
    link: Option<String>,
}

#[derive(Default)]
struct Inline {
    bold: u32,
    italic: u32,
    strike: u32,
    link: Option<String>,
}

impl Inline {
    fn push(&mut self, text: &str, code: bool, out: &mut Vec<Span>) {
        if text.is_empty() {
            return;
        }
        if let Some(last) = out.last_mut() {
            let same = last.bold == (self.bold > 0)
                && last.italic == (self.italic > 0)
                && last.strike == (self.strike > 0)
                && last.code == code
                && last.link == self.link;
            if same {
                last.text.push_str(text);
                return;
            }
        }
        out.push(Span {
            text: text.to_owned(),
            bold: self.bold > 0,
            italic: self.italic > 0,
            strike: self.strike > 0,
            code,
            link: self.link.clone(),
        });
    }
}

/// Render a markdown document in the current vertical flow.
pub(crate) fn render(ui: &mut egui::Ui, src: &str, p: Palette) {
    let mut opts = Options::empty();
    opts.insert(Options::ENABLE_STRIKETHROUGH);
    opts.insert(Options::ENABLE_TABLES);
    opts.insert(Options::ENABLE_TASKLISTS);

    let mut inline = Inline::default();
    let mut para: Vec<Span> = vec![];
    let mut para_open = false;
    let mut heading: Option<HeadingLevel> = None;
    let mut quote_depth: u32 = 0;
    let mut code: Option<(String, String)> = None; // lang, body
    let mut list_stack: Vec<(bool, u64)> = vec![]; // ordered, next index
    let mut table: Option<TableState> = None;

    for ev in Parser::new_ext(src, opts) {
        match ev {
            Event::Start(tag) => match tag {
                Tag::Paragraph => {
                    para.clear();
                    para_open = true;
                }
                Tag::Heading { level, .. } => {
                    para.clear();
                    para_open = true;
                    heading = Some(level);
                }
                Tag::CodeBlock(kind) => {
                    let lang = match kind {
                        CodeBlockKind::Fenced(info) => {
                            info.split(' ').next().unwrap_or("").to_owned()
                        }
                        _ => String::new(),
                    };
                    code = Some((lang, String::new()));
                }
                Tag::List(start) => {
                    let ordered = start.is_some();
                    list_stack.push((ordered, start.unwrap_or(1)));
                }
                Tag::Item => {
                    para.clear();
                    para_open = true;
                }
                Tag::BlockQuote(_) => {
                    quote_depth += 1;
                }
                Tag::Table(_) => {
                    table = Some(TableState::default());
                }
                Tag::TableHead => {
                    if let Some(t) = table.as_mut() {
                        t.in_head = true;
                    }
                }
                Tag::TableRow => {
                    if let Some(t) = table.as_mut() {
                        t.row.clear();
                    }
                }
                Tag::TableCell => {
                    para.clear();
                    para_open = true;
                }
                Tag::Emphasis => inline.italic += 1,
                Tag::Strong => inline.bold += 1,
                Tag::Strikethrough => inline.strike += 1,
                Tag::Link { dest_url, .. } => inline.link = Some(dest_url.to_string()),
                Tag::Image { .. } => {
                    // egui cannot fetch remote art; ZCode shows alt text.
                    inline.italic += 1;
                }
                _ => {}
            },
            Event::End(tag_end) => match tag_end {
                TagEnd::Paragraph => {
                    if table.is_some() {
                        if let Some(t) = table.as_mut() {
                            t.cell(para.clone());
                        }
                        para_open = false;
                        continue;
                    }
                    if let Some(level) = heading.take() {
                        flush_heading(ui, &para, p, level);
                    } else if !list_stack.is_empty() {
                        flush_list_item(ui, &mut list_stack, &para, p);
                    } else if quote_depth > 0 {
                        flush_quote(ui, &para, p);
                    } else {
                        flush_para(ui, &para, p, theme::text::BASE);
                    }
                    para_open = false;
                }
                TagEnd::Heading(_) => {
                    if let Some(level) = heading.take() {
                        flush_heading(ui, &para, p, level);
                    }
                    para_open = false;
                }
                TagEnd::CodeBlock => {
                    if let Some((lang, body)) = code.take() {
                        code_block(ui, &lang, &body, p);
                    }
                }
                TagEnd::Item => {
                    if para_open {
                        flush_list_item(ui, &mut list_stack, &para, p);
                        para_open = false;
                    }
                }
                TagEnd::List(_) => {
                    list_stack.pop();
                }
                TagEnd::TableHead => {
                    if let Some(t) = table.as_mut() {
                        t.in_head = false;
                    }
                }
                TagEnd::Table => {
                    if let Some(t) = table.take() {
                        table_render(ui, &t, p);
                    }
                }
                TagEnd::Emphasis => inline.italic = inline.italic.saturating_sub(1),
                TagEnd::Strong => inline.bold = inline.bold.saturating_sub(1),
                TagEnd::Strikethrough => inline.strike = inline.strike.saturating_sub(1),
                TagEnd::Link => inline.link = None,
                TagEnd::Image => inline.italic = inline.italic.saturating_sub(1),
                _ => {}
            },
            Event::Text(text) => {
                if let Some((_, body)) = code.as_mut() {
                    body.push_str(&text);
                } else if para_open {
                    inline.push(&text, false, &mut para);
                }
            }
            Event::Code(text) => {
                if para_open {
                    inline.push(&text, true, &mut para);
                }
            }
            Event::SoftBreak => {
                if para_open {
                    inline.push(" ", false, &mut para);
                }
            }
            Event::HardBreak => {
                if para_open {
                    inline.push("\n", false, &mut para);
                }
            }
            Event::Rule => {
                let (rect, _) =
                    ui.allocate_exact_size(egui::vec2(ui.available_width(), 1.0), egui::Sense::hover());
                ui.painter().line_segment(
                    [egui::pos2(rect.left(), rect.center().y), egui::pos2(rect.right(), rect.center().y)],
                    ui::hairline(p),
                );
            }
            Event::TaskListMarker(checked) => {
                let glyph = if checked { "[x] " } else { "[ ] " };
                inline.push(glyph, true, &mut para);
            }
            Event::InlineMath(m) => {
                if para_open {
                    inline.push(&format!("${m}$"), true, &mut para);
                }
            }
            Event::DisplayMath(m) => {
                if para_open {
                    inline.push(&format!("$${m}$$"), true, &mut para);
                }
            }
            _ => {}
        }
    }
    if para_open && !para.is_empty() && heading.is_none() && table.is_none() {
        flush_para(ui, &para, p, theme::text::BASE);
    }
}

#[derive(Default)]
struct TableState {
    in_head: bool,
    headers: Vec<Vec<Span>>,
    rows: Vec<Vec<Vec<Span>>>,
    row: Vec<Vec<Span>>,
}

impl TableState {
    fn cell(&mut self, spans: Vec<Span>) {
        if self.in_head {
            self.headers.push(spans);
        } else {
            self.row.push(spans);
        }
    }
}

fn open_first_link(ui: &egui::Ui, spans: &[Span]) {
    if let Some(url) = spans.iter().find_map(|s| s.link.clone()) {
        ui.output_mut(|o| {
            o.open_url = Some(egui::output::OpenUrl { url, new_tab: true });
        });
    }
}

fn flush_para(ui: &mut egui::Ui, spans: &[Span], p: Palette, size: f32) {
    if spans.is_empty() {
        return;
    }
    let job = spans_job(ui, spans, p, size, p.foreground());
    let resp = ui.add(egui::Label::new(egui::WidgetText::Galley(job)));
    if resp.clicked() {
        open_first_link(ui, spans);
    }
}

fn flush_heading(ui: &mut egui::Ui, spans: &[Span], p: Palette, level: HeadingLevel) {
    // Streamdown heading sizes around the text-ui scale.
    let size = match level {
        HeadingLevel::H1 => 22.0,
        HeadingLevel::H2 => 18.0,
        HeadingLevel::H3 => 16.0,
        HeadingLevel::H4 => 14.0,
        HeadingLevel::H5 => 13.0,
        HeadingLevel::H6 => 12.0,
    };
    let mut styled: Vec<Span> = spans.to_vec();
    for s in &mut styled {
        s.bold = true;
    }
    ui.add_space(if matches!(level, HeadingLevel::H1 | HeadingLevel::H2) { 8.0 } else { 4.0 });
    flush_para(ui, &styled, p, size);
    ui.add_space(2.0);
}

fn flush_list_item(ui: &mut egui::Ui, stack: &mut Vec<(bool, u64)>, spans: &[Span], p: Palette) {
    let depth = stack.len().saturating_sub(1);
    let (ordered, idx) = stack.last().map(|(o, n)| (*o, *n)).unwrap_or((false, 1));
    if let Some(last) = stack.last_mut() {
        last.1 += 1;
    }
    let indent = 16.0 + depth as f32 * 16.0;
    let bullet = if ordered { format!("{idx}.") } else { "\u{2022}".to_owned() };

    ui.allocate_ui_with_layout(
        egui::vec2(ui.available_width(), 18.0),
        egui::Layout::left_to_right(egui::Align::TOP),
        |ui| {
            ui.add_space(indent);
            let (br, _) = ui.allocate_exact_size(egui::vec2(16.0, 18.0), egui::Sense::hover());
            ui.painter().text(
                egui::pos2(br.right(), br.center().y),
                egui::Align2::RIGHT_CENTER,
                bullet,
                egui::FontId::proportional(theme::text::BASE),
                p.foreground_subtle(),
            );
            ui.with_layout(egui::Layout::top_down(egui::Align::LEFT), |ui| {
                if spans.is_empty() {
                    ui.add_space(theme::text::BASE);
                } else {
                    flush_para(ui, spans, p, theme::text::BASE);
                }
            });
        },
    );
}

fn flush_quote(ui: &mut egui::Ui, spans: &[Span], p: Palette) {
    // Shadcn blockquote: 2px inset border + regular copy.
    let left = ui.cursor().left() + 4.0;
    let w = (ui.available_width() - 14.0).max(80.0);
    let top = ui.cursor().top();
    let resp = ui.allocate_new_ui(
        egui::UiBuilder::new().max_rect(egui::Rect::from_min_size(
            egui::pos2(left + 12.0, top),
            egui::vec2(w, 0.0),
        )),
        |ui| flush_para(ui, spans, p, theme::text::BASE),
    );
    let content = resp.response.rect;
    ui.painter().line_segment(
        [egui::pos2(left, content.top()), egui::pos2(left, content.bottom())],
        egui::Stroke::new(2.0_f32, p.border()),
    );
    ui.add_space(2.0);
}

fn spans_job(ui: &egui::Ui, spans: &[Span], p: Palette, size: f32, color: egui::Color32) -> Arc<egui::Galley> {
    let mut job = egui::epaint::text::LayoutJob::default();
    job.wrap.max_width = ui.available_width().max(120.0);
    for s in spans {
        let mut fmt = egui::TextFormat::default();
        fmt.font_id = if s.code {
            egui::FontId::monospace(size - 1.0)
        } else if s.bold {
            bold(size)
        } else {
            egui::FontId::proportional(size)
        };
        fmt.color = if s.link.is_some() { p.brand() } else { color };
        if s.code {
            // Inline code chip: tag token fill, foreground copy (ai-elements).
            fmt.background = p.tag();
            fmt.color = p.foreground();
        }
        if s.italic {
            fmt.italics = true;
        }
        if s.strike {
            fmt.strikethrough = egui::Stroke::new(1.0_f32, fmt.color);
        }
        if s.link.is_some() {
            fmt.underline = egui::Stroke::new(1.0_f32, p.brand());
        }
        job.append(&s.text, 0.0, fmt);
    }
    ui.fonts(|f| f.layout_job(job))
}

/// Stable short hash so code-block UI state keys never collide.
fn fnv(s: &str) -> u64 {
    let mut h: u64 = 0xcbf29ce484222325;
    for b in s.as_bytes() {
        h ^= *b as u64;
        h = h.wrapping_mul(0x100000001b3);
    }
    h
}

/// The `CodeBlock` port: rounded-xl card, header with language label +
/// wrap/copy buttons, 13px mono body with light syntax coloring.
fn code_block(ui: &mut egui::Ui, lang: &str, body: &str, p: Palette) {
    ui.add_space(2.0);
    let width = ui.available_width().min(720.0);
    let id = egui::Id::new(("md_code", fnv(body)));
    let copied_at: Option<std::time::Instant> = ui
        .ctx()
        .data(|d| d.get_temp::<std::time::Instant>(id.with("copied")));
    let copied = copied_at.map(|t| t.elapsed().as_secs_f32() < 2.0).unwrap_or(false);
    let wrapped: bool = ui.ctx().data(|d| d.get_temp::<bool>(id).unwrap_or(false));

    let body_trim = body.strip_suffix('\n').unwrap_or(body);
    let lines: Vec<&str> = body_trim.lines().collect();
    let line_h = theme::text::SM + 6.0;
    let header_h = 30.0;
    let body_h = lines.len() as f32 * line_h + 12.0;

    let (rect, _) = ui.allocate_exact_size(egui::vec2(width, header_h + body_h), egui::Sense::hover());
    ui.painter().rect_filled(rect, egui::Rounding::same(theme::geom::RADIUS_LG), p.card());
    ui.painter().rect_stroke(rect, egui::Rounding::same(theme::geom::RADIUS_LG), ui::hairline(p));

    ui.allocate_new_ui(egui::UiBuilder::new().max_rect(rect), |ui| {
        let pr = ui.painter().clone();
        // Header: document glyph + language (mono, lowercase).
        let hy = rect.top() + header_h / 2.0;
        let gc = egui::pos2(rect.left() + 14.0, hy);
        let stroke = egui::Stroke::new(1.2_f32, p.foreground_subtlest());
        pr.rect_stroke(egui::Rect::from_center_size(gc, egui::vec2(9.0, 11.0)), 2.0, stroke);
        for dy in [-2.5, 0.0, 2.5] {
            pr.line_segment(
                [egui::pos2(gc.x - 2.5, gc.y + dy), egui::pos2(gc.x + 2.5, gc.y + dy)],
                stroke,
            );
        }
        let label = if lang.is_empty() { "text" } else { lang };
        pr.text(
            egui::pos2(gc.x + 10.0, hy),
            egui::Align2::LEFT_CENTER,
            label.to_lowercase(),
            egui::FontId::monospace(theme::text::SM),
            p.foreground_subtle(),
        );

        // Copy button (right edge, ghost icon-md).
        let copy_c = egui::pos2(rect.right() - 22.0, hy);
        let copy_rect = egui::Rect::from_center_size(copy_c, egui::vec2(24.0, 24.0));
        let copy_resp = ui.interact(copy_rect, id.with("copy"), egui::Sense::click());
        if copy_resp.hovered() {
            pr.rect_filled(copy_rect, 6.0, p.hover());
        }
        if copied {
            glyph_check(&pr, copy_c, p.success(), 10.0);
        } else {
            glyph_copy(&pr, copy_c, p.foreground_subtle(), 10.0);
        }
        if copy_resp.clicked() {
            ui.ctx().copy_text(body_trim.to_owned());
            ui.ctx()
                .data_mut(|d| d.insert_temp(id.with("copied"), std::time::Instant::now()));
        }

        // Wrap toggle button.
        let wrap_c = egui::pos2(rect.right() - 48.0, hy);
        let wrap_rect = egui::Rect::from_center_size(wrap_c, egui::vec2(24.0, 24.0));
        let wrap_resp = ui.interact(wrap_rect, id.with("wrap"), egui::Sense::click());
        if wrap_resp.hovered() {
            pr.rect_filled(wrap_rect, 6.0, p.hover());
        }
        glyph_wrap(&pr, wrap_c, p.foreground_subtle(), 10.0);
        if wrap_resp.clicked() {
            ui.ctx().data_mut(|d| d.insert_temp(id, !wrapped));
        }

        // Body: mono lines with token colors; horizontal scroll when unwrapped.
        let body_rect = egui::Rect::from_min_max(
            egui::pos2(rect.left(), rect.top() + header_h),
            egui::pos2(rect.right(), rect.bottom()),
        );
        ui.allocate_new_ui(egui::UiBuilder::new().max_rect(body_rect), |ui| {
            ui.style_mut().spacing.item_spacing.y = 0.0;
            if wrapped {
                let avail = rect.width() - 24.0;
                for line in &lines {
                    let (r, _) =
                        ui.allocate_exact_size(egui::vec2(rect.width(), line_h), egui::Sense::hover());
                    paint_code_line(ui, r, line, p, avail, true);
                }
            } else {
                egui::ScrollArea::horizontal()
                    .id_salt(id.with("hscroll"))
                    .show(ui, |ui| {
                        let max_line_w = lines
                            .iter()
                            .map(|l| mono_width(l))
                            .fold(0.0_f32, f32::max)
                            .max(rect.width() - 24.0);
                        for line in &lines {
                            let (r, _) = ui.allocate_exact_size(
                                egui::vec2(max_line_w + 24.0, line_h),
                                egui::Sense::hover(),
                            );
                            paint_code_line(ui, r, line, p, max_line_w, false);
                        }
                    });
            }
        });
    });
    ui.add_space(4.0);
}

fn mono_width(s: &str) -> f32 {
    // Monospace advance is stable enough for scroll extents.
    s.chars().count() as f32 * theme::text::SM * 0.6
}

fn paint_code_line(ui: &mut egui::Ui, rect: egui::Rect, line: &str, p: Palette, max_w: f32, wrap: bool) {
    let lang = "";
    let mut job = egui::epaint::text::LayoutJob::default();
    if wrap {
        job.wrap.max_width = max_w;
    } else {
        job.wrap.max_width = f32::INFINITY;
    }
    for (range, tok) in highlight::tokens(line, lang) {
        if range.start >= range.end || range.start > line.len() {
            continue;
        }
        let mut fmt = egui::TextFormat::default();
        fmt.font_id = egui::FontId::monospace(theme::text::SM);
        fmt.color = highlight::color(tok, p);
        job.append(&line[range], 0.0, fmt);
    }
    let galley = ui.fonts(|f| f.layout_job(job));
    ui.painter().galley(
        egui::pos2(rect.left() + 12.0, rect.center().y - galley.size().y / 2.0),
        galley,
        egui::Color32::TRANSPARENT,
    );
}

fn table_render(ui: &mut egui::Ui, t: &TableState, p: Palette) {
    let cols = t.headers.len().max(t.rows.iter().map(|r| r.len()).max().unwrap_or(0));
    if cols == 0 {
        return;
    }
    ui.add_space(2.0);
    egui::Grid::new(("md_table", cols, t.rows.len()))
        .striped(false)
        .min_col_width(48.0)
        .spacing([16.0, 6.0])
        .show(ui, |ui| {
            for cell in &t.headers {
                let mut bold_cell: Vec<Span> = (*cell).clone();
                for s in &mut bold_cell {
                    s.bold = true;
                }
                cell_text(ui, &bold_cell, p);
            }
            ui.end_row();
            // Shadcn table rule under the header.
            let (r, _) = ui.allocate_exact_size(
                egui::vec2(ui.available_width(), 1.0),
                egui::Sense::hover(),
            );
            ui.painter().line_segment(
                [egui::pos2(r.left(), r.center().y), egui::pos2(r.right(), r.center().y)],
                ui::hairline(p),
            );
            for row in &t.rows {
                for c in 0..cols {
                    match row.get(c) {
                        Some(cell) => cell_text(ui, cell, p),
                        None => {
                            ui.label("");
                        }
                    }
                }
                ui.end_row();
            }
        });
    ui.add_space(2.0);
}

fn cell_text(ui: &mut egui::Ui, spans: &[Span], p: Palette) {
    if spans.is_empty() {
        ui.label("");
        return;
    }
    let job = spans_job(ui, spans, p, theme::text::SM, p.foreground_subtle());
    ui.add(egui::Label::new(egui::WidgetText::Galley(job)));
}

// --- minimal glyph set (lucide stand-ins, painter primitives) ---

pub(crate) fn glyph_check(pr: &egui::Painter, c: egui::Pos2, color: egui::Color32, s: f32) {
    let pts = [
        egui::pos2(c.x - s * 0.5, c.y),
        egui::pos2(c.x - s * 0.15, c.y + s * 0.35),
        egui::pos2(c.x + s * 0.55, c.y - s * 0.4),
    ];
    pr.add(egui::Shape::line(pts.to_vec(), egui::Stroke::new(1.6_f32, color)));
}

fn glyph_copy(pr: &egui::Painter, c: egui::Pos2, color: egui::Color32, s: f32) {
    let stroke = egui::Stroke::new(1.2_f32, color);
    pr.rect_stroke(
        egui::Rect::from_center_size(c + egui::vec2(-s * 0.15, s * 0.15), egui::vec2(s * 0.8, s * 0.8)),
        2.0,
        stroke,
    );
    pr.rect_stroke(
        egui::Rect::from_center_size(c + egui::vec2(s * 0.2, -s * 0.2), egui::vec2(s * 0.8, s * 0.8)),
        2.0,
        stroke,
    );
}

fn glyph_wrap(pr: &egui::Painter, c: egui::Pos2, color: egui::Color32, s: f32) {
    let stroke = egui::Stroke::new(1.2_f32, color);
    let y0 = c.y - s * 0.35;
    let y1 = c.y + s * 0.35;
    pr.line_segment([egui::pos2(c.x - s * 0.6, y0), egui::pos2(c.x + s * 0.25, y0)], stroke);
    pr.line_segment([egui::pos2(c.x - s * 0.6, y1), egui::pos2(c.x + s * 0.5, y1)], stroke);
    pr.line_segment(
        [egui::pos2(c.x + s * 0.25, y0), egui::pos2(c.x + s * 0.25, y1 - s * 0.2)],
        stroke,
    );
    let pts = [
        egui::pos2(c.x + s * 0.05, y1 - s * 0.45),
        egui::pos2(c.x + s * 0.45, y1 - s * 0.45),
        egui::pos2(c.x + s * 0.25, y1 - s * 0.1),
    ];
    pr.add(egui::Shape::convex_polygon(pts.to_vec(), color, egui::Stroke::NONE));
}

pub(crate) fn glyph_brain(pr: &egui::Painter, c: egui::Pos2, color: egui::Color32, s: f32) {
    let stroke = egui::Stroke::new(1.2_f32, color);
    // Head outline (two lobes) + stem.
    pr.circle_stroke(c + egui::vec2(-s * 0.22, -s * 0.1), s * 0.32, stroke);
    pr.circle_stroke(c + egui::vec2(s * 0.22, -s * 0.1), s * 0.32, stroke);
    pr.line_segment(
        [egui::pos2(c.x, c.y + s * 0.1), egui::pos2(c.x, c.y + s * 0.45)],
        stroke,
    );
    pr.line_segment(
        [egui::pos2(c.x - s * 0.35, c.y + s * 0.45), egui::pos2(c.x + s * 0.35, c.y + s * 0.45)],
        stroke,
    );
}

/// Tiny shiki stand-in: strings, comments, numbers and keywords per family.
pub(crate) mod highlight {
    use super::*;
    use crate::theme::Palette;

    #[derive(Clone, Copy, PartialEq)]
    pub(crate) enum Tok {
        Plain,
        Str,
        Comment,
        Kw,
        Num,
    }

    pub(crate) fn color(tok: Tok, p: Palette) -> egui::Color32 {
        // One Dark family (dark) / light-github family (light).
        if p.dark() {
            match tok {
                Tok::Plain => p.foreground(),
                Tok::Str => egui::Color32::from_rgb(0x98, 0xc3, 0x79),
                Tok::Comment => p.foreground_subtlest(),
                Tok::Kw => egui::Color32::from_rgb(0xc6, 0x78, 0xdd),
                Tok::Num => egui::Color32::from_rgb(0xd1, 0x9a, 0x66),
            }
        } else {
            match tok {
                Tok::Plain => p.foreground(),
                Tok::Str => egui::Color32::from_rgb(0x0a, 0x7c, 0x42),
                Tok::Comment => p.foreground_subtlest(),
                Tok::Kw => egui::Color32::from_rgb(0x82, 0x50, 0xdf),
                Tok::Num => egui::Color32::from_rgb(0x95, 0x38, 0x00),
            }
        }
    }

    fn family(lang: &str) -> u8 {
        // 0 hash comments, 1 c-family (// comments), 3 dash-dash comments,
        // 4 markup-ish (no colors).
        match lang {
            "py" | "python" | "toml" | "yaml" | "yml" | "rb" | "ruby" | "sh" | "bash" | "zsh"
            | "shell" => 0,
            "rs" | "rust" | "js" | "ts" | "jsx" | "tsx" | "c" | "cpp" | "h" | "go" | "java"
            | "kt" | "json" | "cjs" | "mjs" | "cs" | "swift" | "dart" | "php" => 1,
            "sql" | "lua" => 3,
            _ => 4,
        }
    }

    fn is_kw(word: &str, lang: &str) -> bool {
        let c_family: &[&str] = &[
            "const", "let", "var", "function", "return", "if", "else", "for", "while", "class",
            "import", "export", "from", "new", "async", "await", "try", "catch", "throw", "this",
            "null", "true", "false", "undefined", "switch", "case", "break", "continue", "default",
            "extends", "interface", "enum", "public", "private", "static", "fn", "mut", "pub",
            "struct", "impl", "trait", "use", "mod", "match", "loop", "crate", "super", "where",
            "as", "dyn", "ref", "move", "unsafe", "Some", "None", "Ok", "Err", "void", "yield",
        ];
        let script: &[&str] = &[
            "def", "class", "return", "if", "elif", "else", "for", "while", "import", "from",
            "as", "with", "try", "except", "finally", "raise", "lambda", "yield", "pass", "None",
            "True", "False", "and", "or", "not", "in", "is", "global", "assert", "del", "async",
            "await", "then", "fi", "do", "done", "esac", "echo", "export", "local", "set",
        ];
        match family(lang) {
            1 => c_family.contains(&word),
            0 | 2 => script.contains(&word),
            _ => false,
        }
    }

    /// Tokenize one line. No multi-line state: code rows are colored
    /// independently, matching how the streamdown viewer splits rows.
    pub(crate) fn tokens(line: &str, lang: &str) -> Vec<(Range<usize>, Tok)> {
        let fam = family(lang);
        let b = line.as_bytes();
        let mut out: Vec<(Range<usize>, Tok)> = vec![];
        let mut segments: Vec<(Range<usize>, Tok)> = vec![];
        let mut i = 0;
        while i < b.len() {
            let c = b[i] as char;
            // Comments run to end of line.
            let comment_start = (fam == 1 && c == '/' && i + 1 < b.len() && b[i + 1] == b'/')
                || (fam == 0 && c == '#')
                || (fam == 3 && c == '-' && i + 1 < b.len() && b[i + 1] == b'-');
            if comment_start {
                segments.push((i..b.len(), Tok::Comment));
                break;
            }
            // Strings.
            if c == '"' || c == '\'' {
                let quote = c;
                let start = i;
                i += 1;
                while i < b.len() && b[i] != quote as u8 {
                    if b[i] == b'\\' {
                        i += 1;
                    }
                    i += 1;
                }
                i = (i + 1).min(b.len());
                segments.push((start..i, Tok::Str));
                continue;
            }
            // Numbers.
            if c.is_ascii_digit()
                && (i == 0 || !(b[i - 1].is_ascii_alphanumeric() || b[i - 1] == b'_'))
            {
                let start = i;
                while i < b.len() && (b[i].is_ascii_hexdigit() || b[i] == b'.' || b[i] == b'_') {
                    i += 1;
                }
                segments.push((start..i, Tok::Num));
                continue;
            }
            // Words.
            if c.is_ascii_alphabetic() || c == '_' {
                let start = i;
                while i < b.len() && (b[i].is_ascii_alphanumeric() || b[i] == b'_') {
                    i += 1;
                }
                let word = &line[start..i];
                let tok = if is_kw(word, lang) { Tok::Kw } else { Tok::Plain };
                segments.push((start..i, tok));
                continue;
            }
            i += 1;
        }
        // Fill the gaps as plain.
        let mut cur = 0;
        for (r, tok) in segments {
            if r.start > cur {
                out.push((cur..r.start, Tok::Plain));
            }
            cur = r.end;
            out.push((r, tok));
        }
        if cur < line.len() {
            out.push((cur..line.len(), Tok::Plain));
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::highlight::{color, tokens, Tok};
    use crate::theme::{Mode, Palette};

    #[test]
    fn rust_line_tokens_comments_strings_keywords_numbers() {
        let toks = tokens("let x = 42; // set answer", "rs");
        assert!(toks.iter().any(|(r, t)| *t == Tok::Kw && r.start == 0));
        assert!(toks.iter().any(|(r, t)| *t == Tok::Num));
        assert!(toks.iter().any(|(r, t)| *t == Tok::Comment && r.start == 12));
    }

    #[test]
    fn shell_line_uses_hash_comments() {
        let toks = tokens("echo hi # done", "sh");
        assert!(toks.iter().any(|(r, t)| *t == Tok::Comment && r.start == 8));
    }

    #[test]
    fn plain_lines_have_no_markup_colors() {
        let toks = tokens("plain text 123", "");
        assert!(!toks.iter().any(|(_, t)| *t == Tok::Kw));
    }

    #[test]
    fn token_colors_follow_the_theme_mode() {
        let dark = Palette { mode: Mode::Dark };
        let light = Palette { mode: Mode::Light };
        assert_ne!(color(Tok::Kw, dark), color(Tok::Kw, light));
    }
}
