//! XenCode — 100% Rust native GUI + agent core.
//!
//! Render mode:     `xencode --screenshot --out DIR` draws every primary page
//!                  through the same renderer the live window uses (tiny-skia
//!                  software rasterizer) and writes PNGs — visual ground truth.
//! Agent mode:      `xencode --agent "prompt"` runs the Phase-1 agent core
//!                  headlessly against the GLM API (XENCODE_API_KEY) with
//!                  subagents, MCP tools and shadow-git checkpoints (Phase 4).
//! Exec mode:       `xencode exec "prompt" --json` is the Phase-4 headless/CI
//!                  contract: never interactive, JSON envelope, --max-turns,
//!                  session --continue/--resume/--fork, --no-session.
//! Checkpoints:     `xencode --checkpoints` lists the rewind timeline;
//!                  `xencode --rewind <id|hash|latest>` restores it.
//! Agent-UI mode:   `xencode --agent-ui "prompt" --out FILE.png` runs the
//!                  agent and renders the live conversation (tool rows,
//!                  subagent cards, checkpoints, consent cards, streamed
//!                  answer) into the chat pane — the composer → loop wiring
//!                  made visible. `XENCODE_DEMO=1` substitutes a scripted
//!                  provider so the wiring renders without an API key.
//! Transcript mode: `xencode --render-session <id> --out FILE.png` renders a
//!                  saved session from `.xencode/sessions/`.
//! Commands mode:   `xencode --commands` lists the markdown slash commands
//!                  found in `.xencode/commands/` (project) and
//!                  `~/.xencode/commands/` (user).
//! Plan mode:       `xencode --plan "task"` (or `--permission-mode plan`)
//!                  researches read-only, prints the plan, and — in a TTY —
//!                  asks for approval before implementing.
//! Recipes:         `xencode --recipes` lists declarative playbooks from
//!                  `.xencode/recipes/*.md`; `xencode recipe <name> k=v …`
//!                  runs one (params → steps → agent loops, QuickJS preludes).
//! Oracle:          `xencode --oracle "question"` asks the second-opinion
//!                  agent (Phase 5) — read-only loop on the configured model.
//! Repo map:        `xencode --repo-map` prints the tree-sitter repo map
//!                  (same section the system prompt gets).
//! Test fixture:    `xencode --mcp-test-server` speaks the MCP protocol over
//!                  stdio — the integration-test target for the MCP client.

mod agent;
mod fonts;
mod gfx;
mod icons;
mod svgpath;
mod theme;
mod ui;

use agent::permissions::PermissionMode;
use agent::run::{run_agent, AgentEvent, AgentOptions, Asker, AskFn};
use agent::session::Session;
use agent::{commands, permissions, CliOptions, ExecOptions, Resume};
use anyhow::{bail, Context, Result};
use gfx::{Canvas, Rect};
use ui::shell::{draw_backdrop, draw_header, draw_panel, draw_sidebar_footer, draw_top_overlay, HeaderTitle, HEADER_H, WIN_H, WIN_W, panel_rect};
use ui::state::{ChatPage, Collector};

const SCALE: f32 = 2.0;
const DEFAULT_SHOTS_DIR: &str = "/home/z/my-project/download/xencode-rust-screenshots";

enum Mode {
    Screenshot { out: Option<String> },
    Agent { prompt: String, cli: CliOptions },
    AgentUi { prompt: String, out: String },
    RenderSession { id: String, out: String },
    Commands,
    // Phase 4 -------------------------------------------------------------
    Exec { prompt: String, ex: ExecOptions },
    Checkpoints,
    Rewind { target: String },
    McpTestServer,
    // Phase 5 -------------------------------------------------------------
    Recipes,
    Recipe { name: String, params: Vec<(String, String)>, cli: CliOptions },
    Oracle { question: String },
    RepoMap,
}

