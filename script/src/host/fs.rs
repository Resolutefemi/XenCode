//! `xen.fs` — read/write/append/watch/glob (tokio + notify).
//!
//! Every path-touching op passes the `fs` permission gate (scope globs from
//! `xen.toml`, GUI consent on first use). Binary reads resolve to
//! `Uint8Array`, text ops take an optional encoding argument (`"utf8"`).

use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use rquickjs::function::Opt;
use rquickjs::prelude::Async;
use rquickjs::module::{Declarations, Exports, ModuleDef};
use rquickjs::{Ctx, Function, Object, Result as JsResult, TypedArray, Value};
use tokio::sync::mpsc;

use crate::permissions::PermissionState;

use super::{js_err, state};

pub struct XenFs;

impl ModuleDef for XenFs {
    fn declare(decl: &Declarations) -> JsResult<()> {
        for name in [
            "readFile", "readText", "writeFile", "appendFile", "remove",
            "mkdir", "rename", "copy", "stat", "glob", "watch",
        ] {
            decl.declare(name)?;
        }
        Ok(())
    }

    fn evaluate<'js>(ctx: &Ctx<'js>, exports: &Exports<'js>) -> JsResult<()> {
        let st = state(ctx)?;
        let perms = st.perms.clone();

        // readFile(path, enc?) → Uint8Array | string
        let p = perms.clone();
        let read_file = Function::new(
            ctx.clone(),
            Async(move |cx: Ctx<'js>, path: String, enc: Opt<String>| {
                let perms = p.clone();
                let cx = cx.clone();
                async move { fs_read_file(&cx, &perms, path, enc.0).await }
            }),
        )?;
        exports.export("readFile", read_file)?;

        // readText(path) → string
        let p = perms.clone();
        let read_text = Function::new(
            ctx.clone(),
            Async(move |cx: Ctx<'js>, path: String| {
                let perms = p.clone();
                let cx = cx.clone();
                async move { fs_read_text(&cx, &perms, path).await }
            }),
        )?;
        exports.export("readText", read_text)?;

        // writeFile(path, data: string|Uint8Array) → undefined
        let p = perms.clone();
        let write_file = Function::new(
            ctx.clone(),
            Async(move |cx: Ctx<'js>, path: String, data: Value<'js>| {
                let perms = p.clone();
                let cx = cx.clone();
                async move { fs_write_file(&cx, &perms, path, data).await }
            }),
        )?;
        exports.export("writeFile", write_file)?;

        // appendFile(path, data)
        let p = perms.clone();
        let append = Function::new(
            ctx.clone(),
            Async(move |cx: Ctx<'js>, path: String, data: Value<'js>| {
                let perms = p.clone();
                let cx = cx.clone();
                async move { fs_append_file(&cx, &perms, path, data).await }
            }),
        )?;
        exports.export("appendFile", append)?;

        // remove(path, {recursive}?)
        let p = perms.clone();
        let remove = Function::new(
            ctx.clone(),
            Async(move |cx: Ctx<'js>, path: String, opts: Opt<Object<'js>>| {
                let perms = p.clone();
                let cx = cx.clone();
                async move { fs_remove(&cx, &perms, path, opts.0).await }
            }),
        )?;
        exports.export("remove", remove)?;

        // mkdir(path, {recursive}?) → created path
        let p = perms.clone();
        let mkdir = Function::new(
            ctx.clone(),
            Async(move |cx: Ctx<'js>, path: String, opts: Opt<Object<'js>>| {
                let perms = p.clone();
                let cx = cx.clone();
                async move { fs_mkdir(&cx, &perms, path, opts.0).await }
            }),
        )?;
        exports.export("mkdir", mkdir)?;

        // rename(from, to)
        let p = perms.clone();
        let rename = Function::new(
            ctx.clone(),
            Async(move |cx: Ctx<'js>, from: String, to: String| {
                let perms = p.clone();
                let cx = cx.clone();
                async move { fs_rename(&cx, &perms, from, to).await }
            }),
        )?;
        exports.export("rename", rename)?;

        // copy(from, to)
        let p = perms.clone();
        let copy = Function::new(
            ctx.clone(),
            Async(move |cx: Ctx<'js>, from: String, to: String| {
                let perms = p.clone();
                let cx = cx.clone();
                async move { fs_copy(&cx, &perms, from, to).await }
            }),
        )?;
        exports.export("copy", copy)?;

        // stat(path) → { size, mtimeMs, isFile, isDirectory, exists }
        let p = perms.clone();
        let stat = Function::new(
            ctx.clone(),
            Async(move |cx: Ctx<'js>, path: String| {
                let perms = p.clone();
                let cx = cx.clone();
                async move { fs_stat(&cx, &perms, path).await }
            }),
        )?;
        exports.export("stat", stat)?;

        // glob(pattern, {cwd, dot}?) → string[]
        let p = perms.clone();
        let glob_fn = Function::new(
            ctx.clone(),
            Async(move |cx: Ctx<'js>, pattern: String, opts: Opt<Object<'js>>| {
                let perms = p.clone();
                let cx = cx.clone();
                async move { fs_glob(&cx, &perms, pattern, opts.0).await }
            }),
        )?;
        exports.export("glob", glob_fn)?;

        // watch(pattern, callback) → { close() }
        let p = perms.clone();
        let watch = Function::new(
            ctx.clone(),
            Async(move |cx: Ctx<'js>, pattern: String, callback: Function<'js>| {
                let p = p.clone();
                let cx = cx.clone();
                async move { fs_watch(&cx, &p, pattern, callback) }
            }),
        )?;
        exports.export("watch", watch)?;

        Ok(())
    }
}

