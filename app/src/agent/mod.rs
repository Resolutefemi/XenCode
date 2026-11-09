//! XenCode agent core — provider clients, tools, the loop, sessions, gates.
//! Phase 1 of plan.md. The GUI (composer, panes) will drive this through the
//! same public API the CLI uses below.

pub mod permissions;
pub mod provider;
pub mod run;
pub mod session;
pub mod tools;

use anyhow::{Context, Result};
use tokio::sync::mpsc;

use provider::GlmProvider;
use run::{run_agent, AgentEvent, AgentOptions};

/// Entry for `xencode --agent "prompt"` — headless, streaming to stdout.
pub fn run_cli(prompt: &str) -> Result<()> {
    let provider = GlmProvider::from_env()?;
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
                    AgentEvent::Done => eprintln!(),
                }
            }
        });

        let opts = AgentOptions {
            session_dir: Some(
                std::env::current_dir()?.join(".xencode").join("sessions"),
            ),
            ..Default::default()
        };
        let answer = run_agent(&provider, prompt, opts, tx).await;
        let _ = printer.await;
        eprintln!();
        answer?;
        Ok(())
    })
}
