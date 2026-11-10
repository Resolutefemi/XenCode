//! The snapshot primitive — `DOMSnapshot.captureSnapshot` turned into a
//! node tree the agent can read, plus an ego-style outline renderer.
//!
//! Why DOMSnapshot and not an accessibility tree: one call returns the whole
//! document (nodes, attributes, text, layout boxes) with stable
//! `backendNodeId`s — the same ids the [`crate::diff`] engine keys deltas on.
//! A `computedStyles` whitelist (`display`, `visibility`) prunes invisible
//! subtrees.
//!
//! [`snapshot_outline`] renders the model-facing text: indented element
//! lines with the attributes that matter (`id`, `class`, `role`, `name`,
//! `href`, `placeholder`, `type`, `value`, `aria-label`), the element's own
//! text (head-truncated) and its layout box. Budget-capped like the Phase-5
//! repo map.

use std::collections::{BTreeMap, HashMap};

use serde_json::Value;

/// Char budget for the model-facing outline (≈1k tokens).
pub const OUTLINE_BUDGET: usize = 4096;

/// Tags that carry no interactive or textual meaning for the agent.
const SKIP_TAGS: &[&str] = &[
    "head", "script", "style", "link", "meta", "noscript", "template", "path", "defs", "g",
    "clippath", "lineargradient", "stop", "br", "wbr", "source", "track", "param", "base",
];

#[derive(Debug, Clone, Default, PartialEq)]
pub struct SnapNode {
    /// Stable CDP node id — the diff engine's join key.
    pub backend_id: u32,
    /// Lowercased tag (`div`, `a`, …) or `#text` / `#document`.
    pub tag: String,
    pub attrs: BTreeMap<String, String>,
    /// The node's own text (from `#text` children or the layout entry).
    pub text: String,
    /// Layout box `[x, y, w, h]` in CSS pixels, when the node has one.
    pub rect: Option<[f32; 4]>,
    pub children: Vec<usize>,
    pub parent: Option<usize>,
    pub depth: usize,
    /// `display:none` / `visibility:hidden` (inherited) or pruned junk tag.
    pub visible: bool,
    pub clickable: bool,
}

#[derive(Debug, Clone, Default)]
pub struct Snapshot {
    pub nodes: Vec<SnapNode>,
    /// node index (into `nodes`) of the `<html>` element.
    pub root: Option<usize>,
}

impl Snapshot {
    pub fn by_backend(&self, id: u32) -> Option<&SnapNode> {
        self.nodes.iter().find(|n| n.backend_id == id)
    }

    /// Count of visible, meaningful elements (outline lines if uncapped).
    pub fn meaningful(&self) -> usize {
        self.nodes
            .iter()
            .filter(|n| n.visible && n.depth > 0 && !SKIP_TAGS.contains(&n.tag.as_str()))
            .count()
    }
}

#[derive(Debug, thiserror::Error)]
#[error("snapshot parse: {0}")]
pub struct ParseError(pub String);

pub type Result<T> = std::result::Result<T, ParseError>;

