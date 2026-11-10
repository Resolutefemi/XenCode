//! Spaces — one isolated browser context per agent task, with auto-wait
//! actions, snapshots and a screencast stream for the GUI tab.
//!
//! A Space is a full Chromium process with its own user-data-dir:
//! - [`SpaceKind::CleanRoom`] — a throwaway profile under the temp dir,
//!   removed when the Space drops. Perfect for scraping and CI.
//! - [`SpaceKind::Inherit`] — a copy of an existing profile (cookies,
//!   session storage), with caches and `Singleton*` locks skipped. Perfect
//!   for driving logged-in state. (Encrypted-cookie portability across
//!   machines depends on the OS keyring — same user/machine works.)
//!
//! Parallel headless Spaces are just multiple `Space` values — separate
//! processes, no GIL, no shared state.

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use anyhow::{anyhow, Context, Result};
use serde::Deserialize;
use serde_json::{json, Value};
use tokio::sync::watch;

use crate::cdp::{Connection, Event};
use crate::diff::{diff_snapshots, SnapshotDiff};
use crate::discover;
use crate::frame::{decode_frame, Frame};
use crate::launch::{remove_dir_best_effort, spawn_browser, LaunchSpec};
use crate::snapshot::{parse_snapshot, snapshot_outline, Snapshot, OUTLINE_BUDGET};

pub const CALL_TIMEOUT: Duration = Duration::from_secs(10);
pub const NAV_TIMEOUT: Duration = Duration::from_secs(20);
pub const AUTOWAIT_TIMEOUT: Duration = Duration::from_secs(5);
const POLL_INTERVAL: Duration = Duration::from_millis(120);

/// Profile strategy for a new Space.
#[derive(Debug, Clone)]
pub enum SpaceKind {
    /// Throwaway temp profile, wiped when the Space drops.
    CleanRoom,
    /// Copy an existing user-data-dir (cookies + sessions, no caches).
    Inherit { profile: PathBuf },
}

#[derive(Debug, Clone)]
pub struct SpaceOptions {
    pub kind: SpaceKind,
    pub headless: bool,
    pub viewport: (u32, u32),
    /// Explicit browser binary; discovered when `None`.
    pub browser_path: Option<PathBuf>,
    pub extra_args: Vec<String>,
}

impl Default for SpaceOptions {
    fn default() -> Self {
        SpaceOptions {
            kind: SpaceKind::CleanRoom,
            headless: true,
            viewport: crate::DEFAULT_VIEWPORT,
            browser_path: None,
            extra_args: Vec::new(),
        }
    }
}

/// How to locate an element. `"text=Sign in"`, `"xpath=//nav[1]"`, anything
/// else is CSS.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Selector {
    Css(String),
    Text(String),
    Xpath(String),
}

impl Selector {
    pub fn parse(raw: &str) -> Self {
        if let Some(rest) = raw.strip_prefix("text=") {
            Selector::Text(rest.to_string())
        } else if let Some(rest) = raw.strip_prefix("xpath=") {
            Selector::Xpath(rest.to_string())
        } else if let Some(rest) = raw.strip_prefix("css=") {
            Selector::Css(rest.to_string())
        } else {
            Selector::Css(raw.to_string())
        }
    }

    fn kind(&self) -> &'static str {
        match self {
            Selector::Css(_) => "css",
            Selector::Text(_) => "text",
            Selector::Xpath(_) => "xpath",
        }
    }

    fn value(&self) -> &str {
        match self {
            Selector::Css(s) | Selector::Text(s) | Selector::Xpath(s) => s,
        }
    }
}

impl std::fmt::Display for Selector {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(f, "{}={}", self.kind(), self.value())
    }
}

/// A resolved element: viewport center + registry token for follow-ups.
#[derive(Debug, Clone, Deserialize)]
pub struct ElemBox {
    pub token: String,
    pub x: f64,
    pub y: f64,
    pub w: f64,
    pub h: f64,
}

/// A live browser Space. Drop = kill the browser, wipe a clean-room profile.
pub struct Space {
    conn: Connection,
    session: String,
    target_id: String,
    child: tokio::process::Child,
    user_data_dir: PathBuf,
    clean: bool,
    viewport: (u32, u32),
    events: tokio::sync::broadcast::Receiver<Event>,
}

