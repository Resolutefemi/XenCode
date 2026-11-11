//! jadx orchestration (Phase 7.2) — the first managed RE backend.
//!
//! jadx (github.com/skylot/jadx) decompiles APKs to readable Java. It ships
//! as a Java app, so XenCode treats it like Ghidra/ILSpy/RetDec per the plan:
//! an *orchestrated backend* — detected on PATH, else downloaded on demand
//! into `~/.xencode/tools/jadx/<version>/` (explicit opt-in only), then run
//! headless (`bin/jadx -d <out> <apk>`) and its output inventoried.
//!
//! No keys, no circumvention: this only reads decompiler output the tool
//! itself produces from an APK the user already owns.

use std::io::{Cursor, Read};
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::Instant;

use anyhow::{anyhow, bail, Context, Result};
use serde::Serialize;

/// Install root override (falls back to `~/.xencode/tools/jadx`).
pub const ENV_INSTALL_ROOT: &str = "XENCODE_TOOLS";
/// Java binary override (jadx needs a JRE 11+ on PATH).
pub const ENV_JAVA: &str = "XENCODE_JAVA";
/// GitHub release lookup is pinned to skylot/jadx.
pub const JADX_RELEASES_URL: &str = "https://api.github.com/repos/skylot/jadx/releases/latest";
/// Human releases page (redirect scrape fallback — not API-rate-limited).
pub const JADX_LATEST_URL: &str = "https://github.com/skylot/jadx/releases/latest";

/// Where jadx could/could not be found.
#[derive(Debug, Clone, Serialize)]
pub struct JadxStatus {
    pub installed: bool,
    /// Version string reported by `jadx --version` (e.g. "1.5.2").
    pub version: Option<String>,
    /// Binary path used (PATH hit or managed install).
    pub path: Option<String>,
    /// Whether this came from the managed install dir.
    pub managed: bool,
    /// Java runtime detected for the run (name + `java -version` first line).
    pub java: Option<String>,
    pub java_version: Option<String>,
    /// Present when probe/ensure failed — the CLI prints it verbatim.
    pub problem: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct JadxRun {
    pub jadx_version: String,
    pub apk: String,
    pub out_dir: String,
    pub args: Vec<String>,
    pub exit_code: i32,
    pub elapsed_ms: u64,
    /// .java files produced (relative paths, capped — see [`JadxRun::max_files`]).
    pub java_files: Vec<String>,
    pub total_java_files: u64,
    pub resource_files: u64,
    /// Tail of jadx stderr (deobfuscation notes, OOM warnings, errors).
    pub warnings: Vec<String>,
}

#[derive(Debug, Clone, Copy)]
pub struct JadxOpts {
    /// `--no-res` — skip resource decompilation (faster, code only).
    pub no_res: bool,
    /// `--deobf` — rename obfuscated classes/methods.
    pub deobf: bool,
    /// `-j` worker threads (0 = jadx default).
    pub threads: u32,
    /// Cap on the .java file listing in the report (total stays exact).
    pub max_files: usize,
}

impl Default for JadxOpts {
    fn default() -> Self {
        JadxOpts { no_res: false, deobf: false, threads: 0, max_files: 512 }
    }
}

impl JadxRun {
    /// Default listing cap shared with the CLI/`--json` consumers.
    pub const DEFAULT_MAX_FILES: usize = 512;
}

// ----------------------------------------------------------------- probe --

/// Default install root: `$XENCODE_TOOLS/jadx` or `~/.xencode/tools/jadx`.
pub fn install_root() -> PathBuf {
    if let Ok(root) = std::env::var(ENV_INSTALL_ROOT) {
        if !root.trim().is_empty() {
            return Path::new(&root).join("jadx");
        }
    }
    let home = std::env::var("HOME")
        .or_else(|_| std::env::var("USERPROFILE"))
        .unwrap_or_else(|_| ".".to_string());
    Path::new(&home).join(".xencode/tools/jadx")
}

/// Locate jadx: managed install first, then PATH. Reads version via
/// `--version` (never spawns a decompile).
pub fn probe() -> JadxStatus {
    probe_in(&install_root())
}

pub fn probe_in(install_root: &Path) -> JadxStatus {
    // Managed layout: <root>/<version>/bin/jadx — newest wins.
    let managed = best_managed(install_root);
    let java = detect_java();

    for candidate in managed.iter() {
        let bin = candidate.join("bin").join(bin_name());
        if bin.exists() {
            let version = read_version(&bin);
            return JadxStatus {
                installed: true,
                version,
                path: Some(bin.display().to_string()),
                managed: true,
                java: java.as_ref().map(|(n, _)| n.clone()),
                java_version: java.as_ref().map(|(_, v)| v.clone()),
                problem: None,
            };
        }
    }

    // PATH.
    if let Some(on_path) = which(bin_name()) {
        let version = read_version(&on_path);
        return JadxStatus {
            installed: true,
            version,
            path: Some(on_path.display().to_string()),
            managed: false,
            java: java.as_ref().map(|(n, _)| n.clone()),
            java_version: java.as_ref().map(|(_, v)| v.clone()),
            problem: None,
        };
    }

    JadxStatus {
        installed: false,
        version: None,
        path: None,
        managed: false,
        java: java.as_ref().map(|(n, _)| n.clone()),
        java_version: java.as_ref().map(|(_, v)| v.clone()),
        problem: Some(format!(
            "jadx not found — managed installs live in {}, or put `jadx` on PATH; \
             `xencode re decompile <APK> --download` fetches the latest release (~40 MB)",
            install_root.display()
        )),
    }
}

fn bin_name() -> &'static str {
    if cfg!(windows) { "jadx.bat" } else { "jadx" }
}

