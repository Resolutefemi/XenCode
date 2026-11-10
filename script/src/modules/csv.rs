//! `xen/csv` — parse/stringify CSV (header→object or array-of-array rows).

use rquickjs::function::Opt;
use rquickjs::module::{Declarations, Exports, ModuleDef};
use rquickjs::{Ctx, Function, Object, Result as JsResult, Value};

use crate::jsvalue::{from_json, to_json};

pub struct XenCsv;

#[derive(Debug, Clone)]
pub struct CsvOpts {
    pub header: bool,
    pub delimiter: char,
}

fn read_opts<'js>(cx: &Ctx<'js>, opts: Option<&Object<'js>>) -> JsResult<CsvOpts> {
    let mut out = CsvOpts {
        header: true,
        delimiter: ',',
    };
    let Some(obj) = opts else { return Ok(out) };
    if let Some(h) = obj.get::<_, Option<bool>>("header")? {
        out.header = h;
    }
    if let Some(d) = obj.get::<_, Option<String>>("delimiter")? {
        if let Some(c) = d.chars().next() {
            out.delimiter = c;
        }
    }
    let _ = cx;
    Ok(out)
}

impl ModuleDef for XenCsv {
    fn declare(decl: &Declarations) -> JsResult<()> {
        decl.declare("parse")?;
        decl.declare("stringify")?;
        Ok(())
    }

    fn evaluate<'js>(ctx: &Ctx<'js>, exports: &Exports<'js>) -> JsResult<()> {
        let parse = Function::new(
            ctx.clone(),
            move |cx: Ctx<'js>, source: String, opts: Opt<Object<'js>>| -> JsResult<Value<'js>> {
                let cfg = read_opts(&cx, opts.0.as_ref())?;
                let mut rdr = csv::ReaderBuilder::new()
                    .has_headers(cfg.header)
                    .delimiter(cfg.delimiter as u8)
                    .flexible(true)
                    .from_reader(source.as_bytes());

                let mut out = Vec::new();
                if cfg.header {
                    let headers = rdr
                        .headers()
                        .map_err(|e| crate::host::js_err(format!("csv parse: {e}")))?
                        .clone();
                    for row in rdr.records() {
                        let row =
                            row.map_err(|e| crate::host::js_err(format!("csv parse: {e}")))?;
                        let mut obj = serde_json::Map::new();
                        for (i, field) in row.iter().enumerate() {
                            let key = headers
                                .get(i)
                                .map(|s| s.to_string())
                                .unwrap_or_else(|| format!("col{i}"));
                            obj.insert(key, serde_json::Value::String(field.to_string()));
                        }
                        out.push(serde_json::Value::Object(obj));
                    }
                } else {
                    for row in rdr.records() {
                        let row =
                            row.map_err(|e| crate::host::js_err(format!("csv parse: {e}")))?;
                        out.push(serde_json::Value::Array(
                            row.iter()
                                .map(|f| serde_json::Value::String(f.to_string()))
                                .collect(),
                        ));
                    }
                }
                from_json(&cx, &serde_json::Value::Array(out))
            },
        )?;
        exports.export("parse", parse)?;

        let stringify = Function::new(
            ctx.clone(),
            move |cx: Ctx<'js>, rows: Value, opts: Opt<Object<'js>>| -> JsResult<String> {
                let cfg = read_opts(&cx, opts.0.as_ref())?;
                let json = to_json(&cx, &rows).map_err(crate::host::js_err)?;
                let serde_json::Value::Array(items) = json else {
                    return Err(crate::host::js_err("csv.stringify expects an array of rows"));
                };

                let mut wtr = csv::WriterBuilder::new()
                    .delimiter(cfg.delimiter as u8)
                    .flexible(true)
                    .from_writer(vec![]);

                // header from union of keys when objects
                let is_objects = items
                    .first()
                    .map(|r| matches!(r, serde_json::Value::Object(_)))
                    .unwrap_or(false);
                let mut headers: Vec<String> = Vec::new();
                if is_objects {
                    for item in &items {
                        if let serde_json::Value::Object(map) = item {
                            for k in map.keys() {
                                if !headers.contains(k) {
                                    headers.push(k.clone());
                                }
                            }
                        }
                    }
                }
                if cfg.header && is_objects {
                    wtr.write_record(&headers)
                        .map_err(|e| crate::host::js_err(format!("csv write: {e}")))?;
                }
                for item in &items {
                    match item {
                        serde_json::Value::Object(map) => {
                            let record: Vec<String> = headers
                                .iter()
                                .map(|h| json_cell(map.get(h)))
                                .collect();
                            wtr.write_record(&record)
                                .map_err(|e| crate::host::js_err(format!("csv write: {e}")))?;
                        }
                        serde_json::Value::Array(vals) => {
                            let record: Vec<String> = vals.iter().map(|v| json_cell(Some(v))).collect();
                            wtr.write_record(&record)
                                .map_err(|e| crate::host::js_err(format!("csv write: {e}")))?;
                        }
                        other => {
                            let record: Vec<String> = vec![json_cell(Some(other))];
                            wtr.write_record(&record)
                                .map_err(|e| crate::host::js_err(format!("csv write: {e}")))?;
                        }
                    }
                }
                let bytes = wtr
                    .into_inner()
                    .map_err(|e| crate::host::js_err(format!("csv write: {e}")))?;
                String::from_utf8(bytes).map_err(|e| crate::host::js_err(format!("csv utf8: {e}")))
            },
        )?;
        exports.export("stringify", stringify)?;
        Ok(())
    }
}

fn json_cell(v: Option<&serde_json::Value>) -> String {
    match v {
        None | Some(serde_json::Value::Null) => String::new(),
        Some(serde_json::Value::String(s)) => s.clone(),
        Some(other) => other.to_string(),
    }
}

#[cfg(test)]
#[allow(unused_imports)]
mod tests {
    use super::*;

    #[test]
    fn parse_with_headers() {
        let mut rdr = csv::ReaderBuilder::new()
            .has_headers(true)
            .from_reader(b"name,age\nada,36\n" as &[u8]);
        let headers = rdr.headers().unwrap().clone();
        let rows: Vec<Vec<String>> = rdr
            .records()
            .map(|r| r.unwrap().iter().map(|s| s.to_string()).collect())
            .collect();
        assert_eq!(headers.get(0).map(|s| s.as_ref()), Some("name"));
        assert_eq!(headers.get(1).map(|s| s.as_ref()), Some("age"));
        assert_eq!(rows, vec![vec!["ada".to_string(), "36".to_string()]]);
    }

    #[test]
    fn write_roundtrip() {
        let mut wtr = csv::WriterBuilder::new().from_writer(vec![]);
        wtr.write_record(&["a", "b"]).unwrap();
        wtr.write_record(&["1", "2"]).unwrap();
        let out = String::from_utf8(wtr.into_inner().unwrap()).unwrap();
        assert_eq!(out, "a,b\n1,2\n");
    }
}
