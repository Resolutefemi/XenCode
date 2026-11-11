//! Scraping engine (Phase 6.4) — selectors, pagination, infinite scroll,
//! and exports (JSON / CSV / Markdown).
//!
//! [`ScrapeSpec`] describes what to pull off a page: an optional repeating
//! row selector (`item`) with per-row fields, page-level fields, an optional
//! next-page selector (pagination) or scroll rounds (infinite scroll). The
//! extraction itself runs as one `Runtime.evaluate` per page — the page
//! serializes rows to JSON, Rust only validates. Exports are pure functions
//! over the row maps so they are unit-testable without a browser.

use anyhow::{anyhow, Result};
use serde::{Deserialize, Serialize};
use serde_json::{json, Map, Value};

use crate::space::Space;

/// One extracted column: `name = selector[@attr]` — attr ∈ href | src |
/// html | value; no attr = trimmed text.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct FieldSpec {
    pub name: String,
    pub selector: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub attr: Option<String>,
}

impl FieldSpec {
    /// Parse `name=selector` or `name=selector@attr`.
    pub fn parse(raw: &str) -> Result<FieldSpec> {
        let (name, rest) = raw
            .split_once('=')
            .ok_or_else(|| anyhow!("field `{raw}` must be name=selector"))?;
        let name = name.trim();
        if name.is_empty() {
            return Err(anyhow!("field `{raw}` has an empty name"));
        }
        let (selector, attr) = match rest.rsplit_once('@') {
            // `a@href` — but keep `a[href@x]`-style selectors intact: only
            // split when the tail is a bare attribute word.
            Some((sel, tail)) if !tail.is_empty() && is_bare_attr(tail) => {
                (sel, Some(tail.to_string()))
            }
            _ => (rest, None),
        };
        if selector.trim().is_empty() {
            return Err(anyhow!("field `{raw}` has an empty selector"));
        }
        Ok(FieldSpec {
            name: name.to_string(),
            selector: selector.trim().to_string(),
            attr,
        })
    }
}

fn is_bare_attr(tail: &str) -> bool {
    !tail.is_empty()
        && tail.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
        && matches!(
            tail,
            "href" | "src" | "html" | "value" | "title" | "alt" | "id" | "class" | "name" | "action" | "datetime" | "content"
        )
}

/// What to pull off a page.
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct ScrapeSpec {
    /// Repeating row selector; when absent, `fields` describe page-level
    /// values (one row per page).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub item: Option<String>,
    /// Fields relative to `item` (or page-level when no item).
    #[serde(default)]
    pub fields: Vec<FieldSpec>,
    /// Page-level fields captured alongside item rows (e.g. the heading).
    #[serde(default)]
    pub page_fields: Vec<FieldSpec>,
    /// Next-page selector → paginate after extraction.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub next: Option<String>,
    /// Pagination cap (default 1 = just the first page).
    #[serde(default = "default_max_pages")]
    pub max_pages: u32,
    /// Infinite-scroll rounds (0 = off). Each round scrolls and waits for
    /// the height to settle.
    #[serde(default)]
    pub scroll_rounds: u32,
    /// Scroll step per round (px, default 900).
    #[serde(default = "default_scroll_dy")]
    pub scroll_dy: f64,
    /// Settle time between pages / after scroll (ms, default 600).
    #[serde(default = "default_wait_ms")]
    pub wait_ms: u64,
}

fn default_max_pages() -> u32 {
    1
}
fn default_scroll_dy() -> f64 {
    900.0
}
fn default_wait_ms() -> u64 {
    600
}

impl ScrapeSpec {
    pub fn validate(&self) -> Result<()> {
        if self.item.is_none() && self.fields.is_empty() && self.page_fields.is_empty() {
            return Err(anyhow!("scrape spec needs --item or at least one --field"));
        }
        Ok(())
    }
}

/// One scraped page.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ScrapePage {
    pub url: String,
    pub rows: Vec<Map<String, Value>>,
}

