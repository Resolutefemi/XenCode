//! Shared runtime state, carried through context userdata so the stateless
//! `xen.*` `ModuleDef`s can reach the event channel, permissions and the
//! agent/ui bridges.

use rquickjs::JsLifetime;

use crate::events::UiEventTx;
use crate::permissions::PermissionState;

use super::agent::AgentBridge;
use super::ui::UiBridge;

#[derive(Clone)]
pub struct RuntimeState {
    /// UI events (console output, toasts, progress) consumed by the host.
    pub events: UiEventTx,
    /// Deno-style permission gates.
    pub perms: PermissionState,
    /// Agent hooks + messaging shared with the agent core.
    pub agent: AgentBridge,
    /// GUI-side prompt bridge (text input in the native GUI).
    pub ui: UiBridge,
}

unsafe impl<'js> JsLifetime<'js> for RuntimeState {
    type Changed<'to> = RuntimeState;
}

impl RuntimeState {
    pub fn new(
        events: UiEventTx,
        perms: PermissionState,
        agent: AgentBridge,
        ui: UiBridge,
    ) -> Self {
        Self { events, perms, agent, ui }
    }
}
