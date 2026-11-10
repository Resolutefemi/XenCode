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
//! API discovery:   `xencode api capture|show|openapi|copy|diff|monitor …`
//!                  (Phase 8) records every XHR/fetch a page makes into an
//!                  endpoint catalog — copy-as cURL/fetch/reqwest/QuickJS,
//!                  OpenAPI export, and a baseline monitor for automation.

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
/// Post-load settle window for `xencode api capture` (ms).
const API_SETTLE_MS: u64 = 1_500;

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
    // Phase 6 -------------------------------------------------------------
    /// `xencode browser <url> ...` — headless, CLI-drivable Spaces.
    Browser { url: String, opts: BrowserOptions },
    /// `xencode --browser-ui <url>` — the native browser tab with a live
    /// screencast frame composited into the renderer.
    BrowserUi { url: String, out: String },
    // Phase 8 -------------------------------------------------------------
    /// `xencode api capture <url>` — record XHR/fetch traffic.
    ApiCapture { url: String, opts: ApiCaptureOptions },
    /// `xencode api show <capture.json>` — summarize the endpoint catalog.
    ApiShow { file: String, json: bool },
    /// `xencode api openapi <capture.json>` — OpenAPI 3.1 export.
    ApiOpenapi { file: String, out: Option<String>, title: Option<String> },
    /// `xencode api copy <capture.json> <N> [--as …]` — replay snippet.
    ApiCopy { file: String, index: usize, as_flavor: ApiCopyAs },
    /// `xencode api diff <old.json> <new.json>` — monitor report.
    ApiDiff { old: String, new: String, json: bool },
    /// `xencode api monitor <url> --baseline FILE` — capture → diff → alert.
    ApiMonitor { url: String, baseline: String, update: bool, json: bool, opts: ApiCaptureOptions },
}

/// `xencode api copy --as` flavor.
#[derive(Debug, Clone, Copy, Default)]
enum ApiCopyAs {
    #[default]
    Curl,
    Fetch,
    Reqwest,
    QuickJS,
}

impl ApiCopyAs {
    fn parse(s: &str) -> Result<ApiCopyAs> {
        match s.to_ascii_lowercase().as_str() {
            "curl" => Ok(ApiCopyAs::Curl),
            "fetch" | "js" => Ok(ApiCopyAs::Fetch),
            "reqwest" | "rust" => Ok(ApiCopyAs::Reqwest),
            "quickjs" | "xen" => Ok(ApiCopyAs::QuickJS),
            other => bail!(
                "unknown copy-as flavor `{other}` — expected one of: {}",
                xencode_browser::apidisco::CopyAs::all().join("|")
            ),
        }
    }

    fn to_lib(self) -> xencode_browser::CopyAs {
        match self {
            ApiCopyAs::Curl => xencode_browser::CopyAs::Curl,
            ApiCopyAs::Fetch => xencode_browser::CopyAs::Fetch,
            ApiCopyAs::Reqwest => xencode_browser::CopyAs::Reqwest,
            ApiCopyAs::QuickJS => xencode_browser::CopyAs::QuickJS,
        }
    }
}

/// Flags shared by `api capture` and `api monitor`.
#[derive(Debug, Default, Clone)]
struct ApiCaptureOptions {
    /// Interaction steps to run after navigation (drive the app so XHRs fire;
    /// a trailing `--wait` doubles as the settle time).
    actions: Vec<BrowserAction>,
    /// Disable response-body capture (shapes need bodies; escape hatch).
    no_bodies: bool,
    /// Keep secret header/query values verbatim (default: redacted).
    no_redact: bool,
    /// Write the raw capture artifact here.
    out: Option<String>,
    /// Also export the generated OpenAPI document here.
    openapi: Option<String>,
    json: bool,
    browser_path: Option<String>,
    /// `clean` (default) or `inherit=<profile-path>`.
    space: String,
    timeout_ms: u64,
    headless: bool,
}

/// One headless browser step (`browser` subcommand).
#[derive(Debug, Clone)]
enum BrowserAction {
    Click(String),
    Fill(String, String),
    Press(String),
    Scroll(f64, f64),
    Wait(u64),
}

#[derive(Debug, Default, Clone)]
struct BrowserOptions {
    headless: bool,
    screenshot: Option<String>,
    snapshot: bool,
    json: bool,
    browser_path: Option<String>,
    /// `clean` (default) or `inherit=<profile-path>`.
    space: String,
    timeout_ms: u64,
    actions: Vec<BrowserAction>,
}

