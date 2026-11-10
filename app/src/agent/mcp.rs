//! MCP (Model Context Protocol) client — Phase 4 harness port #6.
//!
//! Speaks JSON-RPC 2.0 over stdio (newline-delimited, per the MCP spec) so
//! any existing MCP server — filesystem, git, sqlite, fetch — plugs into the
//! agent loop with zero glue: every server tool surfaces in the registry as
//! `mcp__<server>__<tool>` and executes through `tools/call`.
//!
//! Servers are declared in `.xencode/mcp.json` (project) and
//! `~/.xencode/mcp.json` (user); project entries shadow user entries per
//! server name:
//!
//! ```json
//! {
//!   "mcpServers": {
//!     "fs": { "command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem", "."] }
//!   }
//! }
//! ```
//!
//! Lifecycle: [`McpManager::connect`] spawns every configured server once per
//! run (handshake + `tools/list` under a timeout; a failing server is skipped
//! with a warning, never fatal), tools stay available for the whole run, and
//! dropping the manager kills the children (`kill_on_drop`).

use std::collections::BTreeMap;
use std::future::Future;
use std::path::{Path, PathBuf};
use std::pin::Pin;
use std::process::Stdio;

use anyhow::{bail, Context, Result};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::{Child, ChildStdin, ChildStdout};

use super::provider::ToolDef;

/// The MCP protocol version this client speaks.
pub const PROTOCOL_VERSION: &str = "2024-11-05";

/// Handshake / listing / ping timeouts — a wedged server must not wedge the run.
const HANDSHAKE_TIMEOUT_MS: u64 = 10_000;
const CALL_TIMEOUT_MS: u64 = 30_000;

// ---------------------------------------------------------------------------
// Configuration
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct McpServerConfig {
    pub command: String,
    #[serde(default)]
    pub args: Vec<String>,
    #[serde(default)]
    pub env: BTreeMap<String, String>,
}

/// Collect server configs: user scope first, project `.xencode/mcp.json`
/// wins per name. Invalid entries are skipped with a warning.
pub fn discover(cwd: &Path) -> BTreeMap<String, McpServerConfig> {
    let home = std::env::var_os("HOME").map(PathBuf::from);
    discover_from(cwd, home.as_deref())
}

pub fn discover_from(
    cwd: &Path,
    home: Option<&Path>,
) -> BTreeMap<String, McpServerConfig> {
    let mut out = BTreeMap::new();
    if let Some(h) = home {
        merge_file(&h.join(".xencode").join("mcp.json"), &mut out);
    }
    merge_file(&cwd.join(".xencode").join("mcp.json"), &mut out);
    out
}

fn merge_file(path: &Path, out: &mut BTreeMap<String, McpServerConfig>) {
    let Ok(text) = std::fs::read_to_string(path) else {
        return; // no config at this scope — fine
    };
    let Ok(v) = serde_json::from_str::<Value>(&text) else {
        eprintln!("xencode · mcp: {} is not valid JSON — skipped", path.display());
        return;
    };
    let Some(servers) = v.get("mcpServers").and_then(Value::as_object) else {
        return;
    };
    for (name, cfg) in servers {
        if !valid_name(name) {
            eprintln!("xencode · mcp: server name `{name}` is invalid (use [a-zA-Z0-9_-]) — skipped");
            continue;
        }
        match serde_json::from_value::<McpServerConfig>(cfg.clone()) {
            Ok(cfg) => {
                out.insert(name.clone(), cfg);
            }
            Err(e) => eprintln!("xencode · mcp: server `{name}` misconfigured ({e}) — skipped"),
        }
    }
}

fn valid_name(s: &str) -> bool {
    !s.is_empty() && s.chars().all(|c| c.is_ascii_alphanumeric() || c == '_' || c == '-')
}

// ---------------------------------------------------------------------------
// Transport
// ---------------------------------------------------------------------------

/// Framed line transport to one MCP server. A trait so tests can script a
/// fake server without spawning processes. `Send + Sync` so `McpManager`
/// (and the whole agent-loop future) stays `Sync` across awaits.
pub trait McpIo: Send + Sync {
    fn send_line<'a>(&'a mut self, line: &'a str) -> Pin<Box<dyn Future<Output = Result<()>> + Send + 'a>>;
    fn recv_line(&mut self) -> Pin<Box<dyn Future<Output = Result<String>> + Send + '_>>;
}