/// Parse the raw `DOMSnapshot.captureSnapshot` result.
///
/// Requested by [`crate::space::Space::snapshot`] with
/// `computedStyles: ["display","visibility"], includeDOMRects: true`.
///
/// Wire shape notes (verified against Chrome for Testing 143, and tolerant
/// of the older documented shape):
/// - one string table at the RESULT level (`documents[].strings` accepted
///   for older shapes); **string index -1 means "absent"**, and index 0 is
///   not guaranteed to be the empty string;
/// - `nodes.parentIndex` (one entry per node, -1 = root) with
///   `childNodeIndexes` as the legacy alternative;
/// - `nodeType`: 9 document / 1 element / 3 text — only text nodes carry a
///   meaningful `nodeValue`;
/// - `layout.bounds` is a 2D array (one client-rect list per entry);
/// - `layout.text` gives the text-box string per layout entry (-1 = none) —
///   element text is aggregated from direct `#text` children;
/// - `layout.styles` carries the requested computed styles as flattened
///   name/value string-index pairs per layout entry.
pub fn parse_snapshot(v: &Value) -> Result<Snapshot> {
    let docs = v
        .get("documents")
        .and_then(Value::as_array)
        .ok_or_else(|| ParseError("no documents array".into()))?;
    let doc = docs
        .first()
        .ok_or_else(|| ParseError("empty documents array".into()))?;

    // Real Chrome (143+): one string table at the result level. Older
    // shapes (and the unit fixture) carry it per document — accept both.
    let strings_src = v
        .get("strings")
        .or_else(|| doc.get("strings"))
        .ok_or_else(|| ParseError("no strings table".into()))?;
    let strings: Vec<&str> = strings_src
        .as_array()
        .map(|a| a.iter().map(|s| s.as_str().unwrap_or("")).collect())
        .ok_or_else(|| ParseError("strings table is not an array".into()))?;

    let nodes_obj = doc
        .get("nodes")
        .ok_or_else(|| ParseError("no nodes in document".into()))?;
    let arr = |key: &str| -> Vec<Value> {
        nodes_obj
            .get(key)
            .and_then(Value::as_array)
            .map(|a| a.iter().cloned().collect())
            .unwrap_or_default()
    };
    // Resolve a string-table index (-1 / out-of-range = absent).
    let s_at = |arr: &[Value], i: usize| -> &str {
        arr.get(i)
            .and_then(Value::as_i64)
            .filter(|x| *x >= 0)
            .and_then(|x| strings.get(x as usize))
            .copied()
            .unwrap_or("")
    };
    let u_at = |arr: &[Value], i: usize| -> Option<u32> {
        arr.get(i).and_then(Value::as_u64).map(|x| x as u32)
    };

    let name_arr = arr("nodeName");
    let value_arr = arr("nodeValue");
    let backend_arr = arr("backendNodeId");
    let type_arr = arr("nodeType");
    let attr_rows = arr("attributes");
    let child_rows = arr("childNodeIndexes");
    let parent_arr = arr("parentIndex");

    let n = backend_arr.len();
    if n == 0 {
        return Err(ParseError("no backend node ids".into()));
    }

    // Build nodes.
    let mut nodes: Vec<SnapNode> = Vec::with_capacity(n);
    for i in 0..n {
        let raw_name = s_at(&name_arr, i);
        // Element/text discrimination: nodeType when present, else the name.
        let is_text = match type_arr.get(i).and_then(Value::as_u64) {
            Some(t) => t == 3,
            None => raw_name == "#text",
        };
        let tag = if raw_name.is_empty() {
            if is_text { "#text".to_string() } else { "#document".to_string() }
        } else {
            raw_name.to_ascii_lowercase()
        };
        // nodeValue is only meaningful for text (and comment) nodes; for
        // elements it is -1 (older engines may repeat a dummy index).
        let value = if is_text { s_at(&value_arr, i) } else { "" };
        let mut attrs = BTreeMap::new();
        if let Some(row) = attr_rows.get(i).and_then(Value::as_array) {
            for pair in row.chunks(2) {
                if pair.len() != 2 {
                    continue;
                }
                let (Some(k), Some(v)) = (
                    pair[0].as_i64().and_then(|x| strings.get(x.max(0) as usize)),
                    pair[1].as_i64().and_then(|x| strings.get(x.max(0) as usize)),
                ) else {
                    continue;
                };
                if !k.is_empty() {
                    attrs.insert(k.to_string(), v.to_string());
                }
            }
        }
        nodes.push(SnapNode {
            backend_id: u_at(&backend_arr, i).unwrap_or(i as u32),
            tag,
            attrs,
            text: value.to_string(),
            rect: None,
            children: Vec::new(),
            parent: None,
            depth: 0,
            visible: true,
            clickable: false,
        });
    }

    // Parent/child wiring. Real Chrome reports `parentIndex` (one entry per
    // node, -1 = root); older shapes carry `childNodeIndexes`. Prefer
    // parentIndex when present, fall back to the child lists.
    if parent_arr.len() >= n {
        for (i, pv) in parent_arr.iter().enumerate().take(n) {
            if let Some(pi) = pv.as_i64() {
                if pi >= 0 && (pi as usize) < n && pi != i as i64 {
                    let parent = pi as usize;
                    nodes[i].parent = Some(parent);
                    nodes[parent].children.push(i);
                }
            }
        }
    } else {
        for (i, row) in child_rows.iter().enumerate().take(n) {
            let Some(kids) = row.as_array() else { continue };
            let idxs: Vec<usize> = kids
                .iter()
                .filter_map(Value::as_u64)
                .map(|x| x as usize)
                .filter(|&k| k < n)
                .collect();
            for &k in &idxs {
                nodes[k].parent = Some(i);
            }
            nodes[i].children = idxs;
        }
    }

    // Layout entries: parallel nodeIndex / bounds (2D) / text arrays.
    let layout = doc.get("layout");
    let layout_node_idx: Vec<usize> = layout
        .and_then(|l| l.get("nodeIndex"))
        .and_then(Value::as_array)
        .map(|a| a.iter().filter_map(Value::as_u64).map(|x| x as usize).collect())
        .unwrap_or_default();
    let layout_bounds: Vec<Value> = layout
        .and_then(|l| l.get("bounds"))
        .and_then(Value::as_array)
        .map(|a| a.iter().cloned().collect())
        .unwrap_or_default();
    let layout_text: Vec<Value> = layout
        .and_then(|l| l.get("text"))
        .and_then(Value::as_array)
        .map(|a| a.iter().cloned().collect())
        .unwrap_or_default();

    // bounds shapes seen in the wild:
    // - Chrome for Testing 143 (includeDOMRects): one [x,y,w,h] array per
    //   layout entry (occasionally a list of client-rect arrays);
    // - documented/legacy: one flat number array, 4 numbers per node.
    let bounds_of_arrays = layout_bounds
        .first()
        .map(Value::is_array)
        .unwrap_or(false);
    let rect_of = move |li: usize| -> Option<[f32; 4]> {
        if bounds_of_arrays {
            let entry = layout_bounds.get(li)?.as_array()?;
            // A list of client rects → keep the first; plain [x,y,w,h] → itself.
            let rect: &Vec<Value> = if entry.first().map(Value::is_array).unwrap_or(false) {
                entry.first()?.as_array()?
            } else {
                entry
            };
            if rect.len() < 4 {
                return None;
            }
            let g = |k: usize| rect[k].as_f64().unwrap_or(0.0) as f32;
            Some([g(0), g(1), g(2), g(3)])
        } else {
            let flat: Vec<f64> = layout_bounds.iter().filter_map(Value::as_f64).collect();
            if flat.len() < li * 4 + 4 {
                return None;
            }
            let g = |k: usize| flat[li * 4 + k] as f32;
            Some([g(0), g(1), g(2), g(3)])
        }
    };

    for (li, &ni) in layout_node_idx.iter().enumerate() {
        if ni >= n {
            continue;
        }
        if let Some(rect) = rect_of(li) {
            if let Some(node) = nodes.get_mut(ni) {
                node.rect = Some(rect);
            }
        }
        // Layout text (only text nodes carry one in practice).
        if let Some(txt) = layout_text
            .get(li)
            .and_then(Value::as_i64)
            .filter(|x| *x >= 0)
            .and_then(|x| strings.get(x as usize))
        {
            if !txt.trim().is_empty() {
                if let Some(node) = nodes.get_mut(ni) {
                    if node.text.trim().is_empty() {
                        node.text = txt.to_string();
                    }
                }
            }
        }
    }

    // Computed styles: node index → (display, visibility). Real Chrome
    // (143+) reports them inside `layout.styles` as flattened name/value
    // string-index pairs per layout entry; older shapes carry a top-level
    // `computedStyles` array of {nodeIndex, styles:[{name, value}]}. Accept
    // both — most nodes simply have neither, which means "no override".
    let mut style_display: HashMap<usize, String> = HashMap::new();
    let mut style_visibility: HashMap<usize, String> = HashMap::new();
    if let Some(styles_rows) = layout.and_then(|l| l.get("styles")).and_then(Value::as_array) {
        for (li, row) in styles_rows.iter().enumerate() {
            let Some(&ni) = layout_node_idx.get(li) else { break };
            if ni >= n {
                continue;
            }
            let Some(pairs) = row.as_array() else { continue };
            for pair in pairs.chunks(2) {
                if pair.len() != 2 {
                    continue;
                }
                let (Some(name), Some(val)) = (
                    pair[0].as_i64().and_then(|x| strings.get(x.max(0) as usize)).map(|s| *s),
                    pair[1].as_i64().and_then(|x| strings.get(x.max(0) as usize)),
                ) else {
                    continue;
                };
                match name {
                    "display" => {
                        style_display.insert(ni, val.to_string());
                    }
                    "visibility" => {
                        style_visibility.insert(ni, val.to_string());
                    }
                    _ => {}
                }
            }
        }
    }
    for entry in doc
        .get("computedStyles")
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
    {
        let Some(idx) = entry.get("nodeIndex").and_then(Value::as_u64) else { continue };
        for st in entry.get("styles").and_then(Value::as_array).into_iter().flatten() {
            let name = st.get("name").and_then(Value::as_str).unwrap_or("");
            let val = st.get("value").and_then(Value::as_str).unwrap_or("");
            match name {
                "display" => {
                    style_display.insert(idx as usize, val.to_string());
                }
                "visibility" => {
                    style_visibility.insert(idx as usize, val.to_string());
                }
                _ => {}
            }
        }
    }

    // Aggregate direct #text children into their parent element's text so an
    // outline line reads `button "Sign in"` without extra indirection. Only
    // direct children — nested elements keep their own lines.
    let aggregated: Vec<(usize, String)> = nodes
        .iter()
        .enumerate()
        .filter(|(_, node)| !node.tag.starts_with('#'))
        .map(|(i, node)| {
            if !node.text.trim().is_empty() {
                return (i, String::new());
            }
            let mut parts: Vec<String> = Vec::new();
            for &k in &node.children {
                if let Some(child) = nodes.get(k) {
                    if child.tag == "#text" && !child.text.trim().is_empty() {
                        parts.push(child.text.split_whitespace().collect::<Vec<_>>().join(" "));
                    }
                }
            }
            (i, parts.join(" "))
        })
        .collect();
    for (i, text) in aggregated {
        if !text.is_empty() {
            if let Some(node) = nodes.get_mut(i) {
                node.text = text;
            }
        }
    }

    // Depths + visibility inheritance from the root(s).
    let mut root = None;
    for (i, node) in nodes.iter().enumerate() {
        if node.parent.is_none() {
            root = Some(i);
            break;
        }
    }
    fn walk(
        nodes: &mut [SnapNode],
        i: usize,
        depth: usize,
        visible: bool,
        style_display: &HashMap<usize, String>,
        style_visibility: &HashMap<usize, String>,
    ) {
        let (tag, disp, vis) = {
            let node = &nodes[i];
            (
                node.tag.clone(),
                style_display.get(&i).cloned().unwrap_or_default(),
                style_visibility.get(&i).cloned().unwrap_or_default(),
            )
        };
        let self_visible = visible
            && disp != "none"
            && vis != "hidden"
            && !SKIP_TAGS.contains(&tag.as_str());
        nodes[i].depth = depth;
        nodes[i].visible = self_visible;
        let kids = nodes[i].children.clone();
        for k in kids {
            walk(nodes, k, depth + 1, self_visible, style_display, style_visibility);
        }
    }
    if let Some(r) = root {
        walk(&mut nodes, r, 0, true, &style_display, &style_visibility);
    }

    // Clickability: CDP reports isClickable when present; also treat
    // interactive tags/roles as clickable for the outline.
    let click_rows: Vec<Value> = arr("isClickable");
    for (i, node) in nodes.iter_mut().enumerate() {
        node.clickable = click_rows
            .get(i)
            .and_then(Value::as_bool)
            .unwrap_or(false)
            || matches!(
                node.tag.as_str(),
                "a" | "button" | "input" | "select" | "textarea" | "label" | "summary"
            )
            || node.attrs.contains_key("onclick")
            || node
                .attrs
                .get("role")
                .map(|r| r == "button" || r == "link" || r == "tab")
                .unwrap_or(false);
    }

    Ok(Snapshot { nodes, root })
}

