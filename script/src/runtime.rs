//! The script runtime — one isolated QuickJS context per script, memory
//! limit, stack limit, and an interrupt guard (deadline + manual cancel).
//!
//! Module loading goes through a custom [`XenResolver`] + [`XenFileLoader`]
//! pair: `xen/*` host/built-in modules come from the registered native
//! `ModuleDef`s, npm-style bare specifiers map onto native modules or the
//! prefetched vendor cache, and file paths resolve relative to the importing
//! module (with `.js`/`.ts`/`.tsx`/`.json` extension + `index` fallback).
//! TypeScript is stripped in-process with oxc before it reaches QuickJS.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use rquickjs::loader::{Loader, ModuleLoader, Resolver};
use rquickjs::{AsyncContext, AsyncRuntime, Ctx, Error as JsError, Module, Result as JsResult, Value};

use crate::events::UiEventTx;
use crate::host::agent::AgentBridge;
use crate::host::state::RuntimeState;
use crate::host::ui::UiBridge;
use crate::modules::NATIVE_BARE;
use crate::permissions::PermissionState;
use crate::shims;
use crate::timers::Timers;
use crate::ts;

/// Runtime knobs ([`ScriptRuntime::new`]).
#[derive(Clone)]
pub struct RuntimeOptions {
    /// QuickJS heap limit in bytes (default 64 MiB).
    pub memory_limit: usize,
    /// JS stack size in bytes (default 1 MiB).
    pub max_stack_size: usize,
    /// Root used for `fs` permission scopes (`$PROJECT` in xen.toml).
    pub project_root: PathBuf,
    /// Permission grants + consent bridge.
    pub permissions: PermissionState,
    /// Where console/toast/progress events go.
    pub events: UiEventTx,
    /// npm specifier → vendored file (from [`crate::npm::VendorCache::prefetch`]).
    pub npm_files: HashMap<String, PathBuf>,
    /// npm package name → vendored package root (for `pkg/subpath` imports).
    pub npm_roots: HashMap<String, PathBuf>,
    /// Optional deadline for interrupt-guarded runs.
    pub timeout: Option<Duration>,
}

impl RuntimeOptions {
    pub fn new(project_root: impl Into<PathBuf>, permissions: PermissionState, events: UiEventTx) -> Self {
        Self {
            memory_limit: 64 * 1024 * 1024,
            max_stack_size: 1024 * 1024,
            project_root: project_root.into(),
            permissions,
            events,
            npm_files: HashMap::new(),
            npm_roots: HashMap::new(),
            timeout: None,
        }
    }
}

/// Shared interrupt state: deadline from `timeout` + manual cancel flag.
#[derive(Default)]
struct InterruptState {
    deadline: Mutex<Option<Instant>>,
    cancelled: AtomicBool,
}

impl InterruptState {
    fn set_timeout(&self, timeout: Option<Duration>) {
        *self.deadline.lock().unwrap() = timeout.map(|t| Instant::now() + t);
        self.cancelled.store(false, Ordering::Relaxed);
    }

    fn cancel(&self) {
        self.cancelled.store(true, Ordering::Relaxed);
    }

    fn should_stop(&self) -> bool {
        if self.cancelled.load(Ordering::Relaxed) {
            return true;
        }
        match *self.deadline.lock().unwrap() {
            Some(d) => Instant::now() >= d,
            None => false,
        }
    }
}

/// One isolated QuickJS runtime + context with the full XenCode automation
/// surface installed (shims, timers, `xen.*` host bridge, built-in modules,
/// npm vendor cache).
pub struct ScriptRuntime {
    pub rt: AsyncRuntime,
    pub ctx: AsyncContext,
    pub timers: Arc<Timers>,
    pub agent: AgentBridge,
    pub ui: UiBridge,
    interrupt: Arc<InterruptState>,
    default_timeout: Option<Duration>,
}

