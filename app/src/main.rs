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
mod browser_driver;
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
    // Phase 6.3 -----------------------------------------------------------
    /// `xencode browser record <url> --out FILE` — record DOM interactions
    /// into a QuickJS replay script (`xen.browser` surface).
    BrowserRecord { url: String, opts: RecordOptions },
    /// `xencode browser run <script.ts>` — run a browser automation script
    /// through the SpaceDriver bridge.
    BrowserRun { script: String, opts: BrowserRunOptions },
    // Phase 6.4 -----------------------------------------------------------
    /// `xencode scrape <url> [--urls FILE] ...` — structured extraction
    /// (single page or a parallel headless fleet) with JSON/CSV/MD exports.
    Scrape { args: ScrapeArgs },
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
    // Phase 7.3 -------------------------------------------------------------
    /// `xencode re xex <FILE> [--json] [--extract DIR]` — Xbox 360 XEX
    /// container summary; --extract materializes the unencrypted flat image.
    ReXex { file: String, json: bool, extract: Option<String> },
    /// `xencode re ghidra <FILE> [--out DIR] [--download] [--max-files N]
    /// [--max-fns N] [--timeout-sec N] [--lang ID] [--json]` — Ghidra
    /// headless analysis + decompiled-C export inventory.
    ReGhidra {
        file: String,
        out: Option<String>,
        download: bool,
        max_files: usize,
        max_fns: usize,
        timeout_sec: usize,
        lang: Option<String>,
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

/// `xencode browser record` options (Phase 6.3).
#[derive(Debug, Clone)]
struct RecordOptions {
    /// Write the emitted QuickJS script here.
    out: String,
    /// Keep password fills verbatim (default: redacted).
    no_redact: bool,
    /// Headless recording (default: headed — a human drives).
    headless: bool,
    /// Recording window cap (ms) — Ctrl-C or browser close ends earlier.
    timeout_ms: u64,
    browser_path: Option<String>,
    /// `clean` (default) or `inherit=<profile-path>`.
    space: String,
}

/// `xencode browser run` options (Phase 6.3).
#[derive(Debug, Clone)]
struct BrowserRunOptions {
    headless: bool,
    browser_path: Option<String>,
    /// `clean` (default) or `inherit=<profile-path>`.
    space: String,
    /// Script deadline (ms); 0 = no deadline.
    timeout_ms: u64,
    json: bool,
    /// Auto-allow all permission prompts (CI; also implied when stdin is
    /// not a terminal).
    yes: bool,
}

/// `xencode scrape` options (Phase 6.4).
#[derive(Debug, Default, Clone)]
struct ScrapeArgs {
    url: Option<String>,
    /// Fleet mode: one URL per line (`#` comments allowed).
    urls_file: Option<String>,
    item: Option<String>,
    fields: Vec<String>,
    page_fields: Vec<String>,
    next: Option<String>,
    max_pages: u32,
    scroll_rounds: u32,
    scroll_dy: f64,
    wait_ms: u64,
    /// json | csv | md (default json).
    format: String,
    out: Option<String>,
    /// Summary envelope on stdout instead of raw rows.
    json: bool,
    workers: u32,
    rps: Option<f64>,
    headless: bool,
    browser_path: Option<String>,
    space: String,
    timeout_ms: u64,
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
        Mode::BrowserRecord { url, opts } => run_browser_record(&url, &opts),
        Mode::BrowserRun { script, opts } => run_browser_run(&script, &opts),
        Mode::Scrape { args } => run_scrape(&args),
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
        Mode::ReXex { file, json, extract } => run_re_xex(&file, json, extract.as_deref()),
        Mode::ReGhidra {
            file,
            out,
            download,
            max_files,
            max_fns,
            timeout_sec,
            lang,
            json,
        } => run_re_ghidra(
            &file,
            out.as_deref(),
            download,
            max_files,
            max_fns,
            timeout_sec,
            lang.as_deref(),
            json,
        ),
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
    // `xencode browser <url> [flags]` / `browser record|run` — Phase 6.
    if args.first().map(|s| s.as_str()) == Some("browser") {
        return parse_browser(&args[1..]);
    }
    // `xencode scrape <url|urls-file> [flags]` — Phase 6.4.
    if args.first().map(|s| s.as_str()) == Some("scrape") {
        return parse_scrape(&args[1..]);
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
    // Phase 6.3 subcommands: `browser record <url> …` / `browser run <file> …`.
    match args.first().map(|s| s.as_str()) {
        Some("record") => return parse_browser_record(&args[1..]),
        Some("run") => return parse_browser_run(&args[1..]),
        _ => {}
    }
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

/// `xencode browser record <url> --out FILE [flags]` — Phase 6.3.
fn parse_browser_record(args: &[String]) -> Result<Mode> {
    let mut opts = RecordOptions {
        out: String::new(),
        no_redact: false,
        headless: false,
        timeout_ms: 600_000,
        browser_path: None,
        space: "clean".into(),
    };
    let mut url: Option<String> = None;
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "--out" if i + 1 < args.len() => {
                opts.out = args[i + 1].clone();
                i += 1;
            }
            "--no-redact" => opts.no_redact = true,
            "--headless" => opts.headless = true,
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
            other if other.starts_with('-') => bail!("unknown browser record flag: {other}"),
            other if url.is_none() => url = Some(other.to_string()),
            other => bail!("unexpected browser record argument: {other}"),
        }
        i += 1;
    }
    let Some(url) = url else {
        bail!(
            "usage: xencode browser record <url> --out FILE [--no-redact] [--headless] \
             [--timeout-ms N] [--space clean|inherit=PATH] [--browser-path BIN]"
        );
    };
    if opts.out.is_empty() {
        bail!("browser record needs --out FILE (the QuickJS replay script to write)");
    }
    Ok(Mode::BrowserRecord { url, opts })
}

/// `xencode browser run <script.ts> [flags]` — Phase 6.3.
fn parse_browser_run(args: &[String]) -> Result<Mode> {
    let mut opts = BrowserRunOptions {
        headless: true,
        browser_path: None,
        space: "clean".into(),
        timeout_ms: 120_000,
        json: false,
        yes: false,
    };
    let mut script: Option<String> = None;
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "--headed" => opts.headless = false,
            "--json" => opts.json = true,
            "--yes" => opts.yes = true,
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
            other if other.starts_with('-') => bail!("unknown browser run flag: {other}"),
            other if script.is_none() => script = Some(other.to_string()),
            other => bail!("unexpected browser run argument: {other}"),
        }
        i += 1;
    }
    let Some(script) = script else {
        bail!(
            "usage: xencode browser run <script.ts> [--headed] [--json] [--yes] [--timeout-ms N] \
             [--space clean|inherit=PATH] [--browser-path BIN]"
        );
    };
    Ok(Mode::BrowserRun { script, opts })
}

