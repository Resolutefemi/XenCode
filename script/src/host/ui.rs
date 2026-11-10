//! `xen.ui` — toasts, progress and text prompts surfaced in the host UI
//! (stderr lines in the CLI, toast cards + input dialogs in the native GUI).
//!
//! Toasts and progress are fire-and-forget [`UiEvent`]s on the shared
//! channel. `xen.ui.prompt` asks the host through [`UiBridge`] and resolves
//! a Promise with the entered text (or `null` when cancelled / no asker).

use rquickjs::function::{Opt, Rest};
use rquickjs::prelude::Async;
use rquickjs::module::{Declarations, Exports, ModuleDef};
use rquickjs::{Ctx, Function, Result as JsResult, Value};
use std::sync::{Arc, Mutex};

use crate::events::UiEvent;

use super::state;

/// Host-side text prompt: returns the entered text, or `None` on cancel.
pub type UiPromptFn = Arc<dyn Fn(&str, Option<&str>) -> Option<String> + Send + Sync>;

/// Bridge the runtime uses to ask the host for user input. The CLI installs
/// a stdin asker; the GUI renders an input card in the chat pane.
#[derive(Clone, Default)]
pub struct UiBridge {
    prompt: Arc<Mutex<Option<UiPromptFn>>>,
}

impl UiBridge {
    pub fn new() -> Self {
        Self::default()
    }

    /// Install the host-side asker.
    pub fn set_prompt(&self, f: UiPromptFn) {
        *self.prompt.lock().unwrap() = Some(f);
    }

    /// Ask; falls back to `None` (cancelled) when no asker is installed.
    pub fn ask(&self, message: &str, default: Option<&str>) -> Option<String> {
        let f = self.prompt.lock().unwrap().clone()?;
        f(message, default)
    }
}

pub struct XenUi;

impl ModuleDef for XenUi {
    fn declare(decl: &Declarations) -> JsResult<()> {
        decl.declare("toast")?;
        decl.declare("progress")?;
        decl.declare("prompt")?;
        Ok(())
    }

    fn evaluate<'js>(ctx: &Ctx<'js>, exports: &Exports<'js>) -> JsResult<()> {
        let state = state(ctx)?;

        let events = state.events.clone();
        let toast = Function::new(ctx.clone(), move |args: Rest<Value>| {
            let (kind, title, body) = parse_toast_args(&args);
            let _ = events.send(UiEvent::Toast { kind, title, body });
        })?;
        exports.export("toast", toast)?;

        let events = state.events.clone();
        let progress = Function::new(
            ctx.clone(),
            move |id: String, value: f64, label: Opt<String>| {
                let _ = events.send(UiEvent::Progress {
                    id,
                    value: value.clamp(0.0, 1.0),
                    label: label.0.unwrap_or_default(),
                });
            },
        )?;
        exports.export("progress", progress)?;

        let ui = state.ui.clone();
        let prompt = Function::new(
            ctx.clone(),
            Async(move |cx: Ctx<'js>, message: String, default: Opt<String>| {
                let ui = ui.clone();
                let cx = cx.clone();
                async move { ui_prompt(&cx, &ui, message, default.0).await }
            }),
        )?;
        exports.export("prompt", prompt)?;

        Ok(())
    }
}

async fn ui_prompt<'js>(
    cx: &Ctx<'js>,
    ui: &UiBridge,
    message: String,
    default: Option<String>,
) -> JsResult<Value<'js>> {
    let answer = ui.ask(&message, default.as_deref());
    match answer {
        Some(text) => {
            let s = rquickjs::String::from_str(cx.clone(), &text)?;
            Ok(s.into_value())
        }
        None => Ok(Value::new_null(cx.clone())),
    }
}

/// `toast("title")` / `toast("title", "body")` / `toast({ kind, title, body })`.
fn parse_toast_args(args: &Rest<Value>) -> (String, String, String) {
    let first = args.iter().next();
    if let Some(v) = first {
        if let Some(obj) = v.as_object() {
            let get = |key: &str| -> String {
                obj.get::<_, Option<String>>(key).ok().flatten().unwrap_or_default()
            };
            let kind = get("kind");
            let title = get("title");
            let body = get("body");
            if !title.is_empty() || !body.is_empty() {
                return (if kind.is_empty() { "info".into() } else { kind }, title, body);
            }
        }
    }
    let mut it = args.iter();
    let title = it
        .next()
        .and_then(|v| v.as_string().and_then(|s| s.to_string().ok()))
        .unwrap_or_default();
    let body = it
        .next()
        .and_then(|v| v.as_string().and_then(|s| s.to_string().ok()))
        .unwrap_or_default();
    ("info".to_string(), title, body)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::host::js_err;

    #[test]
    fn toast_args_forms() {
        let (tx, mut rx) = crate::events::channel();
        parse_send(&tx, "Saved");
        let ev = rx.try_recv().unwrap();
        match ev {
            UiEvent::Toast { kind, title, body } => {
                assert_eq!((kind.as_str(), title.as_str(), body.as_str()), ("info", "Saved", ""));
            }
            _ => panic!("expected toast"),
        }
    }

    fn parse_send(tx: &crate::events::UiEventTx, title: &str) {
        let _ = tx.send(UiEvent::Toast {
            kind: "info".into(),
            title: title.into(),
            body: String::new(),
        });
    }

    #[test]
    fn prompt_bridge_none_by_default() {
        let ui = UiBridge::new();
        assert!(ui.ask("hi?", None).is_none());
        ui.set_prompt(Arc::new(|m, _d| Some(format!("echo:{m}"))));
        assert_eq!(ui.ask("hi?", None).unwrap(), "echo:hi?");
    }

    #[test]
    fn js_err_carries_message() {
        let e = js_err("nope");
        match e {
            rquickjs::Error::FromJs { message, .. } => {
                assert_eq!(message.unwrap(), "nope");
            }
            other => panic!("unexpected: {other}"),
        }
    }
}
