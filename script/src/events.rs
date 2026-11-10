//! UI events flowing from scripts to the host (CLI or native GUI).
//!
//! `xen.ui` (toasts, prompts, progress) and the console shim both emit
//! [`UiEvent`]s on an unbounded channel; the host decides how to surface
//! them — stderr lines in the CLI, toast/consent cards in the GUI pane.

use serde::Serialize;
use tokio::sync::mpsc::UnboundedSender;

/// One user-facing event produced by a script.
#[derive(Debug, Clone, Serialize)]
#[serde(tag = "type", rename_all = "camelCase")]
pub enum UiEvent {
    /// Toast notification (`xen.ui.toast`).
    Toast { kind: String, title: String, body: String },
    /// Progress update (`xen.ui.progress`).
    Progress { id: String, value: f64, label: String },
    /// Console output (`console.*` shims).
    Log { level: String, text: String },
    /// Consent request — resolved through [`super::permissions::PromptBridge`].
    PermissionPrompt { kind: String, detail: String },
}

/// Channel scripts push UI events through.
pub type UiEventTx = UnboundedSender<UiEvent>;

/// Create the event channel used by one runtime (or one host process).
pub fn channel() -> (UiEventTx, tokio::sync::mpsc::UnboundedReceiver<UiEvent>) {
    tokio::sync::mpsc::unbounded_channel()
}

/// Format an event for the CLI (stderr).
pub fn format_cli(ev: &UiEvent) -> Option<String> {
    match ev {
        UiEvent::Toast { kind, title, body } => Some(format!(
            "toast[{kind}] {title}{sep}{body}",
            sep = if body.is_empty() { String::new() } else { format!(" — {body}") }
        )),
        UiEvent::Progress { id, value, label } => {
            Some(format!("progress[{id}] {:>5.1}% {label}", value * 100.0))
        }
        UiEvent::Log { level, text } => match level.as_str() {
            "error" => Some(format!("[console.error] {text}")),
            "warn" => Some(format!("[console.warn] {text}")),
            "debug" | "trace" => Some(format!("[console.{level}] {text}")),
            _ => Some(text.clone()),
        },
        UiEvent::PermissionPrompt { kind, detail } => {
            Some(format!("permission requested: {kind} `{detail}`"))
        }
    }
}
