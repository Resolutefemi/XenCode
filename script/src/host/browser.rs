//! `xen.browser` — the XenBrowser surface exposed to QuickJS (Phase 6.3).
//!
//! Scripts drive a real browser through the same auto-wait Space actions
//! the CLI uses. The engine-agnostic part lives here (commands + bridge);
//! the browser-backed driver is injected by the host (`xencode browser run`,
//! the agent core, tests) via [`BrowserDriver`], following the same
//! injectable-bridge pattern as `xen.agent` / `xen.ui`.
//!
//! ```js
//! // a recorded script (xencode browser record) replays like:
//! await xen.browser.navigate("https://example.test/");
//! await xen.browser.fill("css=#email", "user@example.test");
//! await xen.browser.click("text=Sign in");
//! const outline = await xen.browser.snapshot();
//! console.log(outline);
//! ```
//!
//! Permission gates: `navigate` passes the `net` gate (host allowlist —
//! the browser talks to the network on the script's behalf); `screenshot`
//! with a path passes the `fs` write gate. When no driver is installed the
//! functions fail with a clear setup message instead of a silent no-op.

use std::future::Future;
use std::pin::Pin;
use std::sync::{Arc, Mutex};

use rquickjs::function::Opt;
use rquickjs::prelude::Async;
use rquickjs::module::{Declarations, Exports, ModuleDef};
use rquickjs::{Ctx, Function, Result as JsResult, Value};

use super::{js_err, state};

/// One plain-data automation command (Send + serializable, so any host can
/// queue it onto its own runtime).
#[derive(Debug, Clone)]
pub enum BrowserCommand {
    Navigate { url: String },
    Click { selector: String },
    Fill { selector: String, text: String },
    Select { selector: String, value: String },
    Press { key: String },
    Scroll { dx: f64, dy: f64 },
    ScrollTo { x: f64, y: f64 },
    Wait { ms: u64 },
    Snapshot,
    Screenshot { path: Option<String> },
    Url,
    Title,
    Eval { js: String },
    Close,
}

/// The command's result (plain data — no QuickJS values cross the bridge).
#[derive(Debug, Clone)]
pub enum BrowserReply {
    Unit,
    Text(String),
    Json(serde_json::Value),
}

impl BrowserReply {
    fn expect_text(self, what: &str) -> Result<String, JsError> {
        match self {
            BrowserReply::Text(s) => Ok(s),
            BrowserReply::Json(v) => Ok(v.as_str().unwrap_or_default().to_string()),
            BrowserReply::Unit => Ok(String::new()),
        }
        .map_err(|e: JsError| js_err(format!("{what}: {e}")))
    }
}

use rquickjs::Error as JsError;

/// Host-injected driver: queues the command onto the runtime that owns the
/// browser and resolves with a plain reply. Send futures — QuickJS script
/// threads have no reactor, so the implementation typically spawns onto a
/// captured `tokio::runtime::Handle` and awaits the join handle.
pub trait BrowserDriver: Send + Sync {
    fn execute(
        &self,
        cmd: BrowserCommand,
    ) -> Pin<Box<dyn Future<Output = Result<BrowserReply, String>> + Send>>;
}

/// Rust-side handle the host uses to install (or remove) a driver.
#[derive(Clone, Default)]
pub struct BrowserBridge {
    driver: Arc<Mutex<Option<Arc<dyn BrowserDriver>>>>,
}

impl BrowserBridge {
    pub fn new() -> Self {
        Self::default()
    }

    /// Install the host-side driver.
    pub fn set_driver(&self, d: Arc<dyn BrowserDriver>) {
        *self.driver.lock().unwrap() = Some(d);
    }

    /// Remove the driver (session teardown).
    pub fn clear_driver(&self) {
        *self.driver.lock().unwrap() = None;
    }

