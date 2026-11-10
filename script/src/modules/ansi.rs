//! `xen/ansi` — terminal styling helpers (chalk-flavoured) + `strip`.
//!
//! Color output auto-disables when stderr is not a TTY (`NO_COLOR` and
//! `--no-ansi` honoured by the CLI through [`set_enabled`]).

use std::sync::atomic::{AtomicBool, Ordering};

use rquickjs::module::{Declarations, Exports, ModuleDef};
use rquickjs::{Ctx, Function, Result as JsResult};

pub struct XenAnsi;

static ENABLED: AtomicBool = AtomicBool::new(true);

/// Global on/off (CLI flips this when output is not a TTY / `--no-ansi`).
pub fn set_enabled(on: bool) {
    ENABLED.store(on, Ordering::Relaxed);
}

pub fn is_enabled() -> bool {
    ENABLED.load(Ordering::Relaxed)
}

const CODES: &[(&str, &str, &str)] = &[
    // name, open, close
    ("black", "\x1b[30m", "\x1b[39m"),
    ("red", "\x1b[31m", "\x1b[39m"),
    ("green", "\x1b[32m", "\x1b[39m"),
    ("yellow", "\x1b[33m", "\x1b[39m"),
    ("blue", "\x1b[34m", "\x1b[39m"),
    ("magenta", "\x1b[35m", "\x1b[39m"),
    ("cyan", "\x1b[36m", "\x1b[39m"),
    ("white", "\x1b[37m", "\x1b[39m"),
    ("gray", "\x1b[90m", "\x1b[39m"),
    ("grey", "\x1b[90m", "\x1b[39m"),
    ("brightRed", "\x1b[91m", "\x1b[39m"),
    ("brightGreen", "\x1b[92m", "\x1b[39m"),
    ("brightYellow", "\x1b[93m", "\x1b[39m"),
    ("brightBlue", "\x1b[94m", "\x1b[39m"),
    ("brightMagenta", "\x1b[95m", "\x1b[39m"),
    ("brightCyan", "\x1b[96m", "\x1b[39m"),
    ("bold", "\x1b[1m", "\x1b[22m"),
    ("dim", "\x1b[2m", "\x1b[22m"),
    ("italic", "\x1b[3m", "\x1b[23m"),
    ("underline", "\x1b[4m", "\x1b[24m"),
    ("strikethrough", "\x1b[9m", "\x1b[29m"),
    ("inverse", "\x1b[7m", "\x1b[27m"),
];

/// Wrap `text` in the ANSI pair for `style` (no-op when disabled).
pub fn style(text: &str, style_name: &str) -> String {
    if !is_enabled() {
        return text.to_string();
    }
    if let Some((_, open, close)) = CODES.iter().find(|(n, _, _)| *n == style_name) {
        format!("{open}{text}{close}")
    } else {
        text.to_string()
    }
}

/// Remove every ANSI escape sequence from `text`.
pub fn strip(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut chars = text.chars().peekable();
    while let Some(c) = chars.next() {
        if c == '\x1b' {
            // CSI: ESC [ ... final-byte (0x40–0x7E)
            if chars.peek() == Some(&'[') {
                chars.next();
                for c2 in chars.by_ref() {
                    if ('\u{40}'..='\u{7e}').contains(&c2) {
                        break;
                    }
                }
            } else {
                // simple two-byte escape
                chars.next();
            }
        } else {
            out.push(c);
        }
    }
    out
}

impl ModuleDef for XenAnsi {
    fn declare(decl: &Declarations) -> JsResult<()> {
        for (name, _, _) in CODES {
            decl.declare(*name)?;
        }
        decl.declare("strip")?;
        decl.declare("enabled")?;
        Ok(())
    }

    fn evaluate<'js>(ctx: &Ctx<'js>, exports: &Exports<'js>) -> JsResult<()> {
        for (name, _, _) in CODES {
            let style_name = name.to_string();
            let f = Function::new(ctx.clone(), move |text: String| {
                style(&text, &style_name)
            })?;
            exports.export(*name, f)?;
        }

        let strip_fn = Function::new(ctx.clone(), |text: String| strip(&text))?;
        exports.export("strip", strip_fn)?;

        let enabled = Function::new(ctx.clone(), |on: rquickjs::function::Opt<bool>| {
            if let Some(on) = on.0 {
                set_enabled(on);
            }
            is_enabled()
        })?;
        exports.export("enabled", enabled)?;
        Ok(())
    }
}

#[cfg(test)]
#[allow(unused_imports)]
mod tests {
    use super::*;

    #[test]
    fn wraps_and_strips() {
        set_enabled(true);
        let s = style("err", "red");
        assert_eq!(s, "\x1b[31merr\x1b[39m");
        assert_eq!(strip(&s), "err");
        assert_eq!(strip("\x1b[1;32mgreen\x1b[0m"), "green");
        set_enabled(false);
        assert_eq!(style("err", "red"), "err");
        set_enabled(true);
    }

    #[test]
    fn unknown_style_is_noop() {
        assert_eq!(style("x", "nope"), "x");
    }
}
