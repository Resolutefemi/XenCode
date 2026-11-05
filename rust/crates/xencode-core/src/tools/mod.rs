//! Local file/command tools — the Claude-Code-grade tool set.
//!
//! Tool names, argument schemas and result conventions are twins of the
//! Kotlin line: results clipped at 12 000 chars, errors prefixed `ERROR:`.

pub mod compactor;
pub mod todo;

use crate::chat::ToolSpec;
use crate::error::tool_err;
use serde_json::Value;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::process::Command;

pub const MAX_RESULT_CHARS: usize = 12_000;
pub const MAX_LIST_DEPTH: usize = 3;
pub const DEFAULT_GREP_HEAD: usize = 100;

pub struct LocalTools {
    pub root: PathBuf,
    pub todo: todo::TodoStore,
}

impl LocalTools {
    pub fn new(root: impl Into<PathBuf>) -> Self {
        let root = root.into();
        let todo = todo::TodoStore::new(&root);
        Self { root, todo }
    }

    pub fn specs() -> Vec<ToolSpec> {
        vec![
            ToolSpec {
                name: "read_file".into(),
                description: "Read a text file from the workspace, optionally a line range.".into(),
                parameters: serde_json::json!({"type":"object","properties":{"path":{"type":"string","description":"Workspace-relative path"},"offset":{"type":"integer","description":"1-based line to start from"},"limit":{"type":"integer","description":"Maximum number of lines to return"}},"required":["path"]}),
            },
            ToolSpec {
                name: "write_file".into(),
                description: "Write a file with full content (creates parents).".into(),
                parameters: serde_json::json!({"type":"object","properties":{"path":{"type":"string","description":"Workspace-relative path"},"content":{"type":"string","description":"Full file content"}},"required":["path","content"]}),
            },
            ToolSpec {
                name: "edit_file".into(),
                description: "Replace an exact text snippet in a file.".into(),
                parameters: serde_json::json!({"type":"object","properties":{"path":{"type":"string","description":"Workspace-relative path"},"old_str":{"type":"string","description":"Exact text to replace"},"new_str":{"type":"string","description":"Replacement text"},"replace_all":{"type":"boolean","description":"Replace every occurrence instead of requiring uniqueness"}},"required":["path","old_str","new_str"]}),
            },
            ToolSpec {
                name: "list_files".into(),
                description: "List files under a directory.".into(),
                parameters: serde_json::json!({"type":"object","properties":{"path":{"type":"string","description":"Workspace-relative directory (default .)"},"recursive":{"type":"boolean","description":"Also list subdirectories up to depth 3"}},"required":[]}),
            },
            ToolSpec {
                name: "glob".into(),
                description: "Find files by glob pattern.".into(),
                parameters: serde_json::json!({"type":"object","properties":{"pattern":{"type":"string","description":"Glob pattern"},"path":{"type":"string","description":"Workspace-relative directory to search (default .)"}},"required":["pattern"]}),
            },
            ToolSpec {
                name: "grep".into(),
                description: "Search file contents with a regular expression.".into(),
                parameters: serde_json::json!({"type":"object","properties":{"pattern":{"type":"string","description":"Regular expression"},"path":{"type":"string","description":"Workspace-relative file or directory (default .)"},"glob":{"type":"string","description":"Only files matching this glob"},"output_mode":{"type":"string","description":"content | files_with_matches | count"},"ignore_case":{"type":"boolean","description":"Case-insensitive match"},"head_limit":{"type":"integer","description":"Max result lines (default 100)"}},"required":["pattern"]}),
            },
            ToolSpec {
                name: "delete_file".into(),
                description: "Delete a file (or empty directory).".into(),
                parameters: serde_json::json!({"type":"object","properties":{"path":{"type":"string","description":"Workspace-relative path"}},"required":["path"]}),
            },
            ToolSpec {
                name: "run_command".into(),
                description: "Run a shell command in the workspace and return exit code + output.".into(),
                parameters: serde_json::json!({"type":"object","properties":{"command":{"type":"string","description":"The command line to run"},"timeout_sec":{"type":"integer","description":"Timeout in seconds (1..600, default 30)"}},"required":["command"]}),
            },
            ToolSpec {
                name: "todo_write".into(),
                description: "Persist the current todo list (replaces it).".into(),
                parameters: serde_json::json!({"type":"object","properties":{"todos":{"type":"array","items":{"type":"object","properties":{"content":{"type":"string"},"status":{"type":"string","description":"pending | active | completed"},"priority":{"type":"string","description":"high | medium | low"}},"required":["content"]}}},"required":["todos"]}),
            },
            ToolSpec {
                name: "todo_read".into(),
                description: "Read the persisted todo list.".into(),
                parameters: serde_json::json!({"type":"object","properties":{},"required":[]}),
            },
        ]
    }

