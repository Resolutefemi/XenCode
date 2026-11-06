//! Interactive chat REPL with slash commands.

use anyhow::Result;
use rustyline::DefaultEditor;
use std::path::{Path, PathBuf};
use xencode_core::chat::ImagePart;
use xencode_core::permissions::PermissionMode;
use xencode_core::state::checkpoints::CheckpointStore;
use xencode_core::state::decisions::DecisionStore;
use xencode_core::state::events::EventLog;
use xencode_core::state::sessions::{Session, SessionMeta, SessionStore};
use xencode_core::store::chatlog::ChatPersister;
use xencode_core::telemetry::{Telemetry, XpLedger};

const HELP: &str = "\
XenCode REPL commands:
  /help                 this help
  /tools                list tools available to the agent
  /mode <m>             permission mode: read-only | confirm | plan | yolo
  /plan                 toggle plan mode (research + propose, never mutate)
  /image <path...>      stage images for the next message (/image clear)
  /resume [id]          list shared-DB sessions, or resume one by id
  /todo [show|text...]  read or replace the todo list
  /goal [add|list|run]  goal DAG operations
  /dag                  render the DAG
  /xp                   XP + streak summary
  /costs                lifetime token usage from the shared database
  /radar                spec radar / compile streak
  /checkpoint [args]    list | create <label> <summary> | restore <id>
  /session [list|save]  list sessions or save this one
  /compact              compact the conversation history
  /clear                clear conversation history
  /decide               decision cards: list | resolve <id> <n>
  /events [n]           tail the event log
  /run <script.mjs>     run an automation script
  /quit                 exit";

