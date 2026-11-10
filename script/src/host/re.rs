//! `xen.re` — the XenRE toolkit exposed to QuickJS (Phase 7).
//!
//! Every function takes a file path, passes the `fs` read permission gate
//! (scope globs from `xen.toml`, GUI consent on first use), then runs the
//! native analyzer — no external processes, no Python, nothing but the
//! static binary. Results come back as plain JSON-shaped objects:
//!
//! ```js
//! import { analyze, strings, disasm } from "xen/re";
//! const info = await analyze("./payload.bin");
//! console.log(info.format, info.arch, info.entry_va);
//! const hits = await strings("./payload.bin", { minLen: 6 });
//! const code = await disasm("./payload.bin", { max: 64 });
//! ```

use rquickjs::function::Opt;
use rquickjs::prelude::Async;
use rquickjs::module::{Declarations, Exports, ModuleDef};
use rquickjs::{Ctx, Function, Object, Result as JsResult, Value};

use crate::host::{js_err, state};
use crate::jsvalue;

pub struct XenRe;

impl ModuleDef for XenRe {
    fn declare(decl: &Declarations) -> JsResult<()> {
        for name in [
            "analyze", "strings", "hex", "symbols", "imports", "exports",
            "functions", "disasm",
        ] {
            decl.declare(name)?;
        }
        Ok(())
    }

    fn evaluate<'js>(ctx: &Ctx<'js>, exports: &Exports<'js>) -> JsResult<()> {
        let st = state(ctx)?;
        let perms = st.perms.clone();

        // analyze(path) → BinaryInfo
        let p = perms.clone();
        let f = Function::new(
            ctx.clone(),
            Async(move |cx: Ctx<'js>, path: String| {
                let perms = p.clone();
                let cx = cx.clone();
                async move { re_analyze(&cx, &perms, &path).await }
            }),
        )?;
        exports.export("analyze", f)?;

        // strings(path, {minLen?, utf16Only?, limit?}?) → FoundString[]
        let p = perms.clone();
        let f = Function::new(
            ctx.clone(),
            Async(move |cx: Ctx<'js>, path: String, opts: Opt<Object<'js>>| {
                let perms = p.clone();
                let cx = cx.clone();
                async move { re_strings(&cx, &perms, &path, opts.0).await }
            }),
        )?;
        exports.export("strings", f)?;

        // hex(path, {offset?|va?, len?, width?}?) → HexRow[]
        let p = perms.clone();
        let f = Function::new(
            ctx.clone(),
            Async(move |cx: Ctx<'js>, path: String, opts: Opt<Object<'js>>| {
                let perms = p.clone();
                let cx = cx.clone();
                async move { re_hex(&cx, &perms, &path, opts.0).await }
            }),
        )?;
        exports.export("hex", f)?;

        // symbols(path, {limit?}?) → Symbol[]
        let p = perms.clone();
        let f = Function::new(
            ctx.clone(),
            Async(move |cx: Ctx<'js>, path: String, opts: Opt<Object<'js>>| {
                let perms = p.clone();
                let cx = cx.clone();
                async move { re_symbols(&cx, &perms, &path, opts.0).await }
            }),
        )?;
        exports.export("symbols", f)?;

        // imports(path) → Import[]
        let p = perms.clone();
        let f = Function::new(
            ctx.clone(),
            Async(move |cx: Ctx<'js>, path: String| {
                let perms = p.clone();
                let cx = cx.clone();
                async move { re_imports(&cx, &perms, &path).await }
            }),
        )?;
        exports.export("imports", f)?;

        // exports(path) → Export[]
        let p = perms.clone();
        let f = Function::new(
            ctx.clone(),
            Async(move |cx: Ctx<'js>, path: String| {
                let perms = p.clone();
                let cx = cx.clone();
                async move { re_exports(&cx, &perms, &path).await }
            }),
        )?;
        exports.export("exports", f)?;

        // functions(path, {limit?}?) → Function[]
        let p = perms.clone();
        let f = Function::new(
            ctx.clone(),
            Async(move |cx: Ctx<'js>, path: String, opts: Opt<Object<'js>>| {
                let perms = p.clone();
                let cx = cx.clone();
                async move { re_functions(&cx, &perms, &path, opts.0).await }
            }),
        )?;
        exports.export("functions", f)?;

        // disasm(path, {va?|offset?, len?, arch?, max?}?) → Insn[]
        let p = perms.clone();
        let f = Function::new(
            ctx.clone(),
            Async(move |cx: Ctx<'js>, path: String, opts: Opt<Object<'js>>| {
                let perms = p.clone();
                let cx = cx.clone();
                async move { re_disasm(&cx, &perms, &path, opts.0).await }
            }),
        )?;
        exports.export("disasm", f)?;

        Ok(())
    }
}