/// `xencode scrape <url> [flags]` — Phase 6.4.
fn parse_scrape(args: &[String]) -> Result<Mode> {
    let mut a = ScrapeArgs {
        format: "json".into(),
        max_pages: 1,
        scroll_dy: 900.0,
        wait_ms: 600,
        workers: 4,
        timeout_ms: 30_000,
        headless: true,
        space: "clean".into(),
        ..Default::default()
    };
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "--urls" if i + 1 < args.len() => {
                a.urls_file = Some(args[i + 1].clone());
                i += 1;
            }
            "--item" if i + 1 < args.len() => {
                a.item = Some(args[i + 1].clone());
                i += 1;
            }
            "--field" if i + 1 < args.len() => {
                a.fields.push(args[i + 1].clone());
                i += 1;
            }
            "--page-field" if i + 1 < args.len() => {
                a.page_fields.push(args[i + 1].clone());
                i += 1;
            }
            "--next" if i + 1 < args.len() => {
                a.next = Some(args[i + 1].clone());
                i += 1;
            }
            "--max-pages" if i + 1 < args.len() => {
                a.max_pages = args[i + 1].parse().context("--max-pages expects a number")?;
                i += 1;
            }
            "--scroll" if i + 1 < args.len() => {
                a.scroll_rounds = args[i + 1].parse().context("--scroll expects a round count")?;
                i += 1;
            }
            "--scroll-dy" if i + 1 < args.len() => {
                a.scroll_dy = args[i + 1].parse().context("--scroll-dy expects a number")?;
                i += 1;
            }
            "--wait" if i + 1 < args.len() => {
                a.wait_ms = args[i + 1].parse().context("--wait expects milliseconds")?;
                i += 1;
            }
            "--format" if i + 1 < args.len() => {
                let f = args[i + 1].to_ascii_lowercase();
                if !matches!(f.as_str(), "json" | "csv" | "md" | "markdown") {
                    bail!("--format expects json|csv|md, got `{}`", args[i + 1]);
                }
                a.format = f;
                i += 1;
            }
            "--out" if i + 1 < args.len() => {
                a.out = Some(args[i + 1].clone());
                i += 1;
            }
            "--json" => a.json = true,
            "--workers" if i + 1 < args.len() => {
                a.workers = args[i + 1].parse().context("--workers expects a number")?;
                i += 1;
            }
            "--rps" if i + 1 < args.len() => {
                a.rps = Some(args[i + 1].parse().context("--rps expects a number")?);
                i += 1;
            }
            "--headless" => a.headless = true,
            "--headed" => a.headless = false,
            "--browser-path" if i + 1 < args.len() => {
                a.browser_path = Some(args[i + 1].clone());
                i += 1;
            }
            "--space" if i + 1 < args.len() => {
                a.space = args[i + 1].clone();
                i += 1;
            }
            "--timeout-ms" if i + 1 < args.len() => {
                a.timeout_ms = args[i + 1].parse().context("--timeout-ms expects a number")?;
                i += 1;
            }
            other if other.starts_with('-') => bail!("unknown scrape flag: {other}"),
            other if a.url.is_none() => a.url = Some(other.to_string()),
            other => bail!("unexpected scrape argument: {other}"),
        }
        i += 1;
    }
    if a.url.is_none() && a.urls_file.is_none() {
        bail!(
            "usage: xencode scrape <url> [--item SEL] [--field NAME=SEL[@ATTR]] ... \
             [--page-field NAME=SEL[@ATTR]] ... [--next SEL] [--max-pages N] [--scroll N] \
             [--format json|csv|md] [--out FILE] [--json] | --urls FILE [--workers N] [--rps F]"
        );
    }
    Ok(Mode::Scrape { args: a })
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