fn main() {
    let mode = match parse_args() {
        Ok(m) => m,
        Err(e) => {
            eprintln!("{e:#}");
            std::process::exit(2);
        }
    };

    let result = match mode {
        Mode::Screenshot { out } => {
            screenshot(out.as_deref().unwrap_or(DEFAULT_SHOTS_DIR));
            Ok(())
        }
        Mode::Agent { prompt, cli } => agent::run_cli(&prompt, cli),
        Mode::AgentUi { prompt, out } => agent_ui(&prompt, &out),
        Mode::RenderSession { id, out } => render_session(&id, &out),
        Mode::Commands => list_commands(),
        Mode::Exec { prompt, ex } => agent::run_exec(&prompt, ex),
        Mode::Checkpoints => list_checkpoints(),
        Mode::Rewind { target } => rewind(&target),
        Mode::McpTestServer => mcp_test_server(),
        Mode::Recipes => agent::list_recipes(),
        Mode::Recipe { name, params, cli } => {
            agent::recipes::run_recipe(&name, &params, &cli).map(|answer| println!("{answer}"))
        }

        Mode::Oracle { question } => agent::run_oracle(&question),
        Mode::RepoMap => print_repo_map(),
    };

    if let Err(e) = result {
        eprintln!("xencode: {e:#}");
        std::process::exit(1);
    }
}

fn parse_args() -> Result<Mode> {
    let args: Vec<String> = std::env::args().skip(1).collect();

    // `xencode exec ...` — the Phase-4 headless subcommand.
    if args.first().map(|s| s.as_str()) == Some("exec") {
        return parse_exec(&args[1..]);
    }
    // `xencode recipe <name> [k=v ...]` — the Phase-5 playbook runner.
    if args.first().map(|s| s.as_str()) == Some("recipe") {
        return parse_recipe(&args[1..]);
    }

    let mut out: Option<String> = None;
    let mut cli = CliOptions::default();
    let mut agent: Option<String> = None;
    let mut agent_ui: Option<String> = None;
    let mut render: Option<String> = None;
    let mut i = 0;

    while i < args.len() {
        match args[i].as_str() {
            "--out" if i + 1 < args.len() => {
                out = Some(args[i + 1].clone());
                i += 2;
                continue;
            }
            "--agent" if i + 1 < args.len() => {
                agent = Some(args[i + 1].clone());
                i += 1; // prompt consumed; keep scanning trailing flags
            }
            "--agent-ui" if i + 1 < args.len() => {
                agent_ui = Some(args[i + 1].clone());
                i += 1;
            }
            "--render-session" if i + 1 < args.len() => {
                render = Some(args[i + 1].clone());
                i += 1;
            }
            "--continue" | "-c" => cli.resume = Some(Resume::Continue),
            "--resume" if i + 1 < args.len() => {
                cli.resume = Some(Resume::Id(args[i + 1].clone()));
                i += 2;
                continue;
            }
            "--fork" if i + 1 < args.len() => {
                cli.resume = Some(Resume::Fork(args[i + 1].clone()));
                i += 2;
                continue;
            }
            "--yes" | "-y" => cli.auto_yes = true,
            "--read-only" => cli.read_only = true,
            "--plan" => cli.permission_mode = Some(PermissionMode::Plan),
            "--permission-mode" if i + 1 < args.len() => {
                let raw = &args[i + 1];
                match PermissionMode::parse(raw) {
                    Some(m) => cli.permission_mode = Some(m),
                    None => bail!(
                        "unknown permission mode `{raw}` — expected one of: \
                         default, accept-edits, read-only, plan, yolo"
                    ),
                }
                i += 2;
                continue;
            }
            "--commands" => return Ok(Mode::Commands),
            "--no-checkpoints" => cli.no_checkpoints = true,
            "--no-repo-map" => cli.no_repo_map = true,
            "--checkpoints" => return Ok(Mode::Checkpoints),
            "--rewind" if i + 1 < args.len() => {
                return Ok(Mode::Rewind { target: args[i + 1].clone() });
            }
            "--mcp-test-server" => return Ok(Mode::McpTestServer),
            "--recipes" => return Ok(Mode::Recipes),
            "--oracle" if i + 1 < args.len() => {
                return Ok(Mode::Oracle { question: args[i + 1].clone() });
            }
            "--repo-map" => return Ok(Mode::RepoMap),
            "--screenshot" | "screenshot" | "" => {}
            "--window" => bail!("live window mode: not wired yet (winit+softbuffer next); use --screenshot"),
            other => bail!("unknown flag: {other}"),
        }
        i += 1;
    }

    // Deferred mode resolution — flags may appear on either side of the
    // prompt/session arguments.
    if let Some(prompt) = agent {
        return Ok(Mode::Agent { prompt, cli });
    }
    if let Some(prompt) = agent_ui {
        let out = out.unwrap_or_else(|| "agent-ui.png".into());
        return Ok(Mode::AgentUi { prompt, out });
    }
    if let Some(id) = render {
        let out = out.unwrap_or_else(|| format!("{id}.png"));
        return Ok(Mode::RenderSession { id, out });
    }

    Ok(Mode::Screenshot { out })
}