    /// Resolve a workspace-relative path, refusing escapes outside the root.
    pub fn resolve(&self, rel: &str) -> Result<PathBuf, String> {
        let rel = rel.trim();
        let rel = rel.strip_prefix("./").unwrap_or(rel);
        let p = Path::new(rel);
        let joined = if p.is_absolute() {
            // Absolute paths must already be inside the workspace.
            PathBuf::from(rel)
        } else {
            self.root.join(rel)
        };
        let norm = normalize(&joined);
        if !norm.starts_with(&self.root) {
            return Err("path escapes the workspace".into());
        }
        Ok(norm)
    }

    /// Execute a tool by name with a raw JSON args string. Never panics; on
    /// any error the returned string starts with `ERROR:`.
    pub fn execute(&mut self, name: &str, args_json: &str) -> String {
        let args: Value = serde_json::from_str(args_json).unwrap_or(Value::Null);
        let result = match name {
            "read_file" => self.read_file(&args),
            "write_file" => self.write_file(&args),
            "edit_file" => self.edit_file(&args),
            "list_files" => self.list_files(&args),
            "glob" => self.glob(&args),
            "grep" => self.grep(&args),
            "delete_file" => self.delete_file(&args),
            "run_command" => self.run_command(&args),
            "todo_write" => self.todo_write(&args),
            "todo_read" => Ok(self.todo.render()),
            other => Err(format!("unknown tool `{other}`")),
        };
        match result {
            Ok(s) => clip(&s),
            Err(e) => clip(&tool_err(e)),
        }
    }

    fn read_file(&self, args: &Value) -> Result<String, String> {
        let path = arg_str(args, "path").ok_or("read_file(path) needs a path")?;
        let f = self.resolve(&path)?;
        if !f.is_file() {
            return Err(format!("not a file: {path}"));
        }
        let text = std::fs::read_to_string(&f).map_err(|e| e.to_string())?;
        let lines: Vec<&str> = text.lines().collect();
        let total = lines.len();
        let start = args.get("offset").and_then(|v| v.as_u64()).unwrap_or(1).max(1) as usize;
        let limit = args.get("limit").and_then(|v| v.as_u64()).map(|v| v as usize);
        let end = match limit {
            Some(l) => (start + l - 1).min(total),
            None => total,
        };
        if start > total {
            return Ok(format!("(empty range: file has {total} lines)"));
        }
        let width = end.to_string().len();
        let body = lines[start - 1..end]
            .iter()
            .enumerate()
            .map(|(i, line)| format!("{:>width$} | {}", start + i, line, width = width))
            .collect::<Vec<_>>()
            .join("\n");
        Ok(format!("[{path} lines {start}..{end} of {total}]\n{body}"))
    }

    fn write_file(&self, args: &Value) -> Result<String, String> {
        let path = arg_str(args, "path").ok_or("write_file(path, content) needs a path")?;
        let content = args.get("content").and_then(|c| c.as_str()).unwrap_or("");
        let f = self.resolve(&path)?;
        if let Some(parent) = f.parent() {
            std::fs::create_dir_all(parent).map_err(|e| e.to_string())?;
        }
        std::fs::write(&f, content).map_err(|e| e.to_string())?;
        Ok(format!("wrote {} chars to {path}", content.chars().count()))
    }

