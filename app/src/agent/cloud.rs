//! Cloud sync client — the desktop-app side of the Xencode backend
//! (`Resolutefemi/Xencode-backend`, private). Pushes local sessions to the
//! cloud, pulls remote deltas back down, opens share links and uploads run
//! telemetry.
//!
//! Config lives in `~/.xencode/cloud.json` (`{"url":…, "token":…,
//! "last_seq":…}`); the environment variables `XENCODE_CLOUD_URL` and
//! `XENCODE_CLOUD_TOKEN` override the file when set. Session files mirror
//! `.xencode/sessions/<id>.json` exactly — the cloud stores them as opaque
//! JSON, so this client stays schema-light (serde_json::Value passthrough).

use std::path::PathBuf;

use anyhow::{bail, Context, Result};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};

/// Local cloud settings. `last_seq` is the delta-sync high-water mark
/// returned by the server's `sync/pull` (`next_seq`).
#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct CloudConfig {
    #[serde(default)]
    pub url: String,
    #[serde(default)]
    pub token: Option<String>,
    #[serde(default)]
    pub last_seq: i64,
}

impl CloudConfig {
    /// `~/.xencode/cloud.json`.
    pub fn path() -> Result<PathBuf> {
        let home = std::env::var("HOME").context("HOME is not set")?;
        Ok(PathBuf::from(home).join(".xencode").join("cloud.json"))
    }

    /// Load from disk, then apply env overrides. Missing file → defaults
    /// (localhost backend, no token).
    pub fn load() -> Self {
        let mut cfg = std::fs::read_to_string(Self::path().unwrap_or_default())
            .ok()
            .and_then(|text| serde_json::from_str::<CloudConfig>(&text).ok())
            .unwrap_or_default();
        if cfg.url.is_empty() {
            cfg.url = "http://127.0.0.1:8080".into();
        }
        if let Ok(url) = std::env::var("XENCODE_CLOUD_URL") {
            if !url.trim().is_empty() {
                cfg.url = url.trim().trim_end_matches('/').into();
            }
        } else {
            cfg.url = cfg.url.trim_end_matches('/').into();
        }
        if let Ok(token) = std::env::var("XENCODE_CLOUD_TOKEN") {
            if !token.trim().is_empty() {
                cfg.token = Some(token.trim().into());
            }
        }
        cfg
    }

    pub fn save(&self) -> Result<()> {
        let path = Self::path()?;
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::write(&path, serde_json::to_string_pretty(self)?)?;
        Ok(())
    }

    pub fn require_token(&self) -> Result<&str> {
        self.token
            .as_deref()
            .filter(|t| !t.trim().is_empty())
            .ok_or_else(|| {
                anyhow::anyhow!(
                    "no cloud token — run `xencode cloud login <EMAIL> <PASSWORD>` first \
                     (or set XENCODE_CLOUD_TOKEN)"
                )
            })
    }
}

/// Auth response from signup/login.
pub struct AuthResponse {
    pub account_id: String,
    pub email: String,
    pub token: String,
}

/// One session summary from `GET /v1/sessions`.
#[derive(Debug)]
pub struct SessionSummary {
    pub id: String,
    pub title: Option<String>,
    pub model: Option<String>,
    pub updated_unix: i64,
    pub message_count: i64,
}

/// Thin REST client over reqwest. Every method maps non-2xx replies onto an
/// error carrying the server's message.
pub struct CloudClient {
    base: String,
    http: reqwest::Client,
    token: Option<String>,
}

impl CloudClient {
    pub fn new(cfg: &CloudConfig) -> Self {
        let http = reqwest::Client::builder()
            .timeout(std::time::Duration::from_secs(60))
            .build()
            .unwrap_or_default();
        Self { base: cfg.url.trim_end_matches('/').to_string(), http, token: cfg.token.clone() }
    }

