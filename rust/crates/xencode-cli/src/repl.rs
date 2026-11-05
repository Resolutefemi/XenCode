//! Interactive chat REPL with slash commands.

use anyhow::Result;
use rustyline::DefaultEditor;
use std::path::Path;
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
  /mode <m>             permission mode: read-only | confirm | yolo
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

pub async fn run_repl(workspace: &Path, mode: PermissionMode) -> Result<()> {
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

    // Shared-database persistence (aicode_agent_db): every turn lands in the
    // same tables the Android app writes — chat_sessions, agent_messages,
    // llm_call_records — and XP goes to xp_events via the session's DB sink.
    let persister = ChatPersister::open_default();
    if let Some(p) = &persister {
        let p2 = p.clone();
        session.on_tool_result = Some(Box::new(move |name, args, result| {
            p2.tool_row(name, args, result);
        }));
        println!("shared DB: {} (chat + XP persisted)", xencode_core::store::SharedStore::default_db_path().display());
    }

    let mut rl = DefaultEditor::new()?;
    loop {
        let readline = rl.readline("xencode› ");
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
                        session.permission.mode = m;
                        println!("permission mode: {}", m.as_str());
                    }
                    None => println!("! unknown mode `{rest}` (read-only | confirm | yolo)"),
                },
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

        // Regular turn → the agent.
        print!("… ");
        use std::io::Write;
        let _ = std::io::stdout().flush();
        if let Some(p) = &persister {
            p.user_turn(
                workspace.display().to_string().as_str(),
                &session.provider.name,
                session.provider.model.as_str(),
                &line,
            );
        }
        let started = std::time::Instant::now();
        let out = session
            .send(&line, &mut |d| {
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
            Some(_) => {}
            None => println!("! failed: {}", session.last_failure.clone().unwrap_or_else(|| "unknown".into())),
        }
    }
    Ok(())
}
