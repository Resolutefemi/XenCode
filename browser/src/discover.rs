//! Find a Chromium-family browser binary.
//!
//! Priority: `XENCODE_BROWSER` env override → well-known install locations
//! (Chrome, Edge, Chromium, Brave on Linux/macOS/Windows) → `PATH` lookup →
//! Playwright browser caches (`~/.cache/ms-playwright/chromium-*`), which is
//! how headless fleets get a browser without touching the user's install.
//!
//! A managed download remains an explicit follow-up (plan.md, open decision
//! #2); every `Space` launch surfaces a clear message when nothing is found.

use std::path::{Path, PathBuf};

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BrowserCandidate {
    pub path: PathBuf,
    /// Human source of the candidate, e.g. `env:XENCODE_BROWSER`, `path`,
    /// `/opt/google/chrome`, `playwright-cache`.
    pub source: &'static str,
}

fn exists(p: &Path) -> bool {
    p.is_file()
}

/// All candidates we can find, best first. Cheap: only stats the filesystem.
pub fn find_all() -> Vec<BrowserCandidate> {
    let mut out: Vec<BrowserCandidate> = Vec::new();

    if let Ok(p) = std::env::var("XENCODE_BROWSER") {
        if !p.trim().is_empty() {
            let p = PathBuf::from(p);
            if exists(&p) {
                out.push(BrowserCandidate { path: p, source: "env:XENCODE_BROWSER" });
            }
        }
    }

    for name in [
        "google-chrome-stable",
        "google-chrome",
        "chromium",
        "chromium-browser",
        "microsoft-edge",
        "microsoft-edge-stable",
        "brave-browser",
    ] {
        if let Some(p) = which(name) {
            out.push(BrowserCandidate { path: p, source: "PATH" });
        }
    }

    for p in well_known_paths() {
        if exists(&p) {
            out.push(BrowserCandidate { path: p, source: "install-path" });
        }
    }

    for p in playwright_cache() {
        out.push(BrowserCandidate { path: p, source: "playwright-cache" });
    }

    out
}

/// The best usable browser, if any.
pub fn find_browser() -> Option<BrowserCandidate> {
    find_all().into_iter().next()
}

/// Minimal `which`: scan `PATH` for an executable file with this name.
fn which(name: &str) -> Option<PathBuf> {
    let path = std::env::var_os("PATH")?;
    for dir in std::env::split_paths(&path) {
        let p = dir.join(name);
        if exists(&p) {
            return Some(p);
        }
    }
    None
}

fn well_known_paths() -> Vec<PathBuf> {
    let mut v = Vec::new();
    if cfg!(target_os = "linux") {
        for p in [
            "/opt/google/chrome/chrome",
            "/usr/bin/google-chrome-stable",
            "/usr/bin/google-chrome",
            "/usr/bin/chromium",
            "/usr/bin/chromium-browser",
            "/snap/bin/chromium",
            "/opt/microsoft/msedge/msedge",
            "/usr/bin/microsoft-edge",
            "/usr/bin/microsoft-edge-stable",
            "/opt/brave.com/brave/brave-browser",
        ] {
            v.push(PathBuf::from(p));
        }
    } else if cfg!(target_os = "macos") {
        for p in [
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "/Applications/Chromium.app/Contents/MacOS/Chromium",
            "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
            "/Applications/Brave Browser.app/Contents/MacOS/Brave Browser",
        ] {
            v.push(PathBuf::from(p));
        }
        if let Some(home) = std::env::var_os("HOME") {
            let home = PathBuf::from(home);
            for rel in [
                "Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                "Applications/Chromium.app/Contents/MacOS/Chromium",
            ] {
                v.push(home.join(rel));
            }
        }
    } else if cfg!(target_os = "windows") {
        let pf = std::env::var("ProgramFiles").unwrap_or_else(|_| r"C:\Program Files".into());
        let pf86 = std::env::var("ProgramFiles(x86)").unwrap_or_else(|_| r"C:\Program Files (x86)".into());
        let local = std::env::var("LOCALAPPDATA").unwrap_or_default();
        for base in [pf, pf86, local] {
            if base.is_empty() {
                continue;
            }
            for rel in [
                r"Google\Chrome\Application\chrome.exe",
                r"Microsoft\Edge\Application\msedge.exe",
                r"Chromium\Application\chrome.exe",
            ] {
                v.push(PathBuf::from(&base).join(rel));
            }
        }
    }
    v
}