/// A single outline line, kept structured so tests (and the GUI) can assert
/// on components instead of string contents.
#[derive(Debug, Clone, PartialEq)]
pub struct OutlineLine {
    pub depth: usize,
    pub tag: String,
    pub desc: String,
}

/// Render the ego-style outline for the model, under `budget` chars.
/// Returns `(text, truncated, lines)`.
pub fn snapshot_outline(snap: &Snapshot, budget: usize) -> (String, bool, Vec<OutlineLine>) {
    let mut lines: Vec<OutlineLine> = Vec::new();
    if let Some(r) = snap.root {
        outline_walk(snap, r, &mut lines);
    }

    let mut out = String::new();
    let mut truncated = false;
    for line in &lines {
        let text = render_line(line);
        if out.len() + text.len() + 1 > budget {
            truncated = true;
            break;
        }
        out.push_str(&text);
        out.push('\n');
    }
    (out, truncated, lines)
}

fn outline_walk(snap: &Snapshot, i: usize, lines: &mut Vec<OutlineLine>) {
    let node = &snap.nodes[i];
    let mut skip_children = false;
    if node.visible && node.depth > 0 {
        // Text nodes are folded into their parent element's line; documents
        // and other '#' pseudo-nodes never render.
        if !node.tag.starts_with('#') && node.tag != "html" {
            // Elements with no attrs, no text and exactly one element child are
            // layout noise (div > div > div chains) — collapse them by skipping
            // the line but keeping the subtree.
            let has_signal = !node.attrs.is_empty()
                || !node.text.trim().is_empty()
                || node.clickable
                || matches!(
                    node.tag.as_str(),
                    "a" | "button" | "input" | "select" | "textarea" | "img" | "h1" | "h2"
                        | "h3" | "h4" | "h5" | "h6" | "p" | "li" | "nav" | "header" | "footer"
                        | "main" | "form" | "table" | "label" | "svg" | "video" | "iframe"
                );
            let single_chain = node.attrs.is_empty()
                && node.text.trim().is_empty()
                && node.children.len() == 1;
            if has_signal || !single_chain {
                lines.push(OutlineLine {
                    depth: node.depth.saturating_sub(1),
                    tag: node.tag.clone(),
                    desc: describe(node),
                });
            }
            skip_children = matches!(node.tag.as_str(), "svg");
        }
    }
    if !skip_children {
        for &k in &node.children {
            outline_walk(snap, k, lines);
        }
    }
}