impl Drop for Space {
    fn drop(&mut self) {
        // kill_on_drop fires when `child` drops at the end of this scope.
        if self.clean {
            let dir = self.user_data_dir.clone();
            std::thread::spawn(move || remove_dir_best_effort(&dir));
        }
    }
}

impl Space {
    /// Discover a browser, launch it and attach to a fresh page target.
    pub async fn launch(opts: SpaceOptions) -> Result<Space> {
        let browser_path = match opts.browser_path.clone() {
            Some(p) => p,
            None => discover::find_browser()
                .map(|c| c.path)
                .ok_or_else(|| {
                    anyhow!(
                        "no Chrome/Chromium/Edge found — set XENCODE_BROWSER to a browser \
                         binary or install Google Chrome"
                    )
                })?,
        };

        let clean = matches!(opts.kind, SpaceKind::CleanRoom);
        let user_data_dir: PathBuf = match &opts.kind {
            SpaceKind::CleanRoom => {
                let dir = std::env::temp_dir().join(format!(
                    "xencode-space-{}-{}",
                    std::process::id(),
                    chrono_suffix()
                ));
                std::fs::create_dir_all(&dir)
                    .with_context(|| format!("create user-data-dir {}", dir.display()))?;
                dir
            }
            SpaceKind::Inherit { profile } => {
                let dir = std::env::temp_dir().join(format!(
                    "xencode-inherit-{}-{}",
                    std::process::id(),
                    chrono_suffix()
                ));
                std::fs::create_dir_all(&dir)?;
                let copied = copy_profile(profile, &dir)
                    .with_context(|| format!("copying profile {}", profile.display()))?;
                eprintln!(
                    "· space: inherited profile from {} ({} KiB)",
                    profile.display(),
                    copied / 1024
                );
                dir
            }
        };

        let spec = LaunchSpec {
            browser_path,
            user_data_dir: user_data_dir.clone(),
            window: opts.viewport,
            headless: opts.headless,
            extra_args: opts.extra_args,
        };
        let pipes = spawn_browser(&spec).map_err(|e| anyhow!("{e}"))?;

        let (conn, events) = Connection::start(pipes.from_browser, pipes.to_browser);
        let space = Space {
            conn,
            session: String::new(),
            target_id: String::new(),
            child: pipes.child,
            user_data_dir,
            clean,
            viewport: spec.window,
            events,
        };

        // Attach to a fresh page target.
        let res = space
            .call_browser("Target.createTarget", json!({"url": "about:blank"}))
            .await?;
        let target_id = res["targetId"]
            .as_str()
            .ok_or_else(|| anyhow!("createTarget returned no targetId"))?
            .to_string();
        let res = space
            .call_browser(
                "Target.attachToTarget",
                json!({"targetId": target_id, "flatten": true}),
            )
            .await?;
        let session = res["sessionId"]
            .as_str()
            .ok_or_else(|| anyhow!("attachToTarget returned no sessionId"))?
            .to_string();

        let mut space = space;
        space.target_id = target_id;
        space.session = session;
        space.call("Page.enable", json!({})).await?;
        space.call("Runtime.enable", json!({})).await?;
        Ok(space)
    }

    pub fn viewport(&self) -> (u32, u32) {
        self.viewport
    }

    /// Subscribe to the raw event stream (debug probes and advanced use).
    pub fn events_resubscribe(&self) -> tokio::sync::broadcast::Receiver<Event> {
        self.events.resubscribe()
    }

    /// Clone the underlying CDP connection (network capture pump, tools).
    pub fn conn_clone(&self) -> Connection {
        self.conn.clone()
    }

    /// This Space's page-target session id.
    pub fn session_id(&self) -> &str {
        &self.session
    }

    /// Chromium's pid (handy for diagnostics and crash reporting).
    pub fn pid(&self) -> Option<u32> {
        self.child.id()
    }

    /// Browser-level call (no session).
    pub async fn call_browser(&self, method: &str, params: Value) -> Result<Value> {
        self.conn
            .call(method, params, None, CALL_TIMEOUT)
            .await
            .map_err(|e| anyhow!("{e}"))
    }

    /// Session-level call (routed to our page target).
    pub async fn call(&self, method: &str, params: Value) -> Result<Value> {
        self.conn
            .call(method, params, Some(&self.session), CALL_TIMEOUT)
            .await
            .map_err(|e| anyhow!("{e}"))
    }