fn main() -> Result<()> {
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
        Mode::Browser { url, opts } => run_browser(&url, opts),
        Mode::BrowserUi { url, out } => browser_ui(&url, &out),
        Mode::ApiCapture { url, opts } => run_api_capture(&url, &opts),
        Mode::ApiShow { file, json } => run_api_show(&file, json),
        Mode::ApiOpenapi { file, out, title } => run_api_openapi(&file, out.as_deref(), title.as_deref()),
        Mode::ApiCopy { file, index, as_flavor } => run_api_copy(&file, index, as_flavor),
        // Offline monitor twin: also exits 1 on changes.
        Mode::ApiDiff { old, new, json } => {
            let alert = run_api_diff(&old, &new, json)?;
            if alert {
                std::process::exit(1);
            }
            Ok(())
        }
        // The monitor exits non-zero on changes so cron/CI automation can
        // alert without parsing output (the report itself is on stdout).
        Mode::ApiMonitor { url, baseline, update, json, opts } => {
            let alert = run_api_monitor(&url, &baseline, update, json, &opts)?;
            if alert {
                std::process::exit(1);
            }
            Ok(())
        }
    };
    if let Err(e) = result {
        eprintln!("xencode: {e:#}");
        std::process::exit(1);
    }
    Ok(())
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
    // `xencode browser <url> [flags]` — the Phase-6 headless Spaces CLI.
    if args.first().map(|s| s.as_str()) == Some("browser") {
        return parse_browser(&args[1..]);
    }
    // `xencode api capture|show|openapi|copy|diff|monitor …` — Phase 8.
    if args.first().map(|s| s.as_str()) == Some("api") {
        return parse_api(&args[1..]);
    }

    let mut out: Option<String> = None;
    let mut cli = CliOptions::default();
    let mut agent: Option<String> = None;
    let mut agent_ui: Option<String> = None;
    let mut render: Option<String> = None;
    let mut browser_ui_url: Option<String> = None;
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
            "--browser-ui" if i + 1 < args.len() => {
                browser_ui_url = Some(args[i + 1].clone());
                i += 1;
            }
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
    if let Some(url) = browser_ui_url {
        let out = out.unwrap_or_else(|| "browser-tab.png".into());
        return Ok(Mode::BrowserUi { url, out });
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

/// Parse the `browser` subcommand: `xencode browser <url> [flags]`.
fn parse_browser(args: &[String]) -> Result<Mode> {
    let mut opts = BrowserOptions { headless: true, timeout_ms: 30_000, ..Default::default() };
    let mut url: Option<String> = None;
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "--headed" => opts.headless = false,
            "--screenshot" if i + 1 < args.len() => {
                opts.screenshot = Some(args[i + 1].clone());
                i += 1;
            }
            "--snapshot" => opts.snapshot = true,
            "--json" => opts.json = true,
            "--timeout-ms" if i + 1 < args.len() => {
                opts.timeout_ms = args[i + 1].parse().context("--timeout-ms expects a number")?;
                i += 1;
            }
            "--browser-path" if i + 1 < args.len() => {
                opts.browser_path = Some(args[i + 1].clone());
                i += 1;
            }
            "--space" if i + 1 < args.len() => {
                opts.space = args[i + 1].clone();
                i += 1;
            }
            "--click" if i + 1 < args.len() => {
                opts.actions.push(BrowserAction::Click(args[i + 1].clone()));
                i += 1;
            }
            "--fill" if i + 1 < args.len() => {
                let (sel, text) = args[i + 1]
                    .split_once('=')
                    .context("--fill expects selector=text")?;
                opts.actions.push(BrowserAction::Fill(sel.to_string(), text.to_string()));
                i += 1;
            }
            "--press" if i + 1 < args.len() => {
                opts.actions.push(BrowserAction::Press(args[i + 1].clone()));
                i += 1;
            }
            "--scroll" if i + 1 < args.len() => {
                let (dx, dy) = args[i + 1]
                    .split_once(',')
                    .context("--scroll expects dx,dy (e.g. --scroll 0,600)")?;
                opts.actions.push(BrowserAction::Scroll(
                    dx.parse().context("--scroll dx must be a number")?,
                    dy.parse().context("--scroll dy must be a number")?,
                ));
                i += 1;
            }
            "--wait" if i + 1 < args.len() => {
                opts.actions.push(BrowserAction::Wait(
                    args[i + 1].parse().context("--wait expects milliseconds")?,
                ));
                i += 1;
            }
            other if other.starts_with('-') => bail!("unknown browser flag: {other}"),
            other if url.is_none() => url = Some(other.to_string()),
            other => bail!("unexpected browser argument: {other}"),
        }
        i += 1;
    }
    let Some(url) = url else {
        bail!(
            "usage: xencode browser <url> [--headless] [--screenshot FILE] [--snapshot] [--json] \
             [--click SEL] [--fill SEL=TEXT] [--press KEY] [--scroll DX,DY] [--wait MS] \
             [--space clean|inherit=PATH] [--browser-path BIN] [--timeout-ms N]"
        );
    };
    Ok(Mode::Browser { url, opts })
}