fn best_managed(root: &Path) -> Vec<PathBuf> {
    let Ok(entries) = std::fs::read_dir(root) else { return vec![] };
    let mut dirs: Vec<(String, PathBuf)> = entries
        .flatten()
        .filter(|e| e.path().is_dir())
        .filter_map(|e| {
            let name = e.file_name().into_string().ok()?;
            semver_like(&name).map(|_| (name, e.path()))
        })
        .collect();
    dirs.sort_by(|a, b| b.0.cmp(&a.0));
    dirs.into_iter().map(|(_, p)| p).collect()
}

/// Loose semver check for managed dir names ("1.5.2", "1.5.2-rc1").
fn semver_like(s: &str) -> Option<()> {
    let core = s.split(['-', '+']).next()?;
    let mut dots = 0;
    for c in core.chars() {
        match c {
            '0'..='9' | '.' => {
                if c == '.' {
                    dots += 1;
                }
            }
            _ => return None,
        }
    }
    if dots >= 1 { Some(()) } else { None }
}

fn which(program: &str) -> Option<PathBuf> {
    let path = std::env::var("PATH").ok()?;
    let ext = if cfg!(windows) { ".exe" } else { "" };
    for dir in path.split(std::path::MAIN_SEPARATOR_STR).filter(|d| !d.is_empty()) {
        let candidate = Path::new(dir).join(program);
        if candidate.is_file() {
            return Some(candidate);
        }
        let with_ext = Path::new(dir).join(format!("{program}{ext}"));
        if with_ext.is_file() {
            return Some(with_ext);
        }
    }
    None
}

fn detect_java() -> Option<(String, String)> {
    let name = std::env::var(ENV_JAVA).unwrap_or_else(|_| "java".to_string());
    let bin = which(&name)?;
    let out = Command::new(&bin)
        .arg("-version")
        .output()
        .ok()?;
    let first = String::from_utf8_lossy(&out.stderr)
        .lines()
        .next()
        .unwrap_or_default()
        .trim()
        .to_string();
    let version = if out.status.success() || !first.is_empty() {
        Some(first.clone())
    } else {
        None
    };
    Some((bin.display().to_string(), version.unwrap_or_default()))
}

/// Run `<bin> --version` with a short timeout; jadx prints `1.5.2`-ish.
fn read_version(bin: &Path) -> Option<String> {
    let out = Command::new(bin)
        .arg("--version")
        .output()
        .ok()?;
    let text = format!(
        "{}{}",
        String::from_utf8_lossy(&out.stdout),
        String::from_utf8_lossy(&out.stderr)
    );
    text.lines()
        .find(|l| !l.trim().is_empty())
        .map(|l| l.trim().to_string())
}

// ---------------------------------------------------------------- ensure --

