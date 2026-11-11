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
use std::path::{Path, PathBuf};
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
    // Phase 7 -------------------------------------------------------------
    /// `xencode re info <FILE>` — format/arch/entry/sections summary.
    ReInfo { file: String, json: bool },
    /// `xencode re strings <FILE> [--min N] [--utf16]` — string extraction.
    ReStrings { file: String, min: usize, utf16_only: bool, json: bool },
    /// `xencode re hex <FILE> [--offset N] [--len N]` — hex-view rows.
    ReHex { file: String, offset: u64, len: u64, json: bool },
    /// `xencode re symbols <FILE>` — symbol table.
    ReSymbols { file: String, json: bool },
    /// `xencode re imports <FILE>` — imported symbols.
    ReImports { file: String, json: bool },
    /// `xencode re exports <FILE>` — exported symbols.
    ReExports { file: String, json: bool },
    /// `xencode re disasm <FILE> [--offset N | --entry] [--len N] [--arch X]`.
    ReDisasm { file: String, offset: Option<u64>, len: u64, arch: Option<String>, json: bool },
    // Phase 7.2 -------------------------------------------------------------
    /// `xencode re apk <FILE> [--json]` — Android package summary.
    ReApk { file: String, json: bool },
    /// `xencode re ipa <FILE> [--json]` — iOS bundle summary.
    ReIpa { file: String, json: bool },
    /// `xencode re decompile <FILE> [--out DIR] [--download] [--no-res]
    /// [--deobf] [-j N] [--json]` — jadx headless decompile + inventory.
    ReDecompile {
        file: String,
        out: Option<String>,
        download: bool,
        no_res: bool,
        deobf: bool,
        threads: u32,
        max_files: usize,
        json: bool,
    },
    /// `xencode --re-ui <FILE>` — the Phase-7 native RE tab with the
    /// analyzed binary rendered into the workspace.
    ReUi { file: String, out: String },
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
        // Phase 7 — XenRE subcommands.
        Mode::ReInfo { file, json } => run_re_info(&file, json),
        Mode::ReStrings { file, min, utf16_only, json } => {
            run_re_strings(&file, min, utf16_only, json)
        }
        Mode::ReHex { file, offset, len, json } => run_re_hex(&file, offset, len, json),
        Mode::ReSymbols { file, json } => run_re_symbols(&file, json),
        Mode::ReImports { file, json } => run_re_imports(&file, json),
        Mode::ReExports { file, json } => run_re_exports(&file, json),
        Mode::ReDisasm { file, offset, len, arch, json } => {
            run_re_disasm(&file, offset, len, arch.as_deref(), json)
        }
        Mode::ReApk { file, json } => run_re_apk(&file, json),
        Mode::ReIpa { file, json } => run_re_ipa(&file, json),
        Mode::ReDecompile {
            file,
            out,
            download,
            no_res,
            deobf,
            threads,
            max_files,
            json,
        } => run_re_decompile(
            &file,
            out.as_deref(),
            download,
            no_res,
            deobf,
            threads,
            max_files,
            json,
        ),
        Mode::ReUi { file, out } => re_ui(&file, &out),
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
    // `xencode re info|strings|hex|symbols|imports|exports|disasm …` — Phase 7.
    if args.first().map(|s| s.as_str()) == Some("re") {
        return parse_re(&args[1..]);
    }

    let mut out: Option<String> = None;
    let mut cli = CliOptions::default();
    let mut agent: Option<String> = None;
    let mut agent_ui: Option<String> = None;
    let mut render: Option<String> = None;
    let mut browser_ui_url: Option<String> = None;
    let mut re_ui_file: Option<String> = None;
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
            "--re-ui" if i + 1 < args.len() => {
                re_ui_file = Some(args[i + 1].clone());
                i += 2;
                continue;
            }
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
    if let Some(file) = re_ui_file {
        return Ok(Mode::ReUi { file, out: out.unwrap_or_else(|| "re-tab.png".to_string()) });
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

// ============================================================================
// Phase 7 — XenRE (`xencode re …`)
// ============================================================================

/// Parse the `re` subcommand tree.
fn parse_re(args: &[String]) -> Result<Mode> {
    let Some(cmd) = args.first() else {
        bail!(
            "usage: xencode re info <FILE> | strings <FILE> | hex <FILE> | symbols <FILE> | \
             imports <FILE> | exports <FILE> | disasm <FILE> | apk <FILE> | ipa <FILE> | \
             decompile <FILE>"
        );
    };
    let rest = &args[1..];
    match cmd.as_str() {
        "apk" | "ipa" => {
            let (file, flags) = re_file_flags(rest)?;
            Ok(if cmd == "apk" {
                Mode::ReApk { file, json: flags.json }
            } else {
                Mode::ReIpa { file, json: flags.json }
            })
        }
        "decompile" => {
            let mut file = None;
            let mut out = None;
            let mut download = false;
            let mut no_res = false;
            let mut deobf = false;
            let mut threads = 0u32;
            let mut max_files = xencode_re::JadxRun::DEFAULT_MAX_FILES;
            let mut json = false;
            let mut i = 0;
            while i < rest.len() {
                match rest[i].as_str() {
                    "--out" if i + 1 < rest.len() => {
                        out = Some(rest[i + 1].clone());
                        i += 2;
                    }
                    "--download" => {
                        download = true;
                        i += 1;
                    }
                    "--no-res" => {
                        no_res = true;
                        i += 1;
                    }
                    "--deobf" => {
                        deobf = true;
                        i += 1;
                    }
                    "-j" if i + 1 < rest.len() => {
                        threads = rest[i + 1]
                            .parse()
                            .context("-j expects a thread count")?;
                        i += 2;
                    }
                    "--max-files" if i + 1 < rest.len() => {
                        max_files = rest[i + 1]
                            .parse()
                            .context("--max-files expects a number")?;
                        i += 2;
                    }
                    "--json" => {
                        json = true;
                        i += 1;
                    }
                    other if other.starts_with('-') => {
                        bail!("unknown re decompile flag: {other}")
                    }
                    other if file.is_none() => {
                        file = Some(other.to_string());
                        i += 1;
                    }
                    other => bail!("unexpected re decompile argument: {other}"),
                }
            }
            Ok(Mode::ReDecompile {
                file: re_file(file, "re decompile")?,
                out,
                download,
                no_res,
                deobf,
                threads,
                max_files,
                json,
            })
        }
        "info" => {
            let (file, flags) = re_file_flags(rest)?;
            Ok(Mode::ReInfo { file, json: flags.json })
        }
        "strings" => {
            let mut file = None;
            let mut min = 4usize;
            let mut utf16_only = false;
            let mut json = false;
            let mut i = 0;
            while i < rest.len() {
                match rest[i].as_str() {
                    "--min" if i + 1 < rest.len() => {
                        min = rest[i + 1]
                            .parse()
                            .context("--min expects a number of bytes")?;
                        i += 2;
                    }
                    "--utf16" => {
                        utf16_only = true;
                        i += 1;
                    }
                    "--json" => {
                        json = true;
                        i += 1;
                    }
                    other if other.starts_with('-') => bail!("unknown re strings flag: {other}"),
                    other if file.is_none() => {
                        file = Some(other.to_string());
                        i += 1;
                    }
                    other => bail!("unexpected re strings argument: {other}"),
                }
            }
            Ok(Mode::ReStrings {
                file: re_file(file, "re strings")?,
                min,
                utf16_only,
                json,
            })
        }
        "hex" => {
            let mut file = None;
            let mut offset = 0u64;
            let mut len = 256u64;
            let mut json = false;
            let mut i = 0;
            while i < rest.len() {
                match rest[i].as_str() {
                    "--offset" if i + 1 < rest.len() => {
                        offset = re_hexnum(&rest[i + 1], "--offset")?;
                        i += 2;
                    }
                    "--len" if i + 1 < rest.len() => {
                        len = re_hexnum(&rest[i + 1], "--len")?;
                        i += 2;
                    }
                    "--json" => {
                        json = true;
                        i += 1;
                    }
                    other if other.starts_with('-') => bail!("unknown re hex flag: {other}"),
                    other if file.is_none() => {
                        file = Some(other.to_string());
                        i += 1;
                    }
                    other => bail!("unexpected re hex argument: {other}"),
                }
            }
            Ok(Mode::ReHex {
                file: re_file(file, "re hex")?,
                offset,
                len,
                json,
            })
        }
        "symbols" | "imports" | "exports" => {
            let (file, flags) = re_file_flags(rest)?;
            let mode = match cmd.as_str() {
                "symbols" => Mode::ReSymbols { file, json: flags.json },
                "imports" => Mode::ReImports { file, json: flags.json },
                _ => Mode::ReExports { file, json: flags.json },
            };
            Ok(mode)
        }
        "disasm" => {
            let mut file = None;
            let mut offset = None;
            let mut len = 0u64; // 0 = auto (entry point / section window)
            let mut arch = None;
            let mut json = false;
            let mut i = 0;
            while i < rest.len() {
                match rest[i].as_str() {
                    "--offset" if i + 1 < rest.len() => {
                        offset = Some(re_hexnum(&rest[i + 1], "--offset")?);
                        i += 2;
                    }
                    "--len" if i + 1 < rest.len() => {
                        len = re_hexnum(&rest[i + 1], "--len")?;
                        i += 2;
                    }
                    "--arch" if i + 1 < rest.len() => {
                        arch = Some(rest[i + 1].clone());
                        i += 2;
                    }
                    "--entry" => {
                        i += 1;
                    }
                    "--json" => {
                        json = true;
                        i += 1;
                    }
                    other if other.starts_with('-') => bail!("unknown re disasm flag: {other}"),
                    other if file.is_none() => {
                        file = Some(other.to_string());
                        i += 1;
                    }
                    other => bail!("unexpected re disasm argument: {other}"),
                }
            }
            Ok(Mode::ReDisasm {
                file: re_file(file, "re disasm")?,
                offset,
                len,
                arch,
                json,
            })
        }
        other => bail!(
            "unknown re subcommand `{other}` — expected: info, strings, hex, symbols, imports, exports, disasm, apk, ipa, decompile"
        ),
    }
}

/// Flags shared by every `re` subcommand: a positional FILE plus --json.
fn re_file_flags(args: &[String]) -> Result<(String, ReFlags)> {
    let mut file = None;
    let mut flags = ReFlags::default();
    for arg in args {
        match arg.as_str() {
            "--json" => flags.json = true,
            other if other.starts_with('-') => bail!("unknown flag: {other}"),
            other if file.is_none() => file = Some(other.to_string()),
            other => bail!("unexpected argument: {other}"),
        }
    }
    Ok((re_file(file, "re")?, flags))
}

#[derive(Default)]
struct ReFlags {
    json: bool,
}

fn re_file(file: Option<String>, what: &str) -> Result<String> {
    file.with_context(|| format!("{what} needs a FILE — see `xencode re` usage"))
}
/// Parse a hex (`0x2e0`, `2e0`) or decimal number — offsets are hex-first
/// in RE workflows.
fn re_hexnum(s: &str, what: &str) -> Result<u64> {
    let t = s.trim();
    let (neg, t) = if let Some(r) = t.strip_prefix('-') { (true, r) } else { (false, t) };
    let digits = t.strip_prefix("0x").or_else(|| t.strip_prefix("0X")).unwrap_or(t);
    let (v, radix) = if digits.contains(|c: char| c.is_ascii_hexdigit() && !c.is_ascii_digit())
        && !t.starts_with("0x")
        && !t.starts_with("0X")
    {
        (digits.parse::<u64>().with_context(|| format!("{what} expects a number, got {s}"))?, 10)
    } else {
        (
            u64::from_str_radix(digits, 16).or_else(|_| digits.parse::<u64>())
                .with_context(|| format!("{what} expects a number, got {s}"))?,
            16,
        )
    };
    let _ = radix;
    Ok(if neg { v.wrapping_neg() } else { v })
}


/// Analyze once, reuse everywhere.
fn re_analyze(file: &str) -> Result<(xencode_re::BinaryInfo, Vec<u8>)> {
    let data = std::fs::read(file).with_context(|| format!("read {file}"))?;
    if data.len() as u64 > xencode_re::MAX_INPUT_BYTES {
        bail!("{} exceeds the {} analysis cap", file, xencode_re::MAX_INPUT_BYTES);
    }
    let info = xencode_re::analyze_file(file)?;
    Ok((info, data))
}

/// `xencode re info <FILE>` — one-screen summary of the image.
fn run_re_info(file: &str, json: bool) -> Result<()> {
    let (info, _data) = re_analyze(file)?;
    if json {
        println!("{}", serde_json::to_string_pretty(&info)?);
        return Ok(());
    }

    println!("file      {file}");
    println!("format    {} ({}-bit, {} endian)", info.format, info.bits, info.endian);
    println!("kind      {}", info.kind);
    println!("arch      {}", info.arch);
    if info.pie {
        println!("pie       yes");
    }
    if let Some(base) = info.image_base {
        println!("base      {base:#x}");
    }
    if let Some(entry) = info.entry_va {
        println!("entry     {entry:#x}");
    }
    if let Some(interp) = &info.interpreter {
        println!("interp    {interp}");
    }
    if !info.libs.is_empty() {
        println!("libs      {}", info.libs.join(", "));
    }
    println!(
        "counts    {} sections · {} symbols · {} imports · {} exports",
        info.sections.len(),
        info.symbols.len(),
        info.imports.len(),
        info.exports.len()
    );
    println!();
    println!("{:<18} {:>10} {:>10} {:>10}  flags", "section", "va", "size", "raw");
    for s in &info.sections {
        let fl = format!(
            "{}{}{}",
            if s.flags.read { "r" } else { "-" },
            if s.flags.write { "w" } else { "-" },
            if s.flags.exec { "x" } else { "-" }
        );
        println!(
            "{:<18} {:>10} {:>10} {:>10}  {}",
            s.name,
            s.va.map(|v| format!("{v:#x}")).unwrap_or_default(),
            s.size,
            s.file_size,
            fl
        );
    }
    Ok(())
}

/// `xencode re strings <FILE> [--min N] [--utf16]` — printable runs.
fn run_re_strings(file: &str, min: usize, utf16_only: bool, json: bool) -> Result<()> {
    let (_info, data) = re_analyze(file)?;
    let mut hits = xencode_re::extract_strings(&data, min);
    if utf16_only {
        hits.retain(|s| s.encoding == xencode_re::StrEnc::Utf16Le);
    }
    if json {
        println!("{}", serde_json::to_string_pretty(&hits)?);
        return Ok(());
    }
    println!("{} strings (min {} bytes)", hits.len(), min);
    println!();
    const SHOW: usize = 80;
    for s in hits.iter().take(SHOW) {
        let tag = match s.encoding {
            xencode_re::StrEnc::Ascii => "  ",
            xencode_re::StrEnc::Utf16Le => "u ",
        };
        println!("{:>10}  {tag}{}", s.offset, s.text);
    }
    if hits.len() > SHOW {
        println!("… {} more ({} total)", hits.len() - SHOW, hits.len());
    }
    Ok(())
}

/// `xencode re hex <FILE> [--offset N] [--len N]` — hex view rows.
fn run_re_hex(file: &str, offset: u64, len: u64, json: bool) -> Result<()> {
    let (_info, data) = re_analyze(file)?;
    let (off, take) = xencode_re::clamp_range(offset, len, data.len() as u64);
    let rows = xencode_re::hex_rows(
        &data[off as usize..(off + take) as usize],
        off,
        xencode_re::DEFAULT_ROW_WIDTH,
    );
    if json {
        println!("{}", serde_json::to_string_pretty(&rows)?);
        return Ok(());
    }
    println!("{} · {} bytes @ {off:#x}..{:#x}", file, take, off + take);
    println!();
    for row in &rows {
        println!("{}", xencode_re::render_hex_row(row, xencode_re::DEFAULT_ROW_WIDTH));
    }
    Ok(())
}

/// `xencode re symbols|imports|exports <FILE>` — symbol tables.
fn run_re_symbols(file: &str, json: bool) -> Result<()> {
    let (info, _data) = re_analyze(file)?;
    if json {
        println!("{}", serde_json::to_string_pretty(&info.symbols)?);
        return Ok(());
    }
    println!("{} symbols", info.symbols.len());
    println!();
    const SHOW: usize = 60;
    for s in info.symbols.iter().take(SHOW) {
        let dyn_tag = if s.dynamic { " (dyn)" } else { "" };
        println!("{:>10}  {}{}", s.va, s, dyn_tag);
    }
    if info.symbols.len() > SHOW {
        println!("… {} more ({} total)", info.symbols.len() - SHOW, info.symbols.len());
    }
    Ok(())
}

fn run_re_imports(file: &str, json: bool) -> Result<()> {
    let (info, _data) = re_analyze(file)?;
    if json {
        println!("{}", serde_json::to_string_pretty(&info.imports)?);
        return Ok(());
    }
    println!("{} imports", info.imports.len());
    println!();
    const SHOW: usize = 60;
    for imp in info.imports.iter().take(SHOW) {
        println!("{imp}");
    }
    if info.imports.len() > SHOW {
        println!("… {} more ({} total)", info.imports.len() - SHOW, info.imports.len());
    }
    Ok(())
}

fn run_re_exports(file: &str, json: bool) -> Result<()> {
    let (info, _data) = re_analyze(file)?;
    if json {
        println!("{}", serde_json::to_string_pretty(&info.exports)?);
        return Ok(());
    }
    println!("{} exports", info.exports.len());
    println!();
    const SHOW: usize = 60;
    for exp in info.exports.iter().take(SHOW) {
        println!("{exp}");
    }
    if info.exports.len() > SHOW {
        println!("… {} more ({} total)", info.exports.len() - SHOW, info.exports.len());
    }
    Ok(())
}

/// `xencode re disasm <FILE> [--offset N | --entry] [--len N] [--arch X]`.
///
/// Default window: the entry point's section (or the first executable
/// section), capped at `DEFAULT_DISASM_BYTES`; `--offset` disassembles from
/// a file offset (base VA taken from the address map when it rounds-trips).
fn run_re_disasm(
    file: &str,
    offset: Option<u64>,
    len: u64,
    arch: Option<&str>,
    json: bool,
) -> Result<()> {
    const DEFAULT_DISASM_BYTES: u64 = 4096;
    let (info, data) = re_analyze(file)?;

    let dis_arch = match arch {
        Some(a) => xencode_re::parse_arch(a)?,
        None => xencode_re::arch_from_binary(&info)
            .with_context(|| "pass --arch to override")?,
    };

    // Window: explicit offset, else the entry point, else first exec section.
    let (off, base_va) = match offset {
        Some(o) => (o, info.offset_to_va(o).unwrap_or(0)),
        None => {
            let va = info
                .entry_va
                .or_else(|| info.exec_sections().first().and_then(|s| s.va))
                .context("no entry point and no executable section — pass --offset")?;
            (info.va_to_offset(va).with_context(|| format!("VA {va:#x} not mapped"))?, va)
        }
    };
    let take = if len > 0 {
        len
    } else {
        DEFAULT_DISASM_BYTES.min(data.len() as u64 - off)
    };
    let code = &data[off as usize..(off + take) as usize];

    let insns = xencode_re::disasm_bytes(code, base_va, dis_arch, 4096)?;
    if json {
        println!("{}", serde_json::to_string_pretty(&insns)?);
        return Ok(());
    }
    println!(
        "{} · {} disassembly @ {:#x} ({} bytes → {} instructions)",
        file,
        dis_arch,
        base_va,
        take,
        insns.len()
    );
    println!();
    for insn in &insns {
        println!("{}", xencode_re::render_insn(insn));
    }
    Ok(())
}

/// Read a whole RE input with the shared size cap.
fn re_read(file: &str) -> Result<Vec<u8>> {
    let data = std::fs::read(file).with_context(|| format!("read {file}"))?;
    if data.len() as u64 > xencode_re::MAX_INPUT_BYTES {
        bail!("{} exceeds the {} analysis cap", file, xencode_re::MAX_INPUT_BYTES);
    }
    Ok(data)
}

/// Byte size in KB/MB for the re summaries.
fn human_bytes(n: u64) -> String {
    if n >= 1024 * 1024 {
        format!("{:.1} MB", n as f64 / (1024.0 * 1024.0))
    } else if n >= 1024 {
        format!("{:.1} KB", n as f64 / 1024.0)
    } else {
        format!("{n} B")
    }
}

/// `xencode re apk <FILE>` — Android package summary: manifest identity,
/// permissions, components, dex inventory, native libs, signing.
fn run_re_apk(file: &str, json: bool) -> Result<()> {
    let data = re_read(file)?;
    if !xencode_re::is_apk(&data) {
        bail!("{file} is not an APK (zip without AndroidManifest.xml/classes*.dex)");
    }
    let info = xencode_re::analyze_apk(&data, xencode_re::ApkOpts::default())
        .with_context(|| format!("analyze {file}"))?;
    if json {
        println!("{}", serde_json::to_string_pretty(&info)?);
        return Ok(());
    }

    println!("file      {file}");
    println!("package   {}", info.package);
    if let Some(v) = &info.version_name {
        print!("version   {v}");
        if let Some(code) = info.version_code {
            print!(" (code {code})");
        }
        println!();
    }
    let sdk = |v: Option<i64>| v.map(|x| x.to_string()).unwrap_or_else(|| "-".into());
    println!(
        "sdk       min {} · target {} · compile {}",
        sdk(info.min_sdk),
        sdk(info.target_sdk),
        sdk(info.compile_sdk)
    );
    if let Some(label) = &info.app_label {
        println!("label     {label}");
    }
    if !info.permissions.is_empty() {
        println!("perms     {}", info.permissions.len());
        for p in info.permissions.iter().take(12) {
            println!("  - {p}");
        }
        if info.permissions.len() > 12 {
            println!("  … {} more", info.permissions.len() - 12);
        }
    }
    if !info.components.is_empty() {
        println!("components {}", info.components.len());
        for c in info.components.iter().take(10) {
            let mut tags = Vec::new();
            if c.launcher {
                tags.push("launcher");
            }
            if c.exported == Some(true) {
                tags.push("exported");
            }
            if c.native_methods > 0 {
                tags.push("jni");
            }
            let flags = tags.join(",");
            println!("  {:<9} {}{}", c.kind, c.name, if flags.is_empty() { String::new() } else { format!("  [{flags}]") });
        }
        if info.components.len() > 10 {
            println!("  … {} more", info.components.len() - 10);
        }
    }
    if !info.dex_files.is_empty() {
        println!("dex");
        for d in &info.dex_files {
            println!(
                "  {:<14} v{} · {} classes · {} methods · {} strings",
                d.name, d.version, d.class_count, d.method_count, d.string_count
            );
        }
    }
    if !info.abis.is_empty() {
        println!("native    {} ({})", info.native_libs.len(), info.abis.join(", "));
    }
    let s = &info.signing;
    let mut sigs = Vec::new();
    if s.v1 { sigs.push("v1"); }
    if s.v2 { sigs.push("v2"); }
    if s.v3 { sigs.push("v3"); }
    if s.v3_1 { sigs.push("v3.1"); }
    println!(
        "signing   {}",
        if sigs.is_empty() { "none detected".to_string() } else { sigs.join(" + ") }
    );
    println!(
        "entries   {} · uncompressed {}",
        info.entries,
        human_bytes(info.uncompressed_size)
    );
    Ok(())
}

/// `xencode re ipa <FILE>` — iOS bundle summary: bundle identity, embedded
/// binary (through the 7.1 analyzer), frameworks, provisioning.
fn run_re_ipa(file: &str, json: bool) -> Result<()> {
    let data = re_read(file)?;
    if !xencode_re::is_ipa(&data) {
        bail!("{file} is not an IPA (zip without Payload/*.app/Info.plist)");
    }
    let info = xencode_re::analyze_ipa(&data, xencode_re::IpaOpts::default())
        .with_context(|| format!("analyze {file}"))?;
    if json {
        println!("{}", serde_json::to_string_pretty(&info)?);
        return Ok(());
    }

    println!("file      {file}");
    println!("app dir   {}", info.app_dir);
    if let Some(id) = &info.bundle_id {
        println!("bundle    {id}");
    }
    if let Some(n) = &info.display_name {
        print!("name      {n}");
        if let (Some(v), Some(b)) = (&info.version, &info.build_version) {
            print!("  {v} (build {b})");
        }
        println!();
    }
    if let Some(min) = &info.min_os_version {
        println!("min os    {min}");
    }
    if !info.architectures.is_empty() {
        println!("arch      {}", info.architectures.join(", "));
    }
    if let Some(bin) = &info.binary {
        println!(
            "binary    {} · entry {} · {} sections · {} symbols",
            bin.arch,
            bin.entry_va.map(|v| format!("{v:#x}")).unwrap_or_else(|| "-".into()),
            bin.sections.len(),
            bin.symbols.len()
        );
    }
    if !info.frameworks.is_empty() {
        println!("frameworks {}", info.frameworks.join(", "));
    }
    if !info.plugins.is_empty() {
        println!("plugins   {}", info.plugins.join(", "));
    }
    println!(
        "signing   {} · provisioning {}",
        if info.code_signature { "code signature" } else { "none detected" },
        if info.provisioned { "embedded.mobileprovision" } else { "none" }
    );
    println!(
        "entries   {} · uncompressed {}",
        info.entries,
        human_bytes(info.uncompressed_size)
    );
    Ok(())
}

/// `xencode re decompile <FILE>` — jadx headless run + output inventory.
/// The backend is detected (PATH, then the managed install); `--download`
/// opts into fetching the latest release (~40 MB) on demand.
#[allow(clippy::too_many_arguments)]
fn run_re_decompile(
    file: &str,
    out: Option<&str>,
    download: bool,
    no_res: bool,
    deobf: bool,
    threads: u32,
    max_files: usize,
    json: bool,
) -> Result<()> {
    let data = re_read(file)?;
    if !xencode_re::is_apk(&data) {
        bail!("jadx decompiles APKs — {file} is not one (see `xencode re apk`)");
    }
    let status = xencode_re::jadx_ensure(&xencode_re::install_root(), download, xencode_re::fetch_url)?;
    if !json {
        match (&status.version, &status.path) {
            (Some(v), Some(p)) => println!("jadx      {v} ({p}{})", if status.managed { ", managed" } else { "" }),
            _ => println!("jadx      version unknown"),
        }
        if let Some(jv) = &status.java_version {
            println!("java      {jv}");
        }
    }

    let out_dir = out
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            let base = Path::new(file)
                .file_stem()
                .and_then(|s| s.to_str())
                .unwrap_or("apk");
            PathBuf::from(format!("{base}-jadx"))
        });
    let opts = xencode_re::JadxOpts { no_res, deobf, threads, max_files };
    let run = xencode_re::jadx_run(Path::new(file), &out_dir, opts, &status)?;

    if json {
        println!("{}", serde_json::to_string_pretty(&run)?);
        return Ok(());
    }
    println!(
        "decompiled {} classes across {} .java files ({} resources) in {:.1}s → exit {}",
        run.total_java_files,
        run.java_files.len().min(run.total_java_files as usize),
        run.resource_files,
        run.elapsed_ms as f64 / 1000.0,
        run.exit_code
    );
    println!("out       {}", run.out_dir);
    if !run.warnings.is_empty() {
        println!("warnings");
        for w in &run.warnings {
            println!("  {w}");
        }
    }
    println!();
    const SHOW: usize = 24;
    for f in run.java_files.iter().take(SHOW) {
        println!("  {f}");
    }
    if run.java_files.len() > SHOW {
        println!("  … {} more listed ({} total)", run.java_files.len() - SHOW, run.total_java_files);
    } else if run.total_java_files as usize > run.java_files.len() {
        println!("  … {} more (cap {})", run.total_java_files as usize - run.java_files.len(), max_files);
    }
    if run.exit_code != 0 {
        bail!("jadx exited with code {}", run.exit_code);
    }
    Ok(())
}