// ---------------------------------------------------------------------------
// named async implementations (concrete signatures keep inference happy)
// ---------------------------------------------------------------------------

async fn fs_read_file<'js>(
    cx: &Ctx<'js>,
    perms: &PermissionState,
    path: String,
    enc: Option<String>,
) -> JsResult<Value<'js>> {
    perms.check_fs_read(&path).map_err(js_err)?;
    let bytes = tokio::fs::read(&path)
        .await
        .map_err(|e| js_err(format!("readFile `{path}`: {e}")))?;
    match enc.as_deref().unwrap_or("binary") {
        "utf8" | "utf-8" | "text" => {
            let text =
                String::from_utf8(bytes).map_err(|e| js_err(format!("readFile `{path}`: {e}")))?;
            let s = rquickjs::String::from_str(cx.clone(), &text)?;
            Ok(s.into_value())
        }
        _ => Ok(TypedArray::new_copy(cx.clone(), bytes)?.into_value()),
    }
}

async fn fs_read_text<'js>(
    cx: &Ctx<'js>,
    perms: &PermissionState,
    path: String,
) -> JsResult<Value<'js>> {
    perms.check_fs_read(&path).map_err(js_err)?;
    let text = tokio::fs::read_to_string(&path)
        .await
        .map_err(|e| js_err(format!("readText `{path}`: {e}")))?;
    let s = rquickjs::String::from_str(cx.clone(), &text)?;
    Ok(s.into_value())
}

async fn fs_write_file<'js>(
    cx: &Ctx<'js>,
    perms: &PermissionState,
    path: String,
    data: Value<'js>,
) -> JsResult<Value<'js>> {
    perms.check_fs_write(&path).map_err(js_err)?;
    let bytes = value_to_bytes(&data)?;
    ensure_parent(&path);
    tokio::fs::write(&path, bytes)
        .await
        .map_err(|e| js_err(format!("writeFile `{path}`: {e}")))?;
    Ok(Value::new_undefined(cx.clone()))
}

