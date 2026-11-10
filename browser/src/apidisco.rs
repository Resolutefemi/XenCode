//! API discovery — endpoint catalog, schema inference, OpenAPI export,
//! copy-as snippets, and monitor diffing (Phase 8).
//!
//! Pipeline: raw [`NetEntry`] captures (already secret-redacted) →
//! [`build_catalog`] groups them into [`Endpoint`]s keyed by
//! (method, base, templated path) — numeric/uuid/hex/token path segments
//! become `{id}`-style placeholders so `/v1/users/42` and `/v1/users/7`
//! collapse into one endpoint. Each endpoint merges the observed query
//! parameters, request/response JSON shapes ([`infer_schema`] /
//! [`merge_schema`]), auth kinds and statuses; the first concrete entry is
//! kept as the `example` the copy-as generators replay.
//!
//! Exports: [`to_openapi`] (OpenAPI 3.1), [`copy_as`] (cURL / fetch /
//! reqwest / QuickJS), [`render_catalog_text`] (human summary). The
//! endpoint monitor is [`diff_catalogs`] — baseline vs fresh capture →
//! [`ApiDiff`] with an `alert` envelope for automation.

use std::collections::HashMap;
use std::fmt;

use serde::{Deserialize, Serialize};
use serde_json::{json, Map, Value};

use crate::netcapture::{redact_entry, AuthKind, NetEntry};

// ---------------------------------------------------------------------
// URL helpers
// ---------------------------------------------------------------------

/// Percent-decode a URL component (`+` becomes space).
pub fn percent_decode(s: &str) -> String {
    let b = s.as_bytes();
    let mut out = Vec::with_capacity(b.len());
    let mut i = 0;
    while i < b.len() {
        match b[i] {
            b'%' if i + 2 < b.len() => {
                let hi = (b[i + 1] as char).to_digit(16);
                let lo = (b[i + 2] as char).to_digit(16);
                if let (Some(h), Some(l)) = (hi, lo) {
                    out.push(((h * 16) + l) as u8);
                    i += 3;
                } else {
                    out.push(b[i]);
                    i += 1;
                }
            }
            b'+' => {
                out.push(b' ');
                i += 1;
            }
            c => {
                out.push(c);
                i += 1;
            }
        }
    }
    String::from_utf8_lossy(&out).into_owned()
}

/// Split a URL into `(base = scheme://authority, path, query_pairs)`.
pub fn split_url(url: &str) -> Option<(String, String, Vec<(String, String)>)> {
    let (scheme, rest) = url.split_once("://")?;
    if scheme.is_empty() {
        return None;
    }
    let end = rest.find(['/', '?', '#']).unwrap_or(rest.len());
    let authority = &rest[..end];
    if authority.is_empty() {
        return None;
    }
    let after = &rest[end..];
    let path = after.split(['?', '#']).next().unwrap_or("");
    let path = if path.is_empty() { "/" } else { path };
    let query = parse_query(url);
    Some((format!("{scheme}://{authority}"), path.to_string(), query))
}

/// Parse the query string of a URL into decoded pairs (order preserved).
pub fn parse_query(url: &str) -> Vec<(String, String)> {
    let Some(q) = url.split_once('?').map(|(_, q)| q) else { return Vec::new() };
    let q = q.split('#').next().unwrap_or(q);
    if q.is_empty() {
        return Vec::new();
    }
    q.split('&')
        .filter(|s| !s.is_empty())
        .map(|pair| match pair.split_once('=') {
            Some((k, v)) => (percent_decode(k), percent_decode(v)),
            None => (percent_decode(pair), String::new()),
        })
        .collect()
}

fn is_uuid(s: &str) -> bool {
    let b = s.as_bytes();
    s.len() == 36
        && b[8] == b'-'
        && b[13] == b'-'
        && b[18] == b'-'
        && b[23] == b'-'
        && s.bytes().all(|c| c.is_ascii_hexdigit() || c == b'-')
}

fn is_random_token(s: &str) -> bool {
    if s.len() < 20 {
        return false;
    }
    let has_upper = s.bytes().any(|c| c.is_ascii_uppercase());
    let has_lower = s.bytes().any(|c| c.is_ascii_lowercase());
    let digits = s.bytes().filter(|c| c.is_ascii_digit()).count();
    let ok_chars = s
        .bytes()
        .all(|c| c.is_ascii_alphanumeric() || c == b'_' || c == b'-' || c == b'~');
    ok_chars && has_upper && has_lower && digits >= 2
}

/// Heuristic path-segment template: `/v1/users/42` → `/v1/users/{id}`,
/// uuids → `{uuid}`, long hex → `{hex}`, random tokens → `{token}`.
pub fn template_path(path: &str) -> String {
    path.split('/')
        .map(segment_template)
        .collect::<Vec<_>>()
        .join("/")
}

fn segment_template(seg: &str) -> String {
    if seg.is_empty() {
        return seg.to_string();
    }
    if seg.bytes().all(|c| c.is_ascii_digit()) {
        return "{id}".into();
    }
    if seg.contains('.')
        && seg.split('.').all(|p| !p.is_empty() && p.bytes().all(|c| c.is_ascii_digit()))
    {
        return "{id}".into();
    }
    if is_uuid(seg) {
        return "{uuid}".into();
    }
    let lower = seg.to_lowercase();
    if lower.len() >= 16 && lower.bytes().all(|c| c.is_ascii_hexdigit()) {
        return "{hex}".into();
    }
    if is_random_token(seg) {
        return "{token}".into();
    }
    seg.to_string()
}

/// Distinct placeholder names in a templated path, in order.
pub fn path_param_names(path: &str) -> Vec<String> {
    let mut out = Vec::new();
    for seg in path.split('/') {
        if let Some(name) = seg.strip_prefix('{').and_then(|s| s.strip_suffix('}')) {
            if !out.iter().any(|n: &String| n == name) {
                out.push(name.to_string());
            }
        }
    }
    out
}

// ---------------------------------------------------------------------
// JSON schema inference
// ---------------------------------------------------------------------

fn string_schema(s: &str) -> Value {
    let mut schema = Map::new();
    schema.insert("type".into(), Value::String("string".into()));
    // light format heuristics — never guess a format we can't check
    if (s.len() >= 20
        && s.as_bytes().get(10) == Some(&b'T')
        && (s.ends_with('Z') || s.contains('+'))
        && s[..4].bytes().all(|c| c.is_ascii_digit()))
        || (s.len() == 10
            && s.as_bytes()[4] == b'-'
            && s.as_bytes()[7] == b'-'
            && s.bytes().all(|c| c.is_ascii_digit() || c == b'-'))
    {
        schema.insert("format".into(), Value::String("date-time".into()));
    } else if is_uuid(s) {
        schema.insert("format".into(), Value::String("uuid".into()));
    } else if s.starts_with("http://") || s.starts_with("https://") {
        schema.insert("format".into(), Value::String("uri".into()));
    } else if s.contains('@') && !s.contains(' ') {
        let (_, domain) = s.rsplit_once('@').unwrap();
        if domain.contains('.') && !domain.is_empty() {
            schema.insert("format".into(), Value::String("email".into()));
        }
    }
    Value::Object(schema)
}

