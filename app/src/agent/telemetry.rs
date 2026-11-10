//! Run telemetry (Phase 5, harness port #14): OTel-style spans, token counts
//! and cost for every agent run. Zero new dependencies — spans are recorded
//! in-process, persisted as OTel-flavored JSONL under
//! `.xencode/telemetry/<run_id>.jsonl`, and optionally POSTed once at run
//! end to an OTLP/HTTP(JSON) collector (`[telemetry] endpoint` or
//! `XENCODE_OTEL_ENDPOINT`).
//!
//! Spans per run:
//! - `agent.run` — the whole loop (attributes: iterations, turns, answer size)
//! - `llm.call`  — one provider turn (model, tokens in/out, estimated flag)
//! - `tool.call` — one tool execution (tool name, ok, duration)
//!
//! Tokens come from the provider when the API reports usage; otherwise an
//! `estimated` count (chars/4) is recorded so cost accounting never silently
//! reads zero. Cost uses the `[telemetry]` price table (USD per million
//! tokens) when configured.

use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;
use std::time::{SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use xencode_script::config::TelemetryConfig;

/// Token counts for one model call (real when the API reported usage,
/// estimated otherwise).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
pub struct TokenUsage {
    pub input: u64,
    pub output: u64,
    /// True when the count is a chars/4 estimate, not provider-reported.
    pub estimated: bool,
}

impl TokenUsage {
    /// Rough chars→tokens estimate (≈4 chars/token for code + English).
    /// A public helper: providers that cannot report usage call this before
    /// handing the message back (GlmProvider estimates inline today).
    #[allow(dead_code)]
    pub fn estimate(text: &str) -> Self {
        let tokens = (text.chars().count() as u64) / 4;
        Self { input: tokens, output: 0, estimated: true }
    }

    pub fn add(&mut self, other: &TokenUsage) {
        self.input += other.input;
        self.output += other.output;
        self.estimated |= other.estimated;
    }
}

/// One recorded span (OTel-ish: name, times, attributes, status).
#[derive(Debug, Clone, Serialize)]
struct Span {
    name: String,
    /// Span ids for OTLP export (hex).
    #[serde(skip_serializing)]
    id: String,
    #[serde(skip_serializing)]
    parent: String,
    start_unix_ms: u128,
    duration_ms: u128,
    status: &'static str,
    attributes: serde_json::Value,
}

/// Telemetry recorder for one run. `Arc`-shared with the loop; spans land in
/// a mutex-protected vec (parallel subagents record concurrently).
pub struct Telemetry {
    run_id: String,
    dir: PathBuf,
    endpoint: Option<String>,
    /// (USD per million input tokens, USD per million output tokens).
    pricing: Option<(f64, f64)>,
    spans: Mutex<Vec<Span>>,
    /// Totals across llm.call spans.
    tokens: Mutex<TokenUsage>,
    counter: AtomicU64,
    trace_id: String,
}

impl Telemetry {
    /// Build the recorder for one run. `None` when telemetry is disabled.
    pub fn for_run(cwd: &Path, cfg: &TelemetryConfig) -> Option<std::sync::Arc<Self>> {
        // Enabled by default when the table exists; explicit false wins.
        if cfg.enabled == Some(false) {
            return None;
        }
        let now_ms = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis()).unwrap_or(0);
        let run_id = format!("run_{now_ms}");
        let dir = cwd.join(".xencode").join("telemetry");
        let pricing = match (cfg.input_cost_per_mtok, cfg.output_cost_per_mtok) {
            (Some(i), Some(o)) => Some((i, o)),
            _ => None,
        };
        let endpoint = cfg
            .endpoint
            .clone()
            .or_else(|| std::env::var("XENCODE_OTEL_ENDPOINT").ok())
            .filter(|s| !s.trim().is_empty());
        Some(std::sync::Arc::new(Self {
            trace_id: hex_id(now_ms as u64, 0, 32),
            run_id,
            dir,
            endpoint,
            pricing,
            spans: Mutex::new(Vec::new()),
            tokens: Mutex::new(TokenUsage::default()),
            counter: AtomicU64::new(1),
        }))
    }

    fn next_span_id(&self) -> String {
        let n = self.counter.fetch_add(1, Ordering::Relaxed);
        let now = SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis() as u64).unwrap_or(0);
        hex_id(now, n, 16)
    }

    /// Record one span synchronously (never blocks the loop).
    fn record(&self, name: &str, parent: &str, start_ms: u128, duration_ms: u128, status: &'static str, attributes: serde_json::Value) {
        let span = Span {
            name: name.into(),
            id: self.next_span_id(),
            parent: parent.into(),
            start_unix_ms: start_ms,
            duration_ms,
            status,
            attributes,
        };
        self.spans.lock().expect("telemetry lock").push(span);
    }

    /// The run-span handle: created at loop start, recorded on Drop (so an
    /// aborted run still leaves a span).
    pub fn run_span(self: &std::sync::Arc<Self>) -> RunSpanGuard {
        RunSpanGuard {
            tele: self.clone(),
            start: now_ms(),
        }
    }

    /// Record one provider call.
    pub fn llm_call(&self, model: &str, usage: &TokenUsage, duration_ms: u128, ok: bool) {
        self.record(
            "llm.call",
            "run",
            now_ms().saturating_sub(duration_ms),
            duration_ms,
            if ok { "ok" } else { "error" },
            serde_json::json!({
                "model": model,
                "tokens_in": usage.input,
                "tokens_out": usage.output,
                "estimated": usage.estimated,
            }),
        );
        self.tokens.lock().expect("telemetry lock").add(usage);
    }

    /// Record one tool execution.
    pub fn tool_call(&self, tool: &str, ok: bool, duration_ms: u128) {
        self.record(
            "tool.call",
            "run",
            now_ms().saturating_sub(duration_ms),
            duration_ms,
            if ok { "ok" } else { "error" },
            serde_json::json!({ "tool": tool }),
        );
    }

    /// Tokens across every llm.call span in this run.
    pub fn total_tokens(&self) -> TokenUsage {
        *self.tokens.lock().expect("telemetry lock")
    }

    /// Compute cost from the recorded tokens (0 without a price table).
    pub fn cost_usd(&self, usage: &TokenUsage) -> f64 {
        match self.pricing {
            Some((pin, pout)) => {
                usage.input as f64 / 1e6 * pin + usage.output as f64 / 1e6 * pout
            }
            None => 0.0,
        }
    }

    /// Finish the run: write the JSONL file (best effort) and, when an
    /// endpoint is configured, POST the spans as OTLP/JSON. Returns a
    /// one-line summary for the CLI.
    pub async fn finish(&self, answer_chars: usize) -> String {
        let tokens = self.total_tokens();
        let cost = self.cost_usd(&tokens);
        let span_count = self.spans.lock().expect("telemetry lock").len();

        // JSONL persistence (best effort — telemetry must never fail a run).
        let mut lines = Vec::with_capacity(span_count + 1);
        for s in self.spans.lock().expect("telemetry lock").iter() {
            if let Ok(line) = serde_json::to_string(s) {
                lines.push(line);
            }
        }
        if !lines.is_empty() {
            let _ = std::fs::create_dir_all(&self.dir);
            let path = self.dir.join(format!("{}.jsonl", self.run_id));
            let _ = std::fs::write(&path, format!("{}\n", lines.join("\n")));
        }

        if let Some(endpoint) = &self.endpoint {
            self.post_otlp(endpoint).await;
        }

        let tok_str = if tokens.estimated {
            format!("~{} in / ~{} out (estimated)", fmt_k(tokens.input), fmt_k(tokens.output))
        } else {
            format!("{} in / {} out", fmt_k(tokens.input), fmt_k(tokens.output))
        };
        let cost_str = if cost > 0.0 {
            format!(" · ${:.4}", cost)
        } else {
            String::new()
        };
        format!(
            "xencode · telemetry: {span_count} spans · {tok_str}{cost_str} · {answer_chars} answer chars"
        )
    }

    /// Fire-and-forget OTLP/HTTP(JSON) export. Failures are ignored.
    async fn post_otlp(&self, endpoint: &str) {
        let spans = self.spans.lock().expect("telemetry lock");
        if spans.is_empty() {
            return;
        }
        let body = serde_json::json!({
            "resourceSpans": [{
                "resource": {
                    "attributes": [
                        { "key": "service.name", "value": { "stringValue": "xencode" } },
                        { "key": "xencode.run_id", "value": { "stringValue": self.run_id } }
                    ]
                },
                "scopeSpans": [{
                    "scope": { "name": "xencode.agent", "version": xencode_script::VERSION },
                    "spans": spans.iter().map(|s| serde_json::json!({
                        "traceId": self.trace_id,
                        "spanId": s.id,
                        "parentSpanId": s.parent,
                        "name": s.name,
                        "kind": "SPAN_KIND_INTERNAL",
                        "startTimeUnixNano": (s.start_unix_ms as u128 * 1_000_000).to_string(),
                        "endTimeUnixNano": ((s.start_unix_ms + s.duration_ms) as u128 * 1_000_000).to_string(),
                        "attributes": attrs_to_otlp(&s.attributes),
                        "status": { "code": if s.status == "ok" { "STATUS_CODE_UNSET" } else { "STATUS_CODE_ERROR" } }
                    })).collect::<Vec<_>>()
                }]
            }]
        });
        drop(spans);

        let client = reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(3))
            .build();
        if let Ok(client) = client {
            let _ = client.post(endpoint).json(&body).send().await;
        }
    }
}

