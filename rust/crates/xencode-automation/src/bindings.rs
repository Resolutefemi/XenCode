//! `xencode:core` — the native module injected into every automation script.
//!
//! Surface (twin of the Kotlin MjsEngine bindings):
//! - `fs.glob(pattern, dir?)`, `fs.readRange(path, start?, end?)`,
//!   `fs.writeAtomic(path, content)`, `fs.applyPatch(path, diff)`
//! - `ast.symbols(path)`, `ast.findPattern(path, pattern)`,
//!   `ast.replaceSymbol(path, symbol, newCode)`
//! - `pty.exec(command, {cwd?, timeoutMs?})` → `{exit, output}`
//! - `git.worktreeCreate(id)`, `git.checkpoint(label, message)`,
//!   `git.diffStat()`
//! - `sync.emitMobileAndDesktop(type, payload)`,
//!   `sync.requestUserDecision({title, body, options})` → card id,
//!   `sync.recordTest(id, passed)`
//!
//! All bindings are synchronous from JS's point of view and return
//! `ERROR: …` strings instead of throwing (Kotlin-engine contract).

use super::astlite;
use super::diffpatch;
use super::EngineState;
use rquickjs::function::Opt;
use rquickjs::{Ctx, Function, Object, Value};
use std::sync::{Arc, Mutex};
use xencode_core::gitx;
use xencode_core::state::checkpoints::CheckpointStore;
use xencode_core::state::decisions::DecisionStore;
use xencode_core::state::events::{types as evt, EventLog};
use xencode_core::telemetry::Telemetry;
use xencode_core::tools::LocalTools;

/// The native module source: re-exports the host bindings object as real ESM.
pub const CORE_MODULE_SRC: &str = r#"
const core = globalThis.__xe_core;
const fs = core.fs, ast = core.ast, pty = core.pty, git = core.git, sync = core.sync;
export { fs, ast, pty, git, sync };
export default core;
"#;

type SharedState = Arc<Mutex<EngineState>>;

fn with_state<T>(state: &SharedState, f: impl FnOnce(&mut EngineState) -> T) -> T {
    let mut st = state.lock().expect("engine state poisoned");
    f(&mut st)
}

fn resolved(root: &std::path::Path, path: &str) -> Result<std::path::PathBuf, String> {
    let tools = LocalTools::new(root);
    tools.resolve(path)
}

fn json_value<'js>(ctx: &Ctx<'js>, v: serde_json::Value) -> Value<'js> {
    ctx.json_parse(v.to_string().as_bytes())
        .unwrap_or_else(|_| Value::new_undefined(ctx.clone()))
}

/// Constructors are separate generic fns so the `'js` lifetime unifies across
/// the `Ctx` parameter and the returned `Value`.
fn mk_glob<'js>(ctx: Ctx<'js>, st: SharedState) -> rquickjs::Result<Function<'js>> {
    Function::new(ctx, move |pattern: String, dir: Opt<String>| -> Vec<String> {
        let root = st.lock().expect("state").root.clone();
        let base = match dir.0 {
            Some(d) => match resolved(&root, &d) {
                Ok(b) => b,
                Err(e) => return vec![format!("ERROR: {e}")],
            },
            None => root.clone(),
        };
        let full = if pattern.starts_with('/') {
            pattern.clone()
        } else {
            format!("{}/{}", base.display(), pattern.trim_start_matches("./"))
        };
        let mut hits = vec![];
        for entry in glob::glob(&full).into_iter().flatten().flatten() {
            let rel = entry
                .strip_prefix(&root)
                .unwrap_or(&entry)
                .to_string_lossy()
                .replace('\\', "/");
            hits.push(rel);
            if hits.len() >= 200 {
                break;
            }
        }
        hits
    })
}

fn mk_read_range<'js>(ctx: Ctx<'js>, st: SharedState) -> rquickjs::Result<Function<'js>> {
    Function::new(ctx, move |path: String, start: Opt<i64>, end: Opt<i64>| -> String {
        let root = st.lock().expect("state").root.clone();
        let f = match resolved(&root, &path) {
            Ok(f) => f,
            Err(e) => return format!("ERROR: {e}"),
        };
        if !f.is_file() {
            return format!("ERROR: not a file: {path}");
        }
        let text = match std::fs::read_to_string(&f) {
            Ok(t) => t,
            Err(e) => return format!("ERROR: {e}"),
        };
        let lines: Vec<&str> = text.lines().collect();
        let start = start.0.unwrap_or(1).max(1) as usize;
        let end = (end.0.unwrap_or(lines.len() as i64) as usize).clamp(start, lines.len().max(1));
        if start > lines.len() {
            return format!("(empty range: file has {} lines)", lines.len());
        }
        lines[start - 1..end].join("\n")
    })
}