/// Make sure jadx exists; download the latest release into the install root
/// when `allow_download` is set. `fetch` is injectable for tests.
pub fn ensure(
    install_root: &Path,
    allow_download: bool,
    fetch: impl Fn(&str) -> Result<Vec<u8>>,
) -> Result<JadxStatus> {
    let status = probe_in(install_root);
    if status.installed {
        return Ok(status);
    }
    if !allow_download {
        return Err(anyhow!(
            "jadx is not installed. Re-run with `--download` to fetch the latest release \
             from GitHub (~40 MB, installed under {}), or install jadx / put it on PATH.",
            install_root.display()
        ));
    }
    let (version, url) = latest_release_asset(&fetch)?;
    let dir = install_root.join(&version);
    if dir.join("bin").join(bin_name()).exists() {
        // Raced or pre-existing — probe again and move on.
        return Ok(probe_in(install_root));
    }
    let archive = fetch(&url).context(format!("download {url}"))?;
    extract_release(&archive, &dir).context("extract jadx release")?;
    std::fs::write(dir.join(".xencode-installed"), format!("{version}\n{url}\n"))
        .ok();
    let status = probe_in(install_root);
    if !status.installed {
        bail!("jadx {version} extracted to {} but the binary is missing", dir.display());
    }
    Ok(status)
}

/// Query the latest skylot/jadx release and pick the `jadx-<semver>.zip`
/// asset. Returns (version, download url). The GitHub API has a 60 req/hour
/// unauthenticated cap — when it answers 403/429 we fall back to following
/// the `releases/latest` redirect (not API-rate-limited) and reconstruct the
/// asset URL from the tag.
pub fn latest_release_asset(fetch: &impl Fn(&str) -> Result<Vec<u8>>) -> Result<(String, String)> {
    match fetch(JADX_RELEASES_URL) {
        Ok(raw) => match parse_release_json(&raw) {
            Ok(found) => Ok(found),
            Err(api_err) => scrape_latest_tag().map_err(|scrape_err| {
                anyhow!("GitHub API: {api_err}; redirect scrape: {scrape_err}")
            }),
        },
        Err(_) => scrape_latest_tag(),
    }
}

/// Pure release-JSON parser: tag_name + the CLI zip asset.
pub fn parse_release_json(raw: &[u8]) -> Result<(String, String)> {
    let v: serde_json::Value =
        serde_json::from_slice(raw).context("jadx release JSON is not valid JSON")?;
    let version = v["tag_name"]
        .as_str()
        .ok_or_else(|| anyhow!("release JSON has no tag_name"))?
        .trim_start_matches('v')
        .to_string();
    let assets = v["assets"]
        .as_array()
        .ok_or_else(|| anyhow!("release JSON has no assets"))?;
    let name_and_url = |a: &serde_json::Value| -> Option<(String, String)> {
        Some((a["name"].as_str()?.to_string(), a["browser_download_url"].as_str()?.to_string()))
    };
    let exact = assets
        .iter()
        .filter_map(name_and_url)
        .find(|(name, _)| *name == format!("jadx-{version}.zip") || *name == format!("jadx-{version}-all.zip"));
    let asset = exact.or_else(|| {
        assets
            .iter()
            .filter_map(name_and_url)
            .find(|(name, _)| name.starts_with("jadx-") && name.ends_with(".zip") && !name.contains("gui"))
    });
    Ok((version, asset.ok_or_else(|| anyhow!("latest jadx release has no jadx-*.zip asset"))?.1))
}

/// Redirect-scrape fallback: follow `github.com/skylot/jadx/releases/latest`
/// to the tag page and rebuild the asset URL (no API quota involved).
pub fn scrape_latest_tag() -> Result<(String, String)> {
    use ureq::ResponseExt;
    let agent = ureq::Agent::config_builder()
        .http_status_as_error(false)
        .build()
        .new_agent();
    let res = agent
        .get(JADX_LATEST_URL)
        .header("User-Agent", "xencode-re")
        .call()
        .map_err(|e| anyhow!("GET {JADX_LATEST_URL} failed: {e}"))?;
    if res.status() != 200 {
        bail!("{} answered {}", JADX_LATEST_URL, res.status());
    }
    let uri = res.get_uri().to_string();
    let tag = uri
        .trim_end_matches('/')
        .rsplit('/')
        .next()
        .unwrap_or_default()
        .trim_start_matches('v')
        .to_string();
    if !tag.chars().next().is_some_and(|c| c.is_ascii_digit()) {
        bail!("could not resolve the latest jadx tag from redirect to {uri}");
    }
    let url = format!("https://github.com/skylot/jadx/releases/download/v{tag}/jadx-{tag}.zip");
    Ok((tag, url))
}