/// Playwright browser cache layout: `~/.cache/ms-playwright/chromium-<rev>/
/// chrome-linux/chrome` (older) or `chrome-linux64/chrome` (newer builds).
/// Also the headless shell (`chromium_headless_shell-*`), which is exactly
/// what clean-room headless Spaces want.
fn playwright_cache() -> Vec<PathBuf> {
    let mut out = Vec::new();
    let home = match std::env::var_os("HOME").or_else(|| std::env::var_os("USERPROFILE")) {
        Some(h) => PathBuf::from(h),
        None => return out,
    };
    let roots = [
        home.join(".cache/ms-playwright"),
        home.join("Library/Caches/ms-playwright"),
        home.join("AppData/Local/ms-playwright"),
    ];
    for root in roots {
        let Ok(entries) = std::fs::read_dir(&root) else { continue };
        let mut revs: Vec<(u64, PathBuf)> = Vec::new();
        for e in entries.flatten() {
            let name = e.file_name();
            let name = name.to_string_lossy();
            let (kind, rev) = if let Some(rest) = name.strip_prefix("chromium_headless_shell-") {
                ("chromium_headless_shell", rest)
            } else if let Some(rest) = name.strip_prefix("chromium-") {
                ("chromium", rest)
            } else {
                continue;
            };
            let rev_n: u64 = rev.parse().unwrap_or(0);
            let base = e.path();
            for sub in ["chrome-linux64", "chrome-linux", "chrome-win", "chrome-mac"] {
                let bin = match (kind, sub) {
                    ("chromium", "chrome-linux64") => base.join(sub).join("chrome"),
                    ("chromium", "chrome-linux") => base.join(sub).join("chrome"),
                    ("chromium", "chrome-win") => base.join(sub).join("chrome.exe"),
                    ("chromium", "chrome-mac") => base.join(sub).join("Chromium.app/Contents/MacOS/Chromium"),
                    ("chromium_headless_shell", "chrome-linux64") => base.join(sub).join("headless_shell"),
                    ("chromium_headless_shell", "chrome-linux") => base.join(sub).join("headless_shell"),
                    ("chromium_headless_shell", "chrome-win") => base.join(sub).join("headless_shell.exe"),
                    _ => continue,
                };
                if exists(&bin) {
                    revs.push((rev_n, bin));
                }
            }
        }
        // newest revision first
        revs.sort_by(|a, b| b.0.cmp(&a.0));
        out.extend(revs.into_iter().map(|(_, p)| p));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Mutex;

    // The env override is process-global; serialize the tests that touch it.
    static ENV_LOCK: Mutex<()> = Mutex::new(());

    #[test]
    fn env_override_wins() {
        let _g = ENV_LOCK.lock().unwrap();
        let dir = tempfile::tempdir().unwrap();
        let exe = dir.path().join("fake-chrome");
        std::fs::write(&exe, "#!/bin/sh\n").unwrap();
        std::env::set_var("XENCODE_BROWSER", &exe);
        let found = find_browser().unwrap();
        assert_eq!(found.path, exe);
        assert_eq!(found.source, "env:XENCODE_BROWSER");
        std::env::remove_var("XENCODE_BROWSER");
    }

    #[test]
    fn missing_env_override_is_skipped() {
        let _g = ENV_LOCK.lock().unwrap();
        std::env::set_var("XENCODE_BROWSER", "/nonexistent/chrome-binary-xyz");
        // must not panic and must not surface the bogus path
        if let Some(c) = find_browser() {
            assert_ne!(c.path, PathBuf::from("/nonexistent/chrome-binary-xyz"));
        }
        std::env::remove_var("XENCODE_BROWSER");
    }

    #[test]
    fn candidate_list_is_cheap_and_typed() {
        // Just runs the scan end-to-end; on any machine it must not panic.
        let all = find_all();
        for c in &all {
            assert!(c.path.is_absolute() || c.source == "PATH");
        }
    }
}