/// Parse the `exec` subcommand: `xencode exec "prompt" [flags]`.
fn parse_exec(args: &[String]) -> Result<Mode> {
    let mut ex = ExecOptions::default();
    let mut prompt: Option<String> = None;
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "--json" => ex.json = true,
            "--max-turns" if i + 1 < args.len() => {
                ex.max_turns = Some(
                    args[i + 1]
                        .parse()
                        .context("--max-turns expects a positive integer")?,
                );
                i += 1;
            }
            "--no-session" => ex.no_session = true,
            "--continue" | "-c" => ex.cli.resume = Some(Resume::Continue),
            "--resume" if i + 1 < args.len() => {
                ex.cli.resume = Some(Resume::Id(args[i + 1].clone()));
                i += 1;
            }
            "--fork" if i + 1 < args.len() => {
                ex.cli.resume = Some(Resume::Fork(args[i + 1].clone()));
                i += 1;
            }
            "--yes" | "-y" => ex.cli.auto_yes = true,
            "--read-only" => ex.cli.read_only = true,
            "--no-checkpoints" => ex.cli.no_checkpoints = true,
            "--no-repo-map" => ex.cli.no_repo_map = true,
            "--plan" => ex.cli.permission_mode = Some(PermissionMode::Plan),
            "--permission-mode" if i + 1 < args.len() => {
                let raw = &args[i + 1];
                match PermissionMode::parse(raw) {
                    Some(m) => ex.cli.permission_mode = Some(m),
                    None => bail!(
                        "unknown permission mode `{raw}` — expected one of: \
                         default, accept-edits, read-only, plan, yolo"
                    ),
                }
                i += 1;
            }
            other if other.starts_with('-') => bail!("unknown exec flag: {other}"),
            other if prompt.is_none() => prompt = Some(other.to_string()),
            other => bail!("unexpected argument: {other}"),
        }
        i += 1;
    }
    let Some(prompt) = prompt else {
        bail!(
            "usage: xencode exec \"prompt\" [--json] [--max-turns N] [--no-session] \
             [--continue | --resume ID | --fork ID] [--read-only] [--permission-mode M] [--no-repo-map]"
        );
    };
    Ok(Mode::Exec { prompt, ex })
}

