//! XenCode CLI — the `xencode` command.
//!
//! Full coding-agent command surface: interactive chat REPL, one-shot exec,
//! sessions, checkpoints, todos, MCP, .mjs automation, decision cards,
//! events, worktrees, goal DAG, XP telemetry, config, doctor.

mod repl;

use anyhow::{bail, Context as _};
use clap::{Parser, Subcommand};
use std::path::{Path, PathBuf};
use xencode_core::config::{load_config, ProviderConfig, ProviderKind};
use xencode_core::permissions::PermissionMode;
use xencode_core::state::checkpoints::CheckpointStore;
use xencode_core::state::decisions::DecisionStore;
use xencode_core::state::events::EventLog;
use xencode_core::state::sessions::{Session, SessionMeta, SessionStore};
use xencode_core::telemetry::{Telemetry, XpLedger};
use xencode_core::tools::todo::TodoStore;

#[derive(Parser)]
#[command(
    name = "xencode",
    version,
    about = "XenCode — autonomous coding agent (Rust PC line)",
    long_about = "XenCode: elite autonomous software engineering agent.\nRust desktop + CLI with an embedded rquickjs .mjs automation engine."
)]
pub struct Cli {
    /// Workspace directory (defaults to cwd or config defaultWorkspace)
    #[arg(long, global = true)]
    pub workspace: Option<PathBuf>,

    /// Permission mode: read-only | confirm | yolo
    #[arg(long, global = true, default_value = "confirm")]
    pub mode: String,

    #[command(subcommand)]
    pub command: Command,
}

#[derive(Subcommand)]
pub enum Command {
    /// Interactive chat REPL with slash commands
    Chat,
    /// One-shot agent run: xencode exec "do the thing"
    Exec {
        prompt: String,
        /// Emit the full final answer only (no streaming dots)
        #[arg(long)]
        quiet: bool,
    },
    /// Run a .mjs automation script (rquickjs engine)
    Run {
        script: PathBuf,
        /// JSON args exposed to the script as `__xe_args`
        #[arg(long)]
        args: Option<String>,
    },
    /// Session management
    Session {
        #[command(subcommand)]
        cmd: SessionCmd,
    },
    /// Checkpoint time machine
    Checkpoint {
        #[command(subcommand)]
        cmd: CheckpointCmd,
    },
    /// Todo list
    Todo {
        #[command(subcommand)]
        cmd: TodoCmd,
    },
    /// MCP (Model Context Protocol) servers
    Mcp {
        #[command(subcommand)]
        cmd: McpCmd,
    },
    /// Decision cards (blocking agent questions)
    Decide {
        #[command(subcommand)]
        cmd: DecideCmd,
    },
    /// Event log (mobile + desktop sync channel)
    Events {
        /// Number of tail entries
        #[arg(long, default_value = "20")]
        n: usize,
    },
    /// Git worktree swimlanes
    Worktree {
        #[command(subcommand)]
        cmd: WorktreeCmd,
    },
    /// Goal DAG
    Goal {
        #[command(subcommand)]
        cmd: GoalCmd,
    },
    /// Show the DAG
    Dag,
    /// XP / streak telemetry
    Xp,
    /// Spec radar / compile streak
    Radar,
    /// Configuration
    Config {
        #[command(subcommand)]
        cmd: ConfigCmd,
    },
    /// Environment + workspace diagnostics
    Doctor,
    /// Initialize a workspace (.xencode/ scaffolding)
    Init,
}

#[derive(Subcommand)]
pub enum SessionCmd {
    List,
    Show { id: String },
    /// Resume a session in the REPL
    Resume { id: String },
    Delete { id: String },
}

#[derive(Subcommand)]
pub enum CheckpointCmd {
    List,
    Create { label: String, summary: String },
    Restore { id: String },
    Diff { id: String },
}

#[derive(Subcommand)]
pub enum TodoCmd {
    /// Replace the list: --item "content" --item ... [--status pending]
    Set {
        #[arg(long)]
        items: Vec<String>,
    },
    Show,
}

#[derive(Subcommand)]
pub enum McpCmd {
    /// List configured servers and their tools
    List,
    /// Call an MCP tool: xencode mcp call <server> <tool> '<json>'
    Call { server: String, tool: String, args: String },
}

#[derive(Subcommand)]
pub enum DecideCmd {
    List,
    Resolve { id: String, choice: usize },
}

#[derive(Subcommand)]
pub enum WorktreeCmd {
    List,
    Create { id: String },
    Remove { id: String },
}

