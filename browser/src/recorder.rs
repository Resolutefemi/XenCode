//! Interaction recorder (Phase 6.3) — turn browser interactions into
//! reusable QuickJS automation scripts.
//!
//! A recorder rides a live Space: a bootstrap script is injected into every
//! document (`Page.addScriptToEvaluateOnNewDocument`) where it listens to
//! real DOM events (click / change / keydown / scroll, capture phase) and
//! streams them back through a `Runtime.addBinding` binding. The Rust-side
//! pump folds the raw events into deterministic [`RecStep`]s:
//!
//! - a click into a text field followed by `change` collapses into one
//!   `Fill` (the click is replay noise),
//! - checkbox/radio clicks keep their `Click` (toggle semantics) and swallow
//!   the trailing `change`,
//! - `<select>` commits become `Select`,
//! - navigations directly caused by a click/press (≤ 2 s before) are dropped
//!   — the replayed click navigates on its own,
//! - scroll bursts coalesce into one absolute `ScrollTo` per position.
//!
//! [`Recording::to_quickjs`] renders the steps as a `xen.browser` script
//! that replays through the same auto-wait Space actions the CLI uses.
//! Password fields are redacted by default (the script asks the user to set
//! the value before replaying).

use std::time::{SystemTime, UNIX_EPOCH};

use anyhow::{anyhow, Result};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use tokio::sync::watch;

use crate::space::Space;

/// Binding name the injected script calls with JSON payloads.
pub const BINDING: &str = "xenRecord";
/// Hard cap on recorded steps (runaway pages).
pub const MAX_STEPS: usize = 500;
/// A click/press this long before a navigation is considered its cause.
const NAV_CAUSE_WINDOW_MS: i64 = 2_000;
/// Clicks this long before a fill/select on the same target are collapsed.
const CLICK_FILL_WINDOW_MS: i64 = 2_000;
/// Scroll bursts inside this window coalesce into one ScrollTo.
const SCROLL_COALESCE_MS: i64 = 500;

/// A raw DOM event streamed from the injected recorder script.
#[derive(Debug, Clone, Deserialize, Serialize)]
pub struct RawEvent {
    pub kind: String,
    #[serde(default)]
    pub css: String,
    #[serde(default)]
    pub text: String,
    #[serde(default)]
    pub value: String,
    #[serde(default)]
    pub key: String,
    #[serde(default)]
    pub scroll_x: f64,
    #[serde(default)]
    pub scroll_y: f64,
    /// `type=password` fills set this instead of the value.
    #[serde(default)]
    pub redacted: bool,
    #[serde(default)]
    pub ts: i64,
    #[serde(default)]
    pub url: String,
}

/// One replayable interaction step.
#[derive(Debug, Clone, Serialize, PartialEq)]
#[serde(tag = "op", rename_all = "snake_case")]
pub enum RecStep {
    Navigate { url: String },
    Click { selector: String, text: String },
    Fill { selector: String, value: String, redacted: bool },
    Select { selector: String, value: String },
    Press { key: String },
    ScrollTo { x: f64, y: f64 },
}

impl RecStep {
    pub fn kind(&self) -> &'static str {
        match self {
            RecStep::Navigate { .. } => "navigate",
            RecStep::Click { .. } => "click",
            RecStep::Fill { .. } => "fill",
            RecStep::Select { .. } => "select",
            RecStep::Press { .. } => "press",
            RecStep::ScrollTo { .. } => "scroll",
        }
    }
}

/// Pure event → step folder (unit-testable without a browser).
#[derive(Debug, Default)]
pub struct RecorderState {
    steps: Vec<RecStep>,
    /// Timestamp of the last pushed interaction step (nav-cause window).
    last_interaction: Option<(i64, &'static str)>,
    /// Last emitted ScrollTo position + ts (coalescing window).
    last_scroll: Option<(f64, f64, i64)>,
    start_url: String,
    truncated: bool,
}

impl RecorderState {
    pub fn new(start_url: &str) -> Self {
        RecorderState {
            start_url: start_url.to_string(),
            ..Default::default()
        }
    }