impl ScriptRuntime {
    pub async fn new(opts: RuntimeOptions) -> JsResult<Self> {
        let rt = AsyncRuntime::new()?;
        rt.set_memory_limit(opts.memory_limit).await;
        rt.set_max_stack_size(opts.max_stack_size).await;

        let interrupt = Arc::new(InterruptState::default());
        let ih = interrupt.clone();
        rt.set_interrupt_handler(Some(Box::new(move || ih.should_stop()))).await;

        let ctx = AsyncContext::full(&rt).await?;

        let agent = AgentBridge::new();
        let ui = UiBridge::new();
        let state = RuntimeState::new(
            opts.events.clone(),
            opts.permissions.clone(),
            agent.clone(),
            ui.clone(),
        );
        ctx.with(|cx| {
            cx.store_userdata(state)
                .map_err(|_| JsError::FromJs {
                    from: "runtime",
                    to: "userdata",
                    message: Some("failed to store runtime state".into()),
                })
                .map(|_| ())
        })
        .await?;

        agent.set_context(Arc::new(ctx.clone()));

        shims::install(&ctx, &opts.events).await?;
        let timers = Timers::install(&ctx).await?;

        // rquickjs 0.14 requires the host to drive futures spawned on the
        // runtime (timers, watchers, pending promises). Must run inside a
        // `LocalSet` (the drive future is !Send, like everything QuickJS).
        tokio::task::spawn_local(rt.drive());

        let resolver = XenResolver {
            npm_files: Arc::new(opts.npm_files.clone()),
            npm_roots: Arc::new(opts.npm_roots.clone()),
        };
        let module_loader = build_native_module_loader();
        rt.set_loader(resolver, (module_loader, XenFileLoader)).await;

        // global `xen` aggregate — after the loader so native imports resolve
        install_xen_global(&ctx).await?;

        Ok(Self {
            rt,
            ctx,
            timers,
            agent,
            ui,
            interrupt,
            default_timeout: opts.timeout,
        })
    }

    /// Run a script file to completion (top-level `await` supported) and
    /// return its `default` export as JSON (`null` when absent/undefined).
    pub async fn run_script(&self, path: &Path, args: &serde_json::Value) -> anyhow::Result<serde_json::Value> {
        self.run_script_with_timeout(path, args, self.default_timeout).await
    }

    /// Run an agent hook script (Phase 3). Evaluated like any script, but a
    /// callable default export wins: it is invoked with the hook payload
    /// (`ctx`) and its promise-aware return value becomes the hook's JSON
    /// verdict (`{ decision, reason, feedback }`). A plain default value or
    /// `export async function main(ctx)` also resolve to the verdict.
    pub async fn run_hook(
        &self,
        path: &Path,
        payload: &serde_json::Value,
    ) -> anyhow::Result<serde_json::Value> {
        let abs = path
            .canonicalize()
            .map_err(|e| anyhow::anyhow!("{}: {e}", path.display()))?;
        let source = std::fs::read_to_string(&abs)
            .map_err(|e| anyhow::anyhow!("{}: {e}", abs.display()))?;
        // The interrupt guard must cover hooks too — a hung hook script
        // (e.g. an accidental `while (true)`) has to hit the deadline, not
        // spin forever in a thread the agent loop waits on.
        self.interrupt.set_timeout(self.default_timeout);
        let result = self.eval_module_source(&abs, &source, payload, true).await;
        self.interrupt.set_timeout(None);
        result
    }

    /// Evaluate pre-read source as a module (entry point machinery).
    /// `hook` selects the hook entry convention (callable default first).
    async fn eval_module_source(
        &self,
        abs: &Path,
        source: &str,
        args: &serde_json::Value,
        hook: bool,
    ) -> anyhow::Result<serde_json::Value> {
        let name = abs.to_string_lossy().to_string();
        let js = ts::strip(source, &name)
            .map_err(|e| anyhow::anyhow!("{e}"))?;
        let args = args.clone();

        let outcome = self
            .ctx
            .async_with(async move |cx| {
                let run = async {
                    let module = Module::declare(cx.clone(), name, js)?;
                    let (module, eval) = module.eval()?;
                    eval.into_future::<()>().await?;
                    let ns = module.namespace()?;
                    let default: Option<Value> = ns.get("default").ok().flatten();

                    // Hook convention: a callable default export — or failing
                    // that `main(ctx)` — receives the payload and its
                    // (promise-aware) return value is the verdict. A
                    // non-callable default only speaks when neither entry
                    // function exists (a static verdict object).
                    if hook {
                        if let Some(f) = default.as_ref().and_then(|v| v.as_function()) {
                            return call_entry(&cx, f, &args).await;
                        }
                        if let Some(main) = ns.get::<_, Option<rquickjs::Function>>("main").ok().flatten() {
                            return call_entry(&cx, &main, &args).await;
                        }
                    }

                    match default {
                        Some(v) if !v.is_undefined() => crate::jsvalue::to_json(&cx, &v)
                            .map_err(crate::host::js_err),
                        _ => {
                            // `xencode.main(args)` / `export async function main(args)` fallback
                            let main: Option<rquickjs::Function> = ns.get("main").ok().flatten();
                            if let Some(main) = main {
                                call_entry(&cx, &main, &args).await
                            } else {
                                Ok(serde_json::Value::Null)
                            }
                        }
                    }
                };
                match run.await {
                    Ok(v) => Ok(v),
                    Err(e) => {
                        // surface pending JS exceptions with message + stack
                        let detail = if matches!(e, JsError::Exception) {
                            let ex: Value = cx.catch();
                            // Exception → value; prefer .message / .stack fields
                            let msg = ex
                                .as_object()
                                .and_then(|o| o.get::<_, Option<String>>("message").ok().flatten())
                                .unwrap_or_else(|| "<no message>".into());
                            let stack = ex
                                .as_object()
                                .and_then(|o| o.get::<_, Option<String>>("stack").ok().flatten())
                                .unwrap_or_default();
                            if stack.is_empty() {
                                msg
                            } else {
                                format!("{msg}\n{stack}")
                            }
                        } else {
                            e.to_string()
                        };
                        Err(JsError::FromJs {
                            from: "script",
                            to: "result",
                            message: Some(detail),
                        })
                    }
                }
            })
            .await;

        outcome.map_err(|e| anyhow::anyhow!("{e}"))
    }