/// Extract the release zip into `dir`, keeping unix exec bits on `bin/*`.
pub fn extract_release(archive: &[u8], dir: &Path) -> Result<()> {
    let mut zip = zip::ZipArchive::new(Cursor::new(archive))
        .context("jadx release download is not a valid zip")?;
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

/// Production fetcher: rustls-backed blocking GET (GitHub API + release CDN).
pub fn fetch_url(url: &str) -> Result<Vec<u8>> {
    let mut res = ureq::get(url)
        .header("User-Agent", "xencode-re")
        .header("Accept", "application/vnd.github+json")
        .call()
        .map_err(|e| anyhow!("GET {url} failed: {e}"))?;
    // ureq 3: read the whole body with a sane cap (100 MB release ceiling).
    let mut out = Vec::new();
    let reader = res.body_mut().as_reader();
    reader
        .take(100 * 1024 * 1024)
        .read_to_end(&mut out)
        .map_err(|e| anyhow!("reading {url} failed: {e}"))?;
    Ok(out)
}

// ------------------------------------------------------------------- run --

/// Run jadx headless over `apk`, write into `out_dir`, inventory the output.
/// The status must carry an installed `path`; Java presence is required.
pub fn run(apk: &Path, out_dir: &Path, opts: JadxOpts, status: &JadxStatus) -> Result<JadxRun> {
    let Some(bin) = status.path.as_ref() else {
        bail!("jadx is not installed — run ensure()/probe() first");
    };
    if !apk.is_file() {
        bail!("{} is not a file", apk.display());
    }
    let java_hint = match (&status.java, &status.java_version) {
        (None, _) => {
            bail!(
                "jadx needs a Java 11+ runtime — install a JRE or set {ENV_JAVA}; \
                 no `java` was found on PATH"
            )
        }
        (Some(j), v) => {
            let v = v.clone().unwrap_or_default();
            if let Some(major) = java_major(&v) {
                if major < 11 {
                    bail!("jadx needs Java 11+; {j} reports {v}");
                }
            }
            v
        }
    };
    let _ = java_hint;

    std::fs::create_dir_all(out_dir)?;
    let mut args: Vec<String> = vec![
        "-d".to_string(),
        out_dir.display().to_string(),
    ];
    if opts.no_res {
        args.push("--no-res".to_string());
    }
    if opts.deobf {
        args.push("--deobf".to_string());
    }
    if opts.threads > 0 {
        args.push("-j".to_string());
        args.push(opts.threads.to_string());
    }
    args.push(apk.display().to_string());

    let started = Instant::now();
    let output = Command::new(bin)
        .args(&args)
        .stdin(std::process::Stdio::null())
        .output()
        .map_err(|e| anyhow!("spawn `{}` failed: {e}", bin))?;
    let elapsed_ms = started.elapsed().as_millis() as u64;

    let stderr_text = String::from_utf8_lossy(&output.stderr).into_owned();
    let warnings = warn_lines(&stderr_text);

    let inventory = scan_output(out_dir, opts.max_files);

    Ok(JadxRun {
        jadx_version: status.version.clone().unwrap_or_default(),
        apk: apk.display().to_string(),
        out_dir: out_dir.display().to_string(),
        args,
        exit_code: output.status.code().unwrap_or(-1),
        elapsed_ms,
        java_files: inventory.java_files,
        total_java_files: inventory.total_java,
        resource_files: inventory.resources,
        warnings,
    })
}

fn java_major(version_line: &str) -> Option<u32> {
    // "openjdk version \"21.0.1\" …" / "java version \"1.8.0_392\"" forms.
    let quote = version_line.split('"').nth(1)?;
    let mut it = quote.split('.');
    let first = it.next()?;
    if first == "1" {
        return it.next()?.parse().ok();
    }
    first.parse().ok()
}

/// Keep the informative stderr tail (skipping progress spam).
fn warn_lines(stderr: &str) -> Vec<String> {
    stderr
        .lines()
        .filter(|l| {
            let t = l.trim();
            !t.is_empty()
                && !t.starts_with("INFO - loading")
                && !t.starts_with("INFO - Loaded")
                && !t.contains("%")
        })
        .map(|l| l.chars().take(200).collect())
        .take(20)
        .collect()
}

struct Inventory {
    java_files: Vec<String>,
    total_java: u64,
    resources: u64,
}

/// Walk the jadx output tree: `sources/**.java` + `resources/**`.
fn scan_output(out_dir: &Path, max_files: usize) -> Inventory {
    let mut java_files = Vec::new();
    let mut total_java = 0u64;
    let mut resources = 0u64;
    let sources = out_dir.join("sources");
    walk(&sources, 0, &mut |p, is_file| {
        if is_file {
            if p.extension().and_then(|e| e.to_str()) == Some("java") {
                total_java += 1;
                if java_files.len() < max_files {
                    java_files.push(
                        p.strip_prefix(out_dir)
                            .unwrap_or(p)
                            .display()
                            .to_string(),
                    );
                }
            }
        }
    });
    java_files.sort();
    let res_dir = out_dir.join("resources");
    if res_dir.is_dir() {
        walk(&res_dir, 0, &mut |_p, is_file| {
            if is_file {
                resources += 1;
            }
        });
    }
    Inventory { java_files, total_java, resources }
}

const WALK_DEPTH_CAP: usize = 24;

fn walk(dir: &Path, depth: usize, visit: &mut impl FnMut(&Path, bool)) {
    if depth > WALK_DEPTH_CAP {
        return;
    }
    let Ok(entries) = std::fs::read_dir(dir) else { return };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.is_dir() {
            visit(&path, false);
            walk(&path, depth + 1, visit);
        } else {
            visit(&path, true);
        }
    }
}

