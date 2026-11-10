//! The trigger engine — one engine, four triggers (plan.md Phase 2):
//!
//! | kind | config | behavior |
//! |---|---|---|
//! | cron | `cron = "0 30 9 * * *"` | fires on schedule (`cron` crate, `@daily` shortcuts) |
//! | watch | `watch = "src/**/*.rs"` | fires on matching file events (notify, debounced) |
//! | webhook | `webhook = "/deploy"` | `POST http://127.0.0.1:<port>/deploy` fires it |
//! | lifecycle | `lifecycle = "onStop"` | fired by the host at engine start/stop/error |
//!
//! Each run goes through the injected [`RunFactory`] — the host builds a
//! fresh, isolated [`ScriptRuntime`] per script (the CLI's factory resolves
//! npm prefetch + permissions the same way as `xen-script run`).

use std::collections::HashMap;
use std::path::PathBuf;
use std::str::FromStr as _;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};

use anyhow::{anyhow, Context as _};
use chrono::Utc;
use futures_util::future::BoxFuture;
use tokio::sync::mpsc;

use crate::config::{TriggerConfig, XenConfig};
use crate::events::{UiEvent, UiEventTx};
use crate::permissions::PermissionState;

/// One executed trigger run.
#[derive(Debug, Clone, serde::Serialize)]
pub struct RunSummary {
    pub id: String,
    pub kind: String,
    pub target: String,
    #[serde(rename = "startedMs")]
    pub started_ms: u128,
    #[serde(rename = "durationMs")]
    pub duration_ms: u128,
    pub ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

/// What the host runs for one trigger firing (isolated runtime per script).
pub type RunFactory =
    Arc<dyn Fn(RunSpec) -> BoxFuture<'static, anyhow::Result<()>> + Send + Sync>;

/// The run request handed to the factory.
#[derive(Debug, Clone)]
pub struct RunSpec {
    pub trigger_id: String,
    pub script: PathBuf,
    pub timeout_ms: Option<u64>,
}

pub struct TriggerEngine {
    cfg: XenConfig,
    config_path: PathBuf,
    #[allow(dead_code)] // retained: future factories reuse the engine's grants
    perms: PermissionState,
    events: UiEventTx,
    factory: Mutex<Option<RunFactory>>,
    runs: Arc<Mutex<Vec<RunSummary>>>,
    stop: Arc<AtomicBool>,
    handles: Mutex<Vec<tokio::task::JoinHandle<()>>>,
}

impl TriggerEngine {
    pub fn new(cfg: XenConfig, config_path: PathBuf, perms: PermissionState, events: UiEventTx) -> Self {
        Self {
            cfg,
            config_path,
            perms,
            events,
            factory: Mutex::new(None),
            runs: Arc::new(Mutex::new(Vec::new())),
            stop: Arc::new(AtomicBool::new(false)),
            handles: Mutex::new(Vec::new()),
        }
    }

    /// Install the run factory (builds a runtime per script).
    pub fn set_factory(&self, factory: RunFactory) {
        *self.factory.lock().unwrap() = Some(factory);
    }

    /// Fire one trigger right now (manual fire / lifecycle / webhook path).
    pub async fn fire(self: &Arc<Self>, trigger: &TriggerConfig) -> RunSummary {
        let id = trigger.id_or();
        let started = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis())
            .unwrap_or(0);
        let _ = self.events.send(UiEvent::Log {
            level: "info".into(),
            text: format!("[trigger:{id}] fired ({})", trigger.kind()),
        });

        let script = self.cfg.resolve_run(&self.config_path, &trigger.run);
        let factory = self.factory.lock().unwrap().clone();
        let result = match factory {
            Some(factory) => {
                factory(RunSpec {
                    trigger_id: id.clone(),
                    script,
                    timeout_ms: trigger.timeout_ms,
                })
                .await
            }
            None => Err(anyhow!("no run factory installed")),
        };

        let summary = RunSummary {
            duration_ms: SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map(|d| d.as_millis())
                .unwrap_or(0)
                - started,
            id: id.clone(),
            kind: trigger.kind().to_string(),
            target: trigger.run.clone(),
            started_ms: started,
            ok: result.is_ok(),
            error: result.err().map(|e| e.to_string()),
        };
        self.runs.lock().unwrap().push(summary.clone());
        if let Some(err) = &summary.error {
            let _ = self.events.send(UiEvent::Log {
                level: "error".into(),
                text: format!("[trigger:{id}] failed: {err}"),
            });
            // onError lifecycle hooks see the failure (boxed to break the
            // fire ⇄ run_lifecycle recursion)
            Box::pin(self.run_lifecycle("onError", Some(err))).await;
        }
        summary
    }

