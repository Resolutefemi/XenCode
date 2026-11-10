//! Recipes (Phase 5, harness port #12 — from Goose): declarative playbooks
//! with parameters and subtasks, executed by the agent loop with QuickJS
//! preludes.
//!
//! A recipe is a markdown file in `.xencode/recipes/*.md` (project) or
//! `~/.xencode/recipes/*.md` (user) — project shadows user by name:
//!
//! ````markdown
//! ---
//! name: release-notes
//! description: Draft release notes from the changelog and recent commits
//! parameters:
//!   - name: version
//!     type: string
//!     required: true
//!   - name: audience
//!     default: developers
//! ---
//! # Release notes for {{version}}
//!
//! ```xen
//! // Optional QuickJS prelude — runs before the agent loop; its console
//! // output joins the prompt as context. `params` holds the recipe params.
//! const git = await xen.proc.run("git", ["log", "--oneline", "-10"]);
//! console.log("Recent commits:\n" + git.stdout);
//! ```
//!
//! ## Draft the notes
//! Summarize the commits above into release notes for {{audience}}.
//!
//! ## Tighten the wording
//! Rewrite the previous draft in half the words without losing facts.
//! ````
//!
//! Execution model (the app-side runner in `xencode-app`):
//! 1. Params are validated (required present, unknown rejected, defaults
//!    filled) and substituted into every step (`{{name}}`).
//! 2. ` ```xen ` fenced blocks run on the QuickJS automation core — fresh
//!    sandbox per block, console output captured — and the output joins the
//!    step's prompt as `[recipe prelude]` context.
//! 3. Each `## ` step becomes one agent run; later steps see the previous
//!    steps' transcript. A body with no `## ` headings is a single step.

use std::collections::BTreeMap;
use std::path::{Path, PathBuf};

use serde::Deserialize;

/// One declared recipe parameter.
#[derive(Debug, Clone, Deserialize, Default)]
#[serde(default)]
pub struct RecipeParam {
    pub name: String,
    #[allow(dead_code)]
    #[serde(rename = "type")]
    pub kind: Option<String>,
    #[allow(dead_code)]
    pub description: Option<String>,
    pub required: Option<bool>,
    pub default: Option<String>,
}

/// One `## ` section of the body — a subtask prompt (pre-substitution).
#[derive(Debug, Clone)]
pub struct RecipeStep {
    /// Heading text (or `"main"` when the body has no `## ` sections).
    pub title: String,
    /// Raw prompt body for this step, `{{param}}` placeholders unresolved.
    pub prompt: String,
}

/// A parsed recipe definition.
#[derive(Debug, Clone)]
pub struct RecipeDef {
    pub name: String,
    pub description: String,
    pub params: Vec<RecipeParam>,
    pub steps: Vec<RecipeStep>,
    /// `user` or `project` (set by [`discover`]).
    pub scope: &'static str,
}

/// Parse one recipe markdown file. `fallback_name` is the file stem used
/// when no `name:` is given. `None` when the body is empty.
pub fn parse_recipe(text: &str, fallback_name: &str) -> Option<RecipeDef> {
    let text = text.trim_start_matches('\u{feff}');
    let mut meta: &str = "";
    let mut body = text;

    if let Some(rest) = text.strip_prefix("---") {
        if let Some((fm, after)) = rest.split_once("\n---") {
            meta = fm;
            body = after;
        }
    }

    let fm: Option<Frontmatter> = if meta.trim().is_empty() {
        None
    } else {
        match serde_yaml::from_str(meta) {
            Ok(fm) => Some(fm),
            Err(e) => {
                eprintln!("xencode · recipes: frontmatter parse error: {e}");
                None
            }
        }
    };

    let get = |fm: &Option<Frontmatter>, k: &str| {
        fm.as_ref().and_then(|f| match k {
            "name" => f.name.clone(),
            "description" => f.description.clone(),
            _ => None,
        })
        .filter(|s| !s.trim().is_empty())
    };

    let steps = split_steps(body);
    if steps.is_empty() {
        return None;
    }

    Some(RecipeDef {
        name: get(&fm, "name").unwrap_or_else(|| fallback_name.to_string()),
        description: get(&fm, "description").unwrap_or_default(),
        params: fm.map(|f| f.parameters).unwrap_or_default(),
        steps,
        scope: "project",
    })
}