/// `xencode browser <url>` — launch a headless Space, navigate, run the
/// action list, then print/screenshot/snapshot. The CLI-drivable contract
/// for CI and scripting.
fn run_browser(url: &str, opts: BrowserOptions) -> Result<()> {
    use xencode_browser::{Selector, Space, SpaceKind, SpaceOptions};

    let rt = tokio::runtime::Runtime::new().context("start tokio runtime")?;
    rt.block_on(async move {
        let kind = if let Some(profile) = opts.space.strip_prefix("inherit=") {
            SpaceKind::Inherit { profile: profile.into() }
        } else {
            SpaceKind::CleanRoom
        };
        let started = std::time::Instant::now();
        let space = Space::launch(SpaceOptions {
            kind,
            headless: opts.headless,
            browser_path: opts.browser_path.clone().map(std::path::PathBuf::from),
            ..Default::default()
        })
        .await
        .context("launch browser space")?;
        let nav_timeout = std::time::Duration::from_millis(opts.timeout_ms.max(1_000));
        let nav = tokio::time::timeout(nav_timeout, space.navigate(url))
            .await
            .map_err(|_| anyhow::anyhow!("navigate timed out after {}ms", opts.timeout_ms))?;
        nav.with_context(|| format!("navigate to {url}"))?;

        for action in &opts.actions {
            match action {
                BrowserAction::Click(sel) => {
                    space.click(&Selector::parse(sel)).await.with_context(|| format!("click {sel}"))?;
                }
                BrowserAction::Fill(sel, text) => {
                    space
                        .fill(&Selector::parse(sel), text)
                        .await
                        .with_context(|| format!("fill {sel}"))?;
                }
                BrowserAction::Press(key) => {
                    space.press(key).await.with_context(|| format!("press {key}"))?;
                }
                BrowserAction::Scroll(dx, dy) => {
                    space.scroll(*dx, *dy).await.context("scroll")?;
                }
                BrowserAction::Wait(ms) => {
                    tokio::time::sleep(std::time::Duration::from_millis(*ms)).await;
                }
            }
        }

        let title = space.title().await.unwrap_or_default();
        let final_url = space.url().await.unwrap_or_else(|_| url.to_string());
        let viewport = space.viewport();

        if opts.snapshot {
            let (outline, truncated) = space.outline().await?;
            if opts.json {
                println!(
                    "{}",
                    serde_json::json!({
                        "url": final_url, "title": title, "outline": outline,
                        "outline_truncated": truncated,
                    })
                );
            } else {
                print!("{outline}");
                if truncated {
                    eprintln!("(outline truncated)");
                }
            }
        }

        if let Some(path) = &opts.screenshot {
            let frame = space.screenshot().await.context("screenshot")?;
            if let Some(parent) = std::path::Path::new(path).parent() {
                std::fs::create_dir_all(parent)?;
            }
            save_frame_png(&frame, path)?;
            if !opts.json {
                println!("saved {path} ({}x{})", frame.width, frame.height);
            }
        }

        if opts.json && !opts.snapshot {
            println!(
                "{}",
                serde_json::json!({
                    "url": final_url, "title": title,
                    "viewport": [viewport.0, viewport.1],
                    "steps": opts.actions.len(),
                    "ms": started.elapsed().as_millis() as u64,
                })
            );
        }
        Ok(())
    })
}

/// Encode a decoded frame as a PNG file (CLI --screenshot output).
fn save_frame_png(frame: &xencode_browser::Frame, path: &str) -> Result<()> {
    let file = std::fs::File::create(path).with_context(|| format!("create {path}"))?;
    let mut enc = png::Encoder::new(std::io::BufWriter::new(file), frame.width, frame.height);
    enc.set_color(png::ColorType::Rgba);
    enc.set_depth(png::BitDepth::Eight);
    let mut writer = enc.write_header().context("png header")?;
    writer.write_image_data(&frame.rgba).context("png data")?;
    writer.finish()?;
    Ok(())
}

