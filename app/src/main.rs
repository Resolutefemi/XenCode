//! XenCode — 100% Rust native GUI + agent core.
//!
//! Render mode:     `xencode --screenshot --out DIR` draws every primary page
//!                  through the same renderer the live window uses (tiny-skia
//!                  software rasterizer) and writes PNGs — visual ground truth.
//! Agent mode:      `xencode --agent "prompt"` runs the Phase-1 agent core
//!                  headlessly against the GLM API (XENCODE_API_KEY).
//! Agent-UI mode:   `xencode --agent-ui "prompt" --out FILE.png` runs the
//!                  agent and renders the live conversation (tool rows,
//!                  permission consent cards, streamed answer) into the chat
//!                  pane — the composer → loop wiring made visible.
//!                  `XENCODE_DEMO=1` substitutes a scripted provider so the
//!                  wiring renders without an API key.
//! Transcript mode: `xencode --render-session <id> --out FILE.png` renders a
//!                  saved session from `.xencode/sessions/`.
//! Commands mode:   `xencode --commands` lists the markdown slash commands
//!                  found in `.xencode/commands/` (project) and
//!                  `~/.xencode/commands/` (user).
//! Plan mode:       `xencode --plan "task"` (or `--permission-mode plan`)
//!                  researches read-only, prints the plan, and — in a TTY —
//!                  asks for approval before implementing.

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
use agent::{commands, permissions, CliOptions, Resume};
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
    };

    if let Err(e) = result {
        eprintln!("xencode: {e:#}");
        std::process::exit(1);
    }
}

fn parse_args() -> Result<Mode> {
    let args: Vec<String> = std::env::args().skip(1).collect();
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
async fn collect_run<P: agent::provider::Provider>(
    provider: P,
    prompt: &str,
    opts: AgentOptions,
    collector: &mut Collector,
) -> Result<String> {
    let (tx, mut rx) = tokio::sync::mpsc::unbounded_channel::<AgentEvent>();
    let answer = run_agent(&provider, prompt, opts, tx).await;
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
    let provider = agent::provider::GlmProvider::from_env()?;
    let mut collector = Collector::new(prompt, "Agent run");
    let cwd = std::env::current_dir().unwrap_or_default();
    let opts = AgentOptions {
        cwd,
        session_dir: Some(session_dir.to_path_buf()),
        gate: Box::new(permissions::AskGate::new()),
        asker: Some(Box::new(AutoAsker)),
        hooks: agent::hooks_for_project(&std::env::current_dir().unwrap_or_default()),
        ..Default::default()
    };
    collect_run(provider, prompt, opts, &mut collector).await?;
    let id = Session::latest(session_dir).ok().flatten().unwrap_or_default();
    Ok((collector.page, id))
}

/// Scripted run for `XENCODE_DEMO=1` — a tiny fake project, a read, a consent
/// prompt (allowed), a build, and the final answer.
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

    let provider = MockProvider::new(vec![
        Message {
            role: Role::Assistant,
            content: "Let me look at the entrypoint first.".into(),
            tool_calls: vec![ToolCall {
                id: "t1".into(),
                name: "read_file".into(),
                arguments: json!({"path": "src/main.rs"}),
            }],
            tool_call_id: None,
        },
        Message {
            role: Role::Assistant,
            content: "The entrypoint is a bare hello-world — it builds as-is, so let me verify with a release build.".into(),
            tool_calls: vec![ToolCall {
                id: "t2".into(),
                name: "bash".into(),
                arguments: json!({"command": "cargo build --release"}),
            }],
            tool_call_id: None,
        },
        Message {
            role: Role::Assistant,
            content: "Build is green. Nothing to change — the demo project compiles clean and the run is done.".into(),
            tool_calls: vec![],
            tool_call_id: None,
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
        ..Default::default()
    };

    collect_run(provider, prompt, opts, &mut collector).await?;
    let id = Session::latest(session_dir).ok().flatten().unwrap_or_default();
    let _ = std::fs::remove_dir_all(&dir);
    Ok((collector.page, id))
}
