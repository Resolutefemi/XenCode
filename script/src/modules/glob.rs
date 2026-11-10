//! `xen/glob` — file matching (node-glob flavoured `sync`).

use std::path::PathBuf;

use rquickjs::function::Opt;
use rquickjs::module::{Declarations, Exports, ModuleDef};
use rquickjs::{Ctx, Function, Object, Result as JsResult};

use crate::host::fs::{literal_root, walk};

pub struct XenGlob;

impl ModuleDef for XenGlob {
    fn declare(decl: &Declarations) -> JsResult<()> {
        decl.declare("sync")?;
        decl.declare("globSync")?;
        decl.declare("hasMagic")?;
        Ok(())
    }

    fn evaluate<'js>(ctx: &Ctx<'js>, exports: &Exports<'js>) -> JsResult<()> {
        let sync = Function::new(ctx.clone(), move |pattern: String, opts: Opt<Object>| {
            let cwd: PathBuf = opts
                .0
                .as_ref()
                .and_then(|o| o.get::<_, Option<String>>("cwd").ok().flatten())
                .map(PathBuf::from)
                .unwrap_or_else(|| std::env::current_dir().unwrap_or_default());
            let dot = opts
                .0
                .as_ref()
                .and_then(|o| o.get::<_, Option<bool>>("dot").ok().flatten())
                .unwrap_or(false);
            Ok::<_, rquickjs::Error>(glob_match(&pattern, &cwd, dot))
        })?;
        exports.export("sync", sync.clone())?;
        exports.export("globSync", sync)?;

        let has_magic = Function::new(ctx.clone(), |pattern: String| {
            pattern.contains('*') || pattern.contains('?') || pattern.contains('[') || pattern.contains('{')
        })?;
        exports.export("hasMagic", has_magic)?;
        Ok(())
    }
}

/// Expand `pattern` under `cwd` into sorted absolute path strings.
pub fn glob_match(pattern: &str, cwd: &PathBuf, dot: bool) -> Vec<String> {
    let pattern_abs = if PathBuf::from(pattern).is_absolute() {
        pattern.to_string()
    } else {
        cwd.join(pattern).to_string_lossy().to_string()
    };
    let Ok(pat) = glob::Pattern::new(&pattern_abs) else {
        return vec![];
    };
    let root = literal_root(&pattern_abs);
    let mut out = Vec::new();
    if let Ok(entries) = walk(&root, 500_000) {
        let base = glob::MatchOptions {
            case_sensitive: true,
            require_literal_separator: true,
            require_literal_leading_dot: !dot,
        };
        for entry in entries {
            if pat.matches_with(&entry, base) {
                out.push(entry);
            }
        }
        out.sort();
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test(flavor = "multi_thread")]
    async fn expands_files() {
        let dir = tempfile::tempdir().unwrap();
        let src = dir.path().join("src");
        std::fs::create_dir_all(&src).unwrap();
        std::fs::write(src.join("a.rs"), "a").unwrap();
        std::fs::write(src.join("b.rs"), "b").unwrap();
        std::fs::write(src.join("c.txt"), "c").unwrap();

        let pat = format!("{}/**/*.rs", dir.path().display());
        let out = glob_match(&pat, &std::env::current_dir().unwrap(), false);
        assert_eq!(out.len(), 2);
        assert!(out[0].ends_with("a.rs"));
    }
}
