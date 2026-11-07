//! XenCloud client for the desktop app.
//!
//! Talks to the Axum backend (`xencode-cloud`): session/API-key auth, the
//! model list, SSE chat streaming and conversation sync. The config lives at
//! `~/.xencode/cloud.json`; the session token never leaves the machine.

use once_cell::sync::Lazy;
use serde::{Deserialize, Serialize};
use std::io::Write;
use std::path::PathBuf;
use std::sync::mpsc::Sender;

static RUNTIME: Lazy<tokio::runtime::Runtime> = Lazy::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()
        .expect("tokio runtime")
});

#[derive(Clone, Debug, Serialize, Deserialize, Default)]
pub struct CloudConfig {
    pub server_url: String,
    pub token: String,
    pub model: String,
}

impl CloudConfig {
    pub fn path() -> PathBuf {
        let home = std::env::var("HOME").unwrap_or_else(|_| ".".into());
        PathBuf::from(home).join(".xencode").join("cloud.json")
    }

    pub fn load() -> Option<CloudConfig> {
        let path = Self::path();
        let text = std::fs::read_to_string(path).ok()?;
        serde_json::from_str(&text).ok()
    }

    pub fn save(&self) -> bool {
        let path = Self::path();
        if let Some(parent) = path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        std::fs::File::create(&path)
            .and_then(|mut f| {
                f.write_all(serde_json::to_string_pretty(self).unwrap_or_default().as_bytes())
            })
            .is_ok()
    }

    pub fn configured(&self) -> bool {
        !self.server_url.trim().is_empty() && !self.token.trim().is_empty()
    }
}

/// Events streamed back to the UI thread.
pub enum StreamEvent {
    Delta(String),
    Usage { input: u64, output: u64 },
    Done,
    Error(String),
}

/// One chat message exchanged with the gateway.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ChatMessage {
    pub role: String,
    pub content: String,
}

pub struct CloudClient {
    pub config: CloudConfig,
    http: reqwest::Client,
}

impl CloudClient {
    pub fn new(config: CloudConfig) -> Self {
        CloudClient {
            config,
            http: reqwest::Client::builder()
                .user_agent(concat!("xencode-desktop/", env!("CARGO_PKG_VERSION")))
                .build()
                .expect("reqwest client"),
        }
    }

    fn base(&self) -> String {
        let mut url = self.config.server_url.trim().trim_end_matches('/').to_string();
        if !url.starts_with("http://") && !url.starts_with("https://") {
            url = format!("http://{url}");
        }
        url
    }

    /// Blocking model-list fetch (runs on the shared runtime).
    pub fn fetch_models(&self) -> Result<Vec<String>, String> {
        RUNTIME.block_on(async {
            let resp = self
                .http
                .get(format!("{}/v1/models", self.base()))
                .bearer_auth(&self.config.token)
                .send()
                .await
                .map_err(|e| e.to_string())?;
            if !resp.status().is_success() {
                return Err(format!("HTTP {}", resp.status()));
            }
            let json: serde_json::Value = resp.json().await.map_err(|e| e.to_string())?;
            let models = json["data"]
                .as_array()
                .map(|arr| {
                    arr.iter()
                        .filter_map(|m| m["id"].as_str().map(String::from))
                        .collect::<Vec<_>>()
                })
                .unwrap_or_default();
            Ok(models)
        })
    }

    /// Blocking who-am-I probe, used by the settings dialog.
    pub fn whoami(&self) -> Result<(String, i64), String> {
        RUNTIME.block_on(async {
            let resp = self
                .http
                .get(format!("{}/v1/me", self.base()))
                .bearer_auth(&self.config.token)
                .send()
                .await
                .map_err(|e| e.to_string())?;
            if !resp.status().is_success() {
                return Err(format!("HTTP {}", resp.status()));
            }
            let json: serde_json::Value = resp.json().await.map_err(|e| e.to_string())?;
            let email = json["user"]["email"].as_str().unwrap_or("?").to_string();
            let credits = json["credits"].as_i64().unwrap_or(0);
            Ok((email, credits))
        })
    }

    /// Push a conversation snapshot to the cloud (best effort).
    pub fn push_conversation(&self, title: &str, model: &str) -> Result<String, String> {
        RUNTIME.block_on(async {
            let resp = self
                .http
                .post(format!("{}/v1/conversations", self.base()))
                .bearer_auth(&self.config.token)
                .json(&serde_json::json!({ "title": title, "model": model }))
                .send()
                .await
                .map_err(|e| e.to_string())?;
            if !resp.status().is_success() {
                return Err(format!("HTTP {}", resp.status()));
            }
            let json: serde_json::Value = resp.json().await.map_err(|e| e.to_string())?;
            json["id"].as_str().map(String::from).ok_or_else(|| "missing id".into())
        })
    }

