//! Repo map (Phase 5, harness port #10 — from Aider): tree-sitter symbol
//! extraction with relevance ranking, rendered into the system prompt so the
//! model starts with a live skeleton of the codebase instead of discovering
//! it file by file.
//!
//! Pipeline (Aider's approach, file-level):
//! 1. **Extract** — per-language tree-sitter queries pull symbol definitions
//!    (functions, types, classes, interfaces, impls, methods) with their
//!    first-line signatures. Languages: Rust, TypeScript/TSX, JavaScript,
//!    Python, Go.
//! 2. **Reference** — every identifier in a parsed file that matches a
//!    symbol defined in a *different* file adds a file→file reference edge.
//! 3. **Rank** — PageRank over the reference graph (damping 0.85): files
//!    that define widely-used symbols rank first.
//! 4. **Render** — ranked files, each with its signature lines, truncated
//!    to a character budget.
//!
//! Deterministic for a given worktree (ties break by path) and bounded:
//! files over 256 KiB are skipped, the walk is capped.

use std::collections::HashMap;
use std::path::{Path, PathBuf};

use tree_sitter::StreamingIterator;

/// Files larger than this are skipped (locks, minified bundles).
const MAX_FILE_BYTES: u64 = 256 * 1024;
/// Hard cap on parsed files (protects huge worktrees).
const MAX_FILES: usize = 3_000;
/// Walk depth limit (matches the grep tool's walk).
const MAX_DEPTH: usize = 10;
/// Signature lines longer than this are trimmed.
const MAX_SIG_CHARS: usize = 120;
/// Signatures shown per file before collapsing to `… N more`.
const MAX_SIGS_PER_FILE: usize = 15;
/// Identifier collection cap per file.
const MAX_IDENTIFIERS: usize = 20_000;

/// One symbol definition extracted from a file.
#[derive(Debug, Clone)]
pub struct Symbol {
    /// Rendered signature (first line of the definition).
    pub signature: String,
    /// 1-based line number of the definition.
    pub line: usize,
}

/// One parsed file's contribution to the map.
#[derive(Debug, Default, Clone)]
struct FileEntry {
    /// Display path (relative to the map root).
    rel: String,
    symbols: Vec<Symbol>,
    /// Every defined name (drives the reference edges).
    defines: Vec<String>,
    /// Every identifier-ish token found in the file.
    identifiers: Vec<String>,
}

/// A rendered repo map plus stats.
#[derive(Debug, Clone, Default)]
pub struct RepoMap {
    pub text: String,
    pub files: usize,
    pub symbols: usize,
    /// True when the map hit the char budget and was truncated.
    pub truncated: bool,
}

/// Extract + rank + render the repo map for `root` within `budget_chars`.
pub fn build(root: &Path, budget_chars: usize) -> RepoMap {
    let candidates = collect_files(root);
    let entries = extract_all(&candidates);
    let total_symbols: usize = entries.iter().map(|f| f.symbols.len()).sum();
    let ranks = page_rank(&entries);
    let mut map = render(&entries, &ranks, budget_chars);
    map.symbols = total_symbols;
    map
}

// ---------------------------------------------------------------------------
// File collection
// ---------------------------------------------------------------------------

/// Language detection by extension (tree-sitter grammars we ship).
fn language_for(path: &Path) -> Option<&'static str> {
    let ext = path.extension()?.to_str()?;
    Some(match ext {
        "rs" => "rust",
        "ts" | "tsx" | "mts" | "cts" => "ts",
        "js" | "jsx" | "mjs" | "cjs" => "js",
        "py" => "python",
        "go" => "go",
        _ => return None,
    })
}

fn ignored(name: &str) -> bool {
    matches!(
        name,
        ".git" | ".xencode" | "target" | "node_modules" | "dist" | "build" | "out" | "vendor"
    )
}

/// Walk the repo for parseable source files, sorted for determinism.
fn collect_files(root: &Path) -> Vec<PathBuf> {
    let mut out = Vec::new();
    walk(root, &mut out, 0);
    out.sort();
    out.truncate(MAX_FILES);
    out
}