/// Parse the `recipe` subcommand: `xencode recipe <name> [key=value ...]`.
fn parse_recipe(args: &[String]) -> Result<Mode> {
    let mut cli = CliOptions::default();
    let mut name: Option<String> = None;
    let mut params: Vec<(String, String)> = Vec::new();
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "--yes" | "-y" => cli.auto_yes = true,
            "--read-only" => cli.read_only = true,
            "--no-checkpoints" => cli.no_checkpoints = true,
            "--no-repo-map" => cli.no_repo_map = true,
            "--plan" => cli.permission_mode = Some(PermissionMode::Plan),
            "--permission-mode" if i + 1 < args.len() => {
                let raw = &args[i + 1];
                match PermissionMode::parse(raw) {
                    Some(m) => cli.permission_mode = Some(m),
                    None => bail!(
                        "unknown permission mode `{raw}` — expected one of: \
                         default, accept-edits, read-only, plan, yolo"
                    ),
                }
                i += 1;
            }
            other if other.starts_with('-') => bail!("unknown recipe flag: {other}"),
            other if name.is_none() => name = Some(other.to_string()),
            other => {
                let (k, v) = other
                    .split_once('=')
                    .context("recipe params look like key=value")?;
                params.push((k.to_string(), v.to_string()));
            }
        }
        i += 1;
    }
    let Some(name) = name else {
        bail!("usage: xencode recipe <name> [key=value ...]  (list: xencode --recipes)");
    };
    Ok(Mode::Recipe { name, params, cli })
}

/// `xencode --repo-map` — print the repo map the system prompt receives.
fn print_repo_map() -> Result<()> {
    let cwd = std::env::current_dir().context("resolve working directory")?;
    let map = agent::repomap::build(&cwd, agent::DEFAULT_REPO_MAP_BUDGET);
    if map.text.is_empty() {
        println!("(no parseable source files found)");
        return Ok(());
    }
    println!(
        "repo map — {} file(s), {} symbol(s){}",
        map.files,
        map.symbols,
        if map.truncated { ", truncated" } else { "" }
    );
    print!("{}", map.text);
    Ok(())
}

/// `xencode --checkpoints` — print the rewind timeline (oldest first).
fn list_checkpoints() -> Result<()> {
    let cwd = std::env::current_dir().context("resolve working directory")?;
    let store = agent::checkpoint::CheckpointStore::open(&cwd);
    let list = store.list();
    if list.is_empty() {
        println!("no checkpoints yet — one is recorded before every edit");
        return Ok(());
    }
    println!("checkpoint timeline (oldest first):");
    for cp in &list {
        println!(
            "  {}  {}  before {} {}",
            cp.id,
            fmt_ts_ms(cp.ts_unix_ms),
            cp.tool,
            cp.detail
        );
    }
    println!("rewind with: xencode --rewind <id | hash prefix | latest>");
    Ok(())
}

/// `xencode --rewind <id|hash|latest>` — restore the worktree to a checkpoint.
fn rewind(target: &str) -> Result<()> {
    let cwd = std::env::current_dir().context("resolve working directory")?;
    let rt = tokio::runtime::Runtime::new().context("start tokio runtime")?;
    rt.block_on(async move {
        let store = agent::checkpoint::CheckpointStore::open(&cwd);
        store.rewind(target).await.map(|_| ())
    })
}

/// `xencode --mcp-test-server` — a minimal MCP server over stdio used by the
/// client's end-to-end test: initialize / tools/list / tools/call / ping.
fn mcp_test_server() -> Result<()> {
    use std::io::{BufRead, Write};
    let stdin = std::io::stdin();
    let mut out = std::io::stdout();
    for line in stdin.lock().lines() {
        let line = line?;
        let Ok(v) = serde_json::from_str::<serde_json::Value>(&line) else { continue };
        let id = v.get("id").cloned();
        let Some(method) = v.get("method").and_then(|m| m.as_str()).map(str::to_string) else { continue };
        let resp = match method.as_str() {
            "initialize" => serde_json::json!({
                "jsonrpc": "2.0", "id": id,
                "result": {
                    "protocolVersion": "2024-11-05",
                    "capabilities": { "tools": {} },
                    "serverInfo": { "name": "xencode-test", "version": "0.0.1" }
                }
            }),
            "tools/list" => serde_json::json!({
                "jsonrpc": "2.0", "id": id,
                "result": { "tools": [
                    {
                        "name": "echo",
                        "description": "Echo a message back",
                        "inputSchema": {
                            "type": "object",
                            "properties": { "message": { "type": "string" } },
                            "required": ["message"]
                        }
                    },
                    {
                        "name": "add",
                        "description": "Add two numbers",
                        "inputSchema": {
                            "type": "object",
                            "properties": { "a": { "type": "number" }, "b": { "type": "number" } },
                            "required": ["a", "b"]
                        }
                    }
                ]}
            }),
            "tools/call" => {
                let name = v["params"]["name"].as_str().unwrap_or_default().to_string();
                let args = v["params"]["arguments"].clone();
                let text = match name.as_str() {
                    "echo" => args["message"].as_str().unwrap_or_default().to_string(),
                    "add" => format!("{}", args["a"].as_f64().unwrap_or(0.0) + args["b"].as_f64().unwrap_or(0.0)),
                    other => format!("unknown tool: {other}"),
                };
                serde_json::json!({
                    "jsonrpc": "2.0", "id": id,
                    "result": { "content": [ { "type": "text", "text": text } ] }
                })
            }
            "ping" => serde_json::json!({ "jsonrpc": "2.0", "id": id, "result": {} }),
            other => {
                if id.is_none() {
                    continue; // unknown notification — stay silent
                }
                serde_json::json!({
                    "jsonrpc": "2.0", "id": id,
                    "error": { "code": -32601, "message": format!("method not found: {other}") }
                })
            }
        };
        writeln!(out, "{resp}")?;
        out.flush()?;
    }
    Ok(())
}

