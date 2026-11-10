//! `xen/zip` — create and read ZIP archives in memory (zip crate).
//!
//! `create(files, opts?)` → `Uint8Array`; `read(data)` → `{ [name]: Uint8Array }`.
//! Values in `files` are strings (text content) or Uint8Arrays (binary).

use rquickjs::function::Opt;
use rquickjs::module::{Declarations, Exports, ModuleDef};
use rquickjs::{Ctx, Function, Object, Result as JsResult, TypedArray, Value};
use std::io::{Read, Write};

use crate::host::js_err;

pub struct XenZip;

impl ModuleDef for XenZip {
    fn declare(decl: &Declarations) -> JsResult<()> {
        decl.declare("create")?;
        decl.declare("read")?;
        Ok(())
    }

    fn evaluate<'js>(ctx: &Ctx<'js>, exports: &Exports<'js>) -> JsResult<()> {
        let create = Function::new(
            ctx.clone(),
            move |cx: Ctx<'js>, files: Object<'js>, opts: Opt<Object<'js>>| -> JsResult<Value<'js>> {
                let level: i64 = opts
                    .0
                    .as_ref()
                    .and_then(|o| o.get::<_, Option<f64>>("level").ok().flatten())
                    .map(|v| v as i64)
                    .unwrap_or(6);
                let level = zip::write::SimpleFileOptions::default()
                    .compression_method(zip::CompressionMethod::Deflated)
                    .compression_level(Some(level.clamp(0, 9) as i64));

                let buf = std::io::Cursor::new(Vec::new());
                let mut writer = zip::ZipWriter::new(buf);
                for pair in files.props::<String, Value>() {
                    let (name, value) = pair.map_err(|e| js_err(format!("zip entry prop: {e}")))?;
                    let bytes = if let Some(s) = value.as_string() {
                        s.to_string()?.into_bytes()
                    } else if let Some(bytes) = crate::jsvalue::extract_bytes(&value) {
                        bytes
                    } else {
                        return Err(js_err(format!("zip entry `{name}` must be a string or Uint8Array")));
                    };
                    writer
                        .start_file(name.clone(), level)
                        .map_err(|e| js_err(format!("zip start `{name}`: {e}")))?;
                    writer
                        .write_all(&bytes)
                        .map_err(|e| js_err(format!("zip write `{name}`: {e}")))?;
                }
                let cursor = writer
                    .finish()
                    .map_err(|e| js_err(format!("zip finish: {e}")))?;
                Ok(TypedArray::new_copy(cx, cursor.into_inner())?.into_value())
            },
        )?;
        exports.export("create", create)?;

        let read = Function::new(
            ctx.clone(),
            move |cx: Ctx<'js>, data: Value<'js>| -> JsResult<Value<'js>> {
                let bytes = crate::jsvalue::extract_bytes(&data)
                    .ok_or_else(|| js_err("zip data must be a Uint8Array"))?;
                let cursor = std::io::Cursor::new(bytes);
                let mut archive =
                    zip::ZipArchive::new(cursor).map_err(|e| js_err(format!("zip open: {e}")))?;
                let out = Object::new(cx.clone())?;
                for i in 0..archive.len() {
                    let mut file = archive
                        .by_index(i)
                        .map_err(|e| js_err(format!("zip entry {i}: {e}")))?;
                    if file.is_dir() {
                        continue;
                    }
                    let mut buf = Vec::with_capacity(file.size() as usize);
                    file.read_to_end(&mut buf)
                        .map_err(|e| js_err(format!("zip read {}: {e}", file.name())))?;
                    let name = file.name().to_string();
                    out.set(name, TypedArray::new_copy(cx.clone(), buf)?)?;
                }
                Ok(out.into_value())
            },
        )?;
        exports.export("read", read)?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn create_and_read_roundtrip() {
        let buf = std::io::Cursor::new(Vec::new());
        let mut w = zip::ZipWriter::new(buf);
        let opts = zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Deflated);
        w.start_file("a.txt", opts).unwrap();
        w.write_all(b"hello zip").unwrap();
        let cursor = w.finish().unwrap();
        let bytes = cursor.into_inner();

        let mut ar = zip::ZipArchive::new(std::io::Cursor::new(bytes)).unwrap();
        assert_eq!(ar.len(), 1);
        let mut f = ar.by_index(0).unwrap();
        let mut s = String::new();
        f.read_to_string(&mut s).unwrap();
        assert_eq!(s, "hello zip");
    }
}