/// `xencode --re-ui <FILE>` — the Phase-7.1 native RE tab: analyze the real
/// binary through the XenRE core and render the hex / strings / symbols /
/// imports views into the workspace (same renderer as the live window).
/// Phase 7.2: APK/IPA inputs route to the Package view (bundle identity,
/// permission/component chips); IPAs feed the embedded Mach-O through the
/// same symbol/section pipeline.
fn re_ui(file: &str, out: &str) -> Result<()> {
    let data = re_read(file)?;
    let started = std::time::Instant::now();

    let apk = if xencode_re::is_apk(&data) {
        Some(xencode_re::analyze_apk(&data, xencode_re::ApkOpts::default())?)
    } else {
        None
    };
    let ipa = if apk.is_none() && xencode_re::is_ipa(&data) {
        Some(xencode_re::analyze_ipa(&data, xencode_re::IpaOpts::default())?)
    } else {
        None
    };

    let mut pkg_rows: Vec<ui::re::ReKv<'static>> = Vec::new();
    let mut pkg_chips: Vec<ui::re::ReChip<'static>> = Vec::new();
    let (format_label, arch_label, kind_label, pkg_size): (&str, &str, &str, u64);
    let bin_info: Option<xencode_re::BinaryInfo>;

    if let Some(a) = &apk {
        format_label = "APK";
        arch_label = Box::leak(
            if a.abis.is_empty() {
                "java".to_string()
            } else {
                a.abis.join(" / ")
            }
            .into_boxed_str(),
        );
        kind_label = "package";
        pkg_size = data.len() as u64;
        pkg_rows.push(ui::re::ReKv {
            key: "package",
            value: Box::leak(a.package.clone().into_boxed_str()),
        });
        pkg_rows.push(ui::re::ReKv {
            key: "version",
            value: Box::leak(
                match (&a.version_name, a.version_code) {
                    (Some(v), Some(c)) => format!("{v} (code {c})"),
                    (Some(v), None) => v.clone(),
                    (None, Some(c)) => format!("code {c}"),
                    (None, None) => "—".to_string(),
                }
                .into_boxed_str(),
            ),
        });
        pkg_rows.push(ui::re::ReKv {
            key: "sdk",
            value: Box::leak(
                format!(
                    "min {} · target {} · compile {}",
                    a.min_sdk.map(|v| v.to_string()).unwrap_or_else(|| "-".into()),
                    a.target_sdk.map(|v| v.to_string()).unwrap_or_else(|| "-".into()),
                    a.compile_sdk.map(|v| v.to_string()).unwrap_or_else(|| "-".into()),
                )
                .into_boxed_str(),
            ),
        });
        if let Some(label) = &a.app_label {
            pkg_rows.push(ui::re::ReKv { key: "label", value: Box::leak(label.clone().into_boxed_str()) });
        }
        for p in a.permissions.iter().take(8) {
            pkg_chips.push(ui::re::ReChip { label: Box::leak(p.clone().into_boxed_str()), tone: ui::re::ReChipTone::Subtle });
        }
        for c in a.components.iter().take(6) {
            let tags = [
                c.launcher.then_some("launcher"),
                (c.exported == Some(true)).then_some("exported"),
                (c.native_methods > 0).then_some("jni"),
            ]
            .into_iter()
            .flatten()
            .collect::<Vec<_>>()
            .join(",");
            let label = if tags.is_empty() {
                format!("{} {}", c.kind, c.name)
            } else {
                format!("{} {} [{}]", c.kind, c.name, tags)
            };
            pkg_chips.push(ui::re::ReChip { label: Box::leak(label.into_boxed_str()), tone: ui::re::ReChipTone::Sky });
        }
        for abi in &a.abis {
            pkg_chips.push(ui::re::ReChip { label: Box::leak(abi.clone().into_boxed_str()), tone: ui::re::ReChipTone::Green });
        }
        for d in a.dex_files.iter().take(4) {
            let label = format!("{}: {} classes / {} methods", d.name, d.class_count, d.method_count);
            pkg_chips.push(ui::re::ReChip { label: Box::leak(label.into_boxed_str()), tone: ui::re::ReChipTone::Subtle });
        }
        let mut sig = Vec::new();
        if a.signing.v1 { sig.push("v1"); }
        if a.signing.v2 { sig.push("v2"); }
        if a.signing.v3 { sig.push("v3"); }
        if a.signing.v3_1 { sig.push("v3.1"); }
        if !sig.is_empty() {
            pkg_chips.push(ui::re::ReChip { label: Box::leak(format!("signed {}", sig.join("+")).into_boxed_str()), tone: ui::re::ReChipTone::Green });
        }
        bin_info = None;
    } else if let Some(i) = &ipa {
        format_label = "IPA";
        arch_label = Box::leak(
            if i.architectures.is_empty() {
                "universal".to_string()
            } else {
                i.architectures.join(" / ")
            }
            .into_boxed_str(),
        );
        kind_label = "package";
        pkg_size = data.len() as u64;
        pkg_rows.push(ui::re::ReKv { key: "bundle", value: Box::leak(i.bundle_id.clone().unwrap_or_default().into_boxed_str()) });
        pkg_rows.push(ui::re::ReKv {
            key: "version",
            value: Box::leak(
                match (&i.version, &i.build_version) {
                    (Some(v), Some(b)) => format!("{v} (build {b})"),
                    (Some(v), None) => v.clone(),
                    _ => "—".to_string(),
                }
                .into_boxed_str(),
            ),
        });
        if let Some(n) = &i.display_name {
            pkg_rows.push(ui::re::ReKv { key: "name", value: Box::leak(n.clone().into_boxed_str()) });
        }
        if let Some(min) = &i.min_os_version {
            pkg_rows.push(ui::re::ReKv { key: "min os", value: Box::leak(min.clone().into_boxed_str()) });
        }
        for fw in i.frameworks.iter().take(6) {
            pkg_chips.push(ui::re::ReChip { label: Box::leak(fw.clone().into_boxed_str()), tone: ui::re::ReChipTone::Sky });
        }
        for pl in i.plugins.iter().take(4) {
            pkg_chips.push(ui::re::ReChip { label: Box::leak(pl.clone().into_boxed_str()), tone: ui::re::ReChipTone::Sky });
        }
        if i.provisioned {
            pkg_chips.push(ui::re::ReChip { label: "provisioned", tone: ui::re::ReChipTone::Green });
        }
        if i.code_signature {
            pkg_chips.push(ui::re::ReChip { label: "code signature", tone: ui::re::ReChipTone::Green });
        }
        bin_info = i.binary.clone();
    } else {
        format_label = "";
        arch_label = "";
        kind_label = "";
        pkg_size = data.len() as u64;
        bin_info = Some(xencode_re::analyze_file(file)?);
    }

    render_re_tab(
        file,
        out,
        bin_info,
        &data,
        started,
        pkg_rows,
        pkg_chips,
        format_label,
        arch_label,
        kind_label,
        pkg_size,
    )
}