/// Format unix milliseconds as `YYYY-MM-DD HH:MM:SS` (UTC).
fn fmt_ts_ms(ms: u64) -> String {
    let secs = ms / 1000;
    let days = (secs / 86_400) as i64;
    let rem = secs % 86_400;
    let (h, m, s) = (rem / 3600, (rem % 3600) / 60, rem % 60);
    // Howard Hinnant's civil-from-days (proleptic Gregorian).
    let z = days + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let mth = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = if mth <= 2 { y + 1 } else { y };
    format!("{y:04}-{mth:02}-{d:02} {h:02}:{m:02}:{s:02}")
}

/// `xencode --commands` — list every markdown slash command visible to the
/// current project (user scope + project scope, project wins on clashes).
fn list_commands() -> Result<()> {
    let cwd = std::env::current_dir().context("resolve working directory")?;
    let cmds = commands::list(&cwd);
    if cmds.is_empty() {
        println!("no slash commands found");
        println!(
            "add markdown files to .xencode/commands/ (project) or ~/.xencode/commands/ (user)"
        );
        return Ok(());
    }
    println!("available slash commands:");
    for c in &cmds {
        let desc = if c.description.is_empty() {
            String::new()
        } else {
            format!(" — {}", c.description)
        };
        println!("  /{:<14} [{}]{}", c.name, c.scope, desc);
    }
    Ok(())
}

fn screenshot(out: &str) {
    std::fs::create_dir_all(out).expect("create out dir");
    let pages: &[(&str, fn(&mut Canvas))] = &[
        ("01-splash", ui::splash::draw),
        ("02-home", ui::home),
        ("03-chat", ui::chat),
        ("04-settings-appearance", ui::settings),
        ("05-git-pane", ui::git),
        ("06-terminal-dock", ui::terminal),
    ];
    for (name, draw) in pages {
        let mut c = Canvas::new(WIN_W, WIN_H, SCALE);
        draw(&mut c);
        let path = format!("{out}/{name}.png");
        c.px.save_png(&path).unwrap_or_else(|e| panic!("save {path}: {e:?}"));
        println!("rendered {path}");
    }
}

/// Frame a chat page in the full workspace shell (backdrop, panel, header,
/// sidebar) — the same chrome the static pages use.
fn render_chat_page(page: &ChatPage, header: HeaderTitle<'_>, out: &str) -> Result<()> {
    let mut c = Canvas::new(WIN_W, WIN_H, SCALE);
    draw_backdrop(&mut c);
    let panel = panel_rect();
    draw_panel(&mut c, panel);
    draw_top_overlay(&mut c);
    draw_header(&mut c, panel, &header, false, false);
    ui::sidebar::draw(&mut c);
    draw_sidebar_footer(&mut c);
    let area = Rect::new(panel.x, panel.y + HEADER_H, panel.w, panel.h - HEADER_H);
    ui::chat::draw_state(&mut c, area, page);

    if let Some(parent) = std::path::Path::new(out).parent() {
        std::fs::create_dir_all(parent)?;
    }
    c.px.save_png(out).map_err(|e| anyhow::anyhow!("save {out}: {e:?}"))?;
    println!("rendered {out}");
    Ok(())
}