/// `xencode browser record <url> --out FILE` — Phase 6.3: open a Space,
/// install the DOM-event recorder, let a human (or the action runner) drive,
/// and emit a QuickJS replay script for the `xen.browser` surface.
fn run_browser_record(url: &str, opts: &RecordOptions) -> Result<()> {
    use xencode_browser::{Recorder, RecordMeta, RecordOptions as RecOpts, Space, SpaceKind, SpaceOptions};

    let kind = if let Some(profile) = opts.space.strip_prefix("inherit=") {
        SpaceKind::Inherit { profile: profile.into() }
    } else {
        SpaceKind::CleanRoom
    };
    let rt = tokio::runtime::Runtime::new().context("start tokio runtime")?;
    rt.block_on(async move {
        let space = Space::launch(SpaceOptions {
            kind,
            headless: opts.headless,
            browser_path: opts.browser_path.clone().map(std::path::PathBuf::from),
            ..Default::default()
        })
        .await
        .context("launch browser space")?;

        let recorder = Recorder::start(
            &space,
            RecOpts { redact_passwords: !opts.no_redact, record_scroll: true },
        )
        .await
        .context("install recorder")?;
        space.navigate(url).await.context("navigate")?;

        let mode = if opts.headless { "headless" } else { "headed" };
        eprintln!("· recording interactions on {url} ({mode}) — Ctrl-C or close the browser to finish");
        eprintln!("· window cap: {} ms", opts.timeout_ms);

        // Recording window: Ctrl-C, the timeout, or the browser dying.
        let deadline = tokio::time::Instant::now()
            + std::time::Duration::from_millis(opts.timeout_ms.max(1_000));
        let mut events = space.events_resubscribe();
        let mut ctrl_c = std::pin::pin!(tokio::signal::ctrl_c());
        let mut connection_alive = true;
        loop {
            tokio::select! {
                biased;
                _ = &mut ctrl_c => {
                    eprintln!("· ctrl-c — stopping the recorder");
                    break;
                }
                _ = tokio::time::sleep_until(deadline) => {
                    eprintln!("· recording window elapsed");
                    break;
                }
                ev = events.recv() => {
                    match ev {
                        Err(_) => {
                            eprintln!("· browser connection closed");
                            connection_alive = false;
                            break;
                        }
                        Ok(_) => continue,
                    }
                }
            }
        }
        let _ = connection_alive;

        let (steps, truncated) = recorder
            .stop()
            .await
            .context("collect recording")?;
        let redacted = steps
            .iter()
            .filter(|s| matches!(s, xencode_browser::RecStep::Fill { redacted: true, .. }))
            .count();
        let meta = RecordMeta {
            start_url: url.to_string(),
            recorded_at: utc_timestamp(),
            redacted_count: redacted,
        };
        let script = xencode_browser::to_quickjs_script(&steps, &meta);
        if let Some(parent) = std::path::Path::new(&opts.out).parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::write(&opts.out, &script)
            .with_context(|| format!("write {}", opts.out))?;

        if truncated {
            eprintln!("· recording truncated at the 500-step cap");
        }
        eprintln!(
            "· recorded {} steps → {} ({} password field(s) redacted)",
            steps.len(),
            opts.out,
            redacted
        );
        eprintln!("· replay: xencode browser run {}", opts.out);
        Ok(())
    })
}

