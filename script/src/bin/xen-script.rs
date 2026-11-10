//! `xen-script` — the automation CLI (Phase 2 entry point).
//!
//! ```text
//! xen-script run <script> [--arg k=v ...] [--yes] [--read-only]
//!                 [--timeout-ms N] [--memory-mb N] [--json]
//! xen-script triggers [--list]
//! xen-script info
//! xen-script cache ls|clean [name]
//! ```
//!
//! `run` loads `xen.toml` from the script's directory (walking parents),
//! prefetches bare npm imports into the vendor cache, installs stdin consent
//! prompts (or `--yes` auto-allow), and executes the script in an isolated
//! QuickJS context with the full `xen.*` bridge. `triggers` runs the daemon
//! (cron + file-watch + webhooks + lifecycle hooks) until Ctrl-C.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::time::Duration;

use anyhow::{anyhow, Context as _};
use xencode_script::config::XenConfig;
use xencode_script::events::{self, UiEvent};
use xencode_script::host::agent::AgentBridge;
use xencode_script::host::ui::UiBridge;
use xencode_script::npm::VendorCache;
use xencode_script::permissions::{Decision, PermissionState, PromptBridge};
use xencode_script::runtime::{RuntimeOptions, ScriptRuntime};
use xencode_script::triggers::{RunFactory, RunSpec, TriggerEngine};

fn main() -> anyhow::Result<()> {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let Some(cmd) = args.first().cloned() else {
        print_help();
        return Ok(());
    };
    let rest: Vec<&String> = args[1..].iter().collect();
    match cmd.as_str() {
        "run" => run_command(&rest),
        "triggers" => triggers_command(&rest),
        "info" => info_command(),
        "cache" => cache_command(&rest),
        "help" | "--help" | "-h" => {
            print_help();
            Ok(())
        }
        other => Err(anyhow!(
            "unknown command `{other}` — try `xen-script help`"
        )),
    }
}

fn print_help() {
    eprintln!(
        "xen-script {version} — XenCode automation core (QuickJS, no npm/node/python)

USAGE:
    xen-script run <script.ts|js> [--arg k=v ...] [--yes] [--read-only]
                   [--timeout-ms N] [--memory-mb N] [--json]
    xen-script triggers [--list]        daemon: cron + watch + webhook + lifecycle
    xen-script info                     engine/version JSON
    xen-script cache ls|clean [name]    vendor cache management

The script's directory (and parents) is searched for `xen.toml`
(permissions + triggers). Bare npm imports are vendored into
~/.cache/xencode/npm, sha512-verified and lockfiled in xen.lock.",
        version = xencode_script::VERSION
    );
}

// ---------------------------------------------------------------------------
// shared pieces
// ---------------------------------------------------------------------------

fn parse_flags<'a>(args: &[&'a String]) -> (Vec<&'a String>, HashMap<String, String>) {
    let mut positional = Vec::new();
    let mut flags = HashMap::new();
    let mut i = 0;
    while i < args.len() {
        let arg = args[i].as_str();
        if let Some(rest) = arg.strip_prefix("--arg:") {
            if let Some((k, v)) = rest.split_once('=') {
                flags.insert(k.to_string(), v.to_string());
            }
        } else if let Some(rest) = arg.strip_prefix("--") {
            // boolean or `--flag=value`
            if let Some((k, v)) = rest.split_once('=') {
                flags.insert(k.to_string(), v.to_string());
            } else {
                flags.insert(rest.to_string(), "true".to_string());
            }
        } else {
            positional.push(args[i]);
        }
        i += 1;
    }
    (positional, flags)
}

/// CLI consent: stdin y/n/a, `--yes` → auto session-allow, `--read-only`
/// → deny proc/net prompts but keep static config.
fn build_permission_state(
    cfg: &XenConfig,
    project_root: &Path,
    flags: &HashMap<String, String>,
) -> PermissionState {
    let bridge = PromptBridge::new();
    let yes = flags.contains_key("yes");
    let read_only = flags.contains_key("read-only");
    bridge.set(Box::new(move |prompt| match prompt.kind {
        "proc" | "net" if read_only => Decision::Deny,
        _ if yes => Decision::AllowSession,
        _ => cli_prompt(prompt.kind, &prompt.detail),
    }));
    PermissionState::new(cfg.permissions.clone(), project_root, bridge)
}