// ------------------------------------------------------------------ tests --

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn release_asset_parsing() {
        let json = br#"{
            "tag_name": "v1.5.2",
            "assets": [
                {"name": "jadx-1.5.2.zip", "browser_download_url": "https://gh/jadx-1.5.2.zip"},
                {"name": "jadx-gui-1.5.2.zip", "browser_download_url": "https://gh/gui.zip"},
                {"name": "sources.tar.gz", "browser_download_url": "https://gh/src.tgz"}
            ]
        }"#;
        let fetch = |_: &str| Ok(json.to_vec());
        let (version, url) = latest_release_asset(&fetch).expect("asset found");
        assert_eq!(version, "1.5.2");
        assert_eq!(url, "https://gh/jadx-1.5.2.zip");
    }

    #[test]
    fn parse_release_json_exact_asset() {
        let json = br#"{"tag_name":"v1.5.2","assets":[{"name":"jadx-1.5.2.zip","browser_download_url":"https://gh/jadx-1.5.2.zip"}]}"#;
        let (version, url) = parse_release_json(json).expect("parses");
        assert_eq!(version, "1.5.2");
        assert_eq!(url, "https://gh/jadx-1.5.2.zip");
        // garbage is a clean error
        assert!(parse_release_json(b"nope").is_err());
    }

    #[test]
    fn release_asset_fallback_picks_cli_zip() {
        let json = br#"{
            "tag_name": "1.6.0",
            "assets": [
                {"name": "jadx-1.6.0.zip", "browser_download_url": "https://gh/jadx-1.6.0.zip"}
            ]
        }"#;
        let fetch = |_: &str| Ok(json.to_vec());
        let (version, url) = latest_release_asset(&fetch).expect("asset found");
        assert_eq!(version, "1.6.0");
        assert!(url.ends_with("jadx-1.6.0.zip"));
    }

    #[test]
    fn ensure_refuses_without_consent() {
        let root = tempfile::tempdir().expect("tmp");
        let err = ensure(root.path(), false, |url| {
            Err(anyhow!("unexpected fetch of {url}"))
        })
        .expect_err("must refuse");
        assert!(err.to_string().contains("--download"));
    }

    #[test]
    fn ensure_downloads_and_probes() {
        let root = tempfile::tempdir().expect("tmp");
        // Fake release archive: bin/jadx + lib/jadx-cli.jar.
        let mut zip_buf = Cursor::new(Vec::new());
        {
            let mut w = zip::ZipWriter::new(&mut zip_buf);
            let opts = zip::write::SimpleFileOptions::default()
                .compression_method(zip::CompressionMethod::Deflated)
                .unix_permissions(0o755);
            w.start_file("bin/jadx", opts).expect("add bin");
            std::io::Write::write_all(&mut w, b"#!/bin/sh\necho 9.9.9\n").expect("write bin");
            w.start_file(
                "lib/jadx-cli-9.9.9.jar",
                zip::write::SimpleFileOptions::default().compression_method(zip::CompressionMethod::Deflated),
            )
            .expect("add jar");
            std::io::Write::write_all(&mut w, b"jar-bytes").expect("write jar");
            w.finish().expect("finish");
        }
        let archive = zip_buf.into_inner();
        let fetched = std::cell::RefCell::new(Vec::new());
        let fetch = move |url: &str| -> Result<Vec<u8>> {
            if url == JADX_RELEASES_URL {
                Ok(br#"{"tag_name":"9.9.9","assets":[{"name":"jadx-9.9.9.zip","browser_download_url":"https://gh/jadx-9.9.9.zip"}]}"#.to_vec())
            } else {
                fetched.borrow_mut().push(url.to_string());
                Ok(archive.clone())
            }
        };
        let status = ensure(root.path(), true, fetch).expect("ensure succeeds");
        assert!(status.installed, "managed install must probe green");
        assert!(status.managed);
        assert_eq!(status.version.as_deref(), Some("9.9.9"));
        let bin = status.path.expect("bin path");
        assert!(bin.contains("9.9.9"), "version dir used: {bin}");
        assert!(Path::new(&bin).exists());
    }

    #[test]
    fn run_inventory_and_java_gate() {
        let root = tempfile::tempdir().expect("tmp");
        // Fake jadx "binary": a shell script that fakes output under $2
        // (the `-d <out>` dir, default opts).
        let fake_bin = root.path().join("fake-jadx");
        std::fs::write(
            &fake_bin,
            "#!/bin/sh\n\
             mkdir -p \"$2/sources/com/xen\" \"$2/resources/res\"\n\
             echo 'public class App {}' > \"$2/sources/com/xen/App.java\"\n\
             echo 'public class Util {}' > \"$2/sources/com/xen/Util.java\"\n\
             echo '<resources/>' > \"$2/resources/res/values.xml\"\n\
             echo \"INFO - deobf: 12 renamed\" >&2\n\
             echo \"INFO - loading foo.apk 42%\" >&2\n\
             exit 0\n",
        )
        .expect("write fake jadx");
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&fake_bin, std::fs::Permissions::from_mode(0o755))
                .expect("chmod");
        }
        let status = JadxStatus {
            installed: true,
            version: Some("9.9.9".to_string()),
            path: Some(fake_bin.display().to_string()),
            managed: false,
            java: Some("java".to_string()),
            java_version: Some("openjdk version \"21.0.1\"".to_string()),
            problem: None,
        };
        let apk = root.path().join("app.apk");
        std::fs::write(&apk, b"PK\x03\x04 fake apk").expect("write apk");
        let out = root.path().join("out");

        let run_res = run(&apk, &out, JadxOpts::default(), &status).expect("run");
        assert_eq!(run_res.exit_code, 0);
        assert_eq!(run_res.total_java_files, 2);
        assert_eq!(run_res.resource_files, 1);
        assert!(run_res.java_files.contains(&"sources/com/xen/App.java".to_string()));
        assert!(run_res.warnings.iter().any(|w| w.contains("deobf: 12 renamed")));
        assert!(!run_res.warnings.iter().any(|w| w.contains("42%")), "progress spam filtered");

        // Java gate: no java → refused before spawn.
        let no_java = JadxStatus {
            java: None,
            java_version: None,
            ..status.clone()
        };
        let out2 = root.path().join("out2");
        let err = run(&apk, &out2, JadxOpts::default(), &no_java).expect_err("must refuse");
        assert!(err.to_string().contains("Java 11+"));
    }

    #[test]
    fn java_major_parsing() {
        assert_eq!(java_major("openjdk version \"21.0.1\" 2023"), Some(21));
        assert_eq!(java_major("java version \"1.8.0_392\""), Some(8));
        assert_eq!(java_major("java version \"17\""), Some(17));
        assert_eq!(java_major("garbage"), None);
    }

    #[test]
    fn semver_dirs_sorted_newest_first() {
        assert!(semver_like("1.5.2").is_some());
        assert!(semver_like("1.6.0-rc1").is_some());
        assert!(semver_like("jadx").is_none());
        assert!(semver_like("1").is_none());
        assert!(semver_like("").is_none());
    }

    #[test]
    fn extract_release_sets_exec_bits() {
        let root = tempfile::tempdir().expect("tmp");
        let mut zip_buf = Cursor::new(Vec::new());
        {
            let mut w = zip::ZipWriter::new(&mut zip_buf);
            w.start_file(
                "bin/jadx",
                zip::write::SimpleFileOptions::default().unix_permissions(0o755),
            )
            .expect("add");
            std::io::Write::write_all(&mut w, b"#!/bin/sh\n").expect("write");
            w.finish().expect("finish");
        }
        let dir = root.path().join("x");
        extract_release(&zip_buf.into_inner(), &dir).expect("extract");
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            let mode = std::fs::metadata(dir.join("bin/jadx"))
                .expect("file")
                .permissions()
                .mode();
            assert_eq!(mode & 0o777, 0o755);
        }
    }
}