    /// Shared request helper: builds a bearer request and maps errors.
    async fn send(
        &self,
        method: reqwest::Method,
        path: &str,
        body: Option<Value>,
    ) -> Result<(u16, Value)> {
        let token = self
            .token
            .as_deref()
            .filter(|t| !t.trim().is_empty())
            .ok_or_else(|| anyhow::anyhow!("no cloud token configured"))?;
        let url = format!("{}{}", self.base, path);
        let mut req = self.http.request(method.clone(), &url).bearer_auth(token);
        if let Some(json_body) = &body {
            req = req.json(json_body);
        }
        let resp = req
            .send()
            .await
            .with_context(|| format!("request failed: {method} {path}"))?;
        let status = resp.status().as_u16();
        let text = resp.text().await.unwrap_or_default();
        let value: Value = serde_json::from_str(&text).unwrap_or(Value::Null);
        if !(200..300).contains(&status) {
            let message = value
                .get("error")
                .and_then(|e| e.get("message"))
                .and_then(Value::as_str)
                .unwrap_or("request failed")
                .to_string();
            bail!("{method} {path} → {status}: {message}");
        }
        Ok((status, value))
    }

    /// Public (no-auth) GET — health/meta/share views.
    async fn send_public(&self, path: &str) -> Result<(u16, Value)> {
        let url = format!("{}{}", self.base, path);
        let resp = self
            .http
            .get(&url)
            .send()
            .await
            .with_context(|| format!("request failed: GET {path}"))?;
        let status = resp.status().as_u16();
        let text = resp.text().await.unwrap_or_default();
        let value: Value = serde_json::from_str(&text).unwrap_or(Value::Null);
        if !(200..300).contains(&status) {
            bail!("GET {path} → {status}");
        }
        Ok((status, value))
    }

    pub async fn health(&self) -> Result<bool> {
        let url = format!("{}/healthz", self.base);
        let resp = self.http.get(&url).send().await?;
        Ok(resp.status().is_success())
    }

    pub async fn meta(&self) -> Result<Value> {
        let (_, value) = self.send_public("/v1/meta").await?;
        Ok(value)
    }

    pub async fn signup(&self, email: &str, password: &str) -> Result<AuthResponse> {
        let (status, value) = self
            .send_public_authless(
                reqwest::Method::POST,
                "/v1/auth/signup",
                json!({ "email": email, "password": password }),
            )
            .await?;
        parse_auth(status, &value)
    }

    pub async fn login(&self, email: &str, password: &str) -> Result<AuthResponse> {
        let (status, value) = self
            .send_public_authless(
                reqwest::Method::POST,
                "/v1/auth/login",
                json!({ "email": email, "password": password }),
            )
            .await?;
        parse_auth(status, &value)
    }

    /// Auth endpoints do not carry a token; untyped POST with JSON body.
    async fn send_public_authless(
        &self,
        method: reqwest::Method,
        path: &str,
        body: Value,
    ) -> Result<(u16, Value)> {
        let url = format!("{}{}", self.base, path);
        let resp = self
            .http
            .request(method, &url)
            .json(&body)
            .send()
            .await
            .with_context(|| format!("request failed: {path}"))?;
        let status = resp.status().as_u16();
        let text = resp.text().await.unwrap_or_default();
        let value: Value = serde_json::from_str(&text).unwrap_or(Value::Null);
        Ok((status, value))
    }

    pub async fn logout(&self) -> Result<()> {
        self.send(reqwest::Method::POST, "/v1/auth/logout", None).await?;
        Ok(())
    }

    pub async fn me(&self) -> Result<Value> {
        let (_, value) = self.send(reqwest::Method::GET, "/v1/me", None).await?;
        Ok(value)
    }

    /// Push one session (PUT /v1/sessions/{id}). Returns true when the server
    /// created it (201) versus updated it (200).
    pub async fn push_session(&self, session: &Value) -> Result<bool> {
        let id = session
            .get("id")
            .and_then(Value::as_str)
            .context("session has no string id")?
            .to_string();
        let (status, _) = self
            .send(
                reqwest::Method::PUT,
                &format!("/v1/sessions/{id}"),
                Some(session.clone()),
            )
            .await?;
        Ok(status == 201)
    }