/// Infer a JSON-Schema-ish shape from one observed value.
pub fn infer_schema(v: &Value) -> Value {
    match v {
        Value::Null => json!({"type": "null"}),
        Value::Bool(_) => json!({"type": "boolean"}),
        Value::Number(n) => {
            if n.is_i64() || n.is_u64() {
                json!({"type": "integer"})
            } else {
                json!({"type": "number"})
            }
        }
        Value::String(s) => string_schema(s),
        Value::Array(items) => {
            let mut merged = Value::Null;
            for it in items.iter().take(32) {
                merged = if merged.is_null() {
                    infer_schema(it)
                } else {
                    merge_schema(&merged, &infer_schema(it))
                };
            }
            let items_schema = if merged.is_null() { json!({}) } else { merged };
            json!({"type": "array", "items": items_schema})
        }
        Value::Object(map) => {
            let mut props = Map::new();
            let mut required = Vec::new();
            for (k, val) in map {
                props.insert(k.clone(), infer_schema(val));
                required.push(Value::String(k.clone()));
            }
            json!({"type": "object", "properties": props, "required": required})
        }
    }
}

/// Merge two inferred schemas (same shapes deepen; different shapes become
/// an `anyOf`).
pub fn merge_schema(a: &Value, b: &Value) -> Value {
    if a == b {
        return a.clone();
    }
    let ta = a.get("type").and_then(Value::as_str);
    let tb = b.get("type").and_then(Value::as_str);
    match (ta, tb) {
        (Some("object"), Some("object")) => {
            let mut props = Map::new();
            let pa = a.get("properties").and_then(Value::as_object).cloned().unwrap_or_default();
            let pb = b.get("properties").and_then(Value::as_object).cloned().unwrap_or_default();
            for (k, v) in pb {
                let merged = match pa.get(&k) {
                    Some(pv) => merge_schema(pv, &v),
                    None => v,
                };
                props.insert(k, merged);
            }
            for (k, v) in &pa {
                props.entry(k.clone()).or_insert_with(|| v.clone());
            }
            let req_a = a.get("required").and_then(Value::as_array).cloned().unwrap_or_default();
            let req_b = b.get("required").and_then(Value::as_array).cloned().unwrap_or_default();
            let required: Vec<Value> = req_a
                .into_iter()
                .filter(|k| req_b.contains(k))
                .collect();
            json!({"type": "object", "properties": props, "required": required})
        }
        (Some("array"), Some("array")) => {
            let ia = a.get("items").cloned().unwrap_or(Value::Null);
            let ib = b.get("items").cloned().unwrap_or(Value::Null);
            let items = match (ia.is_null(), ib.is_null()) {
                (true, true) => json!({}),
                (true, false) => ib,
                (false, true) => ia,
                (false, false) => merge_schema(&ia, &ib),
            };
            json!({"type": "array", "items": items})
        }
        (Some("string"), Some("string")) => {
            let fa = a.get("format").and_then(Value::as_str);
            let fb = b.get("format").and_then(Value::as_str);
            let mut m = Map::new();
            m.insert("type".into(), Value::String("string".into()));
            if fa.is_some() && fa == fb {
                m.insert("format".into(), Value::String(fa.unwrap().into()));
            }
            Value::Object(m)
        }
        (Some("integer"), Some("number")) | (Some("number"), Some("integer")) => {
            json!({"type": "number"})
        }
        _ => {
            let mut alts: Vec<Value> = Vec::new();
            for v in [a, b] {
                if let Some(list) = v.get("anyOf").and_then(Value::as_array) {
                    alts.extend(list.iter().cloned());
                } else {
                    alts.push(v.clone());
                }
            }
            let mut dedup: Vec<Value> = Vec::new();
            for alt in alts {
                if !dedup.contains(&alt) {
                    dedup.push(alt);
                }
            }
            if dedup.len() == 1 {
                dedup.remove(0)
            } else {
                json!({"anyOf": dedup})
            }
        }
    }
}

/// Schema for a scalar query/form value.
fn infer_scalar_schema(s: &str) -> Value {
    if s.is_empty() {
        json!({"type": "string"})
    } else if s.parse::<i64>().is_ok() {
        json!({"type": "integer"})
    } else if s.parse::<f64>().is_ok() && s.contains('.') {
        json!({"type": "number"})
    } else if s.eq_ignore_ascii_case("true") || s.eq_ignore_ascii_case("false") {
        json!({"type": "boolean"})
    } else {
        string_schema(s)
    }
}

/// One-line readable shape: `object{id: integer, tags: string[]}`.
pub fn schema_brief(v: &Value) -> String {
    let t = v.get("type").and_then(Value::as_str).unwrap_or("");
    match t {
        "object" => {
            let props = v
                .get("properties")
                .and_then(Value::as_object)
                .cloned()
                .unwrap_or_default();
            if props.is_empty() {
                return "object".into();
            }
            let mut parts: Vec<String> = props
                .iter()
                .take(6)
                .map(|(k, pv)| format!("{k}: {}", schema_brief(pv)))
                .collect();
            if props.len() > 6 {
                parts.push(format!("…{} more", props.len() - 6));
            }
            format!("object{{{}}}", parts.join(", "))
        }
        "array" => {
            let items = v.get("items").cloned().unwrap_or(Value::Null);
            if items.is_null() || items.get("type").is_none() {
                "array".into()
            } else {
                format!("{}[]", schema_brief(&items))
            }
        }
        "string" => match v.get("format").and_then(Value::as_str) {
            Some(f) => format!("string<{f}>"),
            None => "string".into(),
        },
        "" => {
            if let Some(list) = v.get("anyOf").and_then(Value::as_array) {
                let parts: Vec<String> = list.iter().map(schema_brief).collect();
                parts.join("|")
            } else {
                "any".into()
            }
        }
        other => other.to_string(),
    }
}

// ---------------------------------------------------------------------
// endpoint catalog
// ---------------------------------------------------------------------

/// Resource types that are page assets, not APIs.
const NON_API_TYPES: &[&str] = &[
    "Document",
    "Stylesheet",
    "Script",
    "Image",
    "Font",
    "Media",
    "Manifest",
    "WebSocket",
    "SignedExchange",
    "TextTrack",
    "Prefetch",
];

