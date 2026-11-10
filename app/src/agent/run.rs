//! The agent loop: prompt → model → tools → model → … → final answer.

use std::path::{Path, PathBuf};
use std::pin::Pin;
use std::sync::Arc;
use std::time::Instant;

use anyhow::{bail, Result};
use serde_json::Value;
use tokio::sync::mpsc::UnboundedSender;

use super::compact;
use super::permissions::{Decision, PermissionGate, Request, Response};
use super::provider::{Message, Provider, Role, ToolCall, ToolDef};
use super::{checkpoint, diagnostics, mcp, oracle, repomap, session, subagent, telemetry, tools};
use xencode_script::hooks::{HookKind, HookRunner};

/// Progress events for a UI or CLI printer.
#[derive(Clone)]
pub enum AgentEvent {
    Iteration(usize),
    Text(String),
    ToolStart { name: String, summary: String },
    ToolEnd { name: String, ok: bool, ms: u128 },
    /// A consent prompt was resolved for `tool` (`allowed` = the outcome).
    Ask { tool: String, detail: String, allowed: bool },
    /// A hook script ran at a loop checkpoint (`ok` = ran to completion).
    HookRun { kind: String, script: String, ok: bool },
    /// Console output produced by a hook script (CLI stderr only).
    HookLog(String),
    /// Older turns were folded into a summary (chars before → after).
    Compacted { before: usize, after: usize },
    /// A subagent run nested under this loop (`name` = the agent's name);
    /// `event` is the child-loop event it emitted.
    Subagent { name: String, event: Box<AgentEvent> },
    /// A shadow-git checkpoint was recorded before a mutating tool ran.
    Checkpoint { tool: String, detail: String },
    /// The final message list — what a session store / GUI transcript renders.
    Transcript(Arc<Vec<Message>>),
    /// The diagnostics loop ran after an edit (`count` = new problems found).
    Diagnostics { source: String, count: usize, detail: String },
    Done,
}

/// Callback that resolves a permission prompt (stdin in the CLI, a GUI
/// consent card in the chat pane, a scripted answer in tests).
pub trait Asker: Send {
    fn ask(&mut self, req: &Request) -> Response;
}

/// Adapter so closures can act as askers.
pub struct AskFn<F: FnMut(&Request) -> Response + Send>(pub F);

impl<F: FnMut(&Request) -> Response + Send> Asker for AskFn<F> {
    fn ask(&mut self, req: &Request) -> Response {
        (self.0)(req)
    }
}

pub struct AgentOptions {
    pub max_iterations: usize,
    pub cwd: PathBuf,
    pub gate: Box<dyn PermissionGate>,
    pub session_dir: Option<PathBuf>,
    /// Prior conversation to seed the loop (resume/fork). Must already start
    /// with the system message; an empty vec starts a fresh session.
    pub history: Vec<Message>,
    /// Resolver for `Decision::Ask`. Without one, asks fail closed (deny).
    pub asker: Option<Box<dyn Asker>>,
    /// Transcript budget in chars; older turns compact when exceeded.
    /// `None` disables compaction.
    pub compact_chars: Option<usize>,
    /// Agent hooks from `xen.toml` (`[hooks]`) — PreToolUse / PostToolUse /
    /// Stop, each executed in its own sandboxed QuickJS runtime. `None` or
    /// an empty runner skips the machinery entirely.
    pub hooks: Option<Arc<HookRunner>>,
    /// Plan mode: the system prompt instructs research-only behavior and
    /// mutations are blocked until the plan is approved (Phase 3).
    pub plan_mode: bool,
    /// MCP servers (Phase 4): their tools join the registry as
    /// `mcp__<server>__<tool>` and calls are routed over JSON-RPC stdio.
    pub mcp: Option<Arc<mcp::McpManager>>,
    /// Shadow-git checkpoint store (Phase 4): a snapshot is committed before
    /// every mutating tool so any edit can be rewound.
    pub checkpoints: Option<Arc<checkpoint::CheckpointStore>>,
    /// Extra system-prompt text appended after the base rules (the subagent
    /// definition prompt).
    pub system_extra: Option<String>,
    /// Whether the `task` tool is registered (children cut off recursion).
    pub allow_subagents: bool,
    /// Repo map (Phase 5): `Some(budget_chars)` renders a relevance-ranked
    /// tree-sitter symbol map into the system prompt. Skipped for scoped
    /// child loops (`system_extra` present) — they carry their own prompt.
    pub repo_map: Option<usize>,
    /// Diagnostics engine (Phase 5): after write/edit tools, the project
    /// check runs once and new errors are pushed into the loop.
    pub diagnostics: Option<Arc<diagnostics::DiagnosticsEngine>>,
    /// Run telemetry (Phase 5): spans (run/llm/tool), tokens, cost — JSONL
    /// under `.xencode/telemetry/` plus optional OTLP export at finish.
    pub telemetry: Option<Arc<telemetry::Telemetry>>,
    /// Oracle setup (Phase 5): when present, the `oracle` tool joins the
    /// registry and consultations run as a read-only nested loop on the
    /// stronger model.
    pub oracle: Option<Arc<oracle::OracleSetup>>,
}

impl Default for AgentOptions {
    fn default() -> Self {
        Self {
            max_iterations: 25,
            cwd: std::env::current_dir().unwrap_or_else(|_| PathBuf::from(".")),
            gate: Box::new(super::permissions::AllowAll),
            session_dir: None,
            history: Vec::new(),
            asker: None,
            compact_chars: Some(compact::DEFAULT_BUDGET_CHARS),
            hooks: None,
            plan_mode: false,
            mcp: None,
            checkpoints: None,
            system_extra: None,
            allow_subagents: true,
            repo_map: None,
            diagnostics: None,
            telemetry: None,
            oracle: None,
        }
    }
}

