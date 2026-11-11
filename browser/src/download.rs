//! Managed Chromium download (Phase 6 — open decision #2, resolved).
//!
//! Discovery order is "system browser first, managed download fallback":
//! `XENCODE_BROWSER` → PATH → well-known installs → this managed install →
//! Playwright caches. When nothing is found and the user consents
//! (`--download` on every browser-driving command), we fetch an official
//! **Chrome for Testing** build — the same builds the CDP/snapshot layers
//! are verified against — from Google's CfT feed and unpack it under
//! `$XENCODE_TOOLS/chromium/<version>/` (`~/.xencode/tools/chromium` by
//! default), next to the other managed backends (jadx, Ghidra).
//!
//! Consent-gated and offline-testable like every orchestrated backend: the
//! fetch closure is injectable, so tests never touch the network.

use std::io::{Cursor, Read};
use std::path::{Path, PathBuf};

use anyhow::{anyhow, bail, Context, Result};

use crate::discover::{find_browser, BrowserCandidate};

/// Google's official Chrome-for-Testing feed — last-known-good build per
/// channel (Stable/Beta/Dev/Canary) with per-platform download URLs.
pub const FEED_URL: &str =
    "https://googlechromelabs.github.io/chrome-for-testing/last-known-good-versions-with-downloads.json";
/// Channel we pin managed installs to.
pub const CHANNEL: &str = "Stable";
/// Release-asset ceiling (a CfT chrome zip is ~150–180 MB).
pub const DOWNLOAD_CAP: u64 = 512 * 1024 * 1024;

// ---------------------------------------------------------------- layout --

/// Default install root: `$XENCODE_TOOLS/chromium` or `~/.xencode/tools/chromium`.
pub fn install_root() -> PathBuf {
    if let Ok(root) = std::env::var("XENCODE_TOOLS") {
        if !root.trim().is_empty() {
            return Path::new(&root).join("chromium");
        }
    }
    let home = std::env::var("HOME")
        .or_else(|_| std::env::var("USERPROFILE"))
        .unwrap_or_else(|_| ".".to_string());
    Path::new(&home).join(".xencode/tools/chromium")
}

/// CfT platform key for the running binary.
pub fn platform_key() -> &'static str {
    if cfg!(target_os = "linux") {
        "linux64"
    } else if cfg!(target_os = "macos") {
        if cfg!(target_arch = "aarch64") { "mac-arm64" } else { "mac-x64" }
    } else if cfg!(target_os = "windows") {
        if cfg!(target_arch = "x86") { "win32" } else { "win64" }
    } else {
        "linux64"
    }
}

/// Binary path inside an unpacked CfT build, relative to the version dir.
pub fn binary_rel_path(platform: &str) -> &'static str {
    match platform {
        "linux64" => "chrome-linux64/chrome",
        "mac-arm64" => {
            "chrome-mac-arm64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing"
        }
        "mac-x64" => {
            "chrome-mac-x64/Google Chrome for Testing.app/Contents/MacOS/Google Chrome for Testing"
        }
        "win64" => "chrome-win64/chrome.exe",
        "win32" => "chrome-win32/chrome.exe",
        _ => "chrome-linux64/chrome",
    }
}

/// `143.0.7238.0` → `[143, 0, 7238, 0]` for numeric ordering. `None` when
/// the name carries no digits (markers, junk).
pub fn version_key(s: &str) -> Option<Vec<u64>> {
    let core = s.split(['-', '+']).next()?;
    let parts: Vec<u64> = core.split('.').map(|p| p.parse::<u64>().ok()).collect::<Option<_>>()?;
    if parts.is_empty() {
        None
    } else {
        Some(parts)
    }
}

// ----------------------------------------------------------------- probe --