fn render_session(id: &str, out: &str) -> Result<()> {
    let session_dir = std::env::current_dir()?.join(".xencode").join("sessions");
    let s = Session::load(&session_dir, id).with_context(|| format!("load session {id}"))?;
    let page = ChatPage::from_conversation(&s.messages);
    let header = HeaderTitle::Task {
        project: "xencode",
        title: "Restored session",
        summary: Some("from .xencode/sessions"),
    };
    render_chat_page(&page, header, out)
}

/// Run the agent and render the live conversation into the chat pane.
fn agent_ui(prompt: &str, out: &str) -> Result<()> {
    let demo = std::env::var("XENCODE_DEMO").map(|v| v == "1").unwrap_or(false);
    let cwd = std::env::current_dir().context("resolve working directory")?;
    let session_dir = cwd.join(".xencode").join("sessions");

    let rt = tokio::runtime::Runtime::new().context("start tokio runtime")?;
    let (page, session_id) = rt.block_on(async {
        let (page, id) = if demo {
            demo_run(prompt, &session_dir).await?
        } else {
            live_run(prompt, &session_dir).await?
        };
        Ok::<_, anyhow::Error>((page, id))
    })?;

    let summary = if session_id.is_empty() {
        "live agent run".to_string()
    } else {
        format!("session {session_id}")
    };
    let header = HeaderTitle::Task {
        project: "xencode",
        title: &page.title,
        summary: Some(&summary),
    };
    render_chat_page(&page, header, out)
}

/// Run the loop inline and fold its (buffered) event stream into the
/// collector once the run completes. The channel is unbounded, so the loop
/// never blocks on a slow consumer.
async fn collect_run(
    provider: std::sync::Arc<dyn agent::provider::Provider>,
    prompt: &str,
    opts: AgentOptions,
    collector: &mut Collector,
) -> Result<String> {
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<AgentEvent>();
    let answer = run_agent(provider, prompt, opts, tx).await;
    while let Ok(ev) = rx.try_recv() {
        collector.on(&ev);
    }
    answer
}

/// Headless agent-UI runs cannot prompt interactively; asks resolve like
/// `--yes` and the pane renders the resolved consent card afterwards.
struct AutoAsker;

impl Asker for AutoAsker {
    fn ask(&mut self, req: &permissions::Request) -> permissions::Response {
        eprintln!("· permission auto-approved: {} `{}`", req.tool, req.detail);
        permissions::Response::Allow
    }
}

async fn live_run(prompt: &str, session_dir: &std::path::Path) -> Result<(ChatPage, String)> {
    let provider: std::sync::Arc<dyn agent::provider::Provider> =
        std::sync::Arc::new(agent::provider::GlmProvider::from_env()?);
    let mut collector = Collector::new(prompt, "Agent run");
    let cwd = std::env::current_dir().unwrap_or_default();
    let mcp = agent::connect_mcp(&cwd).await;
    let harness = agent::harness_for_project(&cwd);
    let telemetry = agent::telemetry::Telemetry::for_run(&cwd, &harness.telemetry_cfg);
    let oracle_setup = agent::recipes::build_oracle(&harness, &provider)?;
    let opts = AgentOptions {
        cwd: cwd.clone(),
        session_dir: Some(session_dir.to_path_buf()),
        gate: Box::new(permissions::AskGate::new()),
        asker: Some(Box::new(AutoAsker)),
        hooks: harness.hooks.clone(),
        mcp,
        checkpoints: Some(agent::checkpoint::CheckpointStore::open(&cwd)),
        repo_map: Some(agent::DEFAULT_REPO_MAP_BUDGET),
        diagnostics: harness.diagnostics.clone(),
        telemetry: telemetry.clone(),
        oracle: oracle_setup,
        ..Default::default()
    };
    collect_run(provider, prompt, opts, &mut collector).await?;
    if let Some(t) = &telemetry {
        eprintln!("{}", t.finish(0).await);
    }
    let id = Session::latest(session_dir).ok().flatten().unwrap_or_default();
    Ok((collector.page, id))
}