/// `xencode --browser-ui <url>` — the Phase-6.2 GUI tab: launch a real
/// Space, navigate, composite a live screencast frame into the renderer.
/// Falls back to an empty-viewport render (with the launch error in the
/// status line) when no browser is installed, so the surface stays
/// inspectable everywhere.
fn browser_ui(url: &str, out: &str) -> Result<()> {
    let rt = tokio::runtime::Runtime::new().context("start tokio runtime")?;
    let (frame, status, title, secure, loading) = rt.block_on(async {
        match live_browser_frame(url).await {
            Ok(v) => v,
            Err(e) => {
                eprintln!("xencode: browser space unavailable: {e}");
                (None, format!("space error: {e}"), String::new(), false, false)
            }
        }
    });

    let page = ui::browser::BrowserPage {
        url,
        title: &title,
        status: &status,
        loading,
        secure,
        frame: frame.as_ref(),
    };
    let mut c = Canvas::new(WIN_W, WIN_H, SCALE);
    draw_backdrop(&mut c);
    let panel = panel_rect();
    draw_panel(&mut c, panel);
    draw_top_overlay(&mut c);
    let host = url
        .split("://")
        .nth(1)
        .unwrap_or(url)
        .split('/')
        .next()
        .unwrap_or(url)
        .to_string();
    draw_header(
        &mut c,
        panel,
        &HeaderTitle::Task {
            project: "xencode",
            title: "Browser",
            summary: Some(&host),
        },
        false,
        false,
    );
    ui::sidebar::draw(&mut c);
    draw_sidebar_footer(&mut c);
    let area = Rect::new(panel.x, panel.y + HEADER_H, panel.w, panel.h - HEADER_H);
    ui::browser::draw(&mut c, area, &page);

    if let Some(parent) = std::path::Path::new(out).parent() {
        std::fs::create_dir_all(parent)?;
    }
    c.px.save_png(out).map_err(|e| anyhow::anyhow!("save {out}: {e:?}"))?;
    println!("rendered {out}");
    Ok(())
}