    fn edit_file(&self, args: &Value) -> Result<String, String> {
        let path = arg_str(args, "path").ok_or("edit_file(path, old_str, new_str) needs a path")?;
        let old = arg_str(args, "old_str").ok_or("edit_file needs old_str")?;
        let new = arg_str(args, "new_str").ok_or("edit_file needs new_str")?;
        let replace_all = args.get("replace_all").and_then(|v| v.as_bool()).unwrap_or(false);
        let f = self.resolve(&path)?;
        let text = std::fs::read_to_string(&f).map_err(|e| e.to_string())?;
        if old.is_empty() {
            return Err("old_str is empty".into());
        }
        let count = text.matches(old.as_str()).count();
        if count == 0 {
            return Err(format!("old_str not found in {path}"));
        }
        if count > 1 && !replace_all {
            return Err(format!("old_str matches {count} times in {path}; pass replace_all:true or widen the snippet"));
        }
        let next = if replace_all { text.replace(old.as_str(), new.as_str()) } else { text.replacen(old.as_str(), new.as_str(), 1) };
        std::fs::write(&f, next).map_err(|e| e.to_string())?;
        Ok(format!("edited {path}: replaced {count} occurrence(s)"))
    }

    fn list_files(&self, args: &Value) -> Result<String, String> {
        let dir = arg_str(args, "path").unwrap_or_else(|| ".".into());
        let recursive = args.get("recursive").and_then(|v| v.as_bool()).unwrap_or(false);
        let base = self.resolve(&dir)?;
        if !base.is_dir() {
            return Err(format!("not a directory: {dir}"));
        }
        let mut out: Vec<String> = vec![];
        let walker = walkdir::WalkDir::new(&base)
            .max_depth(if recursive { MAX_LIST_DEPTH } else { 1 })
            .follow_links(false);
        for entry in walker {
            let entry = entry.map_err(|e| e.to_string())?;
            if entry.depth() == 0 {
                continue;
            }
            let name = entry.file_name().to_string_lossy();
            if name.starts_with('.') {
                continue;
            }
            let rel = entry
                .path()
                .strip_prefix(&self.root)
                .unwrap_or(entry.path())
                .to_string_lossy()
                .replace('\\', "/");
            out.push(if entry.file_type().is_dir() { format!("{rel}/") } else { rel });
            if out.len() >= 500 {
                out.push("… (truncated at 500 entries)".into());
                break;
            }
        }
        out.sort();
        Ok(if out.is_empty() { "(empty)".into() } else { out.join("\n") })
    }

    fn glob(&self, args: &Value) -> Result<String, String> {
        let pattern = arg_str(args, "pattern").ok_or("glob(pattern) needs a pattern")?;
        let dir = arg_str(args, "path").unwrap_or_else(|| ".".into());
        let base = self.resolve(&dir)?;
        let full = if pattern.starts_with('/') {
            pattern.clone()
        } else {
            format!("{}/{}", base.display(), pattern.trim_start_matches("./"))
        };
        let mut hits: Vec<String> = vec![];
        for entry in glob::glob(&full).map_err(|e| format!("bad glob pattern: {e}"))? {
            match entry {
                Ok(p) => {
                    let rel = p.strip_prefix(&self.root).unwrap_or(&p).to_string_lossy().replace('\\', "/");
                    hits.push(rel);
                    if hits.len() >= 200 {
                        break;
                    }
                }
                Err(_) => continue,
            }
        }
        hits.sort();
        Ok(if hits.is_empty() { "(no matches)".into() } else { hits.join("\n") })
    }