/// Scripted run for `XENCODE_DEMO=1` — a tiny fake project exercising the
/// Phase-4 surfaces: a subagent dispatch (with its own project agent file),
/// a checkpointed write, and the final answer.
async fn demo_run(prompt: &str, session_dir: &std::path::Path) -> Result<(ChatPage, String)> {
    use agent::provider::{Message, MockProvider, Role, ToolCall};
    use serde_json::json;

    let dir = std::env::temp_dir().join(format!("xencode-demo-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    std::fs::create_dir_all(dir.join("src"))?;
    std::fs::write(
        dir.join("src/main.rs"),
        "fn main() {\n    println!(\"xencode\");\n}\n",
    )?;
    // A project subagent for the demo dispatch.
    std::fs::create_dir_all(dir.join(".xencode/agents"))?;
    std::fs::write(
        dir.join(".xencode/agents/reviewer.md"),
        "---\nname: reviewer\ndescription: Reviews the demo project\ntools: read_file, list_dir\n---\nYou are a careful code reviewer. Read the named files and report issues as a numbered list.",
    )?;

    // Turn order: parent task call → child's final report → parent write
    // (checkpointed) → parent's final answer. One shared scripted queue.
    let provider = MockProvider::new(vec![
        Message {
            role: Role::Assistant,
            content: "Let me get a second opinion on the entrypoint.".into(),
            tool_calls: vec![ToolCall {
                id: "t1".into(),
                name: "task".into(),
                arguments: json!({"agent": "reviewer", "prompt": "Review src/main.rs for issues"}),
            }],
            tool_call_id: None,
            usage: None,
        },
        // Consumed by the child reviewer loop as its final report.
        Message {
            role: Role::Assistant,
            content: "Review complete: main.rs is a minimal hello-world. No issues found.".into(),
            tool_calls: vec![],
            tool_call_id: None,
            usage: None,
        },
        Message {
            role: Role::Assistant,
            content: "The reviewer signed off. Recording the findings.".into(),
            tool_calls: vec![ToolCall {
                id: "t2".into(),
                name: "write_file".into(),
                arguments: json!({"path": "NOTES.md", "content": "# Review notes\n\n- main.rs is clean; no issues found."}),
            }],
            tool_call_id: None,
            usage: None,
        },
        Message {
            role: Role::Assistant,
            content: "Reviewer agent signed off (no issues) and the findings are recorded in NOTES.md — demo complete.".into(),
            tool_calls: vec![],
            tool_call_id: None,
            usage: None,
        },
    ]);

    let mut collector = Collector::new(prompt, "Demo agent run");
    let opts = AgentOptions {
        cwd: dir.clone(),
        session_dir: Some(session_dir.to_path_buf()),
        gate: Box::new(permissions::AskGate::new()),
        asker: Some(Box::new(AskFn(|req: &permissions::Request| {
            eprintln!("· permission (demo): {} `{}` — allowed", req.tool, req.detail);
            permissions::Response::AllowAlways
        }))),
        checkpoints: Some(agent::checkpoint::CheckpointStore::open(&dir)),
        ..Default::default()
    };

    collect_run(std::sync::Arc::new(provider), prompt, opts, &mut collector).await?;
    let id = Session::latest(session_dir).ok().flatten().unwrap_or_default();
    let _ = std::fs::remove_dir_all(&dir);
    Ok((collector.page, id))
}
