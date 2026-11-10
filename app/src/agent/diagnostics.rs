//! Diagnostics loop (Phase 5, harness port #11 — from OpenCode): compiler /
//! linter errors auto-fed back to the model. After the agent edits a source
//! file, the project's check command runs once; **new** errors (vs the
//! previous round) are pushed into the loop as a user turn so the model sees
//! and fixes them before finishing — the compiler closes the loop.
//!
//! `xen.toml`:
//!
//! ```toml
//! [diagnostics]
//! # Explicit toolchain; omit for auto-detection.
//! command    = ["cargo", "check", "--message-format=json"]
//! format     = "cargo"      # "cargo" (JSON lines) | "regex" (line_pattern)
//! timeout_ms = 120000
//! ```
//!
//! Auto-detection (no `command`): a `Cargo.toml` project gets
//! `cargo check --message-format=json`; a project with a local
//! `node_modules/.bin/tsc` gets `tsc --noEmit --pretty false`. Nothing
//! detectable → the engine is `None` and the machinery stays off.

use std::path::Path;
use std::sync::Mutex;

use anyhow::{Context, Result};
use serde_json::Value;
use xencode_script::config::DiagnosticsConfig;

/// One compiler/linter diagnostic (what the model sees).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Diag {
    pub file: String,
    pub line: u32,
    pub level: String,
    pub message: String,
}

impl Diag {
    fn key(&self) -> String {
        format!("{}:{}:{}:{}", self.file, self.line, self.level, self.message)
    }

    /// One rendered line for the feedback turn.
    pub fn render(&self) -> String {
        format!("{}:{}: {}: {}", self.file, self.line, self.level, self.message)
    }
}

/// How check output is parsed.
enum Parser {
    /// `cargo check --message-format=json` lines.
    Cargo,
    /// Every output line matched against a regex with `file`/`line`/`level`/
    /// `message` named groups.
    Regex(regex::Regex),
}

/// The configured check command + parser, resolved against a project root.
pub struct DiagnosticsEngine {
    command: Vec<String>,
    parser: Parser,
    timeout_ms: u64,
    /// Display name of the toolchain (`cargo check`, `tsc`, the command head).
    source: String,
    /// Extensions that trigger a check after write_file / edit_file.
    trigger_exts: Vec<String>,
    /// Diagnostics from the previous round — only *new* ones feed back.
    previous: Mutex<Vec<String>>,
}

const DEFAULT_TIMEOUT_MS: u64 = 120_000;

impl DiagnosticsEngine {
    /// Resolve the engine for a project: explicit `[diagnostics]` config
    /// first, then auto-detection. `None` = nothing configured or detectable.
    pub fn resolve(cwd: &Path, cfg: &DiagnosticsConfig) -> Option<Self> {
        if !cfg.command.is_empty() {
            let fmt = cfg.format.as_deref().unwrap_or("cargo");
            let parser = if fmt == "regex" {
                let pattern = cfg.line_pattern.as_deref()?;
                Some(Parser::Regex(regex::Regex::new(pattern).ok()?))
            } else {
                Some(Parser::Cargo)
            }?;
            return Some(Self::build(
                cfg.command.clone(),
                parser,
                cfg.timeout_ms,
                cfg.extensions.as_ref(),
            ));
        }
        // Auto-detect: Cargo.toml → cargo check; local tsc → tsc --noEmit.
        if cwd.join("Cargo.toml").is_file() {
            return Some(Self::build(
                vec![
                    "cargo".into(),
                    "check".into(),
                    "--message-format=json".into(),
                ],
                Parser::Cargo,
                cfg.timeout_ms,
                cfg.extensions.as_ref(),
            ));
        }
        let tsc = cwd.join("node_modules").join(".bin").join("tsc");
        if tsc.is_file() {
            let ts_exts = vec!["ts".into(), "tsx".into(), "js".into(), "jsx".into()];
            return Some(Self::build(
                [tsc.to_string_lossy().into_owned(), "--noEmit".into(), "--pretty".into(), "false".into()].into(),
                Parser::Regex(
                    regex::Regex::new(r"^(?P<file>[^(]+)\((?P<line>\d+),\d+\): (?P<level>error|warning) TS\d+: (?P<message>.*)$")
                        .expect("static regex"),
                ),
                cfg.timeout_ms,
                Some(&cfg.extensions.clone().unwrap_or(ts_exts)),
            ));
        }
        None
    }

