//! `xen.agent` — hooks (beforeTool/afterTool/onStop), session context and
//! agent messaging.
//!
//! [`AgentBridge`] is the Rust-side handle the agent core uses to drive
//! script-registered hooks and deliver host→script messages. Scripts reach
//! the same bridge through the `xen.agent` module.

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use rquickjs::module::{Declarations, Exports, ModuleDef};
use rquickjs::{AsyncContext, AsyncRuntime, Ctx, Function, Persistent, Result as JsResult, Value};

use crate::jsvalue::to_js;

use super::{js_err, state};

/// Names of the lifecycle hooks a script may register.
pub const HOOK_NAMES: &[&str] = &["beforeTool", "afterTool", "onStop"];

/// A saved JS callback (lifetime-erased; restore inside a runtime lock).
type HookFn = Persistent<Function<'static>>;

#[derive(Default)]
struct AgentInner {
    hooks: HashMap<String, Vec<HookFn>>,
    on_message: Option<HookFn>,
    inbox: Vec<String>,
    session_id: Option<String>,
}

/// Rust-side handle to script-registered agent hooks and the message inbox.
///
/// The agent core calls [`AgentBridge::run_hook`] around tool executions and
/// on stop; [`AgentBridge::deliver`] pushes host→script messages into the
/// script's `onMessage` callback.
#[derive(Clone, Default)]
pub struct AgentBridge {
    inner: Arc<Mutex<AgentInner>>,
    ctx: Arc<Mutex<Option<Arc<AsyncContext>>>>,
}

impl AgentBridge {
    pub fn new() -> Self {
        Self::default()
    }

    /// Bind the bridge to a context (needed before `run_hook`/`deliver`).
    pub fn set_context(&self, ctx: Arc<AsyncContext>) {
        *self.ctx.lock().unwrap() = Some(ctx);
    }

    /// Register (JS side) a hook function under `name`.
    pub fn add_hook(&self, name: &str, f: HookFn) {
        self.inner.lock().unwrap().hooks.entry(name.to_string()).or_default().push(f);
    }

    /// Set the script's message callback (JS side).
    pub fn set_on_message(&self, f: HookFn) {
        self.inner.lock().unwrap().on_message = Some(f);
    }

    /// Script → agent message: buffered until the host drains it.
    pub fn push_message(&self, text: String) {
        self.inner.lock().unwrap().inbox.push(text);
    }

    /// Drain everything the script sent with `xen.agent.message`.
    pub fn drain_messages(&self) -> Vec<String> {
        std::mem::take(&mut self.inner.lock().unwrap().inbox)
    }

    pub fn set_session_id(&self, id: impl Into<String>) {
        self.inner.lock().unwrap().session_id = Some(id.into());
    }

    pub fn session_id(&self) -> Option<String> {
        self.inner.lock().unwrap().session_id.clone()
    }

    /// Run every hook registered under `name` with `payload`; returns the
    /// last defined hook result (JSON), or `None` when no hook ran / a hook
    /// returned `undefined`.
    pub async fn run_hook(
        &self,
        name: &str,
        payload: serde_json::Value,
    ) -> anyhow::Result<Option<serde_json::Value>> {
        let (hooks, ctx) = {
            let inner = self.inner.lock().unwrap();
            let hooks = inner.hooks.get(name).cloned().unwrap_or_default();
            let ctx = self.ctx.lock().unwrap().clone();
            (hooks, ctx)
        };
        let Some(ctx) = ctx else {
            anyhow::bail!("agent bridge not bound to a context");
        };
        let mut last: Option<serde_json::Value> = None;
        for hook in hooks {
            let payload = payload.clone();
            let result: Result<Option<serde_json::Value>, rquickjs::Error> = ctx
                .with(|cx| {
                    let f = hook.restore(&cx)?;
                    let arg = to_js(&cx, &payload)?;
                    let out: Value = f.call((arg,))?;
                    if out.is_undefined() || out.is_null() {
                        Ok(None)
                    } else {
                        crate::jsvalue::to_json(&cx, &out).map(Some).map_err(js_err)
                    }
                })
                .await;
            last = result.map_err(|e| anyhow::anyhow!("hook `{name}` failed: {e}"))?;
        }
        Ok(last)
    }

