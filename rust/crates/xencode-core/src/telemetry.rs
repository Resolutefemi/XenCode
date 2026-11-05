//! Gamified telemetry: XP curve + streaks + compile-streak & spec-radar.
//! Numeric contract twin of `XpMath` (shared with the Android Room ledger):
//! level curve 100·L², TURN_AWARD 5, GOAL_AWARD 50, streak 10/day capped 50.

use crate::config::{xp_ledger_path, write_atomic};
use serde::{Deserialize, Serialize};
use std::collections::BTreeSet;
use std::path::PathBuf;

pub const LEVEL_CURVE_FACTOR: i64 = 100;
pub const TURN_AWARD: i64 = 5;
pub const GOAL_AWARD: i64 = 50;
pub const STREAK_BONUS_PER_DAY: i64 = 10;
pub const STREAK_BONUS_DAILY_CAP: i64 = 50;
pub const MILLIS_PER_DAY: i64 = 24 * 60 * 60 * 1000;

pub fn xp_for_level(level: i64) -> i64 {
    LEVEL_CURVE_FACTOR * level * level
}

pub fn level_for(total_xp: i64) -> i64 {
    let mut level = 0;
    while xp_for_level(level + 1) <= total_xp {
        level += 1;
    }
    level
}

pub fn next_level_xp(level: i64) -> i64 {
    xp_for_level(level + 1)
}

pub fn daily_streak_bonus(streak_days: i64) -> i64 {
    if streak_days <= 1 {
        0
    } else {
        (STREAK_BONUS_PER_DAY * streak_days).min(STREAK_BONUS_DAILY_CAP)
    }
}

pub fn epoch_day(ts: i64) -> i64 {
    ts.div_euclid(MILLIS_PER_DAY)
}

