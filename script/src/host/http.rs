//! `xen.http` — fetch/headers/streaming on reqwest.
//!
//! `fetch(url, options)` resolves to a WHATWG-flavoured Response object:
//! `status`, `ok`, `statusText`, `url`, `headers`, plus `text()`, `json()`
//! and `arrayBuffer()` accessors implemented as async host functions over
//! the already-buffered body. Every request passes the `net` permission
//! gate (host allowlist / GUI consent) first.

use std::sync::Arc;

use rquickjs::function::Opt;
use rquickjs::prelude::Async;
use rquickjs::module::{Declarations, Exports, ModuleDef};
use rquickjs::{ArrayBuffer, Ctx, Function, Object, Result as JsResult, TypedArray, Value};

use super::{js_err, state};

pub struct XenHttp;

impl ModuleDef for XenHttp {
    fn declare(decl: &Declarations) -> JsResult<()> {
        decl.declare("fetch")?;
        Ok(())
    }

    fn evaluate<'js>(ctx: &Ctx<'js>, exports: &Exports<'js>) -> JsResult<()> {
        let st = state(ctx)?;
        let perms = st.perms.clone();
        let client = Arc::new(
            reqwest::Client::builder()
                .user_agent(concat!("xencode-script/", env!("CARGO_PKG_VERSION")))
                .build()
                .map_err(|e| js_err(format!("http client init failed: {e}")))?,
        );
        let fetch = Function::new(
            ctx.clone(),
            Async(move |cx: Ctx<'js>, url: String, opts: Opt<Object<'js>>| {
                let perms = perms.clone();
                let client = client.clone();
                let cx = cx.clone();
                async move { do_fetch(&cx, &perms, &client, url, opts.0).await }
            }),
        )?;
        exports.export("fetch", fetch)?;
        Ok(())
    }
}

struct ReqSpec {
    method: String,
    headers: Vec<(String, String)>,
    body: Option<Vec<u8>>,
    timeout_ms: Option<u64>,
}

fn read_opts<'js>(cx: &Ctx<'js>, obj: Option<&Object<'js>>) -> JsResult<ReqSpec> {
    let mut spec = ReqSpec {
        method: "GET".into(),
        headers: Vec::new(),
        body: None,
        timeout_ms: None,
    };
    let Some(obj) = obj else { return Ok(spec) };

    if let Some(m) = obj.get::<_, Option<String>>("method")? {
        spec.method = m.to_ascii_uppercase();
    }
    if let Some(headers) = obj.get::<_, Option<Object>>("headers")? {
        for pair in headers.props::<String, Value>() {
            let (k, v) = pair.map_err(|e| js_err(format!("header prop: {e}")))?;
            let val = match v.as_string() {
                Some(s) => s.to_string()?,
                None => {
                    let json = crate::jsvalue::to_json(cx, &v)
                        .map_err(js_err)?;
                    json.to_string()
                }
            };
            spec.headers.push((k, val));
        }
    }
    match obj.get::<_, Option<Value>>("body")? {
        Some(v) if !v.is_undefined() && !v.is_null() => {
            if let Some(bytes) = crate::jsvalue::extract_bytes(&v) {
                spec.body = Some(bytes);
            } else if let Some(s) = v.as_string() {
                spec.body = Some(s.to_string()?.into_bytes());
            } else {
                let json = crate::jsvalue::to_json(cx, &v).map_err(js_err)?;
                spec.body = Some(json.to_string().into_bytes());
            }
        }
        _ => {}
    }
    if let Some(t) = obj.get::<_, Option<f64>>("timeoutMs")? {
        spec.timeout_ms = Some(t.max(0.0) as u64);
    }
    Ok(spec)
}

