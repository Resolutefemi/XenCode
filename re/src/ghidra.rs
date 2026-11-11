//! Ghidra headless orchestration (Phase 7.3) — the second managed RE backend.
//!
//! Ghidra (github.com/NationalSecurityAgency/ghidra) decompiles PPC/x86/ARM
//! images to readable C. Like jadx it ships as a Java app, so XenCode treats
//! it as an *orchestrated backend*: detected on PATH, else downloaded on
//! demand into `~/.xencode/tools/ghidra/<version>/` (explicit opt-in only,
//! ~450 MB), then run headless via `support/analyzeHeadless` with a bundled
//! decompile-export post-script; the emitted `.c` files are inventoried the
//! same way jadx's `.java` output is.
//!
//! XEX loader flow: a XEX container Ghidra cannot import natively is first
//! flattened by [`crate::xex::decompress`] (unencrypted raw/basic payloads
//! only — encrypted ones are refused upstream) and imported as a raw binary
//! with `-loader BinaryLoader -processor PPC:BE:64:default` at the XEX load
//! address, so the Xenon code lands at its real virtual addresses.
//!
//! No keys, no circumvention: this only runs analysis over files the user
//! already owns, and never ships Ghidra loader code for encrypted containers.

use std::io::{Cursor, Read};
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::Instant;

use anyhow::{anyhow, bail, Context, Result};
use serde::Serialize;

use crate::xex;

/// Install root override (falls back to `~/.xencode/tools/ghidra`).
pub const ENV_INSTALL_ROOT: &str = "XENCODE_TOOLS";
/// Java binary override (Ghidra 11+ needs a JDK 21 on PATH).
pub const ENV_JAVA: &str = "XENCODE_JAVA";
/// GitHub release lookup is pinned to NationalSecurityAgency/ghidra.
pub const GHIDRA_RELEASES_URL: &str =
    "https://api.github.com/repos/NationalSecurityAgency/ghidra/releases/latest";
/// Human releases page (redirect scrape fallback — not API-rate-limited).
pub const GHIDRA_LATEST_URL: &str = "https://github.com/NationalSecurityAgency/ghidra/releases/latest";
/// Expanded-assets fragment: GitHub loads release assets for the tag page
/// from here; plain HTML with the download hrefs (no API quota involved).
pub const GHIDRA_ASSETS_URL: &str =
    "https://github.com/NationalSecurityAgency/ghidra/releases/expanded_assets";
/// Default processor language for raw XEX images (Xenon = PPC64 big-endian).
pub const DEFAULT_PPC_LANG: &str = "PPC:BE:64:default";
/// Name of the bundled decompile-export post-script (written next to the run).
pub const DECOMP_SCRIPT_NAME: &str = "XenDumpDecomp.java";

/// Bundled Ghidra post-script: decompile every function into `<out>/*.c`.
/// Written to the output dir at run time; args: `<out_dir> <max_functions>`.
const DECOMP_SCRIPT: &str = r#"import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

public class XenDumpDecomp extends GhidraScript {
    @Override
    public void run() throws Exception {
        String[] args = getScriptArgs();
        File outDir = new File(args[0]);
        int maxFns = args.length > 1 ? Integer.parseInt(args[1]) : 20000;
        int timeoutSec = args.length > 2 ? Integer.parseInt(args[2]) : 90;
        outDir.mkdirs();
        DecompInterface di = new DecompInterface();
        di.setOptions(new DecompileOptions());
        di.openProgram(currentProgram);
        FunctionIterator it = currentProgram.getFunctionManager().getFunctions(true);
        int n = 0;
        while (it.hasNext() && n < maxFns) {
            Function f = it.next();
            if (f.isThunk() || f.isExternal()) continue;
            String name = f.getName().replaceAll("[^A-Za-z0-9_.\\-]", "_");
            String addr = f.getEntryPoint().toString();
            File of = new File(outDir, name + "_" + addr + ".c");
            PrintWriter w = new PrintWriter(
                new OutputStreamWriter(new FileOutputStream(of), StandardCharsets.UTF_8));
            try {
                w.println("// " + f.getName(true) + " @ " + addr);
                DecompileResults res = di.decompileFunction(f, timeoutSec, monitor);
                if (res != null && res.decompileCompleted() && res.getDecompiledFunction() != null) {
                    w.println(res.getDecompiledFunction().getC());
                } else {
                    w.println("// decompilation failed @ " + addr);
                }
            } finally {
                w.close();
            }
            n++;
        }
        di.dispose();
        println("XenDumpDecomp: wrote " + n + " functions to " + outDir);
    }
}
"#;