fn attrs_to_otlp(attrs: &serde_json::Value) -> Vec<serde_json::Value> {
    let mut out = Vec::new();
    if let Some(obj) = attrs.as_object() {
        for (k, v) in obj {
            let value = match v {
                serde_json::Value::String(s) => serde_json::json!({ "stringValue": s }),
                serde_json::Value::Number(n) => serde_json::json!({ "doubleValue": n.as_f64() }),
                serde_json::Value::Bool(b) => serde_json::json!({ "boolValue": b }),
                other => serde_json::json!({ "stringValue": other.to_string() }),
            };
            out.push(serde_json::json!({ "key": k, "value": value }));
        }
    }
    out
}

/// Guard that records the `agent.run` span on drop (covers error paths).
pub struct RunSpanGuard {
    tele: std::sync::Arc<Telemetry>,
    start: u128,
}

impl RunSpanGuard {
    /// Record with explicit outcome + attributes (call before returning).
    pub fn finish(self, status: &'static str, attributes: serde_json::Value) {
        let duration = now_ms().saturating_sub(self.start);
        self.tele.record("agent.run", "", self.start, duration, status, attributes);
        std::mem::forget(self); // Drop must not double-record
    }
}

impl Drop for RunSpanGuard {
    fn drop(&mut self) {
        let duration = now_ms().saturating_sub(self.start);
        self.tele.record("agent.run", "", self.start, duration, "error", serde_json::json!({"ended": "implicit"}));
    }
}

