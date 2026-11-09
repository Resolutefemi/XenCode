//! XenCode agent core — provider clients, tools, the loop, sessions, gates.
//! Phase 1 of plan.md. The GUI (composer, panes) drives this through the
//! same public API the CLI uses below.

pub mod compact;
pub mod permissions;
pub mod provider;
pub mod run;
pub mod session;
pub mod tools;

use anyhow::{Context, Result};
use std::io::IsTerminal;
use tokio::sync::mpsc;

use permissions::{AskGate, PermissionGate, ReadOnly, Request, Response};
use provider::GlmProvider;
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

/// Entry for `xencode --agent "prompt"` — headless, streaming to stdout.
pub fn run_cli(prompt: &str, cli: CliOptions) -> Result<()> {
    let provider = GlmProvider::from_env()?;
    let cwd = std::env::current_dir().context("resolve working directory")?;
    let session_dir = cwd.join(".xencode").join("sessions");

    // Seed history from the session store when resuming / forking.
    let mut history = Vec::new();
    if let Some(kind) = &cli.resume {
        let id = match kind {
            Resume::Continue => session::Session::latest(&session_dir)
                .context("read session store")?
                .context("no sessions in this project yet — run without --continue first")?,
            Resume::Id(id) | Resume::Fork(id) => id.clone(),
        };
        let s = session::Session::load(&session_dir, &id)
            .with_context(|| format!("load session {id}"))?;
        if let Resume::Fork(_) = kind {
            let forked = s.fork();
            eprintln!("xencode · forked session {} → {}", id, forked.id);
            history = forked.messages;
        } else {
            eprintln!("xencode · resuming session {id}");
            history = s.messages;
        }
    }

    // Gate selection: read-only flag → ReadOnly; interactive TTY → AskGate
    // with the stdin prompter; non-interactive → AllowAll (CI default).
    let interactive = std::io::stdin().is_terminal();
    let (gate, asker): (Box<dyn PermissionGate>, Option<Box<dyn Asker>>) = if cli.read_only {
        (Box::new(ReadOnly), None)
    } else if interactive {
        (
            Box::new(AskGate::new()),
            Some(Box::new(StdinAsker { auto_yes: cli.auto_yes })),
        )
    } else {
        (Box::new(AskGate::new()), Some(Box::new(StdinAsker { auto_yes: true })))
    };

    let rt = tokio::runtime::Runtime::new().context("start tokio runtime")?;
    rt.block_on(async move {
        let (tx, mut rx) = mpsc::unbounded_channel::<AgentEvent>();
        let printer = tokio::spawn(async move {
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
                    AgentEvent::Compacted { before, after } => {
                        eprintln!("xencode · compacted context {before} → {after} chars");
                    }
                    AgentEvent::Transcript(_) | AgentEvent::Done => {}
                }
            }
        });

        let opts = AgentOptions {
            cwd,
            session_dir: Some(session_dir),
            history,
            gate,
            asker,
            ..Default::default()
        };
        let answer = run_agent(&provider, prompt, opts, tx).await;
        let _ = printer.await;
        eprintln!();
        answer?;
        Ok(())
    })
}