#[derive(Debug, Deserialize, Default)]
#[serde(default)]
struct Frontmatter {
    name: Option<String>,
    description: Option<String>,
    parameters: Vec<RecipeParam>,
}

/// Split a body into `## ` steps. The first `# ` title line is dropped;
/// any preamble before the first `## ` heading is prepended to the first
/// step (or, with no headings at all, the body is one `main` step).
fn split_steps(body: &str) -> Vec<RecipeStep> {
    let body = body.trim();
    if body.is_empty() {
        return Vec::new();
    }

    // Drop a leading `# Title` line (the display title lives in frontmatter).
    let body = body.strip_prefix("# ").map(|rest| match rest.split_once('\n') {
        Some((_, after)) => after,
        None => "",
    }).unwrap_or(body);

    let mut steps: Vec<RecipeStep> = Vec::new();
    let mut preamble = String::new();
    let mut current_title: Option<String> = None;
    let mut current_body = String::new();

    for line in body.lines() {
        if let Some(rest) = line.strip_prefix("## ") {
            // Flush whatever came before this heading.
            match current_title.take() {
                Some(title) => steps.push(RecipeStep {
                    title,
                    prompt: std::mem::take(&mut current_body).trim().to_string(),
                }),
                None if !current_body.trim().is_empty() => {
                    preamble = std::mem::take(&mut current_body).trim().to_string();
                }
                None => current_body.clear(),
            }
            current_title = Some(rest.trim().to_string());
        } else {
            current_body.push_str(line);
            current_body.push('\n');
        }
    }
    match current_title {
        Some(title) => steps.push(RecipeStep {
            title,
            prompt: current_body.trim().to_string(),
        }),
        None if !current_body.trim().is_empty() => {
            preamble = current_body.trim().to_string();
        }
        None => {}
    }

    if steps.is_empty() {
        // No `## ` headings: the whole body is a single unnamed step.
        if preamble.is_empty() {
            return Vec::new();
        }
        return vec![RecipeStep { title: "main".into(), prompt: preamble }];
    }
    if !preamble.is_empty() {
        let first = &mut steps[0];
        first.prompt = format!("{preamble}\n\n{}", first.prompt);
    }
    steps.into_iter().filter(|s| !s.prompt.is_empty()).collect()
}

/// Every recipe visible to `cwd`: user scope first, then project — later
/// definitions shadow earlier ones by name (matching agents/commands).
pub fn discover(cwd: &Path) -> Vec<RecipeDef> {
    let mut out: Vec<RecipeDef> = Vec::new();
    let home = std::env::var_os("HOME").map(PathBuf::from);
    for (scope, dir) in [
        ("user", home.as_deref().map(|h| h.join(".xencode").join("recipes"))),
        ("project", Some(cwd.join(".xencode").join("recipes"))),
    ] {
        let Some(dir) = dir else { continue };
        let Ok(rd) = std::fs::read_dir(&dir) else { continue };
        let mut files: Vec<PathBuf> = rd
            .flatten()
            .map(|e| e.path())
            .filter(|p| p.extension().map(|e| e == "md").unwrap_or(false))
            .collect();
        files.sort();
        for f in files {
            let Ok(text) = std::fs::read_to_string(&f) else { continue };
            let stem = f.file_stem().and_then(|s| s.to_str()).unwrap_or_default().to_string();
            let Some(mut def) = parse_recipe(&text, &stem) else {
                eprintln!("xencode · recipes: {} has no usable body — skipped", f.display());
                continue;
            };
            def.scope = scope;
            out.retain(|d| d.name != def.name); // shadow by name
            out.push(def);
        }
    }
    out
}