/// Tag + the attributes that matter — the line head both the outline and
/// the diff engine build on.
pub fn head(node: &SnapNode) -> String {
    let mut parts: Vec<String> = Vec::new();
    let mut push = |k: &str, v: &str, cap: usize| {
        if !v.is_empty() {
            let v = truncate(v, cap);
            parts.push(format!("{k}={v}"));
        }
    };
    if let Some(id) = node.attrs.get("id") {
        push("id", id, 40);
    }
    if let Some(cls) = node.attrs.get("class") {
        // first two class tokens only
        let two: Vec<&str> = cls.split_whitespace().take(2).collect();
        if !two.is_empty() {
            push("class", &two.join(" "), 40);
        }
    }
    for k in ["role", "name", "placeholder", "type", "aria-label"] {
        if let Some(v) = node.attrs.get(k) {
            push(k, v, 40);
        }
    }
    if node.tag == "a" {
        if let Some(href) = node.attrs.get("href") {
            if !href.starts_with('#') {
                push("href", href, 60);
            }
        }
    }
    let mut s = node.tag.clone();
    if !parts.is_empty() {
        s.push(' ');
        s.push_str(&parts.join(" "));
    }
    s
}

/// Human-readable one-line description: `button "Sign in" [312,44 96x28]`.
pub fn describe(node: &SnapNode) -> String {
    let mut s = head(node);
    let text = node.text.trim();
    if !text.is_empty() {
        s.push_str(" \"");
        s.push_str(&truncate(text, 80));
        s.push('"');
    }
    if let Some([x, y, w, h]) = node.rect {
        s.push_str(&format!(" [{x:.0},{y:.0} {w:.0}x{h:.0}]"));
    }
    s
}