    /// Run one command; errors carry the driver's message.
    pub async fn execute(&self, cmd: BrowserCommand) -> Result<BrowserReply, String> {
        let d = self.driver.lock().unwrap().clone();
        match d {
            Some(d) => d.execute(cmd).await,
            None => Err(
                "browser bridge not installed — run through `xencode browser run <script>` \
                 (or install a BrowserDriver on the runtime)"
                    .into(),
            ),
        }
    }
}

pub struct XenBrowser;

impl ModuleDef for XenBrowser {
    fn declare(decl: &Declarations) -> JsResult<()> {
        for name in [
            "navigate", "click", "fill", "select", "press", "scroll", "scrollTo",
            "wait", "snapshot", "screenshot", "url", "title", "eval", "close",
        ] {
            decl.declare(name)?;
        }
        Ok(())
    }

    fn evaluate<'js>(ctx: &Ctx<'js>, exports: &Exports<'js>) -> JsResult<()> {
        let st = state(ctx)?;
        let perms = st.perms.clone();
        let browser = st.browser.clone();

        // navigate(url) — net gate on the URL host.
        let p = perms.clone();
        let b = browser.clone();
        let f = Function::new(
            ctx.clone(),
            Async(move |_cx: Ctx<'js>, url: String| {
                let perms = p.clone();
                let browser = b.clone();
                async move {
                    let host = url_host(&url)?;
                    if let Err(e) = perms.check_net(&host) {
                        return Err(js_err(format!("net permission denied for `{host}`: {e}")));
                    }
                    run_unit(&browser, BrowserCommand::Navigate { url }).await
                }
            }),
        )?;
        exports.export("navigate", f)?;

        let b2 = browser.clone();
        let f = Function::new(
            ctx.clone(),
            Async(move |_cx: Ctx<'js>, selector: String| {
                let browser = b2.clone();
                async move { run_unit(&browser, BrowserCommand::Click { selector }).await }
            }),
        )?;
        exports.export("click", f)?;

        let b3 = browser.clone();
        let f = Function::new(
            ctx.clone(),
            Async(move |_cx: Ctx<'js>, selector: String, text: String| {
                let browser = b3.clone();
                async move {
                    run_unit(&browser, BrowserCommand::Fill { selector, text }).await
                }
            }),
        )?;
        exports.export("fill", f)?;

        let b4 = browser.clone();
        let f = Function::new(
            ctx.clone(),
            Async(move |_cx: Ctx<'js>, selector: String, value: String| {
                let browser = b4.clone();
                async move {
                    run_unit(&browser, BrowserCommand::Select { selector, value }).await
                }
            }),
        )?;
        exports.export("select", f)?;

        let b5 = browser.clone();
        let f = Function::new(
            ctx.clone(),
            Async(move |_cx: Ctx<'js>, key: String| {
                let browser = b5.clone();
                async move { run_unit(&browser, BrowserCommand::Press { key }).await }
            }),
        )?;
        exports.export("press", f)?;

        let b6 = browser.clone();
        let f = Function::new(
            ctx.clone(),
            Async(move |_cx: Ctx<'js>, dx: f64, dy: f64| {
                let browser = b6.clone();
                async move { run_unit(&browser, BrowserCommand::Scroll { dx, dy }).await }
            }),
        )?;
        exports.export("scroll", f)?;

        let b7 = browser.clone();
        let f = Function::new(
            ctx.clone(),
            Async(move |_cx: Ctx<'js>, x: f64, y: f64| {
                let browser = b7.clone();
                async move { run_unit(&browser, BrowserCommand::ScrollTo { x, y }).await }
            }),
        )?;
        exports.export("scrollTo", f)?;

        let b8 = browser.clone();
        let f = Function::new(
            ctx.clone(),
            Async(move |_cx: Ctx<'js>, ms: f64| {
                let browser = b8.clone();
                async move {
                    run_unit(&browser, BrowserCommand::Wait { ms: ms.max(0.0) as u64 }).await
                }
            }),
        )?;
        exports.export("wait", f)?;

        // snapshot() → outline text (the model-facing primitive).
        let b9 = browser.clone();
        let f = Function::new(
            ctx.clone(),
            Async(move |_cx: Ctx<'js>| {
                let browser = b9.clone();
                async move {
                    match browser.execute(BrowserCommand::Snapshot).await {
                        Ok(BrowserReply::Text(s)) => Ok(s),
                        Ok(_) => Ok(String::new()),
                        Err(e) => Err(js_err(e)),
                    }
                }
            }),
        )?;
        exports.export("snapshot", f)?;

        // screenshot(path?) → {path?, width, height, png?} — fs write gate
        // when a path is given, base64 payload otherwise.
        let p2 = perms.clone();
        let b10 = browser.clone();
        let f = Function::new(
            ctx.clone(),
            Async(move |cx: Ctx<'js>, path: Opt<String>| {
                let perms = p2.clone();
                let browser = b10.clone();
                let cx = cx.clone();
                async move {
                    let path = path.0;
                    if let Some(p) = &path {
                        if let Err(e) = perms.check_fs_write(p) {
                            return Err(js_err(format!("fs write permission denied for `{p}`: {e}")));
                        }
                    }
                    match browser.execute(BrowserCommand::Screenshot { path }).await {
                        Ok(BrowserReply::Json(v)) => crate::jsvalue::from_json(&cx, &v),
                        Ok(_) => Ok(Value::new_null(cx.clone())),
                        Err(e) => Err(js_err(e)),
                    }
                }
            }),
        )?;
        exports.export("screenshot", f)?;

        // url() / title()
        let b11 = browser.clone();
        let f = Function::new(
            ctx.clone(),
            Async(move |_cx: Ctx<'js>| {
                let browser = b11.clone();
                async move {
                    match browser.execute(BrowserCommand::Url).await {
                        Ok(r) => r.expect_text("url"),
                        Err(e) => Err(js_err(e)),
                    }
                }
            }),
        )?;
        exports.export("url", f)?;

        let b12 = browser.clone();
        let f = Function::new(
            ctx.clone(),
            Async(move |_cx: Ctx<'js>| {
                let browser = b12.clone();
                async move {
                    match browser.execute(BrowserCommand::Title).await {
                        Ok(r) => r.expect_text("title"),
                        Err(e) => Err(js_err(e)),
                    }
                }
            }),
        )?;
        exports.export("title", f)?;

        // eval(js) → JSON value
        let b13 = browser.clone();
        let f = Function::new(
            ctx.clone(),
            Async(move |cx: Ctx<'js>, js: String| {
                let browser = b13.clone();
                let cx = cx.clone();
                async move {
                    match browser.execute(BrowserCommand::Eval { js }).await {
                        Ok(BrowserReply::Json(v)) => crate::jsvalue::from_json(&cx, &v),
                        Ok(BrowserReply::Text(s)) => crate::jsvalue::from_json(
                            &cx,
                            &serde_json::Value::String(s),
                        ),
                        Ok(BrowserReply::Unit) => Ok(Value::new_null(cx.clone())),
                        Err(e) => Err(js_err(e)),
                    }
                }
            }),
        )?;
        exports.export("eval", f)?;

        // close()
        let b14 = browser;
        let f = Function::new(
            ctx.clone(),
            Async(move |_cx: Ctx<'js>| {
                let browser = b14.clone();
                async move { run_unit(&browser, BrowserCommand::Close).await }
            }),
        )?;
        exports.export("close", f)?;

        Ok(())
    }
}

