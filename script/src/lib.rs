//! XenCode automation core (Phase 2) — QuickJS with the `xen.*` host bridge.
//!
//! - One isolated [`runtime::ScriptRuntime`] context per script, with a memory
//!   limit and an interrupt guard (deadline + manual cancel).
//! - Host bridge: [`host::http`] (fetch on reqwest), [`host::fs`] (tokio +
//!   notify + glob), [`host::proc`] (spawn processes), [`host::agent`] (agent
//!   hooks + messaging), [`host::ui`] (toasts/prompts/progress into the
//!   native GUI) — plus the standard shims (`console`, timers, `URL`,
//!   `TextEncoder/Decoder`, `atob/btoa`, `crypto.getRandomValues`).
//! - "No npm" solved in three tiers: built-in Rust modules ([`modules`]),
//!   the npm-lite vendor cache ([`npm`]), and TypeScript stripped in-process
//!   with oxc ([`ts`]).
//! - Deno-style permissions declared in `xen.toml` ([`permissions`]) with
//!   GUI consent on first use, and one trigger engine ([`triggers`]) for
//!   cron schedules, file-watch, webhooks and lifecycle hooks.

pub mod config;
pub mod events;
pub mod host;
pub mod jsvalue;
pub mod modules;
pub mod npm;
pub mod permissions;
pub mod runtime;
pub mod shims;
pub mod timers;
pub mod triggers;
pub mod ts;

pub use config::XenConfig;
pub use events::{UiEvent, UiEventTx};
pub use npm::VendorCache;
pub use permissions::{PermissionState, PromptBridge, PromptReply};
pub use runtime::{RuntimeOptions, ScriptRuntime};
pub use triggers::TriggerEngine;

/// Version of the automation core (matches the workspace release).
pub const VERSION: &str = env!("CARGO_PKG_VERSION");

/// The QuickJS engine tag reported to scripts (`xen.info().engine`).
pub const ENGINE: &str = "quickjs-ng (rquickjs)";