pub async fn run_repl(workspace: &Path, mode: PermissionMode, mut pending_images: Vec<PathBuf>) -> Result<()> {
    println!(
        "XenCode {} — Rust PC line · workspace {}",
        env!("CARGO_PKG_VERSION"),
        workspace.display()
    );
    let cfg = xencode_core::config::load_config()?;
    let mut session = match xencode_core::agent::session_from_config(workspace, &cfg, mode, true) {
        Ok(s) => s,
        Err(e) => {
            println!("! {e}");
            println!("Configure a provider first: xencode config set-provider <name> --base-url ... --api-key ... --model ...");
            return Ok(());
        }
    };
    println!("provider: {} ({}) — type /help for commands", session.provider.name, session.provider.kind.as_str());
    if !pending_images.is_empty() {
        println!("staged images: {} — they ride along with your next message", pending_images.len());
    }

    // Shared-database persistence (aicode_agent_db): every turn lands in the
    // same tables the Android app writes — chat_sessions, agent_messages
    // (with `AgentAttachment` metadata for images), llm_call_records — and XP
    // goes to xp_events via the session's DB sink.
    let persister = ChatPersister::open_default();
    if let Some(p) = &persister {
        let p2 = p.clone();
        session.on_tool_result = Some(Box::new(move |name, args, result| {
            p2.tool_row(name, args, result);
        }));
        p.set_permission_mode(mode);
        println!("shared DB: {} (chat + XP persisted)", xencode_core::store::SharedStore::default_db_path().display());
    }

    // The mode to restore when /plan toggles plan mode back off.
    let mut mode_before_plan: Option<PermissionMode> = None;

    let mut rl = DefaultEditor::new()?;
    loop {
        let plan_hint = if session.permission.mode == PermissionMode::Plan { " (plan)" } else { "" };
        let image_hint = if pending_images.is_empty() { "" } else { " 📎" };
        let readline = rl.readline(&format!("xencode{plan_hint}{image_hint}› "));
        let line = match readline {
            Ok(l) => l,
            Err(rustyline::error::ReadlineError::Interrupted) | Err(rustyline::error::ReadlineError::Eof) => break,
            Err(e) => return Err(e.into()),
        };
        let line = line.trim().to_string();
        if line.is_empty() {
            continue;
        }
        let _ = rl.add_history_entry(&line);

        if let Some(cmd) = line.strip_prefix('/') {
            let mut parts = cmd.splitn(2, ' ');
            let name = parts.next().unwrap_or("");
            let rest = parts.next().unwrap_or("").trim();
            match name {
                "quit" | "q" | "exit" => break,
                "help" => println!("{HELP}"),
                "tools" => {
                    for t in session.tool_specs() {
                        println!("  {:<14} {}", t.name, t.description);
                    }
                }
                "mode" => match xencode_core::permissions::PermissionMode::parse(rest) {
                    Some(m) => {
                        if m == PermissionMode::Plan && session.permission.mode != PermissionMode::Plan {
                            mode_before_plan = Some(session.permission.mode);
                        }
                        session.permission.mode = m;
                        if let Some(p) = &persister {
                            p.set_permission_mode(m);
                        }
                        println!("permission mode: {}", m.as_str());
                        if m == PermissionMode::Plan {
                            println!("plan mode: read/search/web/skills/todo stay on; writes, edits and shell commands are denied until you switch back");
                        }
                    }
                    None => println!("! unknown mode `{rest}` (read-only | confirm | plan | yolo)"),
                },
                "plan" => {
                    if session.permission.mode == PermissionMode::Plan {
                        let back = mode_before_plan.take().unwrap_or(PermissionMode::Confirm);
                        session.permission.mode = back;
                        if let Some(p) = &persister {
                            p.set_permission_mode(back);
                        }
                        println!("plan mode off — back to {} (say “go ahead” to execute the plan)", back.as_str());
                    } else {
                        mode_before_plan = Some(session.permission.mode);
                        session.permission.mode = PermissionMode::Plan;
                        if let Some(p) = &persister {
                            p.set_permission_mode(PermissionMode::Plan);
                        }
                        println!("plan mode on — XenCode will research and propose a plan; writes, edits and shell commands are denied");
                        println!("(toggle again with /plan, or /mode <m> to jump straight to a mode)");
                    }
                }
                "image" => {
                    let rest = rest.trim();
                    if rest.is_empty() {
                        if pending_images.is_empty() {
                            println!("no staged images — /image <path...> to stage, they ride along with your next message");
                        } else {
                            println!("staged images:");
                            for p in &pending_images {
                                println!("  {}", p.display());
                            }
                        }
                    } else if rest == "clear" {
                        pending_images.clear();
                        println!("staged images cleared");
                    } else {
                        for arg in rest.split_whitespace() {
                            let path = PathBuf::from(arg);
                            if path.is_file() {
                                pending_images.push(path);
                            } else {
                                println!("! skipped `{arg}` (not a file)");
                            }
                        }
                        println!("staged images: {} (send a message, or /image clear)", pending_images.len());
                    }
                }
                "resume" => {
                    let id = rest.trim();
                    if id.is_empty() {
                        match &persister {
                            Some(p) => {
                                let sessions = p.recent_sessions(10);
                                if sessions.is_empty() {
                                    println!("no sessions in the shared database yet");
                                } else {
                                    println!("recent shared-DB sessions (newest first) — /resume <id>:");
                                    for s in sessions {
                                        println!("  {:<14} {:<10} {} · {}", s.id, s.mode, clip_title(&s.title, 46), when(s.updated_at));
                                    }
                                }
                            }
                            None => println!("! shared database unavailable"),
                        }
                    } else {
                        match persister.as_ref().and_then(|p| p.session_by_id(id)) {
                            Some(resumed) => {
                                session.messages = ChatPersister::rows_to_history(&resumed.rows);
                                if let Some(p) = &persister {
                                    p.resume(&resumed.session);
                                }
                                println!("resumed `{}` — {} history messages bound to session {}", resumed.session.title, session.messages.len(), resumed.session.id);
                                for m in session.messages.iter().take(6) {
                                    let who = if m.role == xencode_core::chat::Role::User { "you" } else { "xencode" };
                                    let text: String = m.content.clone().unwrap_or_default().chars().take(72).collect();
                                    println!("  [{who}] {text}");
                                }
                                if session.messages.len() > 6 {
                                    println!("  … {} more", session.messages.len() - 6);
                                }
                            }
                            None => println!("! no shared-DB session `{id}`"),
                        }
                    }
                }
                "todo" => {
                    let store = xencode_core::tools::todo::TodoStore::new(workspace);
                    if rest.is_empty() || rest == "show" {
                        println!("{}", store.render());
                    } else {
                        let items = rest
                            .split('|')
                            .map(|s| xencode_core::tools::todo::TodoItem {
                                content: s.trim().to_string(),
                                status: "pending".into(),
                                priority: "medium".into(),
                            })
                            .collect();
                        store.replace(items).ok();
                        println!("{}", store.render());
                    }
                }
                "goal" => {
                    let mut segs = rest.splitn(3, ' ');
                    let sub = segs.next().unwrap_or("list");
                    match sub {
                        "add" => {
                            let title = segs.next().unwrap_or("");
                            let detail = segs.next().unwrap_or("");
                            if title.is_empty() {
                                println!("! usage: /goal add <title> [detail]");
                            } else {
                                let mut dag = xencode_core::goal::file::load(workspace);
                                match dag.add(title, detail, vec![]) {
                                    Ok(n) => {
                                        xencode_core::goal::file::save(workspace, &dag).ok();
                                        println!("added {} — {title}", n.id);
                                    }
                                    Err(e) => println!("! {e}"),
                                }
                            }
                        }
                        "run" => {
                            let summary = xencode_core::goal::runner::run_ready(workspace, 1).await;
                            println!("{summary}");
                        }
                        _ => println!("{}", xencode_core::goal::file::load(workspace).render()),
                    }
                }
                "dag" => println!("{}", xencode_core::goal::file::load(workspace).render()),
                "xp" => match xencode_core::store::xp::DbXpLedger::open_default() {
                    Ok(db) => println!("{}", xencode_core::telemetry::XpSink::summary(&db, xencode_core::now_ms()).render()),
                    Err(_) => println!("{}", XpLedger::default().render_summary(xencode_core::now_ms())),
                },
                "costs" => match &persister {
                    Some(p) => match p.llm_totals() {
                        Some((calls, errors, in_tok, out_tok)) => println!(
                            "Lifetime (shared DB): {calls} llm calls ({errors} errored) · tokens in {in_tok} · out {out_tok}"
                        ),
                        None => println!("! token totals unavailable"),
                    },
                    None => println!("! shared database unavailable"),
                },
                "radar" => println!("{}", Telemetry::load(workspace).render_radar()),
                "checkpoint" => {
                    let store = CheckpointStore::new(workspace);
                    let mut segs = rest.splitn(3, ' ');
                    match segs.next().unwrap_or("list") {
                        "create" => {
                            let (label, summary) = (segs.next().unwrap_or("manual"), segs.next().unwrap_or("checkpoint"));
                            match store.create(label, summary) {
                                Ok(cp) => println!("checkpoint {} @{}", cp.id, cp.commit.as_deref().unwrap_or("-")),
                                Err(e) => println!("! {e}"),
                            }
                        }
                        "restore" => match store.restore(segs.next().unwrap_or("")) {
                            Ok(m) => println!("{m}"),
                            Err(e) => println!("! {e}"),
                        },
                        _ => println!("{}", store.render_list()),
                    }
                }
                "session" => {
                    let store = SessionStore::new(workspace);
                    if rest == "save" {
                        let mut s = Session {
                            meta: SessionMeta {
                                id: xencode_core::short_id(),
                                title: "repl session".into(),
                                created_at: xencode_core::now_ms(),
                                updated_at: xencode_core::now_ms(),
                                turns: session.messages.len() as u32 / 2,
                            },
                            messages: session.history_snapshot(),
                            tokens_in: session.tokens_in,
                            tokens_out: session.tokens_out,
                        };
                        store.save(&mut s).ok();
                        println!("saved session {}", s.meta.id);
                    } else {
                        println!("{}", store.render_list());
                    }
                }
                "compact" => {
                    let did = session.maybe_compact();
                    println!("{}", if did { "history compacted" } else { "history below threshold — nothing to do" });
                }
                "clear" => {
                    session.clear();
                    println!("history cleared");
                }
                "decide" => {
                    let store = DecisionStore::new(workspace);
                    let mut segs = rest.splitn(3, ' ');
                    match segs.next().unwrap_or("list") {
                        "resolve" => match (segs.next(), segs.next()) {
                            (Some(id), Some(n)) => match store.resolve(id, n.parse().unwrap_or(usize::MAX)) {
                                Ok(card) => println!("resolved {} → {:?}", card.id, card.chosen_index.map(|i| card.options[i].clone())),
                                Err(e) => println!("! {e}"),
                            },
                            _ => println!("! usage: /decide resolve <id> <choice#>"),
                        },
                        _ => println!("{}", store.render_list()),
                    }
                }
                "events" => {
                    let n: usize = rest.parse().unwrap_or(20);
                    println!("{}", EventLog::new(workspace).render_tail(n));
                }
                "run" => {
                    if rest.is_empty() {
                        println!("! usage: /run <script.mjs>");
                    } else {
                        let engine = xencode_automation::MjsEngine::new(workspace);
                        match engine.run_file(&workspace.join(rest), None) {
                            Ok(res) => print!("{}", xencode_automation::render_result(&res)),
                            Err(e) => println!("! {e}"),
                        }
                    }
                }
                other => println!("! unknown command /{other} — /help"),
            }
            continue;
        }

        // Regular turn → the agent (with any staged images).
        print!("… ");
        use std::io::Write;
        let _ = std::io::stdout().flush();
        let parts: Vec<ImagePart> = pending_images
            .iter()
            .map(|p| ImagePart::from_path(p.display().to_string()))
            .collect();
        if let Some(p) = &persister {
            p.user_turn_with_attachments(
                workspace.display().to_string().as_str(),
                &session.provider.name,
                session.provider.model.as_str(),
                &line,
                &parts,
            );
        }
        let started = std::time::Instant::now();
        let out = session
            .send_with_images(&line, &parts, &mut |d| {
                print!("\r\x1b[K{d}");
                let _ = std::io::stdout().flush();
            }, &mut |tc| {
                eprintln!("\n[tool] {} {}", tc.name, tc.args_json.chars().take(100).collect::<String>());
            })
            .await;
        println!();
        if let Some(p) = &persister {
            let duration = started.elapsed().as_millis() as i64;
            match &out {
                Some(reply) => p.assistant_turn(
                    workspace.display().to_string().as_str(),
                    &session.provider.name,
                    session.provider.model.as_str(),
                    reply,
                    session.last_turn_usage,
                    duration,
                ),
                None => {
                    let failure = session.last_failure.clone().unwrap_or_else(|| "unknown".into());
                    p.failed_turn(&session.provider.name, session.provider.model.as_str(), &failure, session.last_turn_usage, duration);
                }
            }
        }
        match out {
            Some(_) => {
                pending_images.clear();
            }
            None => println!("! failed: {}", session.last_failure.clone().unwrap_or_else(|| "unknown".into())),
        }
    }
    Ok(())
}

/// Clips a session title for the /resume listing.
fn clip_title(s: &str, max: usize) -> String {
    if s.chars().count() <= max {
        s.to_string()
    } else {
        format!("{}…", s.chars().take(max - 1).collect::<String>())
    }
}

/// Renders “3d ago”-style relative times for the /resume listing.
fn when(ts_millis: i64) -> String {
    let delta = (xencode_core::now_ms() - ts_millis).max(0) / 1000;
    match delta {
        0..=59 => format!("{delta}s ago"),
        60..=3599 => format!("{}m ago", delta / 60),
        3600..=86399 => format!("{}h ago", delta / 3600),
        _ => format!("{}d ago", delta / 86_400),
    }
}
