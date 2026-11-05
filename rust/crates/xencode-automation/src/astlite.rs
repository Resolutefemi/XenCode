//! Lightweight, regex-based symbol tools (twin of the Kotlin AstLite):
//! language-agnostic line structure without a real parser. Good enough for
//! surgical edits; never pretends to be a compiler.

/// List symbols as "kind name @line N" strings.
pub fn symbols(source: &str, path: &str) -> Vec<String> {
    let lang = lang_of(path);
    let mut out = vec![];
    for (i, line) in source.lines().enumerate() {
        let trimmed = line.trim_start();
        for (kind, re) in decl_regexes(lang) {
            if let Some(caps) = re.captures(trimmed) {
                if let Some(name) = caps.get(1) {
                    out.push(format!("{} {} @line {}", kind, name.as_str(), i + 1));
                }
            }
        }
    }
    out
}

/// Find lines matching a regex: "line N: <snippet>".
pub fn find_pattern(source: &str, pattern: &str) -> Vec<String> {
    let re = match regex::RegexBuilder::new(pattern)
        .size_limit(5_000_000)
        .build()
    {
        Ok(r) => r,
        Err(e) => return vec![format!("ERROR: bad regex: {e}")],
    };
    let mut out = vec![];
    for (i, line) in source.lines().enumerate() {
        if re.is_match(line) {
            out.push(format!("line {}: {}", i + 1, line.trim()));
            if out.len() >= 200 {
                out.push("… (truncated at 200)".into());
                break;
            }
        }
    }
    out
}

/// Replace the full declaration block of `symbol` with `new_code`.
/// Brace-matching for brace languages, indent-matching for Python.
/// Returns None when the symbol is not found.
pub fn replace_symbol(source: &str, path: &str, symbol: &str, new_code: &str) -> Option<String> {
    let lang = lang_of(path);

    // Find the first line declaring this symbol.
    let lines: Vec<String> = source.lines().map(|s| s.to_string()).collect();
    let mut decl_line: Option<usize> = None;
    for (i, line) in lines.iter().enumerate() {
        let trimmed = line.trim_start();
        for (_, re) in decl_regexes(lang) {
            if let Some(caps) = re.captures(trimmed) {
                if caps.get(1).map(|m| m.as_str()) == Some(symbol) {
                    decl_line = Some(i);
                    break;
                }
            }
        }
        if decl_line.is_some() {
            break;
        }
    }
    let start = decl_line?;

    let end = if lang == Lang::Python {
        let indent = indent_of(&lines[start]);
        let mut end = start;
        for (i, line) in lines.iter().enumerate().skip(start + 1) {
            if line.trim().is_empty() {
                continue;
            }
            if indent_of(line) <= indent {
                break;
            }
            end = i;
        }
        end
    } else {
        // Brace matching from the declaration line (handles one-line decls).
        let mut depth = 0i64;
        let mut seen_open = false;
        let mut end = start;
        'outer: for (i, line) in lines.iter().enumerate().skip(start) {
            let mut in_str = false;
            let mut esc = false;
            for c in line.chars() {
                if in_str {
                    if esc {
                        esc = false;
                    } else if c == '\\' {
                        esc = true;
                    } else if c == '"' {
                        in_str = false;
                    }
                    continue;
                }
                match c {
                    '"' => in_str = true,
                    '{' => {
                        depth += 1;
                        seen_open = true;
                    }
                    '}' => {
                        depth -= 1;
                        if seen_open && depth == 0 {
                            end = i;
                            break 'outer;
                        }
                    }
                    _ => {}
                }
            }
            // One-line declaration without braces (e.g. `fn x();`) ends here.
            if !seen_open && (line.trim_end().ends_with(';') || line.trim_end().ends_with(')')) {
                end = i;
                break;
            }
            end = i;
        }
        end
    };

    let mut out: Vec<String> = Vec::with_capacity(lines.len());
    out.extend_from_slice(&lines[..start]);
    out.push(new_code.to_string());
    out.extend_from_slice(&lines[end + 1..]);
    Some(out.join("\n") + if source.ends_with('\n') { "\n" } else { "" })
}

