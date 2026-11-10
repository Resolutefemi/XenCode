//! `xen/yaml` — parse/stringify YAML (serde_yaml, no install).

use rquickjs::module::{Declarations, Exports, ModuleDef};
use rquickjs::{Ctx, Function, Result as JsResult, Value};

use crate::jsvalue::{from_json, to_json};

pub struct XenYaml;

impl ModuleDef for XenYaml {
    fn declare(decl: &Declarations) -> JsResult<()> {
        decl.declare("parse")?;
        decl.declare("stringify")?;
        Ok(())
    }

    fn evaluate<'js>(ctx: &Ctx<'js>, exports: &Exports<'js>) -> JsResult<()> {
        let parse = Function::new(
            ctx.clone(),
            move |cx: Ctx<'js>, source: String| -> JsResult<Value<'js>> {
                let value: serde_yaml::Value = serde_yaml::from_str(&source)
                    .map_err(|e| crate::host::js_err(format!("yaml parse: {e}")))?;
                let json = serde_json::to_value(value)
                    .map_err(|e| crate::host::js_err(format!("yaml→json: {e}")))?;
                from_json(&cx, &json)
            },
        )?;
        exports.export("parse", parse)?;

        let stringify = Function::new(
            ctx.clone(),
            move |cx: Ctx<'js>, value: Value| -> JsResult<String> {
                let json = to_json(&cx, &value).map_err(crate::host::js_err)?;
                serde_yaml::to_string(&json)
                    .map_err(|e| crate::host::js_err(format!("yaml stringify: {e}")))
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
    fn yaml_roundtrip_shape() {
        let v: serde_yaml::Value =
            serde_yaml::from_str("name: xen\nitems:\n  - 1\n  - 2\nnested:\n  on: true\n").unwrap();
        let json = serde_json::to_value(v).unwrap();
        assert_eq!(json["name"], "xen");
        assert_eq!(json["items"][1], 2);
        assert_eq!(json["nested"]["on"], true);
        let back = serde_yaml::to_string(&json).unwrap();
        assert!(back.contains("name: xen"));
    }
}