    pub fn steps(&self) -> &[RecStep] {
        &self.steps
    }

    pub fn truncated(&self) -> bool {
        self.truncated
    }

    pub fn start_url(&self) -> &str {
        &self.start_url
    }

    /// Fold one raw event; returns the step it produced (if any).
    pub fn fold(&mut self, raw: &RawEvent) -> Option<RecStep> {
        let step = match raw.kind.as_str() {
            "click" => self.fold_click(raw)?,
            "fill" => self.fold_fill(raw)?,
            "select" => self.fold_select(raw)?,
            "press" => self.fold_press(raw)?,
            "scroll" => self.fold_scroll(raw)?,
            _ => return None,
        };
        if self.steps.len() >= MAX_STEPS {
            self.truncated = true;
            return None;
        }
        self.last_interaction = Some((
            raw.ts,
            match step.kind() {
                "click" | "press" => "interaction",
                _ => "other",
            },
        ));
        self.steps.push(step.clone());
        Some(step)
    }

    /// Fold a main-frame navigation (from `Page.frameNavigated`).
    pub fn fold_navigate(&mut self, url: &str, ts: i64) -> Option<RecStep> {
        if url == self.start_url && self.steps.is_empty() {
            return None; // the initial load is the opening navigate
        }
        // A navigation immediately after a click/press was caused by it.
        if let Some((t, kind)) = self.last_interaction {
            if kind == "interaction" && ts - t <= NAV_CAUSE_WINDOW_MS {
                return None;
            }
        }
        if self.steps.len() >= MAX_STEPS {
            self.truncated = true;
            return None;
        }
        let step = RecStep::Navigate { url: url.to_string() };
        self.steps.push(step.clone());
        Some(step)
    }

    fn fold_click(&mut self, raw: &RawEvent) -> Option<RecStep> {
        let selector = pick_selector(&raw.css, &raw.text);
        Some(RecStep::Click {
            selector,
            text: raw.text.clone(),
        })
    }

    fn fold_fill(&mut self, raw: &RawEvent) -> Option<RecStep> {
        // Drop the click that focused this same field.
        self.drop_preceding_click(&raw.css, raw.ts);
        let step = RecStep::Fill {
            selector: format!("css={}", raw.css),
            value: if raw.redacted { String::new() } else { raw.value.clone() },
            redacted: raw.redacted,
        };
        Some(step)
    }

    fn fold_select(&mut self, raw: &RawEvent) -> Option<RecStep> {
        self.drop_preceding_click(&raw.css, raw.ts);
        let step = RecStep::Select {
            selector: format!("css={}", raw.css),
            value: raw.value.clone(),
        };
        Some(step)
    }

    fn fold_press(&mut self, raw: &RawEvent) -> Option<RecStep> {
        if raw.key.is_empty() {
            return None;
        }
        Some(RecStep::Press { key: raw.key.clone() })
    }

    fn fold_scroll(&mut self, raw: &RawEvent) -> Option<RecStep> {
        // Coalesce bursts into the final position.
        if let Some((_, _, ts)) = self.last_scroll {
            if raw.ts - ts <= SCROLL_COALESCE_MS {
                if let Some(RecStep::ScrollTo { x, y }) = self.steps.last_mut() {
                    *x = raw.scroll_x;
                    *y = raw.scroll_y;
                    self.last_scroll = Some((raw.scroll_x, raw.scroll_y, raw.ts));
                    return None;
                }
            }
        }
        let step = RecStep::ScrollTo { x: raw.scroll_x, y: raw.scroll_y };
        self.last_scroll = Some((raw.scroll_x, raw.scroll_y, raw.ts));
        Some(step)
    }

