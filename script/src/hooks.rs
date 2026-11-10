//! Agent hooks (Phase 3, harness port #3 — from Claude Code): user scripts
//! that observe and gate the agent loop, executed by the QuickJS runtime.
//!
//! Configured in `xen.toml`:
//!
//! ```toml
//! [hooks]
//! pre_tool_use  = [".xencode/hooks/guard.ts"]
//! post_tool_use = [".xencode/hooks/audit.js"]
//! stop          = [".xencode/hooks/verify.ts"]
//! ```
//!
//! Every hook run executes in its own isolated [`ScriptRuntime`] (fresh
//! QuickJS context, memory limit, interrupt guard, per-hook deadline) on a
//! dedicated thread — a hung hook can never take the agent loop down. The
//! payload arrives as the `ctx` argument; the return value is the verdict:
//!
//! | Kind        | Payload `ctx`                                            | Verdict                                        |
//! |-------------|----------------------------------------------------------|------------------------------------------------|
//! | PreToolUse  | `{ hook, tool, args, cwd }`                              | `{ decision: "deny", reason }` blocks the call |
//! | PostToolUse | `{ hook, tool, args, result, ok, ms }`                   | `{ feedback }` is appended to the tool result  |
//! | Stop        | `{ hook, answer, iterations }`                           | `{ decision: "block", reason }` continues      |
//!
//! A thrown exception (or missing verdict) is non-blocking — matching
//! Claude Code, where only an explicit deny/return verdict interrupts the
//! loop; errors are surfaced to the host instead.

use std::collections::HashMap;
use std::path::{Path, PathBuf};
use std::time::Duration;

use serde_json::Value;

use crate::config::HooksConfig;
use crate::events::UiEventTx;
use crate::permissions::PermissionState;
use crate::runtime::{RuntimeOptions, ScriptRuntime};

/// Which point in the agent loop a hook observes.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum HookKind {
    /// Before a tool executes — may deny the call.
    PreToolUse,
    /// After a tool executed — may attach feedback to its result.
    PostToolUse,
    /// When the model is about to stop — may block and continue the loop.
    Stop,
}

impl HookKind {
    /// Config key in `[hooks]` (`pre_tool_use`, …).
    pub fn key(self) -> &'static str {
        match self {
            HookKind::PreToolUse => "pre_tool_use",
            HookKind::PostToolUse => "post_tool_use",
            HookKind::Stop => "stop",
        }
    }

    /// Payload / documentation name (`PreToolUse`, …).
    pub fn as_str(self) -> &'static str {
        match self {
            HookKind::PreToolUse => "PreToolUse",
            HookKind::PostToolUse => "PostToolUse",
            HookKind::Stop => "Stop",
        }
    }
}

/// What one hook script said about one event.
#[derive(Debug, Clone, Default)]
pub struct HookOutcome {
    /// Script that produced this outcome (display path).
    pub script: String,
    /// The script ran to completion (false on timeout / exception / crash).
    pub ok: bool,
    /// Error detail when `ok` is false.
    pub error: Option<String>,
    /// `decision` from the verdict (`"deny"`, `"block"`, `"allow"`, …).
    pub decision: Option<String>,
    /// `reason` from the verdict (shown to the model when blocking).
    pub reason: Option<String>,
    /// `feedback` from the verdict (PostToolUse — appended to the result).
    pub feedback: Option<String>,
    /// The full JSON verdict for anything the host wants to inspect.
    pub raw: Value,
}

impl HookOutcome {
    /// PreToolUse: this hook blocked the tool call.
    pub fn denies(&self) -> bool {
        self.decision.as_deref() == Some("deny")
    }

    /// Stop: this hook blocked stopping — the loop must continue.
    pub fn blocks(&self) -> bool {
        self.decision.as_deref() == Some("block")
    }
}

/// The configured hook scripts for one project, resolved against the
/// `xen.toml` location. Cloneable and shared across runs.
#[derive(Clone)]
pub struct HookRunner {
    /// Root the sandbox treats as `$PROJECT` (the xen.toml directory).
    root: PathBuf,
    scripts: HashMap<HookKind, Vec<PathBuf>>,
    timeout: Duration,
}