#[derive(Clone, Copy, PartialEq)]
enum Lang {
    Rust,
    Python,
    CStyle, // js/ts/mjs/java/kt/go/c/cpp
}

fn lang_of(path: &str) -> Lang {
    let ext = path.rsplit('.').next().unwrap_or("");
    match ext {
        "rs" => Lang::Rust,
        "py" => Lang::Python,
        _ => Lang::CStyle,
    }
}

fn indent_of(line: &str) -> usize {
    line.len() - line.trim_start().len()
}

fn decl_regexes(lang: Lang) -> Vec<(&'static str, regex::Regex)> {
    let mk = |kind: &'static str, pat: &str| {
        (
            kind,
            regex::Regex::new(pat).unwrap_or_else(|_| regex::Regex::new(r"$^").expect("static never-match regex")),
        )
    };
    match lang {
        Lang::Rust => vec![
            mk("fn", r"(?:pub\s+)?(?:async\s+)?fn\s+(\w+)"),
            mk("struct", r"(?:pub\s+)?struct\s+(\w+)"),
            mk("enum", r"(?:pub\s+)?enum\s+(\w+)"),
            mk("trait", r"(?:pub\s+)?trait\s+(\w+)"),
            mk("impl", r"impl(?:<[^>]*>)?\s+(\w+)"),
            mk("mod", r"(?:pub\s+)?mod\s+(\w+)"),
        ],
        Lang::Python => vec![
            mk("def", r"(?:async\s+)?def\s+(\w+)"),
            mk("class", r"class\s+(\w+)"),
        ],
        Lang::CStyle => vec![
            mk("function", r"(?:export\s+)?(?:default\s+)?(?:async\s+)?function\s*\*?\s*(\w+)"),
            mk("class", r"(?:export\s+)?(?:abstract\s+)?class\s+(\w+)"),
            mk("interface", r"(?:export\s+)?interface\s+(\w+)"),
            mk("const-fn", r"(?:export\s+)?const\s+(\w+)\s*=\s*(?:async\s*)?\("),
            mk("fun", r"(?:override\s+)?(?:suspend\s+)?fun\s+(?:[\w<>.]+\.)?(\w+)\s*\("),
            mk("func", r"func\s+(?:\([^)]*\)\s*)?(\w+)"),
        ],
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const RUST_SRC: &str = "use std::io;\n\nfn alpha() {\n    println!(\"{ \\\" nested }\");\n    other();\n}\n\nfn beta() {\n    alpha();\n}\n";

    #[test]
    fn symbols_and_find() {
        let syms = symbols(RUST_SRC, "lib.rs");
        assert!(syms.iter().any(|s| s.contains("fn alpha @line 3")), "{syms:?}");
        assert!(syms.iter().any(|s| s.contains("fn beta")));
        let found = find_pattern(RUST_SRC, "alpha");
        assert!(found.iter().any(|s| s.starts_with("line 3:")), "{found:?}");
    }

    #[test]
    fn replace_symbol_with_nested_braces_and_strings() {
        let next = replace_symbol(RUST_SRC, "lib.rs", "alpha", "fn alpha() { /* new */ }").unwrap();
        assert!(next.contains("/* new */"));
        assert!(next.contains("fn beta"));
        assert!(!next.contains("other();"));
        assert_eq!(next.matches("fn alpha").count(), 1);
    }

    #[test]
    fn replace_missing_symbol_is_none() {
        assert!(replace_symbol(RUST_SRC, "lib.rs", "gamma", "x").is_none());
    }

    #[test]
    fn python_indent_matching() {
        let src = "def a():\n    x = 1\n    y = 2\n\ndef b():\n    pass\n";
        let next = replace_symbol(src, "m.py", "a", "def a():\n    return 42").unwrap();
        assert!(next.contains("return 42"));
        assert!(next.contains("def b"));
        assert!(!next.contains("x = 1"));
    }
}