fn mk_write_atomic<'js>(ctx: Ctx<'js>, st: SharedState) -> rquickjs::Result<Function<'js>> {
    Function::new(ctx, move |path: String, content: String| -> String {
        let root = st.lock().expect("state").root.clone();
        let f = match resolved(&root, &path) {
            Ok(f) => f,
            Err(e) => return format!("ERROR: {e}"),
        };
        match xencode_core::config::write_atomic(&f, content.as_bytes()) {
            Ok(()) => {
                with_state(&st, |s| s.files_written.push(path.clone()));
                format!("wrote {} chars to {}", content.chars().count(), path)
            }
            Err(e) => format!("ERROR: {e}"),
        }
    })
}

fn mk_apply_patch<'js>(ctx: Ctx<'js>, st: SharedState) -> rquickjs::Result<Function<'js>> {
    Function::new(ctx, move |path: String, diff: String| -> String {
        let root = st.lock().expect("state").root.clone();
        let f = match resolved(&root, &path) {
            Ok(f) => f,
            Err(e) => return format!("ERROR: {e}"),
        };
        if !f.is_file() {
            return format!("ERROR: not a file: {path}");
        }
        let text = match std::fs::read_to_string(&f) {
            Ok(t) => t,
            Err(e) => return format!("ERROR: {e}"),
        };
        match diffpatch::apply_to_string(&text, &diff) {
            Ok((next, hunks, lines)) => match std::fs::write(&f, next) {
                Ok(()) => {
                    with_state(&st, |s| s.files_written.push(path.clone()));
                    format!("applied {hunks} hunks ({lines} lines) to {path}")
                }
                Err(e) => format!("ERROR: {e}"),
            },
            Err(reason) => format!("ERROR: patch rejected: {reason}"),
        }
    })
}

fn mk_ast_symbols<'js>(ctx: Ctx<'js>, st: SharedState) -> rquickjs::Result<Function<'js>> {
    Function::new(ctx, move |path: String| -> Vec<String> {
        let root = st.lock().expect("state").root.clone();
        let f = match resolved(&root, &path) {
            Ok(f) => f,
            Err(e) => return vec![format!("ERROR: {e}")],
        };
        match std::fs::read_to_string(&f) {
            Ok(src) => astlite::symbols(&src, &path),
            Err(_) => vec![format!("ERROR: not a file: {path}")],
        }
    })
}

fn mk_ast_find<'js>(ctx: Ctx<'js>, st: SharedState) -> rquickjs::Result<Function<'js>> {
    Function::new(ctx, move |path: String, pattern: String| -> Vec<String> {
        let root = st.lock().expect("state").root.clone();
        let f = match resolved(&root, &path) {
            Ok(f) => f,
            Err(e) => return vec![format!("ERROR: {e}")],
        };
        match std::fs::read_to_string(&f) {
            Ok(src) => astlite::find_pattern(&src, &pattern),
            Err(_) => vec![format!("ERROR: not a file: {path}")],
        }
    })
}

fn mk_ast_replace<'js>(ctx: Ctx<'js>, st: SharedState) -> rquickjs::Result<Function<'js>> {
    Function::new(ctx, move |path: String, symbol: String, new_code: String| -> String {
        let root = st.lock().expect("state").root.clone();
        let f = match resolved(&root, &path) {
            Ok(f) => f,
            Err(e) => return format!("ERROR: {e}"),
        };
        let src = match std::fs::read_to_string(&f) {
            Ok(s) => s,
            Err(e) => return format!("ERROR: {e}"),
        };
        match astlite::replace_symbol(&src, &path, &symbol, &new_code) {
            Some(next) => match std::fs::write(&f, next) {
                Ok(()) => {
                    with_state(&st, |s| s.files_written.push(path.clone()));
                    format!("replaced `{symbol}` in {path}")
                }
                Err(e) => format!("ERROR: {e}"),
            },
            None => format!("ERROR: symbol `{symbol}` not found in {path}"),
        }
    })
}

