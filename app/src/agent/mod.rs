//! XenCode agent core — provider clients, tools, the loop, sessions, gates.
//! Phase 1 of plan.md. The GUI (composer, panes) drives this through the
//! same public API the CLI uses below.

pub mod checkpoint;
pub mod commands;
pub mod compact;
pub mod mcp;
pub mod memory;
pub mod permissions;
pub mod provider;
pub mod run;
pub mod session;
pub mod subagent;
pub mod tools;

use anyhow::{Context, Result};
use std::io::IsTerminal;
use std::path::Path;
use std::sync::Arc;
use tokio::sync::mpsc;

use permissions::{AskGate, PermissionGate, PermissionMode, Request, Response};
use provider::{GlmProvider, Message, Provider, Role};
use run::{run_agent, AgentEvent, AgentOptions, Asker};

/// How to seed the loop from the session store.
#[derive(Debug, Clone)]
pub enum Resume {
    /// Most recent session in the project (`--continue`).
    Continue,
    /// Explicit session id (`--resume <id>`).
    Id(String),
    /// Explicit session id, branched under a fresh id (`--fork <id>`).
    Fork(String),
}

/// CLI-specific knobs parsed out of argv.
#[derive(Debug, Default)]
pub struct CliOptions {
    pub resume: Option<Resume>,
    /// Auto-approve every permission prompt (`--yes`, for CI).
    pub auto_yes: bool,
    /// Hard read-only policy (`--read-only`): mutations are denied outright.
    pub read_only: bool,
    /// Explicit permission mode (`--permission-mode`, `--plan`). Overrides
    /// `read_only` when set.
    pub permission_mode: Option<PermissionMode>,
    /// Disable shadow-git checkpoints (`--no-checkpoints`).
    pub no_checkpoints: bool,
}

/// Options for `xencode exec` — the headless/CI entry (Phase 4).
#[derive(Debug, Default)]
pub struct ExecOptions {
    pub cli: CliOptions,
    /// Emit a JSON envelope on stdout instead of the plain answer.
    pub json: bool,
    /// Cap the loop (`--max-turns`); default 25.
    pub max_turns: Option<usize>,
    /// Don't persist the session (`--no-session`).
    pub no_session: bool,
}

/// Discover the project's `[hooks]` from its `xen.toml`. `None` when the
/// project has no hooks configured (the machinery is skipped entirely).
pub fn hooks_for_project(cwd: &Path) -> Option<Arc<xencode_script::HookRunner>> {
    let (cfg, cfg_path) = xencode_script::XenConfig::discover(cwd).ok()?;
    if cfg.hooks.is_empty() {
        return None;
    }
    Some(Arc::new(xencode_script::HookRunner::from_config(
        &cfg_path,
        &cfg.hooks,
    )))
}

/// Terminal permission prompter: y = once, a = always (this session), n = no.
struct StdinAsker {
    auto_yes: bool,
}

impl Asker for StdinAsker {
    fn ask(&mut self, req: &Request) -> Response {
        if self.auto_yes {
            return Response::Allow;
        }
        use std::io::Write;
        eprint!(
            "\nxencode · permission: {} `{}` — [y]es [n]o [a]lways? ",
            req.tool, req.detail
        );
        let _ = std::io::stderr().flush();
        let mut line = String::new();
        if std::io::stdin().read_line(&mut line).unwrap_or(0) == 0 {
            return Response::Deny; // EOF — fail closed
        }
        match line.trim() {
            "y" | "yes" | "" => Response::Allow,
            "a" | "always" => Response::AllowAlways,
            _ => Response::Deny,
        }
    }
}

/// Connect the project's MCP servers (`.xencode/mcp.json` + user scope).
/// `None` when nothing is configured or every server failed — never fatal.
pub async fn connect_mcp(cwd: &Path) -> Option<Arc<mcp::McpManager>> {
    let cfgs = mcp::discover(cwd);
    if cfgs.is_empty() {
        return None;
    }
    let mgr = mcp::McpManager::connect(cfgs).await;
    if mgr.is_empty() {
        None
    } else {
        Some(Arc::new(mgr))
    }
}