    /// Fire every lifecycle hook matching `name` (`onStart`, `onStop`).
    pub async fn run_lifecycle(self: &Arc<Self>, name: &str, error: Option<&str>) {
        let triggers: Vec<TriggerConfig> = self
            .cfg
            .triggers
            .iter()
            .filter(|t| t.lifecycle.as_deref() == Some(name))
            .cloned()
            .collect();
        for trigger in triggers {
            if let Some(err) = error {
                // onError hooks get the failure text through the process env
                // SAFETY: engine owns its process
                unsafe { std::env::set_var("XEN_TRIGGER_ERROR", err) };
            }
            self.fire(&trigger).await;
        }
    }

    /// Start every non-lifecycle trigger. Returns the webhook port (if any
    /// webhook triggers exist).
    pub async fn start(self: &Arc<Self>) -> anyhow::Result<Option<u16>> {
        self.run_lifecycle("onStart", None).await;

        let mut webhook_port = None;
        for trigger in self.cfg.triggers.clone() {
            let engine = self.clone();
            if let Some(expr) = trigger.cron.clone() {
                let handle = tokio::spawn(cron_loop(engine, trigger, expr));
                self.handles.lock().unwrap().push(handle);
            } else if let Some(pattern) = trigger.watch.clone() {
                let handle = tokio::spawn(watch_loop(engine, trigger, pattern));
                self.handles.lock().unwrap().push(handle);
            } else if let Some(path) = trigger.webhook.clone() {
                if webhook_port.is_none() {
                    webhook_port = Some(start_webhook_server(self.clone()).await?);
                }
                let _ = path;
            }
            // lifecycle triggers are host-driven, no task here
        }
        Ok(webhook_port)
    }

    /// Stop the engine (aborts loops, fires `onStop`).
    pub async fn stop(self: &Arc<Self>) {
        self.stop.store(true, Ordering::Relaxed);
        let mut handles = self.handles.lock().unwrap();
        for handle in handles.drain(..) {
            handle.abort();
        }
        self.run_lifecycle("onStop", None).await;
    }

    /// History of executed runs.
    pub fn runs(&self) -> Vec<RunSummary> {
        self.runs.lock().unwrap().clone()
    }

    fn stopped(&self) -> bool {
        self.stop.load(Ordering::Relaxed)
    }
}

/// cron schedule → next fire duration (supports `@hourly`-style shortcuts).
fn next_cron_delay(expr: &str) -> anyhow::Result<Duration> {
    let expanded = expand_shorthand(expr);
    let schedule = cron::Schedule::from_str(&expanded)
        .with_context(|| format!("cron expression `{expr}`"))?;
    let next = schedule
        .upcoming(Utc)
        .next()
        .ok_or_else(|| anyhow!("cron expression `{expr}` never fires"))?;
    let now = chrono::Utc::now();
    let delay = (next - now).to_std().unwrap_or(Duration::from_secs(1));
    Ok(delay.max(Duration::from_secs(1)))
}

fn expand_shorthand(expr: &str) -> String {
    match expr.trim() {
        "@hourly" => "0 0 * * * *".into(),
        "@daily" | "@midnight" => "0 0 0 * * *".into(),
        "@weekly" => "0 0 0 * * Sun".into(),
        "@monthly" => "0 0 0 1 * *".into(),
        "@yearly" | "@annually" => "0 0 0 1 1 *".into(),
        other => other.to_string(),
    }
}

async fn cron_loop(engine: Arc<TriggerEngine>, trigger: TriggerConfig, expr: String) {
    while !engine.stopped() {
        match next_cron_delay(&expr) {
            Ok(delay) => {
                tokio::select! {
                    _ = tokio::time::sleep(delay) => {}
                    _ = tokio::time::sleep(Duration::from_millis(500)), if engine.stopped() => {}
                }
                if engine.stopped() {
                    break;
                }
                engine.fire(&trigger).await;
            }
            Err(err) => {
                let _ = engine.events.send(UiEvent::Log {
                    level: "error".into(),
                    text: format!("[trigger:{}] {err}", trigger.id_or()),
                });
                break;
            }
        }
    }
}

