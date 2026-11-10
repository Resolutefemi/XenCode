//! Snapshot diffing — the token-saving half of the snapshot primitive.
//!
//! Two snapshots of the same page are matched by `backendNodeId` (stable for
//! a node's lifetime); the diff contains only what changed:
//! - **added**   — nodes present now, absent before
//! - **removed** — nodes present before, absent now
//! - **changed** — same id, different text / attributes / moved box
//!
//! The output is the model-facing text: one line per delta plus a summary
//! header, budget-capped. A diff of identical snapshots renders as
//! `(no changes)`.

use crate::snapshot::{describe, SnapNode, Snapshot};
use std::collections::HashMap;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Delta {
    Added,
    Removed,
    Changed,
}

#[derive(Debug, Clone, PartialEq)]
pub struct DiffLine {
    pub delta: Delta,
    pub backend_id: u32,
    /// Rendered one-liner (same shape as an outline line).
    pub line: String,
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct SnapshotDiff {
    pub added: Vec<DiffLine>,
    pub removed: Vec<DiffLine>,
    pub changed: Vec<DiffLine>,
}

impl SnapshotDiff {
    pub fn is_empty(&self) -> bool {
        self.added.is_empty() && self.removed.is_empty() && self.changed.is_empty()
    }

    pub fn len(&self) -> usize {
        self.added.len() + self.removed.len() + self.changed.len()
    }