impl HookRunner {
    /// Resolve a `[hooks]` config against its config-file path. Entries
    /// pointing at missing files are skipped (a typo must not take the loop
    /// down; the host can log what was skipped).
    pub fn from_config(config_path: &Path, cfg: &HooksConfig) -> Self {
        let base = config_path.parent().unwrap_or(Path::new(".")).to_path_buf();
        let mut scripts = HashMap::new();
        for (kind, list) in [
            (HookKind::PreToolUse, &cfg.pre_tool_use),
            (HookKind::PostToolUse, &cfg.post_tool_use),
            (HookKind::Stop, &cfg.stop),
        ] {
            let resolved: Vec<PathBuf> = list
                .iter()
                .map(|p| base.join(p))
                .filter(|p| {
                    if p.is_file() {
                        true
                    } else {
                        eprintln!("xencode · hook skipped (missing): {}", p.display());
                        false
                    }
                })
                .collect();
            if !resolved.is_empty() {
                scripts.insert(kind, resolved);
            }
        }
        Self {
            root: base,
            scripts,
            timeout: Duration::from_millis(cfg.timeout_ms.unwrap_or(10_000)),
        }
    }

    /// A runner with no hooks at all.
    pub fn empty(root: impl Into<PathBuf>) -> Self {
        Self {
            root: root.into(),
            scripts: HashMap::new(),
            timeout: Duration::from_secs(10),
        }
    }

    /// True when nothing is configured (callers skip the machinery).
    pub fn is_empty(&self) -> bool {
        self.scripts.is_empty()
    }

    /// True when at least one script is registered for `kind`.
    pub fn has(&self, kind: HookKind) -> bool {
        self.scripts.contains_key(&kind)
    }

    /// Number of scripts registered for `kind`.
    pub fn count(&self, kind: HookKind) -> usize {
        self.scripts.get(&kind).map(Vec::len).unwrap_or(0)
    }

    /// Run every script for `kind`, in config order (deterministic — the
    /// first deny is the verdict that matters). Each run is fully isolated:
    /// fresh QuickJS context, own thread, own deadline. Console output from
    /// hooks flows through `events` like any script's.
    pub async fn run(
        &self,
        kind: HookKind,
        payload: Value,
        events: UiEventTx,
    ) -> Vec<HookOutcome> {
        let Some(scripts) = self.scripts.get(&kind) else {
            return Vec::new();
        };
        let mut outcomes = Vec::with_capacity(scripts.len());
        for script in scripts {
            let root = self.root.clone();
            let script = script.clone();
            let script_disp = script.display().to_string();
            let payload = payload.clone();
            let events = events.clone();
            let timeout = self.timeout;
            // spawn_blocking keeps QuickJS (and its dedicated thread) off the
            // async workers; works on both multi-thread and current-thread
            // runtimes, so #[tokio::test] is safe too.
            let outcome = tokio::task::spawn_blocking(move || {
                Self::run_one_sync(&root, &script, &payload, timeout, events)
            })
            .await
            .unwrap_or_else(|e| HookOutcome {
                script: script_disp,
                ok: false,
                error: Some(format!("hook task failed: {e}")),
                ..Default::default()
            });
            let stopped = matches!(kind, HookKind::PreToolUse | HookKind::Stop)
                && (outcome.denies() || outcome.blocks());
            outcomes.push(outcome);
            if stopped {
                break; // a blocking verdict ends the sequence
            }
        }
        outcomes
    }