fn walk(root: &Path, out: &mut Vec<PathBuf>, depth: usize) {
    if depth > MAX_DEPTH || out.len() >= MAX_FILES {
        return;
    }
    let Ok(rd) = std::fs::read_dir(root) else { return };
    for entry in rd.flatten() {
        let name = entry.file_name().to_string_lossy().into_owned();
        if ignored(&name) {
            continue;
        }
        let Ok(ft) = entry.file_type() else { continue };
        let p = entry.path();
        if ft.is_dir() {
            walk(&p, out, depth + 1);
        } else if let Some(_lang) = language_for(&p) {
            if entry.metadata().map(|m| m.len()).unwrap_or(0) <= MAX_FILE_BYTES {
                out.push(p);
            }
        }
    }
}

// ---------------------------------------------------------------------------
// tree-sitter extraction
// ---------------------------------------------------------------------------

/// The per-language symbol query. Captures: `name` (the identifier) and
/// `item` (the whole definition node — its first line is the signature).
const RUST_QUERY: &str = r#"
(function_item name: (identifier) @name) @item
(struct_item name: (type_identifier) @name) @item
(enum_item name: (type_identifier) @name) @item
(trait_item name: (type_identifier) @name) @item
(type_item name: (type_identifier) @name) @item
(mod_item name: (identifier) @name) @item
(impl_item type: (type_identifier) @name) @item
(const_item name: (identifier) @name) @item
"#;

const TS_QUERY: &str = r#"
(function_declaration name: (identifier) @name) @item
(class_declaration name: (type_identifier) @name) @item
(method_definition name: (property_identifier) @name) @item
(interface_declaration name: (type_identifier) @name) @item
(type_alias_declaration name: (type_identifier) @name) @item
(enum_declaration name: (identifier) @name) @item
(lexical_declaration (variable_declarator name: (identifier) @name value: [(arrow_function) (function_expression)])) @item
"#;

const JS_QUERY: &str = r#"
(function_declaration name: (identifier) @name) @item
(generator_function_declaration name: (identifier) @name) @item
(class_declaration name: (identifier) @name) @item
(method_definition name: (property_identifier) @name) @item
(lexical_declaration (variable_declarator name: (identifier) @name value: [(arrow_function) (function)])) @item
"#;

const PY_QUERY: &str = r#"
(function_definition name: (identifier) @name) @item
(class_definition name: (identifier) @name) @item
"#;

const GO_QUERY: &str = r#"
(function_declaration name: (identifier) @name) @item
(method_declaration name: (field_identifier) @name) @item
(type_declaration (type_spec name: (type_identifier) @name)) @item
"#;

fn ts_language(lang: &str) -> Option<tree_sitter::Language> {
    Some(match lang {
        "rust" => tree_sitter_rust::LANGUAGE.into(),
        "ts" => tree_sitter_typescript::LANGUAGE_TYPESCRIPT.into(),
        "js" => tree_sitter_javascript::LANGUAGE.into(),
        "python" => tree_sitter_python::LANGUAGE.into(),
        "go" => tree_sitter_go::LANGUAGE.into(),
        _ => return None,
    })
}

fn query_for(lang: &str) -> Option<&'static str> {
    Some(match lang {
        "rust" => RUST_QUERY,
        "ts" => TS_QUERY,
        "js" => JS_QUERY,
        "python" => PY_QUERY,
        "go" => GO_QUERY,
        _ => return None,
    })
}