const REDIRECT_STATUSES: &[u16] = &[301, 302, 303, 307, 308];

/// Header names excluded from an endpoint's notable-headers list.
const NOISE_HEADERS: &[&str] = &[
    "host",
    "content-length",
    "connection",
    "accept-encoding",
    "accept-language",
    "user-agent",
    "referer",
    "origin",
    "cache-control",
    "pragma",
    "dnt",
    "upgrade-insecure-requests",
    "priority",
    "sec-ch-ua",
    "sec-ch-ua-mobile",
    "sec-ch-ua-platform",
    "sec-fetch-dest",
    "sec-fetch-mode",
    "sec-fetch-site",
    "sec-fetch-user",
    "cookie",
    "if-none-match",
    "if-modified-since",
    "if-range",
    "range",
    "last-modified",
    "etag",
    "expires",
    "server",
    "date",
    "via",
    "x-powered-by",
    "cf-ray",
    "cf-cache-status",
    "cf-request-id",
    "cf-connecting-ip",
    "alt-svc",
    "report-to",
    "nel",
    "strict-transport-security",
    "x-content-type-options",
    "x-frame-options",
    "x-xss-protection",
    "content-security-policy",
    "content-encoding",
    "transfer-encoding",
    "vary",
    "access-control-allow-origin",
    "access-control-allow-headers",
    "access-control-allow-methods",
    "access-control-allow-credentials",
    "access-control-expose-headers",
    "access-control-max-age",
    "x-request-id",
    "x-correlation-id",
    "x-ratelimit-limit",
    "x-ratelimit-remaining",
    "x-ratelimit-reset",
    "timing-allow-origin",
    "set-cookie",
];

fn is_noise_header(name: &str) -> bool {
    NOISE_HEADERS.contains(&name.to_ascii_lowercase().as_str())
}

/// Is this entry catalog-worthy (an API-ish call, not a page asset)?
pub fn is_api_entry(e: &NetEntry) -> bool {
    if e.failed {
        return false;
    }
    if !e.url.starts_with("http://") && !e.url.starts_with("https://") {
        return false;
    }
    if let Some(status) = e.status {
        if REDIRECT_STATUSES.contains(&status) {
            return false;
        }
    }
    match e.resource_type.as_deref() {
        Some(t) if NON_API_TYPES.contains(&t) => return false,
        Some("XHR") | Some("Fetch") | Some("Ping") | Some("EventSource") | Some("Other") => {}
        Some(_) => return false, // unknown-but-classified types are assets
        None => {}
    }
    // Unclassified entries only count when they look like JSON APIs.
    let mime_json = e
        .mime_type
        .as_deref()
        .map(|m| m.to_ascii_lowercase().contains("json"))
        .unwrap_or(false);
    let body_json = e.method != "GET" && e.post_data.as_deref().map(is_json_text).unwrap_or(false);
    e.resource_type.is_some() || mime_json || body_json
}

fn is_json_text(s: &str) -> bool {
    let t = s.trim_start();
    t.starts_with('{') || t.starts_with('[')
}

/// One observed query/form parameter.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Param {
    pub name: String,
    /// How many samples carried it (of the endpoint's `samples`).
    pub count: usize,
    pub schema: Value,
}

/// One discovered endpoint — the merged view of every matching entry.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Endpoint {
    /// `scheme://host[:port]` — the OpenAPI server base.
    pub base: String,
    pub method: String,
    /// Templated path: `/v1/users/{id}`.
    pub path: String,
    pub samples: usize,
    pub statuses: Vec<u16>,
    pub query_params: Vec<Param>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub request_schema: Option<Value>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub response_schema: Option<Value>,
    pub auth: Vec<AuthKind>,
    /// Notable request header names (noise filtered, values redacted).
    pub request_headers: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub request_content_type: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub response_mime: Option<String>,
    /// First concrete (redacted) entry — what copy-as replays.
    pub example: NetEntry,
}

impl Endpoint {
    /// `POST https://api.example.com/v1/things` — display/diff identity.
    pub fn label(&self) -> String {
        format!("{} {}{}", self.method, self.base, self.path)
    }
}

/// The endpoint catalog built from one capture.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EndpointCatalog {
    /// Sorted by (base, path, method).
    pub endpoints: Vec<Endpoint>,
    pub sources: Vec<String>,
    pub generated_at_ms: u128,
}

/// Group raw entries into the endpoint catalog. Entries are re-redacted
/// for the stored example (idempotent), bodies are used as captured.
pub fn build_catalog(entries: &[NetEntry]) -> EndpointCatalog {
    let mut groups: HashMap<String, Endpoint> = HashMap::new();

    for e in entries {
        if !is_api_entry(e) {
            continue;
        }
        let Some((base, path, query)) = split_url(&e.url) else { continue };
        let template = template_path(&path);
        let key = format!("{base}\u{1}{}\u{1}{}", template, e.method);

        let mut redacted_example = e.clone();
        redact_entry(&mut redacted_example);

        let g = groups.entry(key.clone()).or_insert_with(|| {
            Endpoint {
                base: base.clone(),
                method: e.method.clone(),
                path: template.clone(),
                samples: 0,
                statuses: Vec::new(),
                query_params: Vec::new(),
                request_schema: None,
                response_schema: None,
                auth: Vec::new(),
                request_headers: Vec::new(),
                request_content_type: None,
                response_mime: None,
                example: redacted_example,
            }
        });

        g.samples += 1;
        if let Some(s) = e.status {
            if !g.statuses.contains(&s) {
                g.statuses.push(s);
            }
        }

        // query params — merged schema + observation counts
        for (k, v) in &query {
            if k.is_empty() {
                continue;
            }
            let schema = infer_scalar_schema(v);
            match g.query_params.iter_mut().find(|p| p.name == *k) {
                Some(p) => {
                    p.count += 1;
                    p.schema = merge_schema(&p.schema, &schema);
                }
                None => g.query_params.push(Param {
                    name: k.clone(),
                    count: 1,
                    schema,
                }),
            }
        }

        // request body shape
        let req_ct = e
            .request_headers
            .iter()
            .find(|(k, _)| k.eq_ignore_ascii_case("content-type"))
            .map(|(_, v)| v.clone());
        if g.request_content_type.is_none() {
            g.request_content_type = req_ct;
        }
        if let Some(pd) = &e.post_data {
            if let Ok(v) = serde_json::from_str::<Value>(pd.trim()) {
                let schema = infer_schema(&v);
                g.request_schema = Some(match &g.request_schema {
                    Some(prev) => merge_schema(prev, &schema),
                    None => schema,
                });
            }
        }

        // response body shape
        if let Some(body) = &e.body {
            if let Ok(v) = serde_json::from_str::<Value>(body.trim()) {
                let schema = infer_schema(&v);
                g.response_schema = Some(match &g.response_schema {
                    Some(prev) => merge_schema(prev, &schema),
                    None => schema,
                });
            }
        }
        if g.response_mime.is_none() {
            g.response_mime = e.mime_type.clone();
        }

        // auth kinds
        if e.auth != AuthKind::None && !g.auth.contains(&e.auth) {
            g.auth.push(e.auth.clone());
        }

        // notable headers
        for (k, _) in &e.request_headers {
            if is_noise_header(k) {
                continue;
            }
            let kl = k.to_ascii_lowercase();
            if !g.request_headers.iter().any(|h| h.eq_ignore_ascii_case(&kl)) {
                g.request_headers.push(kl);
            }
        }
    }

    let mut endpoints: Vec<Endpoint> = groups.into_values().collect();
    endpoints.sort_by(|a, b| (&a.base, &a.path, &a.method).cmp(&(&b.base, &b.path, &b.method)));
    let mut sources: Vec<String> = endpoints.iter().map(|e| e.base.clone()).collect();
    sources.sort();
    sources.dedup();
    EndpointCatalog {
        endpoints,
        sources,
        generated_at_ms: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .map(|d| d.as_millis())
            .unwrap_or(0),
    }
}