/// `xencode browser run <script.ts>` — Phase 6.3: run a QuickJS automation
/// script against a real Space through the `xen.browser` bridge. Permissions
/// come from `xen.toml` (walking parents) with the stdin consent gate, same
/// contract as `xen-script run`.
fn run_browser_run(script: &str, opts: &BrowserRunOptions) -> Result<()> {
    use xencode_browser::SpaceKind;
    use xencode_script::config::XenConfig;
    use xencode_script::permissions::{PermissionState, PromptBridge};
    use xencode_script::runtime::{RuntimeOptions, ScriptRuntime};

    let script_path = PathBuf::from(script);
    if !script_path.exists() {
        bail!("script not found: {script}");
    }
    let script_dir = script_path
        .parent()
        .map(|p| p.to_path_buf())
        .unwrap_or_else(|| std::env::current_dir().unwrap_or_default());
    let (cfg, config_path) = XenConfig::discover(&script_dir)?;
    if config_path.is_file() {
        eprintln!("· using {}", config_path.display());
    }

    // stdin consent (y/n/a) — the CLI asker, as in `xen-script run`.
    // `--yes` (or a non-terminal stdin, e.g. CI) session-allows everything.
    use std::io::IsTerminal as _;
    let yes = opts.yes || !std::io::stdin().is_terminal();
    let bridge = PromptBridge::new();
    bridge.set(std::boxed::Box::new(move |prompt: &xencode_script::permissions::PermissionPrompt| {
        use xencode_script::permissions::Decision;
        if yes {
            return Decision::AllowSession;
        }
        eprintln!("? {} access to `{}`? [y]es / [n]o / [a]lways: ", prompt.kind, prompt.detail);
        let mut line = String::new();
        if std::io::stdin().read_line(&mut line).is_err() {
            return Decision::Deny;
        }
        match line.trim().to_ascii_lowercase().as_str() {
            "a" | "always" => Decision::AllowSession,
            "y" | "yes" => Decision::AllowOnce,
            _ => Decision::Deny,
        }
    }));
    let project_root = std::env::current_dir().unwrap_or_else(|_| script_dir.clone());
    let perms = PermissionState::new(cfg.permissions.clone(), project_root, bridge);

    let kind = if let Some(profile) = opts.space.strip_prefix("inherit=") {
        SpaceKind::Inherit { profile: profile.into() }
    } else {
        SpaceKind::CleanRoom
    };
    let started = std::time::Instant::now();
    let rt = tokio::runtime::Runtime::new().context("start tokio runtime")?;
    let driver = browser_driver::SpaceDriver::new(
        rt.handle().clone(),
        browser_driver::SpaceLaunchConfig {
            kind,
            headless: opts.headless,
            browser_path: opts.browser_path.clone().map(std::path::PathBuf::from),
        },
    );

    let script_file = script_path.clone();
    let timeout = if opts.timeout_ms > 0 {
        Some(std::time::Duration::from_millis(opts.timeout_ms))
    } else {
        None
    };
    let result = rt.block_on(async move {
        // QuickJS values are !Send — dedicated thread + current-thread
        // runtime + LocalSet, mirroring the hook-runner pattern. The driver
        // bounces Space work onto the multi-thread runtime via its Handle.
        let outcome = tokio::task::spawn_blocking(move || {
            let script_file = script_file.clone();
            std::thread::Builder::new()
                .name("xen-browser-run".into())
                .spawn(move || {
                    let inner = tokio::runtime::Builder::new_current_thread()
                        .enable_all()
                        .build()?;
                    let local = tokio::task::LocalSet::new();
                    local.block_on(&inner, async move {
                        let (tx, mut rx) = xencode_script::events::channel();
                        // Stream UI events (console) to stderr while running.
                        let pump = tokio::task::spawn_local(async move {
                            while let Some(ev) = rx.recv().await {
                                if let Some(line) = xencode_script::events::format_cli(&ev) {
                                    eprintln!("{line}");
                                }
                            }
                        });
                        let mut ropts = RuntimeOptions::new(&script_dir, perms, tx);
                        ropts.browser = Some(std::sync::Arc::new(driver) as _);
                        ropts.timeout = timeout;
                        let srt = ScriptRuntime::new(ropts).await?;
                        let res = srt.run_script(&script_file, &serde_json::Value::Null).await;
                        srt.shutdown().await;
                        pump.abort();
                        res
                    })
                })?
                .join()
                .map_err(|_| std::io::Error::other("browser-run thread panicked"))?
        })
        .await
        .map_err(|e| anyhow::anyhow!("browser-run task failed: {e}"))?;
        outcome
    });

    let ms = started.elapsed().as_millis() as u64;
    match result {
        Ok(v) => {
            if opts.json {
                println!(
                    "{}",
                    serde_json::json!({ "ok": true, "result": v, "ms": ms })
                );
            } else {
                let line = serde_json::to_string_pretty(&v).unwrap_or_else(|_| "null".into());
                println!("{line}");
                eprintln!("· script finished in {ms} ms");
            }
            Ok(())
        }
        Err(e) => {
            if opts.json {
                println!(
                    "{}",
                    serde_json::json!({ "ok": false, "error": format!("{e:#}"), "ms": ms })
                );
            }
            Err(anyhow::anyhow!("script failed: {e:#}"))
        }
    }
}