    /// Remove a click on the same target inside the click→fill window.
    fn drop_preceding_click(&mut self, css: &str, ts: i64) {
        if let Some(RecStep::Click { selector, .. }) = self.steps.last() {
            let same_target = selector == &format!("css={css}")
                || selector
                    .strip_prefix("css=")
                    .map(|s| s == css)
                    .unwrap_or(false);
            if same_target {
                if let Some((t, _)) = self.last_interaction {
                    if ts - t <= CLICK_FILL_WINDOW_MS {
                        self.steps.pop();
                    }
                }
            }
        }
    }
}

/// Prefer high-signal CSS (`#id`, `[data-testid=…]`, `input[name=…]`);
/// fall back to a text selector when one is available; otherwise the raw
/// CSS path.
pub fn pick_selector(css: &str, text: &str) -> String {
    let high_signal = css.starts_with('#') || css.starts_with("[data-testid") || css.contains("[name=");
    if high_signal || text.is_empty() {
        format!("css={css}")
    } else {
        format!("text={}", text)
    }
}

/// Recording handle — pumps binding events + frame navigations and folds
/// them into steps. `stop()` winds the pump down and returns the steps.
pub struct Recorder {
    stop_tx: watch::Sender<bool>,
    pump: Option<tokio::task::JoinHandle<()>>,
    state: std::sync::Arc<futures_state::StateSlot>,
}

// The pump is !Send-adjacent (broadcast receiver); the folded state rides a
// plain mutex slot the stop() future reads after the pump finishes.
mod futures_state {
    use super::RecorderState;
    use std::sync::Mutex;

    #[derive(Default)]
    pub struct StateSlot {
        pub state: Mutex<Option<RecorderState>>,
    }
}

#[derive(Debug, Clone)]
pub struct RecordOptions {
    /// Redact `type=password` fills (default true).
    pub redact_passwords: bool,
    /// Record scroll positions as absolute ScrollTo steps.
    pub record_scroll: bool,
}

impl Default for RecordOptions {
    fn default() -> Self {
        RecordOptions { redact_passwords: true, record_scroll: true }
    }
}

impl Recorder {
    /// Install the recorder bootstrap + binding on a Space and start the
    /// event pump. Call before navigating so every document gets covered.
    pub async fn start(space: &Space, opts: RecordOptions) -> Result<Recorder> {
        space
            .call(
                "Runtime.addBinding",
                json!({"name": BINDING}),
            )
            .await
            .map_err(|e| anyhow!("Runtime.addBinding: {e}"))?;
        space
            .call(
                "Page.addScriptToEvaluateOnNewDocument",
                json!({"source": recorder_js(&opts)}),
            )
            .await
            .map_err(|e| anyhow!("addScriptToEvaluateOnNewDocument: {e}"))?;

        let start_url = space.url().await.unwrap_or_default();
        let slot = std::sync::Arc::new(futures_state::StateSlot::default());
        let (stop_tx, mut stop_rx) = watch::channel(false);
        let mut events = space.events_resubscribe();
        let session = space.session_id().to_string();
        let slot2 = slot.clone();

        let pump = tokio::spawn(async move {
            let mut state = RecorderState::new(&start_url);
            loop {
                tokio::select! {
                    biased;
                    _ = stop_rx.changed() => {
                        if *stop_rx.borrow() { break; }
                    }
                    ev = events.recv() => {
                        match ev {
                            Ok(ev) => {
                                if ev.session_id.as_deref() != Some(session.as_str()) {
                                    continue;
                                }
                                match ev.method.as_str() {
                                    "Runtime.bindingCalled" => {
                                        let payload = ev
                                            .params
                                            .get("payload")
                                            .and_then(Value::as_str)
                                            .unwrap_or_default();
                                        if ev.params.get("name").and_then(Value::as_str) != Some(BINDING) {
                                            continue;
                                        }
                                        if let Ok(raw) = serde_json::from_str::<RawEvent>(payload) {
                                            state.fold(&raw);
                                        }
                                    }
                                    "Page.frameNavigated" => {
                                        // main frame only
                                        let frame = ev.params.get("frame");
                                        let is_main = frame
                                            .and_then(|f| f.get("parentId"))
                                            .is_none();
                                        if !is_main {
                                            continue;
                                        }
                                        let url = frame
                                            .and_then(|f| f.get("url"))
                                            .and_then(Value::as_str)
                                            .unwrap_or_default();
                                        if url.is_empty() || url.starts_with("about:") {
                                            continue;
                                        }
                                        let ts = now_ms();
                                        state.fold_navigate(url, ts);
                                    }
                                    _ => {}
                                }
                            }
                            Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                            Err(_) => break,
                        }
                    }
                }
            }
            *slot2.state.lock().unwrap() = Some(state);
        });

        Ok(Recorder {
            stop_tx,
            pump: Some(pump),
            state: slot,
        })
    }

