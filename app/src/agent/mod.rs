//! XenCode agent core — provider clients, tools, the loop, sessions, gates.
//! Phase 1 of plan.md. The GUI (composer, panes) drives this through the
//! same public API the CLI uses below.
//! Phase 5 adds: repo map (system prompt), diagnostics loop, recipes, the
//! oracle tool and run telemetry.

pub mod checkpoint;
pub mod commands;
pub mod compact;
pub mod diagnostics;
pub mod mcp;
pub mod memory;
pub mod oracle;
pub mod permissions;
pub mod provider;
pub mod recipes;
pub mod repomap;
pub mod run;
pub mod session;
pub mod subagent;
pub mod telemetry;
pub mod tools;

use anyhow::{Context, Result};
use std::io::IsTerminal;
use std::path::Path;
use std::sync::Arc;
use tokio::sync::mpsc;

use permissions::{PermissionGate, PermissionMode, Request, Response};
use provider::{GlmProvider, Message, Provider, Role};
use run::{run_agent, AgentEvent, AgentOptions, Asker};

/// Default repo-map budget in characters (~1k tokens) for agent runs.
pub(crate) const DEFAULT_REPO_MAP_BUDGET: usize = 4_096;

/// Everything the project's `xen.toml` configures for agent runs — built
/// once per CLI entry and shared by the loop (and recipe steps).
pub(crate) struct Harness {
    pub hooks: Option<Arc<xencode_script::HookRunner>>,
    pub diagnostics: Option<Arc<diagnostics::DiagnosticsEngine>>,
    pub telemetry_cfg: xencode_script::config::TelemetryConfig,
    pub oracle_model: Option<String>,
}

/// Discover the project's Phase-3/5 machinery from its `xen.toml`.
pub(crate) fn harness_for_project(cwd: &Path) -> Harness {
    let (cfg, cfg_path) = xencode_script::XenConfig::discover(cwd).unwrap_or_default();
    let hooks = if cfg.hooks.is_empty() {
        None
    } else {
        Some(Arc::new(xencode_script::HookRunner::from_config(
            &cfg_path,
            &cfg.hooks,
        )))
    };
    let diagnostics = diagnostics::DiagnosticsEngine::resolve(cwd, &cfg.diagnostics)
        .map(Arc::new);
    Harness {
        hooks,
        diagnostics,
        telemetry_cfg: cfg.telemetry,
        oracle_model: cfg.oracle.model,
    }
}

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
    /// Disable the repo-map system-prompt section (`--no-repo-map`).
    pub no_repo_map: bool,
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

/// The interactive asker for a run: stdin y/n/a prompts unless `auto_yes`
/// (CI) or the session is not a terminal.
pub(crate) fn interactive_asker(auto_yes: bool, interactive: bool) -> Option<Box<dyn Asker>> {
    if auto_yes || !interactive {
        None
    } else {
        Some(Box::new(StdinAsker))
    }
}

/// Terminal permission prompter: y = once, a = always (this session), n = no.
struct StdinAsker;