fn cli_prompt(kind: &str, detail: &str) -> Decision {
    use std::io::Write;
    eprint!("{kind} access to `{detail}`? [y]es / [n]o / [a]lways: ");
    let _ = std::io::stderr().flush();
    let mut line = String::new();
    if std::io::stdin().read_line(&mut line).is_err() {
        return Decision::Deny;
    }
    match line.trim().to_ascii_lowercase().as_str() {
        "y" | "yes" => Decision::AllowOnce,
        "a" | "always" => Decision::AllowSession,
        _ => Decision::Deny,
    }
}

/// Build a runtime factory: a fresh, isolated QuickJS runtime per script.
///
/// `ScriptRuntime` is deliberately `!Send` (QuickJS values must stay on one
/// thread), so the prefetch (Send, async) runs on the caller's runtime and
/// the runtime creation + execution happen on a dedicated blocking thread
/// with its own current-thread tokio runtime.
fn runtime_factory(
    cfg: XenConfig,
    config_path: PathBuf,
    flags: HashMap<String, String>,
    events_tx: events::UiEventTx,
) -> RunFactory {
    Arc::new(move |spec: RunSpec| {
        let cfg = cfg.clone();
        let config_path = config_path.clone();
        let flags = flags.clone();
        let events_tx = events_tx.clone();
        Box::pin(async move {
            let project_root = config_path.parent().unwrap_or(Path::new(".")).to_path_buf();
            let perms = build_permission_state(&cfg, &project_root, &flags);

            // npm prefetch for this script's imports (network — Send ok)
            let cache = VendorCache::new(VendorCache::default_dir());
            let source: String = std::fs::read_to_string(&spec.script)
                .with_context(|| format!("reading {}", spec.script.display()))?;
            let prefetch = cache
                .prefetch(&source, &project_root, &perms)
                .await
                .unwrap_or_else(|err| {
                    eprintln!("[npm] vendor prefetch skipped: {err}");
                    Default::default()
                });

            let mut opts = RuntimeOptions::new(project_root, perms, events_tx);
            opts.npm_files = prefetch.files.into_iter().collect();
            opts.npm_roots = prefetch.roots.into_iter().collect();
            opts.timeout = spec
                .timeout_ms
                .map(Duration::from_millis)
                .or_else(|| {
                    flags
                        .get("timeout-ms")
                        .and_then(|v| v.parse::<u64>().ok())
                        .map(Duration::from_millis)
                });
            if let Some(mb) = flags.get("memory-mb").and_then(|v| v.parse::<usize>().ok()) {
                opts.memory_limit = mb * 1024 * 1024;
            }

            // execute on a dedicated thread (ScriptRuntime is !Send)
            let script = spec.script.clone();
            let (result_tx, result_rx) = tokio::sync::oneshot::channel();
            tokio::task::spawn_blocking(move || {
                let outcome: anyhow::Result<()> = (|| {
                    let rt = tokio::runtime::Builder::new_current_thread()
                        .enable_all()
                        .build()?;
                    let local = tokio::task::LocalSet::new();
                    local.block_on(&rt, async move {
                        let runtime = ScriptRuntime::new(opts).await?;
                        let result = runtime
                            .run_script(&script, &serde_json::Value::Null)
                            .await;
                        runtime.shutdown().await;
                        result.map(|_| ())
                    })
                })();
                let _ = result_tx.send(outcome);
            });
            let outcome = result_rx
                .await
                .map_err(|e| anyhow!("script thread died: {e}"))?;
            outcome
        })
    })
}

/// Pump UI events to stderr until the sender half drops.
fn spawn_event_pump(events_rx: tokio::sync::mpsc::UnboundedReceiver<UiEvent>) {
    tokio::spawn(async move {
        let mut rx = events_rx;
        while let Some(ev) = rx.recv().await {
            if let Some(line) = events::format_cli(&ev) {
                eprintln!("{line}");
            }
        }
    });
}

// ---------------------------------------------------------------------------
// commands
// ---------------------------------------------------------------------------