    /// Navigate and wait for the load event (non-fatal on timeout —
    /// client-rendered apps still become snapshot-able).
    pub async fn navigate(&self, url: &str) -> Result<()> {
        let res = self
            .call("Page.navigate", json!({"url": url}))
            .await
            .context("navigate")?;
        if let Some(err) = res.get("errorText").and_then(Value::as_str) {
            if !err.is_empty() {
                return Err(anyhow!("navigate to {url}: {err}"));
            }
        }
        // loadEventFired watch — bounded, non-fatal.
        let mut events = self.events.resubscribe();
        let deadline = tokio::time::Instant::now() + NAV_TIMEOUT;
        loop {
            let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
            if remaining.is_zero() {
                eprintln!("· space: load event timeout on {url} (continuing)");
                return Ok(());
            }
            match tokio::time::timeout(remaining, events.recv()).await {
                Ok(Ok(ev)) => {
                    if ev.session_id.as_deref() == Some(self.session.as_str())
                        && ev.method == "Page.loadEventFired"
                    {
                        return Ok(());
                    }
                }
                Ok(Err(tokio::sync::broadcast::error::RecvError::Lagged(_))) => continue,
                Ok(Err(_)) | Err(_) => return Ok(()), // closed / timed out
            }
        }
    }

    /// Evaluate a JS expression in the page and return the raw value.
    pub async fn evaluate(&self, expression: &str) -> Result<Value> {
        let res = self
            .call(
                "Runtime.evaluate",
                json!({"expression": expression, "returnByValue": true, "awaitPromise": true}),
            )
            .await
            .context("evaluate")?;
        if let Some(exc) = res.get("exceptionDetails") {
            let text = exc
                .get("exception")
                .and_then(|e| e.get("description"))
                .and_then(Value::as_str)
                .unwrap_or("script threw");
            return Err(anyhow!("evaluate failed: {text}"));
        }
        // Runtime.evaluate returns a RemoteObject — the JS value rides in
        // its `value` field (returnByValue: true).
        let remote = res.get("result").cloned().unwrap_or(Value::Null);
        Ok(remote.get("value").cloned().unwrap_or(Value::Null))
    }

    /// Resolve a selector with auto-wait; `None` when the deadline passes.
    pub async fn resolve(&self, selector: &Selector) -> Result<Option<ElemBox>> {
        let js = resolve_js(selector);
        let deadline = tokio::time::Instant::now() + AUTOWAIT_TIMEOUT;
        loop {
            let v = self.evaluate(&js).await?;
            if let Some(boxed) = serde_json::from_value::<ElemBox>(v).ok() {
                return Ok(Some(boxed));
            }
            if tokio::time::Instant::now() >= deadline {
                return Ok(None);
            }
            tokio::time::sleep(POLL_INTERVAL).await;
        }
    }

    /// Click an element (auto-waits, scrolls into view first).
    pub async fn click(&self, selector: &Selector) -> Result<()> {
        let Some(el) = self.resolve(selector).await? else {
            return Err(anyhow!("click: `{selector}` not found within {:?}", AUTOWAIT_TIMEOUT));
        };
        self.mouse_event("mouseMoved", el.x, el.y).await?;
        self.mouse_pressed(el.x, el.y, "mousePressed").await?;
        self.mouse_pressed(el.x, el.y, "mouseReleased").await?;
        Ok(())
    }

    async fn mouse_event(&self, kind: &str, x: f64, y: f64) -> Result<()> {
        self.call(
            "Input.dispatchMouseEvent",
            json!({"type": kind, "x": x, "y": y, "button": "none", "buttons": 0, "clickCount": 0, "pointerType": "mouse"}),
        )
        .await?;
        Ok(())
    }

    async fn mouse_pressed(&self, x: f64, y: f64, kind: &str) -> Result<()> {
        self.call(
            "Input.dispatchMouseEvent",
            json!({"type": kind, "x": x, "y": y, "button": "left", "buttons": if kind == "mousePressed" { 1 } else { 0 }, "clickCount": 1, "pointerType": "mouse"}),
        )
        .await?;
        Ok(())
    }