/// Drive the agent to completion. Returns the assistant's final text answer.
/// The provider is shared (`Arc<dyn>`) so `task` calls can fan child loops
/// out across tokio tasks in parallel.
///
/// The future is boxed: child loops recurse into this entry, and the boxed
/// `+ Send` bound terminates the auto-trait cycle (a recursive async fn's
/// future can never prove `Send` on its own).
pub fn run_agent<'a>(
    provider: Arc<dyn Provider>,
    prompt: &'a str,
    opts: AgentOptions,
    tx: UnboundedSender<AgentEvent>,
) -> Pin<Box<dyn std::future::Future<Output = Result<String>> + Send + 'a>> {
    Box::pin(run_agent_inner(provider, prompt, opts, tx))
}

async fn run_agent_inner(
    provider: Arc<dyn Provider>,
    prompt: &str,
    opts: AgentOptions,
    tx: UnboundedSender<AgentEvent>,
) -> Result<String> {
    let AgentOptions {
        max_iterations,
        cwd,
        gate,
        ref session_dir,
        history,
        mut asker,
        compact_chars,
        ref hooks,
        plan_mode,
        ref mcp,
        ref checkpoints,
        ref system_extra,
        allow_subagents,
        ref repo_map,
        ref diagnostics,
        ref telemetry,
        ref oracle,
    } = opts;
    let mut tool_defs: Vec<ToolDef> = tools::tool_defs();
    // Subagents (Phase 4): the `task` tool is always available — at least the
    // built-in `general-purpose` agent exists even with no `agents/` files.
    // Child loops run with `allow_subagents: false` (no recursion).
    if allow_subagents {
        tool_defs.push(subagent::task_tool_def());
    }
    // MCP tools (Phase 4): `mcp__<server>__<tool>`, discovered at connect time.
    if let Some(m) = mcp {
        tool_defs.extend(m.tool_defs());
    }
    // Oracle (Phase 5): second-opinion consultations join the registry.
    if oracle.is_some() {
        tool_defs.push(oracle::tool_def());
    }
    let ctx = tools::ToolCtx { cwd: cwd.clone() };

    // Phase 5 telemetry: the run span is recorded on drop (error paths keep
    // their span); the ok-path finishes it explicitly before returning.
    let run_guard = telemetry.as_ref().map(|t| t.run_span());

    // Phase 5 repo map: a relevance-ranked skeleton of the codebase joins the
    // system prompt. Scoped child loops (subagents/oracle) carry their own
    // prompt instead — the map is for the top-level agent.
    let repo_map_section = match (repo_map, system_extra.is_none()) {
        (Some(budget), true) => {
            let map = repomap::build(&cwd, *budget);
            if map.text.is_empty() {
                None
            } else {
                let note = if map.truncated {
                    format!("\n(map truncated to {budget} chars — read files for detail)")
                } else {
                    String::new()
                };
                Some(format!(
                    "\n\n# Repo map\n\nCurrent code skeleton — most-referenced files first, \
                     signatures with line numbers. Use it to decide what to read; \
                     it may not include every file.{}\n\n{}",
                    note, map.text
                ))
            }
        }
        _ => None,
    };

    // Hook scripts' console output flows through its own channel; a drainer
    // forwards it to the UI as HookLog events for the whole run.
    let (hook_tx, mut hook_rx) = xencode_script::events::channel();
    let hook_log_tx = tx.clone();
    tokio::spawn(async move {
        while let Some(ev) = hook_rx.recv().await {
            if let Some(line) = xencode_script::events::format_cli(&ev) {
                let _ = hook_log_tx.send(AgentEvent::HookLog(line));
            }
        }
    });

    // Seed the loop: fresh sessions get system+prompt; resumes append to history.
    let mut messages = if history.is_empty() {
        vec![Message::system(system_prompt(&cwd, plan_mode, system_extra.as_deref(), repo_map_section.as_deref())), Message::user(prompt)]
    } else {
        let mut h = history;
        if !h.iter().any(|m| m.role == Role::System) {
            h.insert(0, Message::system(system_prompt(&cwd, plan_mode, system_extra.as_deref(), repo_map_section.as_deref())));
        }
        h.push(Message::user(prompt));
        h
    };

    for iteration in 1..=max_iterations {
        // Compact when the transcript outgrows the budget.
        if let Some(budget) = compact_chars {
            let size = compact::estimate_chars(&messages);
            if size > budget && messages.len() >= 6 {
                match compact::compact(&provider, &messages, budget / 2).await {
                    Ok(next) if compact::estimate_chars(&next) < size => {
                        let after = compact::estimate_chars(&next);
                        messages = next;
                        let _ = tx.send(AgentEvent::Compacted { before: size, after });
                    }
                    _ => {} // summarization failed or did not shrink; keep going
                }
            }
        }

        let _ = tx.send(AgentEvent::Iteration(iteration));

        // Forward the provider's streaming deltas to the UI as Text events.
        let (delta_tx, mut delta_rx) = tokio::sync::mpsc::unbounded_channel::<String>();
        let pump = {
            let tx = tx.clone();
            tokio::spawn(async move {
                while let Some(s) = delta_rx.recv().await {
                    let _ = tx.send(AgentEvent::Text(s));
                }
            })
        };
        let assistant = {
            let t0 = Instant::now();
            let result = provider.complete(&messages, &tool_defs, Some(&delta_tx)).await;
            let ms = t0.elapsed().as_millis();
            match result {
                Ok(a) => {
                    // Phase 5 telemetry: one llm.call span per provider turn.
                    // When the API did not report usage, estimate from the
                    // transcript (input) and answer (output) sizes.
                    if let Some(t) = telemetry {
                        let usage = a.usage.unwrap_or(telemetry::TokenUsage {
                            input: (compact::estimate_chars(&messages) as u64) / 4,
                            output: (a.content.chars().count() as u64) / 4,
                            estimated: true,
                        });
                        t.llm_call(provider.model(), &usage, ms, true);
                    }
                    a
                }
                Err(e) => {
                    if let Some(t) = telemetry {
                        t.llm_call(provider.model(), &telemetry::TokenUsage::default(), ms, false);
                    }
                    return Err(e);
                }
            }
        };
        drop(delta_tx);
        let _ = pump.await;

        if assistant.tool_calls.is_empty() {
            // Stop hooks may block stopping: the reason is pushed into the
            // loop as a user turn and the model keeps working (bounded by
            // max_iterations like every other path).
            if let Some(reason) = stop_hook_block(hooks.as_ref(), &assistant.content, iteration, hook_tx.clone(), &tx).await {
                if iteration < max_iterations {
                    messages.push(assistant.clone());
                    messages.push(Message::user(format!(
                        "[Stop hook] Your reply was blocked: {reason}\nContinue working on the task."
                    )));
                    continue;
                }
            }
            if let Some(g) = run_guard {
                g.finish("ok", serde_json::json!({ "iterations": iteration, "answer_chars": assistant.content.chars().count() }));
            }
            if let Some(dir) = session_dir {
                messages.push(assistant.clone());
                let _ = session::Session::from_messages(&messages).save(dir);
            }
            let _ = tx.send(AgentEvent::Transcript(Arc::new(messages.clone())));
            let _ = tx.send(AgentEvent::Done);
            return Ok(assistant.content);
        }

        let calls = assistant.tool_calls.clone();
        messages.push(assistant);

        // Phase 4: consecutive `task` calls in one turn form a parallel group
        // (subagents fan out across tokio tasks); everything else stays
        // strictly sequential, preserving the existing ordering guarantees.
        let mut groups: Vec<Vec<ToolCall>> = Vec::new();
        for call in calls {
            match groups.last_mut() {
                Some(g) if g[0].name == "task" && call.name == "task" => g.push(call),
                _ => groups.push(vec![call]),
            }
        }

        for group in groups {
            if group[0].name == "task" {
                dispatch_subagents(
                    group,
                    &provider,
                    &cwd,
                    checkpoints.as_ref(),
                    &gate,
                    &mut asker,
                    hooks.as_ref(),
                    hook_tx.clone(),
                    &tx,
                    &mut messages,
                )
                .await;
            } else {
                for call in group {
                    // Oracle calls get the dedicated card summary; everything
                    // else uses the generic argument summarizer.
                    let detail = if call.name == "oracle" {
                        oracle::describe(&call.arguments)
                    } else {
                        summarize(&call.arguments)
                    };
                    let mut decision = gate.review(&call.name, &call.arguments);
                    let mut prompted = false;
                    if decision == Decision::Ask {
                        prompted = true;
                        decision = match asker.as_mut() {
                            Some(a) => match a.ask(&Request { tool: call.name.clone(), detail: detail.clone() }) {
                                Response::Allow => Decision::Allow,
                                Response::AllowAlways => {
                                    gate.allow_always(&call.name);
                                    Decision::Allow
                                }
                                Response::Deny => Decision::Deny,
                            },
                            // No way to ask a human — fail closed.
                            None => Decision::Deny,
                        };
                    }
                    // Only real prompts (and their outcomes) reach the UI as cards;
                    // policy allow/deny never surfaces a consent card.
                    if prompted {
                        let _ = tx.send(AgentEvent::Ask {
                            tool: call.name.clone(),
                            detail: detail.clone(),
                            allowed: decision == Decision::Allow,
                        });
                    }

                    if decision == Decision::Deny {
                        let _ = tx.send(AgentEvent::ToolStart {
                            name: call.name.clone(),
                            summary: "(denied by permission gate)".into(),
                        });
                        let _ = tx.send(AgentEvent::ToolEnd { name: call.name.clone(), ok: false, ms: 0 });
                        messages.push(Message::tool_result(&call.id, gate.denial_message()));
                        continue;
                    }

                    // PreToolUse hooks run after the permission gate and can still
                    // block the call with an explicit `{ decision: "deny" }` verdict.
                    if let Some(block) = pre_tool_hook(hooks.as_ref(), &call.name, &call.arguments, &cwd, hook_tx.clone(), &tx).await {
                        let _ = tx.send(AgentEvent::ToolStart {
                            name: call.name.clone(),
                            summary: "(blocked by PreToolUse hook)".into(),
                        });
                        let _ = tx.send(AgentEvent::ToolEnd { name: call.name.clone(), ok: false, ms: 0 });
                        messages.push(Message::tool_result(&call.id, block));
                        continue;
                    }

                    // Phase 4: commit a shadow-git checkpoint before every
                    // mutation so the edit can be rewound from the timeline.
                    if matches!(call.name.as_str(), "write_file" | "edit_file" | "bash") {
                        if let Some(cp) = checkpoints {
                            if let Some(c) = cp.snapshot(&call.name, &detail).await {
                                let _ = tx.send(AgentEvent::Checkpoint {
                                    tool: call.name.clone(),
                                    detail: c.detail,
                                });
                            }
                        }
                    }

                    let _ = tx.send(AgentEvent::ToolStart {
                        name: call.name.clone(),
                        summary: detail,
                    });
                    let t0 = Instant::now();
                    // Tool routing: the oracle (Phase 5) consults the stronger
                    // model as a nested loop; MCP tools (Phase 4) route over
                    // JSON-RPC stdio; built-ins run through the deterministic
                    // tool set.
                    let result: Result<String> = if call.name == "oracle" {
                        match oracle {
                            Some(o) => oracle::consult(o, &call.arguments, &cwd, tx.clone()).await,
                            None => Err(anyhow::anyhow!(
                                "oracle tool used but no oracle is configured"
                            )),
                        }
                    } else {
                        match call.name.strip_prefix("mcp__") {
                            Some(rest) => match mcp {
                                Some(m) => m.call(rest, &call.arguments).await,
                                None => Err(anyhow::anyhow!(
                                    "mcp tool used but no servers are connected: {}",
                                    call.name
                                )),
                            },
                            None => tools::execute(&call.name, &call.arguments, &ctx).await,
                        }
                    };
                    let ms = t0.elapsed().as_millis();
                    let ok = result.is_ok();
                    let mut text = result.unwrap_or_else(|e| format!("Error: {e:#}"));
                    let _ = tx.send(AgentEvent::ToolEnd { name: call.name.clone(), ok, ms });
                    if let Some(t) = telemetry {
                        t.tool_call(&call.name, ok, ms);
                    }

                    // PostToolUse hooks may attach feedback that joins the result
                    // the model sees for this tool call.
                    post_tool_hook(hooks.as_ref(), &call.name, &call.arguments, &mut text, ok, ms, hook_tx.clone(), &tx).await;

                    messages.push(Message::tool_result(&call.id, text));

                    // Phase 5 diagnostics loop: after a source edit the project
                    // check runs once; new problems are pushed as a user turn so
                    // the model sees (and fixes) the compiler's verdict.
                    if matches!(call.name.as_str(), "write_file" | "edit_file") {
                        if let Some(dx) = diagnostics {
                            let edited = call.arguments.get("path").and_then(Value::as_str).unwrap_or_default();
                            if dx.triggers_on(edited) {
                                if let Some((count, feedback)) = dx.new_problems(&cwd).await {
                                    let _ = tx.send(AgentEvent::Diagnostics {
                                        source: dx.source().to_string(),
                                        count,
                                        detail: feedback.clone(),
                                    });
                                    messages.push(Message::user(feedback));
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    bail!("agent stopped: reached max iterations ({})", max_iterations)
}

/// Execute a parallel group of `task` calls (Phase 4 subagents).
///
/// Gate/ask/hook verdicts are resolved sequentially (a human prompt must not
/// race), then every approved child agent loop fans out across tokio tasks —
/// child events stream to the UI wrapped as [`AgentEvent::Subagent`], and the
/// child's final report becomes the tool result the model sees.
#[allow(clippy::too_many_arguments)]
async fn dispatch_subagents(
    group: Vec<ToolCall>,
    provider: &Arc<dyn Provider>,
    cwd: &Path,
    checkpoints: Option<&Arc<checkpoint::CheckpointStore>>,
    gate: &Box<dyn PermissionGate>,
    asker: &mut Option<Box<dyn Asker>>,
    hooks: Option<&Arc<HookRunner>>,
    hook_tx: xencode_script::events::UiEventTx,
    tx: &tokio::sync::mpsc::UnboundedSender<AgentEvent>,
    messages: &mut Vec<Message>,
) {
    let mut approved: Vec<(ToolCall, String)> = Vec::new();
    for call in group {
        let detail = subagent::describe(&call.arguments);
        let mut decision = gate.review(&call.name, &call.arguments);
        let mut prompted = false;
        if decision == Decision::Ask {
            prompted = true;
            decision = match asker.as_mut() {
                Some(a) => match a.ask(&Request { tool: call.name.clone(), detail: detail.clone() }) {
                    Response::Allow => Decision::Allow,
                    Response::AllowAlways => {
                        gate.allow_always(&call.name);
                        Decision::Allow
                    }
                    Response::Deny => Decision::Deny,
                },
                None => Decision::Deny, // fail closed
            };
        }
        if prompted {
            let _ = tx.send(AgentEvent::Ask {
                tool: call.name.clone(),
                detail: detail.clone(),
                allowed: decision == Decision::Allow,
            });
        }
        if decision == Decision::Deny {
            let _ = tx.send(AgentEvent::ToolStart {
                name: call.name.clone(),
                summary: "(denied by permission gate)".into(),
            });
            let _ = tx.send(AgentEvent::ToolEnd { name: call.name.clone(), ok: false, ms: 0 });
            messages.push(Message::tool_result(&call.id, gate.denial_message()));
            continue;
        }
        if let Some(block) = pre_tool_hook(hooks, &call.name, &call.arguments, cwd, hook_tx.clone(), tx).await {
            let _ = tx.send(AgentEvent::ToolStart {
                name: call.name.clone(),
                summary: "(blocked by PreToolUse hook)".into(),
            });
            let _ = tx.send(AgentEvent::ToolEnd { name: call.name.clone(), ok: false, ms: 0 });
            messages.push(Message::tool_result(&call.id, block));
            continue;
        }
        approved.push((call, detail));
    }
    if approved.is_empty() {
        return;
    }

    // Announce every start up front (one card per child, in call order).
    for (call, detail) in &approved {
        let _ = tx.send(AgentEvent::ToolStart {
            name: call.name.clone(),
            summary: detail.clone(),
        });
    }
    let t0 = Instant::now();
    let mut set = tokio::task::JoinSet::new();
    for (call, _) in &approved {
        let provider = provider.clone();
        let cwd = cwd.to_path_buf();
        let checkpoints = checkpoints.cloned();
        let tx = tx.clone();
        let call = call.clone();
        set.spawn(async move {
            let report = subagent::run_child(&call, provider, &cwd, checkpoints, tx).await;
            (call.id, report)
        });
    }
    let mut outcomes: std::collections::HashMap<String, anyhow::Result<String>> =
        std::collections::HashMap::new();
    while let Some(res) = set.join_next().await {
        if let Ok((id, report)) = res {
            outcomes.insert(id, report);
        }
    }

    // Attach results in the original call order; post-tool hooks still see
    // every child report.
    let ms = t0.elapsed().as_millis();
    for (call, _) in &approved {
        let report = outcomes.remove(&call.id)
            .unwrap_or_else(|| Err(anyhow::anyhow!("subagent vanished")));
        let ok = report.is_ok();
        let mut text = report.unwrap_or_else(|e| format!("Error: {e:#}"));
        let _ = tx.send(AgentEvent::ToolEnd { name: call.name.clone(), ok, ms });
        post_tool_hook(hooks, &call.name, &call.arguments, &mut text, ok, ms, hook_tx.clone(), tx).await;
        messages.push(Message::tool_result(&call.id, text));
    }
}

fn system_prompt(cwd: &Path, plan_mode: bool, extra: Option<&str>, repo_map: Option<&str>) -> String {
    let mut p = format!(
        "You are XenCode, a coding agent working inside the user's project at {}.\n\n\
         Rules:\n\
         - Use the provided tools to read, search, edit and run code. Never guess file contents — read first.\n\
         - Prefer edit_file with a minimal old/new pair over rewriting whole files.\n\
         - Verify your changes: run builds or tests with bash when you can.\n\
         - Keep answers short and concrete. When you are done, reply with the final summary only — no tool calls.",
        cwd.display()
    );

    // Phase 5: repo map — relevance-ranked code skeleton (Aider-style).
    if let Some(map) = repo_map {
        p.push_str(map);
    }

    // Phase 3: memory files auto-loaded into context (XENCODE.md / AGENTS.md).
    if let Some(memory) = super::memory::system_memory_section(cwd) {
        p.push_str(&memory);
    }

    // Phase 4: subagent identity prompt appended after the base rules.
    if let Some(extra) = extra {
        p.push_str("\n\n# Your role\n\n");
        p.push_str(extra);
    }

    if plan_mode {
        p.push_str(
            "\n\n# Plan mode\n\n\
             PLAN MODE IS ACTIVE. You may research freely (read, grep, list, read-only bash) \n\
             but every mutating tool call is blocked. Produce a step-by-step implementation \n\
             plan as your final answer: files to touch, the change for each, verification \n\
             steps, and risks. The user will review the plan before any edit happens.",
        );
    }
    p
}

/// Run PreToolUse hooks; `Some(message)` means the call is blocked and the
/// message becomes the tool result the model sees.
async fn pre_tool_hook(
    hooks: Option<&Arc<HookRunner>>,
    tool: &str,
    args: &Value,
    cwd: &Path,
    hook_tx: xencode_script::events::UiEventTx,
    tx: &tokio::sync::mpsc::UnboundedSender<AgentEvent>,
) -> Option<String> {
    let hooks = hooks?;
    if !hooks.has(HookKind::PreToolUse) {
        return None;
    }
    let payload = serde_json::json!({
        "hook": HookKind::PreToolUse.as_str(),
        "tool": tool,
        "args": args,
        "cwd": cwd.display().to_string(),
    });
    let outcomes = hooks.run(HookKind::PreToolUse, payload, hook_tx).await;
    for o in outcomes {
        let _ = tx.send(AgentEvent::HookRun {
            kind: HookKind::PreToolUse.as_str().into(),
            script: o.script.clone(),
            ok: o.ok,
        });
        if o.denies() {
            let reason = o.reason.unwrap_or_else(|| "no reason given".into());
            return Some(format!("Blocked by PreToolUse hook ({}): {reason}", o.script));
        }
    }
    None
}

/// Run PostToolUse hooks; feedback verdicts are appended to the tool result.
async fn post_tool_hook(
    hooks: Option<&Arc<HookRunner>>,
    tool: &str,
    args: &Value,
    text: &mut String,
    ok: bool,
    ms: u128,
    hook_tx: xencode_script::events::UiEventTx,
    tx: &tokio::sync::mpsc::UnboundedSender<AgentEvent>,
) {
    let Some(hooks) = hooks else { return };
    if !hooks.has(HookKind::PostToolUse) {
        return;
    }
    let payload = serde_json::json!({
        "hook": HookKind::PostToolUse.as_str(),
        "tool": tool,
        "args": args,
        "result": text,
        "ok": ok,
        "ms": ms,
    });
    let outcomes = hooks.run(HookKind::PostToolUse, payload, hook_tx).await;
    for o in outcomes {
        let _ = tx.send(AgentEvent::HookRun {
            kind: HookKind::PostToolUse.as_str().into(),
            script: o.script.clone(),
            ok: o.ok,
        });
        if let Some(feedback) = o.feedback {
            text.push_str(&format!("\n\n[hook feedback from {}]: {feedback}", o.script));
        }
    }
}

/// Run Stop hooks; `Some(reason)` blocks stopping and feeds the reason back.
async fn stop_hook_block(
    hooks: Option<&Arc<HookRunner>>,
    answer: &str,
    iterations: usize,
    hook_tx: xencode_script::events::UiEventTx,
    tx: &tokio::sync::mpsc::UnboundedSender<AgentEvent>,
) -> Option<String> {
    let hooks = hooks?;
    if !hooks.has(HookKind::Stop) {
        return None;
    }
    let payload = serde_json::json!({
        "hook": HookKind::Stop.as_str(),
        "answer": answer,
        "iterations": iterations,
    });
    let outcomes = hooks.run(HookKind::Stop, payload, hook_tx).await;
    for o in outcomes {
        let _ = tx.send(AgentEvent::HookRun {
            kind: HookKind::Stop.as_str().into(),
            script: o.script.clone(),
            ok: o.ok,
        });
        if o.blocks() {
            return Some(o.reason.unwrap_or_else(|| "stop blocked by hook".into()));
        }
    }
    None
}

pub(crate) fn summarize(args: &Value) -> String {
    let value = ["path", "command", "pattern", "todos", "agent", "question"]
        .iter()
        .find_map(|k| args.get(*k))
        .cloned()
        .unwrap_or_default();
    let text = value.to_string().trim_matches('"').to_string();
    if text.chars().count() > 60 {
        text.chars().take(60).collect()
    } else {
        text
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::agent::permissions::{self, AskGate, ReadOnly};
    use crate::agent::provider::{MockProvider, Role, ToolCall};
    use serde_json::json;
    use std::sync::Arc;
    use tokio::sync::mpsc;

    fn tmp(tag: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("xencode-test-{}-{}", tag, std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn tool_turn(id: &str, name: &str, args: Value) -> Message {
        Message {
            role: Role::Assistant,
            content: String::new(),
            tool_calls: vec![ToolCall { id: id.into(), name: name.into(), arguments: args }],
            tool_call_id: None,
            usage: None,
        }
    }

    fn final_turn(text: &str) -> Message {
        Message {
            role: Role::Assistant,
            content: text.into(),
            tool_calls: vec![],
            tool_call_id: None,
            usage: None,
        }
    }

    #[tokio::test]
    async fn executes_tools_until_final_answer() {
        let dir = tmp("loop");
        std::fs::write(dir.join("hello.txt"), "hello from xencode").unwrap();

        let mock = MockProvider::new(vec![
            tool_turn("t1", "read_file", json!({"path": "hello.txt"})),
            final_turn("The file says hello from xencode"),
        ]);
        let (tx, mut rx) = mpsc::unbounded_channel();
        let opts = AgentOptions {
            cwd: dir.clone(),
            session_dir: Some(dir.join(".xencode")),
            ..Default::default()
        };

        let answer = run_agent(Arc::new(mock), "what does hello.txt say", opts, tx).await.unwrap();
        assert!(answer.contains("hello from xencode"));

        let mut saw_tool_end = false;
        while let Ok(ev) = rx.try_recv() {
            if let AgentEvent::ToolEnd { ok: true, .. } = ev {
                saw_tool_end = true;
            }
        }
        assert!(saw_tool_end, "expected a successful ToolEnd event");

        let sessions = session::Session::list(&dir.join(".xencode")).unwrap();
        assert_eq!(sessions.len(), 1, "session should be saved");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn permission_gate_blocks_writes() {
        let dir = tmp("gate");
        let mock = MockProvider::new(vec![
            tool_turn("t1", "write_file", json!({"path": "evil.txt", "content": "nope"})),
            final_turn("the write was blocked"),
        ]);
        let (tx, _rx) = mpsc::unbounded_channel();
        let opts = AgentOptions {
            cwd: dir.clone(),
            gate: Box::new(ReadOnly),
            ..Default::default()
        };

        let answer = run_agent(Arc::new(mock), "write evil.txt", opts, tx).await.unwrap();
        assert!(answer.contains("blocked"));
        assert!(!dir.join("evil.txt").exists(), "denied tool must not run");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn ask_gate_prompts_and_remember_always_allow() {
        use std::sync::atomic::{AtomicUsize, Ordering};
        use std::sync::Arc as StdArc;

        let dir = tmp("ask");
        let mock = MockProvider::new(vec![
            tool_turn("t1", "write_file", json!({"path": "denied.txt", "content": "no"})),
            tool_turn("t2", "write_file", json!({"path": "allowed.txt", "content": "yes"})),
            final_turn("done"),
        ]);
        let (tx, mut rx) = mpsc::unbounded_channel();

        let asks = StdArc::new(AtomicUsize::new(0));
        let counter = asks.clone();
        let asker = AskFn(move |_req: &Request| {
            let n = counter.fetch_add(1, Ordering::SeqCst);
            if n == 0 {
                Response::Deny
            } else {
                Response::AllowAlways
            }
        });
        let opts = AgentOptions {
            cwd: dir.clone(),
            gate: Box::new(AskGate::new()),
            asker: Some(Box::new(asker)),
            ..Default::default()
        };

        let answer = run_agent(Arc::new(mock), "write two files", opts, tx).await.unwrap();
        assert_eq!(answer, "done");
        assert!(!dir.join("denied.txt").exists(), "denied prompt must not run");
        assert!(dir.join("allowed.txt").exists(), "approved prompt must run");
        assert_eq!(asks.load(Ordering::SeqCst), 2, "asked twice (deny, then always)");

        let mut saw_ask = false;
        while let Ok(ev) = rx.try_recv() {
            if let AgentEvent::Ask { allowed, .. } = ev {
                saw_ask = true;
                let _ = allowed;
            }
        }
        assert!(saw_ask, "Ask events must reach the UI channel");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn ask_without_asker_fails_closed() {
        let dir = tmp("closed");
        let mock = MockProvider::new(vec![
            tool_turn("t1", "bash", json!({"command": "echo nope"})),
            final_turn("could not run"),
        ]);
        let (tx, _rx) = mpsc::unbounded_channel();
        let opts = AgentOptions {
            cwd: dir.clone(),
            gate: Box::new(AskGate::new()),
            asker: None, // cannot ask → deny
            ..Default::default()
        };
        run_agent(Arc::new(mock), "run echo", opts, tx).await.unwrap();
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn resume_seeds_history_and_saves_extended_session() {
        let dir = tmp("resume");
        let session_dir = dir.join(".xencode");

        let mock = MockProvider::new(vec![final_turn("continued fine")]);
        let (tx, _rx) = mpsc::unbounded_channel();
        let history = vec![
            Message::system("system prompt"),
            Message::user("earlier question"),
            Message {
                role: Role::Assistant,
                content: "earlier answer".into(),
                tool_calls: vec![],
                tool_call_id: None,
                usage: None,
            },
        ];
        let opts = AgentOptions {
            cwd: dir.clone(),
            session_dir: Some(session_dir.clone()),
            history,
            ..Default::default()
        };
        let answer = run_agent(Arc::new(mock), "now continue", opts, tx).await.unwrap();
        assert_eq!(answer, "continued fine");

        let id = session::Session::latest(&session_dir).unwrap().unwrap();
        let s = session::Session::load(&session_dir, &id).unwrap();
        assert_eq!(s.messages[1].content, "earlier question", "history preserved");
        assert_eq!(s.messages[3].content, "now continue", "new prompt appended");
        assert_eq!(s.messages[4].content, "continued fine");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn compaction_triggers_when_transcript_outgrows_budget() {
        let dir = tmp("compact");
        let big = "y".repeat(2_000);

        // Order matters: the compaction summarizer consumes the first turn,
        // the loop's first model call consumes the second.
        let mock = MockProvider::new(vec![
            Message {
                role: Role::Assistant,
                content: "User asked about big.txt; it was read; task continues.".into(),
                tool_calls: vec![],
                tool_call_id: None,
                usage: None,
            },
            final_turn("all done"),
        ]);
        let (tx, mut rx) = mpsc::unbounded_channel();
        let history = vec![
            Message::system("system prompt"),
            Message::user(format!("task {big}")),
            Message::user("second turn {".to_string() + &big + "}"),
            Message::user("third turn {".to_string() + &big + "}"),
            Message::user("fourth turn {".to_string() + &big + "}"),
        ];
        let opts = AgentOptions {
            cwd: dir,
            session_dir: None,
            history,
            compact_chars: Some(1_000), // force the trigger
            ..Default::default()
        };
        let answer = run_agent(Arc::new(mock), "finish it", opts, tx).await.unwrap();
        assert_eq!(answer, "all done");

        let mut compacted = false;
        let mut summary_in_transcript = false;
        while let Ok(ev) = rx.try_recv() {
            match ev {
                AgentEvent::Compacted { before, after } => {
                    compacted = true;
                    assert!(after < before);
                }
                AgentEvent::Transcript(msgs) => {
                    summary_in_transcript = msgs
                        .iter()
                        .any(|m| m.content.starts_with("[Context summary of earlier turns"));
                }
                _ => {}
            }
        }
        assert!(compacted, "Compacted event must fire");
        assert!(summary_in_transcript, "summary message must be in the final transcript");
    }

    #[tokio::test]
    async fn tools_read_edit_grep() {
        let dir = tmp("tools");
        std::fs::write(dir.join("a.rs"), "fn alpha() {}\nfn gamma() {}\n").unwrap();
        let ctx = tools::ToolCtx { cwd: dir.clone() };

        let found = tools::execute("grep", &json!({"pattern": "alpha"}), &ctx).await.unwrap();
        assert!(found.contains("a.rs"), "grep should find the match");

        let edited = tools::execute(
            "edit_file",
            &json!({"path": "a.rs", "old": "alpha", "new": "beta"}),
            &ctx,
        )
        .await
        .unwrap();
        assert!(edited.contains("1 occurrence"));

        let content = std::fs::read_to_string(dir.join("a.rs")).unwrap();
        assert!(content.contains("beta") && !content.contains("alpha"));

        let listing = tools::execute("list_dir", &json!({}), &ctx).await.unwrap();
        assert!(listing.contains("a.rs"));
        let _ = std::fs::remove_dir_all(&dir);
    }

    // ------------------------------------------------------------------
    // Phase 3: memory files, hooks, plan mode
    // ------------------------------------------------------------------

    /// Write a hook config into `dir` and return the runner for it.
    fn hook_runner(dir: &Path, hooks_toml: &str) -> Arc<HookRunner> {
        std::fs::write(dir.join("xen.toml"), hooks_toml).unwrap();
        let cfg = xencode_script::XenConfig::load(&dir.join("xen.toml")).unwrap();
        Arc::new(HookRunner::from_config(&dir.join("xen.toml"), &cfg.hooks))
    }

    #[tokio::test]
    async fn memory_files_reach_the_system_prompt() {
        let dir = tmp("mem-prompt");
        std::fs::write(dir.join("XENCODE.md"), "project convention: never use unwrap()").unwrap();

        let mock = MockProvider::new(vec![final_turn("noted")]);
        let (tx, mut rx) = mpsc::unbounded_channel();
        let opts = AgentOptions {
            cwd: dir.clone(),
            ..Default::default()
        };
        run_agent(Arc::new(mock), "hello", opts, tx).await.unwrap();

        let mut system = None;
        while let Ok(ev) = rx.try_recv() {
            if let AgentEvent::Transcript(msgs) = ev {
                system = Some(msgs[0].content.clone());
            }
        }
        let system = system.expect("transcript event");
        assert!(system.contains("never use unwrap()"), "memory must reach the prompt");
        assert!(system.contains("Project memory"));
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn pre_tool_use_hook_blocks_the_call() {
        let dir = tmp("pre-hook");
        std::fs::write(
            dir.join("guard.ts"),
            r#"
            export default function (ctx) {
              if (ctx.tool === "write_file" && ctx.args.path.includes("forbidden")) {
                return { decision: "deny", reason: "forbidden path" };
              }
              return { decision: "allow" };
            }
            "#,
        )
        .unwrap();
        let hooks = hook_runner(&dir, "[hooks]\npre_tool_use = [\"guard.ts\"]\n");

        let mock = MockProvider::new(vec![
            tool_turn("t1", "write_file", json!({"path": "forbidden.txt", "content": "nope"})),
            final_turn("the write was blocked"),
        ]);
        let (tx, mut rx) = mpsc::unbounded_channel();
        let opts = AgentOptions {
            cwd: dir.clone(),
            hooks: Some(hooks),
            ..Default::default()
        };
        run_agent(Arc::new(mock), "write it", opts, tx).await.unwrap();

        let mut blocked_text = None;
        let mut saw_hook_event = false;
        while let Ok(ev) = rx.try_recv() {
            match ev {
                AgentEvent::HookRun { kind, ok, .. } => {
                    if kind == "PreToolUse" && ok {
                        saw_hook_event = true;
                    }
                }
                AgentEvent::Transcript(msgs) => {
                    blocked_text = msgs
                        .iter()
                        .find(|m| m.role == Role::Tool)
                        .map(|m| m.content.clone());
                }
                _ => {}
            }
        }
        assert!(saw_hook_event, "HookRun event must reach the UI channel");
        let blocked = blocked_text.expect("tool result in transcript");
        assert!(blocked.contains("Blocked by PreToolUse hook"), "got: {blocked}");
        assert!(blocked.contains("forbidden path"));
        assert!(!dir.join("forbidden.txt").exists(), "hook must prevent the write");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn post_tool_use_feedback_joins_the_result() {
        let dir = tmp("post-hook");
        std::fs::write(
            dir.join("audit.ts"),
            r#"
            export default function (ctx) {
              if (ctx.ok && ctx.tool === "write_file") {
                return { feedback: "remember to run the formatter" };
              }
              return {};
            }
            "#,
        )
        .unwrap();
        let hooks = hook_runner(&dir, "[hooks]\npost_tool_use = [\"audit.ts\"]\n");

        let mock = MockProvider::new(vec![
            tool_turn("t1", "write_file", json!({"path": "out.txt", "content": "hi"})),
            final_turn("wrote it"),
        ]);
        let (tx, mut rx) = mpsc::unbounded_channel();
        let opts = AgentOptions {
            cwd: dir.clone(),
            hooks: Some(hooks),
            ..Default::default()
        };
        run_agent(Arc::new(mock), "write it", opts, tx).await.unwrap();

        let mut tool_text = None;
        while let Ok(ev) = rx.try_recv() {
            if let AgentEvent::Transcript(msgs) = ev {
                tool_text = msgs
                    .iter()
                    .find(|m| m.role == Role::Tool)
                    .map(|m| m.content.clone());
            }
        }
        let text = tool_text.expect("tool result");
        assert!(text.contains("Wrote"), "tool result present");
        assert!(
            text.contains("[hook feedback from") && text.contains("formatter"),
            "feedback must join the tool result, got: {text}"
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn stop_hook_blocks_until_the_answer_qualifies() {
        let dir = tmp("stop-hook");
        std::fs::write(
            dir.join("verify.ts"),
            r#"
            export default function (ctx) {
              if (!ctx.answer.includes("tests")) {
                return { decision: "block", reason: "report the test results" };
              }
              return {};
            }
            "#,
        )
        .unwrap();
        let hooks = hook_runner(&dir, "[hooks]\nstop = [\"verify.ts\"]\n");

        let mock = MockProvider::new(vec![
            final_turn("all done"),
            final_turn("done — tests pass"),
        ]);
        let (tx, mut rx) = mpsc::unbounded_channel();
        let opts = AgentOptions {
            cwd: dir.clone(),
            hooks: Some(hooks),
            ..Default::default()
        };
        let answer = run_agent(Arc::new(mock), "finish", opts, tx).await.unwrap();
        assert_eq!(answer, "done — tests pass", "blocked stop must continue the loop");

        let mut saw_stop_note = false;
        while let Ok(ev) = rx.try_recv() {
            if let AgentEvent::Transcript(msgs) = ev {
                saw_stop_note = msgs
                    .iter()
                    .any(|m| m.content.contains("[Stop hook]") && m.content.contains("test results"));
            }
        }
        assert!(saw_stop_note, "stop-hook reason must reach the transcript");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn plan_mode_blocks_mutations_until_gate_swap() {
        let dir = tmp("plan");
        std::fs::write(dir.join("note.txt"), "research me").unwrap();

        // Plan run: the model tries to write anyway — blocked by the gate.
        let mock = MockProvider::new(vec![
            tool_turn("t1", "write_file", json!({"path": "plan.txt", "content": "step 1"})),
            final_turn("Here is my plan: 1. write the file"),
        ]);
        let (tx, mut rx) = mpsc::unbounded_channel();
        let opts = AgentOptions {
            cwd: dir.clone(),
            gate: Box::new(permissions::PlanGate),
            plan_mode: true,
            ..Default::default()
        };
        let answer = run_agent(Arc::new(mock), "plan it", opts, tx).await.unwrap();
        assert!(answer.contains("Here is my plan"));
        assert!(!dir.join("plan.txt").exists(), "plan mode must block writes");

        let mut blocked = None;
        while let Ok(ev) = rx.try_recv() {
            if let AgentEvent::Transcript(msgs) = ev {
                blocked = msgs
                    .iter()
                    .find(|m| m.role == Role::Tool)
                    .map(|m| m.content.clone());
            }
        }
        assert!(
            blocked.unwrap_or_default().contains("Plan mode:"),
            "denial must explain plan mode"
        );

        // Execution run (post-approval): same tool now passes the AskGate.
        let mock2 = MockProvider::new(vec![
            tool_turn("t2", "write_file", json!({"path": "plan.txt", "content": "step 1"})),
            final_turn("implemented"),
        ]);
        let (tx2, _rx2) = mpsc::unbounded_channel();
        let opts2 = AgentOptions {
            cwd: dir.clone(),
            gate: Box::new(permissions::AllowAll),
            plan_mode: false,
            ..Default::default()
        };
        run_agent(Arc::new(mock2), "implement", opts2, tx2).await.unwrap();
        assert!(dir.join("plan.txt").exists(), "approved run writes");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn plan_mode_system_prompt_is_flagged() {
        let dir = tmp("plan-prompt");
        let mock = MockProvider::new(vec![final_turn("the plan")]);
        let (tx, mut rx) = mpsc::unbounded_channel();
        let opts = AgentOptions {
            cwd: dir.clone(),
            plan_mode: true,
            ..Default::default()
        };
        run_agent(Arc::new(mock), "plan it", opts, tx).await.unwrap();
        let mut system = None;
        while let Ok(ev) = rx.try_recv() {
            if let AgentEvent::Transcript(msgs) = ev {
                system = Some(msgs[0].content.clone());
            }
        }
        assert!(system.expect("transcript").contains("PLAN MODE IS ACTIVE"));
        let _ = std::fs::remove_dir_all(&dir);
    }
}