/// Parse + extract every file. Unparsable files drop out of the graph.
fn extract_all(files: &[PathBuf]) -> Vec<FileEntry> {
    let root = std::env::current_dir().unwrap_or_default();
    let mut parser = tree_sitter::Parser::new();
    let mut entries = Vec::with_capacity(files.len());
    for path in files {
        let Some(lang) = language_for(path) else { continue };
        let Some(ts_lang) = ts_language(lang) else { continue };
        if parser.set_language(&ts_lang).is_err() {
            continue;
        }
        let Ok(src) = std::fs::read_to_string(path) else { continue };
        let Some(tree) = parser.parse(&src, None) else { continue };
        let rel = path
            .strip_prefix(&root)
            .ok()
            .map(|p| p.to_string_lossy().into_owned())
            .unwrap_or_else(|| path.to_string_lossy().into_owned());
        entries.push(extract_one(&rel, lang, &src, &tree));
    }
    entries
}

fn extract_one(rel: &str, lang: &str, src: &str, tree: &tree_sitter::Tree) -> FileEntry {
    let mut entry = FileEntry { rel: rel.to_string(), ..Default::default() };
    let Some(query_src) = query_for(lang) else { return entry };
    let Some(ts_lang) = ts_language(lang) else { return entry };
    let Ok(query) = tree_sitter::Query::new(&ts_lang, query_src) else { return entry };
    let src_bytes = src.as_bytes();
    let name_idx = query.capture_index_for_name("name");
    let item_idx = query.capture_index_for_name("item");
    let Some(item_idx) = item_idx else { return entry };

    let root = tree.root_node();
    let mut cursor = tree_sitter::QueryCursor::new();
    let mut seen_spans: Vec<(usize, usize)> = Vec::new();

    // tree-sitter 0.25's QueryMatches is a StreamingIterator, not a
    // std::iter::Iterator — drive it with StreamingIterator::next.
    let mut matches = cursor.matches(&query, root, src_bytes);
    while let Some(m) = matches.next() {
        let Some(item) = m.nodes_for_capture_index(item_idx).next() else { continue };
        // Dedup: one signature per definition span (a node can satisfy two
        // patterns, e.g. `impl Trait for Type` firing both impl patterns).
        let span = (item.start_byte(), item.end_byte());
        if seen_spans.contains(&span) {
            continue;
        }
        seen_spans.push(span);

        let signature = signature_of(src, item);
        let name = name_idx
            .and_then(|i| m.nodes_for_capture_index(i).next())
            .and_then(|n| n.utf8_text(src_bytes).ok())
            .unwrap_or_default()
            .to_string();
        if !name.is_empty() {
            entry.defines.push(name);
        }
        entry.symbols.push(Symbol { signature, line: item.start_position().row + 1 });
    }

    entry.identifiers = collect_identifiers(root, src_bytes);
    entry
}

/// First-line signature of a definition node, trimmed and bounded.
fn signature_of(src: &str, node: tree_sitter::Node) -> String {
    let text = node.utf8_text(src.as_bytes()).unwrap_or_default();
    let first = text.lines().next().unwrap_or_default().trim_start();
    let mut sig: String = first
        .trim_end_matches(|c: char| c == '{' || c.is_whitespace())
        .chars()
        .take(MAX_SIG_CHARS)
        .collect();
    if first.chars().count() > MAX_SIG_CHARS {
        sig.push('…');
    }
    sig
}

/// Every identifier-ish node in the tree, in source order.
fn collect_identifiers(root: tree_sitter::Node, src: &[u8]) -> Vec<String> {
    let mut out = Vec::new();
    walk_identifiers(root, src, &mut out, 0);
    out
}

fn walk_identifiers(node: tree_sitter::Node, src: &[u8], out: &mut Vec<String>, depth: usize) {
    if depth > 200 || out.len() >= MAX_IDENTIFIERS {
        return;
    }
    if matches!(
        node.kind(),
        "identifier" | "type_identifier" | "property_identifier" | "field_identifier"
    ) {
        if let Ok(text) = node.utf8_text(src) {
            out.push(text.to_string());
        }
    }
    for i in 0..node.child_count() {
        if let Some(c) = node.child(i) {
            walk_identifiers(c, src, out, depth + 1);
        }
    }
}

// ---------------------------------------------------------------------------
// Ranking
// ---------------------------------------------------------------------------