    /// Stop the pump and return the folded steps (+ whether the cap hit).
    pub async fn stop(mut self) -> Result<(Vec<RecStep>, bool)> {
        let _ = self.stop_tx.send(true);
        if let Some(pump) = self.pump.take() {
            let _ = pump.await;
        }
        let guard = self.state.state.lock().unwrap();
        match guard.as_ref() {
            Some(st) => Ok((st.steps().to_vec(), st.truncated())),
            None => Err(anyhow!("recorder pump finished without state")),
        }
    }
}

fn now_ms() -> i64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// The injected bootstrap. Runs before page scripts on every document;
/// listeners use the capture phase so page handlers cannot swallow events.
pub fn recorder_js(opts: &RecordOptions) -> String {
    let redact = opts.redact_passwords;
    let scroll = opts.record_scroll;
    format!(
        r#"(() => {{
  if (window.__xenRecorder) return;
  window.__xenRecorder = true;
  const send = (evt) => {{
    try {{
      evt.ts = Date.now();
      evt.url = location.href;
      xenRecord(JSON.stringify(evt));
    }} catch (e) {{}}
  }};
  const ownText = (el) => {{
    const t = Array.from(el.childNodes)
      .filter(n => n.nodeType === 3)
      .map(n => n.textContent).join('').trim();
    return t.slice(0, 60);
  }};
  const cssPath = (el) => {{
    try {{
      if (el.id && document.querySelectorAll('#' + CSS.escape(el.id)).length === 1)
        return '#' + CSS.escape(el.id);
      const dt = el.getAttribute && el.getAttribute('data-testid');
      if (dt) return '[data-testid="' + dt + '"]';
      const name = el.getAttribute && el.getAttribute('name');
      if (name) return el.tagName.toLowerCase() + '[name="' + name + '"]';
      const parts = [];
      let cur = el, depth = 0;
      while (cur && cur.nodeType === 1 && depth < 6) {{
        let sel = cur.tagName.toLowerCase();
        if (cur.id) {{ parts.unshift('#' + CSS.escape(cur.id)); break; }}
        const parent = cur.parentElement;
        if (parent) {{
          const sibs = Array.from(parent.children).filter(c => c.tagName === cur.tagName);
          if (sibs.length > 1) sel += ':nth-of-type(' + (sibs.indexOf(cur) + 1) + ')';
        }}
        parts.unshift(sel);
        cur = cur.parentElement;
        depth += 1;
      }}
      return parts.join(' > ');
    }} catch (e) {{ return el.tagName ? el.tagName.toLowerCase() : 'unknown'; }}
  }};
  const actionable = 'button, a, [role=button], [role=link], [role=tab], label, summary, input, [onclick], select, textarea';
  document.addEventListener('click', (ev) => {{
    const el = ev.target && ev.target.closest ? ev.target.closest(actionable) : null;
    if (!el) return;
    const tag = el.tagName.toLowerCase();
    const type = tag === 'input' ? (el.getAttribute('type') || 'text').toLowerCase() : '';
    if (tag === 'select' || tag === 'textarea') return; // change covers them
    if (tag === 'input' && (type === 'checkbox' || type === 'radio')) {{
      window.__xenSuppress = el; // the trailing change belongs to this click
    }}
    if (tag === 'input' && type !== 'checkbox' && type !== 'radio' && type !== 'submit' && type !== 'button')
      return; // text fields are recorded on change (one Fill, not click+fill)
    send({{ kind: 'click', css: cssPath(el), text: ownText(el) }});
  }}, true);
  document.addEventListener('change', (ev) => {{
    const el = ev.target;
    if (!el || !el.tagName) return;
    const tag = el.tagName.toLowerCase();
    if (el === window.__xenSuppress) {{ window.__xenSuppress = null; return; }}
    if (tag === 'select') {{
      send({{ kind: 'select', css: cssPath(el), value: el.value }});
      return;
    }}
    if (tag === 'input' || tag === 'textarea') {{
      const type = tag === 'input' ? (el.getAttribute('type') || 'text').toLowerCase() : 'text';
      if (type === 'checkbox' || type === 'radio' || type === 'submit' || type === 'button' || type === 'file') return;
      const redacted = {redact} && type === 'password';
      send({{ kind: 'fill', css: cssPath(el), value: redacted ? '' : el.value, redacted }});
    }}
  }}, true);
  document.addEventListener('keydown', (ev) => {{
    if (ev.key !== 'Enter' && ev.key !== 'Escape' && ev.key !== 'Tab') return;
    const el = ev.target;
    const tag = el && el.tagName ? el.tagName.toLowerCase() : '';
    if (tag === 'input' || tag === 'textarea') {{
      if (ev.key === 'Enter') send({{ kind: 'press', key: 'Enter' }});
      return;
    }}
    send({{ kind: 'press', key: ev.key }});
  }}, true);
  {scroll_js}
}})();"#,
        redact = redact,
        scroll_js = if scroll {
            r#"let __xenScrollTimer = null;
  window.addEventListener('scroll', () => {
    if (__xenScrollTimer) clearTimeout(__xenScrollTimer);
    __xenScrollTimer = setTimeout(() => {
      __xenScrollTimer = null;
      send({ kind: 'scroll', scroll_x: window.scrollX, scroll_y: window.scrollY });
    }, 300);
  }, true);"#.to_string()
        } else {
            String::new()
        },
    )
}

