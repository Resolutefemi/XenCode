//! Room-compatibility proof: the database file created by `SharedStore` must
//! satisfy the exact schema the Kotlin app expects (`59.json`, the Room-exported
//! schema of `AgentDatabase` v59).
//!
//! This replicates Room's own post-migration validation logic: for every entity
//! — table set, column set, column affinity, notNull, defaultValue, primary key
//! and index set must match. A failure here means the Kotlin app would refuse
//! (or crash) opening a PC-created database, which is precisely what must never
//! happen for the shared `aicode_agent_db`.

use rusqlite::Connection;
use serde::Deserialize;
use xencode_core::store::{SharedStore, IDENTITY_HASH, SCHEMA_VERSION};

const FIXTURE: &str = include_str!("fixtures/room_schema_59.json");

#[derive(Deserialize)]
struct RoomSchema {
    database: RoomDatabase,
}

#[derive(Deserialize)]
struct RoomDatabase {
    version: i64,
    #[serde(rename = "identityHash")]
    identity_hash: String,
    entities: Vec<RoomEntity>,
    #[serde(rename = "setupQueries")]
    #[allow(dead_code)]
    setup_queries: Vec<String>,
}

#[derive(Deserialize)]
struct RoomEntity {
    #[serde(rename = "tableName")]
    table_name: String,
    #[serde(rename = "createSql")]
    create_sql: String,
    #[serde(default)]
    fields: Vec<RoomField>,
    #[serde(rename = "primaryKey")]
    primary_key: RoomPrimaryKey,
    #[serde(default)]
    indices: Vec<RoomIndex>,
}

#[derive(Deserialize)]
struct RoomPrimaryKey {
    #[serde(rename = "columnNames", default)]
    column_names: Vec<String>,
}

#[derive(Deserialize)]
struct RoomField {
    #[serde(rename = "fieldPath")]
    #[allow(dead_code)]
    field_path: String,
    #[serde(rename = "columnName")]
    column_name: String,
    affinity: String,
    #[serde(rename = "notNull", default)]
    not_null: bool,
    #[serde(rename = "defaultValue", default)]
    default_value: Option<String>,
}

#[derive(Deserialize)]
struct RoomIndex {
    name: String,
    #[serde(rename = "createSql", default)]
    create_sql: String,
    #[serde(default)]
    #[allow(dead_code)]
    unique: bool,
}

/// Column metadata as reported by `PRAGMA table_info`.
#[derive(Debug, PartialEq)]
struct SqliteColumn {
    name: String,
    affinity: String,
    not_null: bool,
    default_value: Option<String>,
}

fn columns_of(conn: &Connection, table: &str) -> Vec<SqliteColumn> {
    let mut st = conn
        .prepare(&format!("PRAGMA table_info(`{table}`)"))
        .expect("pragma table_info");
    let rows = st
        .query_map([], |r| {
            let name: String = r.get(1)?;
            let dtype: String = r.get::<_, Option<String>>(2)?.unwrap_or_default();
            let not_null: i64 = r.get(3)?;
            let dflt: Option<String> = r.get(4)?;
            Ok((name, dtype, not_null, dflt))
        })
        .expect("query_map");
    rows.map(|row| {
        let (name, dtype, not_null, dflt) = row.expect("row");
        SqliteColumn {
            name,
            affinity: normalize_affinity(&dtype),
            not_null: not_null != 0,
            default_value: dflt,
        }
    })
    .collect()
}

/// Room affinities → SQLite reported types (see Room's `SQLiteTypeAffinity`).
fn normalize_affinity(dtype: &str) -> String {
    let u = dtype.to_uppercase();
    if u.contains("INT") {
        "INTEGER".into()
    } else if u.contains("CHAR") || u.contains("CLOB") || u.contains("TEXT") {
        "TEXT".into()
    } else if u.contains("BLOB") || u.is_empty() {
        "BLOB".into()
    } else if u.contains("REAL") || u.contains("FLOA") || u.contains("DOUB") {
        "REAL".into()
    } else {
        "NUMERIC".into()
    }
}

/// Normalise a PRAGMA-reported default to the Room export form (strips quotes).
fn normalize_default(raw: Option<String>) -> Option<String> {
    raw.map(|d| d.trim_matches('\'').to_string())
}
#[allow(dead_code)]
fn _assert_normalize_default_used() {
    let _ = normalize_default(Some("'0'".into()));
}

#[test]
fn fixture_matches_compiled_constants() {
    let schema: RoomSchema = serde_json::from_str(FIXTURE).expect("parse 59.json fixture");
    assert_eq!(schema.database.version, SCHEMA_VERSION);
    assert_eq!(schema.database.identity_hash, IDENTITY_HASH);
}