fn mk_pty_exec<'js>(ctx: Ctx<'js>, st: SharedState) -> rquickjs::Result<Function<'js>> {
    Function::new(ctx, move |ctx: Ctx<'js>, command: String, opts: Opt<Value<'js>>| -> Value<'js> {
        let root = st.lock().expect("state").root.clone();
        let (cwd_rel, timeout_ms) = match &opts.0 {
            Some(v) if v.is_object() => {
                let cwd = v
                    .as_object()
                    .and_then(|o| o.get::<_, Option<String>>("cwd").ok().flatten())
                    .unwrap_or_else(|| ".".into());
                let t = v
                    .as_object()
                    .and_then(|o| o.get::<_, Option<i64>>("timeoutMs").ok().flatten())
                    .unwrap_or(30_000);
                (cwd, t)
            }
            _ => (".".to_string(), 30_000),
        };
        let cwd = match resolved(&root, &cwd_rel) {
            Ok(c) => c,
            Err(e) => return json_value(&ctx, serde_json::json!({"exit": -1, "output": format!("ERROR: {e}")})),
        };
        with_state(&st, |s| s.commands_run.push(command.clone()));
        let out = xencode_core::tools::run_shell(
            &command,
            &cwd,
            ((timeout_ms.clamp(1_000, 600_000) / 1000).max(1)) as u64,
        )
        .unwrap_or_else(|e| format!("exit=-1\nERROR: {e}"));
        let (exit, output) = match out.split_once('\n') {
            Some((head, rest)) => (
                head.strip_prefix("exit=").and_then(|c| c.parse::<i32>().ok()).unwrap_or(-1),
                rest.to_string(),
            ),
            None => (-1, out),
        };
        json_value(&ctx, serde_json::json!({"exit": exit, "output": output}))
    })
}

fn mk_worktree_create<'js>(ctx: Ctx<'js>, st: SharedState) -> rquickjs::Result<Function<'js>> {
    Function::new(ctx, move |ctx: Ctx<'js>, id: String| -> Value<'js> {
        let root = st.lock().expect("state").root.clone();
        match gitx::worktree_create(&root, &id) {
            Ok((path, branch)) => json_value(
                &ctx,
                serde_json::json!({"ok": true, "path": path.display().to_string(), "branch": branch}),
            ),
            Err(e) => json_value(&ctx, serde_json::json!({"ok": false, "error": e})),
        }
    })
}

fn mk_git_checkpoint<'js>(ctx: Ctx<'js>, st: SharedState) -> rquickjs::Result<Function<'js>> {
    Function::new(ctx, move |ctx: Ctx<'js>, label: String, message: Opt<String>| -> Value<'js> {
        let root = st.lock().expect("state").root.clone();
        let store = CheckpointStore::new(&root);
        match store.create(&label, &message.0.unwrap_or_else(|| "verified".into())) {
            Ok(cp) => {
                EventLog::new(&root).emit(
                    evt::CHECKPOINT,
                    Some(&format!("{} {}", cp.id, cp.commit.as_deref().unwrap_or("-"))),
                    serde_json::Map::from_iter([(
                        "label".to_string(),
                        serde_json::Value::String(cp.label.clone()),
                    )]),
                );
                json_value(
                    &ctx,
                    serde_json::json!({
                        "ok": true, "id": cp.id,
                        "commit": cp.commit.unwrap_or_default(),
                        "filesChanged": cp.files_changed
                    }),
                )
            }
            Err(e) => json_value(&ctx, serde_json::json!({"ok": false, "error": e})),
        }
    })
}

fn mk_diff_stat<'js>(ctx: Ctx<'js>, st: SharedState) -> rquickjs::Result<Function<'js>> {
    Function::new(ctx, move || -> String {
        let root = st.lock().expect("state").root.clone();
        gitx::diff_stat(&root).unwrap_or_else(|e| e)
    })
}