    fn build(
        command: Vec<String>,
        parser: Parser,
        timeout_ms: Option<u64>,
        extensions: Option<&Vec<String>>,
    ) -> Self {
        let source = command.first().cloned().unwrap_or_default();
        let trigger_exts = extensions.cloned().unwrap_or_else(|| match source.as_str() {
            "cargo" => vec!["rs".into()],
            "tsc" => vec!["ts".into(), "tsx".into(), "js".into(), "jsx".into()],
            _ => vec!["rs".into(), "ts".into(), "tsx".into(), "js".into(), "py".into(), "go".into()],
        });
        Self {
            command,
            parser,
            timeout_ms: timeout_ms.unwrap_or(DEFAULT_TIMEOUT_MS),
            source,
            trigger_exts,
            previous: Mutex::new(Vec::new()),
        }
    }

    /// Toolchain name for events (`cargo check`, `tsc`, …).
    pub fn source(&self) -> &str {
        &self.source
    }

    /// True when an edit to `path` should trigger a check.
    pub fn triggers_on(&self, path: &str) -> bool {
        Path::new(path)
            .extension()
            .and_then(|e| e.to_str())
            .map(|e| self.trigger_exts.iter().any(|t| t == e))
            .unwrap_or(false)
    }

    /// Run the check command and return every diagnostic it reports.
    pub async fn check(&self, cwd: &Path) -> Result<Vec<Diag>> {
        use std::process::Stdio;
        let (program, rest) = self.command.split_first().context("empty diagnostics command")?;

        let out = tokio::time::timeout(
            std::time::Duration::from_millis(self.timeout_ms),
            tokio::process::Command::new(program)
                .args(rest)
                .current_dir(cwd)
                .stdin(Stdio::null())
                .stdout(Stdio::piped())
                .stderr(Stdio::piped())
                .output(),
        )
        .await
        .map_err(|_| anyhow::anyhow!("diagnostics: {} timed out after {}ms", self.source, self.timeout_ms))??;

        // Cargo prints JSON on stdout; tsc-style tools on either stream.
        let stdout = String::from_utf8_lossy(&out.stdout).into_owned();
        let stderr = String::from_utf8_lossy(&out.stderr).into_owned();
        let mut diags = match &self.parser {
            Parser::Cargo => parse_cargo_json(&stdout),
            Parser::Regex(re) => parse_lines_regex(re, &stdout).into_iter().chain(parse_lines_regex(re, &stderr)).collect(),
        };
        // A check tool that exits non-zero without parseable output is a
        // broken toolchain (typo, missing binary) — surface it once instead
        // of silently reporting "no problems". Tools like cargo exit
        // non-zero *with* diagnostics, so they never hit this branch.
        if diags.is_empty() && !out.status.success() {
            anyhow::bail!(
                "{} exited with code {} and produced no parseable diagnostics",
                self.source,
                out.status.code().unwrap_or(-1)
            );
        }
        diags.sort_by(|a, b| (&a.file, a.line).cmp(&(&b.file, b.line)));
        diags.dedup();
        Ok(diags)
    }

    /// The post-edit loop step: run the check, diff against the previous
    /// round, and render the *new* problems as a feedback turn. `None` =
    /// nothing new. Remembers the current round either way.
    pub async fn new_problems(&self, cwd: &Path) -> Option<(usize, String)> {
        let diags = match self.check(cwd).await {
            Ok(d) => d,
            Err(e) => {
                // A failing check tool must not wedge the loop; surface it
                // once per run (empty snapshot prevents repeats).
                let mut prev = self.previous.lock().expect("diagnostics lock");
                if prev.iter().any(|k| k == "::error::") {
                    return None;
                }
                prev.push("::error::".into());
                return Some((0, format!("[Diagnostics] `{}` failed to run: {e:#}", self.source)));
            }
        };
        let keys: Vec<String> = diags.iter().map(Diag::key).collect();
        let new: Vec<Diag> = {
            let prev = self.previous.lock().expect("diagnostics lock");
            diags.iter().filter(|d| !prev.contains(&d.key())).cloned().collect()
        };
        *self.previous.lock().expect("diagnostics lock") = keys;

        if new.is_empty() {
            return None;
        }
        // Errors first (they block the build); cap the feedback turn.
        let mut ordered = new;
        ordered.sort_by_key(|d| d.level != "error");
        let shown: Vec<String> = ordered.iter().take(20).map(Diag::render).collect();
        let more = ordered.len() - shown.len();
        let mut body = shown.join("\n");
        if more > 0 {
            body.push_str(&format!("\n… and {more} more"));
        }
        let label = if ordered.len() == 1 { "problem" } else { "problems" };
        Some((
            ordered.len(),
            format!(
                "[Diagnostics] `{}` reports {} new {} after your edit:\n{}\nFix these before finishing.",
                self.source,
                ordered.len(),
                label,
                body
            ),
        ))
    }
}