/// Extract the current page per the spec (one evaluate, JSON round-trip).
pub async fn scrape_page(space: &Space, spec: &ScrapeSpec) -> Result<ScrapePage> {
    let js = extract_js(spec);
    let v = space
        .evaluate(&js)
        .await
        .map_err(|e| anyhow!("extract failed: {e}"))?;
    let url = space.url().await.unwrap_or_default();
    let rows = match v {
        Value::Array(rows) => rows
            .into_iter()
            .filter_map(|r| r.as_object().cloned())
            .collect(),
        Value::Object(_) => {
            return Err(anyhow!("extract returned an object — expected a row array"))
        }
        _ => Vec::new(),
    };
    Ok(ScrapePage { url, rows })
}

/// Build the in-page extraction expression. Fields resolve relative to each
/// item element (or the document when there is no item selector); missing
/// fields become null rather than dropping the row.
pub fn extract_js(spec: &ScrapeSpec) -> String {
    let fields_json = serde_json::to_string(&spec.fields).unwrap_or_else(|_| "[]".into());
    let page_json = serde_json::to_string(&spec.page_fields).unwrap_or_else(|_| "[]".into());
    let item = spec.item.clone().unwrap_or_default();
    format!(
        r#"(() => {{
  const fields = {fields_json};
  const pageFields = {page_json};
  const pick = (root, f) => {{
    let el = null;
    try {{ el = root.querySelector(f.selector); }} catch (e) {{ return null; }}
    if (!el) return null;
    if (f.attr === 'html') return el.innerHTML;
    if (f.attr) return el.getAttribute(f.attr);
    if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement || el instanceof HTMLSelectElement)
      return el.value;
    return (el.textContent || '').trim();
  }};
  const itemSel = {item_json};
  const roots = itemSel ? Array.from(document.querySelectorAll(itemSel)) : [document];
  const pageVals = {{}};
  for (const f of pageFields) pageVals[f.name] = pick(document, f);
  const rows = roots.map(root => {{
    const row = {{}};
    for (const f of fields) {{
      const v = pick(root, f);
      row[f.name] = (v === undefined || v === null || v === '') ? null : v;
    }}
    if (!itemSel) for (const [k, v] of Object.entries(pageVals)) row[k] = v;
    return row;
  }});
  if (itemSel && Object.keys(pageVals).length) for (const r of rows) {{
    for (const [k, v] of Object.entries(pageVals)) if (!(k in r)) r[k] = v;
  }}
  return rows;
}})()"#,
        fields_json = fields_json,
        page_json = page_json,
        item_json = serde_json::to_string(&item).unwrap_or_else(|_| "\"\"".into()),
    )
}

/// Run the full spec against one Space: scroll rounds first (infinite
/// scroll), then extraction, then pagination via the next selector.
pub async fn scrape(space: &Space, spec: &ScrapeSpec) -> Result<Vec<ScrapePage>> {
    spec.validate()?;
    let mut pages = Vec::new();

    for page_no in 0..spec.max_pages.max(1) {
        if spec.scroll_rounds > 0 {
            infinite_scroll(space, spec).await?;
        }
        if spec.wait_ms > 0 {
            tokio::time::sleep(std::time::Duration::from_millis(spec.wait_ms)).await;
        }
        pages.push(scrape_page(space, spec).await?);

        if page_no + 1 >= spec.max_pages.max(1) {
            break;
        }
        let Some(next) = &spec.next else { break };
        let selector = crate::space::Selector::parse(next);
        space
            .click(&selector)
            .await
            .map_err(|e| anyhow!("next-page click `{next}`: {e}"))?;
        if spec.wait_ms > 0 {
            tokio::time::sleep(std::time::Duration::from_millis(spec.wait_ms)).await;
        }
    }
    Ok(pages)
}

/// Scroll to the bottom `rounds` times, stopping early when the page height
/// stops growing.
async fn infinite_scroll(space: &Space, spec: &ScrapeSpec) -> Result<()> {
    let mut last_height: f64 = -1.0;
    for _ in 0..spec.scroll_rounds {
        space.scroll(0.0, spec.scroll_dy).await?;
        tokio::time::sleep(std::time::Duration::from_millis(250)).await;
        let h = space
            .evaluate("document.body ? document.body.scrollHeight : 0")
            .await
            .unwrap_or(Value::from(0));
        let h = h.as_f64().unwrap_or(0.0);
        if h <= last_height {
            break; // height stabilized — nothing more to load
        }
        last_height = h;
    }
    Ok(())
}