/// Managed builds under `root`, newest version first.
pub fn probe_in(root: &Path) -> Vec<BrowserCandidate> {
    let Ok(entries) = std::fs::read_dir(root) else {
        return vec![];
    };
    let rel = binary_rel_path(platform_key());
    let mut dirs: Vec<(Vec<u64>, PathBuf)> = entries
        .flatten()
        .filter(|e| e.path().is_dir())
        .filter_map(|e| {
            let name = e.file_name().into_string().ok()?;
            version_key(&name).map(|k| (k, e.path()))
        })
        .collect();
    dirs.sort_by(|a, b| b.0.cmp(&a.0));
    dirs.into_iter()
        .map(|(_, base)| base.join(rel))
        .filter(|bin| bin.is_file())
        .map(|path| BrowserCandidate { path, source: "managed-xencode" })
        .collect()
}

/// Managed candidates for the discovery scan (empty when never downloaded).
pub fn managed_candidates() -> Vec<BrowserCandidate> {
    probe_in(&install_root())
}

// ------------------------------------------------------------------ feed --

/// Parse the CfT last-known-good feed: (version, download url) for
/// `channel` × `platform`'s `chrome` artifact. Pure — tests feed it fixtures.
pub fn parse_feed(raw: &[u8], channel: &str, platform: &str) -> Result<(String, String)> {
    let v: serde_json::Value =
        serde_json::from_slice(raw).context("CfT feed is not valid JSON")?;
    let ch = &v["channels"][channel];
    if ch.is_null() {
        bail!("CfT feed has no `{channel}` channel");
    }
    let version = ch["version"]
        .as_str()
        .ok_or_else(|| anyhow!("CfT feed `{channel}` has no version"))?
        .to_string();
    let artifacts = ch["downloads"]["chrome"]
        .as_array()
        .ok_or_else(|| anyhow!("CfT feed `{channel}` has no chrome downloads"))?;
    let url = artifacts
        .iter()
        .filter_map(|a| {
            Some((a["platform"].as_str()?, a["url"].as_str()?))
        })
        .find(|(p, _)| *p == platform)
        .map(|(_, u)| u.to_string())
        .ok_or_else(|| {
            anyhow!("CfT feed `{channel}` has no chrome build for platform `{platform}`")
        })?;
    Ok((version, url))
}

// -------------------------------------------------------------- download --

/// Resolve a browser for launching: discovery first (system installs, then
/// any previous managed download), else — with consent — fetch the latest
/// Chrome-for-Testing Stable build into the managed root. `fetch` is
/// injectable for tests.
pub fn resolve(allow_download: bool, fetch: impl Fn(&str) -> Result<Vec<u8>>) -> Result<BrowserCandidate> {
    if let Some(c) = find_browser() {
        return Ok(c);
    }
    ensure_in(&install_root(), allow_download, fetch)
}

/// The post-discovery fallback against an explicit managed root: a previous
/// managed install wins without any network; otherwise consent gates the
/// download. Root is a parameter so tests never touch the real HOME.
pub fn ensure_in(
    root: &Path,
    allow_download: bool,
    fetch: impl Fn(&str) -> Result<Vec<u8>>,
) -> Result<BrowserCandidate> {
    if let Some(c) = probe_in(root).into_iter().next() {
        return Ok(c);
    }
    if !allow_download {
        bail!(
            "no Chrome/Chromium/Edge found — set XENCODE_BROWSER to a browser binary, \
             install Google Chrome, or re-run with `--download` to fetch an official \
             Chrome for Testing build (~170 MB, installed under {})",
            root.display()
        );
    }
    download_latest(root, fetch)
}