/// Read + analyze once for all bindings (permission gate included).
async fn load_and_analyze(
    perms: &crate::permissions::PermissionState,
    path: &str,
) -> JsResult<(xencode_re::BinaryInfo, Vec<u8>)> {
    perms.check_fs_read(path).map_err(js_err)?;
    let data = tokio::fs::read(path)
        .await
        .map_err(|e| js_err(format!("re `{path}`: {e}")))?;
    if data.len() as u64 > xencode_re::MAX_INPUT_BYTES {
        return Err(js_err(format!(
            "re `{path}`: {} bytes exceeds the {} cap",
            data.len(),
            xencode_re::MAX_INPUT_BYTES
        )));
    }
    let info =
        xencode_re::analyze_bytes(&data).map_err(|e| js_err(format!("re `{path}`: {e}")))?;
    Ok((info, data))
}

async fn re_analyze<'js>(
    cx: &Ctx<'js>,
    perms: &crate::permissions::PermissionState,
    path: &str,
) -> JsResult<Value<'js>> {
    let (info, _data) = load_and_analyze(perms, path).await?;
    Ok(jsvalue::to_js(cx, &info)?)
}

async fn re_strings<'js>(
    cx: &Ctx<'js>,
    perms: &crate::permissions::PermissionState,
    path: &str,
    opts: Option<Object<'js>>,
) -> JsResult<Value<'js>> {
    let (_info, data) = load_and_analyze(perms, path).await?;
    let (min, utf16_only, limit) = strings_opts(opts.as_ref())?;
    let mut hits = xencode_re::extract_strings(&data, min);
    if utf16_only {
        hits.retain(|s| s.encoding == xencode_re::StrEnc::Utf16Le);
    }
    hits.truncate(limit);
    Ok(jsvalue::to_js(cx, &hits)?)
}

fn strings_opts(opts: Option<&Object<'_>>) -> JsResult<(usize, bool, usize)> {
    const DEFAULT_LIMIT: usize = 20_000;
    let mut min = 4usize;
    let mut utf16_only = false;
    let mut limit = DEFAULT_LIMIT;
    if let Some(o) = opts {
        min = o
            .get::<_, Option<f64>>("minLen")?
            .map(|v| v.max(1.0) as usize)
            .unwrap_or(min);
        utf16_only = o.get::<_, Option<bool>>("utf16Only")?.unwrap_or(false);
        limit = o
            .get::<_, Option<f64>>("limit")?
            .map(|v| v.max(0.0) as usize)
            .unwrap_or(limit);
    }
    Ok((min, utf16_only, limit))
}

async fn re_hex<'js>(
    cx: &Ctx<'js>,
    perms: &crate::permissions::PermissionState,
    path: &str,
    opts: Option<Object<'js>>,
) -> JsResult<Value<'js>> {
    let (info, data) = load_and_analyze(perms, path).await?;
    let (offset, len, width) = hex_opts(opts.as_ref(), &info)?;
    let (off, take) = xencode_re::clamp_range(offset, len, data.len() as u64);
    let slice = &data[off as usize..(off + take) as usize];
    let rows = xencode_re::hex_rows(slice, off, width);
    Ok(jsvalue::to_js(cx, &rows)?)
}

fn hex_opts(
    opts: Option<&Object<'_>>,
    info: &xencode_re::BinaryInfo,
) -> JsResult<(u64, u64, usize)> {
    // Default window: the entry point when mapped, else the first 256 bytes.
    let default_off = info
        .entry_va
        .and_then(|va| info.va_to_offset(va))
        .unwrap_or(0);
    let mut offset = default_off;
    let mut len = 256u64;
    let mut width = xencode_re::DEFAULT_ROW_WIDTH;
    if let Some(o) = opts {
        offset = o
            .get::<_, Option<f64>>("offset")?
            .map(|v| v.max(0.0) as u64)
            .unwrap_or(offset);
        // `va` (virtual address) takes precedence when given.
        if let Some(va) = o.get::<_, Option<f64>>("va")? {
            let va = va.max(0.0) as u64;
            if let Some(off) = info.va_to_offset(va) {
                offset = off;
            }
        }
        len = o
            .get::<_, Option<f64>>("len")?
            .map(|v| v.max(0.0) as u64)
            .unwrap_or(len);
        width = o
            .get::<_, Option<f64>>("width")?
            .map(|v| (v.max(1.0) as usize).min(64))
            .unwrap_or(width);
    }
    Ok((offset, len, width))
}