    /// Batch push (POST /v1/sync/push). Returns the server's report.
    pub async fn sync_push(&self, sessions: &[Value]) -> Result<Value> {
        let (_, value) = self
            .send(
                reqwest::Method::POST,
                "/v1/sync/push",
                Some(json!({ "sessions": sessions })),
            )
            .await?;
        Ok(value)
    }

    /// Delta pull from a seq cursor: (sessions, next cursor).
    pub async fn sync_pull(&self, since_seq: i64) -> Result<(Vec<Value>, i64)> {
        let (_, value) = self
            .send(
                reqwest::Method::GET,
                &format!("/v1/sync/pull?since_seq={since_seq}"),
                None,
            )
            .await?;
        let sessions = value
            .get("sessions")
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default();
        let next = value.get("next_seq").and_then(Value::as_i64).unwrap_or(since_seq);
        Ok((sessions, next))
    }

    /// Remote session summary list (for `cloud status` / interactive pickers).
    pub async fn list_remote(&self, limit: i64) -> Result<Vec<SessionSummary>> {
        let (_, value) = self
            .send(
                reqwest::Method::GET,
                &format!("/v1/sessions?limit={limit}"),
                None,
            )
            .await?;
        let items = value
            .get("sessions")
            .and_then(Value::as_array)
            .cloned()
            .unwrap_or_default();
        Ok(items
            .iter()
            .map(|v| SessionSummary {
                id: v.get("id").and_then(Value::as_str).unwrap_or_default().into(),
                title: v.get("title").and_then(Value::as_str).map(str::to_string),
                model: v.get("model").and_then(Value::as_str).map(str::to_string),
                updated_unix: v.get("updated_unix").and_then(Value::as_i64).unwrap_or(0),
                message_count: v.get("message_count").and_then(Value::as_i64).unwrap_or(0),
            })
            .collect())
    }

    /// Open a share link: returns {slug, url, existed}.
    pub async fn share(&self, session_id: &str) -> Result<Value> {
        let (_, value) = self
            .send(
                reqwest::Method::POST,
                &format!("/v1/sessions/{session_id}/share"),
                None,
            )
            .await?;
        Ok(value)
    }

    /// Revoke a share link.
    pub async fn unshare(&self, session_id: &str) -> Result<()> {
        self.send(
            reqwest::Method::DELETE,
            &format!("/v1/sessions/{session_id}/share"),
            None,
        )
        .await?;
        Ok(())
    }

    /// Upload a telemetry batch (POST /v1/telemetry/runs). Returns the
    /// server's stored/failed report.
    pub async fn telemetry_push(&self, runs: &[Value]) -> Result<Value> {
        let (_, value) = self
            .send(
                reqwest::Method::POST,
                "/v1/telemetry/runs",
                Some(json!(runs)),
            )
            .await?;
        Ok(value)
    }

    /// Publish a registry item (command/recipe/hook markdown).
    pub async fn registry_publish(
        &self,
        kind: &str,
        name: &str,
        description: Option<&str>,
        content: &str,
    ) -> Result<Value> {
        let (_, value) = self
            .send(
                reqwest::Method::POST,
                "/v1/registry",
                Some(json!({
                    "kind": kind,
                    "name": name,
                    "description": description,
                    "content": content,
                })),
            )
            .await?;
        Ok(value)
    }

    /// Public registry search.
    pub async fn registry_search(&self, kind: Option<&str>, q: Option<&str>) -> Result<Value> {
        let mut path = String::from("/v1/registry?");
        if let Some(k) = kind {
            path.push_str(&format!("kind={}&", urlencode(k)));
        }
        if let Some(query) = q {
            path.push_str(&format!("q={}&", urlencode(query)));
        }
        let (_, value) = self.send_public(path.trim_end_matches('&')).await?;
        Ok(value)
    }
}

fn parse_auth(_status: u16, value: &Value) -> Result<AuthResponse> {
    let token = value
        .get("token")
        .and_then(Value::as_str)
        .context("auth response missing token")?
        .to_string();
    Ok(AuthResponse {
        account_id: value
            .get("account_id")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .into(),
        email: value
            .get("email")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .into(),
        token,
    })
}