    /// Focus an element and replace its content with `text` (proper input
    /// events, so React/Vue state follows).
    pub async fn fill(&self, selector: &Selector, text: &str) -> Result<()> {
        let Some(el) = self.resolve(selector).await? else {
            return Err(anyhow!("fill: `{selector}` not found within {:?}", AUTOWAIT_TIMEOUT));
        };
        // Click to focus, then clear through the registry token.
        self.mouse_event("mouseMoved", el.x, el.y).await?;
        self.mouse_pressed(el.x, el.y, "mousePressed").await?;
        self.mouse_pressed(el.x, el.y, "mouseReleased").await?;
        let clear = format!(
            "(() => {{ const el = (window.__xen && window.__xen.n.get('{}')) || null; \
             if (!el) return false; el.focus(); \
             if (el instanceof HTMLInputElement || el instanceof HTMLTextAreaElement) {{ \
                 el.value = ''; el.setSelectionRange(0, 0); }} \
             else if (el.isContentEditable) {{ el.textContent = ''; }} \
             return true; }})()",
            el.token
        );
        let ok = self.evaluate(&clear).await?;
        if ok != Value::Bool(true) {
            return Err(anyhow!("fill: element vanished before the input landed"));
        }
        self.call("Input.insertText", json!({"text": text})).await?;
        Ok(())
    }

    /// Press a key (`Enter`, `Tab`, `Escape`, `ArrowDown`, `a`, …).
    pub async fn press(&self, key: &str) -> Result<()> {
        let spec = key_spec(key)?;
        if let Some(text) = &spec.text {
            // Printable key: keyDown (with text) → keyUp.
            let down = json!({
                "type": "keyDown", "key": spec.key, "code": spec.code,
                "windowsVirtualKeyCode": spec.vk, "nativeVirtualKeyCode": spec.vk,
                "text": text
            });
            self.call("Input.dispatchKeyEvent", down).await?;
            let up = json!({
                "type": "keyUp", "key": spec.key, "code": spec.code,
                "windowsVirtualKeyCode": spec.vk, "nativeVirtualKeyCode": spec.vk
            });
            self.call("Input.dispatchKeyEvent", up).await?;
        } else {
            let base = json!({
                "key": spec.key, "code": spec.code,
                "windowsVirtualKeyCode": spec.vk, "nativeVirtualKeyCode": spec.vk
            });
            let mut down = base.clone();
            down["type"] = Value::String("rawKeyDown".into());
            self.call("Input.dispatchKeyEvent", down).await?;
            let mut up = base;
            up["type"] = Value::String("keyUp".into());
            self.call("Input.dispatchKeyEvent", up).await?;
        }
        Ok(())
    }

    /// Scroll the viewport (positive dy scrolls down).
    pub async fn scroll(&self, dx: f64, dy: f64) -> Result<()> {
        let (w, h) = self.viewport;
        self.call(
            "Input.dispatchMouseEvent",
            json!({"type": "mouseWheel", "x": w as f64 / 2.0, "y": h as f64 / 2.0, "deltaX": dx, "deltaY": dy, "pointerType": "mouse"}),
        )
        .await?;
        Ok(())
    }

    /// Capture the DOM snapshot (the model-facing primitive).
    pub async fn snapshot(&self) -> Result<Snapshot> {
        let res = self
            .call(
                "DOMSnapshot.captureSnapshot",
                json!({
                    "computedStyles": ["display", "visibility"],
                    "includeDOMRects": true,
                    "includePaintOrder": false
                }),
            )
            .await
            .context("snapshot")?;
        parse_snapshot(&res).map_err(|e| anyhow!("{e}"))
    }

    /// Snapshot + outline in one call: `(text, truncated)`.
    pub async fn outline(&self) -> Result<(String, bool)> {
        let snap = self.snapshot().await?;
        let (text, truncated, _) = snapshot_outline(&snap, OUTLINE_BUDGET);
        Ok((text, truncated))
    }

    /// Diff against a previous snapshot — the deltas are what the model sees.
    pub async fn diff_since(&self, old: &Snapshot) -> Result<SnapshotDiff> {
        let new = self.snapshot().await?;
        Ok(diff_snapshots(old, &new))
    }

    /// Viewport screenshot as a decoded RGBA frame.
    pub async fn screenshot(&self) -> Result<Frame> {
        let res = self
            .call(
                "Page.captureScreenshot",
                json!({"format": "png", "captureBeyondViewport": false}),
            )
            .await
            .context("screenshot")?;
        let data = res["data"]
            .as_str()
            .ok_or_else(|| anyhow!("captureScreenshot returned no data"))?;
        decode_frame(data, "png").map_err(|e| anyhow!("{e}"))
    }