async fn re_symbols<'js>(
    cx: &Ctx<'js>,
    perms: &crate::permissions::PermissionState,
    path: &str,
    opts: Option<Object<'js>>,
) -> JsResult<Value<'js>> {
    let (info, _data) = load_and_analyze(perms, path).await?;
    let mut syms = info.symbols;
    if let Some(o) = opts.as_ref() {
        if let Some(limit) = o.get::<_, Option<f64>>("limit")? {
            syms.truncate(limit.max(0.0) as usize);
        }
    }
    Ok(jsvalue::to_js(cx, &syms)?)
}

async fn re_imports<'js>(
    cx: &Ctx<'js>,
    perms: &crate::permissions::PermissionState,
    path: &str,
) -> JsResult<Value<'js>> {
    let (info, _data) = load_and_analyze(perms, path).await?;
    Ok(jsvalue::to_js(cx, &info.imports)?)
}

async fn re_exports<'js>(
    cx: &Ctx<'js>,
    perms: &crate::permissions::PermissionState,
    path: &str,
) -> JsResult<Value<'js>> {
    let (info, _data) = load_and_analyze(perms, path).await?;
    Ok(jsvalue::to_js(cx, &info.exports)?)
}

async fn re_functions<'js>(
    cx: &Ctx<'js>,
    perms: &crate::permissions::PermissionState,
    path: &str,
    opts: Option<Object<'js>>,
) -> JsResult<Value<'js>> {
    let (info, _data) = load_and_analyze(perms, path).await?;
    let mut fns = xencode_re::functions(&info);
    if let Some(o) = opts.as_ref() {
        if let Some(limit) = o.get::<_, Option<f64>>("limit")? {
            fns.truncate(limit.max(0.0) as usize);
        }
    }
    Ok(jsvalue::to_js(cx, &fns)?)
}

async fn re_disasm<'js>(
    cx: &Ctx<'js>,
    perms: &crate::permissions::PermissionState,
    path: &str,
    opts: Option<Object<'js>>,
) -> JsResult<Value<'js>> {
    let (info, data) = load_and_analyze(perms, path).await?;

    // Arch: explicit option wins, else the binary's own.
    let arch = match opts
        .as_ref()
        .and_then(|o| o.get::<_, Option<String>>("arch").ok().flatten())
    {
        Some(a) => xencode_re::parse_arch(&a).map_err(|e| js_err(e.to_string()))?,
        None => xencode_re::arch_from_binary(&info).map_err(|e| js_err(e.to_string()))?,
    };

    // Window: explicit va/offset, else the entry point, else first exec section.
    let (off, base_va) = if let Some(o) = opts.as_ref() {
        if let Some(va) = o.get::<_, Option<f64>>("va")? {
            let va = va.max(0.0) as u64;
            let off = info
                .va_to_offset(va)
                .ok_or_else(|| js_err(format!("va {va:#x} is not mapped")))?;
            (off, va)
        } else if let Some(off) = o.get::<_, Option<f64>>("offset")? {
            let off = off.max(0.0) as u64;
            (off, info.offset_to_va(off).unwrap_or(0))
        } else {
            default_code_window(&info)?
        }
    } else {
        default_code_window(&info)?
    };

    let max = opts
        .as_ref()
        .and_then(|o| o.get::<_, Option<f64>>("max").ok().flatten())
        .map(|v| (v.max(1.0) as usize).min(10_000))
        .unwrap_or(256);

    let take = (data.len() as u64)
        .saturating_sub(off)
        .min(
            opts.as_ref()
                .and_then(|o| o.get::<_, Option<f64>>("len").ok().flatten())
                .map(|v| v.max(0.0) as u64)
                .unwrap_or(4096),
        );
    if take == 0 {
        return Err(js_err("nothing to disassemble at that offset"));
    }
    let code = &data[off as usize..(off + take) as usize];
    let insns = xencode_re::disasm_bytes(code, base_va, arch, max)
        .map_err(|e| js_err(e.to_string()))?;
    Ok(jsvalue::to_js(cx, &insns)?)
}

fn default_code_window(info: &xencode_re::BinaryInfo) -> JsResult<(u64, u64)> {
    let va = info
        .entry_va
        .or_else(|| info.exec_sections().first().and_then(|s| s.va))
        .ok_or_else(|| js_err("no entry point / executable section — pass va or offset"))?;
    let off = info
        .va_to_offset(va)
        .ok_or_else(|| js_err(format!("entry {va:#x} is not mapped")))?;
    Ok((off, va))
}
