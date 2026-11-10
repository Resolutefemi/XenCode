//! `xen/sqlite` — local SQLite via rusqlite (bundled, no install).
//!
//! `open(path)` → `{ exec(sql, params?), query(sql, params?), close() }`.
//! `query` returns rows as objects keyed by column name. Params are either
//! an array (positional `?1…`) or an object (`:name` / `$name` / `@name`).

use std::sync::{Arc, Mutex};

use rquickjs::function::Opt;
use rquickjs::module::{Declarations, Exports, ModuleDef};
use rquickjs::{Ctx, Function, Object, Result as JsResult, Value};
use rusqlite::types::Value as SqlValue;

use crate::jsvalue::{from_json, to_json};

pub struct XenSqlite;

impl ModuleDef for XenSqlite {
    fn declare(decl: &Declarations) -> JsResult<()> {
        decl.declare("open")?;
        Ok(())
    }

    fn evaluate<'js>(ctx: &Ctx<'js>, exports: &Exports<'js>) -> JsResult<()> {
        let open = Function::new(ctx.clone(), move |cx: Ctx<'js>, path: String| {
            open_db(&cx, &path)
        })?;
        exports.export("open", open)?;
        Ok(())
    }
}

fn open_db<'js>(cx: &Ctx<'js>, path: &str) -> JsResult<Object<'js>> {
    let conn = if path == ":memory:" {
        rusqlite::Connection::open_in_memory()
    } else {
        if let Some(parent) = std::path::Path::new(path).parent() {
            let _ = std::fs::create_dir_all(parent);
        }
        rusqlite::Connection::open(path)
    }
    .map_err(|e| crate::host::js_err(format!("sqlite open `{path}`: {e}")))?;
    conn.busy_timeout(std::time::Duration::from_secs(5))
        .map_err(|e| crate::host::js_err(format!("sqlite busy_timeout: {e}")))?;

    let db = Arc::new(Mutex::new(Some(conn)));

    // exec(sql, params?) → { changes }
    let db2 = db.clone();
    let exec = Function::new(cx.clone(), move |cx: Ctx<'js>, sql: String, params: Opt<Value<'js>>| {
        let db = db2.clone();
        let cx = cx.clone();
        let json = params_to_json(&cx, params.0)?;
        with_conn(&db, move |conn| {
            let affected = execute_sql(conn, &sql, json)?;
            let obj = Object::new(cx.clone())?;
            obj.set("changes", affected as f64)?;
            Ok(obj)
        })
    })?;

    // query(sql, params?) → rows: objects keyed by column name
    let db3 = db.clone();
    let query = Function::new(cx.clone(), move |cx: Ctx<'js>, sql: String, params: Opt<Value<'js>>| {
        let db = db3.clone();
        let cx = cx.clone();
        let json = params_to_json(&cx, params.0)?;
        let rows_json = with_conn(&db, move |conn| {
            let mut stmt = conn.prepare(&sql).map_err(sql_err(&sql))?;
            let names: Vec<String> = stmt
                .column_names()
                .into_iter()
                .map(|s| s.to_string())
                .collect();
            let mut rows = open_rows(&mut stmt, json)?;
            let mut rows_out: Vec<serde_json::Value> = Vec::new();
            while let Some(row) = rows.next().map_err(sql_err(&sql))? {
                let mut obj = serde_json::Map::new();
                for (i, name) in names.iter().enumerate() {
                    let v: SqlValue = row.get(i).map_err(sql_err(&sql))?;
                    obj.insert(name.clone(), sql_to_json(v));
                }
                rows_out.push(serde_json::Value::Object(obj));
            }
            Ok(rows_out)
        })?;
        from_json(&cx, &serde_json::Value::Array(rows_json))
    })?;

    let db4 = db.clone();
    let close = Function::new(cx.clone(), move || {
        if let Ok(mut slot) = db4.lock() {
            *slot = None;
        }
    })?;

    let handle = Object::new(cx.clone())?;
    handle.set("exec", exec)?;
    handle.set("query", query)?;
    handle.set("close", close)?;
    Ok(handle)
}

fn params_to_json<'js>(cx: &Ctx<'js>, params: Option<Value<'js>>) -> JsResult<serde_json::Value> {
    match params {
        Some(v) if !v.is_undefined() => to_json(cx, &v).map_err(crate::host::js_err),
        _ => Ok(serde_json::Value::Null),
    }
}

fn sql_err(sql: &str) -> impl Fn(rusqlite::Error) -> rquickjs::Error + '_ {
    move |e| {
        crate::host::js_err(format!(
            "sqlite `{}`: {e}",
            sql.lines().next().unwrap_or(sql)
        ))
    }
}

/// Run `f` against the connection on the current thread (holding the mutex).
fn with_conn<T>(
    db: &Arc<Mutex<Option<rusqlite::Connection>>>,
    f: impl FnOnce(&mut rusqlite::Connection) -> JsResult<T>,
) -> JsResult<T> {
    let mut slot = db
        .lock()
        .map_err(|_| crate::host::js_err("sqlite connection poisoned"))?;
    let conn = slot
        .as_mut()
        .ok_or_else(|| crate::host::js_err("sqlite connection closed"))?;
    f(conn)
}

