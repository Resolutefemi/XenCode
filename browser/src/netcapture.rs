//! Network capture — record every XHR/fetch a page makes (Phase 8).
//!
//! Wraps the CDP `Network` domain around a live [`Space`]: `Network.enable`
//! on the session, then a pump task folds the event stream into
//! [`NetEntry`] records (one per request, redirects split into hops):
//!
//! - `Network.requestWillBeSent` — opens an entry (method, URL, headers,
//!   post data, initiator). A `redirectResponse` finalizes the previous
//!   hop under the same request id and opens the next one.
//! - `Network.requestWillBeSentExtraInfo` — cookie presence flag (values
//!   themselves are treated as secrets and never copied anywhere).
//! - `Network.responseReceived` — status, mime type, response headers.
//! - `Network.loadingFinished` — marks done; fetches the response body
//!   (JSON/text only, size-capped) and the request post data when the
//!   event didn't carry it inline.
//! - `Network.loadingFailed` — records the error text.
//!
//! The pump is deliberately split: [`CaptureState`] is a pure state
//! machine (unit-testable without a browser) and the task around it only
//! routes events and performs the two CDP body calls.

use std::collections::HashMap;
use std::time::{Duration, SystemTime, UNIX_EPOCH};

use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use tokio::sync::watch;

use anyhow::Context as _;

use crate::cdp::Connection;
use crate::space::{Space, CALL_TIMEOUT};

#[cfg(test)]
use crate::cdp::Event;

/// Response/request bodies are fetched only for these mime shapes.
fn body_worthy_mime(mime: &str) -> bool {
    let m = mime.to_ascii_lowercase();
    m.contains("json") || m.starts_with("text/") || m.contains("xml")
}

/// Options for a capture session.
#[derive(Debug, Clone)]
pub struct CaptureOptions {
    /// Fetch response bodies (JSON/text/xml, below `body_cap`) and request
    /// post data that wasn't inlined. Default on — response shapes are the
    /// point of API discovery.
    pub bodies: bool,
    /// Per-entry body cap in bytes (decoded); larger bodies are truncated.
    pub body_cap: usize,
    /// Total decoded-body budget for the whole session (memory safety).
    pub body_budget: usize,
    /// Hard cap on recorded entries.
    pub max_entries: usize,
}

impl Default for CaptureOptions {
    fn default() -> Self {
        CaptureOptions {
            bodies: true,
            body_cap: 256 * 1024,
            body_budget: 8 * 1024 * 1024,
            max_entries: 4_000,
        }
    }
}

/// How the request authenticated, as observed on the wire.
#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "kebab-case")]
pub enum AuthKind {
    #[default]
    None,
    Bearer,
    ApiKey,
    Cookie,
    QueryToken,
}

/// One captured network exchange (request + response pair).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct NetEntry {
    pub request_id: String,
    pub url: String,
    pub method: String,
    pub status: Option<u16>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub status_text: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub mime_type: Option<String>,
    /// CDP resource type: `XHR`, `Fetch`, `Document`, `Script`, …
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub resource_type: Option<String>,
    #[serde(default)]
    pub request_headers: Vec<(String, String)>,
    #[serde(default)]
    pub response_headers: Vec<(String, String)>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub post_data: Option<String>,
    /// Decoded query pairs, in URL order.
    #[serde(default)]
    pub query: Vec<(String, String)>,
    #[serde(default)]
    pub auth: AuthKind,
    /// `initiator.type` from CDP: `script`, `parser`, `other`, …
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub initiator: Option<String>,
    /// Previous hop URL when this entry continued a redirect chain.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub redirected_from: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub body: Option<String>,
    #[serde(default)]
    pub body_truncated: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
    pub started_ms: u128,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub duration_ms: Option<u64>,
    pub finished: bool,
    pub failed: bool,
    /// CDP `request.hasPostData` — post data must be fetched separately.
    #[serde(default, skip)]
    pub(crate) has_post_data: bool,
}