/// Real stdio transport around a spawned child process. `kill_on_drop` makes
/// dropping the connection reap the server.
pub struct StdioIo {
    /// Held so `kill_on_drop(true)` reaps the server when the connection
    /// drops; never read directly.
    #[allow(dead_code)]
    child: Child,
    stdin: ChildStdin,
    stdout: BufReader<ChildStdout>,
}

impl StdioIo {
    pub fn spawn(cfg: &McpServerConfig) -> Result<Self> {
        let mut cmd = tokio::process::Command::new(&cfg.command);
        cmd.args(&cfg.args)
            .envs(&cfg.env)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null()) // server logs must not corrupt the protocol pipe
            .kill_on_drop(true);
        let mut child = cmd.spawn().with_context(|| format!("spawn mcp server `{}`", cfg.command))?;
        let stdin = child.stdin.take().context("mcp child has no stdin")?;
        let stdout = child.stdout.take().context("mcp child has no stdout")?;
        Ok(Self { child, stdin, stdout: BufReader::new(stdout) })
    }

    /// Terminate the child (best effort). The connection also kills the
    /// child on drop; this is for explicit early shutdown.
    #[allow(dead_code)]
    pub async fn kill(&mut self) {
        let _ = self.child.kill().await;
    }
}

impl McpIo for StdioIo {
    fn send_line<'a>(&'a mut self, line: &'a str) -> Pin<Box<dyn Future<Output = Result<()>> + Send + 'a>> {
        Box::pin(async move {
            self.stdin.write_all(line.as_bytes()).await?;
            self.stdin.write_all(b"\n").await?;
            self.stdin.flush().await?;
            Ok(())
        })
    }

    fn recv_line(&mut self) -> Pin<Box<dyn Future<Output = Result<String>> + Send + '_>> {
        Box::pin(async move {
            let mut buf = String::new();
            let n = self.stdout.read_line(&mut buf).await?;
            if n == 0 {
                bail!("mcp server closed the pipe");
            }
            Ok(buf.trim_end().to_string())
        })
    }
}

/// Scripted transport for tests: sends are recorded, receives pop a queue.
#[cfg(test)]
use std::collections::VecDeque;

#[cfg(test)]
pub struct ScriptedIo {
    pub sent: std::sync::Mutex<Vec<String>>,
    pub lines: std::sync::Mutex<VecDeque<String>>,
}

#[cfg(test)]
impl ScriptedIo {
    pub fn new(lines: &[&str]) -> Self {
        Self {
            sent: std::sync::Mutex::new(Vec::new()),
            lines: std::sync::Mutex::new(lines.iter().map(|s| s.to_string()).collect()),
        }
    }
}

#[cfg(test)]
impl McpIo for ScriptedIo {
    fn send_line<'a>(&'a mut self, line: &'a str) -> Pin<Box<dyn Future<Output = Result<()>> + Send + 'a>> {
        Box::pin(async move {
            self.sent.lock().unwrap().push(line.to_string());
            Ok(())
        })
    }

    fn recv_line(&mut self) -> Pin<Box<dyn Future<Output = Result<String>> + Send + '_>> {
        Box::pin(async move {
            self.lines
                .lock()
                .unwrap()
                .pop_front()
                .context("scripted server has no more lines")
        })
    }
}

// ---------------------------------------------------------------------------
// JSON-RPC protocol
// ---------------------------------------------------------------------------

/// One tool advertised by a server via `tools/list`.
#[derive(Debug, Clone)]
pub struct McpTool {
    pub name: String,
    pub description: String,
    pub schema: Value,
}

/// A JSON-RPC 2.0 client session with one MCP server.
pub struct McpConn {
    io: Box<dyn McpIo>,
    next_id: i64,
}

impl McpConn {
    pub fn new(io: Box<dyn McpIo>) -> Self {
        Self { io, next_id: 1 }
    }

    /// Send a request and wait for the matching response. Notifications and
    /// out-of-order ids are skipped; a server `error` object fails the call.
    pub async fn request(&mut self, method: &str, params: Value) -> Result<Value> {
        let id = self.next_id;
        self.next_id += 1;
        let line = json!({ "jsonrpc": "2.0", "id": id, "method": method, "params": params }).to_string();
        self.io.send_line(&line).await?;
        loop {
            let line = self.io.recv_line().await.context("mcp: waiting for {method} response")?;
            let v: Value = serde_json::from_str(&line)
                .with_context(|| format!("mcp: malformed JSON from server: {line}"))?;
            if v.get("id").and_then(Value::as_i64) == Some(id) {
                if let Some(err) = v.get("error") {
                    bail!("mcp {method} failed: {err}");
                }
                return Ok(v.get("result").cloned().unwrap_or(Value::Null));
            }
            // not ours (a notification or an unsolicited response) — keep reading
        }
    }

