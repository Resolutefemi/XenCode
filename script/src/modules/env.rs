//! `xen/env` — environment variable access behind the `env` permission gate.

use rquickjs::function::Opt;
use rquickjs::module::{Declarations, Exports, ModuleDef};
use rquickjs::{Ctx, Function, Result as JsResult};

use crate::host::state;

pub struct XenEnv;

impl ModuleDef for XenEnv {
    fn declare(decl: &Declarations) -> JsResult<()> {
        decl.declare("get")?;
        decl.declare("set")?;
        decl.declare("has")?;
        decl.declare("toObject")?;
        Ok(())
    }

    fn evaluate<'js>(ctx: &Ctx<'js>, exports: &Exports<'js>) -> JsResult<()> {
        let st = state(ctx)?;
        let perms = st.perms.clone();

        let p = perms.clone();
        let get = Function::new(ctx.clone(), move |name: String| -> JsResult<Option<String>> {
            p.check_env(&name).map_err(crate::host::js_err)?;
            Ok(std::env::var(&name).ok())
        })?;
        exports.export("get", get)?;

        let p = perms.clone();
        let set = Function::new(
            ctx.clone(),
            move |name: String, value: String| -> JsResult<()> {
            // writing env is a full grant (no per-key scopes)
            if !matches!(p.check_env(&name), Ok(())) {
                return Err(crate::host::js_err(format!(
                    "env access to `{name}` denied — add it to the `env` scope in xen.toml"
                )));
            }
            // SAFETY: env mutation while scripts run one per process.
            unsafe { std::env::set_var(&name, &value) };
            Ok(())
        })?;
        exports.export("set", set)?;

        let p = perms.clone();
        let has = Function::new(ctx.clone(), move |name: String| -> JsResult<bool> {
            p.check_env(&name).map_err(crate::host::js_err)?;
            Ok(std::env::var_os(&name).is_some())
        })?;
        exports.export("has", has)?;

        let p = perms.clone();
        let to_object = Function::new(
            ctx.clone(),
            move |filter: Opt<Vec<String>>| -> JsResult<std::collections::BTreeMap<String, String>> {
            let mut out = std::collections::BTreeMap::new();
            match filter.0 {
                Some(keys) => {
                    for key in keys {
                        p.check_env(&key).map_err(crate::host::js_err)?;
                        if let Ok(v) = std::env::var(&key) {
                            out.insert(key, v);
                        }
                    }
                }
                None => {
                    // full dump needs the "*" grant or an explicit session allow
                    p.check_env("*").map_err(crate::host::js_err)?;
                    for (k, v) in std::env::vars() {
                        out.insert(k, v);
                    }
                }
            }
            Ok(out)
        })?;
        exports.export("toObject", to_object)?;

        Ok(())
    }
}