fn status_reason(code: u16) -> &'static str {
    match code {
        200 => "OK",
        201 => "Created",
        202 => "Accepted",
        204 => "No Content",
        301 => "Moved Permanently",
        302 => "Found",
        304 => "Not Modified",
        400 => "Bad Request",
        401 => "Unauthorized",
        403 => "Forbidden",
        404 => "Not Found",
        405 => "Method Not Allowed",
        409 => "Conflict",
        410 => "Gone",
        415 => "Unsupported Media Type",
        422 => "Unprocessable Entity",
        429 => "Too Many Requests",
        500 => "Internal Server Error",
        501 => "Not Implemented",
        502 => "Bad Gateway",
        503 => "Service Unavailable",
        504 => "Gateway Timeout",
        _ => "Response",
    }
}

// ---------------------------------------------------------------------
// OpenAPI export
// ---------------------------------------------------------------------

fn security_scheme(auth: &AuthKind) -> Option<(String, Value)> {
    match auth {
        AuthKind::Bearer => Some((
            "bearerAuth".into(),
            json!({"type": "http", "scheme": "bearer", "description": "Observed Bearer tokens on captured requests."}),
        )),
        AuthKind::ApiKey => Some((
            "apiKeyHeader".into(),
            json!({"type": "apiKey", "in": "header", "name": "X-API-Key", "description": "Observed API-key headers on captured requests."}),
        )),
        AuthKind::Cookie => Some((
            "cookieAuth".into(),
            json!({"type": "apiKey", "in": "cookie", "name": "session", "description": "Observed session cookies on captured requests."}),
        )),
        AuthKind::QueryToken => Some((
            "queryToken".into(),
            json!({"type": "apiKey", "in": "query", "name": "token", "description": "Observed token query parameters on captured requests."}),
        )),
        AuthKind::None => None,
    }
}

/// Generate an OpenAPI 3.1 document from observed traffic.
pub fn to_openapi(catalog: &EndpointCatalog, title: &str) -> Value {
    let mut paths = Map::new();
    let mut schemes: Map<String, Value> = Map::new();

    for ep in &catalog.endpoints {
        let item = paths
            .entry(ep.path.clone())
            .or_insert_with(|| Value::Object(Map::new()));
        let op = item.as_object_mut().expect("path item");

        let mut parameters: Vec<Value> = Vec::new();
        for name in path_param_names(&ep.path) {
            parameters.push(json!({
                "name": name, "in": "path", "required": true,
                "schema": {"type": "string"},
                "description": "Placeholder observed in traffic (id-like path segment)."
            }));
        }
        for p in &ep.query_params {
            parameters.push(json!({
                "name": p.name, "in": "query",
                "required": p.count == ep.samples && ep.samples > 0,
                "schema": p.schema,
                "description": format!("Observed {}/{} sample(s).", p.count, ep.samples),
            }));
        }

        let mut operation = Map::new();
        operation.insert(
            "summary".into(),
            Value::String(format!("{} {}", ep.method, ep.path)),
        );
        operation.insert(
            "description".into(),
            Value::String(format!(
                "Auto-generated from observed browser traffic: {} sample(s), statuses {:?}.",
                ep.samples, ep.statuses
            )),
        );
        operation.insert("tags".into(), json!([ep.base]));
        if !parameters.is_empty() {
            operation.insert("parameters".into(), Value::Array(parameters));
        }

        if let Some(schema) = &ep.request_schema {
            let mut content = Map::new();
            content.insert(
                "application/json".into(),
                json!({"schema": schema}),
            );
            operation.insert(
                "requestBody".into(),
                json!({"required": true, "content": content}),
            );
        }

        let mut responses = Map::new();
        for status in &ep.statuses {
            let mut response = Map::new();
            response.insert(
                "description".into(),
                Value::String(status_reason(*status).to_string()),
            );
            if let Some(schema) = &ep.response_schema {
                let mime = ep
                    .response_mime
                    .as_deref()
                    .filter(|m| m.contains("json"))
                    .unwrap_or("application/json");
                let mut content = Map::new();
                content.insert(mime.into(), json!({"schema": schema}));
                response.insert("content".into(), Value::Object(content));
            }
            responses.insert(status.to_string(), Value::Object(response));
        }
        if responses.is_empty() {
            responses.insert(
                "default".into(),
                json!({"description": "Response (no body captured)."}),
            );
        }
        operation.insert("responses".into(), Value::Object(responses));

        let mut security: Vec<Value> = Vec::new();
        for auth in &ep.auth {
            if let Some((key, scheme)) = security_scheme(auth) {
                schemes.entry(key.clone()).or_insert(scheme.clone());
                security.push(json!({key: []}));
            }
        }
        if !security.is_empty() {
            operation.insert("security".into(), Value::Array(security));
        }

        op.insert(ep.method.to_lowercase(), Value::Object(operation));
    }

    json!({
        "openapi": "3.1.0",
        "info": {
            "title": title,
            "version": "0.1.0",
            "description": "Auto-generated by XenCode API Discovery from observed browser traffic."
        },
        "servers": catalog.sources.iter().map(|s| json!({"url": s})).collect::<Vec<_>>(),
        "tags": catalog.sources.iter().map(|s| json!({"name": s})).collect::<Vec<_>>(),
        "paths": Value::Object(paths),
        "components": {"securitySchemes": Value::Object(schemes)},
    })
}

