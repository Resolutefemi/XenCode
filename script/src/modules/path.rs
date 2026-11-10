//! `xen/path` — node:path-compatible path helpers (POSIX semantics).

use rquickjs::function::{Opt, Rest};
use rquickjs::module::{Declarations, Exports, ModuleDef};
use rquickjs::{Ctx, Function, Object, Result as JsResult};
use std::path::{Component, Path, PathBuf};

pub struct XenPath;

impl ModuleDef for XenPath {
    fn declare(decl: &Declarations) -> JsResult<()> {
        for name in [
            "join", "resolve", "normalize", "basename", "dirname", "extname",
            "isAbsolute", "relative", "parse", "format", "sep", "delimiter",
        ] {
            decl.declare(name)?;
        }
        Ok(())
    }

    fn evaluate<'js>(ctx: &Ctx<'js>, exports: &Exports<'js>) -> JsResult<()> {
        let join = Function::new(ctx.clone(), |parts: Rest<String>| {
            let mut buf = PathBuf::new();
            for part in parts.iter() {
                let part = part.as_str();
                if part.starts_with('/') {
                    buf = PathBuf::from(part);
                } else {
                    buf.push(part);
                }
            }
            clean(&buf)
        })?;
        exports.export("join", join)?;

        let resolve = Function::new(ctx.clone(), |parts: Rest<String>| {
            let mut buf = std::env::current_dir().unwrap_or_else(|_| PathBuf::from("/"));
            for part in parts.iter() {
                let part = part.as_str();
                if part.starts_with('/') {
                    buf = PathBuf::from(part);
                } else {
                    buf.push(part);
                }
            }
            clean(&buf)
        })?;
        exports.export("resolve", resolve)?;

        let normalize = Function::new(ctx.clone(), |p: String| clean(&PathBuf::from(p)))?;
        exports.export("normalize", normalize)?;

        let basename = Function::new(ctx.clone(), |p: String, ext: Opt<String>| {
            let mut base = Path::new(&p)
                .file_name()
                .map(|f| f.to_string_lossy().to_string())
                .unwrap_or_default();
            if let Some(ext) = ext.0 {
                if base.ends_with(&ext) {
                    base.truncate(base.len() - ext.len());
                }
            }
            base
        })?;
        exports.export("basename", basename)?;

        let dirname = Function::new(ctx.clone(), |p: String| {
            Path::new(&p)
                .parent()
                .map(|d| d.to_string_lossy().to_string())
                .filter(|s| !s.is_empty())
                .unwrap_or_else(|| ".".to_string())
        })?;
        exports.export("dirname", dirname)?;

        let extname = Function::new(ctx.clone(), |p: String| {
            Path::new(&p)
                .extension()
                .map(|e| format!(".{}", e.to_string_lossy()))
                .unwrap_or_default()
        })?;
        exports.export("extname", extname)?;

        let is_absolute = Function::new(ctx.clone(), |p: String| p.starts_with('/'))?;
        exports.export("isAbsolute", is_absolute)?;

        let relative = Function::new(ctx.clone(), |from: String, to: String| {
            path_relative(&from, &to)
        })?;
        exports.export("relative", relative)?;

        let parse = Function::new(
            ctx.clone(),
            move |cx: Ctx<'js>, p: String| -> JsResult<Object<'js>> {
            let path = Path::new(&p);
            let obj = Object::new(cx.clone())?;
            let root = if p.starts_with('/') { "/".to_string() } else { String::new() };
            let base = path
                .file_name()
                .map(|f| f.to_string_lossy().to_string())
                .unwrap_or_default();
            let ext = path
                .extension()
                .map(|e| format!(".{}", e.to_string_lossy()))
                .unwrap_or_default();
            let dir = path
                .parent()
                .map(|d| d.to_string_lossy().to_string())
                .filter(|s| !s.is_empty())
                .unwrap_or_else(|| "/".to_string());
            let name = base.trim_end_matches(&ext).to_string();
            obj.set("root", root)?;
            obj.set("dir", dir)?;
            obj.set("base", base)?;
            obj.set("ext", ext)?;
            obj.set("name", name)?;
            Ok(obj)
        })?;
        exports.export("parse", parse)?;