impl Asker for StdinAsker {
    fn ask(&mut self, req: &Request) -> Response {
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
/// `--no-checkpoints`); permission behavior is unchanged. Phase 5 adds:
/// repo map in the system prompt, diagnostics loop, the oracle tool and run
/// telemetry (all opt-out via `--no-repo-map` / config / disabled flags).
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
    let (gate, asker): (Box<dyn PermissionGate>, Option<Box<dyn Asker>>) = match mode {
        // Never-ask modes: the gate hard-decides everything.
        PermissionMode::ReadOnly | PermissionMode::Plan | PermissionMode::Yolo => {
            (mode.gate(), None)
        }
        // Interactive-capable modes ask (auto-yes in CI).
        PermissionMode::Default | PermissionMode::AcceptEdits => {
            (mode.gate(), interactive_asker(cli.auto_yes, interactive))
        }
    };

    let harness = harness_for_project(&cwd);
    if let Some(h) = &harness.hooks {
        eprintln!(
            "xencode · hooks active: {} pre-tool, {} post-tool, {} stop",
            h.count(xencode_script::HookKind::PreToolUse),
            h.count(xencode_script::HookKind::PostToolUse),
            h.count(xencode_script::HookKind::Stop),
        );
    }
    if let Some(d) = &harness.diagnostics {
        eprintln!("xencode · diagnostics loop active (`{}`)", d.source());
    }
    if mode == PermissionMode::Plan {
        eprintln!("xencode · plan mode: mutations blocked until the plan is approved");
    }

    let rt = tokio::runtime::Runtime::new().context("start tokio runtime")?;
    rt.block_on(async move {
        // --- Phase 4/5 machinery: MCP + checkpoints + oracle ----------------
        let mcp = connect_mcp(&cwd).await;
        let checkpoints = if cli.no_checkpoints {
            None
        } else {
            Some(checkpoint::CheckpointStore::open(&cwd))
        };
        let telemetry = telemetry::Telemetry::for_run(&cwd, &harness.telemetry_cfg);
        let oracle_setup = recipes::build_oracle(&harness, &provider)?;
        let repo_map = if cli.no_repo_map { None } else { Some(DEFAULT_REPO_MAP_BUDGET) };
        let common = CommonOpts {
            hooks: harness.hooks.clone(),
            mcp: mcp.clone(),
            checkpoints: checkpoints.clone(),
            telemetry: telemetry.clone(),
            oracle: oracle_setup.clone(),
            repo_map,
            diagnostics: harness.diagnostics.clone(),
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
            plan_mode: mode == PermissionMode::Plan,
            ..common.split()
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
                cwd: cwd.clone(),
                session_dir: Some(session_dir),
                history: exec_history,
                gate: Box::new(permissions::AskGate::new()),
                asker: interactive_asker(cli.auto_yes, true),
                plan_mode: false,
                ..common.split()
            };
            let follow_up = "The user reviewed and APPROVED the plan above. Implement it now, \
                             step by step, starting with the first step.";
            let _ = run_agent(provider.clone(), follow_up, opts2, tx2).await?;
            let _ = printer2.await;
        } else {
            let _ = answer;
        }

        // Phase 5: close the telemetry file / OTLP export after the run(s).
        if let Some(t) = &telemetry {
            eprintln!("{}", t.finish(0).await);
        }
        Ok(())
    })
}

/// Shared AgentOptions fields for a project run (built once per CLI entry,
/// split into each run/step's options).
struct CommonOpts {
    hooks: Option<Arc<xencode_script::HookRunner>>,
    mcp: Option<Arc<mcp::McpManager>>,
    checkpoints: Option<Arc<checkpoint::CheckpointStore>>,
    telemetry: Option<Arc<telemetry::Telemetry>>,
    oracle: Option<Arc<oracle::OracleSetup>>,
    repo_map: Option<usize>,
    diagnostics: Option<Arc<diagnostics::DiagnosticsEngine>>,
}

impl CommonOpts {
    fn split(&self) -> AgentOptions {
        AgentOptions {
            hooks: self.hooks.clone(),
            mcp: self.mcp.clone(),
            checkpoints: self.checkpoints.clone(),
            telemetry: self.telemetry.clone(),
            oracle: self.oracle.clone(),
            repo_map: self.repo_map,
            diagnostics: self.diagnostics.clone(),
            ..Default::default()
        }
    }
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