/// Launch a clean-room Space, navigate, pump the screencast for ~2.5s and
/// hand back the latest frame for compositing.
async fn live_browser_frame(
    url: &str,
) -> Result<(Option<xencode_browser::Frame>, String, String, bool, bool)> {
    use xencode_browser::{Space, SpaceKind, SpaceOptions};
    use std::time::Duration;

    let space = Space::launch(SpaceOptions {
        kind: SpaceKind::CleanRoom,
        headless: true,
        ..Default::default()
    })
    .await?;
    space.navigate(url).await?;
    let title = space.title().await.unwrap_or_default();
    let final_url = space.url().await.unwrap_or_else(|_| url.to_string());
    let secure = final_url.starts_with("https://");

    let mut cast = space.start_screencast().await?;
    // Give the page a moment to paint and the stream to deliver a settled
    // frame (first frames often precede first paint).
    let mut frame = cast.wait_for_frame(Duration::from_secs(8)).await;
    tokio::time::sleep(Duration::from_millis(1200)).await;
    if let Some(later) = cast.latest().await {
        frame = Some(later);
    }
    let frame = frame.map(|arc| (*arc).clone());
    let dims = frame
        .as_ref()
        .map(|f| format!(" · {}×{}", f.width, f.height))
        .unwrap_or_default();
    let status = format!("clean-room space · headless{dims}");
    Ok((frame, status, title, secure, false))
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

// ---------------------------------------------------------------------
// Phase 8 — API Discovery (`xencode api …`)
// ---------------------------------------------------------------------

/// Parse the `api` subcommand.
fn parse_api(args: &[String]) -> Result<Mode> {
    let Some(cmd) = args.first() else {
        bail!(
            "usage: xencode api capture <url> [flags] | show <FILE> | openapi <FILE> [--out FILE] | \
             copy <FILE> <N> [--as curl|fetch|reqwest|quickjs] | diff <OLD> <NEW> | \
             monitor <url> --baseline FILE [--update-baseline]"
        );
    };
    let rest = &args[1..];
    match cmd.as_str() {
        "capture" => {
            let mut opts =
                ApiCaptureOptions { headless: true, timeout_ms: 30_000, ..Default::default() };
            let url = parse_capture_flags(rest, &mut opts)?;
            let Some(url) = url else {
                bail!("usage: xencode api capture <url> [--wait MS] [--click SEL] [--fill SEL=TEXT] \
                       [--press KEY] [--scroll DX,DY] [--no-bodies] [--no-redact] [--openapi FILE] \
                       [--out FILE] [--json] [--space clean|inherit=PATH] [--browser-path BIN] \
                       [--timeout-ms N] [--headed]");
            };
            Ok(Mode::ApiCapture { url, opts })
        }
        "show" => {
            let mut file = None;
            let mut json = false;
            for a in rest {
                match a.as_str() {
                    "--json" => json = true,
                    other if other.starts_with('-') => bail!("unknown api show flag: {other}"),
                    other if file.is_none() => file = Some(other.to_string()),
                    other => bail!("unexpected api show argument: {other}"),
                }
            }
            let Some(file) = file else {
                bail!("usage: xencode api show <capture.json> [--json]");
            };
            Ok(Mode::ApiShow { file, json })
        }
        "openapi" => {
            let mut file = None;
            let mut out = None;
            let mut title = None;
            let mut i = 0;
            while i < rest.len() {
                match rest[i].as_str() {
                    "--out" if i + 1 < rest.len() => {
                        out = Some(rest[i + 1].clone());
                        i += 1;
                    }
                    "--title" if i + 1 < rest.len() => {
                        title = Some(rest[i + 1].clone());
                        i += 1;
                    }
                    other if other.starts_with('-') => bail!("unknown api openapi flag: {other}"),
                    other if file.is_none() => file = Some(other.to_string()),
                    other => bail!("unexpected api openapi argument: {other}"),
                }
                i += 1;
            }
            let Some(file) = file else {
                bail!("usage: xencode api openapi <capture.json> [--out FILE] [--title T]");
            };
            Ok(Mode::ApiOpenapi { file, out, title })
        }
        "copy" => {
            let mut file = None;
            let mut index = None;
            let mut as_flavor = ApiCopyAs::Curl;
            let mut i = 0;
            while i < rest.len() {
                match rest[i].as_str() {
                    "--as" if i + 1 < rest.len() => {
                        as_flavor = ApiCopyAs::parse(&rest[i + 1])?;
                        i += 1;
                    }
                    other if other.starts_with('-') => bail!("unknown api copy flag: {other}"),
                    other if file.is_none() => file = Some(other.to_string()),
                    other if index.is_none() => {
                        index = Some(other.parse().context("api copy index must be a number (1-based, see `api show`)")?);
                    }
                    other => bail!("unexpected api copy argument: {other}"),
                }
                i += 1;
            }
            let (Some(file), Some(index)) = (file, index) else {
                bail!("usage: xencode api copy <capture.json> <N> [--as curl|fetch|reqwest|quickjs]");
            };
            if index == 0 {
                bail!("api copy index is 1-based (see the numbering in `xencode api show`)");
            }
            Ok(Mode::ApiCopy { file, index, as_flavor })
        }
        "diff" => {
            let mut files: Vec<String> = Vec::new();
            let mut json = false;
            for a in rest {
                match a.as_str() {
                    "--json" => json = true,
                    other if other.starts_with('-') => bail!("unknown api diff flag: {other}"),
                    other => files.push(other.to_string()),
                }
            }
            let [ref old, ref new] = files[..] else {
                bail!("usage: xencode api diff <old-capture.json> <new-capture.json> [--json]");
            };
            Ok(Mode::ApiDiff { old: old.clone(), new: new.clone(), json })
        }
        "monitor" => {
            let mut opts =
                ApiCaptureOptions { headless: true, timeout_ms: 30_000, ..Default::default() };
            let mut baseline = None;
            let mut update = false;
            let mut json = false;
            let mut url = None;
            let mut i = 0;
            while i < rest.len() {
                match rest[i].as_str() {
                    "--baseline" if i + 1 < rest.len() => {
                        baseline = Some(rest[i + 1].clone());
                        i += 1;
                    }
                    "--update-baseline" => update = true,
                    "--json" => json = true,
                    "--wait" if i + 1 < rest.len() => {
                        opts.actions.push(BrowserAction::Wait(
                            rest[i + 1].parse().context("--wait expects milliseconds")?,
                        ));
                        i += 1;
                    }
                    "--click" if i + 1 < rest.len() => {
                        opts.actions.push(BrowserAction::Click(rest[i + 1].clone()));
                        i += 1;
                    }
                    "--fill" if i + 1 < rest.len() => {
                        let (sel, text) = rest[i + 1]
                            .split_once('=')
                            .context("--fill expects selector=text")?;
                        opts.actions.push(BrowserAction::Fill(sel.to_string(), text.to_string()));
                        i += 1;
                    }
                    "--press" if i + 1 < rest.len() => {
                        opts.actions.push(BrowserAction::Press(rest[i + 1].clone()));
                        i += 1;
                    }
                    "--scroll" if i + 1 < rest.len() => {
                        let (dx, dy) = rest[i + 1]
                            .split_once(',')
                            .context("--scroll expects dx,dy (e.g. --scroll 0,600)")?;
                        opts.actions.push(BrowserAction::Scroll(
                            dx.parse().context("--scroll dx must be a number")?,
                            dy.parse().context("--scroll dy must be a number")?,
                        ));
                        i += 1;
                    }
                    "--no-bodies" => opts.no_bodies = true,
                    "--no-redact" => opts.no_redact = true,
                    "--out" if i + 1 < rest.len() => {
                        opts.out = Some(rest[i + 1].clone());
                        i += 1;
                    }
                    "--space" if i + 1 < rest.len() => {
                        opts.space = rest[i + 1].clone();
                        i += 1;
                    }
                    "--browser-path" if i + 1 < rest.len() => {
                        opts.browser_path = Some(rest[i + 1].clone());
                        i += 1;
                    }
                    "--timeout-ms" if i + 1 < rest.len() => {
                        opts.timeout_ms = rest[i + 1].parse().context("--timeout-ms expects a number")?;
                        i += 1;
                    }
                    "--headed" => opts.headless = false,
                    other if other.starts_with('-') => bail!("unknown api monitor flag: {other}"),
                    other if url.is_none() => url = Some(other.to_string()),
                    other => bail!("unexpected api monitor argument: {other}"),
                }
                i += 1;
            }
            let (Some(url), Some(baseline)) = (url, baseline) else {
                bail!(
                    "usage: xencode api monitor <url> --baseline FILE [--update-baseline] [--json] \
                     [--click SEL] [--fill SEL=TEXT] [--press KEY] [--scroll DX,DY] [--wait MS] \
                     [--no-bodies] [--no-redact] [--space clean|inherit=PATH] [--browser-path BIN] \
                     [--timeout-ms N] [--headed]"
                );
            };
            Ok(Mode::ApiMonitor { url, baseline, update, json, opts })
        }
        other => bail!("unknown api subcommand `{other}` — expected capture, show, openapi, copy, diff, monitor"),
    }
}

/// Shared capture/monitor flag parsing. Returns the positional URL.
fn parse_capture_flags(args: &[String], opts: &mut ApiCaptureOptions) -> Result<Option<String>> {
    let mut url = None;
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "--wait" if i + 1 < args.len() => {
                // Extra settle time is modeled as a final Wait action.
                opts.actions.push(BrowserAction::Wait(
                    args[i + 1].parse().context("--wait expects milliseconds")?,
                ));
                i += 1;
            }
            "--click" if i + 1 < args.len() => {
                opts.actions.push(BrowserAction::Click(args[i + 1].clone()));
                i += 1;
            }
            "--fill" if i + 1 < args.len() => {
                let (sel, text) = args[i + 1]
                    .split_once('=')
                    .context("--fill expects selector=text")?;
                opts.actions.push(BrowserAction::Fill(sel.to_string(), text.to_string()));
                i += 1;
            }
            "--press" if i + 1 < args.len() => {
                opts.actions.push(BrowserAction::Press(args[i + 1].clone()));
                i += 1;
            }
            "--scroll" if i + 1 < args.len() => {
                let (dx, dy) = args[i + 1]
                    .split_once(',')
                    .context("--scroll expects dx,dy (e.g. --scroll 0,600)")?;
                opts.actions.push(BrowserAction::Scroll(
                    dx.parse().context("--scroll dx must be a number")?,
                    dy.parse().context("--scroll dy must be a number")?,
                ));
                i += 1;
            }
            "--no-bodies" => opts.no_bodies = true,
            "--no-redact" => opts.no_redact = true,
            "--out" if i + 1 < args.len() => {
                opts.out = Some(args[i + 1].clone());
                i += 1;
            }
            "--openapi" if i + 1 < args.len() => {
                opts.openapi = Some(args[i + 1].clone());
                i += 1;
            }
            "--json" => opts.json = true,
            "--space" if i + 1 < args.len() => {
                opts.space = args[i + 1].clone();
                i += 1;
            }
            "--browser-path" if i + 1 < args.len() => {
                opts.browser_path = Some(args[i + 1].clone());
                i += 1;
            }
            "--timeout-ms" if i + 1 < args.len() => {
                opts.timeout_ms = args[i + 1].parse().context("--timeout-ms expects a number")?;
                i += 1;
            }
            "--headed" => opts.headless = false,
            other if other.starts_with('-') => bail!("unknown api capture flag: {other}"),
            other if url.is_none() => url = Some(other.to_string()),
            other => bail!("unexpected api capture argument: {other}"),
        }
        i += 1;
    }
    Ok(url)
}