        let format = Function::new(
            ctx.clone(),
            |obj: Object| -> JsResult<String> {
            let dir: String = obj.get::<_, Option<String>>("dir")?.unwrap_or_default();
            let base: String = obj.get::<_, Option<String>>("base")?.unwrap_or_default();
            let name: String = obj.get::<_, Option<String>>("name")?.unwrap_or_default();
            let ext: String = obj.get::<_, Option<String>>("ext")?.unwrap_or_default();
            let mut out = dir;
            if !out.is_empty() && !out.ends_with('/') {
                out.push('/');
            }
            if !base.is_empty() {
                out.push_str(&base);
            } else {
                out.push_str(&name);
                if !ext.is_empty() {
                    if !ext.starts_with('.') {
                        out.push('.');
                    }
                    out.push_str(&ext);
                }
            }
            Ok(out)
        })?;
        exports.export("format", format)?;

        exports.export("sep", "/")?;
        exports.export("delimiter", ":")?;
        Ok(())
    }
}

/// Node-flavored cleanup: resolve `.`/`..`, collapse duplicate separators.
fn clean(p: &Path) -> String {
    let is_abs = p.is_absolute();
    let mut out: Vec<String> = Vec::new();
    for comp in p.components() {
        match comp {
            Component::RootDir | Component::CurDir => {}
            Component::ParentDir => {
                let can_pop = out.len() > usize::from(is_abs)
                    && out.last().map(|l| l != "..").unwrap_or(false);
                if can_pop {
                    out.pop();
                } else if !is_abs {
                    out.push("..".to_string());
                }
            }
            Component::Normal(part) => {
                out.push(part.to_string_lossy().to_string());
            }
            Component::Prefix(_) => {}
        }
    }
    let joined = out.join("/");
    if is_abs {
        if joined.is_empty() {
            "/".to_string()
        } else {
            format!("/{joined}")
        }
    } else if joined.is_empty() {
        ".".to_string()
    } else {
        joined
    }
}

/// path.relative(from, to) — both resolved against cwd.
fn path_relative(from: &str, to: &str) -> String {
    let cwd = std::env::current_dir().unwrap_or_default();
    let from_abs = absolutize(&cwd, from);
    let to_abs = absolutize(&cwd, to);
    let from_comps: Vec<String> = from_abs
        .components()
        .filter_map(|c| match c {
            Component::Normal(p) => Some(p.to_string_lossy().to_string()),
            _ => None,
        })
        .collect();
    let to_comps: Vec<String> = to_abs
        .components()
        .filter_map(|c| match c {
            Component::Normal(p) => Some(p.to_string_lossy().to_string()),
            _ => None,
        })
        .collect();
    let common = from_comps
        .iter()
        .zip(to_comps.iter())
        .take_while(|(a, b)| a == b)
        .count();
    let mut ups = Vec::new();
    for _ in common..from_comps.len() {
        ups.push("..".to_string());
    }
    let mut parts = ups;
    parts.extend(to_comps[common..].iter().cloned());
    if parts.is_empty() {
        ".".to_string()
    } else {
        parts.join("/")
    }
}

fn absolutize(cwd: &Path, p: &str) -> PathBuf {
    let path = PathBuf::from(p);
    if path.is_absolute() {
        clean(&path);
        path
    } else {
        let mut buf = cwd.to_path_buf();
        buf.push(path);
        buf
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn clean_paths() {
        assert_eq!(clean(Path::new("/a/b/../c")), "/a/c");
        assert_eq!(clean(Path::new("a/./b")), "a/b");
        assert_eq!(clean(Path::new("/")), "/");
        assert_eq!(clean(Path::new("../a")), "../a");
        assert_eq!(clean(Path::new("/a//b///c")), "/a/b/c");
    }

    #[test]
    fn relative_paths() {
        assert_eq!(path_relative("/a/b", "/a/b/c/d"), "c/d");
        assert_eq!(path_relative("/a/b/c", "/a/x/y"), "../../x/y");
        assert_eq!(path_relative("/a/b", "/a/b"), ".");
    }
}