fn run_command(args: &[&String]) -> anyhow::Result<()> {
    let (positional, flags) = parse_flags(args);
    let Some(script) = positional.first() else {
        return Err(anyhow!("usage: xen-script run <script> [flags]"));
    };
    let script_path = PathBuf::from(script);
    if !script_path.is_file() {
        return Err(anyhow!("script not found: {}", script_path.display()));
    }

    let script_dir = script_path
        .parent()
        .map(|p| p.to_path_buf())
        .unwrap_or_else(|| PathBuf::from("."));
    let (cfg, config_path) = XenConfig::discover(&script_dir)?;

    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()?;
    runtime.block_on(async move {
        let (tx, rx) = events::channel();
        spawn_event_pump(rx);

        let factory = runtime_factory(cfg.clone(), config_path.clone(), flags.clone(), tx.clone());
        let spec = RunSpec {
            trigger_id: "cli".to_string(),
            script: script_path.clone(),
            timeout_ms: flags
                .get("timeout-ms")
                .and_then(|v| v.parse::<u64>().ok()),
        };
        let result = factory(spec).await;

        // lifecycle onStop hooks
        if cfg
            .triggers
            .iter()
            .any(|t| t.lifecycle.as_deref() == Some("onStop"))
        {
            let engine = Arc::new(TriggerEngine::new(
                cfg.clone(),
                config_path.clone(),
                build_permission_state(&cfg, config_path.parent().unwrap_or(Path::new(".")), &flags),
                tx.clone(),
            ));
            engine.set_factory(factory.clone());
            engine.run_lifecycle("onStop", None).await;
        }

        match result {
            Ok(()) => Ok(()),
            Err(err) => Err(anyhow!("{err:#}")),
        }
    })
}

fn triggers_command(args: &[&String]) -> anyhow::Result<()> {
    let (_positional, _flags) = parse_flags(args);
    let cwd = std::env::current_dir()?;
    let (cfg, config_path) = XenConfig::discover(&cwd)?;

    let runtime = tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()?;
    runtime.block_on(async move {
        let (tx, rx) = events::channel();
        spawn_event_pump(rx);

        let project_root = config_path.parent().unwrap_or(Path::new(".")).to_path_buf();
        let perms = PermissionState::new(cfg.permissions.clone(), &project_root, PromptBridge::new());
        let engine = Arc::new(TriggerEngine::new(cfg.clone(), config_path.clone(), perms, tx.clone()));
        engine.set_factory(runtime_factory(cfg.clone(), config_path.clone(), HashMap::new(), tx.clone()));

        let port = engine.start().await.context("engine start")?;
        if let Some(port) = port {
            eprintln!("[triggers] webhook endpoint http://127.0.0.1:{port}");
        }
        eprintln!(
            "[triggers] {} trigger(s) running — press Ctrl-C to stop",
            cfg.triggers.len()
        );
        tokio::signal::ctrl_c().await.ok();
        engine.stop().await;
        eprintln!("[triggers] stopped; {} run(s) executed", engine.runs().len());
        Ok(())
    })
}

fn info_command() -> anyhow::Result<()> {
    println!(
        "{}",
        serde_json::json!({
            "name": "xencode-script",
            "version": xencode_script::VERSION,
            "engine": xencode_script::ENGINE,
            "modules": [
                "xen/http", "xen/fs", "xen/proc", "xen/agent", "xen/ui",
                "xen/path", "xen/glob", "xen/yaml", "xen/toml", "xen/csv",
                "xen/zip", "xen/sqlite", "xen/dotenv", "xen/env",
                "xen/semver", "xen/ansi", "xen/markdown"
            ],
        })
    );
    Ok(())
}

fn cache_command(args: &[&String]) -> anyhow::Result<()> {
    let (positional, _flags) = parse_flags(args);
    let cache = VendorCache::new(VendorCache::default_dir());
    match positional.first().map(|s| s.as_str()) {
        Some("ls") | None => {
            let entries = cache.list();
            if entries.is_empty() {
                println!("(vendor cache empty)");
            }
            for (name, size) in entries {
                println!("{name}  {:.1} KB", size as f64 / 1024.0);
            }
            Ok(())
        }
        Some("clean") => {
            let n = cache.clean(positional.get(1).map(|s| s.as_str()))?;
            println!("cleaned {n} package(s)");
            Ok(())
        }
        Some(other) => Err(anyhow!("unknown cache command `{other}` — use ls|clean")),
    }
}

// agent/ui bridges are surfaced for hosts embedding the runtime (the GUI);
// the CLI wires its own prompts directly.
#[allow(dead_code)]
fn bridges_for_tests() -> (AgentBridge, UiBridge) {
    (AgentBridge::new(), UiBridge::new())
}