/// `xencode scrape` — Phase 6.4: structured extraction on one page, or a
/// parallel headless fleet over a URL list, with JSON/CSV/Markdown exports.
fn run_scrape(args: &ScrapeArgs) -> Result<()> {
    use xencode_browser::{
        parse_url_list, run_fleet, scrape, to_csv, to_json, to_markdown, FieldSpec, FleetOptions,
        ScrapeSpec, Space, SpaceKind, SpaceOptions,
    };

    let mut spec = ScrapeSpec {
        item: args.item.clone(),
        next: args.next.clone(),
        max_pages: args.max_pages.max(1),
        scroll_rounds: args.scroll_rounds,
        scroll_dy: args.scroll_dy,
        wait_ms: args.wait_ms,
        ..Default::default()
    };
    for f in &args.fields {
        spec.fields.push(FieldSpec::parse(f).with_context(|| format!("--field {f}"))?);
    }
    for f in &args.page_fields {
        spec.page_fields.push(FieldSpec::parse(f).with_context(|| format!("--page-field {f}"))?);
    }
    spec.validate().context("scrape spec")?;

    let kind = if let Some(profile) = args.space.strip_prefix("inherit=") {
        SpaceKind::Inherit { profile: profile.into() }
    } else {
        SpaceKind::CleanRoom
    };
    let browser_path = args.browser_path.clone().map(std::path::PathBuf::from);
    let rt = tokio::runtime::Runtime::new().context("start tokio runtime")?;

    let (pages, ms, errors) = rt.block_on(async move {
        let started = std::time::Instant::now();
        match (&args.urls_file, &args.url) {
            (Some(file), _) => {
                let text = std::fs::read_to_string(file)
                    .with_context(|| format!("read {}", file))?;
                let urls = parse_url_list(&text);
                if urls.is_empty() {
                    bail!("url list is empty: {file}");
                }
                eprintln!(
                    "· fleet: {} urls, {} workers{}",
                    urls.len(),
                    args.workers.max(1),
                    args.rps
                        .map(|r| format!(", {} rps cap", r))
                        .unwrap_or_default()
                );
                let results = run_fleet(
                    urls,
                    spec,
                    FleetOptions {
                        workers: args.workers,
                        rps: args.rps,
                        timeout_ms: args.timeout_ms,
                        browser_path,
                        headless: args.headless,
                        kind,
                    },
                )
                .await?;
                let mut pages = Vec::new();
                let mut errors = Vec::new();
                for r in &results {
                    if let Some(e) = &r.error {
                        errors.push(format!("✗ {}: {e}", r.url));
                    } else if !r.ok {
                        errors.push(format!("✗ {}: failed", r.url));
                    }
                    pages.extend(r.pages.clone());
                }
                let ms = started.elapsed().as_millis() as u64;
                Ok((pages, ms, errors))
            }
            (None, Some(url)) => {
                let space = Space::launch(SpaceOptions {
                    kind,
                    headless: args.headless,
                    browser_path,
                    ..Default::default()
                })
                .await
                .context("launch browser space")?;
                let nav = tokio::time::timeout(
                    std::time::Duration::from_millis(args.timeout_ms.max(1_000)),
                    space.navigate(url),
                )
                .await;
                match nav {
                    Err(_) => bail!("navigate timed out after {}ms", args.timeout_ms),
                    Ok(Err(e)) => return Err(anyhow::anyhow!("{e:#}")),
                    Ok(Ok(())) => {}
                }
                let pages = scrape(&space, &spec)
                    .await
                    .context("scrape failed")?;
                let ms = started.elapsed().as_millis() as u64;
                Ok((pages, ms, Vec::new()))
            }
            (None, None) => bail!("scrape needs a URL or --urls FILE"),
        }
    })?;

    let rows: Vec<serde_json::Map<String, serde_json::Value>> =
        pages.iter().flat_map(|p| p.rows.clone()).collect();
    let body = match args.format.as_str() {
        "csv" => to_csv(&rows),
        "md" | "markdown" => to_markdown(&rows),
        _ => to_json(&rows),
    };
    match &args.out {
        Some(path) => {
            if let Some(parent) = std::path::Path::new(path).parent() {
                std::fs::create_dir_all(parent)?;
            }
            std::fs::write(path, &body).with_context(|| format!("write {path}"))?;
            eprintln!("· wrote {} rows → {}", rows.len(), path);
        }
        None if args.json => {}
        None => print!("{body}"),
    }

    for e in &errors {
        eprintln!("{e}");
    }
    if args.json {
        let summary = serde_json::json!({
            "pages": pages.len(),
            "rows": rows.len(),
            "ms": ms,
            "failed": errors.len(),
            "urls": pages.iter().map(|p| p.url.clone()).collect::<Vec<_>>(),
            "data": rows,
        });
        println!("{}", serde_json::to_string_pretty(&summary)?);
    } else {
        eprintln!("· {} page(s), {} row(s) in {} ms", pages.len(), rows.len(), ms);
    }
    if args.urls_file.is_some() && !errors.is_empty() {
        // fleet: per-URL failures don't fail the run (results are partial),
        // but surface the count in the exit code semantics used by `api
        // diff`? No — fleet failures are data, not alerts. Stay 0.
        eprintln!("· fleet finished with {} failed url(s)", errors.len());
    }
    Ok(())
}