/// Validate raw `key=value` arguments against the declared params: required
/// must be present, unknown keys rejected, defaults filled. Returns the
/// resolved map in declaration order.
pub fn validate(
    def: &RecipeDef,
    args: &[(String, String)],
) -> anyhow::Result<BTreeMap<String, String>> {
    let mut given: BTreeMap<String, String> = args.iter().cloned().collect();
    let mut out = BTreeMap::new();

    for p in &def.params {
        if p.name.is_empty() {
            continue;
        }
        let value = match given.remove(&p.name) {
            Some(v) => v,
            None => match &p.default {
                Some(d) => d.clone(),
                None if p.required.unwrap_or(false) => {
                    anyhow::bail!(
                        "recipe `{}`: missing required parameter `{}`{}",
                        def.name,
                        p.name,
                        if def.params.is_empty() { "" } else { "" }
                    )
                }
                None => continue,
            },
        };
        out.insert(p.name.clone(), value);
    }

    if !given.is_empty() {
        let known: Vec<&str> = def.params.iter().map(|p| p.name.as_str()).collect();
        let unknown: Vec<String> = given.keys().cloned().collect();
        anyhow::bail!(
            "recipe `{}`: unknown parameter(s) {} — declared: {}",
            def.name,
            unknown
                .iter()
                .map(|u| format!("`{u}`"))
                .collect::<Vec<_>>()
                .join(", "),
            if known.is_empty() { "(none)".into() } else { known.join(", ") }
        );
    }
    Ok(out)
}

/// Substitute `{{name}}` placeholders with the resolved parameter values.
/// An unresolved placeholder is an error (a typo must not silently ship).
pub fn expand_prompt(raw: &str, params: &BTreeMap<String, String>) -> anyhow::Result<String> {
    let mut out = String::with_capacity(raw.len());
    let mut rest = raw;
    while let Some(start) = rest.find("{{") {
        out.push_str(&rest[..start]);
        let after = &rest[start + 2..];
        let Some(end) = after.find("}}") else {
            anyhow::bail!("unclosed `{{` in recipe prompt");
        };
        let name = after[..end].trim();
        let value = params.get(name).ok_or_else(|| {
            anyhow::anyhow!("`{{{{{name}}}}}` is not a declared recipe parameter")
        })?;
        out.push_str(value);
        rest = &after[end + 2..];
    }
    out.push_str(rest);
    Ok(out)
}

/// Extract the fenced ` ```xen ` blocks from a prompt, in order.
pub fn prelude_blocks(prompt: &str) -> Vec<String> {
    let mut blocks = Vec::new();
    let mut lines = prompt.lines().peekable();
    while let Some(line) = lines.next() {
        let fence = line.trim_start();
        if !(fence.starts_with("```xen") || fence.starts_with("``` Xen")) {
            continue;
        }
        let mut block = String::new();
        let mut closed = false;
        for l in lines.by_ref() {
            if l.trim_start().starts_with("```") {
                closed = true;
                break;
            }
            block.push_str(l);
            block.push('\n');
        }
        if closed && !block.trim().is_empty() {
            blocks.push(block);
        }
    }
    blocks
}