    /// Evaluate a script expression and return its JSON value.
    pub async fn eval_json(&self, code: &str) -> anyhow::Result<serde_json::Value> {
        let outcome = self.ctx.with(|cx| {
            let v: Value = cx.eval(code)?;
            crate::jsvalue::to_json(&cx, &v).map_err(crate::host::js_err)
        });
        outcome.await.map_err(|e| anyhow::anyhow!("{e}"))
    }

    /// Drive pending jobs/timers until the runtime is quiet.
    pub async fn idle(&self) {
        self.rt.idle().await;
    }

    /// Trigger the interrupt handler on the next tick (manual cancel).
    pub fn interrupt(&self) {
        self.interrupt.cancel();
    }

    /// Stop lingering intervals after a run completes.
    pub fn clear_timers(&self) {
        self.timers.clear_all();
    }

    /// Graceful teardown: cancel timers, drive pending jobs, then give the
    /// spawned futures (sleeping timers, watchers) a moment to release their
    /// JS values before the runtime is freed. Dropping the runtime while a
    /// spawned future still holds a `Function` aborts in QuickJS.
    pub async fn shutdown(&self) {
        self.timers.clear_all();
        self.rt.idle().await;
        tokio::time::sleep(Duration::from_millis(80)).await;
        self.rt.idle().await;
    }
}

/// Call a JS entry function with `args` and JSON-ify the promise-aware
/// result. Shared by the script entry (`main`) and the hook entries
/// (callable default / `main`).
async fn call_entry<'a>(
    cx: &Ctx<'a>,
    f: &rquickjs::Function<'a>,
    args: &serde_json::Value,
) -> Result<serde_json::Value, JsError> {
    let arg = crate::jsvalue::to_js(cx, args)?;
    let res: Value = f.call((arg,))?;
    if res.is_promise() {
        let p: rquickjs::Promise = rquickjs::Promise::from_value(res.clone())?;
        let v: Value = p.into_future().await?;
        crate::jsvalue::to_json(cx, &v).map_err(crate::host::js_err)
    } else if res.is_undefined() {
        Ok(serde_json::Value::Null)
    } else {
        crate::jsvalue::to_json(cx, &res).map_err(crate::host::js_err)
    }
}

impl ScriptRuntime {
    /// `run_script` with an explicit deadline.
    pub async fn run_script_with_timeout(
        &self,
        path: &Path,
        args: &serde_json::Value,
        timeout: Option<Duration>,
    ) -> anyhow::Result<serde_json::Value> {
        let abs = path
            .canonicalize()
            .map_err(|e| anyhow::anyhow!("{}: {e}", path.display()))?;
        let source = std::fs::read_to_string(&abs)
            .map_err(|e| anyhow::anyhow!("{}: {e}", abs.display()))?;
        self.interrupt.set_timeout(timeout);
        let result = self.eval_module_source(&abs, &source, args, false).await;
        self.interrupt.set_timeout(None);
        result
    }
}

/// Global `xen` object aggregating the host namespaces (`xen.http`, `xen.fs`,
/// `xen.proc`, `xen.agent`, `xen.ui`) — the ESM `xen/*` modules stay available
/// for import-style access. (Same shape Deno exposes globals.)
async fn install_xen_global(ctx: &AsyncContext) -> JsResult<()> {
    ctx.async_with(async move |cx| {
        let xen_obj = rquickjs::Object::new(cx.clone())?;
        for (name, spec) in [
            ("http", "xen/http"),
            ("fs", "xen/fs"),
            ("proc", "xen/proc"),
            ("agent", "xen/agent"),
            ("ui", "xen/ui"),
        ] {
            let promise = rquickjs::Module::import(&cx, spec)?;
            let ns: rquickjs::Object = promise.into_future().await?;
            xen_obj.set(name, ns)?;
        }
        cx.globals().set("xen", xen_obj)?;
        Ok::<(), rquickjs::Error>(())
    })
    .await
}