// ---------------------------------------------------------------------
// exports
// ---------------------------------------------------------------------

/// Union of row keys in first-seen order (CSV/Markdown columns).
pub fn columns(rows: &[Map<String, Value>]) -> Vec<String> {
    let mut cols: Vec<String> = Vec::new();
    for row in rows {
        for k in row.keys() {
            if !cols.iter().any(|c| c == k) {
                cols.push(k.clone());
            }
        }
    }
    cols
}

fn cell_str(v: &Value) -> String {
    match v {
        Value::Null => String::new(),
        Value::String(s) => s.clone(),
        Value::Bool(b) => b.to_string(),
        Value::Number(n) => n.to_string(),
        other => other.to_string(),
    }
}

/// Rows as a pretty JSON document.
pub fn to_json(rows: &[Map<String, Value>]) -> String {
    let arr: Vec<&Map<String, Value>> = rows.iter().collect();
    serde_json::to_string_pretty(&arr).unwrap_or_else(|_| "[]".into())
}

/// RFC 4180 CSV — quoted when needed, embedded quotes doubled, \r\n rows.
pub fn to_csv(rows: &[Map<String, Value>]) -> String {
    let cols = columns(rows);
    let mut out = String::new();
    let header: Vec<String> = cols.iter().map(|c| csv_cell(c)).collect();
    out.push_str(&header.join(","));
    out.push_str("\r\n");
    for row in rows {
        let cells: Vec<String> = cols
            .iter()
            .map(|c| csv_cell(&cell_str(row.get(c).unwrap_or(&Value::Null))))
            .collect();
        out.push_str(&cells.join(","));
        out.push_str("\r\n");
    }
    out
}

fn csv_cell(s: &str) -> String {
    if s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r') {
        format!("\"{}\"", s.replace('"', "\"\""))
    } else {
        s.to_string()
    }
}

/// GitHub-Flavored Markdown table with pipe escaping.
pub fn to_markdown(rows: &[Map<String, Value>]) -> String {
    let cols = columns(rows);
    if cols.is_empty() {
        return String::new();
    }
    let esc = |s: &str| s.replace('|', "\\|").replace('\n', " ").replace('\r', "");
    let mut out = String::new();
    out.push('|');
    for c in &cols {
        out.push(' ');
        out.push_str(&esc(c));
        out.push_str(" |");
    }
    out.push('\n');
    out.push('|');
    for _ in &cols {
        out.push_str(" --- |");
    }
    out.push('\n');
    for row in rows {
        out.push('|');
        for c in &cols {
            let cell = cell_str(row.get(c).unwrap_or(&Value::Null));
            out.push(' ');
            out.push_str(&esc(&cell));
            out.push_str(" |");
        }
        out.push('\n');
    }
    out
}

/// Format a number for code emission (no trailing `.0` on whole f64s).
pub fn fmt_num(v: f64) -> String {
    if v.fract() == 0.0 && v.abs() < 1e15 {
        format!("{}", v as i64)
    } else {
        format!("{v}")
    }
}