// ---------------------------------------------------------------------
// copy-as
// ---------------------------------------------------------------------

/// Replay snippet flavors.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CopyAs {
    Curl,
    Fetch,
    Reqwest,
    QuickJS,
}

impl fmt::Display for CopyAs {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        let s = match self {
            CopyAs::Curl => "curl",
            CopyAs::Fetch => "fetch",
            CopyAs::Reqwest => "reqwest",
            CopyAs::QuickJS => "quickjs",
        };
        f.write_str(s)
    }
}

impl CopyAs {
    pub fn parse(s: &str) -> Option<CopyAs> {
        match s.to_ascii_lowercase().as_str() {
            "curl" | "cURL" => Some(CopyAs::Curl),
            "fetch" | "js" | "javascript" => Some(CopyAs::Fetch),
            "reqwest" | "rust" => Some(CopyAs::Reqwest),
            "quickjs" | "xen" | "xen.http" => Some(CopyAs::QuickJS),
            _ => None,
        }
    }

    pub fn all() -> &'static [&'static str] {
        &["curl", "fetch", "reqwest", "quickjs"]
    }
}

/// Headers worth replaying (noise + redacted-value secrets kept only when
/// meaningful — the redaction marker is preserved so users refill them).
fn replay_headers(ep: &Endpoint) -> Vec<(String, String)> {
    ep.example
        .request_headers
        .iter()
        .filter(|(k, _)| !is_noise_header(k))
        .cloned()
        .collect()
}

fn shell_quote(s: &str) -> String {
    format!("'{}'", s.replace('\'', r"'\''"))
}

fn js_quote(s: &str) -> String {
    // A JSON string literal is a valid JS string literal.
    serde_json::to_string(s).unwrap_or_else(|_| "\"\"".into())
}

fn rust_raw_string(s: &str) -> String {
    for n in 1..16usize {
        let hashes = "#".repeat(n);
        let delim = format!("\"{hashes}");
        if !s.contains(&delim) {
            return format!("r{hashes}\"{s}\"{hashes}");
        }
    }
    // Extremely unlikely; fall back to escaping via debug repr.
    format!("\"{}\"", s.escape_default())
}

fn json_or_null(s: &str) -> Option<Value> {
    serde_json::from_str::<Value>(s.trim()).ok()
}

/// Generate the replay snippet for one endpoint (from its example entry).
pub fn copy_as(ep: &Endpoint, flavor: CopyAs) -> String {
    let url = &ep.example.url;
    let headers = replay_headers(ep);
    let body = ep.example.post_data.clone().unwrap_or_default();
    let json_body = if body.is_empty() { None } else { json_or_null(&body) };

    match flavor {
        CopyAs::Curl => {
            let mut first = vec!["curl".to_string()];
            if ep.method != "GET" {
                first.push(format!("-X {}", ep.method));
            }
            first.push(shell_quote(url));
            let mut out = first.join(" ");
            let mut rest: Vec<String> = Vec::new();
            for (k, v) in &headers {
                rest.push(format!("-H {}", &shell_quote(&format!("{k}: {v}"))));
            }
            if let Some(j) = &json_body {
                rest.push(format!("--data {}", shell_quote(&j.to_string())));
            } else if !body.is_empty() {
                rest.push(format!("--data-raw {}", shell_quote(&body)));
            }
            for part in rest {
                out.push_str(&format!(" \\\n  {part}"));
            }
            out
        }
        CopyAs::Fetch => {
            let mut lines = Vec::new();
            if ep.method == "GET" && headers.is_empty() && body.is_empty() {
                lines.push(format!("const res = await fetch({});", js_quote(url)));
                lines.push("console.log(res.status, await res.json());".into());
                return lines.join("\n");
            }
            lines.push(format!("const res = await fetch({}, {{", js_quote(url)));
            if ep.method != "GET" {
                lines.push(format!("  method: \"{}\",", ep.method));
            }
            if !headers.is_empty() {
                lines.push("  headers: {".into());
                for (k, v) in &headers {
                    lines.push(format!("    {}: {},", js_quote(k), js_quote(v)));
                }
                lines.push("  },".into());
            }
            if let Some(j) = &json_body {
                lines.push(format!("  body: JSON.stringify({}),", j));
            } else if !body.is_empty() {
                lines.push(format!("  body: {},", js_quote(&body)));
            }
            lines.push("});".into());
            lines.push("console.log(res.status, await res.json());".into());
            lines.join("\n")
        }
        CopyAs::Reqwest => {
            let mut lines = Vec::new();
            lines.push("let client = reqwest::Client::new();".into());
            let method_fn = ep.method.to_lowercase();
            lines.push(format!("let res = client.{method_fn}({})", js_quote(url)));
            for (k, v) in &headers {
                lines.push(format!(
                    "    .header({}, {})",
                    js_quote(k),
                    js_quote(v)
                ));
            }
            if let Some(j) = &json_body {
                let raw = rust_raw_string(&j.to_string());
                lines.push("    .header(\"Content-Type\", \"application/json\")".into());
                lines.push(format!("    .body({raw})"));
            } else if !body.is_empty() {
                lines.push(format!("    .body({})", rust_raw_string(&body)));
            }
            lines.push("    .send()".into());
            lines.push("    .await?;".into());
            lines.push("println!(\"{} {}\", res.status(), res.text().await?);".into());
            lines.join("\n")
        }
        CopyAs::QuickJS => {
            // xen.http.fetch(url, {method, headers, body, timeoutMs});
            // non-string bodies are JSON-encoded automatically.
            let mut lines = Vec::new();
            if ep.method == "GET" && headers.is_empty() && body.is_empty() {
                lines.push(format!("const res = await xen.http.fetch({});", js_quote(url)));
                lines.push("console.log(res.status, await res.json());".into());
                return lines.join("\n");
            }
            lines.push(format!("const res = await xen.http.fetch({}, {{", js_quote(url)));
            if ep.method != "GET" {
                lines.push(format!("  method: \"{}\",", ep.method));
            }
            if !headers.is_empty() {
                lines.push("  headers: {".into());
                for (k, v) in &headers {
                    lines.push(format!("    {}: {},", js_quote(k), js_quote(v)));
                }
                lines.push("  },".into());
            }
            if let Some(j) = &json_body {
                lines.push(format!("  body: {},", j));
            } else if !body.is_empty() {
                lines.push(format!("  body: {},", js_quote(&body)));
            }
            lines.push("});".into());
            lines.push("console.log(res.status, await res.json());".into());
            lines.join("\n")
        }
    }
}

// ---------------------------------------------------------------------
// rendering + monitor diff
// ---------------------------------------------------------------------