fn truncate(s: &str, cap: usize) -> String {
    if s.chars().count() <= cap {
        s.to_string()
    } else {
        let mut out: String = s.chars().take(cap).collect();
        out.push('…');
        out
    }
}

fn render_line(line: &OutlineLine) -> String {
    let indent = "  ".repeat(line.depth);
    // `desc` already starts with the tag — keep the two fields separate so
    // tests and the GUI can assert on structure without re-parsing.
    format!("{indent}{}", line.desc)
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    /// Build a small but structurally truthful DOMSnapshot result:
    /// `<html><body><nav id="top" class="bar" role="navigation">
    ///   <a href="/x">Home</a>
    ///   <button style="display:none">Ghost</button>
    ///   <main><p>Hello world</p><svg><path/></svg></main>
    /// </body></html>`
    ///
    /// String table (index → value):
    /// 0 "" 1 html 2 body 3 nav 4 a 5 button 6 main 7 p 8 svg 9 path
    /// 10 #text 11 top 12 bar 13 navigation 14 /x 15 Home 16 Ghost
    /// 17 Hello world 18 href 19 id 20 class 21 role 22 display
    /// 23 visibility 24 none
    fn fixture() -> Value {
        let strings = json!([
            "", "html", "body", "nav", "a", "button", "main", "p", "svg", "path", "#text",
            "top", "bar", "navigation", "/x", "Home", "Ghost", "Hello world",
            "href", "id", "class", "role", "display", "visibility", "none"
        ]);
        // Node order:
        // 0 html(101) 1 body(102) 2 nav(103) 3 a(104) 4 #text Home(105)
        // 5 button(106) 6 #text Ghost(107) 7 main(108) 8 p(109)
        // 9 #text Hello world(110) 10 svg(111) 11 path(112)
        let nodes = json!({
            "nodeName": [1, 2, 3, 4, 10, 5, 10, 6, 7, 10, 8, 9],
            "nodeValue": [0, 0, 0, 0, 15, 0, 16, 0, 0, 17, 0, 0],
            "backendNodeId": [101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111, 112],
            "attributes": [
                [],
                [],
                [19, 11, 20, 12, 21, 13],
                [18, 14],
                [],
                [],
                [],
                [],
                [],
                [],
                [],
                []
            ],
            "childNodeIndexes": [
                [1], [2, 5, 7], [3], [4], [], [6], [], [8, 10], [9], [], [11], []
            ]
        });
        // Layout entries: element boxes + text-node boxes (which carry the
        // text via layout.text, mirroring a real includeDOMRects capture).
        let layout = json!({
            "nodeIndex": [2, 3, 8, 10, 4, 6, 9],
            "bounds": [0,0,300,40, 8,10,50,20, 8,60,80,20, 8,90,16,16, 8,10,50,20, 8,44,60,16, 8,60,80,20],
            "text": [0, 0, 0, 0, 15, 16, 17]
        });
        let computed_styles = json!([
            {"nodeIndex": 5, "styles": [{"name": "display", "value": "none"}]}
        ]);
        json!({
            "documents": [{
                "strings": strings,
                "nodes": nodes,
                "layout": layout,
                "computedStyles": computed_styles
            }]
        })
    }

    #[test]
    fn parses_tree_attributes_and_rects() {
        let snap = parse_snapshot(&fixture()).unwrap();
        assert_eq!(snap.nodes.len(), 12);
        let root = snap.root.unwrap();
        assert_eq!(snap.nodes[root].tag, "html");
        // nav: id/class/role parsed
        let nav = snap.by_backend(103).unwrap();
        assert_eq!(nav.attrs.get("id").map(String::as_str), Some("top"));
        assert_eq!(nav.attrs.get("class").map(String::as_str), Some("bar"));
        assert_eq!(nav.attrs.get("role").map(String::as_str), Some("navigation"));
        assert_eq!(nav.rect, Some([0.0, 0.0, 300.0, 40.0]));
        // <a> aggregates its direct #text child
        let a = snap.by_backend(104).unwrap();
        assert_eq!(a.text, "Home");
        assert!(a.clickable, "anchors are clickable");
        // button is display:none → invisible, together with its text child
        let button = snap.by_backend(106).unwrap();
        assert!(!button.visible);
        assert_eq!(button.text, "Ghost");
        let ghost_text = snap.by_backend(107).unwrap();
        assert!(!ghost_text.visible);
        // depths follow the tree: html(0) body(1) main(2) p(3)
        let p = snap.by_backend(109).unwrap();
        assert_eq!(p.depth, 3);
        assert_eq!(p.text, "Hello world");
        // main keeps no aggregated text (only p, its element child, does)
        let main = snap.by_backend(108).unwrap();
        assert_eq!(main.text, "");
    }

    #[test]
    fn outline_has_structure_and_budget() {
        let snap = parse_snapshot(&fixture()).unwrap();
        let (text, truncated, lines) = snapshot_outline(&snap, OUTLINE_BUDGET);
        assert!(!truncated);
        assert!(text.contains("a href=/x \"Home\""), "got:\n{text}");
        assert!(text.contains("nav id=top class=bar role=navigation"));
        assert!(text.contains("p \"Hello world\""));
        assert!(!text.contains("Ghost"), "invisible button is pruned");
        assert!(!text.contains("path"), "svg internals are skipped");
        assert!(text.contains("svg"), "the svg line itself survives");
        // tiny budget truncates
        let (text2, truncated2, _) = snapshot_outline(&snap, 60);
        assert!(truncated2);
        assert!(text2.len() <= 61);
        assert!(!lines.is_empty());
    }

    #[test]
    fn div_chains_without_signal_collapse() {
        // <div><div><div id="leaf">x</div></div></div> renders one line: the
        // bare wrapper chain is layout noise, the leaf carries the signal.
        let strings = json!(["", "html", "body", "div", "#text", "leaf", "x", "id"]);
        let nodes = json!({
            "nodeName": [1, 2, 3, 3, 3, 4],
            "nodeValue": [0, 0, 0, 0, 0, 6],
            "backendNodeId": [1, 2, 3, 4, 5, 6],
            "attributes": [[], [], [], [], [7, 5], []],
            "childNodeIndexes": [[1], [2], [3], [4], [5], []]
        });
        let v = json!({
            "documents": [{
                "strings": strings,
                "nodes": nodes,
                "layout": {"nodeIndex": [], "bounds": [], "text": []},
                "computedStyles": []
            }]
        });
        let snap = parse_snapshot(&v).unwrap();
        let (text, _, lines) = snapshot_outline(&snap, OUTLINE_BUDGET);
        assert_eq!(lines.len(), 1, "{text}");
        assert!(text.contains("div id=leaf \"x\""), "{text}");
    }

    #[test]
    fn malformed_results_error_cleanly() {
        assert!(parse_snapshot(&json!({})).is_err());
        assert!(parse_snapshot(&json!({"documents": []})).is_err());
    }
}
