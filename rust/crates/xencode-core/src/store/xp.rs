//! XP ledger backed by the shared database — rows land in `xp_events` inside
//! `aicode_agent_db`, the same table the Android app writes through Room's
//! `XpEventDao` (identical kind/amount/label semantics). Summaries derive
//! with the shared math in [`crate::telemetry`], so a level means the same
//! thing on every front end (phone, desktop, CLI).
//!
//! [`DbXpLedger::import_legacy_json`] migrates the old per-machine file
//! ledger (`~/.xencode/pc-xp-ledger.json`) once, so switching to the DB
//! never loses a level or a streak.

use crate::store::SharedStore;
use crate::telemetry::{
    current_streak, daily_streak_bonus, epoch_day, level_for, next_level_xp, XpEvent, XpSink,
    XpSummary, MILLIS_PER_DAY, TURN_AWARD, GOAL_AWARD,
};
use std::path::Path;
use std::sync::Arc;

/// The shared store is a rusqlite `Connection` (Send, not Sync), so the
/// ledger guards it with a mutex; every access is a short query.
pub struct DbXpLedger {
    store: Arc<std::sync::Mutex<SharedStore>>,
    /// Session tag for analytics; the Android entity reserves empty strings
    /// for global events, which is what the PC line writes.
    session_id: String,
}

impl DbXpLedger {
    pub fn new(store: Arc<std::sync::Mutex<SharedStore>>) -> Self {
        Self { store, session_id: String::new() }
    }

    pub fn with_session(store: Arc<std::sync::Mutex<SharedStore>>, session_id: impl Into<String>) -> Self {
        Self { store, session_id: session_id.into() }
    }

    /// Opens the machine-default database (`~/.xencode/aicode_agent_db`) and
    /// imports the legacy JSON ledger once.
    pub fn open_default() -> Result<Self, crate::error::XeError> {
        let store = Arc::new(std::sync::Mutex::new(SharedStore::open(&SharedStore::default_db_path())?));
        let ledger = Self::new(store);
        let legacy = crate::config::xp_ledger_path();
        if legacy.is_file() {
            let _ = ledger.import_legacy_json(&legacy);
        }
        Ok(ledger)
    }

    pub fn count_all(&self) -> usize {
        self.store.lock().expect("shared store poisoned").xp_list_all().map(|v| v.len()).unwrap_or(0)
    }

    /// Imports the legacy JSON ledger when the table is still empty.
    /// Returns the number of imported rows (0 when nothing was imported).
    pub fn import_legacy_json(&self, file: &Path) -> usize {
        if !file.is_file() || self.count_all() > 0 {
            return 0;
        }
        let raw = match std::fs::read_to_string(file) {
            Ok(r) => r,
            Err(_) => return 0,
        };
        let events: Vec<XpEvent> = match serde_json::from_str(&raw) {
            Ok(v) => v,
            Err(_) => return 0,
        };
        let n = events.len();
        for e in events {
            let _ = self.insert(&e.kind, e.amount, &e.label, e.at);
        }
        n
    }

    fn insert(&self, kind: &str, amount: i64, label: &str, at: i64) -> Option<i64> {
        self.store
            .lock()
            .expect("shared store poisoned")
            .xp_insert(&crate::store::XpEventRow {
                id: 0,
                session_id: self.session_id.clone(),
                kind: kind.to_string(),
                amount,
                label: label.to_string(),
                created_at: at,
            })
            .ok()
    }

    /// First award of the day pays the streak bonus — the same rule as the
    /// file ledger (counted only when this insert was the day's first).
    fn maybe_streak_bonus(&self, now: i64) {
        let day_start = now - (now % MILLIS_PER_DAY);
        let today = self
            .load()
            .iter()
            .filter(|e| e.at >= day_start)
            .count();
        if today > 1 {
            return;
        }
        let days: std::collections::BTreeSet<i64> = self.load().iter().map(|e| epoch_day(e.at)).collect();
        let streak = current_streak(&days, epoch_day(now));
        let bonus = daily_streak_bonus(streak);
        if bonus > 0 {
            self.insert("DAILY_STREAK_BONUS", bonus, &format!("{streak}d streak kept alive"), now);
        }
    }
}