    /// Fire-and-forget notification (no id, no response expected).
    pub async fn notify(&mut self, method: &str, params: Value) -> Result<()> {
        let line = json!({ "jsonrpc": "2.0", "method": method, "params": params }).to_string();
        self.io.send_line(&line).await
    }

    /// The mandatory `initialize` handshake + `notifications/initialized`.
    pub async fn initialize(&mut self, client_name: &str) -> Result<Value> {
        let fut = self.request(
            "initialize",
            json!({
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {},
                "clientInfo": { "name": client_name, "version": env!("CARGO_PKG_VERSION") },
            }),
        );
        let res = tokio::time::timeout(std::time::Duration::from_millis(HANDSHAKE_TIMEOUT_MS), fut)
            .await
            .context("mcp initialize timed out")??;
        self.notify("notifications/initialized", json!({})).await?;
        Ok(res)
    }

    /// `tools/list` with cursor pagination.
    pub async fn list_tools(&mut self) -> Result<Vec<McpTool>> {
        let mut tools = Vec::new();
        let mut cursor: Option<String> = None;
        loop {
            let mut params = json!({});
            if let Some(c) = &cursor {
                params["cursor"] = json!(c);
            }
            let res = self.request("tools/list", params).await?;
            if let Some(arr) = res.get("tools").and_then(Value::as_array) {
                for t in arr {
                    let name = t.get("name").and_then(Value::as_str).unwrap_or_default();
                    if name.is_empty() {
                        continue;
                    }
                    tools.push(McpTool {
                        name: name.to_string(),
                        description: t
                            .get("description")
                            .and_then(Value::as_str)
                            .unwrap_or_default()
                            .to_string(),
                        schema: t
                            .get("inputSchema")
                            .cloned()
                            .unwrap_or_else(|| json!({"type": "object", "properties": {}})),
                    });
                }
            }
            cursor = res.get("nextCursor").and_then(Value::as_str).map(str::to_string);
            if cursor.is_none() {
                return Ok(tools);
            }
        }
    }

    /// `tools/call`, flattening the content array into text. An `isError`
    /// result (tool-level failure) surfaces as an Err carrying the text.
    pub async fn call_tool(&mut self, name: &str, args: &Value) -> Result<String> {
        let fut = self.request("tools/call", json!({ "name": name, "arguments": args }));
        let res = tokio::time::timeout(std::time::Duration::from_millis(CALL_TIMEOUT_MS), fut)
            .await
            .context("mcp tools/call timed out")??;
        let mut text = String::new();
        if let Some(items) = res.get("content").and_then(Value::as_array) {
            for item in items {
                if !text.is_empty() {
                    text.push('\n');
                }
                match item.get("type").and_then(Value::as_str) {
                    Some("text") => {
                        text.push_str(item.get("text").and_then(Value::as_str).unwrap_or_default());
                    }
                    Some("image") => text.push_str("[image content]"),
                    Some("resource") => text.push_str("[resource content]"),
                    other => text.push_str(&format!("[{} content]", other.unwrap_or("unknown"))),
                }
            }
        }
        if res.get("isError").and_then(Value::as_bool).unwrap_or(false) {
            bail!("{text}");
        }
        Ok(text)
    }

    #[allow(dead_code)] // part of the protocol surface (liveness probes)
    pub async fn ping(&mut self) -> Result<()> {
        self.request("ping", json!({})).await.map(|_| ())
    }
}

// ---------------------------------------------------------------------------
// Manager — all servers for one agent run
// ---------------------------------------------------------------------------

struct Server {
    name: String,
    conn: tokio::sync::Mutex<McpConn>,
    tools: Vec<McpTool>,
}

/// Every connected MCP server for one run. Tools are discovered at connect
/// time; calls take a per-server lock so parallel tool calls serialize on the
/// one stdio pipe (the MCP stdio transport is not multiplexed by design).
pub struct McpManager {
    servers: Vec<Server>,
}