fn mk_emit_event<'js>(ctx: Ctx<'js>, st: SharedState) -> rquickjs::Result<Function<'js>> {
    Function::new(ctx, move |event_type: String, payload: Opt<Value>| -> String {
        let root = st.lock().expect("state").root.clone();
        // Build a throwaway context-free conversion: reuse the value via its
        // JSON form obtained at call time.
        let fields = payload_to_fields_json(payload.0);
        let summary = fields.get("summary").and_then(|s| s.as_str()).map(|s| s.to_string());
        EventLog::new(&root).emit(&event_type, summary.as_deref(), fields)
    })
}

fn payload_to_fields_json(payload: Option<Value>) -> serde_json::Map<String, serde_json::Value> {
    let mut fields = serde_json::Map::new();
    if let Some(v) = payload {
        if let Some(obj) = v.as_object() {
            let ctx = obj.ctx().clone();
            if let Ok(Some(js)) = ctx.json_stringify(v) {
                if let Ok(serde_json::Value::Object(map)) =
                    serde_json::from_str::<serde_json::Value>(js.to_string().unwrap_or_default().as_str())
                {
                    fields = map;
                }
            }
        }
    }
    fields
}

fn mk_request_decision<'js>(ctx: Ctx<'js>, st: SharedState) -> rquickjs::Result<Function<'js>> {
    Function::new(ctx, move |ctx: Ctx<'js>, payload: Opt<Value>| -> Value<'js> {
        let root = st.lock().expect("state").root.clone();
        let fields = payload_to_fields_json(payload.0);
        let title = fields.get("title").and_then(|t| t.as_str()).unwrap_or("Decision needed").to_string();
        let body = fields.get("body").and_then(|b| b.as_str()).unwrap_or("").to_string();
        let options: Vec<String> = fields
            .get("options")
            .and_then(|o| o.as_array())
            .map(|arr| arr.iter().filter_map(|v| v.as_str().map(|s| s.to_string())).collect())
            .filter(|v: &Vec<String>| !v.is_empty())
            .unwrap_or_else(|| vec!["approve".into(), "reject".into()]);
        let store = DecisionStore::new(&root);
        match store.create(&title, &body, options) {
            Ok(card) => {
                EventLog::new(&root).emit(
                    evt::DECISION_REQUESTED,
                    Some(&card.title),
                    serde_json::Map::from_iter([(
                        "card".to_string(),
                        serde_json::Value::String(card.id.clone()),
                    )]),
                );
                with_state(&st, |s| s.decision_requested = true);
                json_value(&ctx, serde_json::Value::String(card.id))
            }
            Err(e) => json_value(&ctx, serde_json::json!({"ok": false, "error": e})),
        }
    })
}

fn mk_record_test<'js>(ctx: Ctx<'js>, st: SharedState) -> rquickjs::Result<Function<'js>> {
    Function::new(ctx, move |id: String, passed: bool| -> String {
        let root = st.lock().expect("state").root.clone();
        let mut t = Telemetry::load(&root);
        t.record_test(&id, passed);
        let _ = t.save(&root);
        format!("recorded {id} {}", if passed { "GREEN" } else { "RED" })
    })
}

