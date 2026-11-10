//! Conversions between QuickJS values and `serde_json::Value`.
//!
//! Used by the built-in Rust modules (yaml/toml/csv/sqlite/dotenv) and the
//! npm vendor cache so every boundary conversion goes through one place.
//! Functions, symbols and other non-JSON values are rejected with a clear
//! error; `undefined` maps to `null`.

use rquickjs::{Array, Ctx, IntoJs, Object, Value};

/// QuickJS value → JSON value.
pub fn to_json(ctx: &Ctx<'_>, v: &Value<'_>) -> Result<serde_json::Value, String> {
    match v.type_of() {
        rquickjs::Type::Undefined | rquickjs::Type::Null => Ok(serde_json::Value::Null),
        rquickjs::Type::Bool => Ok(serde_json::Value::Bool(v.as_bool().unwrap())),
        rquickjs::Type::Int => Ok(serde_json::Value::Number(
            serde_json::Number::from(v.as_int().unwrap()),
        )),
        rquickjs::Type::Float => serde_json::Number::from_f64(v.as_float().unwrap_or(0.0))
            .map(serde_json::Value::Number)
            .ok_or_else(|| "float is not a valid JSON number".to_string()),
        rquickjs::Type::String => {
            let s = v.as_string().unwrap().to_string().map_err(|e| e.to_string())?;
            Ok(serde_json::Value::String(s))
        }
        rquickjs::Type::Array => {
            let arr = v.as_array().unwrap();
            let mut out = Vec::with_capacity(arr.len());
            for i in 0..arr.len() {
                let item: Value = arr.get(i).map_err(|e| e.to_string())?;
                out.push(to_json(ctx, &item)?);
            }
            Ok(serde_json::Value::Array(out))
        }
        rquickjs::Type::Object => {
            // TypedArrays / ArrayBuffers → base64 data-url (deterministic, lossless)
            if let Some(bytes) = extract_bytes(v) {
                return Ok(serde_json::Value::String(format!(
                    "data:application/octet-stream;base64,{}",
                    b64_encode(&bytes)
                )));
            }
            let obj = v.as_object().unwrap();
            let mut map = serde_json::Map::new();
            for prop in obj.props::<String, Value>() {
                let (k, val) = prop.map_err(|e| e.to_string())?;
                map.insert(k, to_json(ctx, &val)?);
            }
            Ok(serde_json::Value::Object(map))
        }
        t => Err(format!(
            "cannot convert {t:?} to JSON (functions/symbols are not serializable)"
        )),
    }
}

/// JSON value → QuickJS value.
pub fn from_json<'js>(ctx: &Ctx<'js>, v: &serde_json::Value) -> rquickjs::Result<Value<'js>> {
    Ok(match v {
        serde_json::Value::Null => Value::new_undefined(ctx.clone()),
        serde_json::Value::Bool(b) => Value::new_bool(ctx.clone(), *b),
        serde_json::Value::Number(n) => {
            if let Some(i) = n.as_i64() {
                if i >= i32::MIN as i64 && i <= i32::MAX as i64 {
                    Value::new_int(ctx.clone(), i as i32)
                } else {
                    Value::new_float(ctx.clone(), i as f64)
                }
            } else {
                Value::new_float(ctx.clone(), n.as_f64().unwrap_or(0.0))
            }
        }
        serde_json::Value::String(s) => {
            let s = rquickjs::String::from_str(ctx.clone(), s)?;
            s.into_value()
        }
        serde_json::Value::Array(items) => {
            let arr = Array::new(ctx.clone())?;
            for (i, item) in items.iter().enumerate() {
                arr.set(i, from_json(ctx, item)?)?;
            }
            arr.into_value()
        }
        serde_json::Value::Object(map) => {
            let obj = Object::new(ctx.clone())?;
            for (k, item) in map {
                obj.set(k.clone(), from_json(ctx, item)?)?;
            }
            obj.into_value()
        }
    })
}

/// Deserialize a generic JS value into a Rust type through JSON
/// (e.g. options objects passed by scripts).
pub fn from_js_via_json<T: serde::de::DeserializeOwned>(
    ctx: &Ctx<'_>,
    v: &Value<'_>,
) -> rquickjs::Result<T> {
    let json = to_json(ctx, v).map_err(|msg| rquickjs::Error::FromJs {
        from: "value",
        to: "options",
        message: Some(msg),
    })?;
    serde_json::from_value(json).map_err(|e| rquickjs::Error::FromJs {
        from: "object",
        to: "options",
        message: Some(e.to_string()),
    })
}

pub fn b64_encode(data: &[u8]) -> String {
    use base64::Engine;
    base64::engine::general_purpose::STANDARD.encode(data)
}

pub fn b64_decode(s: &str) -> Result<Vec<u8>, String> {
    use base64::Engine;
    base64::engine::general_purpose::STANDARD
        .decode(s.trim())
        .map_err(|e| e.to_string())
}

/// Serialize a Rust value into JS through JSON.
pub fn to_js<'js, T: serde::Serialize>(ctx: &Ctx<'js>, value: &T) -> rquickjs::Result<Value<'js>> {
    let json = serde_json::to_value(value).map_err(|e| rquickjs::Error::FromJs {
        from: "rust",
        to: "value",
        message: Some(e.to_string()),
    })?;
    from_json(ctx, &json)
}

/// Helper for module authors: define parse/stringify pairs consistently.
pub fn into_js_str<'js>(ctx: &Ctx<'js>, s: impl Into<String>) -> rquickjs::Result<Value<'js>> {
    let s = rquickjs::String::from_str(ctx.clone(), &s.into())?;
    Ok(s.into_value())
}

/// Newtype allowing any JSON value to cross into JS with `IntoJs`.
#[derive(Debug, Clone)]
pub struct JsonProxy(pub serde_json::Value);

impl<'js> IntoJs<'js> for JsonProxy {
    fn into_js(self, ctx: &Ctx<'js>) -> rquickjs::Result<Value<'js>> {
        from_json(ctx, &self.0)
    }
}

/// Extract a copy of the bytes behind a `Uint8Array` / `ArrayBuffer` value.
///
/// Returns `None` for any other value (or a detached buffer). The bytes are
/// copied out immediately — no JavaScript runs while the engine-aliased
/// slice is alive.
pub fn extract_bytes(v: &Value<'_>) -> Option<Vec<u8>> {
    let obj = v.as_object()?;
    if let Some(arr) = obj.as_typed_array::<u8>() {
        // SAFETY: slice used immediately and copied before returning; no JS
        // runs while it is alive and the runtime lock is held by the caller.
        return unsafe { arr.as_bytes() }.map(|b| b.to_vec());
    }
    if let Some(ab) = obj.as_array_buffer() {
        // SAFETY: as above.
        return unsafe { ab.as_bytes() }.map(|b| b.to_vec());
    }
    None
}