/// Launch a Space, run the interaction script under a network capture, and
/// return the redacted capture file plus its derived endpoint catalog.
fn api_capture_session(
    url: &str,
    opts: &ApiCaptureOptions,
) -> Result<(xencode_browser::CaptureFile, xencode_browser::EndpointCatalog)> {
    use xencode_browser::{
        CaptureOptions, CaptureState, NetCapture, Selector, Space, SpaceKind, SpaceOptions,
    };

    let rt = tokio::runtime::Runtime::new().context("start tokio runtime")?;
    rt.block_on(async move {
        let kind = if let Some(profile) = opts.space.strip_prefix("inherit=") {
            SpaceKind::Inherit { profile: profile.into() }
        } else {
            SpaceKind::CleanRoom
        };
        let space = Space::launch(SpaceOptions {
            kind,
            headless: opts.headless,
            browser_path: opts.browser_path.clone().map(std::path::PathBuf::from),
            ..Default::default()
        })
        .await
        .context("launch browser space")?;

        let capture = NetCapture::start(
            &space,
            CaptureOptions {
                bodies: !opts.no_bodies,
                ..Default::default()
            },
        )
        .await
        .context("start network capture")?;

        let nav_timeout = std::time::Duration::from_millis(opts.timeout_ms.max(1_000));
        let nav = tokio::time::timeout(nav_timeout, space.navigate(url))
            .await
            .map_err(|_| anyhow::anyhow!("navigate timed out after {}ms", opts.timeout_ms))?;
        nav.with_context(|| format!("navigate to {url}"))?;

        for action in &opts.actions {
            match action {
                BrowserAction::Click(sel) => {
                    space.click(&Selector::parse(sel)).await.with_context(|| format!("click {sel}"))?;
                }
                BrowserAction::Fill(sel, text) => {
                    space
                        .fill(&Selector::parse(sel), text)
                        .await
                        .with_context(|| format!("fill {sel}"))?;
                }
                BrowserAction::Press(key) => {
                    space.press(key).await.with_context(|| format!("press {key}"))?;
                }
                BrowserAction::Scroll(dx, dy) => {
                    space.scroll(*dx, *dy).await.context("scroll")?;
                }
                BrowserAction::Wait(ms) => {
                    tokio::time::sleep(std::time::Duration::from_millis(*ms)).await;
                }
            }
        }

        // Most apps fire their real API calls after the load event; always
        // give the page a short settle window (a trailing --wait adds more).
        tokio::time::sleep(std::time::Duration::from_millis(API_SETTLE_MS)).await;

        let mut entries = capture.stop().await.context("stop network capture")?;
        let redacted = !opts.no_redact;
        if redacted {
            for e in &mut entries {
                xencode_browser::redact_entry(e);
            }
        }
        let catalog = {
            // feed the pure state machine result through the catalog builder
            let _ = CaptureState::new(&CaptureOptions::default()); // (kept for parity with lib tests)
            xencode_browser::build_catalog(&entries)
        };
        let file = xencode_browser::CaptureFile::new(url, redacted, entries);
        Ok((file, catalog))
    })
}

