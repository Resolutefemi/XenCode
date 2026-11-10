//! Standard shims every script gets — the CommonJS/browser globals QuickJS
//! lacks: `console`, `setTimeout`/`setInterval` (+ clears), `URL`,
//! `URLSearchParams`, `TextEncoder`/`TextDecoder`, `atob`/`btoa`,
//! `crypto.getRandomValues` and `performance.now`.

use rquickjs::{AsyncContext, Ctx, Function, Object, Result, TypedArray, Value};

use crate::events::{UiEvent, UiEventTx};

/// Install all shims on the context's globals.
pub async fn install(ctx: &AsyncContext, events: &UiEventTx) -> Result<()> {
    ctx.async_with(async move |cx| {
        install_console(&cx, events)?;
        install_encoding(&cx)?;
        install_crypto(&cx)?;
        install_url(&cx)?;
        Ok(())
    })
    .await
}

/// console.log/info/warn/error/debug/trace — buffered as UI events so both
/// the CLI and the native GUI can render them.
fn install_console<'js>(cx: &Ctx<'js>, events: &UiEventTx) -> Result<()> {
    let console = Object::new(cx.clone())?;
    for (name, level) in [
        ("log", "log"),
        ("info", "info"),
        ("warn", "warn"),
        ("error", "error"),
        ("debug", "debug"),
        ("trace", "trace"),
    ] {
        let tx = events.clone();
        let lvl = level.to_string();
        let f = Function::new(cx.clone(), move |args: rquickjs::function::Rest<Value>| {
            let text = format_values(&args);
            let _ = tx.send(UiEvent::Log { level: lvl.clone(), text });
        })?;
        console.set(name, f)?;
    }
    let tx = events.clone();
    let assert_fn = Function::new(cx.clone(), move |cond: bool, args: rquickjs::function::Rest<Value>| {
        if !cond {
            let text = format_values(&args);
            let _ = tx.send(UiEvent::Log { level: "error".into(), text: format!("Assertion failed: {text}") });
        }
    })?;
    console.set("assert", assert_fn)?;
    cx.globals().set("console", console)?;
    Ok(())
}

/// Format console args the way devtools do: strings as-is, objects JSON'd.
pub fn format_values(args: &[Value<'_>]) -> String {
    args.iter()
        .filter_map(|v| format_value(v))
        .collect::<Vec<_>>()
        .join(" ")
}

fn format_value(v: &Value<'_>) -> Option<String> {
    match v.type_of() {
        rquickjs::Type::String => v.as_string().map(|s| s.to_string().unwrap_or_default()),
        rquickjs::Type::Int => Some(v.as_int().map(|i| i.to_string()).unwrap_or_default()),
        rquickjs::Type::Float => Some(fmt_float(v.as_float().unwrap_or(0.0))),
        rquickjs::Type::Bool => Some(v.as_bool().map(|b| b.to_string()).unwrap_or_default()),
        rquickjs::Type::Undefined => Some("undefined".into()),
        rquickjs::Type::Null => Some("null".into()),
        _ => {
            // objects/arrays: Rust-side JSON conversion with a fallback
            match crate::jsvalue::to_json(v.ctx(), v) {
                Ok(json) => Some(json.to_string()),
                Err(_) => Some(format!("[{}]", v.type_of())),
            }
        }
    }
}

fn fmt_float(f: f64) -> String {
    if f.fract() == 0.0 && f.abs() < 1e15 {
        format!("{f:.1}")
    } else {
        format!("{f}")
    }
}

/// TextEncoder / TextDecoder / atob / btoa / performance.now.
fn install_encoding<'js>(cx: &Ctx<'js>) -> Result<()> {
    let g = cx.globals();

    // TextEncoder: encode(str) → Uint8Array; encoding = "utf-8"
    let te = Object::new(cx.clone())?;
    let encode = Function::new(cx.clone(), move |cx: Ctx<'js>, text: String| -> Result<TypedArray<'js, u8>> {
        TypedArray::new_copy(cx, text.as_bytes())
    })?;
    te.set("encode", encode)?;
    te.set("encoding", "utf-8")?;
    g.set("TextEncoder", te)?;

    // TextDecoder: new TextDecoder("utf-8"); decode(Uint8Array) → str
    let ctor = Function::new(
        cx.clone(),
        move |cx: Ctx<'js>, _enc: rquickjs::function::Opt<String>| -> Result<Object<'js>> {
        let d = Object::new(cx.clone())?;
        d.set("encoding", "utf-8")?;
        let decode = Function::new(cx.clone(), |bytes: TypedArray<'js, u8>| -> Result<String> {
            // SAFETY: copied immediately; no JS runs while the slice is alive
            let vec = unsafe { bytes.as_bytes() }
                .map(|b| b.to_vec())
                .unwrap_or_default();
            String::from_utf8(vec)
                .map_err(|e| rquickjs::Error::FromJs {
                    from: "Uint8Array",
                    to: "string",
                    message: Some(format!("invalid utf-8: {e}")),
                })
        })?;
        d.set("decode", decode)?;
        Ok(d)
    })?;
    g.set("TextDecoder", ctor)?;

    // atob / btoa
    let btoa = Function::new(cx.clone(), |data: String| -> Result<String> {
        Ok(crate::jsvalue::b64_encode(data.as_bytes()))
    })?;
    g.set("btoa", btoa)?;
    let atob = Function::new(cx.clone(), |data: String| -> Result<String> {
        crate::jsvalue::b64_decode(&data)
            .map_err(|e| rquickjs::Error::FromJs {
                from: "string",
                to: "base64",
                message: Some(format!("atob: {e}")),
            })
            .and_then(|bytes| {
                String::from_utf8(bytes).map_err(|e| rquickjs::Error::FromJs {
                    from: "string",
                    to: "base64",
                    message: Some(format!("atob: invalid utf-8: {e}")),
                })
            })
    })?;
    g.set("atob", atob)?;

    // performance.now (ms since an arbitrary epoch start, like the spec)
    let start = std::time::Instant::now();
    let now = Function::new(cx.clone(), move || start.elapsed().as_secs_f64() * 1000.0)?;
    if let Ok(perf) = Object::new(cx.clone()) {
        perf.set("now", now)?;
        g.set("performance", perf)?;
    }
    Ok(())
}