pub fn current_streak(day_epochs: &BTreeSet<i64>, today_epoch_day: i64) -> i64 {
    if day_epochs.is_empty() {
        return 0;
    }
    if !day_epochs.contains(&today_epoch_day) && !day_epochs.contains(&(today_epoch_day - 1)) {
        return 0;
    }
    let mut day = today_epoch_day;
    if !day_epochs.contains(&day) {
        day -= 1;
    }
    let mut streak = 0;
    while day_epochs.contains(&day) {
        streak += 1;
        day -= 1;
    }
    streak
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct XpEvent {
    pub kind: String, // TURN_COMPLETED | GOAL_COMPLETED | DAILY_STREAK_BONUS
    pub amount: i64,
    pub label: String,
    pub at: i64,
}

pub struct XpLedger {
    file: PathBuf,
}

impl Default for XpLedger {
    fn default() -> Self {
        Self::new(xp_ledger_path())
    }
}

impl XpLedger {
    pub fn new(file: PathBuf) -> Self {
        Self { file }
    }

    pub fn load(&self) -> Vec<XpEvent> {
        std::fs::read_to_string(&self.file)
            .ok()
            .and_then(|raw| serde_json::from_str(&raw).ok())
            .unwrap_or_default()
    }

    pub fn append(&self, event: XpEvent) -> Vec<XpEvent> {
        let mut all = self.load();
        all.push(event);
        if let Ok(json) = serde_json::to_string(&all) {
            let _ = write_atomic(&self.file, json.as_bytes());
        }
        all
    }

    pub fn award_turn(&self, label: &str, now: i64) {
        let events = self.append(XpEvent {
            kind: "TURN_COMPLETED".into(),
            amount: TURN_AWARD,
            label: label.to_string(),
            at: now,
        });
        self.maybe_streak_bonus(&events, now);
    }

    pub fn award_goal(&self, goal_title: &str, now: i64) {
        let events = self.append(XpEvent {
            kind: "GOAL_COMPLETED".into(),
            amount: GOAL_AWARD,
            label: goal_title.to_string(),
            at: now,
        });
        self.maybe_streak_bonus(&events, now);
    }

    fn maybe_streak_bonus(&self, events: &[XpEvent], now: i64) {
        let day_start = now - (now % MILLIS_PER_DAY);
        let today = events.iter().filter(|e| e.at >= day_start).count();
        if today > 1 {
            return; // not the first event of the day
        }
        let days: BTreeSet<i64> = events.iter().map(|e| epoch_day(e.at)).collect();
        let streak = current_streak(&days, epoch_day(now));
        let bonus = daily_streak_bonus(streak);
        if bonus > 0 {
            self.append(
                XpEvent {
                    kind: "DAILY_STREAK_BONUS".into(),
                    amount: bonus,
                    label: format!("{streak}d streak kept alive"),
                    at: now,
                },
            );
        }
    }

    pub fn summary(&self, now: i64) -> XpSummary {
        let all = self.load();
        let total: i64 = all.iter().map(|e| e.amount).sum();
        let level = level_for(total);
        let day_start = now - (now % MILLIS_PER_DAY);
        let days: BTreeSet<i64> = all.iter().map(|e| epoch_day(e.at)).collect();
        XpSummary {
            total_xp: total,
            level,
            next_level_xp: next_level_xp(level),
            streak_days: current_streak(&days, epoch_day(now)),
            today_xp: all.iter().filter(|e| e.at >= day_start).map(|e| e.amount).sum(),
            recent: all.iter().rev().take(8).cloned().collect(),
        }
    }

    pub fn render_summary(&self, now: i64) -> String {
        let s = self.summary(now);
        let mut out = format!(
            "Level {} · {} XP (next at {}) · {}d streak · today {} XP",
            s.level, s.total_xp, s.next_level_xp, s.streak_days, s.today_xp
        );
        for e in &s.recent {
            out.push_str(&format!("\n  {} {} {} {}", crate::state::sessions::fmt_ts(e.at), e.kind, e.amount, e.label));
        }
        out
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct XpSummary {
    pub total_xp: i64,
    pub level: i64,
    pub next_level_xp: i64,
    pub streak_days: i64,
    pub today_xp: i64,
    pub recent: Vec<XpEvent>,
}

/// Spec-radar / compile-streak telemetry — tracks verification health.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct Telemetry {
    #[serde(rename = "compileStreak")]
    pub compile_streak: u32,
    #[serde(rename = "bestCompileStreak")]
    pub best_compile_streak: u32,
    #[serde(rename = "testsPassed")]
    pub tests_passed: u64,
    #[serde(rename = "testsFailed")]
    pub tests_failed: u64,
    #[serde(rename = "specRadar", default)]
    pub spec_radar: std::collections::BTreeMap<String, TestVerdict>,
    #[serde(rename = "updatedAt", default)]
    pub updated_at: i64,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq)]
pub enum TestVerdict {
    Green,
    Red,
}

impl Telemetry {
    pub fn path(workspace_root: &std::path::Path) -> PathBuf {
        workspace_root.join(".xencode").join("telemetry.json")
    }

    pub fn load(workspace_root: &std::path::Path) -> Self {
        std::fs::read_to_string(Self::path(workspace_root))
            .ok()
            .and_then(|raw| serde_json::from_str(&raw).ok())
            .unwrap_or_default()
    }

    pub fn save(&self, workspace_root: &std::path::Path) -> Result<(), String> {
        let mut copy = self.clone();
        copy.updated_at = crate::now_ms();
        let json = serde_json::to_string_pretty(&copy).map_err(|e| e.to_string())?;
        write_atomic(&Self::path(workspace_root), json.as_bytes()).map_err(|e| e.to_string())
    }

    pub fn record_test(&mut self, id: &str, passed: bool) {
        let verdict = if passed { TestVerdict::Green } else { TestVerdict::Red };
        match self.spec_radar.get(id) {
            Some(TestVerdict::Green) if passed => {}
            Some(TestVerdict::Red) if !passed => {}
            _ => match verdict {
                TestVerdict::Green => self.tests_passed += 1,
                TestVerdict::Red => self.tests_failed += 1,
            },
        }
        self.spec_radar.insert(id.to_string(), verdict);
        if passed {
            self.compile_streak += 1;
            if self.compile_streak > self.best_compile_streak {
                self.best_compile_streak = self.compile_streak;
            }
        } else {
            self.compile_streak = 0;
        }
    }

    pub fn render_radar(&self) -> String {
        let total = self.spec_radar.len();
        let green = self.spec_radar.values().filter(|v| **v == TestVerdict::Green).count();
        let mut out = format!(
            "compile streak: {} (best {}) · radar: {green}/{total} GREEN · tests ↑{} ✗{}",
            self.compile_streak, self.best_compile_streak, self.tests_passed, self.tests_failed
        );
        let mut reds: Vec<_> = self.spec_radar.iter().filter(|(_, v)| **v == TestVerdict::Red).collect();
        reds.sort_by_key(|(k, _)| (*k).clone());
        for (id, _) in reds.iter().take(10) {
            out.push_str(&format!("\n  RED {id}"));
        }
        out
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn xp_curve_matches_kotlin() {
        assert_eq!(xp_for_level(0), 0);
        assert_eq!(xp_for_level(1), 100);
        assert_eq!(xp_for_level(2), 400);
        assert_eq!(level_for(99), 0);
        assert_eq!(level_for(100), 1);
        assert_eq!(level_for(500), 2);
        assert_eq!(next_level_xp(1), 400);
        assert_eq!(daily_streak_bonus(1), 0);
        assert_eq!(daily_streak_bonus(3), 30);
        assert_eq!(daily_streak_bonus(9), 50); // capped
    }

    #[test]
    fn streak_math() {
        let today = 20_000;
        let days: BTreeSet<i64> = [19_998, 19_999, 20_000].into();
        assert_eq!(current_streak(&days, today), 3);
        let gapped: BTreeSet<i64> = [19_996, 20_000].into();
        assert_eq!(current_streak(&gapped, today), 1);
        assert_eq!(current_streak(&BTreeSet::new(), today), 0);
    }

    #[test]
    fn ledger_awards_and_streak_bonus_once() {
        let dir = tempfile::tempdir().unwrap();
        let ledger = XpLedger::new(dir.path().join("ledger.json"));
        let now = 100 * MILLIS_PER_DAY + 5_000;
        ledger.award_turn("t1", now);
        ledger.award_turn("t2", now + 1_000);
        // same day, so no streak bonus yet; next day one award triggers bonus
        ledger.award_turn("t3", now + MILLIS_PER_DAY);
        let all = ledger.load();
        assert_eq!(all.iter().filter(|e| e.kind == "TURN_COMPLETED").count(), 3);
        assert_eq!(all.iter().filter(|e| e.kind == "DAILY_STREAK_BONUS").count(), 1);
        let s = ledger.summary(now + MILLIS_PER_DAY + 1_000);
        assert_eq!(s.total_xp, 15 + 20); // 3 turns * 5 + 20 bonus
    }

    #[test]
    fn telemetry_radar() {
        let mut t = Telemetry::default();
        t.record_test("s1", true);
        t.record_test("s2", true);
        t.record_test("s3", false);
        assert_eq!(t.compile_streak, 0);
        t.record_test("s3", true);
        assert_eq!(t.compile_streak, 1);
        let r = t.render_radar();
        assert!(r.contains("3/3 GREEN"), "{r}");
    }
}