    let harness = harness_for_project(&cwd);
    let rt = tokio::runtime::Runtime::new().context("start tokio runtime")?;
    rt.block_on(async move {
        let mcp = connect_mcp(&cwd).await;
        let checkpoints = if ex.cli.no_checkpoints {
            None
        } else {
            Some(checkpoint::CheckpointStore::open(&cwd))
        };
        let telemetry = telemetry::Telemetry::for_run(&cwd, &harness.telemetry_cfg);
        let oracle_setup = recipes::build_oracle(&harness, &provider)?;
        let common = CommonOpts {
            hooks: harness.hooks.clone(),
            mcp,
            checkpoints,
            telemetry: telemetry.clone(),
            oracle: oracle_setup,
            repo_map: if ex.cli.no_repo_map { None } else { Some(DEFAULT_REPO_MAP_BUDGET) },
            diagnostics: harness.diagnostics.clone(),
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
            cwd: cwd.clone(),
            session_dir: if ex.no_session { None } else { Some(session_dir.clone()) },
            history,
            gate: mode.gate(),
            asker: None, // non-interactive: the Yolo/ReadOnly gate never asks
            max_iterations: ex.max_turns.unwrap_or(25),
            plan_mode: mode == PermissionMode::Plan,
            ..common.split()
        };
        let answer = run_agent(provider, &prompt, opts, tx).await?;
        let iterations = counter.await.unwrap_or(0);
        if let Some(t) = &telemetry {
            eprintln!("{}", t.finish(answer.chars().count()).await);
        }
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

/// Entry for `xencode --oracle "question"` — a standalone second opinion
/// (Phase 5). Seeds context from the most recent session when one exists,
/// runs the oracle on its configured model, streams the verdict to stdout.
pub fn run_oracle(question: &str) -> Result<()> {
    let cwd = std::env::current_dir().context("resolve working directory")?;
    let harness = harness_for_project(&cwd);
    let main_provider: Arc<dyn Provider> = Arc::new(GlmProvider::from_env()?);
    let model = oracle::model_name(harness.oracle_model.as_deref(), main_provider.model());
    let provider: Arc<dyn Provider> = if model == main_provider.model() {
        main_provider.clone()
    } else {
        eprintln!("xencode · oracle model: {model} (main: {})", main_provider.model());
        Arc::new(GlmProvider::from_env_with_model(&model)?)
    };
    let setup = Arc::new(oracle::OracleSetup { provider, model });

    // Context: the tail of the most recent session, when one exists.
    let session_dir = cwd.join(".xencode").join("sessions");
    let context = session::Session::latest(&session_dir)
        .ok()
        .flatten()
        .and_then(|id| session::Session::load(&session_dir, &id).ok())
        .map(|s| {
            // Last 6 non-system messages, oldest first.
            let recent: Vec<&Message> = s
                .messages
                .iter()
                .filter(|m| !matches!(m.role, Role::System))
                .rev()
                .take(6)
                .collect();
            recent
                .into_iter()
                .rev()
                .map(|m| {
                    let head: String = m.content.chars().take(600).collect();
                    format!("- {:?}: {head}", m.role)
                })
                .collect::<Vec<_>>()
                .join("\n")
        })
        .filter(|c| !c.trim().is_empty());
    if context.is_some() {
        eprintln!("xencode · oracle context: recent session transcript");
    }

    let rt = tokio::runtime::Runtime::new().context("start tokio runtime")?;
    rt.block_on(async move {
        let (tx, mut rx) = mpsc::unbounded_channel::<AgentEvent>();
        let printer = tokio::spawn(async move {
            use std::io::Write;
            while let Some(ev) = rx.recv().await {
                match ev {
                    AgentEvent::Subagent { event, .. } => match *event {
                        AgentEvent::Text(s) => {
                            print!("{s}");
                            let _ = std::io::stdout().flush();
                        }
                        AgentEvent::ToolStart { name, summary } => {
                            eprintln!("\n· [{name}] {summary}");
                        }
                        AgentEvent::ToolEnd { name, ok, .. } => {
                            eprintln!("  [{name}] {}", if ok { "ok" } else { "failed" });
                        }
                        _ => {}
                    },
                    _ => {}
                }
            }
        });
        let args = serde_json::json!({ "question": question, "context": context.unwrap_or_default() });
        let verdict: String = oracle::consult(&setup, &args, &cwd, tx).await?;
        let _ = printer.await;
        println!();
        Ok::<String, anyhow::Error>(verdict)
    })?;
    Ok(())
}

/// `xencode --recipes` — list every recipe visible to the project.
pub fn list_recipes() -> Result<()> {
    let cwd = std::env::current_dir().context("resolve working directory")?;
    let defs = recipes::list(&cwd);
    if defs.is_empty() {
        println!("no recipes found");
        println!(
            "add markdown files to .xencode/recipes/ (project) or ~/.xencode/recipes/ (user)"
        );
        return Ok(());
    }
    println!("available recipes:");
    for d in &defs {
        let desc = if d.description.is_empty() {
            String::new()
        } else {
            format!(" — {}", d.description)
        };
        let params = d
            .params
            .iter()
            .map(|p| p.name.as_str())
            .collect::<Vec<_>>()
            .join(", ");
        println!(
            "  {:<18} [{}] {} step(s){}{}",
            d.name,
            d.scope,
            d.steps.len(),
            if params.is_empty() { String::new() } else { format!(" · params: {params}") },
            desc
        );
    }
    println!("run one with: xencode recipe <name> [key=value ...]");
    Ok(())
}

/// CLI event printer — streams loop progress to stderr/stdout and captures
/// the final transcript (plan mode needs it for the approval hand-off).
pub(crate) fn spawn_cli_printer(
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
                AgentEvent::Diagnostics { source, count, detail } => {
                    eprintln!(
                        "  diagnostics · {source}: {count} new problem(s) fed back"
                    );
                    let _ = count;
                    let head = detail.lines().skip(1).take(4).collect::<Vec<_>>().join("\n  ");
                    if !head.is_empty() {
                        eprintln!("  {head}");
                    }
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