/// crypto.getRandomValues(Uint8Array) — fills in place and returns it.
fn install_crypto<'js>(cx: &Ctx<'js>) -> Result<()> {
    let crypto = Object::new(cx.clone())?;
    let g = cx.globals();
    let get_random = Function::new(cx.clone(), move |cx: Ctx<'js>, arr: TypedArray<'js, u8>| -> Result<TypedArray<'js, u8>> {
        // fill a fresh buffer and return it (JS keeps the returned array)
        let len = unsafe { arr.as_bytes() }
            .map(|b| b.len())
            .ok_or_else(|| rquickjs::Error::FromJs {
                from: "Uint8Array",
                to: "bytes",
                message: Some("buffer is detached".into()),
            })?;
        let mut buf = vec![0u8; len];
        getrandom::fill(&mut buf).map_err(|e| rquickjs::Error::FromJs {
            from: "crypto",
            to: "random",
            message: Some(e.to_string()),
        })?;
        Ok(TypedArray::new_copy(cx, &buf)?)
    })?;
    crypto.set("getRandomValues", get_random)?;
    // convenience: crypto.randomUUID()
    let uuid = Function::new(cx.clone(), || -> Result<String> {
        let mut b = [0u8; 16];
        getrandom::fill(&mut b).map_err(|e| rquickjs::Error::FromJs {
            from: "crypto",
            to: "uuid",
            message: Some(e.to_string()),
        })?;
        b[6] = (b[6] & 0x0f) | 0x40; // v4
        b[8] = (b[8] & 0x3f) | 0x80;
        let hex: String = b.iter().map(|x| format!("{x:02x}")).collect();
        Ok(format!(
            "{}-{}-{}-{}-{}",
            &hex[0..8], &hex[8..12], &hex[12..16], &hex[16..20], &hex[20..32]
        ))
    })?;
    crypto.set("randomUUID", uuid)?;
    g.set("crypto", crypto)?;
    Ok(())
}