/// Parse `cargo check --message-format=json` stdout into diagnostics.
/// Only compiler messages with a primary span become entries; warnings and
/// errors both count (the model fixes warnings too when told).
pub fn parse_cargo_json(stdout: &str) -> Vec<Diag> {
    let mut out = Vec::new();
    for line in stdout.lines() {
        let Ok(v) = serde_json::from_str::<Value>(line.trim()) else { continue };
        if v.get("reason").and_then(Value::as_str) != Some("compiler-message") {
            continue;
        }
        let msg = &v["message"];
        let level = msg.get("level").and_then(Value::as_str).unwrap_or_default();
        if level != "error" && level != "warning" {
            continue;
        }
        let text = msg.get("message").and_then(Value::as_str).unwrap_or_default();
        // Primary span = the file/line the compiler points at.
        let span = msg
            .get("spans")
            .and_then(Value::as_array)
            .and_then(|spans| spans.iter().find(|s| s.get("is_primary").and_then(Value::as_bool) == Some(true)))
            .or_else(|| {
                msg.get("spans").and_then(Value::as_array).and_then(|s| s.first())
            });
        let (file, line) = match span {
            Some(s) => (
                s.get("file_name").and_then(Value::as_str).unwrap_or_default(),
                s.get("line_start").and_then(Value::as_u64).unwrap_or(0) as u32,
            ),
            None => ("", 0),
        };
        if file.is_empty() {
            continue;
        }
        let mut message = text.to_string();
        // Attach the first error code (error[E0308]) when present.
        if let Some(code) = msg
            .get("code")
            .and_then(|c| c.get("code"))
            .and_then(Value::as_str)
        {
            message = format!("[{code}] {message}");
        }
        out.push(Diag { file: file.into(), line, level: level.into(), message });
    }
    out
}

