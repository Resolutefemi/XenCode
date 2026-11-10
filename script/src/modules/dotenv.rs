//! `xen/dotenv` — parse .env files and load them into the environment.

use rquickjs::function::Opt;
use rquickjs::module::{Declarations, Exports, ModuleDef};
use rquickjs::{Ctx, Function, Result as JsResult, Value};

use crate::jsvalue::{from_json, to_json};

pub struct XenDotenv;

impl ModuleDef for XenDotenv {
    fn declare(decl: &Declarations) -> JsResult<()> {
        decl.declare("parse")?;
        decl.declare("load")?;
        Ok(())
    }

    fn evaluate<'js>(ctx: &Ctx<'js>, exports: &Exports<'js>) -> JsResult<()> {
        let parse = Function::new(
            ctx.clone(),
            move |cx: Ctx<'js>, source: String| -> JsResult<Value<'js>> {
                let map = parse_env(&source);
                from_json(&cx, &serde_json::Value::Object(
                    map.into_iter().collect(),
                ))
            },
        )?;
        exports.export("parse", parse)?;

        let load = Function::new(
            ctx.clone(),
            move |cx: Ctx<'js>, path: Opt<String>, opts: Opt<rquickjs::Object<'js>>| -> JsResult<Value<'js>> {
                let path = path.0.unwrap_or_else(|| ".env".to_string());
                let override_existing = opts
                    .0
                    .as_ref()
                    .and_then(|o| o.get::<_, Option<bool>>("override").ok().flatten())
                    .unwrap_or(false);
                let source = std::fs::read_to_string(&path)
                    .map_err(|e| crate::host::js_err(format!("dotenv load `{path}`: {e}")))?;
                let map = parse_env(&source);
                let mut applied = serde_json::Map::new();
                for (key, value) in &map {
                    let text = value.as_str().unwrap_or_default().to_string();
                    if override_existing || std::env::var(key).is_err() {
                        // SAFETY: single-threaded env mutation within the
                        // runtime; scripts run one at a time per process.
                        unsafe { std::env::set_var(key, &text) };
                        applied.insert(key.clone(), serde_json::Value::String(text));
                    }
                }
                from_json(&cx, &serde_json::Value::Object(applied))
            },
        )?;
        exports.export("load", load)?;
        Ok(())
    }
}

/// dotenv-style parse: `KEY=value` lines, `export KEY=value`, quotes trimmed,
/// inline comments after unquoted values dropped, blank/`#` lines skipped.
pub fn parse_env(source: &str) -> serde_json::Map<String, serde_json::Value> {
    let mut map = serde_json::Map::new();
    for line in source.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let line = line.strip_prefix("export ").unwrap_or(line).trim();
        let Some((key, value)) = line.split_once('=') else { continue };
        let key = key.trim().to_string();
        let mut value = value.trim().to_string();
        if value.starts_with('"') && value.ends_with('"') && value.len() >= 2 {
            value = value[1..value.len() - 1].replace("\\n", "\n").replace("\\\"", "\"");
        } else if value.starts_with('\'') && value.ends_with('\'') && value.len() >= 2 {
            value = value[1..value.len() - 1].to_string();
        } else if let Some(idx) = value.find(" #") {
            value = value[..idx].trim().to_string();
        }
        if key.is_empty() {
            continue;
        }
        map.insert(key, serde_json::Value::String(value));
    }
    map
}

#[cfg(test)]
#[allow(unused_imports)]
mod tests {
    use super::*;

    #[test]
    fn parses_shapes() {
        let src = "# comment\nA=1\nexport B=\"two words\"\nC='single'\nD = spaced # trailing\nE=\n\nF=true\n";
        let map = parse_env(src);
        assert_eq!(map.get("A").unwrap(), "1");
        assert_eq!(map.get("B").unwrap(), "two words");
        assert_eq!(map.get("C").unwrap(), "single");
        assert_eq!(map.get("D").unwrap(), "spaced");
        assert_eq!(map.get("E").unwrap(), "");
        assert_eq!(map.get("F").unwrap(), "true");
    }
}

// silence unused import warning if to_json isn't referenced on some paths
#[allow(unused)]
fn _touch() {
    let _ = to_json as fn(&rquickjs::Ctx<'_>, &rquickjs::Value<'_>) -> Result<serde_json::Value, String>;
}