/// Seed history from the session store for `--continue` / `--resume` / `--fork`.
fn seed_history(session_dir: &Path, resume: &Option<Resume>) -> Result<Vec<Message>> {
    let Some(kind) = resume else { return Ok(Vec::new()) };
    let id = match kind {
        Resume::Continue => session::Session::latest(session_dir)
            .context("read session store")?
            .context("no sessions in this project yet — run without --continue first")?,
        Resume::Id(id) | Resume::Fork(id) => id.clone(),
    };
    let s = session::Session::load(session_dir, &id)
        .with_context(|| format!("load session {id}"))?;
    if let Resume::Fork(_) = kind {
        let forked = s.fork();
        eprintln!("xencode · forked session {} → {}", id, forked.id);
        Ok(forked.messages)
    } else {
        eprintln!("xencode · resuming session {id}");
        Ok(s.messages)
    }
}

/// Entry for `xencode --agent "prompt"` — headless, streaming to stdout.
/// Phase 3 adds: slash-command expansion, memory files (auto-loaded in the
/// system prompt), QuickJS hooks from `xen.toml`, and plan mode with an
/// interactive approve-then-implement flow. Phase 4 adds: subagents, MCP
/// tools and shadow-git checkpoints by default (opt out with
/// `--no-checkpoints`); permission behavior is unchanged.
pub fn run_cli(prompt: &str, cli: CliOptions) -> Result<()> {
    let cwd = std::env::current_dir().context("resolve working directory")?;
    let session_dir = cwd.join(".xencode").join("sessions");

    // Slash commands: `/name args` expands from .xencode/commands/*.md;
    // unknown or non-command prompts pass through unchanged.
    let prompt = match commands::expand(&cwd, prompt) {
        Some(expanded) => {
            eprintln!("xencode · slash command expanded ({} chars)", expanded.len());
            expanded
        }
        None => prompt.to_string(),
    };

    let provider: Arc<dyn Provider> = Arc::new(GlmProvider::from_env()?);

    // Seed history from the session store when resuming / forking.
    let history = seed_history(&session_dir, &cli.resume)?;

    // Permission mode: explicit flag > --read-only > interactive default.
    let interactive = std::io::stdin().is_terminal();
    let mode = if cli.read_only {
        PermissionMode::ReadOnly
    } else {
        cli.permission_mode.unwrap_or_default()
    };
    let asker = Box::new(StdinAsker { auto_yes: cli.auto_yes || !interactive });
    let (gate, asker): (Box<dyn PermissionGate>, Option<Box<dyn Asker>>) = match mode {
        // Never-ask modes: the gate hard-decides everything.
        PermissionMode::ReadOnly | PermissionMode::Plan | PermissionMode::Yolo => {
            (mode.gate(), None)
        }
        // Interactive-capable modes ask (auto-yes in CI).
        PermissionMode::Default | PermissionMode::AcceptEdits => (mode.gate(), Some(asker)),
    };

    let hooks = hooks_for_project(&cwd);
    if let Some(h) = &hooks {
        eprintln!(
            "xencode · hooks active: {} pre-tool, {} post-tool, {} stop",
            h.count(xencode_script::HookKind::PreToolUse),
            h.count(xencode_script::HookKind::PostToolUse),
            h.count(xencode_script::HookKind::Stop),
        );
    }
    if mode == PermissionMode::Plan {
        eprintln!("xencode · plan mode: mutations blocked until the plan is approved");
    }

    let rt = tokio::runtime::Runtime::new().context("start tokio runtime")?;
    rt.block_on(async move {
        // --- Phase 4 machinery: MCP servers + shadow-git checkpoints -------
        let mcp = connect_mcp(&cwd).await;
        let checkpoints = if cli.no_checkpoints {
            None
        } else {
            Some(checkpoint::CheckpointStore::open(&cwd))
        };

        // --- run 1: research / plan ---
        let (tx, rx) = mpsc::unbounded_channel::<AgentEvent>();
        let printer = spawn_cli_printer(rx);
        let opts = AgentOptions {
            cwd: cwd.clone(),
            session_dir: Some(session_dir.clone()),
            history: history.clone(),
            gate,
            asker,
            hooks: hooks.clone(),
            plan_mode: mode == PermissionMode::Plan,
            mcp: mcp.clone(),
            checkpoints: checkpoints.clone(),
            ..Default::default()
        };
        let answer = run_agent(provider.clone(), &prompt, opts, tx).await?;
        let transcript = printer
            .await
            .unwrap_or_default()
            .unwrap_or_else(|| Arc::new(Vec::new()));
        eprintln!();

        // --- plan mode: approval gate, then implementation run ---
        if mode == PermissionMode::Plan {
            if !interactive {
                // CI/headless plan runs print the plan and stop — nothing
                // was approved, nothing was changed.
                return Ok(());
            }
            use std::io::Write;
            eprint!("\nxencode · proceed with this plan? [y]es [n]o: ");
            let _ = std::io::stderr().flush();
            let mut line = String::new();
            if std::io::stdin().read_line(&mut line).unwrap_or(0) == 0 {
                return Ok(()); // EOF — treat as “not approved”
            }
            match line.trim() {
                "y" | "yes" | "" => {}
                _ => {
                    eprintln!("xencode · plan not approved — no changes were made");
                    return Ok(());
                }
            }
            eprintln!("xencode · plan approved — implementing");

            let (tx2, rx2) = mpsc::unbounded_channel::<AgentEvent>();
            let printer2 = spawn_cli_printer(rx2);
            // Drop the plan-mode system prompt: the execution run re-seeds a
            // fresh one (with memory) and keeps the whole research thread.
            let exec_history: Vec<Message> = transcript
                .iter()
                .skip_while(|m| m.role == Role::System)
                .cloned()
                .collect();
            let opts2 = AgentOptions {
                cwd,
                session_dir: Some(session_dir),
                history: exec_history,
                gate: Box::new(AskGate::new()),
                asker: Some(Box::new(StdinAsker { auto_yes: cli.auto_yes })),
                hooks,
                plan_mode: false,
                mcp,
                checkpoints,
                ..Default::default()
            };
            let follow_up = "The user reviewed and APPROVED the plan above. Implement it now, \
                             step by step, starting with the first step.";
            let _ = run_agent(provider.clone(), follow_up, opts2, tx2).await?;
            let _ = printer2.await;
        } else {
            let _ = answer;
        }
        Ok(())
    })
}