/// Render the RE tab canvas from a prepared package/bin state.
#[allow(clippy::too_many_arguments)]
fn render_re_tab(
    file: &str,
    out: &str,
    bin_info: Option<xencode_re::BinaryInfo>,
    data: &[u8],
    started: std::time::Instant,
    pkg_rows: Vec<ui::re::ReKv<'static>>,
    pkg_chips: Vec<ui::re::ReChip<'static>>,
    format_label: &str,
    arch_label: &str,
    kind_label: &str,
    size: u64,
) -> Result<()> {
    // Hex: 12 rows starting at the entry point (or the first exec section);
    // packages fall back to the first rows of the archive.
    let hex_start_off = bin_info
        .as_ref()
        .and_then(|info| {
            info.entry_va
                .and_then(|va| info.va_to_offset(va))
                .or_else(|| info.exec_sections().first().map(|s| s.file_off))
        })
        .unwrap_or(0);
    let hex_take = ((12 * xencode_re::DEFAULT_ROW_WIDTH) as u64)
        .min(data.len() as u64 - hex_start_off);
    let hex_rows: Vec<String> = if hex_take > 0 {
        let slice = &data
            [hex_start_off as usize..(hex_start_off + hex_take) as usize];
        xencode_re::hex_rows(slice, hex_start_off, xencode_re::DEFAULT_ROW_WIDTH)
            .iter()
            .map(|r| xencode_re::render_hex_row(r, xencode_re::DEFAULT_ROW_WIDTH))
            .collect()
    } else {
        Vec::new()
    };

    // Strings: first 14 runs ≥ 5 bytes across the whole image.
    let strings: Vec<ui::re::ReString<'_>> = xencode_re::extract_strings(data, 5)
        .into_iter()
        .take(14)
        .map(|s| ui::re::ReString {
            offset: s.offset,
            text: Box::leak(s.text.into_boxed_str()),
            utf16: s.encoding == xencode_re::StrEnc::Utf16Le,
        })
        .collect();

    // Symbols/imports/sections/functions exist only for native images.
    let empty_syms: Vec<ui::re::ReSym<'_>> = Vec::new();
    let empty_imports: Vec<ui::re::ReImport<'_>> = Vec::new();
    let empty_sections: Vec<ui::re::ReSection<'_>> = Vec::new();
    let empty_fns: Vec<ui::re::ReFunction<'_>> = Vec::new();

    let symbols: Vec<ui::re::ReSym<'_>> = if let Some(info) = bin_info.as_ref() {
        let mut syms: Vec<&xencode_re::Symbol> = info
            .symbols
            .iter()
            .filter(|s| s.defined && !s.name.is_empty())
            .collect();
        syms.sort_by_key(|s| std::cmp::Reverse(s.kind == xencode_re::SymbolKind::Func));
        syms.iter()
            .take(12)
            .map(|s| ui::re::ReSym {
                va: s.va,
                name: Box::leak(s.name.clone().into_boxed_str()),
                kind: match s.kind {
                    xencode_re::SymbolKind::Func => "Func",
                    xencode_re::SymbolKind::Object => "Data",
                    _ => "Sym",
                },
            })
            .collect()
    } else {
        empty_syms
    };

    let imports: Vec<ui::re::ReImport<'_>> = match bin_info.as_ref() {
        Some(info) => info
            .imports
            .iter()
            .take(10)
            .map(|i| ui::re::ReImport {
                dll: Box::leak(i.dll.clone().unwrap_or_default().into_boxed_str()),
                name: Box::leak(i.name.clone().into_boxed_str()),
            })
            .collect(),
        None => empty_imports,
    };

    let sections: Vec<ui::re::ReSection<'_>> = match bin_info.as_ref() {
        Some(info) => info
            .sections
            .iter()
            .filter(|s| s.size > 0)
            .take(8)
            .map(|s| ui::re::ReSection {
                name: Box::leak(s.name.clone().into_boxed_str()),
                va: s.va,
                size: s.size,
                exec: s.flags.exec,
            })
            .collect(),
        None => empty_sections,
    };

    let functions: Vec<ui::re::ReFunction<'_>> = match bin_info.as_ref() {
        Some(info) => xencode_re::functions(info)
            .into_iter()
            .take(9)
            .map(|f| ui::re::ReFunction {
                va: f.va,
                name: Box::leak(f.name.into_boxed_str()),
            })
            .collect(),
        None => empty_fns,
    };

    let (fmt_s, arch_s, kind_s, entry_s) = match bin_info.as_ref() {
        Some(info) => (
            info.format.to_string(),
            info.arch.to_string(),
            info.kind.to_string(),
            info.entry_va
                .map(|v| format!("{v:#x}"))
                .unwrap_or_else(|| "—".into()),
        ),
        None => (
            format_label.to_string(),
            arch_label.to_string(),
            kind_label.to_string(),
            "—".to_string(),
        ),
    };
    let status = if bin_info.is_some() {
        format!(
            "{} · {} · {} sections · {} symbols · {} imports · analyzed in {}ms",
            fmt_s,
            kind_s,
            sections.len(),
            bin_info.as_ref().map(|i| i.symbols.len()).unwrap_or(0),
            imports.len(),
            started.elapsed().as_millis()
        )
    } else {
        format!(
            "{} · {} · {} entries · analyzed in {}ms",
            fmt_s,
            kind_s,
            pkg_chips.len() + pkg_rows.len(),
            started.elapsed().as_millis()
        )
    };

    let page = ui::re::RePage {
        file,
        format: Box::leak(fmt_s.into_boxed_str()),
        arch: Box::leak(arch_s.into_boxed_str()),
        kind: Box::leak(kind_s.into_boxed_str()),
        entry: Box::leak(entry_s.into_boxed_str()),
        size,
        // Packages open on the bundle summary; binaries on hex.
        tab: if pkg_rows.is_empty() {
            ui::re::ReTab::Hex
        } else {
            ui::re::ReTab::Package
        },
        hex: Box::leak(hex_rows.into_boxed_slice()),
        strings: Box::leak(strings.into_boxed_slice()),
        symbols: Box::leak(symbols.into_boxed_slice()),
        imports: Box::leak(imports.into_boxed_slice()),
        sections: Box::leak(sections.into_boxed_slice()),
        functions: Box::leak(functions.into_boxed_slice()),
        package_rows: Box::leak(pkg_rows.into_boxed_slice()),
        package_chips: Box::leak(pkg_chips.into_boxed_slice()),
        status: Box::leak(status.into_boxed_str()),
    };

    let mut c = Canvas::new(WIN_W, WIN_H, SCALE);
    draw_backdrop(&mut c);
    let panel = panel_rect();
    draw_panel(&mut c, panel);
    draw_top_overlay(&mut c);
    let base = std::path::Path::new(file)
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or(file)
        .to_string();
    draw_header(
        &mut c,
        panel,
        &HeaderTitle::Task {
            project: "xencode",
            title: "Binary inspector",
            summary: Some(&base),
        },
        false,
        false,
    );
    ui::sidebar::draw(&mut c);
    draw_sidebar_footer(&mut c);
    let area = Rect::new(panel.x, panel.y + HEADER_H, panel.w, panel.h - HEADER_H);
    ui::re::draw(&mut c, area, &page);

    if let Some(parent) = std::path::Path::new(out).parent() {
        std::fs::create_dir_all(parent)?;
    }
    c.px.save_png(out).map_err(|e| anyhow::anyhow!("save {out}: {e:?}"))?;
    println!("rendered {out}");
    Ok(())
}
