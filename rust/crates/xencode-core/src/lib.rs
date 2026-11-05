//! XenCode core — the Rust PC line agent runtime.
//!
//! Twin-compatible with the Kotlin `core` module: identical on-disk contracts
//! (config, DAG, sessions, checkpoints, events, XP ledger) so state written by
//! one line is readable by the other.

pub mod agent;
pub mod chat;
pub mod config;
pub mod error;
pub mod gitx;
pub mod goal;
pub mod hooks;
pub mod mcp;
pub mod permissions;
pub mod skills;
pub mod prompt;
pub mod provider;
pub mod state;
pub mod store;
pub mod telemetry;
pub mod tools;

pub use error::{Result, XeError};

/// Current millisecond timestamp (the unit every on-disk contract uses).
pub fn now_ms() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as i64)
        .unwrap_or(0)
}

/// Short random id (used for session / checkpoint / card ids).
pub fn short_id() -> String {
    uuid::Uuid::new_v4().simple().to_string()[..12].to_string()
}

/// Base-36 encoding of a non-negative integer (checkpoint ids are `c`+base36(ms)).
pub fn base36(mut n: i64) -> String {
    const DIGITS: &[u8] = b"0123456789abcdefghijklmnopqrstuvwxyz";
    if n <= 0 {
        return "0".into();
    }
    let mut buf = vec![];
    while n > 0 {
        buf.push(DIGITS[(n % 36) as usize]);
        n /= 36;
    }
    buf.reverse();
    String::from_utf8(buf).unwrap_or_default()
}
