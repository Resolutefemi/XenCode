//! Built-in agent tools: deterministic file-system / process operations the
//! model can invoke. Arguments arrive as JSON objects from the model.

use std::path::{Path, PathBuf};
use std::process::Stdio;

use anyhow::{bail, Context, Result};
use serde_json::{json, Value};

use super::provider::ToolDef;

pub struct ToolCtx {
    pub cwd: PathBuf,
}

const MAX_OUTPUT: usize = 16 * 1024;

pub fn tool_defs() -> Vec<ToolDef> {
    vec![
        ToolDef {
            name: "read_file".into(),
            description: "Read a text file. Returns the file content with numbered lines."
                .into(),
            parameters: json!({
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "File path (relative to the project root)"},
                    "offset": {"type": "integer", "description": "1-based line to start from"},
                    "limit": {"type": "integer", "description": "Max lines to return"}
                },
                "required": ["path"]
            }),
        },
        ToolDef {
            name: "write_file".into(),
            description: "Create or overwrite a file with the given content.".into(),
            parameters: json!({
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "File path (relative to the project root)"},
                    "content": {"type": "string", "description": "Full file content"}
                },
                "required": ["path", "content"]
            }),
        },
        ToolDef {
            name: "edit_file".into(),
            description: "Replace an exact string in a file. Prefer this over rewriting whole files."
                .into(),
            parameters: json!({
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "File path (relative to the project root)"},
                    "old": {"type": "string", "description": "Exact text to replace"},
                    "new": {"type": "string", "description": "Replacement text"},
                    "replace_all": {"type": "boolean", "description": "Replace every occurrence (default: first only)"}
                },
                "required": ["path", "old", "new"]
            }),
        },
        ToolDef {
            name: "list_dir".into(),
            description: "List a directory's entries (dirs get a trailing slash).".into(),
            parameters: json!({
                "type": "object",
                "properties": {
                    "path": {"type": "string", "description": "Directory (default: project root)"}
                }
            }),
        },
        ToolDef {
            name: "grep".into(),
            description: "Search file contents with a regular expression. Skips .git/target/node_modules."
                .into(),
            parameters: json!({
                "type": "object",
                "properties": {
                    "pattern": {"type": "string", "description": "Rust regex pattern"},
                    "path": {"type": "string", "description": "Where to search (default: project root)"},
                    "include": {"type": "string", "description": "Filename substring filter, e.g. '.rs'"}
                },
                "required": ["pattern"]
            }),
        },
        ToolDef {
            name: "bash".into(),
            description: "Run a shell command in the project root and return its output.".into(),
            parameters: json!({
                "type": "object",
                "properties": {
                    "command": {"type": "string", "description": "The shell command"},
                    "timeout_ms": {"type": "integer", "description": "Timeout in ms (default 120000)"}
                },
                "required": ["command"]
            }),
        },
        ToolDef {
            name: "todo_write".into(),
            description: "Write the current task list. Statuses: pending, in_progress, completed."
                .into(),
            parameters: json!({
                "type": "object",
                "properties": {
                    "todos": {
                        "type": "array",
                        "items": {
                            "type": "object",
                            "properties": {
                                "content": {"type": "string"},
                                "status": {"type": "string", "enum": ["pending", "in_progress", "completed"]}
                            },
                            "required": ["content", "status"]
                        }
                    }
                },
                "required": ["todos"]
            }),
        },
    ]
}

pub async fn execute(name: &str, args: &Value, ctx: &ToolCtx) -> Result<String> {
    match name {
        "read_file" => read_file(args, ctx).await,
        "write_file" => write_file(args, ctx).await,
        "edit_file" => edit_file(args, ctx).await,
        "list_dir" => list_dir(args, ctx).await,
        "grep" => grep(args, ctx).await,
        "bash" => bash(args, ctx).await,
        "todo_write" => todo_write(args),
        other => bail!("unknown tool: {other}"),
    }
}

fn s(args: &Value, key: &str) -> Option<String> {
    args.get(key).and_then(Value::as_str).map(str::to_string)
}

fn resolve(ctx: &ToolCtx, p: &str) -> PathBuf {
    let path = Path::new(p);
    if path.is_absolute() {
        path.to_path_buf()
    } else {
        ctx.cwd.join(path)
    }
}

fn cap(text: String) -> String {
    if text.len() <= MAX_OUTPUT {
        return text;
    }
    let mut end = MAX_OUTPUT;
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    format!("{}\n… (truncated)", &text[..end])
}

async fn read_file(args: &Value, ctx: &ToolCtx) -> Result<String> {
    let path = resolve(ctx, &s(args, "path").context("read_file: missing path")?);
    let text = tokio::fs::read_to_string(&path)
        .await
        .with_context(|| format!("read {}", path.display()))?;
    let offset = args.get("offset").and_then(Value::as_u64).unwrap_or(1).max(1) as usize;
    let limit = args.get("limit").and_then(Value::as_u64).unwrap_or(400).min(2000) as usize;
    let mut out = String::new();
    for (i, line) in text.lines().enumerate().skip(offset - 1).take(limit) {
        out.push_str(&format!("{:>5} | {}\n", i + 1, line));
    }
    Ok(cap(if out.is_empty() { "(empty file)".into() } else { out }))
}

async fn write_file(args: &Value, ctx: &ToolCtx) -> Result<String> {
    let path = resolve(ctx, &s(args, "path").context("write_file: missing path")?);
    let content = s(args, "content").context("write_file: missing content")?;
    if let Some(parent) = path.parent() {
        tokio::fs::create_dir_all(parent).await?;
    }
    tokio::fs::write(&path, &content)
        .await
        .with_context(|| format!("write {}", path.display()))?;
    Ok(format!("Wrote {} bytes to {}", content.len(), path.display()))
}