/// Download + unpack the latest CfT Stable chrome build under `root` and
/// return it as a candidate. Does not consult system discovery first.
pub fn download_latest(
    root: &Path,
    fetch: impl Fn(&str) -> Result<Vec<u8>>,
) -> Result<BrowserCandidate> {
    let platform = platform_key();
    let (version, url) = parse_feed(&fetch(FEED_URL)?, CHANNEL, platform)
        .context("resolve Chrome for Testing build")?;
    let dir = root.join(&version);
    let bin = dir.join(binary_rel_path(platform));
    if bin.is_file() {
        // Raced or pre-existing — done.
        return Ok(BrowserCandidate { path: bin, source: "managed-xencode" });
    }
    eprintln!("· downloading Chrome for Testing {version} ({platform}) …");
    let archive = fetch(&url).context(format!("download {url}"))?;
    extract_release(&archive, &dir).context("extract Chrome for Testing build")?;
    std::fs::write(dir.join(".xencode-installed"), format!("{version}\n{url}\n")).ok();
    if !bin.is_file() {
        bail!(
            "Chrome for Testing {version} extracted to {} but {} is missing",
            dir.display(),
            binary_rel_path(platform)
        );
    }
    Ok(BrowserCandidate { path: bin, source: "managed-xencode" })
}

/// Production fetcher: rustls-backed blocking GET (CfT feed + download CDN).
pub fn fetch_url(url: &str) -> Result<Vec<u8>> {
    let mut res = ureq::get(url)
        .header("User-Agent", "xencode-browser")
        .call()
        .map_err(|e| anyhow!("GET {url} failed: {e}"))?;
    let mut out = Vec::new();
    res.body_mut()
        .as_reader()
        .take(DOWNLOAD_CAP)
        .read_to_end(&mut out)
        .map_err(|e| anyhow!("reading {url} failed: {e}"))?;
    Ok(out)
}

/// Extract the CfT zip into `dir`, keeping unix exec bits on the binaries.
/// The archives carry one top-level `chrome-<platform>/` dir, which lands
/// exactly where [`binary_rel_path`] expects it.
pub fn extract_release(archive: &[u8], dir: &Path) -> Result<()> {
    let mut zip = zip::ZipArchive::new(Cursor::new(archive))
        .context("Chrome for Testing download is not a valid zip")?;
    std::fs::create_dir_all(dir)?;
    let mut exec_bits: Vec<(PathBuf, u32)> = Vec::new();
    for i in 0..zip.len() {
        let mut file = zip.by_index(i).context("zip member")?;
        let Some(rel) = file.enclosed_name().map(|p| p.to_path_buf()) else { continue };
        let target = dir.join(&rel);
        if file.is_dir() {
            std::fs::create_dir_all(&target)?;
            continue;
        }
        if let Some(parent) = target.parent() {
            std::fs::create_dir_all(parent)?;
        }
        let mut out = Vec::with_capacity(file.size() as usize);
        file.read_to_end(&mut out)?;
        let mode = file.unix_mode();
        std::fs::write(&target, &out)?;
        if let Some(m) = mode {
            if m & 0o111 != 0 {
                exec_bits.push((target, m & 0o777));
            }
        }
    }
    for (path, mode) in exec_bits {
        set_exec(&path, mode)?;
    }
    Ok(())
}

#[cfg(unix)]
fn set_exec(path: &Path, mode: u32) -> Result<()> {
    use std::os::unix::fs::PermissionsExt;
    std::fs::set_permissions(path, std::fs::Permissions::from_mode(mode))
        .with_context(|| format!("chmod {:o} {}", mode, path.display()))
}