impl NetEntry {
    /// Compact identity for logs: `GET https://host/path → 200`.
    pub fn label(&self) -> String {
        match (self.method.as_str(), self.status) {
            (m, Some(s)) => format!("{m} {url} → {s}", url = self.url),
            (m, None) => format!("{m} {url}", url = self.url),
        }
    }
}

/// Headers whose values are secrets by convention (redacted by default).
pub const SENSITIVE_HEADERS: &[&str] = &[
    "authorization",
    "proxy-authorization",
    "cookie",
    "set-cookie",
    "x-api-key",
    "api-key",
    "apikey",
    "x-auth-token",
    "x-access-token",
    "x-session-token",
    "x-csrf-token",
    "x-xsrf-token",
    "csrf-token",
    "x-amz-security-token",
    "private-token",
];

/// Query parameter names whose values are secrets by convention.
pub const SENSITIVE_QUERY: &[&str] = &[
    "token",
    "access_token",
    "refresh_token",
    "id_token",
    "api_key",
    "apikey",
    "api-key",
    "key",
    "secret",
    "client_secret",
    "password",
    "passwd",
    "sig",
    "signature",
    "session",
    "sessionid",
    "session_id",
    "jwt",
    "auth",
    "private_key",
];

/// The redaction marker written in place of secret values.
pub const REDACTED: &str = "***";

pub fn is_sensitive_header(name: &str) -> bool {
    SENSITIVE_HEADERS.contains(&name.to_ascii_lowercase().as_str())
}

pub fn is_sensitive_query(name: &str) -> bool {
    SENSITIVE_QUERY.contains(&name.to_ascii_lowercase().as_str())
}

/// Redact header values in place (case-insensitive name match).
pub fn redact_headers(headers: &mut [(String, String)]) {
    for (k, v) in headers.iter_mut() {
        if is_sensitive_header(k) && !v.is_empty() {
            *v = REDACTED.to_string();
        }
    }
}

/// Redact query values in place.
pub fn redact_query(query: &mut [(String, String)]) {
    for (k, v) in query.iter_mut() {
        if is_sensitive_query(k) && !v.is_empty() {
            *v = REDACTED.to_string();
        }
    }
}

/// Redact an entry's header/query values (bodies are left intact —
/// schema inference needs them; never share unredacted captures).
pub fn redact_entry(e: &mut NetEntry) {
    redact_headers(&mut e.request_headers);
    redact_headers(&mut e.response_headers);
    redact_query(&mut e.query);
}

/// Detect auth kinds from the observed headers/flags/query.
pub fn detect_auth(
    request_headers: &[(String, String)],
    had_cookie_header: bool,
    query: &[(String, String)],
) -> Vec<AuthKind> {
    let mut out = Vec::new();
    for (k, v) in request_headers {
        let kl = k.to_ascii_lowercase();
        let vl = v.to_ascii_lowercase();
        if kl == "authorization" && !v.is_empty() {
            if vl.starts_with("bearer") {
                out.push(AuthKind::Bearer);
            } else {
                out.push(AuthKind::ApiKey);
            }
        } else if (kl == "x-api-key" || kl == "api-key" || kl == "apikey") && !v.is_empty() {
            out.push(AuthKind::ApiKey);
        }
    }
    if had_cookie_header
        || request_headers
            .iter()
            .any(|(k, _)| k.eq_ignore_ascii_case("cookie"))
    {
        out.push(AuthKind::Cookie);
    }
    if query.iter().any(|(k, v)| is_sensitive_query(k) && !v.is_empty()) {
        out.push(AuthKind::QueryToken);
    }
    out
}

// ---------------------------------------------------------------------
// state machine
// ---------------------------------------------------------------------

/// Pure event-fold for one capture. The pump feeds it session-filtered
/// CDP events; body fetching happens outside (needs an async CDP call).
#[derive(Debug, Default)]
pub struct CaptureState {
    open: HashMap<String, NetEntry>,
    done: Vec<NetEntry>,
    order: Vec<String>, // request ids in first-seen order
    cookies_seen: HashMap<String, bool>,
    start_ts: HashMap<String, f64>,
    body_budget_left: usize,
    body_cap: usize,
    max_entries: usize,
    dropped: usize,
}

