//! stdio MCP client + registry (`.xencode/mcp.json`, Claude-Code-style
//! `mcpServers` layout). Tool names surface as `mcp_<server>_<tool>`.

use crate::error::{Result, XeError};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::io::{BufRead, BufReader, Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::mpsc::{channel, Receiver, Sender};
use std::time::Duration;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct McpServerConfig {
    pub command: String,
    #[serde(default)]
    pub args: Vec<String>,
    #[serde(default)]
    pub env: std::collections::BTreeMap<String, String>,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct McpConfig {
    #[serde(rename = "mcpServers", default)]
    pub servers: std::collections::BTreeMap<String, McpServerConfig>,
}

impl McpConfig {
    pub fn path(workspace_root: &Path) -> PathBuf {
        workspace_root.join(".xencode").join("mcp.json")
    }
    pub fn load(workspace_root: &Path) -> Self {
        std::fs::read_to_string(Self::path(workspace_root))
            .ok()
            .and_then(|raw| serde_json::from_str(&raw).ok())
            .unwrap_or_default()
    }
}

pub struct McpClient {
    server_name: String,
    #[allow(dead_code)]
    child: Child,
    tx: Sender<Value>,
    rx: Receiver<Value>,
    next_id: u64,
    pub last_error: Option<String>,
}

impl McpClient {
    /// Spawn the server process and perform the initialize handshake.
    pub fn connect(server_name: &str, cfg: &McpServerConfig) -> Result<Self> {
        let mut child = Command::new(&cfg.command)
            .args(&cfg.args)
            .envs(&cfg.env)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn()
            .map_err(|e| XeError::Mcp(format!("failed to spawn `{}`: {e}", cfg.command)))?;

        let stdin = child.stdin.take().ok_or_else(|| XeError::Mcp("no stdin".into()))?;
        let stdout = child.stdout.take().ok_or_else(|| XeError::Mcp("no stdout".into()))?;

        let (req_tx, req_rx) = channel::<Value>();
        let (res_tx, res_rx) = channel::<Value>();

        // writer thread
        std::thread::spawn(move || {
            for req in req_rx {
                if writeln!(&stdin, "{req}").is_err() {
                    break;
                }
            }
        });
        // reader thread: pick the "id"-bearing response lines
        std::thread::spawn(move || {
            let reader = BufReader::new(stdout);
            for line in reader.lines().map_while(|l| l.ok()) {
                let line = line.trim();
                if line.is_empty() {
                    continue;
                }
                if let Ok(v) = serde_json::from_str::<Value>(line) {
                    if v.get("id").is_some() && v.get("method").is_none() && res_tx.send(v).is_err() {
                        break;
                    }
                }
            }
        });

        let mut client = Self {
            server_name: server_name.to_string(),
            child,
            tx: req_tx,
            rx: res_rx,
            next_id: 1,
            last_error: None,
        };
        let init = client.request(
            "initialize",
            json!({
                "protocolVersion": "2024-11-05",
                "capabilities": {},
                "clientInfo": {"name": "xencode", "version": "2.0.0"}
            }),
        )?;
        let _ = init; // serverInfo; capabilities not needed yet
        client.notify("notifications/initialized", json!({}))?;
        Ok(client)
    }

    fn request(&mut self, method: &str, params: Value) -> Result<Value> {
        let id = self.next_id;
        self.next_id += 1;
        let req = json!({"jsonrpc": "2.0", "id": id, "method": method, "params": params});
        self.tx.send(req).map_err(|_| XeError::Mcp(format!("server `{}` is gone", self.server_name)))?;
        let deadline = std::time::Instant::now() + Duration::from_secs(30);
        loop {
            let remaining = deadline.saturating_duration_since(std::time::Instant::now());
            if remaining.is_zero() {
                self.last_error = Some("timeout".into());
                return Err(XeError::Mcp(format!("server `{}` timed out on {method}", self.server_name)));
            }
            match self.rx.recv_timeout(remaining.min(Duration::from_millis(500))) {
                Ok(resp) => {
                    if resp.get("id").and_then(|i| i.as_u64()) == Some(id) {
                        if let Some(err) = resp.get("error") {
                            self.last_error = Some(err.to_string());
                            return Err(XeError::Mcp(format!("server error on {method}: {err}")));
                        }
                        return Ok(resp.get("result").cloned().unwrap_or(Value::Null));
                    }
                    // response to an older id — ignore and keep waiting
                }
                Err(std::sync::mpsc::RecvTimeoutError::Timeout) => continue,
                Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => {
                    return Err(XeError::Mcp(format!("server `{}` closed the pipe", self.server_name)));
                }
            }
        }
    }

    fn notify(&mut self, method: &str, params: Value) -> Result<()> {
        let req = json!({"jsonrpc": "2.0", "method": method, "params": params});
        self.tx.send(req).map_err(|_| XeError::Mcp("server gone".into()))
    }

    pub fn list_tools(&mut self) -> Vec<McpTool> {
        match self.request("tools/list", json!({})) {
            Ok(result) => result
                .get("tools")
                .and_then(|t| t.as_array())
                .map(|arr| {
                    arr.iter()
                        .filter_map(|t| {
                            Some(McpTool {
                                name: t.get("name")?.as_str()?.to_string(),
                                description: t.get("description").and_then(|d| d.as_str()).unwrap_or("").to_string(),
                                schema: t.get("inputSchema").cloned().unwrap_or(json!({"type":"object"})),
                            })
                        })
                        .collect()
                })
                .unwrap_or_default(),
            Err(e) => {
                self.last_error = Some(e.to_string());
                vec![]
            }
        }
    }

    pub fn call_tool(&mut self, tool_name: &str, args_json: &str) -> String {
        let arguments: Value = serde_json::from_str(args_json).unwrap_or(json!({}));
        match self.request("tools/call", json!({"name": tool_name, "arguments": arguments})) {
            Ok(result) => {
                // Unwrap content blocks into text.
                if let Some(content) = result.get("content").and_then(|c| c.as_array()) {
                    let text: Vec<String> = content
                        .iter()
                        .filter_map(|c| c.get("text").and_then(|t| t.as_str()).map(|s| s.to_string()))
                        .collect();
                    if !text.is_empty() {
                        return text.join("\n");
                    }
                }
                result.to_string()
            }
            Err(e) => format!("ERROR: MCP server `{}` did not answer tools/call {}", self.server_name, e),
        }
    }
}

impl Drop for McpClient {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

#[derive(Debug, Clone)]
pub struct McpTool {
    pub name: String,
    pub description: String,
    pub schema: Value,
}

/// A running registry of MCP servers (owned for the lifetime of a CLI run).
pub struct McpRegistry {
    clients: std::collections::BTreeMap<String, McpClient>,
    pub tools: Vec<(String /*prefixed*/, String /*server*/, McpTool)>,
    pub statuses: Vec<(String, String)>,
}

impl McpRegistry {
    /// Connect to all configured servers; failures are recorded, not fatal.
    pub fn connect_all(workspace_root: &Path) -> Self {
        let cfg = McpConfig::load(workspace_root);
        let mut clients = std::collections::BTreeMap::new();
        let mut tools = vec![];
        let mut statuses = vec![];
        for (name, scfg) in &cfg.servers {
            match McpClient::connect(name, scfg) {
                Ok(mut client) => {
                    for t in client.list_tools() {
                        tools.push((format!("mcp_{name}_{}", t.name), name.clone(), t));
                    }
                    statuses.push((name.clone(), "connected".into()));
                    clients.insert(name.clone(), client);
                }
                Err(e) => statuses.push((name.clone(), format!("failed: {e}"))),
            }
        }
        Self { clients, tools, statuses }
    }

    pub fn is_empty(&self) -> bool {
        self.clients.is_empty()
    }

    /// All MCP tools as ToolSpecs with the mcp_ prefix.
    pub fn specs(&self) -> Vec<crate::chat::ToolSpec> {
        self.tools
            .iter()
            .map(|(prefixed, _, t)| crate::chat::ToolSpec {
                name: prefixed.clone(),
                description: if t.description.is_empty() {
                    format!("MCP tool {}", t.name)
                } else {
                    t.description.clone()
                },
                parameters: t.schema.clone(),
            })
            .collect()
    }

    pub fn call(&mut self, prefixed_name: &str, args_json: &str) -> Option<String> {
        let entry = self.tools.iter().find(|(p, _, _)| p == prefixed_name)?;
        let server = entry.1.clone();
        let raw_tool = entry.2.name.clone();
        let client = self.clients.get_mut(&server)?;
        Some(client.call_tool(&raw_tool, args_json))
    }
}

/// Read a server's stdout fully (used by tests with plain processes).
#[allow(dead_code)]
fn drain<R: Read>(r: &mut R) -> String {
    let mut s = String::new();
    let _ = r.read_to_string(&mut s);
    s
}

#[cfg(test)]
mod tests {
    use super::*;

    /// sh-based JSON-RPC test server (twin of the Kotlin test server):
    /// answers initialize and tools/list; tools/call echoes the input.
    #[test]
    fn handshake_list_and_call_over_stdio() {
        if !cfg!(unix) {
            return;
        }
        let dir = tempfile::tempdir().unwrap();
        let script = dir.path().join("fake_mcp.sh");
        std::fs::write(
            &script,
            r#"#!/bin/sh
while IFS= read -r line; do
  case "$line" in
    *'"method":"initialize"'*)
      printf '{"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05","serverInfo":{"name":"fake"}}}\n' ;;
    *'"method":"tools/list"'*)
      printf '{"jsonrpc":"2.0","id":2,"result":{"tools":[{"name":"echo","description":"echoes","inputSchema":{"type":"object"}}]}}\n' ;;
    *'"method":"tools/call"'*)
      printf '{"jsonrpc":"2.0","id":3,"result":{"content":[{"type":"text","text":"echo ok"}]}}\n' ;;
  esac
done
"#,
        )
        .unwrap();
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&script, std::fs::Permissions::from_mode(0o755)).unwrap();
        }
        let cfg = McpServerConfig {
            command: "sh".into(),
            args: vec![script.display().to_string()],
            env: Default::default(),
        };
        let mut client = McpClient::connect("fake", &cfg).unwrap();
        let tools = client.list_tools();
        assert_eq!(tools.len(), 1);
        assert_eq!(tools[0].name, "echo");
        let out = client.call_tool("echo", r#"{"x":1}"#);
        assert_eq!(out, "echo ok");
    }
}