async fn fs_append_file<'js>(
    cx: &Ctx<'js>,
    perms: &PermissionState,
    path: String,
    data: Value<'js>,
) -> JsResult<Value<'js>> {
    use tokio::io::AsyncWriteExt;
    perms.check_fs_write(&path).map_err(js_err)?;
    let bytes = value_to_bytes(&data)?;
    ensure_parent(&path);
    let mut f = tokio::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&path)
        .await
        .map_err(|e| js_err(format!("appendFile `{path}`: {e}")))?;
    f.write_all(&bytes)
        .await
        .map_err(|e| js_err(format!("appendFile `{path}`: {e}")))?;
    Ok(Value::new_undefined(cx.clone()))
}

async fn fs_remove<'js>(
    cx: &Ctx<'js>,
    perms: &PermissionState,
    path: String,
    opts: Option<Object<'js>>,
) -> JsResult<Value<'js>> {
    perms.check_fs_write(&path).map_err(js_err)?;
    let recursive = opts
        .as_ref()
        .and_then(|o| o.get::<_, Option<bool>>("recursive").ok().flatten())
        .unwrap_or(false);
    let meta = tokio::fs::symlink_metadata(&path).await;
    let is_dir = meta.map(|m| m.is_dir()).unwrap_or(false);
    let res = if is_dir {
        if recursive {
            tokio::fs::remove_dir_all(&path).await
        } else {
            tokio::fs::remove_dir(&path).await
        }
    } else {
        tokio::fs::remove_file(&path).await
    };
    res.map_err(|e| js_err(format!("remove `{path}`: {e}")))?;
    Ok(Value::new_undefined(cx.clone()))
}

async fn fs_mkdir<'js>(
    cx: &Ctx<'js>,
    perms: &PermissionState,
    path: String,
    opts: Option<Object<'js>>,
) -> JsResult<Value<'js>> {
    perms.check_fs_write(&path).map_err(js_err)?;
    let recursive = opts
        .as_ref()
        .and_then(|o| o.get::<_, Option<bool>>("recursive").ok().flatten())
        .unwrap_or(true);
    let created = if recursive {
        tokio::fs::create_dir_all(&path).await
    } else {
        tokio::fs::create_dir(&path).await
    };
    created.map_err(|e| js_err(format!("mkdir `{path}`: {e}")))?;
    let s = rquickjs::String::from_str(cx.clone(), &path)?;
    Ok(s.into_value())
}

async fn fs_rename<'js>(
    cx: &Ctx<'js>,
    perms: &PermissionState,
    from: String,
    to: String,
) -> JsResult<Value<'js>> {
    perms.check_fs_read(&from).map_err(js_err)?;
    perms.check_fs_write(&to).map_err(js_err)?;
    ensure_parent(&to);
    tokio::fs::rename(&from, &to)
        .await
        .map_err(|e| js_err(format!("rename `{from}` → `{to}`: {e}")))?;
    Ok(Value::new_undefined(cx.clone()))
}

async fn fs_copy<'js>(
    cx: &Ctx<'js>,
    perms: &PermissionState,
    from: String,
    to: String,
) -> JsResult<Value<'js>> {
    perms.check_fs_read(&from).map_err(js_err)?;
    perms.check_fs_write(&to).map_err(js_err)?;
    ensure_parent(&to);
    tokio::fs::copy(&from, &to)
        .await
        .map_err(|e| js_err(format!("copy `{from}` → `{to}`: {e}")))?;
    Ok(Value::new_undefined(cx.clone()))
}

async fn fs_stat<'js>(
    cx: &Ctx<'js>,
    perms: &PermissionState,
    path: String,
) -> JsResult<Value<'js>> {
    perms.check_fs_read(&path).map_err(js_err)?;
    let obj = Object::new(cx.clone())?;
    match tokio::fs::metadata(&path).await {
        Ok(meta) => {
            obj.set("exists", true)?;
            obj.set("size", meta.len() as f64)?;
            obj.set(
                "mtimeMs",
                meta.modified()
                    .ok()
                    .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                    .map(|d| d.as_millis() as f64)
                    .unwrap_or(0.0),
            )?;
            obj.set("isFile", meta.is_file())?;
            obj.set("isDirectory", meta.is_dir())?;
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
            obj.set("exists", false)?;
            obj.set("size", 0.0)?;
            obj.set("mtimeMs", 0.0)?;
            obj.set("isFile", false)?;
            obj.set("isDirectory", false)?;
        }
        Err(e) => return Err(js_err(format!("stat `{path}`: {e}"))),
    }
    Ok(obj.into_value())
}