    /// Start the screencast stream (Phase 6.2 GUI tab). Frames keep flowing
    /// until the handle drops (which stops the stream).
    pub async fn start_screencast(&self) -> Result<ScreenCast> {
        self.call(
            "Page.startScreencast",
            json!({
                "format": "jpeg",
                "quality": 70,
                "maxWidth": 1600,
                "maxHeight": 1200,
                "everyNthFrame": 1
            }),
        )
        .await
        .context("startScreencast")?;

        let (tx, rx) = watch::channel(None::<Arc<Frame>>);
        let mut events = self.events.resubscribe();
        let pump_conn = self.conn.clone();
        let pump_session = self.session.clone();
        let handle = tokio::spawn(async move {
            let conn = pump_conn;
            let session = pump_session;
            loop {
                match events.recv().await {
                    Ok(ev) => {
                        if ev.session_id.as_deref() != Some(session.as_str()) {
                            continue;
                        }
                        if ev.method != "Page.screencastFrame" {
                            continue;
                        }
                        let Some(data) = ev.params.get("data").and_then(Value::as_str) else {
                            continue;
                        };
                        // Chrome ≥ 143 reports the frame session as a NUMBER;
                        // older engines as a string. Pass it back verbatim.
                        let Some(frame_session) = ev.params.get("sessionId") else {
                            continue;
                        };
                        match decode_frame(data, "jpeg") {
                            Ok(mut frame) => {
                                frame.from_screencast = true;
                                let _ = tx.send(Some(Arc::new(frame)));
                            }
                            Err(_) => continue, // a broken frame must not kill the pump
                        }
                        // Ack so Chrome keeps producing frames.
                        let _ = conn
                            .call(
                                "Page.screencastFrameAck",
                                json!({"sessionId": frame_session}),
                                Some(&session),
                                CALL_TIMEOUT,
                            )
                            .await;
                    }
                    Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                    Err(_) => break, // connection closed
                }
            }
        });
        Ok(ScreenCast {
            rx,
            conn: self.conn.clone(),
            session: self.session.clone(),
            pump: Some(handle),
        })
    }

    /// Current page URL via `location.href`.
    pub async fn url(&self) -> Result<String> {
        let v = self.evaluate("location.href").await?;
        Ok(v.as_str().unwrap_or_default().to_string())
    }

    /// Current page title.
    pub async fn title(&self) -> Result<String> {
        let v = self.evaluate("document.title").await?;
        Ok(v.as_str().unwrap_or_default().to_string())
    }

    /// Force-close: kill the browser now (Drop also kills).
    pub fn close(self) {
        // Drop does the work; nothing async needed.
    }
}

/// The screencast handle: `latest()` gives the newest decoded frame.
pub struct ScreenCast {
    rx: watch::Receiver<Option<Arc<Frame>>>,
    conn: Connection,
    session: String,
    pump: Option<tokio::task::JoinHandle<()>>,
}

impl ScreenCast {
    pub async fn latest(&self) -> Option<Arc<Frame>> {
        self.rx.borrow().clone()
    }

    /// Wait until a frame arrives or the deadline passes.
    pub async fn wait_for_frame(&mut self, timeout: Duration) -> Option<Arc<Frame>> {
        let deadline = tokio::time::Instant::now() + timeout;
        loop {
            {
                let current = self.rx.borrow().clone();
                if current.is_some() {
                    return current;
                }
            }
            let now = tokio::time::Instant::now();
            if now >= deadline {
                return None;
            }
            if tokio::time::timeout(deadline - now, self.rx.changed()).await.is_err() {
                return None;
            }
        }
    }
}

impl Drop for ScreenCast {
    fn drop(&mut self) {
        // Stop the stream (best effort) and let the pump wind down.
        let conn = self.conn.clone();
        let session = self.session.clone();
        if let Some(pump) = self.pump.take() {
            pump.abort();
        }
        tokio::spawn(async move {
            let _ = conn
                .call("Page.stopScreencast", json!({}), Some(&session), Duration::from_secs(2))
                .await;
        });
    }
}

// ---------------------------------------------------------------------
// helpers
// ---------------------------------------------------------------------

fn chrono_suffix() -> String {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis())
        .unwrap_or(0)
        .to_string()
}