#[cfg(not(unix))]
fn set_exec(_path: &Path, _mode: u32) -> Result<()> {
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn feed_json() -> serde_json::Value {
        serde_json::json!({
            "timestamp": "2026-11-10T00:00:00.000Z",
            "channels": {
                "Stable": {
                    "channel": "Stable",
                    "version": "143.0.7238.0",
                    "revision": "r1503",
                    "downloads": {
                        "chrome": [
                            {"platform": "linux64", "url": "https://example.com/143/linux64/chrome-linux64.zip"},
                            {"platform": "mac-arm64", "url": "https://example.com/143/mac-arm64/chrome-mac-arm64.zip"},
                            {"platform": "mac-x64", "url": "https://example.com/143/mac-x64/chrome-mac-x64.zip"},
                            {"platform": "win32", "url": "https://example.com/143/win32/chrome-win32.zip"},
                            {"platform": "win64", "url": "https://example.com/143/win64/chrome-win64.zip"}
                        ],
                        "chrome-headless-shell": [
                            {"platform": "linux64", "url": "https://example.com/143/linux64/chrome-headless-shell-linux64.zip"}
                        ]
                    }
                },
                "Beta": {
                    "channel": "Beta",
                    "version": "144.0.1.2",
                    "downloads": {
                        "chrome": [
                            {"platform": "linux64", "url": "https://example.com/144/linux64/chrome-linux64.zip"}
                        ]
                    }
                }
            }
        })
    }

    #[test]
    fn parse_feed_picks_channel_and_platform() {
        let raw = serde_json::to_vec(&feed_json()).unwrap();
        let (version, url) = parse_feed(&raw, "Stable", "linux64").unwrap();
        assert_eq!(version, "143.0.7238.0");
        assert_eq!(url, "https://example.com/143/linux64/chrome-linux64.zip");

        let (version, url) = parse_feed(&raw, "Beta", "linux64").unwrap();
        assert_eq!(version, "144.0.1.2");
        assert_eq!(url, "https://example.com/144/linux64/chrome-linux64.zip");

        let (_, url) = parse_feed(&raw, "Stable", "mac-arm64").unwrap();
        assert_eq!(url, "https://example.com/143/mac-arm64/chrome-mac-arm64.zip");
    }

    #[test]
    fn parse_feed_reports_missing_pieces() {
        let raw = serde_json::to_vec(&feed_json()).unwrap();
        let err = parse_feed(&raw, "Canary", "linux64").unwrap_err().to_string();
        assert!(err.contains("Canary"), "{err}");

        let err = parse_feed(&raw, "Stable", "riscv64").unwrap_err().to_string();
        assert!(err.contains("riscv64"), "{err}");

        let err = parse_feed(b"not json", "Stable", "linux64").unwrap_err().to_string();
        assert!(err.contains("not valid JSON"), "{err}");
    }

    #[test]
    fn version_keys_sort_numerically() {
        let mut v = vec!["99.0.9.9", "143.0.7238.0", "143.1.0.0", "junk", "141.0.1.1"];
        v.sort_by_key(|s| version_key(s));
        // junk carries no key and sorts first; versions compare numerically
        // (141 < 143, and 143.0 < 143.1) — not lexicographically
        // ("99" would otherwise beat "143").
        let nums: Vec<_> = v.iter().filter_map(|s| version_key(s)).collect();
        assert_eq!(
            nums,
            vec![
                vec![99, 0, 9, 9],
                vec![141, 0, 1, 1],
                vec![143, 0, 7238, 0],
                vec![143, 1, 0, 0]
            ]
        );
    }

    #[test]
    fn binary_paths_match_cft_layout() {
        assert_eq!(binary_rel_path("linux64"), "chrome-linux64/chrome");
        assert_eq!(binary_rel_path("win64"), "chrome-win64/chrome.exe");
        assert!(binary_rel_path("mac-arm64").contains(".app/Contents/MacOS/"));
    }

    #[test]
    fn probe_in_finds_newest_managed_build() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path();
        let rel = binary_rel_path(platform_key());
        for v in ["141.0.1.1", "143.0.7238.0"] {
            let bin = root.join(v).join(rel);
            std::fs::create_dir_all(bin.parent().unwrap()).unwrap();
            std::fs::write(&bin, "#!/bin/sh\n").unwrap();
        }
        let found = probe_in(root);
        assert_eq!(found.len(), 2);
        assert!(found[0].path.to_string_lossy().contains("143.0.7238.0"));
        assert_eq!(found[0].source, "managed-xencode");

        // Empty root → no candidates, no panic.
        let empty = tempfile::tempdir().unwrap();
        assert!(probe_in(empty.path()).is_empty());
    }

    fn write_test_zip() -> Vec<u8> {
        let mut buf = Cursor::new(Vec::new());
        {
            let mut w = zip::ZipWriter::new(&mut buf);
            let opts: zip::write::SimpleFileOptions = Default::default();
            w.add_directory("chrome-linux64", opts).unwrap();
            w.start_file("chrome-linux64/chrome", opts.unix_permissions(0o755)).unwrap();
            std::io::Write::write_all(&mut w, b"#!/bin/sh\necho chrome\n").unwrap();
            w.start_file("chrome-linux64/README", opts.unix_permissions(0o644)).unwrap();
            std::io::Write::write_all(&mut w, b"hello").unwrap();
            w.finish().unwrap();
        }
        buf.into_inner()
    }

    #[test]
    fn extract_release_keeps_exec_bits() {
        let dir = tempfile::tempdir().unwrap();
        extract_release(&write_test_zip(), dir.path()).unwrap();
        let bin = dir.path().join("chrome-linux64/chrome");
        assert!(bin.is_file());
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = std::fs::metadata(&bin).unwrap().permissions().mode();
            assert_eq!(mode & 0o777, 0o755);
            let readme = dir.path().join("chrome-linux64/README");
            let mode = std::fs::metadata(readme).unwrap().permissions().mode();
            assert_eq!(mode & 0o111, 0, "non-exec member must stay non-exec");
        }
    }

    #[test]
    fn download_into_installs_and_probes() {
        let root = tempfile::tempdir().unwrap();
        let zip = write_test_zip();
        let fetch = |url: &str| -> Result<Vec<u8>> {
            if url == FEED_URL {
                Ok(serde_json::to_vec(&feed_json()).unwrap())
            } else if url == "https://example.com/143/linux64/chrome-linux64.zip" {
                Ok(zip.clone())
            } else {
                bail!("unexpected fetch: {url}")
            }
        };
        let c = download_latest(root.path(), fetch).unwrap();
        assert_eq!(c.source, "managed-xencode");
        assert!(c.path.to_string_lossy().contains("143.0.7238.0"));
        assert!(c.path.is_file());
        assert!(root.path().join("143.0.7238.0/.xencode-installed").is_file());

        // Idempotent rerun: ensure_in() probes the managed root first, so a
        // repeat never touches the network.
        let again = ensure_in(root.path(), true, |_| bail!("no network")).unwrap();
        assert_eq!(again.path, c.path);
    }

    #[test]
    fn ensure_without_consent_names_the_fix() {
        let root = tempfile::tempdir().unwrap();
        let err = ensure_in(root.path(), false, |_| bail!("offline"))
            .unwrap_err()
            .to_string();
        assert!(err.contains("--download"), "{err}");
        assert!(err.to_string().contains("Chrome for Testing"), "{err}");
        assert!(err.contains(root.path().to_str().unwrap()), "{err}");
    }

    #[test]
    fn ensure_with_consent_downloads_via_injected_fetch() {
        let root = tempfile::tempdir().unwrap();
        let c = ensure_in(root.path(), true, |url| {
            if url == FEED_URL {
                Ok(serde_json::to_vec(&feed_json()).unwrap())
            } else {
                Ok(write_test_zip())
            }
        })
        .unwrap();
        assert_eq!(c.source, "managed-xencode");
        assert!(c.path.is_file());
    }

    // ------------------------------------------------------ live (opt-in) --
    // `cargo test -p xencode-browser --lib download -- --ignored`
    // Real feed + real ~170 MB CfT download + real launch check. Never runs
    // in CI; the injected-fetch tests above cover the same logic offline.

    #[test]
    #[ignore = "live network: downloads the real Chrome for Testing Stable build"]
    fn live_managed_download_end_to_end() {
        let root = tempfile::tempdir().unwrap();
        let c = download_latest(root.path(), fetch_url).unwrap();
        assert_eq!(c.source, "managed-xencode");
        assert!(c.path.is_file());
        let out = std::process::Command::new(&c.path).arg("--version").output().unwrap();
        assert!(out.status.success(), "chrome --version failed");
        let v = String::from_utf8_lossy(&out.stdout);
        assert!(v.contains("Google Chrome"), "{v}");
        eprintln!("· managed install ok: {} → {}", v.trim(), c.path.display());
    }
}