async fn fs_glob<'js>(
    cx: &Ctx<'js>,
    perms: &PermissionState,
    pattern: String,
    opts: Option<Object<'js>>,
) -> JsResult<Value<'js>> {
    perms.check_fs_read(&pattern).map_err(js_err)?;
    let cwd: PathBuf = opts
        .as_ref()
        .and_then(|o| o.get::<_, Option<String>>("cwd").ok().flatten())
        .map(PathBuf::from)
        .unwrap_or_else(|| std::env::current_dir().unwrap_or_default());
    let dot = opts
        .as_ref()
        .and_then(|o| o.get::<_, Option<bool>>("dot").ok().flatten())
        .unwrap_or(false);
    let out = crate::modules::glob::glob_match(&pattern, &cwd, dot);
    Ok(crate::jsvalue::to_js(cx, &out)?)
}

/// Wire a notify watcher through a forwarding thread into a tokio channel,
/// then run the callback loop as a spawned future (polled under the runtime
/// lock, so the JS callback can be invoked directly).
fn fs_watch<'js>(
    cx: &Ctx<'js>,
    perms: &PermissionState,
    pattern: String,
    callback: Function<'js>,
) -> JsResult<Value<'js>> {
    use notify::Watcher;
    perms.check_fs_read(&pattern).map_err(js_err)?;
    let pat = glob::Pattern::new(&pattern)
        .map_err(|e| js_err(format!("watch `{pattern}`: {e}")))?;

    let (std_tx, std_rx) = std::sync::mpsc::channel::<notify::Result<notify::Event>>();
    let mut watcher = notify::recommended_watcher(std_tx)
        .map_err(|e| js_err(format!("watcher init failed: {e}")))?;

    let root = literal_root(&pattern);
    if root.exists() {
        watcher
            .watch(&root, notify::RecursiveMode::Recursive)
            .map_err(|e| js_err(format!("watch `{}`: {e}", root.display())))?;
    } else {
        return Err(js_err(format!(
            "watch path `{}` does not exist",
            root.display()
        )));
    }
    let watcher = Arc::new(Mutex::new(Some(watcher)));

    let (close_tx, mut close_rx) = mpsc::unbounded_channel::<()>();
    let (ev_tx, mut ev_rx) = mpsc::unbounded_channel::<notify::Event>();

    // std receiver → tokio channel (plain thread owns only Send data)
    std::thread::spawn(move || {
        for msg in std_rx {
            match msg {
                Ok(ev) => {
                    if ev_tx.send(ev).is_err() {
                        break;
                    }
                }
                Err(_) => break,
            }
        }
    });

    struct WatchFilter {
        pattern: glob::Pattern,
        last: Mutex<std::collections::HashMap<String, std::time::Instant>>,
    }
    let filter = Arc::new(WatchFilter {
        pattern: pat,
        last: Mutex::new(std::collections::HashMap::new()),
    });
    let cb = callback.clone();
    let cx2 = cx.clone();

    cx.spawn(async move {
        loop {
            tokio::select! {
                _ = close_rx.recv() => break,
                ev = ev_rx.recv() => {
                    let Some(ev) = ev else { break };
                    let paths: Vec<String> = ev
                        .paths
                        .iter()
                        .map(|p| p.to_string_lossy().to_string())
                        .collect();
                    for path in paths {
                        if !filter.pattern.matches_path(std::path::Path::new(&path)) {
                            continue;
                        }
                        // debounce: one fire per path per 300 ms
                        {
                            let mut last = filter.last.lock().unwrap();
                            let now = std::time::Instant::now();
                            if let Some(t) = last.get(&path) {
                                if now.duration_since(*t)
                                    < std::time::Duration::from_millis(300)
                                {
                                    continue;
                                }
                            }
                            last.insert(path.clone(), now);
                        }
                        let kind = format!("{:?}", ev.kind).to_ascii_lowercase();
                        let kind_v = rquickjs::String::from_str(cx2.clone(), &kind)
                            .map(|s| s.into_value())
                            .unwrap_or_else(|_| Value::new_undefined(cx2.clone()));
                        let path_v = rquickjs::String::from_str(cx2.clone(), &path)
                            .map(|s| s.into_value())
                            .unwrap_or_else(|_| Value::new_undefined(cx2.clone()));
                        let _ = cb.call::<_, ()>((kind_v, path_v));
                    }
                }
            }
        }
    });

    // handle: { close() }
    let handle = Object::new(cx.clone())?;
    let close = Function::new(cx.clone(), move || {
        let _ = close_tx.send(());
        if let Ok(mut slot) = watcher.lock() {
            *slot = None; // drop the notify watcher → forwarding thread exits
        }
    })?;
    handle.set("close", close)?;
    Ok(handle.into_value())
}