impl XpSink for DbXpLedger {
    fn load(&self) -> Vec<XpEvent> {
        self.store
            .lock()
            .expect("shared store poisoned")
            .xp_list_all()
            .unwrap_or_default()
            .into_iter()
            .map(|r| XpEvent { kind: r.kind, amount: r.amount, label: r.label, at: r.created_at })
            .collect()
    }

    fn award_turn(&self, label: &str, now: i64) {
        self.insert("TURN_COMPLETED", TURN_AWARD, label, now);
        self.maybe_streak_bonus(now);
    }

    fn award_goal(&self, goal_title: &str, now: i64) {
        self.insert("GOAL_COMPLETED", GOAL_AWARD, goal_title, now);
        self.maybe_streak_bonus(now);
    }

    fn summary(&self, now: i64) -> XpSummary {
        let all = self.load();
        let total: i64 = all.iter().map(|e| e.amount).sum();
        let level = level_for(total);
        let day_start = now - (now % MILLIS_PER_DAY);
        let days: std::collections::BTreeSet<i64> = all.iter().map(|e| epoch_day(e.at)).collect();
        XpSummary {
            total_xp: total,
            level,
            next_level_xp: next_level_xp(level),
            streak_days: current_streak(&days, epoch_day(now)),
            today_xp: all.iter().filter(|e| e.at >= day_start).map(|e| e.amount).sum(),
            recent: all.iter().rev().take(8).cloned().collect(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::telemetry::MILLIS_PER_DAY;

    fn mem() -> DbXpLedger {
        DbXpLedger::new(Arc::new(std::sync::Mutex::new(SharedStore::open_in_memory().unwrap())))
    }

    #[test]
    fn awards_match_the_shared_curve() {
        let xp = mem();
        let now = crate::now_ms();
        xp.award_turn("first turn", now);
        xp.award_turn("second turn", now + 1);
        let s = xp.summary(now + 2);
        assert_eq!(10, s.total_xp);
        assert_eq!(0, s.level); // below the first 100-XP threshold
        assert_eq!(100, s.next_level_xp);
        assert_eq!(2, s.recent.iter().filter(|e| e.kind == "TURN_COMPLETED").count());
        assert!(!s.recent.iter().any(|e| e.kind == "DAILY_STREAK_BONUS"));
    }

    #[test]
    fn goal_award_pays_streak_bonus_on_first_award_of_second_day() {
        let xp = mem();
        let now = crate::now_ms();
        let yesterday = now - MILLIS_PER_DAY;
        xp.award_turn("day 1", yesterday);
        xp.award_goal("ship it", now);
        let s = xp.summary(now);
        // 5 (turn) + 50 (goal) + 20 (2-day streak bonus)
        assert_eq!(75, s.total_xp);
        assert_eq!(1, s.recent.iter().filter(|e| e.kind == "DAILY_STREAK_BONUS").count());
        assert_eq!(2, s.streak_days);
    }

    #[test]
    fn legacy_json_imports_once() {
        let xp = mem();
        let tmp = tempfile::tempdir().unwrap();
        let f = tmp.path().join("pc-xp-ledger.json");
        std::fs::write(
            &f,
            r#"[{"kind":"TURN_COMPLETED","amount":5,"label":"old turn","at":1600000000000},
                {"kind":"GOAL_COMPLETED","amount":50,"label":"old goal","at":1600000000001}]"#,
        )
        .unwrap();
        assert_eq!(2, xp.import_legacy_json(&f));
        assert_eq!(55, xp.summary(crate::now_ms()).total_xp);
        // second import is a no-op
        assert_eq!(0, xp.import_legacy_json(&f));
        assert_eq!(55, xp.summary(crate::now_ms()).total_xp);
        // missing file: no-op
        assert_eq!(0, xp.import_legacy_json(&tmp.path().join("nope.json")));
    }

    #[test]
    fn rows_use_the_android_event_vocabulary() {
        let store = Arc::new(std::sync::Mutex::new(SharedStore::open_in_memory().unwrap()));
        let xp = DbXpLedger::with_session(store.clone(), "s9");
        xp.award_turn("hello", 1_600_000_000_000);
        let rows = store.lock().unwrap().xp_list_all().unwrap();
        assert_eq!(1, rows.len());
        assert_eq!("s9", rows[0].session_id);
        assert_eq!("TURN_COMPLETED", rows[0].kind);
        assert_eq!(5, rows[0].amount);
    }
}