    fn grep(&self, args: &Value) -> Result<String, String> {
        let pattern = arg_str(args, "pattern").ok_or("grep(pattern) needs a pattern")?;
        let dir = arg_str(args, "path").unwrap_or_else(|| ".".into());
        let glob_filter = arg_str(args, "glob");
        let output_mode = arg_str(args, "output_mode").unwrap_or_else(|| "content".into());
        let ignore_case = args.get("ignore_case").and_then(|v| v.as_bool()).unwrap_or(false);
        let head = args.get("head_limit").and_then(|v| v.as_u64()).unwrap_or(DEFAULT_GREP_HEAD as u64) as usize;
        let base = self.resolve(&dir)?;
        let re = regex::RegexBuilder::new(&pattern)
            .case_insensitive(ignore_case)
            .size_limit(10_000_000)
            .build()
            .map_err(|e| format!("bad regex: {e}"))?;
        let glob_re = glob_filter.as_deref().map(glob_to_regex);
        let glob_re = glob_re.as_ref();

        let mut lines: Vec<String> = vec![];
        let mut file_hits = 0usize;
        let mut file_count = 0usize;
        let mut truncated = false;

        let files: Vec<PathBuf> = if base.is_file() {
            vec![base.clone()]
        } else {
            walkdir::WalkDir::new(&base)
                .follow_links(false)
                .into_iter()
                .filter_map(|e| e.ok())
                .filter(|e| e.file_type().is_file())
                .map(|e| e.into_path())
                .collect()
        };
        for f in files {
            let rel = f.strip_prefix(&self.root).unwrap_or(&f).to_string_lossy().replace('\\', "/");
            if rel.starts_with(".xencode/") || rel.contains("/.git/") {
                continue;
            }
            if let Some(gr) = glob_re {
                if !gr.is_match(&rel) {
                    continue;
                }
            }
            let text = match std::fs::read_to_string(&f) {
                Ok(t) => t,
                Err(_) => continue, // binary or unreadable — skip
            };
            let mut file_matched = false;
            let mut count = 0usize;
            for (i, line) in text.lines().enumerate() {
                if re.is_match(line) {
                    file_matched = true;
                    count += 1;
                    if output_mode == "content" && lines.len() < head {
                        lines.push(format!("{rel}:{}: {}", i + 1, clip_line(line)));
                    } else if lines.len() >= head && output_mode == "content" {
                        truncated = true;
                    }
                }
            }
            if file_matched {
                file_hits += 1;
                file_count += count;
                if output_mode == "files_with_matches" {
                    lines.push(rel.clone());
                    if lines.len() >= head {
                        truncated = true;
                    }
                }
            }
        }
        match output_mode.as_str() {
            "count" => Ok(format!("{file_hits} file(s), {file_count} match(es)")),
            "files_with_matches" => Ok(if truncated {
                format!("{}\n… (truncated at {head})", lines.join("\n"))
            } else if lines.is_empty() {
                "(no matches)".into()
            } else {
                lines.join("\n")
            }),
            _ => Ok(if truncated {
                format!("{}\n… (truncated at {head})", lines.join("\n"))
            } else if lines.is_empty() {
                "(no matches)".into()
            } else {
                lines.join("\n")
            }),
        }
    }

    fn delete_file(&self, args: &Value) -> Result<String, String> {
        let path = arg_str(args, "path").ok_or("delete_file(path) needs a path")?;
        let f = self.resolve(&path)?;
        if f.is_dir() {
            std::fs::remove_dir(&f).map_err(|e| format!("directory not empty or unreadable: {e}"))?;
        } else {
            std::fs::remove_file(&f).map_err(|e| e.to_string())?;
        }
        Ok(format!("deleted {path}"))
    }

    fn run_command(&self, args: &Value) -> Result<String, String> {
        let command = arg_str(args, "command").ok_or("run_command(command) needs a command")?;
        let timeout = args.get("timeout_sec").and_then(|v| v.as_u64()).unwrap_or(30).clamp(1, 600);
        run_shell(&command, &self.root, timeout)
    }

    fn todo_write(&mut self, args: &Value) -> Result<String, String> {
        let todos = args.get("todos").and_then(|t| t.as_array()).ok_or("todo_write needs todos[]")?;
        let mut items = vec![];
        for t in todos {
            let content = t.get("content").and_then(|c| c.as_str()).unwrap_or("").to_string();
            if content.is_empty() {
                continue;
            }
            let status = t.get("status").and_then(|s| s.as_str()).unwrap_or("pending").to_string();
            let priority = t.get("priority").and_then(|p| p.as_str()).unwrap_or("medium").to_string();
            items.push(todo::TodoItem { content, status, priority });
        }
        self.todo.replace(items)?;
        Ok(self.todo.render())
    }
}

fn arg_str(args: &Value, key: &str) -> Option<String> {
    args.get(key).and_then(|v| v.as_str()).map(|s| s.to_string())
}