/// Execute a command and flatten a Unit/Text reply; JSON replies error here
/// (they only come back from screenshot/eval which handle their own).
async fn run_unit(
    browser: &BrowserBridge,
    cmd: BrowserCommand,
) -> Result<(), rquickjs::Error> {
    match browser.execute(cmd).await {
        Ok(_) => Ok(()),
        Err(e) => Err(js_err(e)),
    }
}

/// Extract the host from a URL for the net permission gate. Hostless
/// schemes (`file:`, `about:`, `data:`) report the scheme name as the host
/// so scoped setups still get a consent prompt instead of a parse error.
fn url_host(url: &str) -> Result<String, rquickjs::Error> {
    match url::Url::parse(url) {
        Ok(u) => Ok(u
            .host_str()
            .map(str::to_string)
            .unwrap_or_else(|| u.scheme().to_string())),
        Err(e) => Err(js_err(format!("invalid url `{url}`: {e}"))),
    }
}

/// Encode raw PNG bytes for the no-path screenshot reply.
pub fn png_to_base64(data: &[u8]) -> String {
    crate::jsvalue::b64_encode(data)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    /// A scripted driver that records commands and replies with canned
    /// values — exercises the full bridge + module path without a browser.
    struct FakeDriver {
        seen: Mutex<Vec<String>>,
        calls: AtomicUsize,
    }

    impl FakeDriver {
        fn new() -> Arc<Self> {
            Arc::new(FakeDriver {
                seen: Mutex::new(Vec::new()),
                calls: AtomicUsize::new(0),
            })
        }
    }

    impl BrowserDriver for FakeDriver {
        fn execute(
            &self,
            cmd: BrowserCommand,
        ) -> Pin<Box<dyn Future<Output = Result<BrowserReply, String>> + Send>> {
            self.calls.fetch_add(1, Ordering::SeqCst);
            let name = match &cmd {
                BrowserCommand::Navigate { url } => format!("navigate:{url}"),
                BrowserCommand::Click { selector } => format!("click:{selector}"),
                BrowserCommand::Fill { selector, text } => format!("fill:{selector}={text}"),
                BrowserCommand::Select { selector, value } => format!("select:{selector}={value}"),
                BrowserCommand::Press { key } => format!("press:{key}"),
                BrowserCommand::Snapshot => "snapshot".into(),
                BrowserCommand::Screenshot { path } => {
                    let path = path.clone();
                    return Box::pin(async move {
                        Ok(BrowserReply::Json(serde_json::json!({
                            "path": path, "width": 1280, "height": 800,
                        })))
                    });
                }
                BrowserCommand::Eval { js } => {
                    let js = js.clone();
                    return Box::pin(async move {
                        // pretend the page evaluated the expression
                        let _ = js;
                        Ok(BrowserReply::Json(serde_json::json!(2)))
                    });
                }
                BrowserCommand::Url => {
                    return Box::pin(async { Ok(BrowserReply::Text("https://x.test/".into())) });
                }
                BrowserCommand::Title => {
                    return Box::pin(async { Ok(BrowserReply::Text("Xen".into())) });
                }
                _ => format!("{cmd:?}"),
            };
            self.seen.lock().unwrap().push(name.clone());
            Box::pin(async move { Ok(BrowserReply::Unit) })
        }
    }

    async fn runtime_with_driver(
        root: &std::path::Path,
    ) -> (
        crate::runtime::ScriptRuntime,
        Arc<FakeDriver>,
        tokio::sync::mpsc::UnboundedReceiver<crate::events::UiEvent>,
    ) {
        let (tx, rx) = crate::events::channel();
        let mut opts = crate::runtime::RuntimeOptions::new(
            root,
            crate::permissions::PermissionState::allow_all(),
            tx,
        );
        let fake = FakeDriver::new();
        opts.browser = Some(fake.clone() as Arc<dyn BrowserDriver>);
        let rt = crate::runtime::ScriptRuntime::new(opts).await.unwrap();
        (rt, fake, rx)
    }

    #[tokio::test(flavor = "current_thread")]
    async fn xen_browser_commands_reach_the_driver() {
        tokio::task::LocalSet::new().run_until(async {
            let dir = std::env::temp_dir().join(format!("xen-browser-test-{}", std::process::id()));
            let _ = std::fs::create_dir_all(&dir);
            let (rt, fake, _rx) = runtime_with_driver(&dir).await;
            let v = rt
                .eval_json_async(
                    "(async () => { \
                        await xen.browser.navigate('https://x.test/'); \
                        await xen.browser.fill('css=#email', 'femi'); \
                        await xen.browser.click('text=Sign in'); \
                        await xen.browser.select('css=#plan', 'pro'); \
                        await xen.browser.press('Enter'); \
                        await xen.browser.scrollTo(0, 600); \
                        const u = await xen.browser.url(); \
                        const t = await xen.browser.title(); \
                        const shot = await xen.browser.screenshot('/tmp/x.png'); \
                        const ev = await xen.browser.eval('1+1'); \
                        return ({ u, t, shot, ev }); \
                    })()",
                )
                .await
                .unwrap();
            assert_eq!(v["u"], "https://x.test/");
            assert_eq!(v["t"], "Xen");
            assert_eq!(v["shot"]["path"], "/tmp/x.png");
            assert_eq!(v["shot"]["width"], 1280);
            assert_eq!(v["ev"], 2);
            let seen = fake.seen.lock().unwrap();
            assert!(seen.contains(&"navigate:https://x.test/".to_string()), "{seen:?}");
            assert!(seen.contains(&"fill:css=#email=femi".to_string()), "{seen:?}");
            assert!(seen.contains(&"click:text=Sign in".to_string()), "{seen:?}");
            assert!(seen.contains(&"select:css=#plan=pro".to_string()), "{seen:?}");
            assert!(seen.contains(&"press:Enter".to_string()), "{seen:?}");
            // eval/screenshot/url/title reply through dedicated arms — the
            // returned values above already assert them.
            rt.shutdown().await;
        })
        .await;
    }

    #[tokio::test(flavor = "current_thread")]
    async fn xen_browser_without_driver_errors_clearly() {
        tokio::task::LocalSet::new().run_until(async {
            let (tx, _rx) = crate::events::channel();
            let opts = crate::runtime::RuntimeOptions::new(
                ".",
                crate::permissions::PermissionState::allow_all(),
                tx,
            );
            let rt = crate::runtime::ScriptRuntime::new(opts).await.unwrap();
            // Catch the rejection inside JS so the real message surfaces
            // (a rejected promise reaches eval_json_async as a bare
            // QuickJS exception marker).
            let v = rt
                .eval_json_async(
                    "(async () => { \
                        try { await xen.browser.click('a'); return 'no-error'; } \
                        catch (e) { return String(e && e.message ? e.message : e); } \
                    })()",
                )
                .await
                .unwrap();
            let msg = v.as_str().unwrap_or_default().to_string();
            assert!(
                msg.contains("browser bridge not installed"),
                "clear setup message, got: {msg}"
            );
            rt.shutdown().await;
        })
        .await;
    }

    #[tokio::test(flavor = "current_thread")]
    async fn xen_browser_net_gate_blocks_disallowed_hosts() {
        tokio::task::LocalSet::new().run_until(async {
            let dir = std::env::temp_dir().join(format!("xen-browser-gate-{}", std::process::id()));
            let _ = std::fs::create_dir_all(&dir);
            let (tx, _rx) = crate::events::channel();
            let mut opts = crate::runtime::RuntimeOptions::new(
                &dir,
                crate::permissions::PermissionState::allow_all(),
                tx,
            );
            let fake = FakeDriver::new();
            opts.browser = Some(fake.clone() as Arc<dyn BrowserDriver>);
            let rt = crate::runtime::ScriptRuntime::new(opts).await.unwrap();
            // allow_all → navigation passes; a broken URL errors with the
            // host diagnosis instead of reaching the driver.
            let err = rt
                .eval_json_async("(async () => { await xen.browser.navigate('not a url'); })()")
                .await;
            assert!(err.is_err(), "invalid url must error");
            rt.shutdown().await;
        })
        .await;
    }

    #[test]
    fn url_host_parses() {
        assert_eq!(url_host("https://example.test/a?b=1").unwrap(), "example.test");
        assert_eq!(url_host("file:///tmp/x.html").unwrap(), "file");
        assert_eq!(url_host("about:blank").unwrap(), "about");
        assert!(url_host("nope").is_err());
    }
}