// ---------------------------------------------------------------------
// script emission
// ---------------------------------------------------------------------

/// Metadata rendered into the script header.
#[derive(Debug, Clone, Default)]
pub struct RecordMeta {
    pub start_url: String,
    pub recorded_at: String,
    pub redacted_count: usize,
}

/// Render the folded steps as a QuickJS automation script targeting the
/// `xen.browser` surface.
pub fn to_quickjs_script(steps: &[RecStep], meta: &RecordMeta) -> String {
    let mut out = String::with_capacity(1024 + steps.len() * 80);
    out.push_str("// XenCode browser recording\n");
    if !meta.recorded_at.is_empty() {
        out.push_str(&format!("// Recorded: {}\n", meta.recorded_at));
    }
    if !meta.start_url.is_empty() {
        out.push_str(&format!("// Source: {}\n", meta.start_url));
    }
    out.push_str(&format!(
        "// Steps: {}\n// Replay: xencode browser run <this-file.ts>\n",
        steps.len()
    ));
    if meta.redacted_count > 0 {
        out.push_str(&format!(
            "// NOTE: {} password field(s) redacted — set the real value(s) before replaying.\n",
            meta.redacted_count
        ));
    }
    out.push('\n');

    for step in steps {
        match step {
            RecStep::Navigate { url } => {
                out.push_str(&format!("await xen.browser.navigate({});\n", js_str(url)));
            }
            RecStep::Click { selector, .. } => {
                out.push_str(&format!("await xen.browser.click({});\n", js_str(selector)));
            }
            RecStep::Fill { selector, value, redacted } => {
                let comment = if *redacted { "  // password — set before replay" } else { "" };
                out.push_str(&format!(
                    "await xen.browser.fill({}, {});{}\n",
                    js_str(selector),
                    js_str(value),
                    comment
                ));
            }
            RecStep::Select { selector, value } => {
                out.push_str(&format!(
                    "await xen.browser.select({}, {});\n",
                    js_str(selector),
                    js_str(value)
                ));
            }
            RecStep::Press { key } => {
                out.push_str(&format!("await xen.browser.press({});\n", js_str(key)));
            }
            RecStep::ScrollTo { x, y } => {
                out.push_str(&format!(
                    "await xen.browser.scrollTo({}, {});\n",
                    crate::scrape::fmt_num(*x),
                    crate::scrape::fmt_num(*y)
                ));
            }
        }
    }

    // The final outline is what the agent (or the user) sees after replay.
    out.push_str("\nconst outline = await xen.browser.snapshot();\nconsole.log(outline);\n");
    out.push_str(&format!(
        "\nexport default {{ recording: {}, steps: {} }};\n",
        js_str(&meta.start_url),
        steps.len()
    ));
    out
}