impl CaptureState {
    pub fn new(opts: &CaptureOptions) -> CaptureState {
        CaptureState {
            body_budget_left: opts.body_budget,
            body_cap: opts.body_cap,
            max_entries: opts.max_entries,
            ..Default::default()
        }
    }

    pub fn len(&self) -> usize {
        self.done.len() + self.open.len()
    }

    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    pub fn dropped(&self) -> usize {
        self.dropped
    }

    /// Route one session-filtered CDP event.
    pub fn handle_event(&mut self, method: &str, params: &Value) {
        match method {
            "Network.requestWillBeSent" => self.on_request(params),
            "Network.requestWillBeSentExtraInfo" => self.on_request_extra(params),
            "Network.responseReceived" => self.on_response(params),
            // responseReceivedExtraInfo cookie values are intentionally
            // never copied anywhere — cookie auth is a presence flag only.
            "Network.responseReceivedExtraInfo" => {}
            "Network.loadingFailed" => self.on_failed(params),
            // loadingFinished needs the async body fetch — pump calls on_finished.
            _ => {}
        }
    }

    fn now_ms() -> u128 {
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map(|d| d.as_millis())
            .unwrap_or(0)
    }

    fn headers_of(v: &Value) -> Vec<(String, String)> {
        v.as_object()
            .map(|m| {
                m.iter()
                    .map(|(k, val)| {
                        (
                            k.to_string(),
                            val.as_str().map(str::to_string).unwrap_or_else(|| val.to_string()),
                        )
                    })
                    .collect()
            })
            .unwrap_or_default()
    }

    fn on_request(&mut self, p: &Value) {
        let Some(id) = p.get("requestId").and_then(Value::as_str) else { return };
        let Some(req) = p.get("request") else { return };
        let url = req
            .get("url")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string();
        let method = req
            .get("method")
            .and_then(Value::as_str)
            .unwrap_or("GET")
            .to_ascii_uppercase();

        // Redirect hop: finalize the previous entry (with the redirect
        // response's status/mime), then open a new one under the same id.
        let redirected_from = if let Some(prev_res) = p.get("redirectResponse") {
            if let Some(prev) = self.open.remove(id) {
                let mut hop = prev;
                hop.status = prev_res
                    .get("status")
                    .and_then(Value::as_u64)
                    .map(|s| s as u16)
                    .or(hop.status);
                hop.status_text = prev_res
                    .get("statusText")
                    .and_then(Value::as_str)
                    .map(str::to_string)
                    .filter(|s| !s.is_empty())
                    .or(hop.status_text);
                if hop.mime_type.is_none() {
                    hop.mime_type = prev_res
                        .get("mimeType")
                        .and_then(Value::as_str)
                        .map(str::to_string);
                }
                self.push_done(hop);
            }
            prev_res
                .get("url")
                .and_then(Value::as_str)
                .map(str::to_string)
                .or_else(|| self.done.last().map(|e| e.url.clone()))
        } else {
            None
        };

        if self.len() >= self.max_entries {
            self.dropped += 1;
            return;
        }

        let query = crate::apidisco::parse_query(&url);
        let request_headers = Self::headers_of(req.get("headers").unwrap_or(&Value::Null));
        let auth = detect_auth(
            &request_headers,
            self.cookies_seen.get(id).copied().unwrap_or(false),
            &query,
        )
        .into_iter()
        .next()
        .unwrap_or(AuthKind::None);

        let ts = p.get("timestamp").and_then(Value::as_f64);
        if let Some(t) = ts {
            self.start_ts.insert(id.to_string(), t);
        }

        let entry = NetEntry {
            request_id: id.to_string(),
            url,
            method,
            status: None,
            status_text: None,
            mime_type: None,
            resource_type: p.get("type").and_then(Value::as_str).map(str::to_string),
            request_headers,
            response_headers: Vec::new(),
            post_data: req.get("postData").and_then(Value::as_str).map(str::to_string),
            query,
            auth,
            initiator: p
                .get("initiator")
                .and_then(|i| i.get("type"))
                .and_then(Value::as_str)
                .map(str::to_string),
            redirected_from,
            body: None,
            body_truncated: false,
            error: None,
            started_ms: Self::now_ms(),
            duration_ms: None,
            finished: false,
            failed: false,
            has_post_data: req
                .get("hasPostData")
                .and_then(Value::as_bool)
                .unwrap_or(false),
        };
        self.order.push(id.to_string());
        self.open.insert(id.to_string(), entry);
    }