    /// Model-facing text: summary header + one line per delta (added,
    /// removed, changed), capped at `budget` chars.
    pub fn render(&self, budget: usize) -> String {
        if self.is_empty() {
            return "(no changes)".to_string();
        }
        let mut out = format!(
            "{} added, {} removed, {} changed\n",
            self.added.len(),
            self.removed.len(),
            self.changed.len()
        );
        let all = self
            .added
            .iter()
            .map(|l| ('+', l))
            .chain(self.removed.iter().map(|l| ('-', l)))
            .chain(self.changed.iter().map(|l| ('~', l)));
        for (prefix, l) in all {
            let text = format!("{prefix} {}\n", l.line);
            if out.len() + text.len() > budget {
                out.push_str("…\n");
                break;
            }
            out.push_str(&text);
        }
        out
    }
}

/// Threshold (px) below which a box move isn't reported — antialiasing and
/// scroll jitter would otherwise flood the diff.
const MOVE_EPSILON: f32 = 2.0;

/// Diff `old` → `new`, matching nodes by `backendNodeId`.
pub fn diff_snapshots(old: &Snapshot, new: &Snapshot) -> SnapshotDiff {
    let old_by_id: HashMap<u32, &SnapNode> =
        old.nodes.iter().map(|n| (n.backend_id, n)).collect();

    let mut added = Vec::new();
    let mut changed = Vec::new();
    let mut seen: HashMap<u32, ()> = HashMap::new();

    for node in &new.nodes {
        seen.insert(node.backend_id, ());
        // Only visible, meaningful nodes participate: invisible plumbing
        // (script/style/head) and bare wrappers would dominate every delta.
        if !participates(node) {
            continue;
        }
        match old_by_id.get(&node.backend_id) {
            None => added.push(DiffLine {
                delta: Delta::Added,
                backend_id: node.backend_id,
                line: describe(node),
            }),
            Some(prev) => {
                if let Some(line) = change_line(prev, node) {
                    changed.push(DiffLine {
                        delta: Delta::Changed,
                        backend_id: node.backend_id,
                        line,
                    });
                }
            }
        }
    }

    let mut removed = Vec::new();
    for node in &old.nodes {
        if !seen.contains_key(&node.backend_id) && participates(node) {
            removed.push(DiffLine {
                delta: Delta::Removed,
                backend_id: node.backend_id,
                line: describe(node),
            });
        }
    }

    // Stable, readable order: document order (both vecs are already in
    // document order because we walked the nodes arrays in order).
    SnapshotDiff { added, removed, changed }
}

/// A node shows up in the diff only if the agent could perceive it.
fn participates(node: &SnapNode) -> bool {
    node.visible
        && node.depth > 0
        && !node.tag.starts_with('#')
        && !matches!(
            node.tag.as_str(),
            "script" | "style" | "head" | "link" | "meta" | "html"
        )
}

/// `Some(line)` when the same node changed: text, attributes or position.
fn change_line(old: &SnapNode, new: &SnapNode) -> Option<String> {
    let text_changed = old.text.trim() != new.text.trim();
    let attrs_changed = old.attrs != new.attrs;
    let moved = match (old.rect, new.rect) {
        (Some(a), Some(b)) => {
            (a[0] - b[0]).abs() > MOVE_EPSILON || (a[1] - b[1]).abs() > MOVE_EPSILON
        }
        (None, Some(_)) | (Some(_), None) => true,
        (None, None) => false,
    };
    if !text_changed && !attrs_changed && !moved {
        return None;
    }

    // Line shape: `tag attrs "old" → "new" [box] (moved)` — the transition
    // (not the final text twice) is the payload the model needs.
    let mut line = crate::snapshot::head(new);
    if text_changed {
        let old_text = old.text.trim();
        let new_text = new.text.trim();
        if old_text.is_empty() {
            line.push_str(&format!(" +text \"{}\"", ellipsize(new_text, 60)));
        } else {
            line.push_str(&format!(
                " \"{}\" → \"{}\"",
                ellipsize(old_text, 40),
                ellipsize(new_text, 60)
            ));
        }
    } else if !new.text.trim().is_empty() {
        line.push_str(&format!(" \"{}\"", ellipsize(new.text.trim(), 80)));
    }
    if let Some([x, y, w, h]) = new.rect {
        line.push_str(&format!(" [{x:.0},{y:.0} {w:.0}x{h:.0}]"));
    }
    if moved && !text_changed && !attrs_changed {
        // Position-only changes are rare; mark them so the model knows the
        // layout shifted without new content.
        line.push_str(" (moved)");
    }
    Some(line)
}

fn ellipsize(s: &str, cap: usize) -> String {
    if s.chars().count() <= cap {
        s.to_string()
    } else {
        let mut out: String = s.chars().take(cap).collect();
        out.push('…');
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn snap_with(nodes: &[(u32, &str, &str, Option<[f32; 4]>)]) -> Snapshot {
        // Minimal tree: every node hangs off an invisible root.
        let mut snap = Snapshot::default();
        for (_i, (id, tag, text, rect)) in nodes.iter().enumerate() {
            snap.nodes.push(SnapNode {
                backend_id: *id,
                tag: tag.to_string(),
                attrs: Default::default(),
                text: text.to_string(),
                rect: *rect,
                children: Vec::new(),
                parent: Some(0),
                depth: 1,
                visible: true,
                clickable: false,
            });
        }
        snap
    }

    fn snap_from_value(v: &serde_json::Value) -> Snapshot {
        crate::snapshot::parse_snapshot(v).unwrap()
    }

    #[test]
    fn identical_snapshots_diff_to_nothing() {
        let a = snap_with(&[(5, "p", "same", Some([0.0, 0.0, 10.0, 10.0]))]);
        let b = snap_with(&[(5, "p", "same", Some([0.0, 0.0, 10.0, 10.0]))]);
        let d = diff_snapshots(&a, &b);
        assert!(d.is_empty());
        assert_eq!(d.render(4096), "(no changes)");
    }

    #[test]
    fn added_removed_changed_are_reported() {
        let old = snap_with(&[
            (5, "p", "old text", Some([0.0, 0.0, 10.0, 10.0])),
            (6, "div", "gone", None),
            (7, "span", "kept", None),
        ]);
        let new = snap_with(&[
            (5, "p", "new text", Some([0.0, 0.0, 10.0, 10.0])),
            (7, "span", "kept", None),
            (9, "button", "New!", None),
        ]);
        let d = diff_snapshots(&old, &new);
        assert_eq!(d.added.len(), 1);
        assert_eq!(d.added[0].backend_id, 9);
        assert!(d.added[0].line.contains("button \"New!\""));
        assert_eq!(d.removed.len(), 1);
        assert_eq!(d.removed[0].backend_id, 6);
        assert_eq!(d.changed.len(), 1);
        assert_eq!(d.changed[0].backend_id, 5);
        assert!(d.changed[0].line.contains("old text") && d.changed[0].line.contains("new text"));

        let text = d.render(4096);
        assert!(text.starts_with("1 added, 1 removed, 1 changed\n"), "{text}");
        assert!(text.contains("+ button \"New!\""));
        assert!(text.contains("- div \"gone\""));
        assert!(text.contains("~ p \"old text\" → \"new text\""));
    }

    #[test]
    fn subpixel_jitter_and_reorders_are_ignored() {
        let a = snap_with(&[(5, "p", "same", Some([10.0, 20.0, 10.0, 10.0]))]);
        let b = snap_with(&[(5, "p", "same", Some([11.0, 21.0, 10.0, 10.0]))]);
        assert!(diff_snapshots(&a, &b).is_empty());
        // a 3px move is reported
        let c = snap_with(&[(5, "p", "same", Some([14.0, 20.0, 10.0, 10.0]))]);
        let d = diff_snapshots(&a, &c);
        assert_eq!(d.changed.len(), 1);
        assert!(d.changed[0].line.contains("(moved)"), "{:?}", d.changed);
    }

    #[test]
    fn invisible_and_junk_nodes_never_diff() {
        let mut a = snap_with(&[(5, "script", "var x = 1;", None)]);
        a.nodes[0].visible = false;
        let mut b = snap_with(&[(5, "script", "var x = 2;", None)]);
        b.nodes[0].visible = false;
        assert!(diff_snapshots(&a, &b).is_empty());
    }

    #[test]
    fn budget_caps_render() {
        let nodes: Vec<(u32, &str, &str, Option<[f32; 4]>)> =
            (1..=50).map(|i| (i, "div", "item", None)).collect();
        let old = snap_with(&[]);
        let new = snap_with(&nodes);
        let d = diff_snapshots(&old, &new);
        let text = d.render(300);
        assert!(text.len() <= 310, "{}", text.len());
        assert!(text.contains("…"), "truncation marker present");
    }

    #[test]
    fn end_to_end_through_the_snapshot_parser() {
        // Two captures of a page where a counter increments — the classic
        // "only deltas go to the model" flow.
        let strings = json!(["", "html", "body", "div", "#text", "0 items", "1 item", "id", "cart"]);
        // node order: 0 html 1 body 2 div#cart 3 #text
        let mk = |count_idx: usize, val: usize| {
            json!({
                "documents": [{
                    "strings": strings,
                    "nodes": {
                        "nodeName": [1, 2, 3, 4],
                        "nodeValue": [0, 0, 0, val],
                        "backendNodeId": [1, 2, 3, 4],
                        "attributes": [[], [], [7, 8], []],
                        "childNodeIndexes": [[1], [2], [3], []]
                    },
                    "layout": {"nodeIndex": [2, 3], "bounds": [8,8,100,20, 8,8,100,20], "text": [0, count_idx]},
                    "computedStyles": []
                }]
            })
        };
        let old = snap_from_value(&mk(5, 5));
        let new = snap_from_value(&mk(6, 6));
        let d = diff_snapshots(&old, &new);
        assert_eq!(d.changed.len(), 1, "{d:?}");
        let text = d.render(4096);
        assert!(text.contains("div id=cart"), "{text}");
        assert!(text.contains("0 items") && text.contains("1 item"), "{text}");
    }
}