fn clip(s: &str) -> String {
    if s.len() <= MAX_RESULT_CHARS {
        s.to_string()
    } else {
        let mut cut = MAX_RESULT_CHARS;
        while !s.is_char_boundary(cut) {
            cut -= 1;
        }
        format!("{}\n… [result clipped at {MAX_RESULT_CHARS} chars]", &s[..cut])
    }
}

fn clip_line(line: &str) -> String {
    if line.len() <= 400 {
        line.to_string()
    } else {
        let mut cut = 400;
        while !line.is_char_boundary(cut) {
            cut -= 1;
        }
        format!("{}…", &line[..cut])
    }
}

/// Lexically normalize a path (resolve `.` and `..` without touching the fs).
pub fn normalize(p: &Path) -> PathBuf {
    let mut out = PathBuf::new();
    for comp in p.components() {
        match comp {
            std::path::Component::ParentDir => {
                out.pop();
            }
            std::path::Component::CurDir => {}
            other => out.push(other.as_os_str()),
        }
    }
    out
}

/// Translate a glob pattern into a regex (twin of Kotlin `globToRegex`).
pub fn glob_to_regex(pattern: &str) -> regex::Regex {
    let mut re = String::from("^");
    let mut chars = pattern.chars().peekable();
    while let Some(c) = chars.next() {
        match c {
            '*' => {
                if chars.peek() == Some(&'*') {
                    chars.next();
                    // `**` — match across separators
                    if chars.peek() == Some(&'/') {
                        chars.next();
                        re.push_str("(?:.*/)?");
                    } else {
                        re.push_str(".*");
                    }
                } else {
                    re.push_str("[^/]*");
                }
            }
            '?' => re.push_str("[^/]"),
            '.' => re.push_str("\\."),
            '/' => re.push('/'),
            other => {
                if "\\.+()^$|{}".contains(other) {
                    re.push('\\');
                }
                re.push(other);
            }
        }
    }
    re.push('$');
    regex::Regex::new(&re).unwrap_or(regex::Regex::new("^$").expect("static regex"))
}

/// Run a shell command with a timeout; returns exit status and combined output.
pub fn run_shell(command: &str, cwd: &Path, timeout_secs: u64) -> Result<String, String> {
    run_shell_env(command, cwd, timeout_secs, &[])
}

