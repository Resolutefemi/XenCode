//! XenCode `.mjs` automation engine — embedded QuickJS via **rquickjs**.
//!
//! Scripts are real ES modules:
//! ```js
//! import { fs, pty, git, sync } from "xencode:core";
//! export async function main() { ... }
//! ```
//! The `xencode:core` native bindings are synchronous (blocking with internal
//! timeouts), so `await` on them resolves immediately and the returned
//! `main()` promise settles before the engine returns.

pub mod astlite;
pub mod bindings;
pub mod diffpatch;

use rquickjs::{Context, Ctx, Module, Runtime};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};

#[derive(Debug, Clone, Default)]
pub struct MjsResult {
    /// What `main()` returned (stringified); `None` when nothing/side-effects only.
    pub main_return: Option<String>,
    pub files_written: Vec<String>,
    pub commands_run: Vec<String>,
    /// True when the script raised a decision card (requestUserDecision).
    pub decision_requested: bool,
}

#[derive(Debug, Clone)]
pub struct EngineState {
    pub root: PathBuf,
    pub files_written: Vec<String>,
    pub commands_run: Vec<String>,
    pub decision_requested: bool,
}

pub struct MjsEngine {
    state: Arc<Mutex<EngineState>>,
}

impl MjsEngine {
    pub fn new(workspace_root: &Path) -> Self {
        Self {
            state: Arc::new(Mutex::new(EngineState {
                root: workspace_root.to_path_buf(),
                files_written: vec![],
                commands_run: vec![],
                decision_requested: false,
            })),
        }
    }

    pub fn run_file(&self, script: &Path, script_args: Option<serde_json::Value>) -> Result<MjsResult, String> {
        let source = std::fs::read_to_string(script).map_err(|e| format!("cannot read {}: {e}", script.display()))?;
        let module_name = format!("file:{}", script.display());
        self.run_source(&source, &module_name, script_args)
    }

    /// Evaluate a `.mjs` source as an ES module and run its `main` export.
    pub fn run_source(&self, source: &str, module_name: &str, script_args: Option<serde_json::Value>) -> Result<MjsResult, String> {
        use rquickjs::FromJs;
        let rt = Runtime::new().map_err(|e| format!("quickjs runtime: {e}"))?;
        rt.set_max_stack_size(1024 * 1024);
        rt.set_memory_limit(512 * 1024 * 1024);
        let ctx = Context::full(&rt).map_err(|e| format!("quickjs context: {e}"))?;

        // 1. Register the native `xencode:core` module + script args global.
        ctx.with(|ctx| -> Result<(), String> {
            bindings::install(&ctx, &self.state).map_err(|e| format!("binding install: {e}"))?;
            if let Some(args) = &script_args {
                let v = ctx
                    .json_parse(args.to_string().as_bytes())
                    .map_err(|e| format!("args json: {e}"))?;
                ctx.globals().set("__xe_args", v).map_err(|e| e.to_string())?;
            } else {
                let null = rquickjs::Value::new_null(ctx.clone());
                ctx.globals().set("__xe_args", null).map_err(|e| e.to_string())?;
            }
            // Native module: real ESM re-exporting the host bindings.
            Module::declare(ctx.clone(), "xencode:core", bindings::CORE_MODULE_SRC)
                .and_then(|m| m.eval())
                .map_err(|e| describe_err(&ctx, e, "xencode:core module"))?;
            while ctx.execute_pending_job() {}
            Ok(())
        })?;

        // 2. Declare + evaluate the user script as an ES module (imports of
        //    `xencode:core` resolve against the registry entry above).
        ctx.with(|ctx| -> Result<(), String> {
            Module::declare(ctx.clone(), module_name.to_string(), source.to_string())
                .and_then(|m| m.eval())
                .map_err(|e| describe_err(&ctx, e, "script module"))?;
            while ctx.execute_pending_job() {}
            Ok(())
        })?;

        // 3. Import the namespace, call main(), settle promise jobs.
        let mut result = MjsResult::default();
        ctx.with(|ctx| -> Result<(), String> {
            let promise = Module::import(&ctx, module_name.to_string())
                .map_err(|e| describe_err(&ctx, e, "module import"))?;
            let ns: rquickjs::Object = promise
                .finish()
                .map_err(|e| describe_err(&ctx, e, "module evaluate"))?;
            let main_fn: Option<rquickjs::Function> = ns.get("main").map_err(|e| e.to_string())?;
            let main_fn = main_fn.ok_or("script exports no `main()` — add `export function main() { ... }`")?;
            let ret: rquickjs::Value = main_fn
                .call(())
                .map_err(|e| describe_err(&ctx, e, "main() threw"))?;

            // Await the returned promise (async main) synchronously; all host
            // bindings are synchronous so nothing blocks here.
            let mut ret = ret;
            let mut ticks = 0;
            loop {
                if let Ok(p) = rquickjs::Promise::from_value(ret.clone()) {
                    if let Some(res) = p.result::<rquickjs::Value>() {
                        ret = res.map_err(|e| describe_err(&ctx, e, "main() promise rejected"))?;
                        break;
                    }
                } else {
                    break; // not a promise
                }
                if !ctx.execute_pending_job() || ticks > 100_000 {
                    break;
                }
                ticks += 1;
            }

            if ret.is_string() {
                if let Ok(s) = String::from_js(&ctx, ret.clone()) {
                    result.main_return = Some(s.to_string());
                }
            } else if !ret.is_undefined() && !ret.is_null() && !ret.is_object() {
                if let Ok(Some(js)) = ctx.json_stringify(ret) {
                    result.main_return = Some(js.to_string().unwrap_or_default());
                }
            }
            Ok(())
        })?;

        let st = self.state.lock().map_err(|_| "engine state poisoned".to_string())?;
        result.files_written = st.files_written.clone();
        result.commands_run = st.commands_run.clone();
        result.decision_requested = st.decision_requested;
        Ok(result)
    }
}