    fn on_request_extra(&mut self, p: &Value) {
        let Some(id) = p.get("requestId").and_then(Value::as_str) else { return };
        let had_cookie = p
            .get("headers")
            .and_then(Value::as_object)
            .map(|m| m.keys().any(|k| k.eq_ignore_ascii_case("cookie")))
            .unwrap_or(false);
        if had_cookie {
            self.cookies_seen.insert(id.to_string(), true);
            if let Some(e) = self.open.get_mut(id) {
                if e.auth == AuthKind::None {
                    e.auth = AuthKind::Cookie;
                }
            }
        }
    }

    fn on_response(&mut self, p: &Value) {
        let Some(id) = p.get("requestId").and_then(Value::as_str) else { return };
        let Some(res) = p.get("response") else { return };
        let Some(e) = self.open.get_mut(id) else { return };
        e.url = res
            .get("url")
            .and_then(Value::as_str)
            .map(str::to_string)
            .unwrap_or_else(|| e.url.clone());
        e.status = res.get("status").and_then(Value::as_u64).map(|s| s as u16);
        e.status_text = res
            .get("statusText")
            .and_then(Value::as_str)
            .map(str::to_string)
            .filter(|s| !s.is_empty());
        e.mime_type = res.get("mimeType").and_then(Value::as_str).map(str::to_string);
        e.response_headers = Self::headers_of(res.get("headers").unwrap_or(&Value::Null));
        if let Some(t) = p.get("type").and_then(Value::as_str) {
            e.resource_type = Some(t.to_string());
        }
    }

    fn on_failed(&mut self, p: &Value) {
        let Some(id) = p.get("requestId").and_then(Value::as_str) else { return };
        if let Some(mut e) = self.open.remove(id) {
            e.failed = true;
            e.finished = true;
            e.error = p.get("errorText").and_then(Value::as_str).map(str::to_string);
            self.push_done(e);
        }
    }

    /// `Network.loadingFinished` — with the optional body/post-data fetch
    /// results (both `None` when bodies are disabled or the call failed).
    pub fn on_finished(
        &mut self,
        params: &Value,
        body: Option<(String, bool)>, // (text, base64Encoded)
        post_data: Option<String>,
    ) {
        let Some(id) = params.get("requestId").and_then(Value::as_str) else { return };
        if let Some(mut e) = self.open.remove(id) {
            e.finished = true;
            let ts = params.get("timestamp").and_then(Value::as_f64);
            if let (Some(end), Some(start)) = (ts, self.start_ts.get(id).copied()) {
                e.duration_ms = Some(((end - start).max(0.0) * 1000.0).round() as u64);
            }
            if e.post_data.is_none() {
                e.post_data = post_data.filter(|s| !s.is_empty());
            }
            if let Some((text, base64)) = body {
                if base64 {
                    // Binary bodies don't inform JSON shapes; presence only.
                    e.body = Some(format!("[binary {} bytes]", text.len()));
                    e.body_truncated = true;
                } else {
                    let mut owned = text;
                    if owned.len() > self.body_cap {
                        owned.truncate(self.body_cap);
                        e.body_truncated = true;
                    }
                    e.body = Some(owned);
                }
            }
            self.push_done(e);
        }
    }

    /// Should the pump try to fetch the response body for this entry?
    pub fn wants_body(&self, params: &Value, opts: &CaptureOptions) -> bool {
        if !opts.bodies || self.body_budget_left == 0 {
            return false;
        }
        let Some(id) = params.get("requestId").and_then(Value::as_str) else { return false };
        let Some(e) = self.open.get(id) else { return false };
        if e.body.is_some() {
            return false;
        }
        match &e.mime_type {
            Some(m) => body_worthy_mime(m),
            None => false,
        }
    }