/// `xencode api capture <url>` — record traffic, print/save the catalog.
fn run_api_capture(url: &str, opts: &ApiCaptureOptions) -> Result<()> {
    let (file, catalog) = api_capture_session(url, opts)?;

    if let Some(path) = &opts.out {
        file.save(std::path::Path::new(path))
            .with_context(|| format!("save capture {path}"))?;
    }
    if let Some(path) = &opts.openapi {
        let doc = xencode_browser::to_openapi(&catalog, &openapi_title(catalog.sources.first()));
        let json = serde_json::to_string_pretty(&doc)?;
        if let Some(parent) = std::path::Path::new(path).parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::write(path, json).with_context(|| format!("write openapi {path}"))?;
    }

    if opts.json {
        println!(
            "{}",
            serde_json::json!({
                "url": url,
                "entries": file.entries.len(),
                "redacted": file.redacted,
                "capture": opts.out,
                "openapi": opts.openapi,
                "endpoints": catalog.endpoints,
                "sources": catalog.sources,
            })
        );
    } else {
        print!("{}", xencode_browser::render_catalog_text(&catalog));
        if let Some(path) = &opts.out {
            eprintln!("· capture saved to {path} ({} entries)", file.entries.len());
        }
        if let Some(path) = &opts.openapi {
            eprintln!("· OpenAPI written to {path}");
        }
        if !catalog.endpoints.is_empty() {
            eprintln!("· replay: `xencode api copy <capture.json> <N> [--as curl|fetch|reqwest|quickjs]`");
        }
    }
    Ok(())
}