/// PageRank over the file→file reference graph. An edge A→B exists when file
/// A references a symbol defined in file B (weight = reference count).
/// Renormalization absorbs dangling mass; deterministic.
fn page_rank(files: &[FileEntry]) -> Vec<f64> {
    let n = files.len();
    if n == 0 {
        return Vec::new();
    }

    // def-name → defining files.
    let mut index: HashMap<&str, Vec<usize>> = HashMap::new();
    for (i, f) in files.iter().enumerate() {
        for d in &f.defines {
            index.entry(d.as_str()).or_default().push(i);
        }
    }

    // Row-normalized edge weights.
    let mut w = vec![vec![0.0f64; n]; n];
    for (i, f) in files.iter().enumerate() {
        for ident in &f.identifiers {
            if let Some(targets) = index.get(ident.as_str()) {
                for &j in targets {
                    if j != i {
                        w[i][j] += 1.0;
                    }
                }
            }
        }
        let sum: f64 = w[i].iter().sum();
        if sum > 0.0 {
            for v in w[i].iter_mut() {
                *v /= sum;
            }
        }
    }

    let damping = 0.85;
    let mut rank = vec![1.0 / n as f64; n];
    for _ in 0..30 {
        let mut next = vec![(1.0 - damping) / n as f64; n];
        for (i, row) in w.iter().enumerate() {
            for (j, wt) in row.iter().enumerate() {
                if *wt > 0.0 {
                    next[j] += damping * rank[i] * wt;
                }
            }
        }
        let sum: f64 = next.iter().sum();
        if sum > 0.0 {
            for v in next.iter_mut() {
                *v /= sum;
            }
        }
        rank = next;
    }
    rank
}

// ---------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------