impl McpManager {
    /// Spawn + handshake every configured server. Failures are warnings, not
    /// errors — a broken server never blocks the run.
    pub async fn connect(cfgs: BTreeMap<String, McpServerConfig>) -> Self {
        let mut servers = Vec::new();
        for (name, cfg) in cfgs {
            let spawn = || -> Result<McpConn> {
                let io = StdioIo::spawn(&cfg)?;
                Ok(McpConn::new(Box::new(io)))
            };
            let Ok(mut conn) = spawn() else {
                eprintln!("xencode · mcp: failed to start server `{name}` — skipped");
                continue;
            };
            let init = tokio::time::timeout(
                std::time::Duration::from_millis(HANDSHAKE_TIMEOUT_MS),
                async { conn.initialize("xencode").await?; conn.list_tools().await },
            )
            .await;
            match init {
                Ok(Ok(tools)) => {
                    eprintln!(
                        "xencode · mcp: connected `{name}` ({} tool{})",
                        tools.len(),
                        if tools.len() == 1 { "" } else { "s" }
                    );
                    servers.push(Server { name, conn: tokio::sync::Mutex::new(conn), tools });
                }
                _ => {
                    eprintln!("xencode · mcp: server `{name}` failed the handshake — skipped");
                }
            }
        }
        Self { servers }
    }

    pub fn is_empty(&self) -> bool {
        self.servers.is_empty()
    }

    pub fn tool_defs(&self) -> Vec<ToolDef> {
        let mut out = Vec::new();
        for s in &self.servers {
            for t in &s.tools {
                out.push(ToolDef {
                    name: format!("mcp__{}__{}", s.name, t.name),
                    description: format!("[mcp:{}] {}", s.name, t.description),
                    parameters: t.schema.clone(),
                });
            }
        }
        out
    }