/// Execute with no / positional / named params; returns affected rows.
fn execute_sql(conn: &mut rusqlite::Connection, sql: &str, params: serde_json::Value) -> JsResult<usize> {
    Ok(match params {
        serde_json::Value::Null => conn.execute(sql, rusqlite::params![]).map_err(sql_err(sql))?,
        serde_json::Value::Array(items) => {
            let bound: Vec<SqlValue> = items.iter().map(json_to_sql).collect();
            conn.execute(sql, rusqlite::params_from_iter(bound))
                .map_err(sql_err(sql))?
        }
        serde_json::Value::Object(map) => {
            let keys: Vec<String> = map.keys().map(|k| format!(":{k}")).collect();
            let named: Vec<(&str, SqlValue)> = keys
                .iter()
                .zip(map.values())
                .map(|(k, v)| (k.as_str(), json_to_sql(v)))
                .collect();
            conn.execute(sql, named.as_slice()).map_err(sql_err(sql))?
        }
        other => {
            return Err(crate::host::js_err(format!(
                "sqlite params must be null/array/object, got {other}"
            )))
        }
    })
}

fn open_rows<'a>(
    stmt: &'a mut rusqlite::Statement<'_>,
    params: serde_json::Value,
) -> JsResult<rusqlite::Rows<'a>> {
    Ok(match params {
        serde_json::Value::Null => stmt.query(rusqlite::params![]).map_err(sql_err("query"))?,
        serde_json::Value::Array(items) => {
            let bound: Vec<SqlValue> = items.iter().map(json_to_sql).collect();
            stmt.query(rusqlite::params_from_iter(bound))
                .map_err(sql_err("query"))?
        }
        serde_json::Value::Object(map) => {
            let keys: Vec<String> = map.keys().map(|k| format!(":{k}")).collect();
            let named: Vec<(&str, SqlValue)> = keys
                .iter()
                .zip(map.values())
                .map(|(k, v)| (k.as_str(), json_to_sql(v)))
                .collect();
            stmt.query(named.as_slice()).map_err(sql_err("query"))?
        }
        _ => return Err(crate::host::js_err("sqlite params must be null/array/object")),
    })
}

fn json_to_sql(v: &serde_json::Value) -> SqlValue {
    match v {
        serde_json::Value::Null => SqlValue::Null,
        serde_json::Value::Bool(b) => SqlValue::Integer(*b as i64),
        serde_json::Value::Number(n) => {
            if let Some(i) = n.as_i64() {
                SqlValue::Integer(i)
            } else {
                SqlValue::Real(n.as_f64().unwrap_or(0.0))
            }
        }
        serde_json::Value::String(s) => SqlValue::Text(s.clone()),
        other => SqlValue::Text(other.to_string()),
    }
}

fn sql_to_json(v: SqlValue) -> serde_json::Value {
    match v {
        SqlValue::Null => serde_json::Value::Null,
        SqlValue::Integer(i) => serde_json::Value::from(i),
        SqlValue::Real(f) => serde_json::Value::from(f),
        SqlValue::Text(s) => serde_json::Value::String(s),
        SqlValue::Blob(b) => serde_json::Value::String(crate::jsvalue::b64_encode(&b)),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn in_memory_roundtrip() {
        let mut conn = rusqlite::Connection::open_in_memory().unwrap();
        conn.execute_batch("CREATE TABLE t (id INTEGER PRIMARY KEY, name TEXT, score REAL);")
            .unwrap();
        let affected = execute_sql(
            &mut conn,
            "INSERT INTO t (name, score) VALUES (?1, ?2)",
            serde_json::json!(["ada", 9.5]),
        )
        .unwrap();
        assert_eq!(affected, 1);

        let mut stmt = conn.prepare("SELECT id, name, score FROM t WHERE name = ?1").unwrap();
        let names: Vec<String> = stmt.column_names().into_iter().map(|s| s.to_string()).collect();
        let mut rows = open_rows(&mut stmt, serde_json::json!(["ada"])).unwrap();
        while let Some(row) = rows.next().unwrap() {
            let name: String = row.get(1).unwrap();
            assert_eq!(name, "ada");
        }
        assert_eq!(names.len(), 3);
    }

    #[test]
    fn named_params() {
        let mut conn = rusqlite::Connection::open_in_memory().unwrap();
        conn.execute_batch("CREATE TABLE p (k TEXT, v INTEGER);").unwrap();
        let affected = execute_sql(
            &mut conn,
            "INSERT INTO p (k, v) VALUES (:k, :v)",
            serde_json::json!({"k": "x", "v": 7}),
        )
        .unwrap();
        assert_eq!(affected, 1);
        let mut stmt = conn.prepare("SELECT v FROM p WHERE k = :k").unwrap();
        let mut rows = open_rows(&mut stmt, serde_json::json!({"k": "x"})).unwrap();
        let row = rows.next().unwrap().unwrap();
        let v: i64 = row.get(0).unwrap();
        assert_eq!(v, 7);
    }

    #[test]
    fn sql_value_conversion() {
        assert_eq!(json_to_sql(&serde_json::json!(42)), SqlValue::Integer(42));
        assert_eq!(json_to_sql(&serde_json::json!(true)), SqlValue::Integer(1));
        assert_eq!(sql_to_json(SqlValue::Text("x".into())), serde_json::json!("x"));
    }
}