async fn edit_file(args: &Value, ctx: &ToolCtx) -> Result<String> {
    let path = resolve(ctx, &s(args, "path").context("edit_file: missing path")?);
    let old = s(args, "old").context("edit_file: missing old")?;
    let new = s(args, "new").unwrap_or_default();
    let text = tokio::fs::read_to_string(&path)
        .await
        .with_context(|| format!("read {}", path.display()))?;
    let count = text.matches(&old).count();
    if count == 0 {
        bail!("edit_file: old_string not found in {}", path.display());
    }
    let replaced = if args.get("replace_all").and_then(Value::as_bool).unwrap_or(false) {
        text.replace(&old, &new)
    } else {
        text.replacen(&old, &new, 1)
    };
    tokio::fs::write(&path, &replaced)
        .await
        .with_context(|| format!("write {}", path.display()))?;
    Ok(format!("Edited {} ({} occurrence(s) found)", path.display(), count))
}

async fn list_dir(args: &Value, ctx: &ToolCtx) -> Result<String> {
    let dir = resolve(ctx, &s(args, "path").unwrap_or_else(|| ".".into()));
    let mut rd = tokio::fs::read_dir(&dir)
        .await
        .with_context(|| format!("list {}", dir.display()))?;
    let mut entries: Vec<String> = Vec::new();
    while let Some(e) = rd.next_entry().await? {
        let name = e.file_name().to_string_lossy().into_owned();
        if e.file_type().await?.is_dir() {
            entries.push(format!("{name}/"));
        } else {
            let size = e.metadata().await.map(|m| m.len()).unwrap_or(0);
            entries.push(format!("{name} ({size}B)"));
        }
    }
    entries.sort();
    if entries.len() > 500 {
        entries.truncate(500);
        entries.push("… truncated".into());
    }
    Ok(cap(if entries.is_empty() { "(empty)".into() } else { entries.join("\n") }))
}

async fn grep(args: &Value, ctx: &ToolCtx) -> Result<String> {
    let pattern = s(args, "pattern").context("grep: missing pattern")?;
    let re = regex::Regex::new(&pattern).context("grep: invalid regex")?;
    let root = resolve(ctx, &s(args, "path").unwrap_or_else(|| ".".into()));
    let include = s(args, "include");

    let mut files = Vec::new();
    walk(&root, &mut files, 0);
    files.sort();

    let mut hits = 0usize;
    let mut out = String::new();
    for f in files {
        if let Some(inc) = &include {
            let name = f.file_name().map(|n| n.to_string_lossy().into_owned()).unwrap_or_default();
            if !name.contains(inc.as_str()) {
                continue;
            }
        }
        let Ok(text) = tokio::fs::read_to_string(&f).await else { continue };
        for (i, line) in text.lines().enumerate() {
            if re.is_match(line) {
                let rel = f.strip_prefix(&root).unwrap_or(&f).display();
                out.push_str(&format!("{rel}:{}: {}\n", i + 1, line.trim_start()));
                hits += 1;
                if hits >= 200 {
                    out.push_str("… truncated at 200 matches\n");
                    return Ok(cap(out));
                }
            }
        }
    }
    if hits == 0 {
        Ok("No matches.".into())
    } else {
        Ok(cap(out))
    }
}

fn walk(dir: &Path, out: &mut Vec<PathBuf>, depth: usize) {
    if depth > 8 {
        return;
    }
    let Ok(rd) = std::fs::read_dir(dir) else { return };
    for entry in rd.flatten() {
        let name = entry.file_name().to_string_lossy().into_owned();
        if matches!(name.as_str(), ".git" | "target" | "node_modules" | "dist" | "build") {
            continue;
        }
        let Ok(ft) = entry.file_type() else { continue };
        let p = entry.path();
        if ft.is_dir() {
            walk(&p, out, depth + 1);
        } else {
            out.push(p);
        }
    }
}

async fn bash(args: &Value, ctx: &ToolCtx) -> Result<String> {
    let cmd = s(args, "command").context("bash: missing command")?;
    let timeout_ms = args.get("timeout_ms").and_then(Value::as_u64).unwrap_or(120_000);

    let mut builder = if cfg!(windows) {
        let mut b = tokio::process::Command::new("cmd");
        b.arg("/C");
        b
    } else {
        let mut b = tokio::process::Command::new("sh");
        b.arg("-c");
        b
    };
    builder
        .arg(&cmd)
        .current_dir(&ctx.cwd)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped());

    let out = tokio::time::timeout(std::time::Duration::from_millis(timeout_ms), builder.output())
        .await
        .context("bash: timed out")??;

    let mut text = String::from_utf8_lossy(&out.stdout).into_owned();
    let err = String::from_utf8_lossy(&out.stderr);
    if !err.trim().is_empty() {
        text.push_str(&err);
    }
    if !out.status.success() {
        text.push_str(&format!("\n[exit code: {}]", out.status.code().unwrap_or(-1)));
    }
    Ok(cap(if text.trim().is_empty() { "(no output)".into() } else { text }))
}

fn todo_write(args: &Value) -> Result<String> {
    let todos = args
        .get("todos")
        .and_then(Value::as_array)
        .context("todo_write: missing todos")?;
    let mut out = String::new();
    for t in todos {
        let content = t.get("content").and_then(Value::as_str).unwrap_or("?");
        let status = t.get("status").and_then(Value::as_str).unwrap_or("pending");
        let mark = match status {
            "completed" => "[x]",
            "in_progress" => "[~]",
            _ => "[ ]",
        };
        out.push_str(&format!("{mark} {content}\n"));
    }
    Ok(out)
}