/// Entry for `xencode exec "prompt"` — the Phase-4 headless/CI contract:
/// never interactive (approvals auto-resolve), answers to stdout (plain or
/// `--json`), session resume/fork/continue reuse, `--max-turns` cap, clean
/// exit codes. A failing run is a non-zero exit; nothing else is silent.
pub fn run_exec(prompt: &str, ex: ExecOptions) -> Result<()> {
    let cwd = std::env::current_dir().context("resolve working directory")?;
    let session_dir = cwd.join(".xencode").join("sessions");

    let prompt = match commands::expand(&cwd, prompt) {
        Some(expanded) => expanded,
        None => prompt.to_string(),
    };
    let provider: Arc<dyn Provider> = Arc::new(GlmProvider::from_env()?);
    let history = seed_history(&session_dir, &ex.cli.resume)?;

    // CI defaults to allow-everything (no human to ask); explicit modes win.
    let mode = if ex.cli.read_only {
        PermissionMode::ReadOnly
    } else {
        ex.cli.permission_mode.unwrap_or(PermissionMode::Yolo)
    };

    let hooks = hooks_for_project(&cwd);
    let rt = tokio::runtime::Runtime::new().context("start tokio runtime")?;
    rt.block_on(async move {
        let mcp = connect_mcp(&cwd).await;
        let checkpoints = if ex.cli.no_checkpoints {
            None
        } else {
            Some(checkpoint::CheckpointStore::open(&cwd))
        };
        let (tx, mut rx) = mpsc::unbounded_channel::<AgentEvent>();
        let counter = tokio::spawn(async move {
            let mut last_iteration = 0usize;
            while let Some(ev) = rx.recv().await {
                if let AgentEvent::Iteration(n) = ev {
                    last_iteration = n;
                }
            }
            last_iteration
        });
        let opts = AgentOptions {
            cwd,
            session_dir: if ex.no_session { None } else { Some(session_dir.clone()) },
            history,
            gate: mode.gate(),
            asker: None, // non-interactive: the Yolo/ReadOnly gate never asks
            hooks,
            max_iterations: ex.max_turns.unwrap_or(25),
            plan_mode: mode == PermissionMode::Plan,
            mcp,
            checkpoints,
            ..Default::default()
        };
        let answer = run_agent(provider, &prompt, opts, tx).await?;
        let iterations = counter.await.unwrap_or(0);
        let session_id = if ex.no_session {
            String::new()
        } else {
            session::Session::latest(&session_dir).ok().flatten().unwrap_or_default()
        };
        if ex.json {
            println!(
                "{}",
                serde_json::json!({
                    "answer": answer,
                    "session_id": session_id,
                    "iterations": iterations,
                })
            );
        } else {
            println!("{answer}");
        }
        Ok(())
    })
}

