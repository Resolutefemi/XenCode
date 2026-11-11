//! The `xen.*` host bridge — the explicit Rust→JS exposure QuickJS requires.
//!
//! | module | file | surface |
//! |---|---|---|
//! | `xen.http` | [`http`] | fetch/headers/streaming on reqwest |
//! | `xen.fs` | [`fs`] | read/write/append/watch/glob (tokio + notify) |
//! | `xen.proc` | [`proc`] | spawn processes, capture stdout/stderr/exit |
//! | `xen.agent` | [`agent`] | hooks (beforeTool/afterTool/onStop), session context, messaging |
//! | `xen.ui` | [`ui`] | toasts, prompts, progress in the native GUI |
//! | `xen.re` | [`re`] | binary analysis (ELF/PE/Mach-O), strings, hex, disasm |
//! | `xen.browser` | [`browser`] | XenBrowser surface — navigate/click/fill/snapshot via an injected driver |
//!
//! Shared state ([`state::RuntimeState`] — event channel, permissions,
//! agent/ui/browser bridges) travels through context userdata so the stateless
//! `ModuleDef`s can reach it.

pub mod agent;
pub mod browser;
pub mod fs;
pub mod http;
pub mod proc;
pub mod re;
pub mod state;
pub mod ui;

use rquickjs::{Ctx, Error as JsError, Result as JsResult};

/// Pull the shared [`state::RuntimeState`] out of context userdata.
pub fn state<'js>(ctx: &Ctx<'js>) -> JsResult<state::RuntimeState> {
    ctx.userdata::<state::RuntimeState>().map(|g| g.clone()).ok_or_else(|| {
        JsError::FromJs {
            from: "module",
            to: "runtime state",
            message: Some("runtime state missing — runtime was not initialized with RuntimeState".into()),
        }
    })
}

/// Map a Rust-side failure string onto a JS error with the message intact.
pub fn js_err(message: impl Into<String>) -> JsError {
    JsError::FromJs {
        from: "host",
        to: "script",
        message: Some(message.into()),
    }
}