#[derive(Subcommand)]
pub enum GoalCmd {
    /// Add a node: xencode goal add "title" "detail" --depends g1,g2
    Add {
        title: String,
        #[arg(default_value = "")]
        detail: String,
        #[arg(long)]
        depends: Option<String>,
    },
    List,
    /// Execute all READY nodes (parallel swimlanes)
    Run {
        #[arg(long, default_value = "1")]
        parallel: usize,
    },
    /// Manually set node state: done|failed|ready|active|cancel
    Set { id: String, state: String },
}

#[derive(Subcommand)]
pub enum ConfigCmd {
    Show,
    /// Add or update a provider
    SetProvider {
        name: String,
        #[arg(long, default_value = "openai")]
        kind: String,
        #[arg(long)]
        base_url: String,
        #[arg(long)]
        api_key: String,
        #[arg(long)]
        model: String,
    },
    /// Set the active provider
    Use { name: String },
    /// Set the default workspace
    Workspace { path: PathBuf },
}

pub fn resolve_workspace(cli: &Cli) -> anyhow::Result<PathBuf> {
    if let Some(ws) = &cli.workspace {
        return Ok(ws.clone());
    }
    if let Ok(env_ws) = std::env::var("XENCODE_WORKSPACE") {
        if !env_ws.trim().is_empty() {
            return Ok(PathBuf::from(env_ws));
        }
    }
    if let Ok(env_ws) = std::env::var("AICODE_WORKSPACE") {
        if !env_ws.trim().is_empty() {
            return Ok(PathBuf::from(env_ws));
        }
    }
    if let Ok(cfg) = load_config() {
        if let Some(ws) = cfg.default_workspace {
            return Ok(PathBuf::from(ws));
        }
    }
    Ok(std::env::current_dir()?)
}

pub fn parse_mode(s: &str) -> anyhow::Result<PermissionMode> {
    PermissionMode::parse(s).ok_or_else(|| anyhow::anyhow!("unknown permission mode `{s}` (read-only | confirm | yolo)"))
}

pub fn main() -> anyhow::Result<()> {
    let cli = Cli::parse();
    let rt = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()?;
    rt.block_on(run(cli))
}

async fn run(cli: Cli) -> anyhow::Result<()> {
    let workspace = resolve_workspace(&cli)?;
    std::fs::create_dir_all(workspace.join(".xencode")).ok();
    let mode = parse_mode(&cli.mode)?;

    match cli.command {
        Command::Chat => repl::run_repl(&workspace, mode).await?,
        Command::Exec { prompt, quiet } => exec_once(&workspace, mode, &prompt, quiet).await?,
        Command::Run { script, args } => run_mjs(&workspace, &script, args)?,
        Command::Session { cmd } => session_cmd(&workspace, cmd)?,
        Command::Checkpoint { cmd } => checkpoint_cmd(&workspace, cmd)?,
        Command::Todo { cmd } => todo_cmd(&workspace, cmd)?,
        Command::Mcp { cmd } => mcp_cmd(&workspace, cmd)?,
        Command::Decide { cmd } => decide_cmd(&workspace, cmd)?,
        Command::Events { n } => {
            let log = EventLog::new(&workspace);
            println!("{}", log.render_tail(n));
        }
        Command::Worktree { cmd } => worktree_cmd(&workspace, cmd)?,
        Command::Goal { cmd } => goal_cmd(&workspace, cmd).await?,
        Command::Dag => {
            let dag = xencode_core::goal::file::load(&workspace);
            println!("{}", dag.render());
        }
        Command::Xp => {
            let ledger = XpLedger::default();
            println!("{}", ledger.render_summary(xencode_core::now_ms()));
        }
        Command::Radar => {
            let t = Telemetry::load(&workspace);
            println!("{}", t.render_radar());
        }
        Command::Config { cmd } => config_cmd(cmd)?,
        Command::Doctor => doctor(&workspace)?,
        Command::Init => init_workspace(&workspace)?,
    }
    Ok(())
}

fn make_session(
    workspace: &Path,
    mode: PermissionMode,
    with_events: bool,
) -> anyhow::Result<xencode_core::agent::AgentSession> {
    let cfg = load_config()?;
    cfg.validate().context("provider configuration invalid")?;
    let mut session = xencode_core::agent::session_from_config(workspace, &cfg, mode, with_events)
        .map_err(anyhow::Error::msg)?;
    let mcp = xencode_core::mcp::McpRegistry::connect_all(workspace);
    if !mcp.is_empty() {
        for (name, status) in &mcp.statuses {
            println!("[mcp] {name}: {status}");
        }
        session.system_prompt = xencode_core::prompt::build_system_prompt(workspace, &session.tool_specs().iter().map(|t| t.name.clone()).collect::<Vec<_>>());
        session.mcp = Some(std::sync::Arc::new(std::sync::Mutex::new(mcp)));
    }
    Ok(session)
}