/// Where Ghidra could/could not be found.
#[derive(Debug, Clone, Serialize)]
pub struct GhidraStatus {
    pub installed: bool,
    /// Version from `Ghidra/application.properties` (e.g. "12.2").
    pub version: Option<String>,
    /// `analyzeHeadless` path used (PATH hit or managed install).
    pub path: Option<String>,
    /// Whether this came from the managed install dir.
    pub managed: bool,
    /// The Ghidra application dir (contains `Ghidra/` + `support/`).
    pub app_dir: Option<String>,
    /// Java runtime detected for the run (path + `java -version` first line).
    pub java: Option<String>,
    pub java_version: Option<String>,
    /// Present when probe/ensure failed — the CLI prints it verbatim.
    pub problem: Option<String>,
}

#[derive(Debug, Clone, Serialize)]
pub struct GhidraRun {
    pub ghidra_version: String,
    pub input: String,
    /// The file actually imported (the flattened image for XEX inputs).
    pub imported: String,
    pub out_dir: String,
    pub args: Vec<String>,
    pub exit_code: i32,
    pub elapsed_ms: u64,
    /// .c files produced (relative paths, capped — see [`GhidraOpts::max_files`]).
    pub c_files: Vec<String>,
    pub total_c_files: u64,
    /// Function count reported by the post-script, when found.
    pub functions_decompiled: Option<u64>,
    /// Curated stderr tail (errors, exceptions).
    pub warnings: Vec<String>,
}

#[derive(Debug, Clone)]
pub struct GhidraOpts {
    /// Cap on the .c file listing in the report (total stays exact).
    pub max_files: usize,
    /// Cap on functions the post-script decompiles (0 = script default).
    pub max_functions: usize,
    /// Per-function decompiler timeout in seconds (0 = script default).
    pub timeout_sec: usize,
    /// Processor language id override for raw images.
    pub lang: Option<String>,
    /// Raw-image base address override (defaults to the XEX load address).
    pub base_addr: Option<u64>,
}

impl GhidraOpts {
    pub const DEFAULT_MAX_FILES: usize = 512;
    pub const DEFAULT_MAX_FUNCTIONS: usize = 20_000;
}

impl Default for GhidraOpts {
    fn default() -> Self {
        GhidraOpts {
            max_files: Self::DEFAULT_MAX_FILES,
            max_functions: Self::DEFAULT_MAX_FUNCTIONS,
            timeout_sec: 0,
            lang: None,
            base_addr: None,
        }
    }
}

// ----------------------------------------------------------------- probe --

/// Default install root: `$XENCODE_TOOLS/ghidra` or `~/.xencode/tools/ghidra`.
pub fn install_root() -> PathBuf {
    if let Ok(root) = std::env::var(ENV_INSTALL_ROOT) {
        if !root.trim().is_empty() {
            return Path::new(&root).join("ghidra");
        }
    }
    let home = std::env::var("HOME")
        .or_else(|_| std::env::var("USERPROFILE"))
        .unwrap_or_else(|_| ".".to_string());
    Path::new(&home).join(".xencode/tools/ghidra")
}

/// Locate Ghidra: managed install first, then PATH. Version comes from
/// `Ghidra/application.properties` (no process spawn needed).
pub fn probe() -> GhidraStatus {
    probe_in(&install_root())
}

pub fn probe_in(install_root: &Path) -> GhidraStatus {
    let java = detect_java();

    // Managed layout: <root>/<version>/ghidra_<ver>_PUBLIC/support/analyzeHeadless
    // (also tolerate a version dir whose contents were extracted flat).
    let mut managed: Vec<(String, PathBuf)> = Vec::new(); // (sort key, app dir)
    if let Ok(entries) = std::fs::read_dir(install_root) {
        for e in entries.flatten().filter(|e| e.path().is_dir()) {
            let name = e.file_name().into_string().unwrap_or_default();
            if let Some(inner) = newest_app_dir(&e.path()) {
                managed.push((name, inner));
            }
        }
    }
    managed.sort_by(|a, b| version_key(&b.0).cmp(&version_key(&a.0)));
    for (_, app) in &managed {
        let headless = app.join("support").join(headless_name());
        if headless.is_file() {
            return GhidraStatus {
                installed: true,
                version: read_version(app),
                path: Some(headless.display().to_string()),
                managed: true,
                app_dir: Some(app.display().to_string()),
                java: java.as_ref().map(|(n, _)| n.clone()),
                java_version: java.as_ref().map(|(_, v)| v.clone()),
                problem: None,
            };
        }
    }

    // PATH.
    if let Some(on_path) = which(headless_name()) {
        // app dir = parent of support/.
        let app = on_path.parent().map(|p| p.to_path_buf());
        return GhidraStatus {
            installed: true,
            version: app.as_deref().and_then(read_version),
            path: Some(on_path.display().to_string()),
            managed: false,
            app_dir: app.map(|p| p.display().to_string()),
            java: java.as_ref().map(|(n, _)| n.clone()),
            java_version: java.as_ref().map(|(_, v)| v.clone()),
            problem: None,
        };
    }

    GhidraStatus {
        installed: false,
        version: None,
        path: None,
        managed: false,
        app_dir: None,
        java: java.as_ref().map(|(n, _)| n.clone()),
        java_version: java.as_ref().map(|(_, v)| v.clone()),
        problem: Some(format!(
            "Ghidra not found — managed installs live in {}, or put `analyzeHeadless` on \
             PATH; `xencode re ghidra <FILE> --download` fetches the latest release \
             (~450 MB, needs a JDK 17+/21+)",
            install_root.display()
        )),
    }
}