/// Build the resolve-and-measure snippet for a selector. The element is
/// parked in `window.__xen.n` under a token so follow-up operations (fill)
/// can reach it without re-resolving.
fn resolve_js(selector: &Selector) -> String {
    let sel = serde_json::to_string(selector.value()).unwrap_or_else(|_| "\"\"".into());
    let find = match selector {
        Selector::Css(_) => format!("document.querySelector({sel})"),
        Selector::Xpath(_) => format!(
            "document.evaluate({sel}, document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue"
        ),
        Selector::Text(_) => format!(
            "(() => {{ \
                const want = {sel}; \
                const cands = document.querySelectorAll('button, a, [role=button], [role=link], [role=tab], label, summary, input, li, h1, h2, h3, h4, h5, h6, p, span, div, td, th'); \
                let best = null; \
                for (const el of cands) {{ \
                    const own = Array.from(el.childNodes).filter(n => n.nodeType === 3).map(n => n.textContent).join('').trim(); \
                    const full = (el.textContent || '').trim(); \
                    if (own === want || full === want) {{ if (!best || best.contains(el)) best = el; }} \
                }} \
                if (!best) for (const el of cands) {{ \
                    if ((el.textContent || '').trim().includes(want)) {{ if (!best || best.contains(el)) best = el; }} \
                }} \
                return best; \
            }})()"
        ),
    };
    format!(
        "(() => {{ \
            const registry = (window.__xen = window.__xen || {{ n: new Map() }}); \
            const el = {find}; \
            if (!el) return null; \
            el.scrollIntoView({{ block: 'center', inline: 'center' }}); \
            const r = el.getBoundingClientRect(); \
            if (r.width === 0 && r.height === 0) return null; \
            const token = Math.random().toString(36).slice(2); \
            registry.n.set(token, el); \
            return {{ token, x: r.x + r.width / 2, y: r.y + r.height / 2, w: r.width, h: r.height }}; \
        }})()"
    )
}

struct KeySpec {
    key: String,
    code: String,
    vk: u32,
    text: Option<String>,
}

fn key_spec(key: &str) -> Result<KeySpec> {
    let spec = match key {
        "Enter" => KeySpec { key: "Enter".into(), code: "Enter".into(), vk: 13, text: Some("\r".into()) },
        "Tab" => KeySpec { key: "Tab".into(), code: "Tab".into(), vk: 9, text: None },
        "Escape" => KeySpec { key: "Escape".into(), code: "Escape".into(), vk: 27, text: None },
        "Backspace" => KeySpec { key: "Backspace".into(), code: "Backspace".into(), vk: 8, text: None },
        "Delete" => KeySpec { key: "Delete".into(), code: "Delete".into(), vk: 46, text: None },
        "ArrowUp" => KeySpec { key: "ArrowUp".into(), code: "ArrowUp".into(), vk: 38, text: None },
        "ArrowDown" => KeySpec { key: "ArrowDown".into(), code: "ArrowDown".into(), vk: 40, text: None },
        "ArrowLeft" => KeySpec { key: "ArrowLeft".into(), code: "ArrowLeft".into(), vk: 37, text: None },
        "ArrowRight" => KeySpec { key: "ArrowRight".into(), code: "ArrowRight".into(), vk: 39, text: None },
        "Home" => KeySpec { key: "Home".into(), code: "Home".into(), vk: 36, text: None },
        "End" => KeySpec { key: "End".into(), code: "End".into(), vk: 35, text: None },
        " " | "Space" => KeySpec { key: " ".into(), code: "Space".into(), vk: 32, text: Some(" ".into()) },
        other if other.chars().count() == 1 => {
            let c = other.chars().next().unwrap();
            KeySpec {
                key: other.to_string(),
                code: format!("Key{}", c.to_ascii_uppercase()),
                vk: c.to_ascii_uppercase() as u32,
                text: Some(other.to_string()),
            }
        }
        other => return Err(anyhow!("unknown key `{other}`")),
    };
    Ok(spec)
}