fn urlencode(text: &str) -> String {
    let mut out = String::new();
    for b in text.bytes() {
        match b {
            b'A'..=b'Z' | b'a'..=b'z' | b'0'..=b'9' | b'-' | b'_' | b'.' | b'~' => {
                out.push(b as char)
            }
            other => out.push_str(&format!("%{other:02X}")),
        }
    }
    out
}

/// Load every local session file under `<cwd>/.xencode/sessions/*.json`.
pub fn local_sessions(dir: &std::path::Path) -> Result<Vec<(String, Value)>> {
    let mut sessions = Vec::new();
    let rd = match std::fs::read_dir(dir) {
        Ok(rd) => rd,
        Err(_) => return Ok(sessions),
    };
    for entry in rd.flatten() {
        let name = entry.file_name().to_string_lossy().into_owned();
        let Some(id) = name.strip_suffix(".json") else { continue };
        let text = std::fs::read_to_string(entry.path())?;
        match serde_json::from_str::<Value>(&text) {
            Ok(v) => sessions.push((id.to_string(), v)),
            Err(err) => bail!("session file {} is not valid JSON: {err}", entry.path().display()),
        }
    }
    sessions.sort_by(|a, b| a.0.cmp(&b.0));
    Ok(sessions)
}

/// Convert one local telemetry JSONL file (span lines) into the backend's
/// run-envelope shape: run_id from the filename, tokens/model aggregated from
/// the `llm.call` spans, duration from the `agent.run` span when present.
pub fn telemetry_run_from_jsonl(run_id: &str, lines: &[&str]) -> Result<Value> {
    let mut spans: Vec<Value> = Vec::new();
    for line in lines {
        if line.trim().is_empty() {
            continue;
        }
        let span: Value = serde_json::from_str(line)
            .with_context(|| format!("telemetry line in {run_id} is not JSON"))?;
        spans.push(span);
    }
    if spans.is_empty() {
        bail!("telemetry file for run {run_id} has no spans");
    }
    let mut tokens_in = 0u64;
    let mut tokens_out = 0u64;
    let mut estimated = false;
    let mut model: Option<String> = None;
    let mut started_ms: Option<u64> = None;
    let mut duration_ms: u64 = 0;
    for span in &spans {
        let attrs = span.get("attributes").cloned().unwrap_or(Value::Null);
        if span.get("name").and_then(Value::as_str) == Some("llm.call") {
            tokens_in += attrs.get("tokens_in").and_then(Value::as_u64).unwrap_or(0);
            tokens_out += attrs.get("tokens_out").and_then(Value::as_u64).unwrap_or(0);
            estimated |= attrs.get("estimated").and_then(Value::as_bool).unwrap_or(false);
            if model.is_none() {
                model = attrs.get("model").and_then(Value::as_str).map(str::to_string);
            }
        }
        if let Some(start) = span.get("start_unix_ms").and_then(Value::as_u64) {
            if started_ms.is_none() || start < started_ms.unwrap() {
                started_ms = Some(start);
            }
        }
        if span.get("name").and_then(Value::as_str) == Some("agent.run") {
            duration_ms += span.get("duration_ms").and_then(Value::as_u64).unwrap_or(0);
        }
    }
    Ok(json!({
        "run_id": run_id,
        "model": model,
        "tokens": { "input": tokens_in, "output": tokens_out, "estimated": estimated },
        "started_unix_ms": started_ms,
        "duration_ms": duration_ms,
        "spans": spans,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn tmp(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("xencode-cloud-{}-{}", tag, std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    #[test]
    fn config_defaults_and_env_override() {
        // Env overrides beat the file; file absent → localhost default.
        std::env::set_var("XENCODE_CLOUD_URL", "https://cloud.example.com/");
        let cfg = CloudConfig::load();
        assert_eq!(cfg.url, "https://cloud.example.com");
        std::env::remove_var("XENCODE_CLOUD_URL");
    }

    #[test]
    fn config_round_trips_through_disk() {
        let dir = tmp("cfg");
        std::env::set_var("HOME", &dir);
        let cfg = CloudConfig {
            url: "https://cloud.xencode.dev".into(),
            token: Some("xk_abc".into()),
            last_seq: 42,
        };
        cfg.save().unwrap();
        std::env::remove_var("XENCODE_CLOUD_URL");
        let loaded = CloudConfig::load();
        assert_eq!(loaded.url, "https://cloud.xencode.dev");
        assert_eq!(loaded.token.as_deref(), Some("xk_abc"));
        assert_eq!(loaded.last_seq, 42);
        std::env::remove_var("HOME");
    }

    #[test]
    fn local_sessions_reads_only_json() {
        let dir = tmp("sessions");
        std::fs::write(
            dir.join("a.json"),
            r#"{"id":"a","created_unix":1,"messages":[{"role":"user","content":"hi"}]}"#,
        )
        .unwrap();
        std::fs::write(dir.join("notes.txt"), "not a session").unwrap();
        std::fs::write(dir.join("bad.json"), "{oops").unwrap();
        let sessions = local_sessions(&dir);
        assert!(sessions.is_err(), "invalid JSON must surface, not be skipped");
        std::fs::remove_file(dir.join("bad.json")).unwrap();
        let sessions = local_sessions(&dir).unwrap();
        assert_eq!(sessions.len(), 1);
        assert_eq!(sessions[0].0, "a");
    }

    #[test]
    fn telemetry_jsonl_becomes_run_envelope() {
        let lines = [
            r#"{"name":"agent.run","start_unix_ms":1700000000000,"duration_ms":4200,"status":"ok","attributes":{"iterations":2}}"#,
            r#"{"name":"llm.call","start_unix_ms":1700000000001,"duration_ms":900,"status":"ok","attributes":{"model":"glm-4.6","tokens_in":100,"tokens_out":50,"estimated":false}}"#,
            r#"{"name":"tool.call","start_unix_ms":1700000000500,"duration_ms":40,"status":"ok","attributes":{"tool":"read"}}"#,
        ];
        let run = telemetry_run_from_jsonl("r9", &lines).unwrap();
        assert_eq!(run["run_id"], json!("r9"));
        assert_eq!(run["model"], json!("glm-4.6"));
        assert_eq!(run["tokens"]["input"], json!(100));
        assert_eq!(run["tokens"]["output"], json!(50));
        assert_eq!(run["duration_ms"], json!(4200));
        assert_eq!(run["spans"].as_array().map(|a| a.len()), Some(3));
        assert!(telemetry_run_from_jsonl("empty", &[]).is_err());
    }

    #[tokio::test]
    async fn client_surfaces_server_error_messages() {
        // A raw one-shot HTTP server that always 401s, so the error text
        // includes the backend's message — exercises the real reqwest path
        // end-to-end on localhost without an axum dependency.
        let body = r#"{"error":{"code":"unauthorized","message":"invalid or revoked token"}}"#;
        let response = format!(
            "HTTP/1.1 401 Unauthorized\r\ncontent-type: application/json\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{}",
            body.len(),
            body
        );
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        std::thread::spawn(move || {
            if let Ok((mut stream, _)) = listener.accept() {
                use std::io::{Read, Write};
                // Drain only the request head (ends at the first blank line);
                // blocking on EOF would hang the one-shot exchange.
                let mut buf = [0u8; 1024];
                let mut seen = 0usize;
                while seen < buf.len() {
                    match stream.read(&mut buf[seen..]) {
                        Ok(0) => break,
                        Ok(n) => {
                            seen += n;
                            if buf[..seen].windows(4).any(|w| w == b"\r\n\r\n") {
                                break;
                            }
                        }
                        Err(_) => break,
                    }
                }
                let _ = stream.write_all(response.as_bytes());
                let _ = stream.flush();
                // Drop closes the socket so the client sees the response end.
            }
        });
        let cfg = CloudConfig { url: format!("http://{addr}"), token: Some("xk_bad".into()), last_seq: 0 };
        let client = CloudClient::new(&cfg);
        let err = client.list_remote(10).await.unwrap_err().to_string();
        assert!(err.contains("invalid or revoked token"), "got: {err}");
    }
}