/// Human-readable catalog summary.
pub fn render_catalog_text(catalog: &EndpointCatalog) -> String {
    if catalog.endpoints.is_empty() {
        return "No API endpoints captured (only page assets or no traffic).\n".into();
    }
    let mut out = String::new();
    out.push_str(&format!(
        "{} endpoint(s) across {} host(s):\n\n",
        catalog.endpoints.len(),
        catalog.sources.len()
    ));
    for (i, ep) in catalog.endpoints.iter().enumerate() {
        out.push_str(&format!(
            "{:>3}. {:<6} {}{}   {}{}  ×{}\n",
            i + 1,
            ep.method,
            ep.base,
            ep.path,
            ep.statuses
                .iter()
                .map(|s| s.to_string())
                .collect::<Vec<_>>()
                .join(","),
            if ep.auth.is_empty() {
                String::new()
            } else {
                format!(
                    "  auth:{}",
                    ep.auth
                        .iter()
                        .map(|a| format!(
                            " {}",
                            serde_json::to_value(a)
                                .ok()
                                .and_then(|v| v.as_str().map(str::to_string))
                                .unwrap_or_default()
                        ))
                        .collect::<String>()
                )
            },
            ep.samples
        ));
        if let Some(rs) = &ep.request_schema {
            out.push_str(&format!("      req: {}\n", schema_brief(rs)));
        }
        if let Some(rs) = &ep.response_schema {
            out.push_str(&format!("      res: {}\n", schema_brief(rs)));
        }
        if !ep.query_params.is_empty() {
            let params = ep
                .query_params
                .iter()
                .map(|p| {
                    format!(
                        "{} ({} {}/{})",
                        p.name,
                        schema_brief(&p.schema),
                        p.count,
                        ep.samples
                    )
                })
                .collect::<Vec<_>>()
                .join(", ");
            out.push_str(&format!("      query: {params}\n"));
        }
        if !ep.request_headers.is_empty() {
            out.push_str(&format!(
                "      headers: {}\n",
                ep.request_headers.join(", ")
            ));
        }
    }
    out
}

/// One endpoint-level change between two captures.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct EndpointChange {
    pub method: String,
    pub base: String,
    pub path: String,
    pub statuses_added: Vec<u16>,
    pub statuses_removed: Vec<u16>,
    pub request_changed: bool,
    pub response_changed: bool,
    pub auth_changed: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub old_response_brief: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub new_response_brief: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub old_request_brief: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub new_request_brief: Option<String>,
}

/// The monitor report: baseline vs fresh capture.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct ApiDiff {
    pub added: Vec<Endpoint>,
    pub removed: Vec<Endpoint>,
    pub changed: Vec<EndpointChange>,
}

impl ApiDiff {
    pub fn has_changes(&self) -> bool {
        !self.added.is_empty()
            || !self.removed.is_empty()
            || self.changed.iter().any(|c| {
                !c.statuses_added.is_empty()
                    || !c.statuses_removed.is_empty()
                    || c.request_changed
                    || c.response_changed
                    || c.auth_changed
            })
    }

    /// The machine envelope automation consumes (`alert` drives the hook).
    pub fn alert_json(&self) -> Value {
        json!({
            "alert": self.has_changes(),
            "added": self.added.len(),
            "removed": self.removed.len(),
            "changed": self.changed.len(),
            "endpoints": {
                "added": self.added.iter().map(|e| e.label()).collect::<Vec<_>>(),
                "removed": self.removed.iter().map(|e| e.label()).collect::<Vec<_>>(),
                "changed": self.changed.iter().map(|c| format!("{} {}{}", c.method, c.base, c.path)).collect::<Vec<_>>(),
            },
            "report": self.render_text().trim_end().to_string(),
        })
    }

    /// Human report (the alert body).
    pub fn render_text(&self) -> String {
        let mut out = String::new();
        if !self.has_changes() {
            out.push_str("API monitor: no changes.\n");
            return out;
        }
        out.push_str(&format!(
            "API monitor: {} added, {} removed, {} changed\n\n",
            self.added.len(),
            self.removed.len(),
            self.changed.len()
        ));
        for ep in &self.added {
            out.push_str(&format!("+ {} (new endpoint)\n", ep.label()));
        }
        for ep in &self.removed {
            out.push_str(&format!("- {} (endpoint gone)\n", ep.label()));
        }
        for c in &self.changed {
            let mut bits = Vec::new();
            if !c.statuses_added.is_empty() {
                bits.push(format!("statuses +{:?}", c.statuses_added));
            }
            if !c.statuses_removed.is_empty() {
                bits.push(format!("statuses -{:?}", c.statuses_removed));
            }
            if c.request_changed {
                bits.push("request shape changed".into());
            }
            if c.response_changed {
                bits.push("response shape changed".into());
            }
            if c.auth_changed {
                bits.push("auth changed".into());
            }
            out.push_str(&format!("~ {} {}{}: {}\n", c.method, c.base, c.path, bits.join("; ")));
            if c.response_changed && (c.old_response_brief.is_some() || c.new_response_brief.is_some()) {
                out.push_str(&format!(
                    "      res old: {}\n      res new: {}\n",
                    c.old_response_brief.as_deref().unwrap_or("(none)"),
                    c.new_response_brief.as_deref().unwrap_or("(none)")
                ));
            }
            if c.request_changed && (c.old_request_brief.is_some() || c.new_request_brief.is_some()) {
                out.push_str(&format!(
                    "      req old: {}\n      req new: {}\n",
                    c.old_request_brief.as_deref().unwrap_or("(none)"),
                    c.new_request_brief.as_deref().unwrap_or("(none)")
                ));
            }
        }
        out
    }
}

fn schema_eq(a: &Option<Value>, b: &Option<Value>) -> bool {
    match (a, b) {
        (None, None) => true,
        (Some(x), Some(y)) => x == y,
        _ => false,
    }
}