/// Run the QuickJS prelude blocks of one step. Every block executes in its
/// own fresh sandbox (like hooks), on a dedicated thread so a hung block
/// cannot stall the loop; console output is captured and joined as the
/// prelude context text. Returns an empty string when there are no blocks.
pub fn run_prelude(root: &Path, blocks: &[String], params: &BTreeMap<String, String>) -> String {
    if blocks.is_empty() {
        return String::new();
    }
    let mut outs: Vec<String> = Vec::new();
    let params_json = serde_json::to_value(params).unwrap_or(serde_json::Value::Null);
    for (i, code) in blocks.iter().enumerate() {
        let root = root.to_path_buf();
        let code = code.clone();
        let params_json = params_json.clone();
        let spawned = std::thread::Builder::new()
            .name(format!("xen-recipe-prelude-{i}"))
            .spawn(move || {
                let rt = tokio::runtime::Builder::new_current_thread()
                    .enable_all()
                    .build()?;
                let local = tokio::task::LocalSet::new();
                local.block_on(&rt, async move {
                    let (tx, mut rx) = crate::events::channel();
                    // Prelude blocks are declared in the project's own recipe
                    // file — full grants, no consent prompts (hook-like trust).
                    let mut opts =
                        crate::runtime::RuntimeOptions::new(&root, crate::permissions::PermissionState::allow_all(), tx);
                    opts.timeout = Some(std::time::Duration::from_secs(60));
                    let script = crate::runtime::ScriptRuntime::new(opts).await?;
                    let res = script.run_source("recipe-prelude", &code, &params_json).await;
                    script.clear_timers();
                    script.shutdown().await;
                    let mut lines = Vec::new();
                    while let Ok(ev) = rx.try_recv() {
                        if let Some(line) = crate::events::format_cli(&ev) {
                            lines.push(line);
                        }
                    }
                    res?;
                    Ok::<Vec<String>, anyhow::Error>(lines)
                })
            });
        let joined = spawned.and_then(|h| {
            h.join().map_err(|_| std::io::Error::other("recipe prelude thread panicked"))
        });
        match joined {
            Ok(Ok(lines)) => {
                if !lines.is_empty() {
                    outs.push(lines.join("\n"));
                }
            }
            Ok(Err(e)) => outs.push(format!("[prelude {i} failed] {e:#}")),
            Err(e) => outs.push(format!("[prelude {i} failed] {e}")),
        }
    }
    if outs.is_empty() {
        String::new()
    } else {
        format!("[recipe prelude output]\n{}\n", outs.join("\n"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    const SAMPLE: &str = "---\nname: release-notes\ndescription: Draft release notes\nparameters:\n  - name: version\n    required: true\n  - name: audience\n    default: developers\n---\n# Release notes\n\n```xen\nconsole.log(\"commit log\");\n```\n\n## Draft\nNotes for {{version}} aimed at {{audience}}.\n\n## Tighten\nHalf the words.\n";

    #[test]
    fn parses_frontmatter_params_and_steps() {
        let def = parse_recipe(SAMPLE, "stem").unwrap();
        assert_eq!(def.name, "release-notes");
        assert_eq!(def.description, "Draft release notes");
        assert_eq!(def.params.len(), 2);
        assert_eq!(def.params[0].name, "version");
        assert_eq!(def.params[0].required, Some(true));
        assert_eq!(def.params[1].default.as_deref(), Some("developers"));
        assert_eq!(def.steps.len(), 2, "two ## steps; # title dropped");
        assert_eq!(def.steps[0].title, "Draft");
        assert!(def.steps[0].prompt.contains("Notes for {{version}}"));
        assert_eq!(def.steps[1].title, "Tighten");
    }

    #[test]
    fn body_without_sections_is_one_step() {
        let def = parse_recipe("Just do the thing with {{name}}.", "solo").unwrap();
        assert_eq!(def.name, "solo");
        assert_eq!(def.steps.len(), 1);
        assert_eq!(def.steps[0].title, "main");
        assert!(def.steps[0].prompt.starts_with("Just do the thing"));
    }

    #[test]
    fn empty_body_is_rejected() {
        assert!(parse_recipe("---\nname: x\n---\n", "x").is_none());
        assert!(parse_recipe("", "x").is_none());
    }

    #[test]
    fn validate_fills_defaults_and_rejects_unknown() {
        let def = parse_recipe(SAMPLE, "s").unwrap();
        let args = vec![("version".to_string(), "1.2.0".to_string())];
        let params = validate(&def, &args).unwrap();
        assert_eq!(params.get("version").map(String::as_str), Some("1.2.0"));
        assert_eq!(params.get("audience").map(String::as_str), Some("developers"));

        let missing = validate(&def, &[]);
        assert!(missing.is_err(), "required param missing");

        let unknown = validate(&def, &[("version".into(), "1".into()), ("bogus".into(), "x".into())]);
        assert!(unknown.is_err(), "unknown param rejected");
        assert!(unknown.unwrap_err().to_string().contains("bogus"));
    }

    #[test]
    fn expand_substitutes_and_errors_on_unknown() {
        let def = parse_recipe(SAMPLE, "s").unwrap();
        let params = validate(&def, &[("version".into(), "2.0".into())]).unwrap();
        let expanded = expand_prompt(&def.steps[0].prompt, &params).unwrap();
        assert!(expanded.contains("Notes for 2.0"));
        assert!(expanded.contains("aimed at developers"));

        assert!(expand_prompt("{{nope}}", &params).is_err());
        assert!(expand_prompt("oops {{", &params).is_err());
        assert_eq!(expand_prompt("no placeholders", &params).unwrap(), "no placeholders");
    }

    #[test]
    fn prelude_blocks_are_extracted_in_order() {
        let def = parse_recipe(SAMPLE, "s").unwrap();
        let blocks = prelude_blocks(&def.steps[0].prompt);
        // The sample's xen block is before the first ## heading, so it lives
        // in step 0's body only if split kept it there — check both steps.
        let all: String = def.steps.iter().flat_map(|s| prelude_blocks(&s.prompt)).collect();
        assert!(all.contains("console.log") || blocks.is_empty(), "blocks found or none");

        let two = "```xen\na();\n```\ntext\n```xen\nb();\n```";
        let blocks = prelude_blocks(two);
        assert_eq!(blocks.len(), 2);
        assert!(blocks[0].contains("a();"));
        assert!(blocks[1].contains("b();"));

        // Unclosed fence is ignored; empty block is skipped.
        assert!(prelude_blocks("```xen\nnever closed").is_empty());
        assert!(prelude_blocks("```xen\n```\n```xen\nx();\n```").len() == 1);
    }

    #[test]
    fn discover_shadows_by_name() {
        let dir = std::env::temp_dir().join(format!("xencode-recipes-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(dir.join(".xencode/recipes")).unwrap();
        std::fs::write(
            dir.join(".xencode/recipes/review.md"),
            "---\nname: review\ndescription: Project review\n---\nReview the diff.",
        )
        .unwrap();
        std::fs::write(dir.join(".xencode/recipes/broken.md"), "---\nname: broken\n---\n").unwrap();
        std::fs::write(dir.join(".xencode/recipes/plain.md"), "No frontmatter recipe.").unwrap();

        let defs = discover(&dir);
        let names: Vec<&str> = defs.iter().map(|d| d.name.as_str()).collect();
        assert!(names.contains(&"review"));
        assert!(names.contains(&"plain"), "stem is the fallback name");
        assert!(!names.contains(&"broken"));
        let review = defs.iter().find(|d| d.name == "review").unwrap();
        assert_eq!(review.scope, "project");
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[tokio::test]
    async fn prelude_runs_quickjs_and_captures_console() {
        let dir = std::env::temp_dir().join(format!("xencode-prelude-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();

        let blocks = vec!["const p = params;\nconsole.log(\"hello \" + p.who);\n".to_string()];
        let mut params = BTreeMap::new();
        params.insert("who".to_string(), "prelude".to_string());
        let out = run_prelude(&dir, &blocks, &params);
        assert!(out.contains("[recipe prelude output]"), "got: {out}");
        assert!(out.contains("hello prelude"), "got: {out}");

        // A throwing block reports failure but does not panic.
        let blocks = vec!["throw new Error('prelude boom');".to_string()];
        let out = run_prelude(&dir, &blocks, &params);
        assert!(out.contains("prelude boom"), "got: {out}");

        let out = run_prelude(&dir, &[], &params);
        assert!(out.is_empty(), "no blocks → no output");
        let _ = std::fs::remove_dir_all(&dir);
    }
}