    /// One hook run: dedicated thread → current-thread tokio runtime →
    /// LocalSet (QuickJS futures are !Send) → fresh sandboxed runtime.
    fn run_one_sync(
        root: &Path,
        script: &Path,
        payload: &Value,
        timeout: Duration,
        events: UiEventTx,
    ) -> HookOutcome {
        let mut outcome = HookOutcome {
            script: script.display().to_string(),
            ..Default::default()
        };

        let root = root.to_path_buf();
        let script = script.to_path_buf();
        let payload = payload.clone();
        let spawned = std::thread::Builder::new()
            .name(format!(
                "xen-hook-{}",
                script
                    .file_stem()
                    .map(|s| s.to_string_lossy().into_owned())
                    .unwrap_or_else(|| "run".into())
            ))
            .spawn(move || {
                let rt = tokio::runtime::Builder::new_current_thread()
                    .enable_all()
                    .build()?;
                tokio::task::LocalSet::new().block_on(&rt, async move {
                    // Hook scripts are explicitly opted into by the project's
                    // xen.toml, so they run with full grants — no consent
                    // prompts from inside a gatekeeping hook.
                    let perms = PermissionState::allow_all();
                    let mut opts = RuntimeOptions::new(&root, perms, events);
                    opts.timeout = Some(timeout);
                    let rt = ScriptRuntime::new(opts).await?;
                    let res = rt.run_hook(&script, &payload).await;
                    rt.shutdown().await;
                    res
                })
            });

        let joined = spawned.and_then(|h| {
            h.join().map_err(|_| {
                std::io::Error::other("hook thread panicked")
            })
        });

        match joined {
            Ok(Ok(raw)) => {
                outcome.ok = true;
                outcome.raw = raw.clone();
                outcome.decision = raw.get("decision").and_then(Value::as_str).map(str::to_string);
                outcome.reason = raw.get("reason").and_then(Value::as_str).map(str::to_string);
                outcome.feedback = raw.get("feedback").and_then(Value::as_str).map(str::to_string);
            }
            Ok(Err(e)) => {
                outcome.error = Some(format!("{e:#}"));
            }
            Err(e) => {
                outcome.error = Some(format!("{e:#}"));
            }
        }
        outcome
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::fs;

    fn tmp(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("xencode-hooks-{}-{}", tag, std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn write_cfg(dir: &Path, hooks: &str) -> PathBuf {
        fs::write(dir.join("xen.toml"), hooks).unwrap();
        dir.join("xen.toml")
    }

    async fn run_kind(
        runner: &HookRunner,
        kind: HookKind,
        payload: Value,
    ) -> Vec<HookOutcome> {
        let (tx, mut rx) = crate::events::channel();
        // Drain console events in the background like a CLI printer would.
        let drainer = tokio::spawn(async move {
            while rx.recv().await.is_some() {}
        });
        let out = runner.run(kind, payload, tx).await;
        drainer.abort();
        out
    }

    #[tokio::test]
    async fn pre_tool_use_deny_blocks_the_call() {
        let dir = tmp("deny");
        fs::write(
            dir.join("guard.ts"),
            r#"
            export default function (ctx) {
              if (ctx.tool === "bash" && ctx.args.command.includes("rm -rf")) {
                return { decision: "deny", reason: "destructive command blocked by guard.ts" };
              }
              return { decision: "allow" };
            }
            "#,
        )
        .unwrap();
        let cfg_path = write_cfg(&dir, "[hooks]\npre_tool_use = [\"guard.ts\"]\n");
        let cfg = crate::config::XenConfig::load(&cfg_path).unwrap();
        let runner = HookRunner::from_config(&cfg_path, &cfg.hooks);
        assert_eq!(runner.count(HookKind::PreToolUse), 1);

        let out = run_kind(
            &runner,
            HookKind::PreToolUse,
            json!({"hook": "PreToolUse", "tool": "bash", "args": {"command": "rm -rf /"}, "cwd": dir.display().to_string()}),
        )
        .await;
        assert_eq!(out.len(), 1);
        assert!(out[0].ok);
        assert!(out[0].denies(), "expected deny, got {:?}", out[0].raw);
        assert!(out[0].reason.as_deref().unwrap().contains("destructive"));

        // A harmless call passes.
        let out = run_kind(
            &runner,
            HookKind::PreToolUse,
            json!({"hook": "PreToolUse", "tool": "bash", "args": {"command": "cargo test"}}),
        )
        .await;
        assert!(out[0].ok && !out[0].denies());
    }

    #[tokio::test]
    async fn async_hook_and_feedback_flow() {
        let dir = tmp("async");
        fs::write(
            dir.join("audit.js"),
            r#"
            export default async function (ctx) {
              if (!ctx.ok) return { feedback: `tool ${ctx.tool} failed — tell the user why` };
              return {};
            }
            "#,
        )
        .unwrap();
        let cfg_path = write_cfg(&dir, "[hooks]\npost_tool_use = [\"audit.js\"]\n");
        let cfg = crate::config::XenConfig::load(&cfg_path).unwrap();
        let runner = HookRunner::from_config(&cfg_path, &cfg.hooks);

        let out = run_kind(
            &runner,
            HookKind::PostToolUse,
            json!({"hook": "PostToolUse", "tool": "bash", "ok": false, "ms": 12, "result": "boom"}),
        )
        .await;
        assert!(out[0].ok);
        assert_eq!(out[0].feedback.as_deref(), Some("tool bash failed — tell the user why"));

        // Empty verdict → no feedback, non-blocking.
        let out = run_kind(
            &runner,
            HookKind::PostToolUse,
            json!({"hook": "PostToolUse", "tool": "bash", "ok": true, "ms": 3, "result": "fine"}),
        )
        .await;
        assert!(out[0].ok && out[0].feedback.is_none() && !out[0].blocks());
    }

    #[tokio::test]
    async fn stop_hook_can_block_stopping() {
        let dir = tmp("stop");
        fs::write(
            dir.join("verify.ts"),
            r#"
            export default function (ctx) {
              if (!ctx.answer.includes("tests")) {
                return { decision: "block", reason: "report the test results before stopping" };
              }
              return {};
            }
            "#,
        )
        .unwrap();
        let cfg_path = write_cfg(&dir, "[hooks]\nstop = [\"verify.ts\"]\n");
        let cfg = crate::config::XenConfig::load(&cfg_path).unwrap();
        let runner = HookRunner::from_config(&cfg_path, &cfg.hooks);

        let out = run_kind(
            &runner,
            HookKind::Stop,
            json!({"hook": "Stop", "answer": "all done", "iterations": 2}),
        )
        .await;
        assert!(out[0].blocks());
        assert_eq!(out[0].reason.as_deref(), Some("report the test results before stopping"));

        let out = run_kind(
            &runner,
            HookKind::Stop,
            json!({"hook": "Stop", "answer": "tests pass", "iterations": 2}),
        )
        .await;
        assert!(!out[0].blocks());
    }

    #[tokio::test]
    async fn thrown_exception_is_non_blocking() {
        let dir = tmp("throw");
        fs::write(dir.join("boom.ts"), "throw new Error('hook exploded');\n").unwrap();
        let cfg_path = write_cfg(&dir, "[hooks]\npre_tool_use = [\"boom.ts\"]\n");
        let cfg = crate::config::XenConfig::load(&cfg_path).unwrap();
        let runner = HookRunner::from_config(&cfg_path, &cfg.hooks);

        let out = run_kind(&runner, HookKind::PreToolUse, json!({"tool": "bash"})).await;
        assert!(!out[0].ok, "script threw");
        assert!(out[0].error.as_deref().unwrap_or("").contains("hook exploded"));
        assert!(!out[0].denies() && !out[0].blocks(), "errors are non-blocking");
    }

    #[tokio::test]
    async fn timeout_kills_a_hung_hook() {
        let dir = tmp("hang");
        fs::write(dir.join("hang.ts"), "while (true) {} // never returns\n").unwrap();
        let cfg_path = write_cfg(
            &dir,
            "[hooks]\npre_tool_use = [\"hang.ts\"]\ntimeout_ms = 300\n",
        );
        let cfg = crate::config::XenConfig::load(&cfg_path).unwrap();
        let runner = HookRunner::from_config(&cfg_path, &cfg.hooks);

        let out = run_kind(&runner, HookKind::PreToolUse, json!({"tool": "bash"})).await;
        assert!(!out[0].ok, "hung hook must be interrupted");
        assert!(!out[0].denies() && !out[0].blocks());
    }

    #[tokio::test]
    async fn main_export_and_missing_files() {
        let dir = tmp("main");
        fs::write(
            dir.join("main-style.ts"),
            "export default {};\nexport function main(ctx) { return { decision: \"allow\" }; }\n",
        )
        .unwrap();
        let cfg_path = write_cfg(
            &dir,
            "[hooks]\npre_tool_use = [\"main-style.ts\", \"nope-missing.ts\"]\n",
        );
        let cfg = crate::config::XenConfig::load(&cfg_path).unwrap();
        let runner = HookRunner::from_config(&cfg_path, &cfg.hooks);
        assert_eq!(runner.count(HookKind::PreToolUse), 1, "missing file skipped");

        let out = run_kind(&runner, HookKind::PreToolUse, json!({"tool": "read_file"})).await;
        assert_eq!(out.len(), 1);
        assert!(out[0].ok);
        assert_eq!(out[0].decision.as_deref(), Some("allow"), "main(ctx) fallback used");

        let empty = HookRunner::empty(&dir);
        assert!(empty.is_empty());
        let out = run_kind(&empty, HookKind::Stop, json!({})).await;
        assert!(out.is_empty());
    }

    #[tokio::test]
    async fn first_deny_short_circuits_the_sequence() {
        let dir = tmp("short");
        fs::write(dir.join("a.ts"), "export default () => ({ decision: \"deny\", reason: \"a\" });\n").unwrap();
        fs::write(dir.join("b.ts"), "export default () => ({ feedback: \"b ran\" });\n").unwrap();
        let cfg_path = write_cfg(&dir, "[hooks]\npre_tool_use = [\"a.ts\", \"b.ts\"]\n");
        let cfg = crate::config::XenConfig::load(&cfg_path).unwrap();
        let runner = HookRunner::from_config(&cfg_path, &cfg.hooks);

        let out = run_kind(&runner, HookKind::PreToolUse, json!({})).await;
        assert_eq!(out.len(), 1, "b.ts must not run after a.ts denied");
        assert_eq!(out[0].reason.as_deref(), Some("a"));
    }
}