/// UTC timestamp for the recording header (`YYYY-MM-DD HH:MMZ`).
fn utc_timestamp() -> String {
    let secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0);
    let days = (secs / 86_400) as i64;
    let rem = secs % 86_400;
    // civil-from-days (Howard Hinnant's algorithm)
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = if m <= 2 { y + 1 } else { y };
    format!("{y:04}-{m:02}-{d:02} {:02}:{:02}Z", rem / 3_600, (rem % 3_600) / 60)
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
             decompile <FILE> | xex <FILE> | ghidra <FILE>"
        );
    };
    let rest = &args[1..];
    match cmd.as_str() {
        "xex" => {
            let mut file = None;
            let mut extract = None;
            let mut json = false;
            let mut i = 0;
            while i < rest.len() {
                match rest[i].as_str() {
                    "--extract" if i + 1 < rest.len() => {
                        extract = Some(rest[i + 1].clone());
                        i += 2;
                    }
                    "--json" => {
                        json = true;
                        i += 1;
                    }
                    other if other.starts_with('-') => bail!("unknown re xex flag: {other}"),
                    other if file.is_none() => {
                        file = Some(other.to_string());
                        i += 1;
                    }
                    other => bail!("unexpected re xex argument: {other}"),
                }
            }
            Ok(Mode::ReXex {
                file: re_file(file, "re xex")?,
                json,
                extract,
            })
        }
        "ghidra" => {
            let mut file = None;
            let mut out = None;
            let mut download = false;
            let mut max_files = xencode_re::GhidraOpts::DEFAULT_MAX_FILES;
            let mut max_fns = xencode_re::GhidraOpts::DEFAULT_MAX_FUNCTIONS;
            let mut timeout_sec = 0usize;
            let mut lang = None;
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
                    "--max-files" if i + 1 < rest.len() => {
                        max_files = rest[i + 1]
                            .parse()
                            .context("--max-files expects a number")?;
                        i += 2;
                    }
                    "--max-fns" if i + 1 < rest.len() => {
                        max_fns = rest[i + 1]
                            .parse()
                            .context("--max-fns expects a number")?;
                        i += 2;
                    }
                    "--timeout-sec" if i + 1 < rest.len() => {
                        timeout_sec = rest[i + 1]
                            .parse()
                            .context("--timeout-sec expects a number of seconds")?;
                        i += 2;
                    }
                    "--lang" if i + 1 < rest.len() => {
                        lang = Some(rest[i + 1].clone());
                        i += 2;
                    }
                    "--json" => {
                        json = true;
                        i += 1;
                    }
                    other if other.starts_with('-') => bail!("unknown re ghidra flag: {other}"),
                    other if file.is_none() => {
                        file = Some(other.to_string());
                        i += 1;
                    }
                    other => bail!("unexpected re ghidra argument: {other}"),
                }
            }
            Ok(Mode::ReGhidra {
                file: re_file(file, "re ghidra")?,
                out,
                download,
                max_files,
                max_fns,
                timeout_sec,
                lang,
                json,
            })
        }
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
    // XEX containers: byte views (strings/disasm) run on the decompressed
    // flat image, whose offsets the BinaryInfo's va↔offset map already uses.
    // Encrypted/LZX containers are refused here with the compliance message.
    if info.format == xencode_re::Format::Xex {
        let (img_info, image) = xencode_re::xex_payload_image(&data)?;
        return Ok((img_info, image));
    }
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