fn now_ms() -> u128 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_millis()).unwrap_or(0)
}

/// Deterministic-ish hex id (time + counter xorshift), `chars` wide — trace
/// and span ids without pulling in a uuid dependency.
fn hex_id(time_ms: u64, counter: u64, chars: usize) -> String {
    let mut x = time_ms ^ (counter.wrapping_mul(0x9E37_79B9_7F4A_7C15));
    x ^= x >> 12;
    x ^= x << 25;
    x ^= x >> 27;
    let mut out = String::with_capacity(chars);
    while out.len() < chars {
        x = x.wrapping_mul(6364136223846793005).wrapping_add(1442695040888963407);
        out.push_str(&format!("{:016x}", x));
    }
    out.truncate(chars);
    out
}

fn fmt_k(n: u64) -> String {
    if n >= 1_000_000 {
        format!("{:.1}M", n as f64 / 1e6)
    } else if n >= 1_000 {
        format!("{:.1}k", n as f64 / 1e3)
    } else {
        n.to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;

    fn tmp(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("xencode-tele-{}-{}", tag, std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn usage_estimate_and_merge() {
        let est = TokenUsage::estimate(&"x".repeat(400));
        assert_eq!(est.input, 100);
        assert!(est.estimated);

        let mut total = TokenUsage::default();
        total.add(&TokenUsage { input: 10, output: 5, estimated: false });
        total.add(&est);
        assert_eq!(total.input, 110);
        assert_eq!(total.output, 5);
        assert!(total.estimated, "any estimated input marks the total");
    }

    #[test]
    fn cost_from_price_table() {
        let dir = tmp("cost");
        let cfg: TelemetryConfig =
            toml::from_str("input_cost_per_mtok = 1.0\noutput_cost_per_mtok = 3.0\n").unwrap();
        let tele = Telemetry::for_run(&dir, &cfg).unwrap();
        let usage = TokenUsage { input: 1_000_000, output: 500_000, estimated: false };
        let cost = tele.cost_usd(&usage);
        assert!((cost - 2.5).abs() < 1e-9, "1.0 + 0.5*3.0 = {cost}");

        let no_price = Telemetry::for_run(&dir, &TelemetryConfig::default()).unwrap();
        assert_eq!(no_price.cost_usd(&usage), 0.0);
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn disabled_by_explicit_false() {
        let dir = tmp("off");
        let cfg: TelemetryConfig = toml::from_str("enabled = false\n").unwrap();
        assert!(Telemetry::for_run(&dir, &cfg).is_none());
        assert!(Telemetry::for_run(&dir, &TelemetryConfig::default()).is_some());
        let _ = fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn spans_land_in_jsonl() {
        let dir = tmp("jsonl");
        let cfg: TelemetryConfig = toml::from_str("").unwrap();
        let tele = Telemetry::for_run(&dir, &cfg).unwrap();

        {
            let guard = tele.run_span();
            tele.llm_call("glm-4.6", &TokenUsage { input: 1200, output: 300, estimated: false }, 900, true);
            tele.tool_call("grep", true, 12);
            tele.tool_call("bash", false, 45);
            guard.finish("ok", serde_json::json!({"iterations": 3}));
        }

        let usage = tele.total_tokens();
        assert_eq!(usage.input, 1200);
        assert_eq!(usage.output, 300);
        assert!(!usage.estimated);

        let summary = tele.finish(42).await;
        assert!(summary.contains("4 spans"), "run + llm + 2 tools — got: {summary}");
        assert!(summary.contains("1.2k in / 300 out"));
        assert!(summary.contains("42 answer chars"));

        let mut path = None;
        for e in fs::read_dir(dir.join(".xencode/telemetry")).unwrap().flatten() {
            path = Some(e.path());
        }
        let path = path.expect("jsonl file written");
        let text = fs::read_to_string(&path).unwrap();
        let names: Vec<String> = text
            .lines()
            .filter_map(|l| {
                serde_json::from_str::<serde_json::Value>(l)
                    .ok()
                    .and_then(|v| v["name"].as_str().map(str::to_string))
            })
            .collect();
        assert_eq!(names, vec!["llm.call", "tool.call", "tool.call", "agent.run"]);
        let _ = fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn implicit_drop_records_error_span() {
        let dir = tmp("drop");
        let cfg: TelemetryConfig = toml::from_str("").unwrap();
        let tele = Telemetry::for_run(&dir, &cfg).unwrap();
        {
            let _guard = tele.run_span();
            // dropped without finish() → error span
        }
        let summary = tele.finish(0).await;
        assert!(summary.contains("1 spans"), "got: {summary}");
        let _ = fs::remove_dir_all(&dir);
    }

    #[test]
    fn hex_ids_are_wellformed() {
        let t = hex_id(1_700_000_000_000, 1, 32);
        assert_eq!(t.len(), 32);
        assert!(t.chars().all(|c| c.is_ascii_hexdigit()));
        let a = hex_id(1_700_000_000_000, 2, 16);
        let b = hex_id(1_700_000_000_000, 3, 16);
        assert_ne!(a, b, "distinct counters → distinct span ids");
    }
}