    /// Deliver a host → script message by calling the script's
    /// `xen.agent.onMessage(fn)` callback.
    pub async fn deliver(&self, text: String) -> anyhow::Result<()> {
        let (on_message, ctx) = {
            let inner = self.inner.lock().unwrap();
            let ctx = self.ctx.lock().unwrap().clone();
            (inner.on_message.clone(), ctx)
        };
        let Some(ctx) = ctx else {
            anyhow::bail!("agent bridge not bound to a context");
        };
        let Some(f) = on_message else {
            anyhow::bail!("script did not register xen.agent.onMessage");
        };
        ctx.with(|cx| {
            let f = f.restore(&cx)?;
            let arg = rquickjs::String::from_str(cx.clone(), &text)?;
            f.call::<_, ()>((arg,))
        })
        .await
        .map_err(|e| anyhow::anyhow!("onMessage failed: {e}"))?;
        Ok(())
    }
}

pub struct XenAgent;

impl ModuleDef for XenAgent {
    fn declare(decl: &Declarations) -> JsResult<()> {
        decl.declare("info")?;
        decl.declare("hook")?;
        decl.declare("message")?;
        decl.declare("onMessage")?;
        Ok(())
    }

    fn evaluate<'js>(ctx: &Ctx<'js>, exports: &Exports<'js>) -> JsResult<()> {
        let state = state(ctx)?;

        let agent_info = state.agent.clone();
        let info = Function::new(ctx.clone(), move || {
            crate::jsvalue::JsonProxy(serde_json::json!({
                "engine": crate::ENGINE,
                "version": crate::VERSION,
                "session": agent_info.session_id(),
            }))
        })?;
        exports.export("info", info)?;

        let agent = state.agent.clone();
        let hook = Function::new(
            ctx.clone(),
            move |cx: Ctx<'js>, name: String, f: Function<'js>| -> JsResult<()> {
                if !HOOK_NAMES.contains(&name.as_str()) {
                    return Err(js_err(format!(
                        "unknown hook `{name}` — expected one of {}",
                        HOOK_NAMES.join(", ")
                    )));
                }
                agent.add_hook(&name, Persistent::save(&cx, f));
                Ok(())
            },
        )?;
        exports.export("hook", hook)?;

        let agent = state.agent.clone();
        let message = Function::new(ctx.clone(), move |text: String| {
            agent.push_message(text);
        })?;
        exports.export("message", message)?;

        let agent = state.agent.clone();
        let on_message = Function::new(ctx.clone(), move |cx: Ctx<'js>, f: Function<'js>| {
            agent.set_on_message(Persistent::save(&cx, f));
        })?;
        exports.export("onMessage", on_message)?;

        Ok(())
    }
}

/// Convenience for tests and the agent core: evaluate a hook through a
/// runtime that was already initialized.
pub async fn run_hook_on(
    rt: &AsyncRuntime,
    bridge: &AgentBridge,
    name: &str,
    payload: serde_json::Value,
) -> anyhow::Result<Option<serde_json::Value>> {
    let _ = rt;
    bridge.run_hook(name, payload).await
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn inbox_roundtrip() {
        let bridge = AgentBridge::new();
        bridge.push_message("hello".into());
        bridge.push_message("world".into());
        assert_eq!(bridge.drain_messages(), vec!["hello", "world"]);
        assert!(bridge.drain_messages().is_empty());
    }

    #[test]
    fn session_roundtrip() {
        let bridge = AgentBridge::new();
        assert!(bridge.session_id().is_none());
        bridge.set_session_id("abc");
        assert_eq!(bridge.session_id().unwrap(), "abc");
    }
}