    /// Append one message to a cloud conversation (best effort).
    pub fn push_message(&self, conversation_id: &str, role: &str, content: &str) -> Result<(), String> {
        RUNTIME.block_on(async {
            let resp = self
                .http
                .post(format!("{}/v1/conversations/{conversation_id}/messages", self.base()))
                .bearer_auth(&self.config.token)
                .json(&serde_json::json!({ "role": role, "content": content }))
                .send()
                .await
                .map_err(|e| e.to_string())?;
            if resp.status().is_success() {
                Ok(())
            } else {
                Err(format!("HTTP {}", resp.status()))
            }
        })
    }

    /// Spawn a streaming chat completion; deltas arrive on `tx`.
    pub fn stream_chat(
        &self,
        messages: Vec<ChatMessage>,
        model: &str,
        effort: Option<&str>,
        tx: Sender<StreamEvent>,
    ) -> std::thread::JoinHandle<()> {
        let http = self.http.clone();
        let base = self.base();
        let token = self.config.token.clone();
        let model = model.to_string();
        let effort = effort.map(String::from);

        std::thread::spawn(move || {
            RUNTIME.block_on(async move {
                let mut body = serde_json::json!({
                    "model": model,
                    "messages": messages,
                    "stream": true,
                });
                if let Some(effort) = &effort {
                    body["reasoning_effort"] = serde_json::json!(effort);
                }
                let resp = match http
                    .post(format!("{base}/v1/chat/completions"))
                    .bearer_auth(token)
                    .json(&body)
                    .send()
                    .await
                {
                    Ok(r) => r,
                    Err(e) => {
                        let _ = tx.send(StreamEvent::Error(e.to_string()));
                        return;
                    }
                };
                let status = resp.status();
                if !status.is_success() {
                    let text = resp.text().await.unwrap_or_default();
                    let _ = tx.send(StreamEvent::Error(format!("HTTP {status}: {text}")));
                    return;
                }

                let mut resp = resp;
                let mut buf = String::new();
                loop {
                    match resp.chunk().await {
                        Ok(Some(chunk)) => {
                            buf.push_str(&String::from_utf8_lossy(&chunk));
                            while let Some(pos) = buf.find('\n') {
                                let line: String = buf.drain(..=pos).collect();
                                let line = line.trim_end();
                                let Some(data) = line.strip_prefix("data: ") else {
                                    continue;
                                };
                                if data.trim() == "[DONE]" {
                                    let _ = tx.send(StreamEvent::Done);
                                    return;
                                }
                                let Ok(v) = serde_json::from_str::<serde_json::Value>(data) else {
                                    continue;
                                };
                                if let Some(u) = v.get("usage").and_then(|u| u.as_object()) {
                                    let input = u.get("prompt_tokens").and_then(|x| x.as_u64()).unwrap_or(0);
                                    let output = u.get("completion_tokens").and_then(|x| x.as_u64()).unwrap_or(0);
                                    if input > 0 || output > 0 {
                                        let _ = tx.send(StreamEvent::Usage { input, output });
                                    }
                                }
                                if let Some(delta) = v["choices"][0]["delta"]["content"].as_str() {
                                    if !delta.is_empty() {
                                        let _ = tx.send(StreamEvent::Delta(delta.to_string()));
                                    }
                                }
                            }
                        }
                        Ok(None) => {
                            let _ = tx.send(StreamEvent::Done);
                            return;
                        }
                        Err(e) => {
                            let _ = tx.send(StreamEvent::Error(e.to_string()));
                            return;
                        }
                    }
                }
            });
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn config_roundtrip() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("cloud.json");
        let cfg = CloudConfig {
            server_url: "http://127.0.0.1:8080".into(),
            token: "xst_test".into(),
            model: "xencode-auto".into(),
        };
        let text = serde_json::to_string_pretty(&cfg).unwrap();
        std::fs::write(&path, &text).unwrap();
        let back: CloudConfig = serde_json::from_str(&std::fs::read_to_string(&path).unwrap()).unwrap();
        assert_eq!(back.server_url, cfg.server_url);
        assert_eq!(back.token, cfg.token);
        assert!(back.configured());
        assert!(!CloudConfig::default().configured());
    }
}
