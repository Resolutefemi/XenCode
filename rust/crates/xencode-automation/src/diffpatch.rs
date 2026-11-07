//! Minimal unified-diff applier (subset of the Kotlin DiffPatch twin):
//! accepts standard `@@ -a,b +c,d @@` hunks with ` `/`-`/`+` lines.

pub fn apply_to_string(content: &str, diff: &str) -> Result<(String, usize, usize), String> {
    let original: Vec<&str> = content.lines().collect();
    let mut out: Vec<String> = original.iter().map(|s| s.to_string()).collect();
    let lines: Vec<&str> = diff.lines().collect();

    let mut i = 0usize;
    let mut hunks = 0usize;
    let mut lines_changed = 0usize;

    while i < lines.len() {
        let line = lines[i];
        if !line.starts_with("@@") {
            i += 1;
            continue;
        }
        // Parse @@ -old,+new @@ — we need the old start (1-based).
        let old_start: usize = line
            .split_whitespace()
            .find_map(|tok| tok.strip_prefix('-').map(|s| s.to_string()))
            .and_then(|s| s.split(',').next().and_then(|n| n.parse::<usize>().ok()))
            .ok_or_else(|| format!("malformed hunk header: {line}"))?;

        i += 1;
        let body_start = i;
        while i < lines.len() && !lines[i].starts_with("@@") {
            i += 1;
        }
        let body = &lines[body_start..i];

        let pos = old_start.saturating_sub(1);
        if pos > out.len() {
            return Err(format!("hunk start {old_start} beyond end of file ({} lines)", out.len()));
        }

        let mut next: Vec<String> = out[..pos].to_vec();
        let mut src_idx = pos;
        for l in body {
            if let Some(rest) = l.strip_prefix('-') {
                // Verify the removal matches the file (context check).
                match out.get(src_idx) {
                    Some(existing) if existing.as_str() == rest => {}
                    Some(existing) => {
                        return Err(format!(
                            "hunk context mismatch at line {}: diff removes `{rest}` but file has `{existing}`",
                            src_idx + 1
                        ));
                    }
                    None => {
                        return Err(format!("hunk removes past end of file at line {}", src_idx + 1));
                    }
                }
                src_idx += 1;
            } else if let Some(rest) = l.strip_prefix('+') {
                next.push(rest.to_string());
                lines_changed += 1;
            } else if l.starts_with('\\') {
                // "\ No newline at end of file" — no-op
            } else {
                let rest = l.strip_prefix(' ').unwrap_or(l);
                match out.get(src_idx) {
                    Some(existing) if existing.as_str() == rest => next.push(existing.clone()),
                    Some(existing) => {
                        if !rest.is_empty() || !existing.is_empty() {
                            return Err(format!(
                                "hunk context mismatch at line {}: diff expects `{rest}` but file has `{existing}`",
                                src_idx + 1
                            ));
                        }
                        next.push(existing.clone());
                    }
                    None if rest.is_empty() => {}
                    None => {
                        return Err(format!("hunk context past end of file at line {}", src_idx + 1));
                    }
                }
                src_idx += 1;
            }
        }
        next.extend_from_slice(&out[src_idx.min(out.len())..]);
        out = next;
        hunks += 1;
    }

    let mut result = out.join("\n");
    if content.ends_with('\n') {
        result.push('\n');
    }
    Ok((result, hunks, lines_changed))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn simple_replacement_hunk() {
        let content = "line1\nline2\nline2b\nline3\nline4\n";
        let diff = "@@ -2,2 +2,1 @@\n-line2\n-line2b\n+LINE2\n";
        let (out, hunks, lines) = apply_to_string(content, diff).unwrap();
        assert_eq!(hunks, 1);
        assert_eq!(lines, 1);
        assert_eq!(out, "line1\nLINE2\nline3\nline4\n");
    }

    #[test]
    fn insertion_and_deletion() {
        let content = "a\nb\nc\n";
        let ins = "@@ -2,0 +2,1 @@\n+X\n";
        let (out, _, _) = apply_to_string(content, ins).unwrap();
        assert_eq!(out, "a\nX\nb\nc\n");

        let del = "@@ -2,1 +2,0 @@\n-b\n";
        let (out2, _, _) = apply_to_string(content, del).unwrap();
        assert_eq!(out2, "a\nc\n");
    }

    #[test]
    fn multi_hunk_and_context() {
        let content = "one\ntwo\nthree\nfour\nfive\nsix\n";
        let diff = "@@ -1,3 +1,3 @@\n one\n-two\n+2\n three\n@@ -5,2 +5,2 @@\n five\n-six\n+6\n";
        let (out, hunks, _) = apply_to_string(content, diff).unwrap();
        assert_eq!(hunks, 2);
        assert_eq!(out, "one\n2\nthree\nfour\nfive\n6\n");
    }

    #[test]
    fn context_mismatch_rejected() {
        let content = "a\nb\nc\n";
        let diff = "@@ -2,1 +2,1 @@\n-ZZZ\n+B\n";
        assert!(apply_to_string(content, diff).is_err());
    }
}