/// Extract a readable message from a QuickJS exception error.
fn describe_err<'js>(ctx: &Ctx<'js>, e: rquickjs::Error, context: &str) -> String {
    if matches!(e, rquickjs::Error::Exception) {
        let thrown = ctx.catch();
        if let Ok(exc) = rquickjs::Exception::from_value(thrown) {
            let msg = exc.message().unwrap_or_else(|| "exception".to_string());
            let stack = exc.stack().unwrap_or_default();
            if stack.trim().is_empty() {
                format!("{context}: {msg}")
            } else {
                let first = stack.lines().next().unwrap_or("").to_string();
                format!("{context}: {msg} ({first})")
            }
        } else {
            format!("{context}: exception")
        }
    } else {
        format!("{context}: {e}")
    }
}

/// Render an MjsResult the way the CLI reports it.
pub fn render_result(res: &MjsResult) -> String {
    let mut out = String::new();
    if let Some(r) = &res.main_return {
        out.push_str(&format!("main() → {r}\n"));
    }
    if !res.files_written.is_empty() {
        out.push_str(&format!("files written: {}\n", res.files_written.join(", ")));
    }
    if !res.commands_run.is_empty() {
        out.push_str(&format!("commands run: {}\n", res.commands_run.len()));
    }
    if res.decision_requested {
        out.push_str("decision card raised (see `xencode decide list`)\n");
    }
    if out.is_empty() {
        out.push_str("(script completed, no output)\n");
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn real_esm_import_and_export() {
        let dir = tempfile::tempdir().unwrap();
        let engine = MjsEngine::new(dir.path());
        let src = r#"
import { fs, pty } from "xencode:core";
export async function main() {
    fs.writeAtomic("hello.txt", "from quickjs");
    const content = fs.readRange("hello.txt", 1, 1);
    return content;
}
"#;
        let res = engine.run_source(src, "test1", None).unwrap();
        assert_eq!(res.main_return.as_deref(), Some("from quickjs"));
        assert_eq!(res.files_written, vec!["hello.txt"]);
        assert!(dir.path().join("hello.txt").exists());
    }

    #[test]
    fn named_imports_subset_and_rename() {
        let dir = tempfile::tempdir().unwrap();
        let engine = MjsEngine::new(dir.path());
        let src = r#"
import { fs as filesystem, sync } from "xencode:core";
export function main() {
    filesystem.writeAtomic("renamed.txt", "ok");
    const id = sync.emitMobileAndDesktop("TEST_EVENT", { summary: "hi", n: 3 });
    return id.length > 0 ? "emitted" : "empty";
}
"#;
        let res = engine.run_source(src, "test2", None).unwrap();
        assert_eq!(res.main_return.as_deref(), Some("emitted"));
    }

    #[test]
    fn script_args_reachable() {
        let dir = tempfile::tempdir().unwrap();
        let engine = MjsEngine::new(dir.path());
        let src = r#"
export function main() {
    return __xe_args.name;
}
"#;
        let res = engine
            .run_source(src, "test3", Some(serde_json::json!({"name": "xencode"})))
            .unwrap();
        assert_eq!(res.main_return.as_deref(), Some("xencode"));
    }

    #[test]
    fn missing_main_is_clear_error() {
        let dir = tempfile::tempdir().unwrap();
        let engine = MjsEngine::new(dir.path());
        let err = engine.run_source("export const x = 1;", "test4", None).unwrap_err();
        assert!(err.contains("no `main()`"), "{err}");
    }

    #[test]
    fn unknown_module_import_errors() {
        let dir = tempfile::tempdir().unwrap();
        let engine = MjsEngine::new(dir.path());
        let err = engine
            .run_source(r#"import x from "nope:thing"; export function main() { return 1; }"#, "test5", None)
            .unwrap_err();
        assert!(err.contains("nope:thing"), "{err}");
    }
}