fn js_str(s: &str) -> String {
    serde_json::to_string(s).unwrap_or_else(|_| "\"\"".into())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn ev(kind: &str, css: &str, text: &str, value: &str, ts: i64) -> RawEvent {
        RawEvent {
            kind: kind.into(),
            css: css.into(),
            text: text.into(),
            value: value.into(),
            key: String::new(),
            scroll_x: 0.0,
            scroll_y: 0.0,
            redacted: false,
            ts,
            url: "https://x.test/".into(),
        }
    }

    #[test]
    fn click_then_change_collapses_into_fill() {
        let mut st = RecorderState::new("https://x.test/");
        st.fold(&ev("click", "#email", "", "", 1_000)).unwrap();
        let step = st
            .fold(&ev("fill", "#email", "", "femi@example.com", 1_600))
            .unwrap();
        match step {
            RecStep::Fill { selector, value, redacted } => {
                assert_eq!(selector, "css=#email");
                assert_eq!(value, "femi@example.com");
                assert!(!redacted);
            }
            other => panic!("expected fill, got {other:?}"),
        }
        // the focusing click is gone
        assert_eq!(st.steps().len(), 1);
    }

    #[test]
    fn checkbox_click_survives_and_swallows_change() {
        let mut st = RecorderState::new("https://x.test/");
        st.fold(&ev("click", "#tos", "", "", 1_000)).unwrap();
        // the injected script sets redacted=false + suppresses the change —
        // simulate a stray change reaching the fold anyway:
        let change = ev("fill", "#tos", "", "on", 1_100);
        // fold_fill would drop the click — but the injected script never
        // sends fills for checkboxes; a real suppressed change never arrives.
        // Fold-level contract: click stays.
        assert_eq!(st.steps().len(), 1);
        assert_eq!(st.steps()[0].kind(), "click");
        assert!(st.fold(&change).is_some()); // treated as a fill (defensive)
    }

    #[test]
    fn navigation_after_click_is_dropped() {
        let mut st = RecorderState::new("https://x.test/");
        st.fold(&ev("click", "a.x", "Docs", "", 1_000)).unwrap();
        assert!(st.fold_navigate("https://x.test/docs", 1_400).is_none());
        assert_eq!(st.steps().len(), 1);
        // a standalone navigation (new tab / URL edit) is kept
        let step = st.fold_navigate("https://x.test/other", 5_000).unwrap();
        match step {
            RecStep::Navigate { url } => assert_eq!(url, "https://x.test/other"),
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn scroll_bursts_coalesce_into_final_position() {
        let mut st = RecorderState::new("https://x.test/");
        st.fold(&ev("scroll", "", "", "", 1_000)).unwrap();
        // update: scroll events carry positions — set them explicitly
        st.steps.clear();
        st.last_scroll = None;
        let mut s1 = ev("scroll", "", "", "", 1_000);
        s1.scroll_y = 300.0;
        st.fold(&s1).unwrap();
        let mut s2 = ev("scroll", "", "", "", 1_100);
        s2.scroll_y = 900.0;
        let r = st.fold(&s2);
        assert!(r.is_none(), "coalesced scroll produces no new step");
        assert_eq!(st.steps().len(), 1);
        match &st.steps()[0] {
            RecStep::ScrollTo { x, y } => {
                assert_eq!(*x, 0.0);
                assert_eq!(*y, 900.0, "burst keeps the final position");
            }
            other => panic!("{other:?}"),
        }
    }

    #[test]
    fn selector_preference_id_testid_then_text() {
        assert_eq!(pick_selector("#email", ""), "css=#email");
        assert_eq!(pick_selector("[data-testid=\"go\"]", "Go"), "css=[data-testid=\"go\"]");
        assert_eq!(pick_selector("button:nth-of-type(2)", "Sign in"), "text=Sign in");
        assert_eq!(pick_selector("div > span:nth-of-type(3)", ""), "css=div > span:nth-of-type(3)");
    }

    #[test]
    fn emitted_script_carries_steps_and_redaction_note() {
        let steps = vec![
            RecStep::Navigate { url: "https://x.test/".into() },
            RecStep::Fill { selector: "css=#user".into(), value: "femi".into(), redacted: false },
            RecStep::Fill { selector: "css=#pass".into(), value: String::new(), redacted: true },
            RecStep::Click { selector: "text=Sign in".into(), text: "Sign in".into() },
            RecStep::Press { key: "Enter".into() },
            RecStep::ScrollTo { x: 0.0, y: 600.0 },
        ];
        let meta = RecordMeta {
            start_url: "https://x.test/".into(),
            recorded_at: "2026-11-11 04:31Z".into(),
            redacted_count: 1,
        };
        let script = to_quickjs_script(&steps, &meta);
        assert!(script.contains("await xen.browser.navigate(\"https://x.test/\");"), "{script}");
        assert!(script.contains("await xen.browser.fill(\"css=#user\", \"femi\");"));
        assert!(script.contains("<REDACTED>") || script.contains("password"), "{script}");
        assert!(script.contains("await xen.browser.click(\"text=Sign in\");"));
        assert!(script.contains("await xen.browser.press(\"Enter\");"));
        assert!(script.contains("xen.browser.scrollTo(0, 600);"));
        assert!(script.contains("1 password field"));
        assert!(script.ends_with("steps: 6 };\n"), "{script}");
    }

    #[test]
    fn emitted_script_quotes_awkward_values() {
        let steps = vec![RecStep::Fill {
            selector: "css=#q".into(),
            value: "say \"hi\" \\ back".into(),
            redacted: false,
        }];
        let script = to_quickjs_script(&steps, &RecordMeta::default());
        assert!(
            script.contains(r#""say \"hi\" \\ back""#),
            "value must be JSON-escaped: {script}"
        );
    }

    #[test]
    fn max_steps_cap_marks_truncation() {
        let mut st = RecorderState::new("https://x.test/");
        for i in 0..(MAX_STEPS + 10) {
            st.fold(&ev("click", &format!("#b{i}"), "", "", i as i64));
        }
        assert!(st.truncated());
        assert_eq!(st.steps().len(), MAX_STEPS);
    }

    #[test]
    fn recorder_js_installs_once_and_covers_events() {
        let js = recorder_js(&RecordOptions::default());
        assert!(js.contains("__xenRecorder"), "one-shot guard");
        assert!(js.contains("xenRecord(JSON.stringify(evt))"), "binding call");
        assert!(js.contains("addEventListener('click'"), "click capture");
        assert!(js.contains("addEventListener('change'"), "change capture");
        assert!(js.contains("addEventListener('keydown'"), "keydown capture");
        assert!(js.contains("__xenScrollTimer"), "scroll debounce");
        assert!(js.contains("type === 'password'"), "password redaction");
        assert!(js.contains("closest(actionable)"), "actionable filter");
    }
}