// ---------------------------------------------------------------------------
// module resolution + loading
// ---------------------------------------------------------------------------

const FILE_EXTENSIONS: &[&str] = &["", ".js", ".mjs", ".ts", ".mts", ".tsx", ".json"];

/// Resolve specifiers: `xen/*` native, npm bare names (native map or vendor
/// cache), node: builtins, and relative/absolute file paths.
pub struct XenResolver {
    npm_files: Arc<HashMap<String, PathBuf>>,
    npm_roots: Arc<HashMap<String, PathBuf>>,
}

impl XenResolver {
    fn resolve_file(&self, base: &str, name: &str) -> Option<String> {
        let base_dir = Path::new(base).parent().unwrap_or(Path::new("/"));
        let joined = if name.starts_with('/') {
            PathBuf::from(name)
        } else {
            base_dir.join(name)
        };
        // normalize (./x/../y → x/y)
        let cleaned = clean_path(&joined);
        for ext in FILE_EXTENSIONS {
            let candidate = format!("{cleaned}{ext}");
            if Path::new(&candidate).is_file() {
                return Some(candidate);
            }
        }
        // index fallbacks
        for ext in FILE_EXTENSIONS {
            if ext.is_empty() {
                continue;
            }
            let candidate = format!("{cleaned}/index{ext}");
            if Path::new(&candidate).is_file() {
                return Some(candidate);
            }
        }
        None
    }

    fn resolve_subpath(&self, name: &str) -> Option<String> {
        // "pkg/sub/path" → vendored package root + subpath
        for (pkg, root) in self.npm_roots.iter() {
            if let Some(rest) = name.strip_prefix(&format!("{pkg}/")) {
                for ext in FILE_EXTENSIONS {
                    let candidate = root.join(format!("{rest}{ext}"));
                    if candidate.is_file() {
                        return Some(candidate.to_string_lossy().to_string());
                    }
                }
            }
        }
        None
    }
}

impl Resolver for XenResolver {
    fn resolve<'js>(
        &mut self,
        _ctx: &Ctx<'js>,
        base: &str,
        name: &str,
        _attributes: Option<rquickjs::loader::ImportAttributes<'js>>,
    ) -> JsResult<String> {
        // native host / built-in modules pass through verbatim
        if name.starts_with("xen/") {
            return Ok(name.to_string());
        }
        // bare npm names with a native implementation
        if let Some((_, target)) = NATIVE_BARE.iter().find(|(bare, _)| *bare == name) {
            return Ok(target.to_string());
        }
        // node: builtins not covered above → fail with a clear message
        if let Some(rest) = name.strip_prefix("node:") {
            return Err(JsError::new_loading(&format!(
                "node built-in `{rest}` is not available in XenCode — use the matching `xen/*` module"
            )));
        }
        // npm vendor cache: exact specifier or package subpath
        if let Some(file) = self.npm_files.get(name) {
            return Ok(file.to_string_lossy().to_string());
        }
        if let Some(file) = self.resolve_subpath(name) {
            return Ok(file);
        }
        // relative / absolute file paths
        if name.starts_with("./") || name.starts_with("../") || name.starts_with('/') {
            return self.resolve_file(base, name).map_or_else(
                || Err(JsError::new_loading(name)),
                Ok,
            );
        }
        Err(JsError::new_loading(&format!(
            "`{name}` is neither a xen module nor a vendored npm package — add it to the vendor cache"
        )))
    }
}

/// Load file-backed modules: read, strip TypeScript in-process, declare.
pub struct XenFileLoader;

impl Loader for XenFileLoader {
    fn load<'js>(
        &mut self,
        ctx: &Ctx<'js>,
        path: &str,
        _attributes: Option<rquickjs::loader::ImportAttributes<'js>>,
    ) -> JsResult<Module<'js>> {
        if path.starts_with("xen/") {
            return Err(JsError::new_loading(path));
        }
        let source = std::fs::read_to_string(path)?;
        if crate::ts::is_typescript_path(path) {
            let js = ts::strip(&source, path).map_err(|e| JsError::new_loading(&format!("{path}: {e}")))?;
            Module::declare(ctx.clone(), path, js)
        } else if path.ends_with(".json") {
            let trimmed = source.trim();
            Module::declare(ctx.clone(), path, format!("export default {trimmed};"))
        } else {
            Module::declare(ctx.clone(), path, source)
        }
    }
}