fn openapi_title(first_source: Option<&String>) -> String {
    match first_source {
        Some(base) => format!("Observed API — {base}"),
        None => "Observed API".to_string(),
    }
}

/// `xencode api show <capture.json>` — catalog summary.
fn run_api_show(file: &str, json: bool) -> Result<()> {
    let capture = xencode_browser::CaptureFile::load(std::path::Path::new(file))?;
    let catalog = xencode_browser::build_catalog(&capture.entries);
    if json {
        println!("{}", serde_json::to_string_pretty(&catalog)?);
    } else {
        print!("{}", xencode_browser::render_catalog_text(&catalog));
    }
    Ok(())
}

/// `xencode api openapi <capture.json>` — OpenAPI 3.1 export.
fn run_api_openapi(file: &str, out: Option<&str>, title: Option<&str>) -> Result<()> {
    let capture = xencode_browser::CaptureFile::load(std::path::Path::new(file))?;
    let catalog = xencode_browser::build_catalog(&capture.entries);
    let doc = xencode_browser::to_openapi(
        &catalog,
        title.unwrap_or(&openapi_title(catalog.sources.first())),
    );
    let json = serde_json::to_string_pretty(&doc)?;
    match out {
        Some(path) => {
            if let Some(parent) = std::path::Path::new(path).parent() {
                std::fs::create_dir_all(parent)?;
            }
            std::fs::write(path, json).with_context(|| format!("write openapi {path}"))?;
            eprintln!("· OpenAPI written to {path}");
        }
        None => println!("{json}"),
    }
    Ok(())
}

/// `xencode api copy <capture.json> <N>` — replay snippet for endpoint N.
fn run_api_copy(file: &str, index: usize, flavor: ApiCopyAs) -> Result<()> {
    let capture = xencode_browser::CaptureFile::load(std::path::Path::new(file))?;
    let catalog = xencode_browser::build_catalog(&capture.entries);
    let ep = catalog.endpoints.get(index - 1).with_context(|| {
        format!(
            "endpoint {} of {} — pick an index from `xencode api show {}`",
            index,
            catalog.endpoints.len(),
            file
        )
    })?;
    println!("{}", xencode_browser::copy_as(ep, flavor.to_lib()));
    Ok(())
}

/// `xencode api diff <old> <new>` — monitor report (exit 1 on changes).
fn run_api_diff(old: &str, new: &str, json: bool) -> Result<bool> {
    let a = xencode_browser::CaptureFile::load(std::path::Path::new(old))?;
    let b = xencode_browser::CaptureFile::load(std::path::Path::new(new))?;
    let old_cat = xencode_browser::build_catalog(&a.entries);
    let new_cat = xencode_browser::build_catalog(&b.entries);
    let diff = xencode_browser::diff_catalogs(&old_cat, &new_cat);
    if json {
        println!("{}", diff.alert_json());
    } else {
        print!("{}", diff.render_text());
    }
    Ok(diff.has_changes())
}

/// `xencode api monitor <url> --baseline FILE` — fresh capture → diff →
/// alert. The baseline is a capture file; `--update-baseline` rewrites it
/// with the fresh capture after comparing.
fn run_api_monitor(
    url: &str,
    baseline: &str,
    update: bool,
    json: bool,
    opts: &ApiCaptureOptions,
) -> Result<bool> {
    let baseline_path = std::path::Path::new(baseline);
    let old = xencode_browser::CaptureFile::load(baseline_path)
        .with_context(|| format!("load baseline {baseline} (create one with `xencode api capture {url} --out {baseline}`)"))?;
    let (fresh, _) = api_capture_session(url, opts)?;
    if old.redacted != fresh.redacted {
        eprintln!(
            "· warning: baseline redaction ({}) differs from this run ({}) — header/query values may not be comparable",
            old.redacted,
            fresh.redacted
        );
    }
    let old_cat = xencode_browser::build_catalog(&old.entries);
    let new_cat = xencode_browser::build_catalog(&fresh.entries);
    let diff = xencode_browser::diff_catalogs(&old_cat, &new_cat);

    if let Some(path) = &opts.out {
        fresh
            .save(std::path::Path::new(path))
            .with_context(|| format!("save capture {path}"))?;
        if !json {
            eprintln!("· fresh capture saved to {path} ({} entries)", fresh.entries.len());
        }
    }

    if json {
        println!("{}", diff.alert_json());
    } else {
        print!("{}", diff.render_text());
    }
    if update {
        fresh.save(baseline_path).with_context(|| format!("update baseline {baseline}"))?;
        if !json {
            eprintln!("· baseline updated: {baseline}");
        }
    }
    Ok(diff.has_changes())
}