fn headless_name() -> &'static str {
    if cfg!(windows) { "analyzeHeadless.bat" } else { "analyzeHeadless" }
}

/// Find the newest `ghidra_*` application dir under `dir` (a managed version
/// dir). Accepts both `<root>/<v>/ghidra_…/` and `<root>/<v>/` extracted flat.
fn newest_app_dir(dir: &Path) -> Option<PathBuf> {
    if dir.join("support").join(headless_name()).is_file() && dir.join("Ghidra").is_dir() {
        return Some(dir.to_path_buf());
    }
    let Ok(entries) = std::fs::read_dir(dir) else { return None };
    let mut apps: Vec<(String, PathBuf)> = entries
        .flatten()
        .filter(|e| e.path().is_dir())
        .filter_map(|e| {
            let name = e.file_name().into_string().ok()?;
            name.starts_with("ghidra_")
                .then(|| (name, e.path()))
        })
        .collect();
    apps.sort_by(|a, b| b.0.cmp(&a.0));
    apps.into_iter()
        .map(|(_, p)| p)
        .find(|p| p.join("support").join(headless_name()).is_file())
}

fn version_key(name: &str) -> Vec<u32> {
    name.split(['.', '-', '_'])
        .filter_map(|p| p.parse().ok())
        .collect()
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
    let bin = if name.contains(std::path::MAIN_SEPARATOR) || Path::new(&name).is_absolute() {
        // Explicit path override — use as-is when it exists.
        let p = PathBuf::from(&name);
        p.is_file().then_some(p)?
    } else {
        which(&name)?
    };
    let out = Command::new(&bin).arg("-version").output().ok()?;
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

/// Version from `Ghidra/application.properties` (`APPLICATION_VERSION=x.y.z`).
fn read_version(app_dir: &Path) -> Option<String> {
    let props = app_dir.join("Ghidra").join("application.properties");
    let text = std::fs::read_to_string(props).ok()?;
    text.lines()
        .find_map(|l| l.strip_prefix("APPLICATION_VERSION="))
        .map(|v| v.trim().to_string())
        .filter(|v| !v.is_empty())
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

/// Minimum JDK major for a Ghidra version (11+ needs 21, 10.x needs 17).
fn min_java_major(ghidra_version: &str) -> u32 {
    let mut parts = ghidra_version.split('.');
    let major: u32 = parts.next().and_then(|p| p.parse().ok()).unwrap_or(11);
    if major >= 11 { 21 } else { 17 }
}

// ---------------------------------------------------------------- ensure --

/// Make sure Ghidra exists; download the latest release into the install
/// root when `allow_download` is set. `fetch` is injectable for tests.
pub fn ensure(
    install_root: &Path,
    allow_download: bool,
    fetch: impl Fn(&str) -> Result<Vec<u8>>,
) -> Result<GhidraStatus> {
    let status = probe_in(install_root);
    if status.installed {
        return Ok(status);
    }
    if !allow_download {
        return Err(anyhow!(
            "Ghidra is not installed. Re-run with `--download` to fetch the latest release \
             from GitHub (~450 MB, installed under {}), or install Ghidra / put \
             `analyzeHeadless` on PATH (a JDK 21 is required).",
            install_root.display()
        ));
    }
    let (version, url) = latest_release_asset(&fetch)?;
    let dir = install_root.join(&version);
    if newest_app_dir(&dir).is_some() {
        // Raced or pre-existing — probe again and move on.
        return Ok(probe_in(install_root));
    }
    let archive = fetch(&url).context(format!("download {url}"))?;
    extract_release(&archive, &dir).context("extract Ghidra release")?;
    std::fs::write(dir.join(".xencode-installed"), format!("{version}\n{url}\n")).ok();
    let status = probe_in(install_root);
    if !status.installed {
        bail!("Ghidra {version} extracted to {} but analyzeHeadless is missing", dir.display());
    }
    Ok(status)
}

/// Query the latest NSA/ghidra release and pick the `ghidra_<v>_PUBLIC_*.zip`
/// asset. API rate limits fall back to the redirect + expanded-assets scrape.
pub fn latest_release_asset(fetch: &impl Fn(&str) -> Result<Vec<u8>>) -> Result<(String, String)> {
    match fetch(GHIDRA_RELEASES_URL) {
        Ok(raw) => match parse_release_json(&raw) {
            Ok(found) => Ok(found),
            Err(api_err) => scrape_latest().map_err(|scrape_err| {
                anyhow!("GitHub API: {api_err}; redirect scrape: {scrape_err}")
            }),
        },
        Err(_) => scrape_latest(),
    }
}

/// Pure release-JSON parser: tag + the `ghidra_*_PUBLIC_*.zip` asset.
pub fn parse_release_json(raw: &[u8]) -> Result<(String, String)> {
    let v: serde_json::Value =
        serde_json::from_slice(raw).context("Ghidra release JSON is not valid JSON")?;
    let tag = v["tag_name"]
        .as_str()
        .ok_or_else(|| anyhow!("release JSON has no tag_name"))?
        .to_string();
    let version = tag
        .strip_prefix("Ghidra_")
        .and_then(|t| t.strip_suffix("_build"))
        .ok_or_else(|| anyhow!("unexpected Ghidra tag `{tag}`"))?
        .to_string();
    let assets = v["assets"]
        .as_array()
        .ok_or_else(|| anyhow!("release JSON has no assets"))?;
    let name_and_url = |a: &serde_json::Value| -> Option<(String, String)> {
        Some((a["name"].as_str()?.to_string(), a["browser_download_url"].as_str()?.to_string()))
    };
    let asset = assets
        .iter()
        .filter_map(name_and_url)
        .find(|(name, _)| {
            name.starts_with(&format!("ghidra_{version}_PUBLIC_")) && name.ends_with(".zip")
        })
        .or_else(|| {
            assets.iter().filter_map(name_and_url).find(|(name, _)| {
                name.starts_with("ghidra_") && name.contains("PUBLIC") && name.ends_with(".zip")
            })
        });
    Ok((version, asset.ok_or_else(|| anyhow!("latest Ghidra release has no ghidra_*_PUBLIC_*.zip asset"))?.1))
}

/// Redirect-scrape fallback: follow `releases/latest` to the tag, then read
/// the `expanded_assets/<tag>` fragment (plain HTML, no API quota) for the
/// `ghidra_*_PUBLIC_*.zip` download href.
pub fn scrape_latest() -> Result<(String, String)> {
    use ureq::ResponseExt;
    let agent = ureq::Agent::config_builder()
        .http_status_as_error(false)
        .build()
        .new_agent();
    let res = agent
        .get(GHIDRA_LATEST_URL)
        .header("User-Agent", "xencode-re")
        .call()
        .map_err(|e| anyhow!("GET {GHIDRA_LATEST_URL} failed: {e}"))?;
    if res.status() != 200 {
        bail!("{} answered {}", GHIDRA_LATEST_URL, res.status());
    }
    let uri = res.get_uri().to_string();
    let tag = uri
        .trim_end_matches('/')
        .rsplit('/')
        .next()
        .unwrap_or_default()
        .to_string();
    if !tag.starts_with("Ghidra_") {
        bail!("could not resolve the latest Ghidra tag from redirect to {uri}");
    }
    let version = tag
        .strip_prefix("Ghidra_")
        .and_then(|t| t.strip_suffix("_build"))
        .unwrap_or_default()
        .to_string();

    let assets_url = format!("{GHIDRA_ASSETS_URL}/{tag}");
    let page = agent
        .get(&assets_url)
        .header("User-Agent", "xencode-re")
        .call()
        .map_err(|e| anyhow!("GET {assets_url} failed: {e}"))?;
    if page.status() != 200 {
        bail!("{} answered {}", assets_url, page.status());
    }
    let html = page.into_body().read_to_string().map_err(|e| anyhow!("read {assets_url}: {e}"))?;
    let url = asset_from_assets_html(&html, &version)
        .ok_or_else(|| anyhow!("no ghidra_*_PUBLIC_*.zip asset on {assets_url}"))?;
    Ok((version, url))
}

/// Pure assets-fragment parser: first `/…/download/…/ghidra_<v>_PUBLIC_*.zip`
/// href, absolutized against github.com.
pub fn asset_from_assets_html(html: &str, version: &str) -> Option<String> {
    let exact = format!("ghidra_{version}_PUBLIC_");
    let mut fallback = None;
    for part in html.split("href=\"").skip(1) {
        let Some(href) = part.split('"').next() else { continue };
        if !href.contains("/releases/download/") || !href.ends_with(".zip") {
            continue;
        }
        let file = href.rsplit('/').next().unwrap_or_default();
        if file.starts_with(&exact) {
            return Some(absolutize(href));
        }
        if file.starts_with("ghidra_") && file.contains("PUBLIC") && fallback.is_none() {
            fallback = Some(absolutize(href));
        }
    }
    fallback
}

fn absolutize(href: &str) -> String {
    if href.starts_with("http") {
        href.to_string()
    } else {
        format!("https://github.com{href}")
    }
}

/// Extract the release zip into `dir`, keeping unix exec bits on scripts.
pub fn extract_release(archive: &[u8], dir: &Path) -> Result<()> {
    let mut zip = zip::ZipArchive::new(Cursor::new(archive))
        .context("Ghidra release download is not a valid zip")?;
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
    // ureq 3: read the whole body with a sane cap (1 GiB release ceiling —
    // Ghidra zips are ~450 MB).
    let mut out = Vec::new();
    let reader = res.body_mut().as_reader();
    reader
        .take(1024 * 1024 * 1024)
        .read_to_end(&mut out)
        .map_err(|e| anyhow!("reading {url} failed: {e}"))?;
    Ok(out)
}

// ------------------------------------------------------------------- run --

/// Run Ghidra headless over `input`, exporting decompiled C into `out_dir`.
/// The status must carry an installed `path`; Java presence + version gate
/// the spawn. XEX inputs are flattened first (compliance-refused upstream
/// when encrypted/LZX).
pub fn run(input: &Path, out_dir: &Path, opts: GhidraOpts, status: &GhidraStatus) -> Result<GhidraRun> {
    let Some(headless) = status.path.as_ref() else {
        bail!("Ghidra is not installed — run ensure()/probe() first");
    };
    if !input.is_file() {
        bail!("{} is not a file", input.display());
    }
    let java_version = status.java_version.clone().unwrap_or_default();
    if status.java.is_none() {
        bail!(
            "Ghidra needs a JDK on PATH — install JDK 21 (Ghidra 11+) or set {ENV_JAVA}; \
             no `java` was found"
        );
    }
    if let Some(major) = java_major(&java_version) {
        let want = min_java_major(status.version.as_deref().unwrap_or("11.0"));
        if major < want {
            bail!("Ghidra {} needs Java {want}+; the detected runtime reports {java_version}", 
                  status.version.as_deref().unwrap_or("11.0"));
        }
    }

    std::fs::create_dir_all(out_dir)?;
    let mut args: Vec<String> = vec![
        out_dir.join("_project").display().to_string(),
        "XenRE".to_string(),
        "-import".to_string(),
    ];

    // XEX loader flow: flatten to a raw image and pin the PPC64 BE language
    // at the load address. Everything else imports natively.
    let raw = std::fs::read(input)?;
    if raw.len() as u64 > crate::MAX_INPUT_BYTES {
        bail!(
            "{} is {} bytes — over the {} analysis cap",
            input.display(),
            raw.len(),
            crate::MAX_INPUT_BYTES
        );
    }
    let imported_path: PathBuf;
    if xex::is_xex(&raw) {
        let img = xex::decompress(&raw)?;
        imported_path = out_dir.join(format!(
            "{}.image.bin",
            input.file_stem().and_then(|s| s.to_str()).unwrap_or("xex")
        ));
        std::fs::write(&imported_path, &img.image)?;
        args.push(imported_path.display().to_string());
        args.push("-loader".into());
        args.push("BinaryLoader".into());
        args.push("-processor".into());
        args.push(opts.lang.clone().unwrap_or_else(|| DEFAULT_PPC_LANG.to_string()));
        let base = opts.base_addr.unwrap_or(img.info.load_address);
        args.push("-loader-baseAddr".into());
        args.push(format!("{base:#x}"));
    } else {
        imported_path = input.to_path_buf();
        args.push(imported_path.display().to_string());
    }

    // Bundled decompile-export post-script.
    let script_dir = out_dir.join("_xen_scripts");
    std::fs::create_dir_all(&script_dir)?;
    std::fs::write(script_dir.join(DECOMP_SCRIPT_NAME), DECOMP_SCRIPT)?;
    args.push("-scriptPath".into());
    args.push(script_dir.display().to_string());
    args.push("-postScript".into());
    args.push(DECOMP_SCRIPT_NAME.into());
    args.push(out_dir.display().to_string());
    args.push(opts.max_functions.max(1).to_string());
    args.push(opts.timeout_sec.to_string());
    args.push("-deleteProject".into());

    let started = Instant::now();
    let mut cmd = Command::new(headless);
    cmd.args(&args)
        .stdin(std::process::Stdio::null());
    // Pin the Java runtime when it came from an override.
    if let Some(java_bin) = status.java.as_deref() {
        if let Some(home) = java_home_of(java_bin) {
            cmd.env("JAVA_HOME", home);
        }
    }
    let output = cmd
        .output()
        .map_err(|e| anyhow!("spawn `{}` failed: {e}", headless))?;
    let elapsed_ms = started.elapsed().as_millis() as u64;

    let stderr_text = String::from_utf8_lossy(&output.stderr).into_owned();
    let warnings = warn_lines(&stderr_text);
    let stdout_text = String::from_utf8_lossy(&output.stdout).into_owned();
    let functions_decompiled = stdout_text
        .lines()
        .find_map(|l| l.trim().strip_prefix("XenDumpDecomp: wrote "))
        .and_then(|rest| {
            rest.split_whitespace().next().and_then(|n| n.parse().ok())
        });

    let inventory = scan_output(out_dir, opts.max_files);

    Ok(GhidraRun {
        ghidra_version: status.version.clone().unwrap_or_default(),
        input: input.display().to_string(),
        imported: imported_path.display().to_string(),
        out_dir: out_dir.display().to_string(),
        args,
        exit_code: output.status.code().unwrap_or(-1),
        elapsed_ms,
        c_files: inventory.c_files,
        total_c_files: inventory.total,
        functions_decompiled,
        warnings,
    })
}

/// JAVA_HOME for a `<home>/bin/java`-style path (None when not bin-layout).
fn java_home_of(java_bin: &str) -> Option<PathBuf> {
    let p = Path::new(java_bin);
    let bin = p.parent()?;
    if bin.file_name()?.to_str()? != "bin" {
        return None;
    }
    bin.parent().map(|home| home.to_path_buf())
}

/// Keep the informative stderr tail (errors/exceptions; analysis progress
/// spam is dropped).
fn warn_lines(stderr: &str) -> Vec<String> {
    stderr
        .lines()
        .filter(|l| {
            let t = l.trim();
            !t.is_empty()
                && !t.starts_with("INFO ")
                && !t.starts_with("INFO  ")
                && !t.contains('%')
                && (t.contains("ERROR")
                    || t.contains("Exception")
                    || t.starts_with("WARN ")
                    || t.starts_with("WARN  "))
        })
        .map(|l| l.chars().take(240).collect())
        .take(20)
        .collect()
}

struct Inventory {
    c_files: Vec<String>,
    total: u64,
}

/// Walk the export tree for `.c` files (the script dir and project leftovers
/// are not .c and thus never listed).
fn scan_output(out_dir: &Path, max_files: usize) -> Inventory {
    let mut c_files = Vec::new();
    let mut total = 0u64;
    walk(out_dir, 0, &mut |p, is_file| {
        if is_file && p.extension().and_then(|e| e.to_str()) == Some("c") {
            total += 1;
            if c_files.len() < max_files {
                c_files.push(
                    p.strip_prefix(out_dir)
                        .unwrap_or(p)
                        .display()
                        .to_string(),
                );
            }
        }
    });
    c_files.sort();
    Inventory { c_files, total }
}

const WALK_DEPTH_CAP: usize = 8;

fn walk(dir: &Path, depth: usize, visit: &mut impl FnMut(&Path, bool)) {
    if depth > WALK_DEPTH_CAP {
        return;
    }
    let Ok(entries) = std::fs::read_dir(dir) else { return };
    for entry in entries.flatten() {
        let path = entry.path();
        if path.is_dir() {
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
            "tag_name": "Ghidra_12.2_build",
            "assets": [
                {"name": "ghidra_12.2_PUBLIC_20261016.zip", "browser_download_url": "https://gh/ghidra.zip"},
                {"name": "ghidra_12.2_src.zip", "browser_download_url": "https://gh/src.zip"}
            ]
        }"#;
        let fetch = |_: &str| Ok(json.to_vec());
        let (version, url) = latest_release_asset(&fetch).expect("asset found");
        assert_eq!(version, "12.2");
        assert_eq!(url, "https://gh/ghidra.zip");
    }

    #[test]
    fn parse_release_json_fallback_and_garbage() {
        let json = br#"{"tag_name":"Ghidra_11.4.2_build","assets":[{"name":"ghidra_11.4.2_PUBLIC_20250710.zip","browser_download_url":"https://gh/g.zip"}]}"#;
        let (version, url) = parse_release_json(json).expect("parses");
        assert_eq!(version, "11.4.2");
        assert_eq!(url, "https://gh/g.zip");
        assert!(parse_release_json(b"nope").is_err());
        assert!(parse_release_json(br#"{"tag_name":"v1.2","assets":[]}"#).is_err());
    }

    #[test]
    fn assets_fragment_parsing() {
        let html = r#"<div><a href="/NationalSecurityAgency/ghidra/releases/download/Ghidra_12.2_build/ghidra_12.2_PUBLIC_20261016.zip">zip</a>
        <a href="/NationalSecurityAgency/ghidra/releases/download/Ghidra_12.2_build/ghidra_12.2_src.zip">src</a></div>"#;
        let url = asset_from_assets_html(html, "12.2").expect("asset");
        assert_eq!(url, "https://github.com/NationalSecurityAgency/ghidra/releases/download/Ghidra_12.2_build/ghidra_12.2_PUBLIC_20261016.zip");
        assert!(asset_from_assets_html("<div>nothing</div>", "12.2").is_none());
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
        // Fake release archive: ghidra_9.9_PUBLIC/support/analyzeHeadless +
        // Ghidra/application.properties.
        let mut zip_buf = Cursor::new(Vec::new());
        {
            let mut w = zip::ZipWriter::new(&mut zip_buf);
            let opts = zip::write::SimpleFileOptions::default()
                .compression_method(zip::CompressionMethod::Deflated)
                .unix_permissions(0o755);
            w.start_file("ghidra_9.9_PUBLIC/support/analyzeHeadless", opts).expect("add headless");
            std::io::Write::write_all(&mut w, b"#!/bin/sh\necho hi\n").expect("write");
            w.start_file(
                "ghidra_9.9_PUBLIC/Ghidra/application.properties",
                zip::write::SimpleFileOptions::default()
                    .compression_method(zip::CompressionMethod::Deflated),
            )
            .expect("add props");
            std::io::Write::write_all(&mut w, b"APPLICATION_VERSION=9.9\n").expect("write props");
            w.finish().expect("finish");
        }
        let archive = zip_buf.into_inner();
        let fetch = move |url: &str| -> Result<Vec<u8>> {
            if url == GHIDRA_RELEASES_URL {
                Ok(br#"{"tag_name":"Ghidra_9.9_build","assets":[{"name":"ghidra_9.9_PUBLIC_20260101.zip","browser_download_url":"https://gh/ghidra.zip"}]}"#.to_vec())
            } else {
                Ok(archive.clone())
            }
        };
        let status = ensure(root.path(), true, fetch).expect("ensure succeeds");
        assert!(status.installed, "managed install must probe green");
        assert!(status.managed);
        assert_eq!(status.version.as_deref(), Some("9.9"));
        let headless = status.path.expect("headless path");
        assert!(headless.contains("9.9"), "version dir used: {headless}");
        assert!(Path::new(&headless).exists());
    }

    #[test]
    fn java_gate_and_version_floor() {
        assert_eq!(min_java_major("12.0"), 21);
        assert_eq!(min_java_major("11.4.2"), 21);
        assert_eq!(min_java_major("10.2"), 17);
        assert_eq!(min_java_major("garbage"), 21);
        assert_eq!(java_major("openjdk version \"21.0.1\" 2023"), Some(21));
        assert_eq!(java_major("java version \"1.8.0_392\""), Some(8));
        assert_eq!(java_major("garbage"), None);
    }

    #[test]
    fn run_xex_flattens_and_loads_raw() {
        let root = tempfile::tempdir().expect("tmp");
        // Minimal unencrypted XEX2 (one 4 KiB code page at 0x82000000).
        let xex_bytes = minimal_xex();
        let xex_path = root.path().join("homebrew.xex");
        std::fs::write(&xex_path, &xex_bytes).expect("write xex");

        // Fake analyzeHeadless: writes one .c file into the last absolute
        // path argument (= the export dir) + a progress-y stderr line.
        let fake = root.path().join("fake-headless");
        std::fs::write(
            &fake,
            "#!/bin/sh\n\
             echo 'INFO  ANALYZING foo' >&2\n\
             echo 'ERROR  line with error' >&2\n\
             for a in \"$@\"; do case \"$a\" in /*) last=\"$a\";; esac; done\n\
             mkdir -p \"$last\"\n\
             echo '// fn' > \"$last/func_82001000.c\"\n\
             echo 'XenDumpDecomp: wrote 1 functions'\n\
             exit 0\n",
        )
        .expect("write fake headless");
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&fake, std::fs::Permissions::from_mode(0o755))
                .expect("chmod");
        }
        let status = GhidraStatus {
            installed: true,
            version: Some("12.2".into()),
            path: Some(fake.display().to_string()),
            managed: false,
            app_dir: None,
            java: Some("/usr/lib/jvm/bin/java".into()),
            java_version: Some("openjdk version \"21.0.1\"".into()),
            problem: None,
        };
        let out = root.path().join("out");
        let run_res = run(&xex_path, &out, GhidraOpts::default(), &status).expect("run");
        assert_eq!(run_res.exit_code, 0);
        assert!(run_res.imported.ends_with(".image.bin"), "XEX flattened: {}", run_res.imported);
        assert!(Path::new(&run_res.imported).exists());
        // Raw-binary loader args present with the XEX load address.
        let joined = run_res.args.join(" ");
        assert!(joined.contains("BinaryLoader"), "{joined}");
        assert!(joined.contains(DEFAULT_PPC_LANG), "{joined}");
        assert!(joined.contains("0x82000000"), "{joined}");
        assert_eq!(run_res.total_c_files, 1);
        assert!(run_res.c_files.contains(&"func_82001000.c".to_string()));
        assert_eq!(run_res.functions_decompiled, Some(1));
        assert!(run_res.warnings.iter().any(|w| w.contains("ERROR")));
        assert!(!run_res.warnings.iter().any(|w| w.contains("ANALYZING")), "info spam filtered");
        // Post-script landed next to the output.
        assert!(out.join("_xen_scripts").join(DECOMP_SCRIPT_NAME).is_file());
    }

    #[test]
    fn run_refuses_without_java() {
        let root = tempfile::tempdir().expect("tmp");
        let elf = root.path().join("sample.elf");
        std::fs::write(&elf, b"\x7fELF stub").expect("write");
        let status = GhidraStatus {
            installed: true,
            version: Some("12.2".into()),
            path: Some("/bin/false".into()),
            managed: false,
            app_dir: None,
            java: None,
            java_version: None,
            problem: None,
        };
        let err = run(&elf, &root.path().join("out"), GhidraOpts::default(), &status)
            .expect_err("must refuse");
        assert!(err.to_string().contains("JDK"), "{err}");
    }

    #[test]
    fn java_home_detection() {
        assert_eq!(
            java_home_of("/usr/lib/jvm/temurin-21/bin/java"),
            Some(PathBuf::from("/usr/lib/jvm/temurin-21"))
        );
        // Derived JAVA_HOME/bin/java always reproduces the input path —
        // even a plain /usr/bin/java yields /usr (self-consistent).
        assert_eq!(java_home_of("/usr/bin/java"), Some(PathBuf::from("/usr")));
    }

    /// Minimal unencrypted XEX2 with one code page at 0x82000000 — mirrors
    /// the byte layout the xex module's fixture builder produces.
    fn minimal_xex() -> Vec<u8> {
        let be = |v: u32| v.to_be_bytes();
        let so: u32 = 0x100;
        // Security block (fixed 0x184 + 1 descriptor), placed at `so`.
        let mut sec = Vec::new();
        sec.extend_from_slice(&be(0x184 + 24)); // security header size
        sec.extend_from_slice(&be(0x1000)); // image size (1 × 4 KiB page)
        sec.extend_from_slice(&[0u8; 0x100]); // RSA sig
        sec.extend_from_slice(&[0u8; 0x4]); // unk_108
        sec.extend_from_slice(&be(0x1000_0000)); // image flags: 4 KiB pages
        sec.extend_from_slice(&be(0x8200_0000)); // load address
        sec.extend_from_slice(&[0u8; 0x14]); // section digest
        sec.extend_from_slice(&be(0)); // import table count
        sec.extend_from_slice(&[0u8; 0x14]); // import digest
        sec.extend_from_slice(&[0u8; 0x10]); // media id
        sec.extend_from_slice(&[0u8; 0x10]); // aes key slot (zeros)
        sec.extend_from_slice(&be(0)); // export table
        sec.extend_from_slice(&[0u8; 0x14]); // header digest
        sec.extend_from_slice(&be(0xFFFF_FFFF)); // region
        sec.extend_from_slice(&be(0x7)); // allowed media
        sec.extend_from_slice(&be(1)); // page descriptor count
        // One code page.
        sec.extend_from_slice(&be((1u32 << 4) | 1));
        sec.extend_from_slice(&[0u8; 0x14]); // page digest

        // Header: records at 0x18, FILE_FORMAT block after them, security at
        // `so`, payload after it.
        let ff_block: [u8; 8] = {
            // struct size (8) + encryption none (u16) + compression none (u16)
            let mut b = [0u8; 8];
            b[0..4].copy_from_slice(&be(8));
            b
        };
        let ff_off = 0x18 + 3 * 8; // after the 3 optional header records
        let pe_off = (so as usize + sec.len()).max(ff_off + ff_block.len()); // payload starts after security
        let mut out = Vec::new();
        out.extend_from_slice(b"XEX2");
        out.extend_from_slice(&be(0x81)); // module flags: title + user-mode
        out.extend_from_slice(&be(pe_off as u32));
        out.extend_from_slice(&be(0)); // reserved
        out.extend_from_slice(&be(so));
        out.extend_from_slice(&be(3)); // optional header count
        // ENTRY_POINT (inline) + IMAGE_BASE (inline) + FILE_FORMAT_INFO (block).
        out.extend_from_slice(&be(0x0001_0100));
        out.extend_from_slice(&be(0x8200_1000));
        out.extend_from_slice(&be(0x0001_0201));
        out.extend_from_slice(&be(0x8200_0000));
        out.extend_from_slice(&be(0x0000_03FF));
        out.extend_from_slice(&be(ff_off as u32));
        // Optional header blocks.
        while out.len() < ff_off {
            out.push(0);
        }
        out.extend_from_slice(&ff_block);
        while out.len() < so as usize {
            out.push(0);
        }

        let mut data = vec![0u8; pe_off];
        data[..out.len()].copy_from_slice(&out);
        data[so as usize..so as usize + sec.len()].copy_from_slice(&sec);
        // Payload: `blr` at image offset 0x1000 (= payload offset 0x1000,
        // i.e. file offset pe_off + 0x1000 for the compression-none layout).
        let entry_off = pe_off + 0x1000;
        if data.len() < entry_off + 4 {
            data.resize(entry_off + 4, 0);
        }
        data[entry_off..entry_off + 4].copy_from_slice(&[0x4e, 0x80, 0x00, 0x20]);
        data
    }
}