/// Render the ranked map within `budget_chars`: files in PageRank order
/// (ties → path order), signatures in line order, `… N more` collapse.
fn render(files: &[FileEntry], ranks: &[f64], budget_chars: usize) -> RepoMap {
    let mut order: Vec<usize> = (0..files.len()).collect();
    order.sort_by(|a, b| {
        ranks[*b]
            .partial_cmp(&ranks[*a])
            .unwrap_or(std::cmp::Ordering::Equal)
            .then_with(|| files[*a].rel.cmp(&files[*b].rel))
    });

    let mut text = String::new();
    let mut used_files = 0usize;
    let mut truncated = false;

    for &i in &order {
        let f = &files[i];
        if f.symbols.is_empty() {
            continue;
        }
        let mut block = format!("{}\n", f.rel);
        for (k, sym) in f.symbols.iter().enumerate() {
            if k >= MAX_SIGS_PER_FILE {
                block.push_str(&format!("  … {} more\n", f.symbols.len() - k));
                break;
            }
            block.push_str(&format!("  {:>4}│ {}\n", sym.line, sym.signature));
        }
        if text.chars().count() + block.chars().count() > budget_chars {
            truncated = true;
            break;
        }
        text.push_str(&block);
        used_files += 1;
    }

    RepoMap { text, files: used_files, symbols: 0, truncated }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    fn tmp(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("xencode-map-{}-{}", tag, std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    /// a.rs defines the shared symbol; b.rs and c.rs reference it → a.rs
    /// must rank first and carry its symbols into the map.
    fn sample_rust(dir: &Path) {
        fs::create_dir_all(dir.join("src")).unwrap();
        fs::write(dir.join("src/lib.rs"), "pub mod core_types;\npub mod users;\n").unwrap();
        fs::write(
            dir.join("src/core_types.rs"),
            "pub struct PaymentCore {\n    pub amount: u64,\n}\n\npub fn settle_payment(core: &PaymentCore) -> u64 {\n    core.amount\n}\n",
        )
        .unwrap();
        fs::write(
            dir.join("src/users.rs"),
            "use crate::core_types::{PaymentCore, settle_payment};\n\npub fn charge_user(user: &str, core: &PaymentCore) -> u64 {\n    settle_payment(core)\n}\n",
        )
        .unwrap();
    }

    #[test]
    fn extracts_rust_symbols() {
        let dir = tmp("rust");
        sample_rust(&dir);
        let map = build(&dir, 8_000);
        assert!(map.text.contains("src/core_types.rs"), "got:\n{}", map.text);
        assert!(map.text.contains("pub struct PaymentCore"));
        assert!(map.text.contains("pub fn settle_payment"));
        assert!(map.text.contains("src/users.rs"));
        assert!(map.text.contains("pub fn charge_user"));
        assert!(map.files >= 2);
        assert!(map.symbols >= 4);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn ranks_referenced_files_first() {
        let dir = tmp("rank");
        sample_rust(&dir);
        let map = build(&dir, 8_000);
        let core = map.text.find("src/core_types.rs").unwrap();
        let users = map.text.find("src/users.rs").unwrap();
        // core_types defines symbols both other files use; users only
        // references — the hub must rank at or above the spoke.
        assert!(core < users, "core_types.rs should rank at or above users.rs");
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn extracts_typescript_symbols() {
        let dir = tmp("ts");
        fs::write(
            dir.join("app.ts"),
            "export interface UserPrefs { theme: string }\n\nexport class PrefsStore {\n  load(): UserPrefs { return { theme: \"dark\" }; }\n}\n\nexport const makePrefs = (): UserPrefs => ({ theme: \"dark\" });\n\nfunction helper(x: number): number { return x; }\n",
        )
        .unwrap();
        let map = build(&dir, 8_000);
        assert!(map.text.contains("app.ts"), "got:\n{}", map.text);
        assert!(map.text.contains("interface UserPrefs"));
        assert!(map.text.contains("class PrefsStore"));
        assert!(map.text.contains("load()"));
        assert!(map.text.contains("makePrefs"));
        assert!(map.text.contains("function helper"));
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn extracts_python_and_go_symbols() {
        let dir = tmp("py-go");
        fs::write(
            dir.join("svc.py"),
            "class CostModel:\n    def rate(self) -> float:\n        return 0.5\n\ndef compute(model: CostModel) -> float:\n    return model.rate()\n",
        )
        .unwrap();
        fs::write(
            dir.join("main.go"),
            "package main\n\nimport \"fmt\"\n\ntype Server struct{ port int }\n\nfunc (s *Server) Start() error {\n    return nil\n}\n\nfunc main() {\n    fmt.Println(\"hi\")\n}\n",
        )
        .unwrap();
        let map = build(&dir, 8_000);
        assert!(map.text.contains("svc.py"), "got:\n{}", map.text);
        assert!(map.text.contains("class CostModel"));
        assert!(map.text.contains("def compute"));
        assert!(map.text.contains("main.go"));
        assert!(map.text.contains("type Server"));
        assert!(map.text.contains("func main"));
        assert!(map.text.contains("Start"));
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn budget_truncates_the_map() {
        let dir = tmp("budget");
        sample_rust(&dir);
        let tiny = build(&dir, 20);
        assert!(tiny.truncated, "20 chars must truncate");

        let full = build(&dir, 100_000);
        assert!(!full.truncated);
        assert!(full.files >= 2);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn ignored_dirs_and_unsupported_files_are_skipped() {
        let dir = tmp("skip");
        fs::create_dir_all(dir.join("target")).unwrap();
        fs::write(dir.join("target/junk.rs"), "fn should_not_appear() {}\n").unwrap();
        fs::write(dir.join("data.json"), "{}").unwrap();
        fs::write(dir.join("real.rs"), "fn visible() {}\n").unwrap();
        let map = build(&dir, 4_000);
        assert!(map.text.contains("fn visible"));
        assert!(!map.text.contains("should_not_appear"));
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn empty_repo_renders_empty_map() {
        let dir = tmp("empty");
        let map = build(&dir, 4_000);
        assert!(map.text.is_empty());
        assert_eq!(map.files, 0);
        let _ = fs::remove_dir_all(&dir);
    }
}