/// `xencode re xex <FILE>` — Xbox 360 XEX container summary: magic, module
/// flags, encryption/compression, execution info, section table, resources,
/// import/static libraries. `--extract DIR` materializes the unencrypted
/// flat image plus one file per section (encrypted/LZX containers refuse
/// with the compliance message — headers stay readable either way).
fn run_re_xex(file: &str, json: bool, extract: Option<&str>) -> Result<()> {
    let data = re_read(file)?;
    let meta = xencode_re::parse_xex(&data)?;

    if json && extract.is_none() {
        println!("{}", serde_json::to_string_pretty(&meta)?);
        return Ok(());
    }

    // Extract the flat image (+ per-section slices) when asked.
    if let Some(dir) = extract {
        let img = xencode_re::xex_decompress(&data)?;
        let out = std::path::Path::new(dir);
        std::fs::create_dir_all(out)?;
        let image_path = out.join("image.bin");
        std::fs::write(&image_path, &img.image)?;
        for s in &img.info.sections {
            let name = format!(
                "{:#010x}_{}_{}k.bin",
                s.va,
                s.type_name,
                s.size / 1024
            );
            std::fs::write(out.join(name), &img.image[s.image_offset as usize..(s.image_offset + s.size) as usize])?;
        }
        if !json {
            println!(
                "extracted {} ({:.1} MB) + {} section files → {}",
                "image.bin",
                img.image.len() as f64 / (1024.0 * 1024.0),
                img.info.sections.len(),
                out.display()
            );
        } else {
            println!(
                "{}",
                serde_json::to_string_pretty(&serde_json::json!({
                    "image": image_path.display().to_string(),
                    "image_size": img.image.len(),
                    "sections": img.info.sections.iter().map(|s| serde_json::json!({
                        "type": s.type_name,
                        "va": s.va,
                        "size": s.size,
                    })).collect::<Vec<_>>(),
                }))?
            );
        }
        return Ok(());
    }

    if json {
        println!("{}", serde_json::to_string_pretty(&meta)?);
        return Ok(());
    }
    println!("magic        {}", meta.magic);
    println!("module flags 0x{:08X} {}", meta.module_flags, meta.module_flag_names.join(", "));
    println!(
        "image        {:#x} @ load {:#x} ({} pages × {})",
        meta.image_size,
        meta.load_address,
        meta.page_descriptor_count,
        human_bytes(meta.page_size)
    );
    println!(
        "entry        {}",
        meta.entry_point.map(|v| format!("{v:#x}")).unwrap_or_else(|| "-".into())
    );
    println!("encryption   {}", meta.encryption);
    println!("compression  {}", meta.compression);
    if !meta.basic_blocks.is_empty() {
        println!(
             "  blocks     {} ({} data / {} zero)",
            meta.basic_blocks.len(),
            human_bytes(meta.basic_blocks.iter().map(|b| b.data_size).sum()),
            human_bytes(meta.basic_blocks.iter().map(|b| b.zero_size).sum())
        );
    }
    if let Some(w) = meta.lzx_window_size {
        println!("  lzx window {w:#x}");
    }
    if let Some(id) = meta.title_id {
        let tag = meta.title_tag.as_deref().unwrap_or("--");
        println!("title id     {id:#010x} ({tag})");
    }
    if let (Some(v), Some(bv)) = (&meta.version, &meta.base_version) {
        println!("version      {v} (base {bv})");
    }
    if let (Some(dn), Some(dc)) = (meta.disc_number, meta.disc_count) {
        println!("disc         {} of {}", dn + 1, dc);
    }
    if let Some(name) = &meta.original_pe_name {
        println!("pe name      {name}");
    }
    if !meta.import_libraries.is_empty() {
        println!("imports      {}", meta.import_libraries.join(", "));
    }
    if !meta.static_libraries.is_empty() {
        println!(
            "static libs  {}",
            meta.static_libraries
                .iter()
                .map(|l| format!("{} {}", l.name, l.version))
                .collect::<Vec<_>>()
                .join(", ")
        );
    }
    if !meta.resources.is_empty() {
        println!("resources    {}", meta.resources.len());
    }
    if meta.encryption != "none" {
        println!();
        println!(
            "note         payload is encrypted — XenRE parses container structure only; \
             decryption keys are never bundled (compliance guardrail)"
        );
    }
    println!();
    println!("{:>10}  {:<9} {:>10}  {:>10}", "VA", "TYPE", "SIZE", "IMAGE-OFF");
    for s in &meta.sections {
        println!(
            "{:>10}  {:<9} {:>10}  {:>10}",
            format!("{:#x}", s.va),
            s.type_name,
            human_bytes(s.size),
            format!("{:#x}", s.image_offset)
        );
    }
    Ok(())
}

