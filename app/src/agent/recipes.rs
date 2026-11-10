//! Recipes runner (Phase 5, harness port #12 — from Goose). The parsing and
//! QuickJS prelude execution live in the automation core
//! (`xencode_script::recipes`); this module glues a recipe's steps to agent
//! loops: params validated → per step, `{{param}}` substitution + prelude
//! output → one agent run whose transcript carries into the next step.

use std::path::Path;
use std::sync::Arc;

use anyhow::{bail, Context, Result};
use tokio::sync::mpsc;

use super::run::{run_agent, AgentOptions};
use super::{oracle, permissions::PermissionMode, provider::GlmProvider, provider::Provider, telemetry, CliOptions};

/// Every recipe visible to `cwd` (user + project scope).
pub fn list(cwd: &Path) -> Vec<xencode_script::RecipeDef> {
    xencode_script::recipes::discover(cwd)
}

/// Run one recipe end to end: validate params, then execute each step as an
/// agent run with the previous steps' transcript as history. Progress goes
/// to stderr; the final step's answer is the return value.
pub fn run_recipe(name: &str, params: &[(String, String)], cli: &CliOptions) -> Result<String> {
    let cwd = std::env::current_dir().context("resolve working directory")?;
    let session_dir = cwd.join(".xencode").join("sessions");

    let defs = list(&cwd);
    let Some(def) = defs.iter().find(|d| d.name == name) else {
        let names: Vec<&str> = defs.iter().map(|d| d.name.as_str()).collect();
        bail!(
            "unknown recipe `{name}` — available: {}{}",
            if names.is_empty() { "(none)".to_string() } else { names.join(", ") },
            "\nadd markdown files to .xencode/recipes/ (project) or ~/.xencode/recipes/ (user)"
        );
    };
    let def = def.clone();
    let resolved = xencode_script::recipes::validate(&def, params)
        .with_context(|| format!("recipe `{}` parameter validation", def.name))?;

    let provider: Arc<dyn Provider> = Arc::new(GlmProvider::from_env()?);
    let harness = super::harness_for_project(&cwd);
    let telemetry = telemetry::Telemetry::for_run(&cwd, &harness.telemetry_cfg);

    // Permission mode: explicit flag > --read-only > interactive default
    // (same resolution as `--agent` runs).
    let interactive = std::io::IsTerminal::is_terminal(&std::io::stdin());
    let mode = if cli.read_only {
        PermissionMode::ReadOnly
    } else {
        cli.permission_mode.unwrap_or_default()
    };
    if mode == PermissionMode::ReadOnly {
        eprintln!("xencode · recipe runs read-only: every step's mutations are blocked");
    }

    eprintln!(
        "xencode · recipe `{}` — {} step(s), params: {}",
        def.name,
        def.steps.len(),
        if resolved.is_empty() {
            "(none)".to_string()
        } else {
            resolved
                .iter()
                .map(|(k, v)| format!("{k}={v}"))
                .collect::<Vec<_>>()
                .join(" ")
        }
    );

    let rt = tokio::runtime::Runtime::new().context("start tokio runtime")?;
    rt.block_on(async move {
        let mcp = super::connect_mcp(&cwd).await;
        let checkpoints = if cli.no_checkpoints {
            None
        } else {
            Some(super::checkpoint::CheckpointStore::open(&cwd))
        };
        let oracle_setup = build_oracle(&harness, &provider)?;
        let repo_map = if cli.no_repo_map { None } else { Some(super::DEFAULT_REPO_MAP_BUDGET) };

        let mut history: Vec<super::provider::Message> = Vec::new();
        let mut last_answer = String::new();

        for (i, step) in def.steps.iter().enumerate() {
            let expanded = xencode_script::recipes::expand_prompt(&step.prompt, &resolved)?;
            // ```xen preludes run before the loop; their console output joins
            // the prompt as context (fresh sandbox per block, 60s deadline).
            let blocks = xencode_script::recipes::prelude_blocks(&expanded);
            let prelude = xencode_script::recipes::run_prelude(&cwd, &blocks, &resolved);
            let prompt = if prelude.is_empty() {
                expanded
            } else {
                format!("{expanded}\n\n{prelude}")
            };

            eprintln!(
                "xencode · step {}/{}: {}{}",
                i + 1,
                def.steps.len(),
                step.title,
                if blocks.is_empty() { String::new() } else { format!(" ({} prelude block(s))", blocks.len()) }
            );

            let (tx, rx) = mpsc::unbounded_channel::<super::run::AgentEvent>();
            let printer = super::spawn_cli_printer(rx);
            let opts = AgentOptions {
                cwd: cwd.clone(),
                session_dir: Some(session_dir.clone()),
                history: history.clone(),
                gate: mode.gate(),
                asker: super::interactive_asker(cli.auto_yes, interactive),
                hooks: harness.hooks.clone(),
                plan_mode: false,
                mcp: mcp.clone(),
                checkpoints: checkpoints.clone(),
                system_extra: None,
                allow_subagents: true,
                repo_map,
                diagnostics: harness.diagnostics.clone(),
                telemetry: telemetry.clone(),
                oracle: oracle_setup.clone(),
                ..Default::default()
            };
            let answer = run_agent(provider.clone(), &prompt, opts, tx).await?;
            last_answer = answer;

            // Carry the whole transcript (system prompt included) into the
            // next step so the repo map, memory and prior work persist.
            history = printer
                .await
                .unwrap_or_default()
                .unwrap_or_else(|| Arc::new(Vec::new()))
                .as_ref()
                .clone();
            eprintln!();
        }

        if let Some(t) = &telemetry {
            let summary = t.finish(last_answer.chars().count()).await;
            eprintln!("{summary}");
        }
        Ok(last_answer)
    })
}

/// Build the oracle setup from the project harness (None = no oracle).
pub(crate) fn build_oracle(
    harness: &super::Harness,
    main_provider: &Arc<dyn Provider>,
) -> Result<Option<Arc<oracle::OracleSetup>>> {
    let model = oracle::model_name(harness.oracle_model.as_deref(), main_provider.model());
    if model == main_provider.model() {
        // Same model — consult on the shared provider (no second client).
        Ok(Some(Arc::new(oracle::OracleSetup { provider: main_provider.clone(), model })))
    } else {
        Ok(Some(Arc::new(oracle::OracleSetup {
            provider: Arc::new(GlmProvider::from_env_with_model(&model)?),
            model,
        })))
    }
}