    /// Should the pump fetch post data that wasn't inlined?
    pub fn wants_post_data(&self, params: &Value) -> bool {
        let Some(id) = params.get("requestId").and_then(Value::as_str) else { return false };
        let Some(e) = self.open.get(id) else { return false };
        e.post_data.is_none() && e.has_post_data
    }

    /// Record a fetched body on the still-open entry.
    pub fn store_body(&mut self, id: &str, text: String, base64: bool, cap: usize) {
        let budget = text.len().min(self.body_budget_left);
        self.body_budget_left -= budget;
        if let Some(e) = self.open.get_mut(id) {
            if base64 {
                e.body = Some(format!("[binary {} bytes]", text.len()));
                e.body_truncated = true;
            } else {
                let mut owned = text;
                if owned.len() > cap {
                    owned.truncate(cap);
                    e.body_truncated = true;
                }
                e.body = Some(owned);
            }
        }
    }

    /// Record fetched post data on the still-open entry.
    pub fn store_post_data(&mut self, id: &str, text: String) {
        if let Some(e) = self.open.get_mut(id) {
            if e.post_data.is_none() && !text.is_empty() {
                e.post_data = Some(text);
            }
        }
    }

    /// Finalize everything still open (end of capture) and return the
    /// entries in first-seen order.
    pub fn finish(mut self) -> Vec<NetEntry> {
        let ids: Vec<String> = self.open.keys().cloned().collect();
        for id in ids {
            if let Some(mut e) = self.open.remove(&id) {
                e.finished = false; // response never arrived / capture stopped
                self.push_done(e);
            }
        }
        let mut out = Vec::with_capacity(self.done.len());
        for id in &self.order {
            if let Some(pos) = self.done.iter().position(|e| e.request_id == *id) {
                out.push(self.done.remove(pos));
            }
        }
        out.extend(self.done.drain(..)); // anything out of order still lands
        out
    }

    fn push_done(&mut self, e: NetEntry) {
        self.done.push(e);
    }
}

// ---------------------------------------------------------------------
// pump
// ---------------------------------------------------------------------

/// A running network capture. Drop = abandon (browser death also ends it);
/// `stop()` disables the domain and returns the collected entries.
pub struct NetCapture {
    pump: Option<tokio::task::JoinHandle<Vec<NetEntry>>>,
    stop_tx: watch::Sender<bool>,
    conn: Connection,
    session: String,
}

impl NetCapture {
    /// Enable the Network domain on the Space's session and start pumping.
    pub async fn start(space: &Space, opts: CaptureOptions) -> anyhow::Result<NetCapture> {
        space
            .call("Network.enable", json!({}))
            .await
            .map_err(|e| anyhow::anyhow!("Network.enable: {e}"))?;

        let mut events = space.events_resubscribe();
        let conn = space.conn_clone();
        let session = space.session_id().to_string();
        let (stop_tx, mut stop_rx) = watch::channel(false);

        let pump_session = session.clone();
        let pump = tokio::spawn(async move {
            let mut state = CaptureState::new(&opts);
            let session = pump_session;
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
                                if !ev.method.starts_with("Network.") {
                                    continue;
                                }
                                match ev.method.as_str() {
                                    "Network.loadingFinished" => {
                                        let id = ev
                                            .params
                                            .get("requestId")
                                            .and_then(Value::as_str)
                                            .unwrap_or_default()
                                            .to_string();
                                        let want_body = state.wants_body(&ev.params, &opts);
                                        let want_post = state.wants_post_data(&ev.params);
                                        let mut post: Option<String> = None;
                                        if want_body {
                                            if let Ok(res) = conn
                                                .call(
                                                    "Network.getResponseBody",
                                                    json!({"requestId": id}),
                                                    Some(&session),
                                                    CALL_TIMEOUT,
                                                )
                                                .await
                                            {
                                                let text = res
                                                    .get("body")
                                                    .and_then(Value::as_str)
                                                    .unwrap_or_default()
                                                    .to_string();
                                                let b64 = res
                                                    .get("base64Encoded")
                                                    .and_then(Value::as_bool)
                                                    .unwrap_or(false);
                                                state.store_body(&id, text, b64, opts.body_cap);
                                            }
                                        }
                                        if want_post {
                                            if let Ok(res) = conn
                                                .call(
                                                    "Network.getRequestPostData",
                                                    json!({"requestId": id}),
                                                    Some(&session),
                                                    CALL_TIMEOUT,
                                                )
                                                .await
                                            {
                                                post = res
                                                    .get("postData")
                                                    .and_then(Value::as_str)
                                                    .map(str::to_string);
                                                if let Some(p) = post.clone() {
                                                    state.store_post_data(&id, p);
                                                }
                                            }
                                        }
                                        state.on_finished(&ev.params, None, post);
                                    }
                                    "Network.requestWillBeSent" => {
                                        state.handle_event(&ev.method, &ev.params);
                                    }
                                    _ => state.handle_event(&ev.method, &ev.params),
                                }
                            }
                            Err(tokio::sync::broadcast::error::RecvError::Lagged(_)) => continue,
                            Err(_) => break, // connection closed
                        }
                    }
                }
            }
            state.finish()
        });

        Ok(NetCapture {
            pump: Some(pump),
            stop_tx,
            conn: space.conn_clone(),
            session,
        })
    }

    /// Stop the capture and return every recorded entry (open entries are
    /// finalized as unfinished).
    pub async fn stop(mut self) -> anyhow::Result<Vec<NetEntry>> {
        let _ = self
            .conn
            .call(
                "Network.disable",
                json!({}),
                Some(&self.session),
                Duration::from_secs(2),
            )
            .await;
        let _ = self.stop_tx.send(true);
        match self.pump.take() {
            Some(h) => h
                .await
                .map_err(|e| anyhow::anyhow!("capture pump crashed: {e}")),
            None => Ok(Vec::new()),
        }
    }
}