/// Compare two catalogs endpoint-by-endpoint (identity: method+base+path).
pub fn diff_catalogs(old: &EndpointCatalog, new: &EndpointCatalog) -> ApiDiff {
    let mut diff = ApiDiff::default();

    for ep in &new.endpoints {
        if !old
            .endpoints
            .iter()
            .any(|o| o.method == ep.method && o.base == ep.base && o.path == ep.path)
        {
            diff.added.push(ep.clone());
        }
    }
    for ep in &old.endpoints {
        if !new
            .endpoints
            .iter()
            .any(|n| n.method == ep.method && n.base == ep.base && n.path == ep.path)
        {
            diff.removed.push(ep.clone());
        }
    }

    for n in &new.endpoints {
        let Some(o) = old
            .endpoints
            .iter()
            .find(|o| o.method == n.method && o.base == n.base && o.path == n.path)
        else { continue };
        let statuses_added: Vec<u16> = n
            .statuses
            .iter()
            .filter(|s| !o.statuses.contains(s))
            .copied()
            .collect();
        let statuses_removed: Vec<u16> = o
            .statuses
            .iter()
            .filter(|s| !n.statuses.contains(s))
            .copied()
            .collect();
        let request_changed = !schema_eq(&o.request_schema, &n.request_schema);
        let response_changed = !schema_eq(&o.response_schema, &n.response_schema);
        let auth_changed = o.auth != n.auth;
        if !statuses_added.is_empty()
            || !statuses_removed.is_empty()
            || request_changed
            || response_changed
            || auth_changed
        {
            diff.changed.push(EndpointChange {
                method: n.method.clone(),
                base: n.base.clone(),
                path: n.path.clone(),
                statuses_added,
                statuses_removed,
                request_changed,
                response_changed,
                auth_changed,
                old_response_brief: o.response_schema.as_ref().map(schema_brief),
                new_response_brief: n.response_schema.as_ref().map(schema_brief),
                old_request_brief: o.request_schema.as_ref().map(schema_brief),
                new_request_brief: n.request_schema.as_ref().map(schema_brief),
            });
        }
    }

    diff
}