/// Like [`run_shell`] with extra environment variables (used by hooks).
pub fn run_shell_env(
    command: &str,
    cwd: &Path,
    timeout_secs: u64,
    env: &[(&str, &str)],
) -> Result<String, String> {
    let shell = if cfg!(windows) { ("cmd", "/C") } else { ("sh", "-c") };
    let mut cmd = Command::new(shell.0);
    cmd.arg(shell.1).arg(command).current_dir(cwd).env("XENCODE", "1");
    for (k, v) in env {
        cmd.env(k, v);
    }
    let mut child = cmd
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::piped())
        .spawn()
        .map_err(|e| format!("spawn failed: {e}"))?;

    let timeout = std::time::Duration::from_secs(timeout_secs.max(1));
    let start = std::time::Instant::now();
    loop {
        match child.try_wait().map_err(|e| e.to_string())? {
            Some(status) => {
                let mut out = String::new();
                if let Some(mut so) = child.stdout.take() {
                    let _ = so.read_to_string(&mut out);
                }
                if let Some(mut se) = child.stderr.take() {
                    let _ = se.read_to_string(&mut out);
                }
                let code = status.code().unwrap_or(-1);
                return Ok(format!("exit={code}\n{out}"));
            }
            None => {
                if start.elapsed() > timeout {
                    let _ = child.kill();
                    let _ = child.wait();
                    return Ok(format!("exit=-1\nERROR: command timed out after {timeout_secs}s"));
                }
                std::thread::sleep(std::time::Duration::from_millis(50));
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn setup() -> (tempfile::TempDir, LocalTools) {
        let dir = tempfile::tempdir().unwrap();
        let tools = LocalTools::new(dir.path());
        (dir, tools)
    }

    #[test]
    fn write_read_edit_delete_roundtrip() {
        let (_d, mut t) = setup();
        let out = t.execute("write_file", r#"{"path":"src/a.rs","content":"fn main(){}\n"}"#);
        assert!(out.starts_with("wrote"), "{out}");
        let out = t.execute("read_file", r#"{"path":"src/a.rs"}"#);
        assert!(out.contains("fn main()"), "{out}");
        let out = t.execute("edit_file", r#"{"path":"src/a.rs","old_str":"fn main(){}","new_str":"fn main(){ println!(\"x\"); }"}"#);
        assert!(out.starts_with("edited"), "{out}");
        let out = t.execute("read_file", r#"{"path":"src/a.rs","offset":1,"limit":1}"#);
        assert!(out.contains("println!"), "{out}");
        let out = t.execute("delete_file", r#"{"path":"src/a.rs"}"#);
        assert!(out.starts_with("deleted"), "{out}");
    }

    #[test]
    fn sandbox_blocks_escape() {
        let (_d, mut t) = setup();
        let out = t.execute("read_file", r#"{"path":"../../etc/passwd"}"#);
        assert!(out.starts_with("ERROR:"), "{out}");
    }

    #[test]
    fn edit_requires_unique_match_unless_replace_all() {
        let (_d, mut t) = setup();
        t.execute("write_file", r#"{"path":"b.txt","content":"x x"}"#);
        let out = t.execute("edit_file", r#"{"path":"b.txt","old_str":"x","new_str":"y"}"#);
        assert!(out.contains("matches 2 times"), "{out}");
        let out = t.execute("edit_file", r#"{"path":"b.txt","old_str":"x","new_str":"y","replace_all":true}"#);
        assert!(out.contains("replaced 2"), "{out}");
    }

    #[test]
    fn glob_and_grep_and_list() {
        let (_d, mut t) = setup();
        t.execute("write_file", r#"{"path":"a.rs","content":"fn alpha(){}"}"#);
        t.execute("write_file", r#"{"path":"sub/b.rs","content":"fn beta(){}"}"#);
        let out = t.execute("glob", r#"{"pattern":"**/*.rs"}"#);
        assert!(out.contains("a.rs") && out.contains("sub/b.rs"), "{out}");
        let out = t.execute("grep", r#"{"pattern":"fn \\w+","output_mode":"content"}"#);
        assert!(out.contains("a.rs:1") && out.contains("sub/b.rs:1"), "{out}");
        let out = t.execute("grep", r#"{"pattern":"fn beta","output_mode":"files_with_matches"}"#);
        assert_eq!(out.trim(), "sub/b.rs");
        let out = t.execute("grep", r#"{"pattern":"FN ALPHA","ignore_case":true,"output_mode":"count"}"#);
        assert!(out.contains("1 file(s)"), "{out}");
        let out = t.execute("list_files", r#"{"recursive":true}"#);
        assert!(out.contains("a.rs") && out.contains("sub/"), "{out}");
    }

    #[test]
    fn run_command_captures_exit_and_timeout_works() {
        let (_d, mut t) = setup();
        let out = t.execute("run_command", r#"{"command":"echo hello; exit 3"}"#);
        assert!(out.starts_with("exit=3"), "{out}");
        assert!(out.contains("hello"));
        let out = t.execute("run_command", r#"{"command":"sleep 5","timeout_sec":1}"#);
        assert!(out.contains("timed out"), "{out}");
    }

    #[test]
    fn todo_tools_roundtrip() {
        let (_d, mut t) = setup();
        let out = t.execute("todo_write", r#"{"todos":[{"content":"ship rust","status":"active","priority":"high"}]}"#);
        assert!(out.contains("ship rust"), "{out}");
        let out = t.execute("todo_read", "{}");
        assert!(out.contains("active"), "{out}");
    }

    #[test]
    fn glob_to_regex_semantics() {
        let re = glob_to_regex("**/*.rs");
        assert!(re.is_match("a.rs"));
        assert!(re.is_match("x/y/z.rs"));
        assert!(!re.is_match("a.txt"));
        let re2 = glob_to_regex("src/*.rs");
        assert!(re2.is_match("src/main.rs"));
        assert!(!re2.is_match("src/x/main.rs"));
    }

    #[test]
    fn unknown_tool_errors() {
        let (_d, mut t) = setup();
        let out = t.execute("nope", "{}");
        assert!(out.starts_with("ERROR: unknown tool"), "{out}");
    }
}