impl Drop for NetCapture {
    fn drop(&mut self) {
        let _ = self.stop_tx.send(true);
        if let Some(p) = self.pump.take() {
            p.abort();
        }
    }
}

// ---------------------------------------------------------------------
// capture artifact
// ---------------------------------------------------------------------

/// Saved capture — the `--out` file of `xencode api capture`. The raw
/// entries are the durable truth; catalogs/OpenAPI documents are derived
/// from it on demand.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct CaptureFile {
    pub captured_at_ms: u128,
    pub start_url: String,
    /// Whether secret redaction was applied at capture time.
    pub redacted: bool,
    pub entries: Vec<NetEntry>,
}

impl CaptureFile {
    pub fn new(start_url: &str, redacted: bool, entries: Vec<NetEntry>) -> CaptureFile {
        CaptureFile {
            captured_at_ms: SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .map(|d| d.as_millis())
                .unwrap_or(0),
            start_url: start_url.to_string(),
            redacted,
            entries,
        }
    }

    pub fn save(&self, path: &std::path::Path) -> anyhow::Result<()> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let json = serde_json::to_string_pretty(self)?;
        std::fs::write(path, json)?;
        Ok(())
    }

    pub fn load(path: &std::path::Path) -> anyhow::Result<CaptureFile> {
        let text = std::fs::read_to_string(path)
            .with_context(|| format!("read capture {}", path.display()))?;
        serde_json::from_str(&text).with_context(|| format!("parse capture {}", path.display()))
    }
}