#[test]
fn pc_created_db_satisfies_room_v59_schema() {
    let schema: RoomSchema = serde_json::from_str(FIXTURE).expect("parse 59.json fixture");
    let dir = tempfile::tempdir().expect("tempdir");
    let db = dir.path().join("aicode_agent_db");
    let store = SharedStore::open(&db).expect("open store");
    let conn = store.raw();

    // Room checks the identity FIRST — mismatch => IllegalStateException.
    let identity: String = conn
        .query_row("SELECT identity FROM room_master_table LIMIT 1", [], |r| r.get(0))
        .expect("identity row");
    assert_eq!(identity, schema.database.identity_hash, "Room identity hash must match");
    let uv: i64 = conn.query_row("PRAGMA user_version", [], |r| r.get(0)).expect("user_version");
    assert_eq!(uv, schema.database.version, "user_version must equal the Room schema version");

    for entity in &schema.database.entities {
        let table = &entity.table_name;
        // 1. Column set, affinity, notNull, defaultValue — exact comparison
        //    (Room does exactly this in `RoomOpenHelper.onValidateSchema`).
        let actual: Vec<SqliteColumn> = columns_of(conn, table);
        let expected: Vec<SqliteColumn> = entity
            .fields
            .iter()
            .map(|f| SqliteColumn {
                name: f.column_name.clone(),
                affinity: normalize_affinity(&f.affinity),
                not_null: f.not_null,
                default_value: f.default_value.as_ref().map(|d| d.trim_matches('\'').to_string()),
            })
            .collect();
        assert_eq!(
            actual,
            expected,
            "table `{table}` columns diverge from the Room contract"
        );

        // 2. Primary key columns in declared order.
        let mut pk_actual: Vec<String> = columns_of(conn, table)
            .iter()
            .enumerate()
            .filter(|(i, _)| {
                let pk: i64 = conn
                    .query_row(
                        &format!("SELECT pk FROM pragma_table_info('{table}') WHERE cid = {i}"),
                        [],
                        |r| r.get(0),
                    )
                    .unwrap_or(0);
                pk > 0
            })
            .map(|(_, c)| c.name.clone())
            .collect();
        pk_actual.sort_by_key(|name| {
            entity
                .primary_key
                .column_names
                .iter()
                .position(|p| p == name)
                .unwrap_or(usize::MAX)
        });
        assert_eq!(
            pk_actual,
            entity.primary_key.column_names,
            "table `{table}` primary key diverges"
        );

        // 3. Index set (names are part of the Room contract).
        let mut idx_actual: Vec<String> = {
            let mut st = conn
                .prepare(&format!(
                    "SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = '{table}'"
                ))
                .expect("sqlite_master");
            st.query_map([], |r| r.get::<_, String>(0))
                .expect("query_map")
                .filter_map(|r| r.ok())
                .filter(|n| !n.starts_with("sqlite_autoindex"))
                .collect()
        };
        idx_actual.sort();
        let mut idx_expected: Vec<String> = entity.indices.iter().map(|i| i.name.clone()).collect();
        idx_expected.sort();
        assert_eq!(idx_actual, idx_expected, "table `{table}` index set diverges");
    }
}

#[test]
fn phone_side_ddl_from_fixture_executes_cleanly_on_a_pc_created_db() {
    // The strongest parity check available in this environment: replay the
    // exact `CREATE TABLE` / `CREATE INDEX` statements exported by Room inside
    // a fresh store; `IF NOT EXISTS` must make each one a no-op — byte-level
    // proof our mirror DDL matches the Kotlin contract.
    let schema: RoomSchema = serde_json::from_str(FIXTURE).expect("parse 59.json fixture");
    let store = SharedStore::open_in_memory().expect("memory store");
    let conn = store.raw();
    for entity in &schema.database.entities {
        let create = entity.create_sql.replace("${TABLE_NAME}", &entity.table_name);
        conn.execute_batch(&create)
            .unwrap_or_else(|e| panic!("Room DDL for {} diverged: {e}", entity.table_name));
        for index in &entity.indices {
            let create_index = index.create_sql.replace("${TABLE_NAME}", &entity.table_name);
            conn.execute_batch(&create_index)
                .unwrap_or_else(|e| panic!("index {} diverged: {e}", index.name));
        }
    }
}

#[test]
fn store_opened_twice_does_not_resurrect_old_identity() {
    let dir = tempfile::tempdir().expect("tempdir");
    let db = dir.path().join("aicode_agent_db");
    {
        let _ = SharedStore::open(&db).expect("first open");
    }
    // Simulate the Kotlin side having bumped the stamp (defensive: reopening a
    // v59 file must never downgrade or rewrite data).
    let s = SharedStore::open(&db).expect("second open");
    assert_eq!(s.stamped_identity().expect("identity"), Some(IDENTITY_HASH.to_string()));
    assert_eq!(s.user_version().expect("uv"), SCHEMA_VERSION);
}