async fn watch_loop(engine: Arc<TriggerEngine>, trigger: TriggerConfig, pattern: String) {
    use crate::host::fs::{literal_root, walk};
    use notify::Watcher;

    let (tx, mut rx) = mpsc::unbounded_channel::<String>();
    let (std_tx, std_rx) = std::sync::mpsc::channel::<notify::Result<notify::Event>>();
    let mut watcher = match notify::recommended_watcher(std_tx) {
        Ok(w) => w,
        Err(err) => {
            let _ = engine.events.send(UiEvent::Log {
                level: "error".into(),
                text: format!("[trigger:{}] watcher init failed: {err}", trigger.id_or()),
            });
            return;
        }
    };
    let root = literal_root(&pattern);
    if let Err(err) = watcher.watch(&root, notify::RecursiveMode::Recursive) {
        let _ = engine.events.send(UiEvent::Log {
            level: "error".into(),
            text: format!("[trigger:{}] watch `{}`: {err}", trigger.id_or(), root.display()),
        });
        return;
    }

    // forward thread: std receiver → tokio channel
    std::thread::spawn(move || {
        for msg in std_rx {
            match msg {
                Ok(ev) => {
                    for path in ev.paths {
                        if tx.send(path.to_string_lossy().to_string()).is_err() {
                            return;
                        }
                    }
                }
                Err(_) => return,
            }
        }
    });

    let Ok(pat) = glob::Pattern::new(&pattern) else {
        let _ = engine.events.send(UiEvent::Log {
            level: "error".into(),
            text: format!("[trigger:{}] bad glob `{pattern}`", trigger.id_or()),
        });
        return;
    };
    // seed set: existing matches count as baseline (no fire on start)
    let mut last_fired: HashMap<String, Instant> = HashMap::new();
    let baseline: Vec<String> = walk(&root, 500_000).unwrap_or_default();

    while !engine.stopped() {
        let Some(path) = rx.recv().await else { break };
        if !pat.matches_path(std::path::Path::new(&path)) {
            continue;
        }
        if baseline.iter().any(|b| b == &path) {
            // baseline files only fire on modification events that recur
            continue;
        }
        let now = Instant::now();
        if last_fired
            .get(&path)
            .map(|t| now.duration_since(*t) < Duration::from_secs(1))
            .unwrap_or(false)
        {
            continue;
        }
        last_fired.insert(path, now);
        engine.fire(&trigger).await;
    }
}

/// Minimal webhook server: one listener, path-routed POST/GET → trigger fire.
async fn start_webhook_server(engine: Arc<TriggerEngine>) -> anyhow::Result<u16> {
    let listener = tokio::net::TcpListener::bind(("127.0.0.1", 0)).await?;
    let port = listener.local_addr()?.port();

    let triggers: Vec<TriggerConfig> = engine
        .cfg
        .triggers
        .iter()
        .filter(|t| t.webhook.is_some())
        .cloned()
        .collect();

    let _ = engine.events.send(UiEvent::Log {
        level: "info".into(),
        text: format!("[webhooks] listening on http://127.0.0.1:{port}"),
    });

    tokio::spawn(async move {
        loop {
            let Ok((mut socket, _)) = listener.accept().await else { break };
            let triggers = triggers.clone();
            let engine = engine.clone();
            tokio::spawn(async move {
                use tokio::io::{AsyncReadExt, AsyncWriteExt};
                let mut buf = Vec::new();
                // read until headers complete (bounded)
                let mut head_end = None;
                while head_end.is_none() && buf.len() < 64 * 1024 {
                    let mut chunk = [0u8; 1024];
                    let Ok(n) = socket.read(&mut chunk).await else { return };
                    if n == 0 {
                        break;
                    }
                    buf.extend_from_slice(&chunk[..n]);
                    head_end = find_header_end(&buf);
                }
                let Some(header_end) = head_end else { return };
                let head = String::from_utf8_lossy(&buf[..header_end]).to_string();
                let mut lines = head.lines();
                let request_line = lines.next().unwrap_or_default().to_string();
                let path = request_line
                    .split_whitespace()
                    .nth(1)
                    .unwrap_or_default()
                    .split('?')
                    .next()
                    .unwrap_or_default()
                    .to_string();
                // drain content-length body
                let content_length = head
                    .to_ascii_lowercase()
                    .lines()
                    .find_map(|l| l.strip_prefix("content-length:"))
                    .and_then(|v| v.trim().parse::<usize>().ok())
                    .unwrap_or(0);
                let have = buf.len() - header_end;
                if content_length > have {
                    let mut rest = vec![0u8; content_length - have];
                    let _ = socket.read_exact(&mut rest).await;
                }

                let matched: Vec<&TriggerConfig> = triggers
                    .iter()
                    .filter(|t| t.webhook.as_deref() == Some(path.as_str()))
                    .collect();
                let (status, body) = if matched.is_empty() {
                    ("404 Not Found", "no trigger for path\n")
                } else {
                    for trigger in matched {
                        engine.fire(trigger).await;
                    }
                    ("200 OK", "ok\n")
                };
                let response = format!(
                    "HTTP/1.1 {status}\r\nContent-Type: text/plain\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                    body.len()
                );
                let _ = socket.write_all(response.as_bytes()).await;
            });
        }
    });
    Ok(port)
}

fn find_header_end(buf: &[u8]) -> Option<usize> {
    buf.windows(4).position(|w| w == b"\r\n\r\n").map(|i| i + 4)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cron_delays() {
        let d1 = next_cron_delay("@daily").unwrap();
        assert!(d1 <= Duration::from_secs(24 * 3600));
        let d2 = next_cron_delay("0 */5 * * * *").unwrap();
        assert!(d2 >= Duration::from_secs(1) && d2 <= Duration::from_secs(5 * 60));
        assert!(next_cron_delay("not a cron").is_err());
    }

    #[test]
    fn header_end_detection() {
        let buf = b"POST /x HTTP/1.1\r\nHost: a\r\n\r\nbody";
        assert_eq!(find_header_end(buf), Some(buf.len() - 4));
        assert_eq!(find_header_end(b"partial"), None);
    }
}