/// Install `globalThis.__xe_core` with all binding groups.
pub fn install(ctx: &Ctx<'_>, state: &SharedState) -> rquickjs::Result<()> {
    let core = Object::new(ctx.clone())?;

    let fs_obj = Object::new(ctx.clone())?;
    fs_obj.set("glob", mk_glob(ctx.clone(), state.clone())?)?;
    fs_obj.set("readRange", mk_read_range(ctx.clone(), state.clone())?)?;
    fs_obj.set("writeAtomic", mk_write_atomic(ctx.clone(), state.clone())?)?;
    fs_obj.set("applyPatch", mk_apply_patch(ctx.clone(), state.clone())?)?;
    core.set("fs", fs_obj)?;

    let ast_obj = Object::new(ctx.clone())?;
    ast_obj.set("symbols", mk_ast_symbols(ctx.clone(), state.clone())?)?;
    ast_obj.set("findPattern", mk_ast_find(ctx.clone(), state.clone())?)?;
    ast_obj.set("replaceSymbol", mk_ast_replace(ctx.clone(), state.clone())?)?;
    core.set("ast", ast_obj)?;

    let pty_obj = Object::new(ctx.clone())?;
    pty_obj.set("exec", mk_pty_exec(ctx.clone(), state.clone())?)?;
    core.set("pty", pty_obj)?;

    let git_obj = Object::new(ctx.clone())?;
    git_obj.set("worktreeCreate", mk_worktree_create(ctx.clone(), state.clone())?)?;
    git_obj.set("checkpoint", mk_git_checkpoint(ctx.clone(), state.clone())?)?;
    git_obj.set("diffStat", mk_diff_stat(ctx.clone(), state.clone())?)?;
    core.set("git", git_obj)?;

    let sync_obj = Object::new(ctx.clone())?;
    sync_obj.set("emitMobileAndDesktop", mk_emit_event(ctx.clone(), state.clone())?)?;
    sync_obj.set("requestUserDecision", mk_request_decision(ctx.clone(), state.clone())?)?;
    sync_obj.set("recordTest", mk_record_test(ctx.clone(), state.clone())?)?;
    core.set("sync", sync_obj)?;

    ctx.globals().set("__xe_core", core)?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::super::MjsEngine;
    use xencode_core::state::decisions::DecisionStore;

    #[test]
    fn pty_exec_and_git_checkpoint() {
        let dir = tempfile::tempdir().unwrap();
        xencode_core::gitx::run(dir.path(), &["init", "-q"]).unwrap();
        xencode_core::gitx::run(dir.path(), &["config", "user.email", "t@t"]).unwrap();
        xencode_core::gitx::run(dir.path(), &["config", "user.name", "t"]).unwrap();
        std::fs::write(dir.path().join("f.txt"), "v1\n").unwrap();

        let engine = MjsEngine::new(dir.path());
        let src = r#"
import { pty, git } from "xencode:core";
export function main() {
    const r = pty.exec("echo scripted", { timeoutMs: 5000 });
    if (r.exit !== 0) throw new Error("echo failed: " + r.output);
    const cp = git.checkpoint("g1", "scripted checkpoint");
    if (!cp.ok) throw new Error("checkpoint failed: " + cp.error);
    return cp.commit.length > 0 ? "checkpointed" : "no commit";
}
"#;
        let res = engine.run_source(src, "t_git", None).unwrap();
        assert_eq!(res.main_return.as_deref(), Some("checkpointed"));
        assert_eq!(res.commands_run.len(), 1);
    }

    #[test]
    fn ast_replace_flow() {
        let dir = tempfile::tempdir().unwrap();
        std::fs::write(
            dir.path().join("lib.rs"),
            "fn alpha() {\n    println!(\"a\");\n}\n\nfn beta() {}\n",
        )
        .unwrap();

        let engine = MjsEngine::new(dir.path());
        let src = r#"
import { ast, fs } from "xencode:core";
export function main() {
    const syms = ast.symbols("lib.rs");
    if (!syms.some(s => s.includes("alpha"))) return "no alpha!";
    ast.replaceSymbol("lib.rs", "alpha", "fn alpha() {\n    println!(\"replaced\");\n}");
    const content = fs.readRange("lib.rs", 1, 3);
    return content.includes("replaced") ? "replaced" : "failed: " + content;
}
"#;
        let res = engine.run_source(src, "t_ast", None).unwrap();
        assert_eq!(res.main_return.as_deref(), Some("replaced"), "{res:?}");
    }

    #[test]
    fn decision_card_flow() {
        let dir = tempfile::tempdir().unwrap();
        let engine = MjsEngine::new(dir.path());
        let src = r#"
import { sync } from "xencode:core";
export function main() {
    const card = sync.requestUserDecision({ title: "Deploy?", body: "to prod", options: ["yes", "no"] });
    return card.length > 0 ? "card:" + card : "no card";
}
"#;
        let res = engine.run_source(src, "t_dec", None).unwrap();
        assert!(res.main_return.as_deref().unwrap_or("").starts_with("card:"));
        assert!(res.decision_requested);
        let store = DecisionStore::new(dir.path());
        assert_eq!(store.pending().len(), 1);
    }

    #[test]
    fn sandbox_escapes_blocked() {
        let dir = tempfile::tempdir().unwrap();
        let engine = MjsEngine::new(dir.path());
        let src = r#"
import { fs } from "xencode:core";
export function main() {
    const r = fs.writeAtomic("../outside.txt", "nope");
    return r.startsWith("ERROR") ? "blocked" : "LEAK";
}
"#;
        let res = engine.run_source(src, "t_sbx", None).unwrap();
        assert_eq!(res.main_return.as_deref(), Some("blocked"));
    }
}