async fn exec_once(workspace: &Path, mode: PermissionMode, prompt: &str, quiet: bool) -> anyhow::Result<()> {
    let mut session = make_session(workspace, mode, true)?;
    let mut buf = String::new();
    let out = session
        .send(prompt, &mut |d| {
            if !quiet {
                print!("{d}");
                use std::io::Write;
                let _ = std::io::stdout().flush();
            } else {
                buf.push_str(d);
            }
        }, &mut |tc| {
            if !quiet {
                eprintln!("\n[tool] {} ({})", tc.name, tc.args_json.chars().take(120).collect::<String>());
            }
        })
        .await;
    if !quiet {
        println!();
    }
    match out {
        Some(text) => {
            if quiet {
                println!("{text}");
            }
            // persist session
            let store = SessionStore::new(workspace);
            let mut s = Session {
                meta: SessionMeta {
                    id: xencode_core::short_id(),
                    title: prompt.chars().take(60).collect(),
                    created_at: xencode_core::now_ms(),
                    updated_at: xencode_core::now_ms(),
                    turns: 1,
                },
                messages: session.history_snapshot(),
                tokens_in: session.tokens_in,
                tokens_out: session.tokens_out,
            };
            store.save(&mut s).ok();
            Ok(())
        }
        None => {
            bail!("agent failed: {}", session.last_failure.clone().unwrap_or_else(|| "unknown".into()))
        }
    }
}

fn run_mjs(workspace: &Path, script: &Path, args: Option<String>) -> anyhow::Result<()> {
    let args: Option<serde_json::Value> = match args {
        Some(raw) => Some(serde_json::from_str(&raw).context("args must be valid JSON")?),
        None => None,
    };
    let engine = xencode_automation::MjsEngine::new(workspace);
    let result = engine.run_file(script, args).map_err(anyhow::Error::msg)?;
    let log = EventLog::new(workspace);
    log.emit(
        "MJS_RUN",
        Some(&script.display().to_string()),
        serde_json::Map::from_iter([
            ("files".to_string(), serde_json::Value::from(result.files_written.len())),
            ("commands".to_string(), serde_json::Value::from(result.commands_run.len())),
        ]),
    );
    print!("{}", xencode_automation::render_result(&result));
    Ok(())
}

fn session_cmd(workspace: &Path, cmd: SessionCmd) -> anyhow::Result<()> {
    let store = SessionStore::new(workspace);
    match cmd {
        SessionCmd::List => println!("{}", store.render_list()),
        SessionCmd::Show { id } => {
            let s = store.load(&id).ok_or_else(|| anyhow::anyhow!("no session `{id}`"))?;
            println!("# {} ({}) — {} turns", s.meta.title, s.meta.id, s.meta.turns);
            for m in &s.messages {
                println!("[{:?}] {}", m.role, m.content.as_deref().unwrap_or("(tool call)"));
            }
        }
        SessionCmd::Resume { id } => {
            let _ = store.load(&id).ok_or_else(|| anyhow::anyhow!("no session `{id}`"))?;
            println!("resuming {id} requires the interactive REPL: xencode chat --resume {id}");
        }
        SessionCmd::Delete { id } => {
            let gone = store.delete(&id).map_err(anyhow::Error::msg)?;
            println!("{}", if gone { "deleted" } else { "not found" });
        }
    }
    Ok(())
}

fn checkpoint_cmd(workspace: &Path, cmd: CheckpointCmd) -> anyhow::Result<()> {
    let store = CheckpointStore::new(workspace);
    match cmd {
        CheckpointCmd::List => println!("{}", store.render_list()),
        CheckpointCmd::Create { label, summary } => match store.create(&label, &summary) {
            Ok(cp) => println!("checkpoint {} @{} ({} files)", cp.id, cp.commit.as_deref().unwrap_or("-"), cp.files_changed),
            Err(e) => bail!("{e}"),
        },
        CheckpointCmd::Restore { id } => match store.restore(&id) {
            Ok(msg) => println!("{msg}"),
            Err(e) => bail!("{e}"),
        },
        CheckpointCmd::Diff { id } => match store.diff(&id) {
            Ok(msg) => println!("{msg}"),
            Err(e) => bail!("{e}"),
        },
    }
    Ok(())
}