// ---------------------------------------------------------------------
// tests
// ---------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;
    use crate::netcapture::{AuthKind, NetEntry};

    fn entry(url: &str, method: &str, status: u16, body: Option<&str>) -> NetEntry {
        entry_typed(url, method, status, body, Some("Fetch"))
    }

    fn entry_typed(
        url: &str,
        method: &str,
        status: u16,
        body: Option<&str>,
        rtype: Option<&str>,
    ) -> NetEntry {
        NetEntry {
            request_id: "r".into(),
            url: url.into(),
            method: method.into(),
            status: Some(status),
            status_text: None,
            mime_type: Some("application/json".into()),
            resource_type: rtype.map(str::to_string),
            request_headers: vec![("Content-Type".into(), "application/json".into())],
            response_headers: vec![],
            post_data: None,
            query: parse_query(url),
            auth: AuthKind::None,
            initiator: Some("script".into()),
            redirected_from: None,
            body: body.map(str::to_string),
            body_truncated: false,
            error: None,
            started_ms: 0,
            duration_ms: Some(10),
            finished: true,
            failed: false,
            has_post_data: false,
        }
    }

    #[test]
    fn url_helpers() {
        let (base, path, q) = split_url("https://api.example.com:8443/v1/things?page=2&tag=a%20b&flag").unwrap();
        assert_eq!(base, "https://api.example.com:8443");
        assert_eq!(path, "/v1/things");
        assert_eq!(
            q,
            vec![
                ("page".into(), "2".into()),
                ("tag".into(), "a b".into()),
                ("flag".into(), "".into()),
            ]
        );
        assert!(split_url("about:blank").is_none());
        assert!(split_url("data:text/plain,hi").is_none());
    }

    #[test]
    fn templating_collapses_ids_uuids_hex_tokens() {
        assert_eq!(template_path("/v1/users/42"), "/v1/users/{id}");
        assert_eq!(template_path("/v1/users/3.5"), "/v1/users/{id}");
        assert_eq!(
            template_path("/v2/items/550e8400-e29b-41d4-a716-446655440000"),
            "/v2/items/{uuid}"
        );
        assert_eq!(template_path("/blob/deadbeefdeadbeefdeadbeef"), "/blob/{hex}");
        assert_eq!(template_path("/s/tK9x_Q2mWv7Bn4Lz8pR6"), "/s/{token}");
        assert_eq!(template_path("/v1/users"), "/v1/users");
        assert_eq!(template_path("/api/v2/OAuth2Callback"), "/api/v2/OAuth2Callback");
        assert_eq!(template_path("/"), "/");
        assert_eq!(path_param_names("/v1/users/{id}/posts/{postId}"), vec!["id", "postId"]);
    }

    #[test]
    fn schema_inference_and_merge() {
        let a = infer_schema(&serde_json::json!({"id": 1, "name": "Ada", "tags": ["x"], "when": "2024-01-15T10:00:00Z"}));
        assert_eq!(schema_brief(&a), "object{id: integer, name: string, tags: string[], when: string<date-time>}");
        let b = infer_schema(&serde_json::json!({"id": 2, "name": "L", "tags": [], "extra": true}));
        let m = merge_schema(&a, &b);
        let props = m.get("properties").unwrap();
        assert_eq!(props.get("id").unwrap().get("type").unwrap(), "integer");
        // `extra` is optional (missing in `a`)
        let required = m.get("required").unwrap().as_array().unwrap();
        assert!(!required.contains(&serde_json::json!("extra")));
        // conflicting types become anyOf
        let c = merge_schema(&serde_json::json!({"type": "integer"}), &serde_json::json!({"type": "string"}));
        assert!(c.get("anyOf").is_some());
        let n = merge_schema(&serde_json::json!({"type": "integer"}), &serde_json::json!({"type": "number"}));
        assert_eq!(n.get("type").unwrap(), "number");
        assert_eq!(schema_brief(&serde_json::json!({"type": "array", "items": {"type": "object", "properties": {"a": {"type": "string"}}}})), "object{a: string}[]");
    }

    #[test]
    fn catalog_groups_by_templated_path() {
        let entries = vec![
            entry("https://api.x.dev/v1/users/1", "GET", 200, Some(r#"{"id":1,"name":"a"}"#)),
            entry("https://api.x.dev/v1/users/42", "GET", 200, Some(r#"{"id":42,"name":"b"}"#)),
            entry("https://api.x.dev/v1/things", "POST", 201, Some(r#"{"ok":true}"#)),
            entry_typed("https://cdn.x.dev/app.js", "GET", 200, None, Some("Script")),
        ];
        let cat = build_catalog(&entries);
        assert_eq!(cat.endpoints.len(), 2, "users/{{id}} collapses; cdn script is an asset");
        let users = cat
            .endpoints
            .iter()
            .find(|e| e.path == "/v1/users/{id}")
            .expect("users endpoint");
        assert_eq!(users.samples, 2);
        assert_eq!(users.statuses, vec![200]);
        let props = users.response_schema.as_ref().unwrap().get("properties").unwrap().clone();
        assert!(props.get("name").is_some());
        let things = cat
            .endpoints
            .iter()
            .find(|e| e.path == "/v1/things")
            .expect("things endpoint");
        assert_eq!(things.method, "POST");
        assert!(things.request_schema.is_none(), "no post data captured");
        assert_eq!(cat.sources, vec!["https://api.x.dev".to_string()]);
    }

    #[test]
    fn catalog_skips_redirects_and_failures() {
        let mut hop = entry("https://h.test/a", "GET", 302, None);
        hop.resource_type = Some("XHR".into());
        let mut fail = entry("https://h.test/b", "GET", 0, None);
        fail.failed = true;
        fail.status = None;
        let cat = build_catalog(&[hop, fail]);
        assert!(cat.endpoints.is_empty());
    }

    #[test]
    fn catalog_detects_auth_kinds() {
        let mut e = entry("https://h.test/api/x", "GET", 200, None);
        e.request_headers = vec![("Authorization".into(), "Bearer z".into())];
        e.auth = AuthKind::Bearer;
        let mut e2 = entry("https://h.test/api/y?token=secret", "GET", 200, None);
        e2.auth = AuthKind::QueryToken;
        let cat = build_catalog(&[e, e2]);
        assert_eq!(cat.endpoints.len(), 2);
        assert!(cat.endpoints[0].auth.contains(&AuthKind::Bearer));
        assert!(cat.endpoints[1].auth.contains(&AuthKind::QueryToken));
    }

    #[test]
    fn openapi_document_shape() {
        let mut e = entry("https://api.x.dev/v1/things?page=2", "POST", 201, Some(r#"{"ok":true,"id":7}"#));
        e.post_data = Some(r#"{"name":"thing","tags":["a"]}"#.into());
        e.auth = AuthKind::Bearer;
        let cat = build_catalog(&[e]);
        let doc = to_openapi(&cat, "Observed API");
        assert_eq!(doc["openapi"], "3.1.0");
        assert_eq!(doc["info"]["title"], "Observed API");
        let path = &doc["paths"]["/v1/things"]["post"];
        assert!(path.is_object());
        let page = &path["parameters"][0];
        assert_eq!(page["name"], "page");
        assert_eq!(page["in"], "query");
        let body = &path["requestBody"]["content"]["application/json"]["schema"];
        assert_eq!(schema_brief(body), "object{name: string, tags: string[]}");
        assert_eq!(path["responses"]["201"]["description"], "Created");
        assert!(doc["components"]["securitySchemes"]["bearerAuth"].is_object());
        assert_eq!(doc["servers"][0]["url"], "https://api.x.dev");
    }

    #[test]
    fn copy_as_flavors() {
        let mut e = entry("https://api.x.dev/v1/things?page=2", "POST", 201, None);
        e.request_headers = vec![
            ("Authorization".into(), "***".into()),
            ("Content-Type".into(), "application/json".into()),
            ("X-Custom".into(), "v".into()),
        ];
        e.post_data = Some(r#"{"name":"a b"}"#.into());
        let cat = build_catalog(&[e]);
        let ep = &cat.endpoints[0];

        let curl = copy_as(ep, CopyAs::Curl);
        assert!(curl.contains("curl -X POST 'https://api.x.dev/v1/things?page=2'"), "{curl}");
        assert!(curl.contains("-H 'Authorization: ***'"), "{curl}");
        assert!(curl.contains(r##"--data '{"name":"a b"}'"##), "{curl}");

        let js = copy_as(ep, CopyAs::Fetch);
        assert!(
            js.contains("const res = await fetch(\"https://api.x.dev/v1/things?page=2\", {"),
            "{js}"
        );
        assert!(js.contains("method: \"POST\""), "{js}");
        assert!(js.contains("body: JSON.stringify({\"name\":\"a b\"})"), "{js}");

        let rs = copy_as(ep, CopyAs::Reqwest);
        assert!(rs.contains("let res = client.post(\"https://api.x.dev/v1/things?page=2\")"), "{rs}");
        assert!(rs.contains(".header(\"Content-Type\", \"application/json\")"), "{rs}");
        assert!(rs.contains(".body(r#\"{\"name\":\"a b\"}\"#)"), "{rs}");
        assert!(rs.contains(".await?;"), "{rs}");

        let xen = copy_as(ep, CopyAs::QuickJS);
        assert!(xen.contains("xen.http.fetch"), "{xen}");
        assert!(xen.contains("body: {\"name\":\"a b\"},"), "{xen}");
    }

    #[test]
    fn copy_as_shell_and_raw_string_escaping() {
        let mut e = entry("https://h.test/x", "POST", 200, None);
        e.post_data = Some("it's \"quoted\"".into());
        let cat = build_catalog(&[e]);
        let curl = copy_as(&cat.endpoints[0], CopyAs::Curl);
        assert!(curl.contains("'it'\\''s \"quoted\"'"), "{curl}");
        let rs = copy_as(&cat.endpoints[0], CopyAs::Reqwest);
        assert!(rs.contains(".body(r#\"it's \"quoted\"\"#)"), "{rs}");
    }

    #[test]
    fn monitor_diff_reports_adds_removals_and_changes() {
        let old_entries = vec![
            entry("https://h.test/v1/a", "GET", 200, Some(r#"{"x":1}"#)),
            entry("https://h.test/v1/gone", "GET", 200, None),
        ];
        let new_entries = vec![
            entry("https://h.test/v1/a", "GET", 200, Some(r#"{"x":1,"y":2}"#)),
            entry("https://h.test/v1/a", "GET", 404, None),
            entry("https://h.test/v1/new", "POST", 201, Some(r#"{"ok":1}"#)),
        ];
        let old = build_catalog(&old_entries);
        let new = build_catalog(&new_entries);
        let d = diff_catalogs(&old, &new);
        assert_eq!(d.added.len(), 1);
        assert_eq!(d.added[0].path, "/v1/new");
        assert_eq!(d.removed.len(), 1);
        assert_eq!(d.removed[0].path, "/v1/gone");
        assert_eq!(d.changed.len(), 1);
        let c = &d.changed[0];
        assert!(c.response_changed);
        assert_eq!(c.statuses_added, vec![404]);
        assert_eq!(c.new_response_brief.as_deref(), Some("object{x: integer, y: integer}"));
        assert!(d.has_changes());
        let alert = d.alert_json();
        assert_eq!(alert["alert"], true);
        assert!(alert["report"].as_str().unwrap().contains("/v1/new"));

        let no_change = diff_catalogs(&new, &new);
        assert!(!no_change.has_changes());
        assert_eq!(no_change.render_text(), "API monitor: no changes.\n");
    }

    #[test]
    fn catalog_text_renders() {
        let entries = vec![entry("https://h.test/v1/a?page=1", "GET", 200, Some(r#"[{"id":1}]"#))];
        let cat = build_catalog(&entries);
        let text = render_catalog_text(&cat);
        assert!(text.contains("https://h.test/v1/a"), "{text}");
        assert!(text.contains("200"));
        assert!(text.contains("object{id: integer}[]"), "{text}");
        assert!(text.contains("page (integer 1/1)"), "{text}");
    }
}