// ---------------------------------------------------------------------
// tests
// ---------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    fn ev(method: &str, params: Value) -> Event {
        Event { session_id: Some("S".into()), method: method.into(), params }
    }

    /// Feed a synthetic request/response/finished exchange.
    fn feed_exchange(st: &mut CaptureState, id: &str, url: &str, method: &str) {
        st.handle_event(
            "Network.requestWillBeSent",
            &json!({
                "requestId": id,
                "timestamp": 10.0,
                "type": "XHR",
                "initiator": {"type": "script"},
                "request": {
                    "url": url,
                    "method": method,
                    "headers": {"Content-Type": "application/json", "Authorization": "Bearer abc"},
                    "hasPostData": method != "GET",
                    "postData": if method == "GET" { Value::Null } else { Value::String("{\"a\":1}".to_string()) }
                }
            }),
        );
        st.handle_event(
            "Network.responseReceived",
            &json!({
                "requestId": id,
                "type": "XHR",
                "response": {
                    "url": url,
                    "status": 200,
                    "statusText": "OK",
                    "mimeType": "application/json",
                    "headers": {"Content-Type": "application/json"}
                }
            }),
        );
        st.on_finished(&json!({"requestId": id, "timestamp": 10.25}), None, None);
    }

    #[test]
    fn pairs_request_response_by_id() {
        let mut st = CaptureState::new(&CaptureOptions::default());
        feed_exchange(&mut st, "1", "https://api.example.com/v1/things", "GET");
        let entries = st.finish();
        assert_eq!(entries.len(), 1);
        let e = &entries[0];
        assert_eq!(e.url, "https://api.example.com/v1/things");
        assert_eq!(e.method, "GET");
        assert_eq!(e.status, Some(200));
        assert_eq!(e.mime_type.as_deref(), Some("application/json"));
        assert_eq!(e.duration_ms, Some(250));
        assert!(e.finished);
        assert_eq!(e.auth, AuthKind::Bearer);
        assert_eq!(
            e.request_headers,
            vec![
                ("Authorization".to_string(), "Bearer abc".to_string()),
                ("Content-Type".to_string(), "application/json".to_string()),
            ]
        );
    }

    #[test]
    fn redirect_splits_into_hops() {
        let mut st = CaptureState::new(&CaptureOptions::default());
        st.handle_event(
            "Network.requestWillBeSent",
            &json!({
                "requestId": "r1", "timestamp": 1.0, "type": "XHR",
                "request": {"url": "https://h.test/a", "method": "GET", "headers": {}}
            }),
        );
        st.handle_event(
            "Network.requestWillBeSent",
            &json!({
                "requestId": "r1", "timestamp": 2.0, "type": "XHR",
                "redirectResponse": {"url": "https://h.test/a", "status": 302, "headers": {}},
                "request": {"url": "https://h.test/b", "method": "GET", "headers": {}}
            }),
        );
        st.handle_event(
            "Network.responseReceived",
            &json!({"requestId": "r1", "response": {"url": "https://h.test/b", "status": 200, "headers": {}}}),
        );
        st.on_finished(&json!({"requestId": "r1"}), None, None);
        let entries = st.finish();
        assert_eq!(entries.len(), 2, "redirect hop + final entry");
        assert_eq!(entries[0].url, "https://h.test/a");
        assert_eq!(entries[0].status, Some(302));
        assert_eq!(entries[1].url, "https://h.test/b");
        assert_eq!(entries[1].redirected_from.as_deref(), Some("https://h.test/a"));
    }

    #[test]
    fn query_parsed_and_auth_detected() {
        let mut st = CaptureState::new(&CaptureOptions::default());
        st.handle_event(
            "Network.requestWillBeSent",
            &json!({
                "requestId": "q1", "timestamp": 1.0, "type": "Fetch",
                "request": {
                    "url": "https://h.test/api?token=abc123&page=2",
                    "method": "POST",
                    "headers": {"X-API-Key": "zzz"}
                }
            }),
        );
        let entries = st.finish();
        assert_eq!(entries[0].query, vec![
            ("token".to_string(), "abc123".to_string()),
            ("page".to_string(), "2".to_string()),
        ]);
        // header auth (ApiKey) wins over the query token for the entry's
        // primary auth kind
        assert_eq!(entries[0].auth, AuthKind::ApiKey);
    }

    #[test]
    fn cookie_extra_info_sets_cookie_auth() {
        let mut st = CaptureState::new(&CaptureOptions::default());
        st.handle_event(
            "Network.requestWillBeSent",
            &json!({
                "requestId": "c1", "timestamp": 1.0,
                "request": {"url": "https://h.test/x", "method": "GET", "headers": {}}
            }),
        );
        st.handle_event(
            "Network.requestWillBeSentExtraInfo",
            &json!({"requestId": "c1", "headers": {"Cookie": "sid=xyz"}}),
        );
        let entries = st.finish();
        assert_eq!(entries[0].auth, AuthKind::Cookie);
    }

    #[test]
    fn loading_failed_records_error() {
        let mut st = CaptureState::new(&CaptureOptions::default());
        st.handle_event(
            "Network.requestWillBeSent",
            &json!({
                "requestId": "f1", "timestamp": 1.0,
                "request": {"url": "https://h.test/x", "method": "GET", "headers": {}}
            }),
        );
        st.handle_event(
            "Network.loadingFailed",
            &json!({"requestId": "f1", "errorText": "net::ERR_FAILED", "canceled": false}),
        );
        let entries = st.finish();
        assert!(entries[0].failed);
        assert_eq!(entries[0].error.as_deref(), Some("net::ERR_FAILED"));
    }

    #[test]
    fn max_entries_cap_drops_new_requests() {
        let mut opts = CaptureOptions::default();
        opts.max_entries = 2;
        let mut st = CaptureState::new(&opts);
        for i in 0..5 {
            st.handle_event(
                "Network.requestWillBeSent",
                &json!({
                    "requestId": i.to_string(), "timestamp": 1.0,
                    "request": {"url": format!("https://h.test/{i}"), "method": "GET", "headers": {}}
                }),
            );
        }
        assert_eq!(st.dropped(), 3);
        assert_eq!(st.len(), 2);
    }

    #[test]
    fn body_store_truncates_and_tracks_budget() {
        let mut opts = CaptureOptions::default();
        opts.body_cap = 10;
        opts.body_budget = 20;
        let mut st = CaptureState::new(&opts);
        st.handle_event(
            "Network.requestWillBeSent",
            &json!({
                "requestId": "b1", "timestamp": 1.0,
                "request": {"url": "https://h.test/x", "method": "GET", "headers": {}}
            }),
        );
        st.handle_event(
            "Network.responseReceived",
            &json!({"requestId": "b1", "response": {"url": "https://h.test/x", "status": 200, "mimeType": "application/json", "headers": {}}}),
        );
        assert!(st.wants_body(&json!({"requestId": "b1"}), &opts));
        st.store_body("b1", "0123456789ABCDEFGHIJ".into(), false, opts.body_cap);
        let entries = st.finish();
        assert_eq!(entries[0].body.as_deref(), Some("0123456789"));
        assert!(entries[0].body_truncated);
    }

    #[test]
    fn redaction_covers_headers_and_query() {
        let mut h = vec![
            ("Authorization".to_string(), "Bearer x".to_string()),
            ("Content-Type".to_string(), "application/json".to_string()),
            ("cookie".to_string(), "sid=1".to_string()),
        ];
        redact_headers(&mut h);
        assert_eq!(h[0].1, "***");
        assert_eq!(h[1].1, "application/json");
        assert_eq!(h[2].1, "***");

        let mut q = vec![
            ("access_token".to_string(), "t".to_string()),
            ("page".to_string(), "2".to_string()),
        ];
        redact_query(&mut q);
        assert_eq!(q[0].1, "***");
        assert_eq!(q[1].1, "2");
    }

    #[tokio::test]
    async fn capture_state_routes_session_filtered_events() {
        // The pump's session filter is the only non-pure bit; verify the
        // pure half feeds cleanly through handle_event + on_finished.
        let mut st = CaptureState::new(&CaptureOptions::default());
        st.handle_event(
            "Network.requestWillBeSent",
            &json!({
                "requestId": "z", "timestamp": 5.0,
                "request": {"url": "https://h.test/x?a=1", "method": "POST",
                            "headers": {}, "hasPostData": true}
            }),
        );
        assert!(st.wants_post_data(&json!({"requestId": "z"})));
        st.store_post_data("z", "{\"k\":1}".into());
        st.handle_event(
            "Network.responseReceived",
            &json!({"requestId": "z", "response": {"url": "https://h.test/x", "status": 201, "mimeType": "application/json", "headers": {}}}),
        );
        assert!(st.wants_body(&json!({"requestId": "z"}), &CaptureOptions::default()));
        let entries = st.finish();
        assert_eq!(entries[0].post_data.as_deref(), Some("{\"k\":1}"));
        assert_eq!(entries[0].status, Some(201));
    }
}