/// Convert a JS string/Uint8Array into raw bytes.
fn value_to_bytes(v: &Value<'_>) -> JsResult<Vec<u8>> {
    if let Some(s) = v.as_string() {
        Ok(s.to_string()?.into_bytes())
    } else if let Some(bytes) = crate::jsvalue::extract_bytes(v) {
        Ok(bytes)
    } else {
        Err(js_err("fs data must be a string or Uint8Array"))
    }
}

fn ensure_parent(path: &str) {
    if let Some(parent) = std::path::Path::new(path).parent() {
        if !parent.as_os_str().is_empty() {
            let _ = std::fs::create_dir_all(parent);
        }
    }
}

/// The deepest literal (non-wildcard) ancestor of a glob pattern.
pub fn literal_root(pattern: &str) -> PathBuf {
    let p = std::path::Path::new(pattern);
    let mut root = if p.is_absolute() {
        PathBuf::from("/")
    } else {
        std::env::current_dir().unwrap_or_default()
    };
    for comp in p.components() {
        let s = comp.as_os_str().to_string_lossy();
        if s.contains('*') || s.contains('?') || s.contains('[') || s.contains('{') {
            break;
        }
        match comp {
            std::path::Component::RootDir => continue,
            std::path::Component::Normal(_) => root.push(comp),
            _ => {}
        }
    }
    root
}

/// Bounded recursive walk (symlinks skipped, file count capped).
pub fn walk(dir: &PathBuf, max_files: usize) -> std::io::Result<Vec<String>> {
    let mut out = Vec::new();
    let mut stack = vec![dir.clone()];
    while let Some(d) = stack.pop() {
        let Ok(rd) = std::fs::read_dir(&d) else { continue };
        for entry in rd.flatten() {
            let path = entry.path();
            let Ok(meta) = entry.metadata() else { continue };
            if meta.is_symlink() {
                continue;
            }
            if meta.is_dir() {
                stack.push(path);
            } else {
                out.push(path.to_string_lossy().to_string());
                if out.len() >= max_files {
                    return Ok(out);
                }
            }
        }
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn literal_root_of_glob() {
        assert_eq!(
            literal_root("/tmp/proj/src/**/*.rs"),
            PathBuf::from("/tmp/proj/src")
        );
        assert_eq!(
            literal_root("/tmp/noglob/file.rs"),
            PathBuf::from("/tmp/noglob/file.rs")
        );
    }

    #[tokio::test(flavor = "multi_thread")]
    async fn write_read_roundtrip() {
        let dir = tempfile::tempdir().unwrap();
        let path = dir.path().join("a.txt");
        std::fs::write(&path, "hello").unwrap();
        let perms = crate::permissions::PermissionState::allow_all();
        perms.check_fs_read(path.to_str().unwrap()).unwrap();
        assert_eq!(std::fs::read_to_string(&path).unwrap(), "hello");
    }
}