/// CLI event printer — streams loop progress to stderr/stdout and captures
/// the final transcript (plan mode needs it for the approval hand-off).
fn spawn_cli_printer(
    mut rx: mpsc::UnboundedReceiver<AgentEvent>,
) -> tokio::task::JoinHandle<Option<Arc<Vec<Message>>>> {
    tokio::spawn(async move {
        let mut transcript: Option<Arc<Vec<Message>>> = None;
        while let Some(ev) = rx.recv().await {
            match ev {
                AgentEvent::Iteration(n) => eprintln!("xencode · iteration {n}"),
                AgentEvent::Text(s) => {
                    use std::io::Write;
                    print!("{s}");
                    let _ = std::io::stdout().flush();
                }
                AgentEvent::ToolStart { name, summary } => {
                    eprintln!("\n· {name} {summary}");
                }
                AgentEvent::ToolEnd { name, ok, ms } => {
                    eprintln!("  {name} {} ({ms}ms)", if ok { "ok" } else { "failed" });
                }
                AgentEvent::Ask { allowed, .. } => {
                    if !allowed {
                        eprintln!("  permission denied by user");
                    }
                }
                AgentEvent::HookRun { kind, script, ok } => {
                    eprintln!(
                        "  hook {kind} {} {}",
                        script,
                        if ok { "ok" } else { "failed" }
                    );
                }
                AgentEvent::HookLog(s) => eprintln!("{s}"),
                AgentEvent::Compacted { before, after } => {
                    eprintln!("xencode · compacted context {before} → {after} chars");
                }
                AgentEvent::Checkpoint { tool, detail } => {
                    eprintln!("  checkpoint · before {tool} {detail}");
                }
                AgentEvent::Subagent { name, event } => print_subagent_event(&name, &event),
                AgentEvent::Transcript(t) => transcript = Some(t),
                AgentEvent::Done => {}
            }
        }
        transcript
    })
}

/// Nested printer for child-loop events (Phase 4 subagents): child activity
/// is indented under `[agent]` labels; child prose stays out of the console
/// (the final report lands in the tool result the model sees).
fn print_subagent_event(name: &str, ev: &AgentEvent) {
    match ev {
        AgentEvent::ToolStart { name: tool, summary } => {
            eprintln!("  [{name}] · {tool} {summary}");
        }
        AgentEvent::ToolEnd { name: tool, ok, .. } => {
            eprintln!("  [{name}]   {tool} {}", if *ok { "ok" } else { "failed" });
        }
        AgentEvent::Subagent { name: inner, event } => print_subagent_event(&format!("{name}>{inner}"), event),
        _ => {}
    }
}