/// URL + URLSearchParams on top of the `url` crate (parse + normalize).
fn install_url<'js>(cx: &Ctx<'js>) -> Result<()> {
    let g = cx.globals();

    // __xenUrlParse(input) → plain record of URL parts (URL class is JS glue)
    let parse = Function::new(cx.clone(), move |cx: Ctx<'js>, input: String, base: rquickjs::function::Opt<String>| -> Result<Object<'js>> {
        let map_err = |e: url::ParseError| rquickjs::Error::FromJs {
            from: "URL",
            to: "string",
            message: Some(e.to_string()),
        };
        let parsed = match base.0 {
            Some(b) => url::Url::parse(&b)
                .and_then(|u| u.join(&input))
                .map_err(map_err)?,
            None => url::Url::parse(&input).map_err(map_err)?,
        };
        let o = Object::new(cx.clone())?;
        o.set("href", parsed.as_str())?;
        o.set("protocol", parsed.scheme())?;
        o.set("username", parsed.username())?;
        o.set("password", parsed.password().unwrap_or(""))?;
        o.set(
            "host",
            parsed.host_str().unwrap_or(""),
        )?;
        o.set("port", parsed.port().map(|p| p.to_string()).unwrap_or_default())?;
        o.set("hostname", parsed.host_str().unwrap_or(""))?;
        o.set("pathname", parsed.path())?;
        o.set("search", parsed.query().map(|q| format!("?{q}")).unwrap_or_default())?;
        o.set("hash", parsed.fragment().map(|f| format!("#{f}")).unwrap_or_default())?;
        let origin = match (parsed.scheme(), parsed.port(), parsed.host_str()) {
            ("http" | "https" | "ws" | "wss", port, Some(host)) => {
                format!("{}://{}{}", parsed.scheme(), host, port.map(|p| format!(":{p}")).unwrap_or_default())
            }
            _ => "null".to_string(),
        };
        o.set("origin", origin)?;
        // searchParams handled in JS glue below
        Ok(o)
    })?;
    g.set("__xenUrlParse", parse)?;

    // __xenQueryParse(search) → [ [k, v], ... ] for URLSearchParams
    let qparse = Function::new(
        cx.clone(),
        |search: String| -> Result<Vec<Vec<String>>> {
            Ok(url_query_pairs(&search).into_iter().map(|(k, v)| vec![k, v]).collect())
        },
    )?;
    g.set("__xenQueryParse", qparse)?;

    // __xenQueryBuild([[k,v],...]) → "a=1&b=2"
    let qbuild = Function::new(
        cx.clone(),
        |pairs: Vec<Vec<String>>| -> Result<String> {
            let mut u = url::Url::parse("http://x.invalid/?").unwrap();
            for pair in pairs {
                if pair.len() >= 2 {
                    u.query_pairs_mut().append_pair(&pair[0], &pair[1]);
                }
            }
            Ok(u.query().unwrap_or("").to_string())
        },
    )?;
    g.set("__xenQueryBuild", qbuild)?;

    // URL / URLSearchParams classes implemented in JS glue on the Rust helpers
    cx.eval::<(), _>(
        r#"globalThis.URL = class URL {
            constructor(input, base) {
                const p = __xenUrlParse(String(input), base?.href ?? base);
                Object.assign(this, p);
                this.searchParams = new URLSearchParams(p.search);
            }
            toString() { return this.href; }
            toJSON() { return this.href; }
            static canParse(input, base) {
                try { __xenUrlParse(String(input), base?.href ?? base); return true; } catch { return false; }
            }
            static parse(input, base) { return new URL(input, base); }
        };
        globalThis.URLSearchParams = class URLSearchParams {
            #pairs;
            constructor(init) {
                this.#pairs = [];
                if (init == null) { }
                else if (Array.isArray(init)) { this.#pairs = init.map(([k, v]) => [String(k), String(v)]); }
                else if (typeof init === 'string') { this.#pairs = __xenQueryParse(init.replace(/^\?/, '')); }
                else { this.#pairs = Object.entries(init).map(([k, v]) => [k, String(v)]); }
            }
            append(k, v) { this.#pairs.push([String(k), String(v)]); }
            delete(k) { this.#pairs = this.#pairs.filter(([a]) => a !== String(k)); }
            get(k) { const f = this.#pairs.find(([a]) => a === String(k)); return f ? f[1] : null; }
            getAll(k) { return this.#pairs.filter(([a]) => a === String(k)).map(([, b]) => b); }
            has(k) { return this.#pairs.some(([a]) => a === String(k)); }
            set(k, v) {
                const key = String(k);
                this.#pairs = this.#pairs.filter(([a]) => a !== key);
                this.#pairs.push([key, String(v)]);
            }
            sort() { this.#pairs.sort((a, b) => a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0); }
            forEach(fn, thisArg) { for (const [k, v] of this.#pairs) fn.call(thisArg, v, k, this); }
            *keys() { for (const [k] of this.#pairs) yield k; }
            *values() { for (const [, v] of this.#pairs) yield v; }
            *entries() { for (const p of this.#pairs) yield p; }
            [Symbol.iterator]() { return this.entries(); }
            toString() { return __xenQueryBuild(this.#pairs); }
        };"#,
    )?;
    Ok(())
}

fn url_query_pairs(search: &str) -> Vec<(String, String)> {
    let s = search.trim_start_matches('?');
    let mut u = url::Url::parse("http://x.invalid/?").unwrap();
    u.set_query(Some(s));
    u.query_pairs().into_owned().into_iter().collect()
}