fn todo_cmd(workspace: &Path, cmd: TodoCmd) -> anyhow::Result<()> {
    let store = TodoStore::new(workspace);
    match cmd {
        TodoCmd::Set { items } => {
            let todos = items
                .into_iter()
                .map(|content| xencode_core::tools::todo::TodoItem {
                    content,
                    status: "pending".into(),
                    priority: "medium".into(),
                })
                .collect();
            store.replace(todos).map_err(anyhow::Error::msg)?;
            println!("{}", store.render());
        }
        TodoCmd::Show => println!("{}", store.render()),
    }
    Ok(())
}

fn mcp_cmd(workspace: &Path, cmd: McpCmd) -> anyhow::Result<()> {
    let mut registry = xencode_core::mcp::McpRegistry::connect_all(workspace);
    match cmd {
        McpCmd::List => {
            if registry.statuses.is_empty() {
                println!("(no MCP servers configured — add .xencode/mcp.json)");
            }
            for (name, status) in &registry.statuses {
                println!("{name}: {status}");
            }
            for (prefixed, server, tool) in &registry.tools {
                println!("  {prefixed} (from {server}): {}", tool.description);
            }
        }
        McpCmd::Call { server, tool, args } => {
            let full = format!("mcp_{server}_{tool}");
            match registry.call(&full, &args) {
                Some(out) => println!("{out}"),
                None => bail!("tool `{full}` not found or server not connected"),
            }
        }
    }
    Ok(())
}

fn decide_cmd(workspace: &Path, cmd: DecideCmd) -> anyhow::Result<()> {
    let store = DecisionStore::new(workspace);
    match cmd {
        DecideCmd::List => println!("{}", store.render_list()),
        DecideCmd::Resolve { id, choice } => {
            let log = EventLog::new(workspace);
            match store.resolve(&id, choice) {
                Ok(card) => {
                    log.emit(
                        "DECISION_RESOLVED",
                        Some(&card.title),
                        serde_json::Map::from_iter([
                            ("card".to_string(), serde_json::Value::String(card.id.clone())),
                            ("choice".to_string(), serde_json::Value::from(choice)),
                        ]),
                    );
                    println!("resolved {} → {}", card.id, card.options[choice]);
                }
                Err(e) => bail!("{e}"),
            }
        }
    }
    Ok(())
}

fn worktree_cmd(workspace: &Path, cmd: WorktreeCmd) -> anyhow::Result<()> {
    match cmd {
        WorktreeCmd::List => {
            let lanes = xencode_core::gitx::worktree_list(workspace);
            if lanes.is_empty() {
                println!("(no worktree lanes)");
            }
            for lane in lanes {
                println!("{lane}");
            }
        }
        WorktreeCmd::Create { id } => match xencode_core::gitx::worktree_create(workspace, &id) {
            Ok((path, branch)) => println!("lane {id}: {} ({branch})", path.display()),
            Err(e) => bail!("{e}"),
        },
        WorktreeCmd::Remove { id } => match xencode_core::gitx::worktree_remove(workspace, &id) {
            Ok(()) => println!("removed {id}"),
            Err(e) => bail!("{e}"),
        },
    }
    Ok(())
}

async fn goal_cmd(workspace: &Path, cmd: GoalCmd) -> anyhow::Result<()> {
    let log = EventLog::new(workspace);
    match cmd {
        GoalCmd::Add { title, detail, depends } => {
            let mut dag = xencode_core::goal::file::load(workspace);
            let deps: Vec<String> = depends
                .map(|d| d.split(',').map(|s| s.trim().to_string()).filter(|s| !s.is_empty()).collect())
                .unwrap_or_default();
            match dag.add(&title, &detail, deps) {
                Ok(node) => {
                    xencode_core::goal::file::save(workspace, &dag).map_err(anyhow::Error::msg)?;
                    if dag.nodes.len() == 1 {
                        log.emit_simple("GOAL_INITIALIZED", Some(&title));
                    }
                    println!("added {} — {title}", node.id);
                }
                Err(e) => bail!("{e}"),
            }
        }
        GoalCmd::List => {
            let dag = xencode_core::goal::file::load(workspace);
            println!("{}", dag.render());
            println!("{}", dag.summary_counts());
        }
        GoalCmd::Run { parallel } => {
            let summary = xencode_core::goal::runner::run_ready(workspace, parallel).await;
            println!("{summary}");
        }
        GoalCmd::Set { id, state } => {
            let mut dag = xencode_core::goal::file::load(workspace);
            let result = match state.to_ascii_lowercase().as_str() {
                "done" => dag.mark_done(&id, Some("manual")),
                "failed" => dag.mark_failed(&id, Some("manual")),
                "ready" => dag.mark_ready(&id),
                "active" => dag.mark_active(&id),
                "cancel" | "cancelled" => dag.cancel(&id),
                other => bail!("unknown state `{other}` (done|failed|ready|active|cancel)"),
            };
            match result {
                Ok(_) => {
                    dag.refresh();
                    xencode_core::goal::file::save(workspace, &dag).map_err(anyhow::Error::msg)?;
                    println!("{id} → {state}");
                }
                Err(e) => bail!("{e}"),
            }
        }
    }
    Ok(())
}