/// Copy a profile directory, skipping caches, crashpads, lock files and
/// oversized artifacts. Returns the copied byte count.
pub fn copy_profile(src: &Path, dst: &Path) -> std::io::Result<u64> {
    const SKIP_DIRS: &[&str] = &[
        "Cache", "Cache_Data", "Code Cache", "GPUCache", "GrShaderCache", "ShaderCache",
        "Service Worker", "Crashpad", "Crash Reports", "component_crx_cache",
        "extensions_crx_cache", "OptimizationGuidePredictionModels", "BrowserMetrics",
        "ChromeGrShaderCache", "GraphiteDawnCache", "Subresource Filter", "Safe Browsing",
        "SingletonLock", "SingletonSocket", "SingletonCookie", "lockfile", ".lock",
        "temp", "tmp",
    ];
    const MAX_FILE: u64 = 64 * 1024 * 1024;
    const MAX_TOTAL: u64 = 1024 * 1024 * 1024;
    let mut total: u64 = 0;

    fn walk(src: &Path, dst: &Path, total: &mut u64) -> std::io::Result<()> {
        for entry in std::fs::read_dir(src)? {
            let entry = entry?;
            let name = entry.file_name();
            let name = name.to_string_lossy();
            if SKIP_DIRS.iter().any(|s| name == *s) || name.starts_with("Singleton") {
                continue;
            }
            let ty = entry.file_type()?;
            let target = dst.join(entry.file_name());
            if ty.is_dir() {
                std::fs::create_dir_all(&target)?;
                walk(&entry.path(), &target, total)?;
            } else if ty.is_file() {
                if *total > MAX_TOTAL {
                    continue;
                }
                let meta = entry.metadata()?;
                if meta.len() > MAX_FILE {
                    continue;
                }
                if std::fs::copy(entry.path(), &target).is_ok() {
                    *total += meta.len();
                }
            }
            // symlinks skipped — they only ever point at locks or sockets here
        }
        Ok(())
    }

    std::fs::create_dir_all(dst)?;
    walk(src, dst, &mut total)?;
    Ok(total)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn selector_parse_forms() {
        assert_eq!(Selector::parse("#id"), Selector::Css("#id".into()));
        assert_eq!(Selector::parse("css=a.x"), Selector::Css("a.x".into()));
        assert_eq!(Selector::parse("text=Sign in"), Selector::Text("Sign in".into()));
        assert_eq!(
            Selector::parse("xpath=//nav[1]"),
            Selector::Xpath("//nav[1]".into())
        );
        assert_eq!(Selector::parse("#id").to_string(), "css=#id");
    }

    #[test]
    fn resolve_js_embeds_escaped_selector() {
        let js = resolve_js(&Selector::Text("Si\"gn".into()));
        // the quote must be JSON-escaped inside the snippet
        assert!(js.contains(r#""Si\"gn""#), "{js}");
        assert!(js.contains("scrollIntoView"));
        let js = resolve_js(&Selector::Css("#x".into()));
        assert!(js.contains("document.querySelector"));
        let js = resolve_js(&Selector::Xpath("//a".into()));
        assert!(js.contains("document.evaluate"));
    }

    #[test]
    fn key_table_covers_the_basics() {
        let enter = key_spec("Enter").unwrap();
        assert_eq!(enter.vk, 13);
        assert_eq!(enter.text, Some("\r".to_string()));
        let a = key_spec("a").unwrap();
        assert_eq!(a.vk, 65);
        assert_eq!(a.text, Some("a".to_string()));
        assert!(key_spec("Cmd+P").is_err());
    }

    #[tokio::test]
    async fn copy_profile_skips_caches_and_locks() {
        let src = tempfile::tempdir().unwrap();
        let dst = tempfile::tempdir().unwrap();
        std::fs::create_dir_all(src.path().join("Default/Network")).unwrap();
        std::fs::create_dir_all(src.path().join("Default/Cache/Cache_Data")).unwrap();
        std::fs::write(src.path().join("Default/Network/Cookies"), b"cookie-db").unwrap();
        std::fs::write(src.path().join("Default/Cache/Cache_Data/f_0001"), b"junk").unwrap();
        std::fs::write(src.path().join("SingletonLock"), b"->/proc/123").unwrap();
        std::fs::write(src.path().join("Local State"), b"{}").unwrap();

        let copied = copy_profile(src.path(), dst.path()).unwrap();
        assert!(dst.path().join("Default/Network/Cookies").exists());
        assert!(dst.path().join("Local State").exists());
        assert!(!dst.path().join("SingletonLock").exists());
        assert!(!dst.path().join("Default/Cache").exists());
        assert!(copied >= b"cookie-db".len() as u64);
    }
}