    /// Route a namespaced call (`<server>__<tool>` — the `mcp__` prefix is
    /// stripped by the loop) through `tools/call`.
    pub async fn call(&self, namespaced: &str, args: &Value) -> Result<String> {
        let (server, tool) = namespaced
            .split_once("__")
            .context("malformed mcp tool name — expected mcp__<server>__<tool>")?;
        let s = self
            .servers
            .iter()
            .find(|s| s.name == server)
            .with_context(|| format!("no mcp server named `{server}`"))?;
        s.conn.lock().await.call_tool(tool, args).await
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn response(id: i64, result: Value) -> String {
        json!({ "jsonrpc": "2.0", "id": id, "result": result }).to_string()
    }

    #[tokio::test]
    async fn handshake_sends_initialize_and_notification() {
        let io = ScriptedIo::new(&[
            &response(
                1,
                json!({
                    "protocolVersion": PROTOCOL_VERSION,
                    "capabilities": { "tools": {} },
                    "serverInfo": { "name": "fake", "version": "1" }
                }),
            ),
        ]);
        let mut conn = McpConn::new(Box::new(io));
        let info = conn.initialize("xencode-test").await.unwrap();
        assert_eq!(info["serverInfo"]["name"], "fake");
    }

    #[tokio::test]
    async fn request_matches_ids_and_surfaces_errors() {
        // The server answers id 2 first (out of order) and id 1 second — the
        // client must skip the foreign id and keep reading.
        let io = ScriptedIo::new(&[
            &response(2, json!({"late": true})),
            &json!({"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"nope"}}).to_string(),
        ]);
        let mut conn = McpConn::new(Box::new(io));
        let err = conn.request("tools/list", json!({})).await.unwrap_err();
        assert!(err.to_string().contains("nope"), "got: {err:#}");
    }

    #[tokio::test]
    async fn list_tools_handles_pagination() {
        let io = ScriptedIo::new(&[
            &response(1, json!({"tools": [
                {"name": "echo", "description": "Echo it", "inputSchema": {"type": "object"}}
            ], "nextCursor": "page2"})),
            &response(2, json!({"tools": [
                {"name": "add", "description": "Add them", "inputSchema": {"type": "object"}}
            ]})),
        ]);
        let mut conn = McpConn::new(Box::new(io));
        let tools = conn.list_tools().await.unwrap();
        assert_eq!(tools.len(), 2);
        assert_eq!(tools[0].name, "echo");
        assert_eq!(tools[1].name, "add");
        assert!(tools[0].schema["type"] == "object");
    }

    #[tokio::test]
    async fn call_tool_flattens_content_and_iserror() {
        let io = ScriptedIo::new(&[
            &response(1, json!({"content": [
                {"type": "text", "text": "line one"},
                {"type": "text", "text": "line two"}
            ]})),
        ]);
        let mut conn = McpConn::new(Box::new(io));
        let text = conn.call_tool("echo", &json!({"message": "hi"})).await.unwrap();
        assert_eq!(text, "line one\nline two");

        let io2 = ScriptedIo::new(&[
            &response(1, json!({"content": [{"type": "text", "text": "boom"}], "isError": true})),
        ]);
        let mut conn2 = McpConn::new(Box::new(io2));
        let err = conn2.call_tool("echo", &json!({})).await.unwrap_err();
        assert!(err.to_string().contains("boom"));
    }

    #[test]
    fn discover_merges_user_and_project_scopes() {
        let dir = std::env::temp_dir().join(format!("xencode-mcp-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join(".xencode")).unwrap();
        let home = dir.join("home");
        std::fs::create_dir_all(home.join(".xencode")).unwrap();

        std::fs::write(
            home.join(".xencode/mcp.json"),
            r#"{"mcpServers": {"fs": {"command": "user-fs"}, "git": {"command": "user-git"}}}"#,
        )
        .unwrap();
        std::fs::write(
            dir.join(".xencode/mcp.json"),
            r#"{"mcpServers": {"fs": {"command": "project-fs", "args": ["-y"]}, "web": {"command": "curl"}}}"#,
        )
        .unwrap();

        let cfgs = discover_from(&dir, Some(&home));
        assert_eq!(cfgs.len(), 3, "fs shadows user scope, others merge");
        assert_eq!(cfgs["fs"].command, "project-fs", "project wins");
        assert_eq!(cfgs["fs"].args, vec!["-y"]);
        assert_eq!(cfgs["git"].command, "user-git");
        assert_eq!(cfgs["web"].command, "curl");

        // Invalid entries are skipped, not fatal.
        std::fs::write(
            dir.join(".xencode/mcp.json"),
            r#"{"mcpServers": {"bad name!": {"command": "x"}, "ok_1": {"command": "y"}}}"#,
        )
        .unwrap();
        let cfgs = discover_from(&dir, None);
        assert_eq!(cfgs.len(), 1);
        assert!(cfgs.contains_key("ok_1"));
        let _ = std::fs::remove_dir_all(&dir);
    }

    /// Full end-to-end: spawn the real binary as a real stdio MCP server and
    /// drive it through the production transport + protocol client.
    #[tokio::test]
    async fn stdio_end_to_end_against_the_builtin_test_server() {
        let cfg = McpServerConfig {
            command: find_xencode_bin().display().to_string(),
            args: vec!["--mcp-test-server".into()],
            env: BTreeMap::new(),
        };
        let io = StdioIo::spawn(&cfg).expect("spawn test server");
        let mut conn = McpConn::new(Box::new(io));

        let info = conn.initialize("xencode-test").await.expect("handshake");
        assert_eq!(info["serverInfo"]["name"], "xencode-test");

        let tools = conn.list_tools().await.expect("tools/list");
        assert!(tools.iter().any(|t| t.name == "echo"));
        assert!(tools.iter().any(|t| t.name == "add"));

        let out = conn.call_tool("echo", &json!({"message": "hello mcp"})).await.unwrap();
        assert_eq!(out, "hello mcp");
        let out = conn.call_tool("add", &json!({"a": 20, "b": 22})).await.unwrap();
        assert_eq!(out, "42");

        conn.ping().await.expect("ping");
    }

    /// Unit tests of a bin target have no `CARGO_BIN_EXE_*`; the test
    /// executable lives in `target/debug/deps/`, the freshly built binary in
    /// `target/debug/` — walk up until we find it.
    fn find_xencode_bin() -> std::path::PathBuf {
        let exe = std::env::current_exe().expect("current_exe");
        let name = if cfg!(windows) { "xencode.exe" } else { "xencode" };
        for dir in exe.ancestors().skip(1) {
            let candidate = dir.join(name);
            if candidate.is_file() {
                return candidate;
            }
        }
        panic!("built xencode binary not found next to the test executable");
    }

    #[test]
    fn tool_defs_are_namespaced() {
        let manager = tool_defs_fixture();
        let defs = manager.tool_defs();
        assert_eq!(defs.len(), 2);
        assert_eq!(defs[0].name, "mcp__fs__read");
        assert!(defs[0].description.starts_with("[mcp:fs]"));
        assert_eq!(defs[1].name, "mcp__git__log");
    }

    fn tool_defs_fixture() -> McpManager {
        let mut servers = Vec::new();
        for (name, tools) in [
            ("fs", vec![McpTool { name: "read".into(), description: "Read a file".into(), schema: json!({"type": "object"}) }]),
            ("git", vec![McpTool { name: "log".into(), description: "Git log".into(), schema: json!({"type": "object"}) }]),
        ] {
            // The conn is never used in this fixture — a scripted io with an
            // empty queue is safe (call() would fail, but we never call).
            let conn = McpConn::new(Box::new(ScriptedIo::new(&[])));
            servers.push(Server { name: name.into(), conn: tokio::sync::Mutex::new(conn), tools });
        }
        McpManager { servers }
    }
}