/// Summary envelope for CLI output.
pub fn summary_json(pages: &[ScrapePage], ms: u64) -> Value {
    let rows: usize = pages.iter().map(|p| p.rows.len()).sum();
    json!({
        "pages": pages.len(),
        "rows": rows,
        "ms": ms,
        "urls": pages.iter().map(|p| p.url.clone()).collect::<Vec<_>>(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn field_spec_parse_forms() {
        let f = FieldSpec::parse("title=h2.item").unwrap();
        assert_eq!(f.name, "title");
        assert_eq!(f.selector, "h2.item");
        assert_eq!(f.attr, None);

        let f = FieldSpec::parse("link=a@href").unwrap();
        assert_eq!(f.attr.as_deref(), Some("href"));

        let f = FieldSpec::parse("body=div.post@html").unwrap();
        assert_eq!(f.attr.as_deref(), Some("html"));

        let f = FieldSpec::parse("img=img.cover@src").unwrap();
        assert_eq!(f.attr.as_deref(), Some("src"));

        // `@` inside the selector is not an attribute split
        let f = FieldSpec::parse("x=[data-x@1]").unwrap();
        assert_eq!(f.attr, None);
        assert_eq!(f.selector, "[data-x@1]");

        assert!(FieldSpec::parse("noselector").is_err());
        assert!(FieldSpec::parse("=sel").is_err());
        assert!(FieldSpec::parse("name=").is_err());
    }

    #[test]
    fn spec_validation_requires_something_to_extract() {
        let mut spec = ScrapeSpec::default();
        assert!(spec.validate().is_err());
        spec.fields.push(FieldSpec::parse("t=h1").unwrap());
        assert!(spec.validate().is_ok());
    }

    #[test]
    fn extraction_js_embeds_spec_and_handles_missing_fields() {
        let spec = ScrapeSpec {
            item: Some("li.product".into()),
            fields: vec![
                FieldSpec::parse("name=h3").unwrap(),
                FieldSpec::parse("url=a@href").unwrap(),
            ],
            page_fields: vec![FieldSpec::parse("page_title=h1").unwrap()],
            ..Default::default()
        };
        let js = extract_js(&spec);
        assert!(js.contains("li.product"), "{js}");
        assert!(js.contains("\"attr\":\"href\""), "{js}");
        assert!(js.contains("querySelectorAll"), "{js}");
        assert!(js.contains("innerHTML"), "html attr path");
        // a spec without item extracts one row from document
        let spec2 = ScrapeSpec {
            fields: vec![FieldSpec::parse("title=h1".into()).unwrap()],
            ..Default::default()
        };
        let js2 = extract_js(&spec2);
        assert!(js2.contains("const roots = itemSel ?"), "{js2}");
    }

    #[test]
    fn csv_export_quotes_and_unions_columns() {
        let mut a = Map::new();
        a.insert("name".into(), json!("pen \"fine\""));
        a.insert("qty".into(), json!(2));
        let mut b = Map::new();
        b.insert("name".into(), json!("note, sticky"));
        b.insert("note".into(), json!("line1\nline2"));
        b.insert("qty".into(), json!(5));
        let csv = to_csv(&[a, b]);
        let lines: Vec<&str> = csv.split("\r\n").collect();
        assert_eq!(lines[0], "name,qty,note");
        assert!(lines[1].contains("\"pen \"\"fine\"\"\""), "embedded quotes doubled: {csv}");
        assert!(lines[2].contains("\"note, sticky\""), "comma forces quoting");
        assert!(lines[2].contains("\"line1\nline2\""), "newline forces quoting");
    }

    #[test]
    fn markdown_export_escapes_pipes() {
        let mut a = Map::new();
        a.insert("a|b".into(), json!("x|y"));
        let md = to_markdown(&[a]);
        assert!(md.starts_with("| a\\|b |\n| --- |\n| x\\|y |\n"), "{md}");
    }

    #[test]
    fn json_export_round_trips() {
        let mut a = Map::new();
        a.insert("n".into(), json!(1));
        a.insert("empty".into(), Value::Null);
        let s = to_json(&[a]);
        let back: Value = serde_json::from_str(&s).unwrap();
        assert_eq!(back[0]["n"], 1);
        assert!(back[0]["empty"].is_null());
    }

    #[test]
    fn columns_first_seen_order() {
        let mut a = Map::new();
        a.insert("x".into(), json!(1));
        let mut b = Map::new();
        b.insert("y".into(), json!(2));
        b.insert("x".into(), json!(3));
        let cols = columns(&[a, b]);
        assert_eq!(cols, vec!["x", "y"]);
    }

    #[test]
    fn fmt_num_whole_numbers_have_no_fraction() {
        assert_eq!(fmt_num(600.0), "600");
        assert_eq!(fmt_num(0.0), "0");
        assert_eq!(fmt_num(-40.5), "-40.5");
    }
}