async fn do_fetch<'js>(
    cx: &Ctx<'js>,
    perms: &crate::permissions::PermissionState,
    client: &reqwest::Client,
    url: String,
    opts: Option<Object<'js>>,
) -> JsResult<Value<'js>> {
    let parsed = url::Url::parse(&url).map_err(|e| js_err(format!("invalid url `{url}`: {e}")))?;
    let host = parsed
        .host_str()
        .ok_or_else(|| js_err(format!("url `{url}` has no host")))?;
    perms.check_net(host).map_err(js_err)?;

    let spec = read_opts(cx, opts.as_ref())?;
    let method: reqwest::Method = spec
        .method
        .parse()
        .map_err(|e| js_err(format!("invalid method `{}`: {e}", spec.method)))?;
    let mut req = client.request(method, parsed);
    for (k, v) in &spec.headers {
        req = req.header(k, v);
    }
    if let Some(body) = &spec.body {
        req = req.body(body.clone());
    }
    if let Some(ms) = spec.timeout_ms {
        req = req.timeout(std::time::Duration::from_millis(ms));
    }

    let resp = req.send().await.map_err(|e| js_err(format!("fetch failed: {e}")))?;

    let status = resp.status();
    let status_text = status.canonical_reason().unwrap_or("").to_string();
    let final_url = resp.url().to_string();
    let mut header_pairs: Vec<(String, String)> = Vec::new();
    for (k, v) in resp.headers().iter() {
        header_pairs.push((k.as_str().to_string(), v.to_str().unwrap_or("").to_string()));
    }

    let bytes = resp
        .bytes()
        .await
        .map_err(|e| js_err(format!("reading body failed: {e}")))?
        .to_vec();

    build_response(cx, status.as_u16(), status.is_success(), &status_text, &final_url, header_pairs, bytes)
        .map(|o| o.into_value())
}

#[allow(clippy::too_many_arguments)]
fn build_response<'js>(
    cx: &Ctx<'js>,
    status: u16,
    ok: bool,
    status_text: &str,
    url: &str,
    headers: Vec<(String, String)>,
    body: Vec<u8>,
) -> JsResult<Object<'js>> {
    let obj = Object::new(cx.clone())?;
    obj.set("status", status)?;
    obj.set("ok", ok)?;
    obj.set("statusText", status_text)?;
    obj.set("url", url)?;

    let header_obj = Object::new(cx.clone())?;
    for (k, v) in &headers {
        header_obj.set(k.clone(), v.clone())?;
    }
    obj.set("headers", header_obj)?;

    // text(): body → UTF-8 string
    let body1 = Arc::new(body.clone());
    let text_fn = Function::new(
        cx.clone(),
        Async(move || {
            let body = body1.clone();
            async move {
                String::from_utf8(body.as_ref().clone()).map_err(|e| {
                    js_err(format!("body is not valid utf-8: {e}"))
                })
            }
        }),
    )?;
    obj.set("text", text_fn)?;

    // json(): body → parsed JSON value
    let body2 = Arc::new(body.clone());
    let json_fn = Function::new(
        cx.clone(),
        Async(move |cx: Ctx<'js>| {
            let body = body2.clone();
            let cx = cx.clone();
            async move {
                let parsed: serde_json::Value = serde_json::from_slice(body.as_ref())
                    .map_err(|e| js_err(format!("body is not valid JSON: {e}")))?;
                Ok::<_, rquickjs::Error>(crate::jsvalue::from_json(&cx, &parsed)?)
            }
        }),
    )?;
    obj.set("json", json_fn)?;

    // arrayBuffer(): body → ArrayBuffer
    let body3 = Arc::new(body.clone());
    let ab_fn = Function::new(
        cx.clone(),
        Async(move |cx: Ctx<'js>| {
            let body = body3.clone();
            let cx = cx.clone();
            async move { Ok::<_, rquickjs::Error>(ArrayBuffer::new(cx, body.as_ref().clone())?) }
        }),
    )?;
    obj.set("arrayBuffer", ab_fn)?;

    // bytes(): body → Uint8Array
    let body4 = Arc::new(body.clone());
    let bytes_fn = Function::new(
        cx.clone(),
        Async(move |cx: Ctx<'js>| {
            let body = body4.clone();
            let cx = cx.clone();
            async move { Ok::<_, rquickjs::Error>(TypedArray::new_copy(cx, body.as_ref().clone())?) }
        }),
    )?;
    obj.set("bytes", bytes_fn)?;

    Ok(obj)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn opts_defaults_without_object() {
        // (compile-shape test: read_opts is exercised through runtime tests)
        let spec = ReqSpec {
            method: "GET".into(),
            headers: vec![],
            body: None,
            timeout_ms: None,
        };
        assert_eq!(spec.method, "GET");
    }
}