/// `xencode re ghidra <FILE>` — Ghidra headless analysis + decompiled-C
/// export. The backend is detected (PATH, then the managed install);
/// `--download` opts into fetching the latest release (~450 MB, JDK 21).
/// XEX inputs are flattened first and imported as PPC64 BE raw binaries.
#[allow(clippy::too_many_arguments)]
fn run_re_ghidra(
    file: &str,
    out: Option<&str>,
    download: bool,
    max_files: usize,
    max_fns: usize,
    timeout_sec: usize,
    lang: Option<&str>,
    json: bool,
) -> Result<()> {
    let status = xencode_re::ghidra_ensure(
        &xencode_re::ghidra_install_root(),
        download,
        xencode_re::ghidra_fetch_url,
    )?;
    if !json {
        match (&status.version, &status.path) {
            (Some(v), Some(p)) => println!(
                "ghidra    {v} ({p}{})",
                if status.managed { ", managed" } else { "" }
            ),
            _ => println!("ghidra    version unknown"),
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
                .unwrap_or("image");
            PathBuf::from(format!("{base}-ghidra"))
        });
    let opts = xencode_re::GhidraOpts {
        max_files,
        max_functions: max_fns,
        timeout_sec,
        lang: lang.map(|s| s.to_string()),
        base_addr: None,
    };
    let run = xencode_re::ghidra_run(Path::new(file), &out_dir, opts, &status)?;

    if json {
        println!("{}", serde_json::to_string_pretty(&run)?);
        return Ok(());
    }
    println!(
        "decompiled {} functions across {} .c files in {:.1}s → exit {}",
        run.functions_decompiled.map(|n| n.to_string()).unwrap_or_else(|| "?".into()),
        run.total_c_files,
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
    for f in run.c_files.iter().take(SHOW) {
        println!("  {f}");
    }
    if run.c_files.len() > SHOW {
        println!("  … {} more listed ({} total)", run.c_files.len() - SHOW, run.total_c_files);
    } else if run.total_c_files as usize > run.c_files.len() {
        println!("  … {} more (cap {})", run.total_c_files as usize - run.c_files.len(), max_files);
    }
    if run.exit_code != 0 {
        bail!("Ghidra exited with code {}", run.exit_code);
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
    } else if xencode_re::is_xex(&data) {
        // Phase 7.3: XEX containers open on the XEX identity view — the
        // structure parse is keyless, so even encrypted retail files render.
        let x = xencode_re::parse_xex(&data)?;
        format_label = "XEX";
        arch_label = "ppc64";
        kind_label = "xbox 360";
        pkg_size = data.len() as u64;
        pkg_rows.push(ui::re::ReKv {
            key: "magic",
            value: Box::leak(x.magic.clone().into_boxed_str()),
        });
        pkg_rows.push(ui::re::ReKv {
            key: "image",
            value: Box::leak(
                format!(
                    "{:#x} @ load {:#x} ({} pages × {})",
                    x.image_size,
                    x.load_address,
                    x.page_descriptor_count,
                    human_bytes(x.page_size)
                )
                .into_boxed_str(),
            ),
        });
        if let Some(ep) = x.entry_point {
            pkg_rows.push(ui::re::ReKv {
                key: "entry",
                value: Box::leak(format!("{ep:#x}").into_boxed_str()),
            });
        }
        pkg_rows.push(ui::re::ReKv {
            key: "codec",
            value: Box::leak(
                format!("enc {} · comp {}", x.encryption, x.compression).into_boxed_str(),
            ),
        });
        if let Some(id) = x.title_id {
            let tag = x.title_tag.clone().unwrap_or_else(|| "--".into());
            pkg_rows.push(ui::re::ReKv {
                key: "title",
                value: Box::leak(format!("{id:#010x} ({tag})").into_boxed_str()),
            });
        }
        if let (Some(v), Some(bv)) = (&x.version, &x.base_version) {
            pkg_rows.push(ui::re::ReKv {
                key: "version",
                value: Box::leak(format!("{v} (base {bv})").into_boxed_str()),
            });
        }
        if let (Some(dn), Some(dc)) = (x.disc_number, x.disc_count) {
            pkg_rows.push(ui::re::ReKv {
                key: "disc",
                value: Box::leak(format!("{} of {}", dn + 1, dc).into_boxed_str()),
            });
        }
        if let Some(name) = &x.original_pe_name {
            pkg_rows.push(ui::re::ReKv {
                key: "pe name",
                value: Box::leak(name.clone().into_boxed_str()),
            });
        }
        for s in x.sections.iter().take(6) {
            let label = format!(
                "{} {:#x} {}",
                s.type_name,
                s.va,
                human_bytes(s.size)
            );
            let tone = if s.page_type == 1 {
                ui::re::ReChipTone::Green
            } else {
                ui::re::ReChipTone::Sky
            };
            pkg_chips.push(ui::re::ReChip { label: Box::leak(label.into_boxed_str()), tone });
        }
        for lib in x.import_libraries.iter().take(6) {
            pkg_chips.push(ui::re::ReChip {
                label: Box::leak(format!("import {lib}").into_boxed_str()),
                tone: ui::re::ReChipTone::Sky,
            });
        }
        for lib in x.static_libraries.iter().take(4) {
            pkg_chips.push(ui::re::ReChip {
                label: Box::leak(
                    format!("{} {}", lib.name, lib.version).into_boxed_str(),
                ),
                tone: ui::re::ReChipTone::Subtle,
            });
        }
        for r in x.resources.iter().take(4) {
            pkg_chips.push(ui::re::ReChip {
                label: Box::leak(
                    format!("resource {} {}", r.name, human_bytes(r.size)).into_boxed_str(),
                ),
                tone: ui::re::ReChipTone::Subtle,
            });
        }
        if x.encryption != "none" {
            pkg_chips.push(ui::re::ReChip {
                label: "encrypted — structure only (no keys bundled)",
                tone: ui::re::ReChipTone::Subtle,
            });
        }
        bin_info = Some(xencode_re::xex_to_binary_info(&x));
    } else {
        format_label = "";
        arch_label = "";
        kind_label = "";
        pkg_size = data.len() as u64;
        bin_info = Some(xencode_re::analyze_file(file)?);
    }

    // The rows/chips vectors are consumed by render_re_tab — capture the
    // default tab choice (and their emptiness) before the move.
    let default_tab = if pkg_rows.is_empty() {
        ui::re::ReTab::Hex
    } else if xencode_re::is_xex(&data) {
        ui::re::ReTab::Xex
    } else {
        ui::re::ReTab::Package
    };
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
        default_tab,
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
    default_tab: ui::re::ReTab,
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
        // Packages open on the bundle summary; XEX on its identity view;
        // plain binaries on hex.
        tab: default_tab,
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