/// Parse generic compiler-style lines with the configured regex.
pub fn parse_lines_regex(re: &regex::Regex, text: &str) -> Vec<Diag> {
    let mut out = Vec::new();
    for line in text.lines() {
        let Some(caps) = re.captures(line) else { continue };
        let g = |name: &str| caps.name(name).map(|m| m.as_str().to_string()).unwrap_or_default();
        out.push(Diag {
            file: g("file"),
            line: g("line").parse().unwrap_or(0),
            level: {
                let l = g("level");
                if l.is_empty() { "error".into() } else { l }
            },
            message: g("message"),
        });
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::path::PathBuf;

    fn tmp(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("xencode-diag-{}-{}", tag, std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn cargo_json_parses_errors_and_warnings() {
        let json = concat!(
            r#"{"reason":"compiler-artifact","target":{"name":"x"}}"#,
            "\n",
            r#"{"reason":"compiler-message","message":{"level":"error","message":"mismatched types","code":{"code":"E0308"},"spans":[{"is_primary":true,"file_name":"src/lib.rs","line_start":42,"line_end":42}]}}"#,
            "\n",
            r#"{"reason":"compiler-message","message":{"level":"warning","message":"unused variable","spans":[{"is_primary":true,"file_name":"src/main.rs","line_start":7,"line_end":7}]}}"#,
            "\n",
            r#"{"reason":"build-finished","success":false}"#,
            "\n",
        );
        let diags = parse_cargo_json(json);
        assert_eq!(diags.len(), 2);
        assert_eq!(diags[0].file, "src/lib.rs");
        assert_eq!(diags[0].line, 42);
        assert_eq!(diags[0].level, "error");
        assert!(diags[0].message.contains("[E0308]"));
        assert!(diags[0].message.contains("mismatched types"));
        assert_eq!(diags[1].level, "warning");
        assert_eq!(diags[1].line, 7);
    }

    #[test]
    fn regex_parser_handles_compiler_lines() {
        let re = regex::Regex::new(
            r"^(?P<file>[^:]+):(?P<line>\d+): (?P<level>\w+): (?P<message>.*)$",
        )
        .unwrap();
        let diags = parse_lines_regex(&re, "src/a.ts:12: error: expected ';'\nnote: not a match\nsrc/b.py:3: warning: shadowed\n");
        assert_eq!(diags.len(), 2);
        assert_eq!(diags[0].file, "src/a.ts");
        assert_eq!(diags[0].line, 12);
        assert_eq!(diags[0].message, "expected ';'");
        assert_eq!(diags[1].level, "warning");
    }

    #[test]
    fn resolve_auto_detects_cargo_projects() {
        let dir = tmp("auto");
        fs::write(dir.join("Cargo.toml"), "[package]\nname = \"x\"\n").unwrap();
        let engine = DiagnosticsEngine::resolve(&dir, &DiagnosticsConfig::default()).unwrap();
        assert_eq!(engine.source(), "cargo");
        assert!(engine.triggers_on("src/lib.rs"));
        assert!(!engine.triggers_on("README.md"));
        assert!(engine.triggers_on("src/main.rs"));
    }

    #[test]
    fn resolve_auto_detects_local_tsc() {
        let dir = tmp("tsc");
        fs::create_dir_all(dir.join("node_modules/.bin")).unwrap();
        fs::write(dir.join("node_modules/.bin/tsc"), "#!/bin/sh\n").unwrap();
        let engine = DiagnosticsEngine::resolve(&dir, &DiagnosticsConfig::default()).unwrap();
        assert_eq!(engine.source(), dir.join("node_modules/.bin/tsc").to_string_lossy());
        assert!(engine.triggers_on("src/app.ts"));
        assert!(!engine.triggers_on("src/app.py"));
    }

    #[test]
    fn resolve_explicit_regex_command() {
        let dir = tmp("explicit");
        let cfg = r#"
command = ["sh", "-c", "echo 'src/a.rs:3: error: boom'"]
format = "regex"
line_pattern = '^(?P<file>[^:]+):(?P<line>\d+): (?P<level>\w+): (?P<message>.*)$'
"#;
        let parsed: DiagnosticsConfig = toml::from_str(cfg).unwrap();
        let engine = DiagnosticsEngine::resolve(&dir, &parsed).unwrap();
        assert!(engine.triggers_on("src/anything.rs"));
    }

    #[tokio::test]
    async fn new_problems_feed_back_once() {
        let dir = tmp("loop");
        // A tiny "checker" that always reports one error.
        let cfg: DiagnosticsConfig = toml::from_str(
            r#"
command = ["sh", "-c", "printf 'src/lib.rs:9: error: synthetic failure\n'"]
format = "regex"
line_pattern = '^(?P<file>[^:]+):(?P<line>\d+): (?P<level>\w+): (?P<message>.*)$'
timeout_ms = 5000
"#,
        )
        .unwrap();
        let engine = DiagnosticsEngine::resolve(&dir, &cfg).unwrap();

        let (count, text) = engine.new_problems(&dir).await.expect("first round has a new problem");
        assert_eq!(count, 1);
        assert!(text.contains("[Diagnostics]"));
        assert!(text.contains("src/lib.rs:9: error: synthetic failure"));
        assert!(text.contains("Fix these"));

        // Second round: same diagnostics are NOT new anymore.
        assert!(engine.new_problems(&dir).await.is_none(), "diffed against the previous round");
        let _ = fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn failing_check_tool_reports_once() {
        let dir = tmp("fail");
        let cfg: DiagnosticsConfig = toml::from_str(
            r#"
command = ["false"]
format = "regex"
line_pattern = '^(?P<file>[^:]+):(?P<line>\d+): (?P<level>\w+): (?P<message>.*)$'
timeout_ms = 5000
"#,
        )
        .unwrap();
        let engine = DiagnosticsEngine::resolve(&dir, &cfg).unwrap();
        let first = engine.new_problems(&dir).await;
        assert!(first.is_some(), "tool failure surfaces once");
        assert!(engine.new_problems(&dir).await.is_none(), "not repeated");
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn timeouts_surface_as_errors() {
        let dir = tmp("timeout");
        let cfg: DiagnosticsConfig = toml::from_str(
            r#"
command = ["sleep", "5"]
format = "regex"
line_pattern = 'x'
timeout_ms = 200
"#,
        )
        .unwrap();
        let engine = DiagnosticsEngine::resolve(&dir, &cfg).unwrap();
        let rt = tokio::runtime::Runtime::new().unwrap();
        let res = rt.block_on(engine.check(&dir));
        assert!(res.is_err());
        assert!(res.unwrap_err().to_string().contains("timed out"));
        let _ = fs::remove_dir_all(&dir);
    }
}