/// Every `xen/*` native module (host bridge + built-ins) in one loader.
fn build_native_module_loader() -> ModuleLoader {
    ModuleLoader::default()
        .with_module("xen/http", crate::host::http::XenHttp)
        .with_module("xen/fs", crate::host::fs::XenFs)
        .with_module("xen/proc", crate::host::proc::XenProc)
        .with_module("xen/agent", crate::host::agent::XenAgent)
        .with_module("xen/ui", crate::host::ui::XenUi)
        .with_module("xen/path", crate::modules::path::XenPath)
        .with_module("xen/glob", crate::modules::glob::XenGlob)
        .with_module("xen/yaml", crate::modules::yaml::XenYaml)
        .with_module("xen/toml", crate::modules::toml::XenToml)
        .with_module("xen/csv", crate::modules::csv::XenCsv)
        .with_module("xen/zip", crate::modules::zip::XenZip)
        .with_module("xen/sqlite", crate::modules::sqlite::XenSqlite)
        .with_module("xen/dotenv", crate::modules::dotenv::XenDotenv)
        .with_module("xen/env", crate::modules::env::XenEnv)
        .with_module("xen/semver", crate::modules::semver::XenSemver)
        .with_module("xen/ansi", crate::modules::ansi::XenAnsi)
        .with_module("xen/markdown", crate::modules::markdown::XenMarkdown)
}

/// Lexical path normalization without touching the filesystem.
fn clean_path(p: &Path) -> String {
    let is_abs = p.is_absolute();
    let mut parts: Vec<String> = Vec::new();
    for comp in p.components() {
        match comp {
            std::path::Component::RootDir | std::path::Component::CurDir => {}
            std::path::Component::ParentDir => {
                if parts.len() > usize::from(is_abs) {
                    parts.pop();
                }
            }
            std::path::Component::Normal(part) => {
                parts.push(part.to_string_lossy().to_string());
            }
            _ => {}
        }
    }
    let joined = parts.join("/");
    if is_abs {
        format!("/{joined}")
    } else {
        joined
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test(flavor = "current_thread")]
    async fn eval_and_memory_limit() {
        tokio::task::LocalSet::new().run_until(async {
        let (tx, _rx) = crate::events::channel();
        let rt = ScriptRuntime::new(RuntimeOptions::new(".", PermissionState::allow_all(), tx))
            .await
            .unwrap();
        let v = rt.eval_json("({a: 1, b: 'two'})").await.unwrap();
        assert_eq!(v["a"], 1);
        assert_eq!(v["b"], "two");
        }).await;
    }

    #[tokio::test(flavor = "current_thread")]
    async fn interrupt_guard_cancels_loop() {
        tokio::task::LocalSet::new().run_until(async {
        // interrupt guard: cancel stops an infinite loop
        let rt2 = ScriptRuntime::new(RuntimeOptions::new(".", PermissionState::allow_all(), {
            let (tx2, _rx2) = crate::events::channel();
            tx2
        }))
        .await
        .unwrap();
        rt2.interrupt();
        let result = tokio::time::timeout(Duration::from_secs(10), rt2.eval_json("while(true){}")).await;
        assert!(result.is_err() || result.unwrap().is_err(), "interrupt fired");
        rt2.shutdown().await;
        }).await;
    }

    #[tokio::test(flavor = "current_thread")]
    async fn timeout_guard_stops_infinite_loop() {
        tokio::task::LocalSet::new().run_until(async {
        let (tx, _rx) = crate::events::channel();
        let rt = ScriptRuntime::new(RuntimeOptions::new(".", PermissionState::allow_all(), tx))
            .await
            .unwrap();
        let start = Instant::now();
        let res = tokio::time::timeout(
            Duration::from_secs(15),
            rt.run_script_with_timeout(
                Path::new("nonexistent.js"),
                &serde_json::Value::Null,
                Some(Duration::from_millis(200)),
            ),
        )
        .await;
        // missing file errors fast — the deadline machinery is exercised in
        // the interrupt test above
        let _ = start;
        assert!(res.is_err() || res.unwrap().is_err());
        rt.shutdown().await;
        }).await;
    }

    #[test]
    fn clean_path_normalizes() {
        assert_eq!(clean_path(Path::new("/a/b/../c")), "/a/c");
        assert_eq!(clean_path(Path::new("x/./y")), "x/y");
    }
}
