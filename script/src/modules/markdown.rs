//! `xen/markdown` — Markdown → HTML (pulldown-cmark, no install).

use rquickjs::function::Opt;
use rquickjs::module::{Declarations, Exports, ModuleDef};
use rquickjs::{Ctx, Function, Result as JsResult};

pub struct XenMarkdown;

impl ModuleDef for XenMarkdown {
    fn declare(decl: &Declarations) -> JsResult<()> {
        decl.declare("toHtml")?;
        Ok(())
    }

    fn evaluate<'js>(ctx: &Ctx<'js>, exports: &Exports<'js>) -> JsResult<()> {
        let to_html = Function::new(
            ctx.clone(),
            move |source: String, opts: Opt<rquickjs::Object<'js>>| -> JsResult<String> {
                let mut options = pulldown_cmark::Options::empty();
                if let Some(obj) = opts.0.as_ref() {
                    for (key, flag) in [
                        ("tables", pulldown_cmark::Options::ENABLE_TABLES),
                        ("strikethrough", pulldown_cmark::Options::ENABLE_STRIKETHROUGH),
                        ("footnotes", pulldown_cmark::Options::ENABLE_FOOTNOTES),
                        ("tasklists", pulldown_cmark::Options::ENABLE_TASKLISTS),
                        ("smartPunctuation", pulldown_cmark::Options::ENABLE_SMART_PUNCTUATION),
                        ("headingAttributes", pulldown_cmark::Options::ENABLE_HEADING_ATTRIBUTES),
                    ] {
                        if obj.get::<_, Option<bool>>(key)?.unwrap_or(false) {
                            options.insert(flag);
                        }
                    }
                }
                let mut html = String::with_capacity(source.len() * 2);
                let parser = pulldown_cmark::Parser::new_ext(&source, options);
                pulldown_cmark::html::push_html(&mut html, parser);
                Ok(html)
            },
        )?;
        exports.export("toHtml", to_html)?;
        Ok(())
    }
}

#[cfg(test)]
#[allow(unused_imports)]
mod tests {
    use super::*;

    #[test]
    fn renders_basic_html() {
        let src = "# Title\n\nhello **world**\n\n- a\n- b\n";
        let mut html = String::new();
        let parser = pulldown_cmark::Parser::new(src);
        pulldown_cmark::html::push_html(&mut html, parser);
        assert!(html.contains("<h1>Title</h1>"));
        assert!(html.contains("<strong>world</strong>"));
        assert!(html.contains("<li>a</li>"));
    }
}
