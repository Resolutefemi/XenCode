//! `xen/toml` — parse/stringify TOML (no install).

use rquickjs::module::{Declarations, Exports, ModuleDef};
use rquickjs::{Ctx, Function, Result as JsResult, Value};

use crate::jsvalue::{from_json, to_json};

pub struct XenToml;

impl ModuleDef for XenToml {
    fn declare(decl: &Declarations) -> JsResult<()> {
        decl.declare("parse")?;
        decl.declare("stringify")?;
        Ok(())
    }

    fn evaluate<'js>(ctx: &Ctx<'js>, exports: &Exports<'js>) -> JsResult<()> {
        let parse = Function::new(
            ctx.clone(),
            move |cx: Ctx<'js>, source: String| -> JsResult<Value<'js>> {
                let value: toml::Value = toml::from_str(&source)
                    .map_err(|e| crate::host::js_err(format!("toml parse: {e}")))?;
                let json = serde_json::to_value(value)
                    .map_err(|e| crate::host::js_err(format!("toml→json: {e}")))?;
                from_json(&cx, &json)
            },
        )?;
        exports.export("parse", parse)?;

        let stringify = Function::new(
            ctx.clone(),
            move |cx: Ctx<'js>, value: Value| -> JsResult<String> {
                let json = to_json(&cx, &value).map_err(crate::host::js_err)?;
                let tv: toml::Value = serde_json::from_value(json)
                    .map_err(|e| crate::host::js_err(format!("json→toml: {e}")))?;
                toml::to_string(&tv)
                    .map_err(|e| crate::host::js_err(format!("toml stringify: {e}")))
            },
        )?;
        exports.export("stringify", stringify)?;
        Ok(())
    }
}

#[cfg(test)]
#[allow(unused_imports)]
mod tests {
    use super::*;

    #[test]
    fn toml_parse_shape() {
        let v: toml::Value =
            toml::from_str("[package]\nname = \"xencode\"\nversion = \"3.17.2\"\n").unwrap();
        let json = serde_json::to_value(v).unwrap();
        assert_eq!(json["package"]["name"], "xencode");
        assert_eq!(json["package"]["version"], "3.17.2");
    }
}