fn config_cmd(cmd: ConfigCmd) -> anyhow::Result<()> {
    match cmd {
        ConfigCmd::Show => {
            let cfg = load_config()?;
            let masked = serde_json::to_string_pretty(&cfg)?;
            println!("{masked}");
        }
        ConfigCmd::SetProvider { name, kind, base_url, api_key, model } => {
            let mut cfg = load_config()?;
            let kind = ProviderKind::parse(&kind)?;
            let provider = ProviderConfig {
                name: name.clone(),
                kind,
                base_url,
                api_key,
                model,
                temperature: None,
                max_tokens: None,
                extra_headers: Default::default(),
            };
            if let Some(existing) = cfg.providers.iter_mut().find(|p| p.name == name) {
                *existing = provider;
            } else {
                cfg.providers.push(provider);
            }
            if cfg.active.is_none() {
                cfg.active = Some(name.clone());
            }
            xencode_core::config::save_config(&cfg)?;
            println!("provider `{name}` saved ({})", xencode_core::config::config_path().display());
        }
        ConfigCmd::Use { name } => {
            let mut cfg = load_config()?;
            if !cfg.providers.iter().any(|p| p.name == name) {
                bail!("no provider `{name}`");
            }
            cfg.active = Some(name.clone());
            xencode_core::config::save_config(&cfg)?;
            println!("active provider: {name}");
        }
        ConfigCmd::Workspace { path } => {
            let mut cfg = load_config()?;
            cfg.default_workspace = Some(path.display().to_string());
            xencode_core::config::save_config(&cfg)?;
            println!("default workspace: {}", path.display());
        }
    }
    Ok(())
}

fn doctor(workspace: &Path) -> anyhow::Result<()> {
    println!("XenCode doctor — {}", workspace.display());
    println!("  binary: xencode {}", env!("CARGO_PKG_VERSION"));
    println!("  os: {} {}", std::env::consts::OS, std::env::consts::ARCH);
    println!("  home: {}", xencode_core::config::xencode_home().display());
    let cfg = load_config()?;
    println!("  providers: {}", cfg.providers.len());
    if let Some(active) = &cfg.active {
        println!("  active: {active}");
    }
    let in_git = xencode_core::gitx::is_git_repo(workspace);
    println!("  git repo: {in_git}");
    let has_mcp = xencode_core::mcp::McpConfig::load(workspace);
    println!("  mcp servers: {}", has_mcp.servers.len());
    let t = Telemetry::load(workspace);
    println!("  {}", t.render_radar());
    println!("  {}", XpLedger::default().render_summary(xencode_core::now_ms()));
    Ok(())
}

fn init_workspace(workspace: &Path) -> anyhow::Result<()> {
    let dir = workspace.join(".xencode");
    std::fs::create_dir_all(&dir)?;
    let files = [
        ("STATE.md", "# XenCode STATE.md\n\nProject state lives here (the disk is the persistent brain).\n"),
        ("DECISIONS.md", "# XenCode DECISIONS.md\n\nArchitectural decisions log.\n"),
        ("BLOCKERS.md", "# XenCode BLOCKERS.md\n\n3-strike self-healing records.\n"),
    ];
    for (name, content) in files {
        let p = dir.join(name);
        if !p.exists() {
            std::fs::write(&p, content)?;
            println!("created .xencode/{name}");
        }
    }
    if !xencode_core::gitx::is_git_repo(workspace) {
        println!("note: not a git repository — `git init` enables checkpoints + swimlanes");
    }
    println!("workspace ready: {}", workspace.display());
    Ok(())
}
