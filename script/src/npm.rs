//! npm-lite vendor cache — "no npm" Tier 2.
//!
//! Pure-JS packages resolved straight from the registry, sha512-verified,
//! lockfiled, and imported as ESM — no node_modules, no npm install.
//!
//! Flow: parse the entry's import specifiers → for every bare specifier that
//! isn't a built-in native module, resolve the package (lockfile pin or
//! latest), download + verify the tarball into the cache dir, pick the ESM
//! entry from package.json, then recurse into the vendored source's own bare
//! imports (bounded depth). The resulting specifier → file map feeds the
//! runtime's [`crate::runtime::XenResolver`].

use std::collections::BTreeMap;
use std::io::Read;
use std::path::{Path, PathBuf};

use anyhow::{anyhow, Context as _};
use serde::{Deserialize, Serialize};
use sha2::{Digest, Sha256, Sha512};

use crate::permissions::PermissionState;

const REGISTRY: &str = "https://registry.npmjs.org";
/// Recursion cap for transitive (pure-JS) dependencies.
const MAX_DEPTH: usize = 8;
/// Total package cap per prefetch run.
const MAX_PACKAGES: usize = 64;

/// Result of a prefetch: what the runtime loader needs to know.
#[derive(Debug, Default, Clone)]
pub struct PrefetchResult {
    /// specifier (e.g. `ms` or `ms/lib/index.js`) → vendored file path
    pub files: BTreeMap<String, PathBuf>,
    /// package name (e.g. `ms` or `@scope/pkg`) → vendored package root
    pub roots: BTreeMap<String, PathBuf>,
    /// packages actually vendored this run (for the lockfile)
    pub vendored: Vec<LockedPackage>,
}

/// One lockfile entry (`xen.lock`).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct LockedPackage {
    pub name: String,
    pub version: String,
    pub integrity: String,
    pub entry: String,
}

#[derive(Debug, Default, Serialize, Deserialize)]
pub struct Lockfile {
    pub packages: BTreeMap<String, LockedPackage>,
}

#[derive(Debug, Deserialize)]
struct RegistryPackage {
    #[serde(default)]
    #[serde(rename = "dist-tags")]
    dist_tags: std::collections::HashMap<String, String>,
    #[serde(default)]
    versions: BTreeMap<String, RegistryVersion>,
}

#[derive(Debug, Deserialize)]
struct RegistryVersion {
    #[serde(default)]
    dist: Dist,
}

#[derive(Debug, Deserialize, Clone, Default)]
struct Dist {
    #[serde(default)]
    tarball: String,
    #[serde(default)]
    integrity: String,
}

#[derive(Debug, Deserialize, Default)]
#[allow(dead_code)]
struct PkgManifest {
    #[serde(default)]
    name: String,
    #[serde(default)]
    main: String,
    #[serde(default)]
    module: String,
    #[serde(default)]
    exports: serde_json::Value,
    #[serde(default)]
    dependencies: BTreeMap<String, String>,
}

pub struct VendorCache {
    root: PathBuf,
    client: reqwest::Client,
}

impl VendorCache {
    /// Cache at `root` (default `~/.cache/xencode/npm`).
    pub fn new(root: impl Into<PathBuf>) -> Self {
        Self {
            root: root.into(),
            client: reqwest::Client::builder()
                .user_agent(concat!("xencode-script/", env!("CARGO_PKG_VERSION")))
                .build()
                .expect("http client"),
        }
    }

    pub fn default_dir() -> PathBuf {
        std::env::var_os("XENCODE_NPM_CACHE")
            .map(PathBuf::from)
            .unwrap_or_else(|| {
                std::env::var_os("HOME")
                    .map(|h| PathBuf::from(h).join(".cache/xencode/npm"))
                    .unwrap_or_else(|| PathBuf::from("/tmp/xencode-npm"))
            })
    }

    /// Load `xen.lock` next to the entry script when present.
    pub fn load_lockfile(project_root: &Path) -> Option<Lockfile> {
        let path = project_root.join("xen.lock");
        let text = std::fs::read_to_string(path).ok()?;
        serde_json::from_str(&text).ok()
    }

    pub fn write_lockfile(project_root: &Path, lock: &Lockfile) -> anyhow::Result<()> {
        let path = project_root.join("xen.lock");
        std::fs::write(&path, serde_json::to_string_pretty(lock)?)
            .with_context(|| format!("writing {}", path.display()))
    }

    /// Prefetch every bare npm import reachable from `entry_source`.
    pub async fn prefetch(
        &self,
        entry_source: &str,
        project_root: &Path,
        perms: &PermissionState,
    ) -> anyhow::Result<PrefetchResult> {
        let lock = Self::load_lockfile(project_root);
        let mut out = PrefetchResult::default();
        let mut queue: Vec<(String, usize)> = collect_bare_imports(entry_source)
            .into_iter()
            .map(|s| (s, 0usize))
            .collect();
        let mut seen: Vec<String> = Vec::new();
        let mut lock_dirty = false;

        while let Some((spec, depth)) = queue.pop() {
            if depth > MAX_DEPTH || out.roots.len() >= MAX_PACKAGES {
                continue;
            }
            let base = package_base(&spec);
            if seen.contains(&base) {
                continue;
            }
            seen.push(base.clone());

            let resolved = self
                .resolve_package(&base, lock.as_ref(), perms)
                .await
                .with_context(|| format!("resolving npm package `{base}`"))?;
            out.roots.insert(base.clone(), resolved.root.clone());
            out.files
                .entry(base.clone())
                .or_insert(resolved.entry.clone());

            // subpath import: `pkg/dist/x.js` → vendored file
            if spec != base {
                let sub = spec[base.len() + 1..].to_string();
                for ext in ["", ".js", ".mjs", "/index.js", "/index.mjs"] {
                    let candidate = resolved.root.join(format!("{sub}{ext}"));
                    if candidate.is_file() {
                        out.files.insert(spec.clone(), candidate);
                        break;
                    }
                }
            }

            // queue the vendored package's own bare imports (pure-JS only)
            if depth < MAX_DEPTH {
                if let Ok(src) = std::fs::read_to_string(&resolved.entry) {
                    for dep in collect_bare_imports(&src) {
                        if !seen.contains(&package_base(&dep)) {
                            queue.push((dep, depth + 1));
                        }
                    }
                }
            }

            let key = format!("{}@{}", resolved.name, resolved.version);
            if !lock.as_ref().map(|l| l.packages.contains_key(&key)).unwrap_or(false) {
                lock_dirty = true;
            }
            out.vendored.push(LockedPackage {
                name: resolved.name,
                version: resolved.version,
                integrity: resolved.integrity,
                entry: resolved
                    .entry
                    .strip_prefix(&resolved.root)
                    .map(|p| p.to_string_lossy().to_string())
                    .unwrap_or_default(),
            });
        }

        if lock_dirty {
            let mut merged = lock.unwrap_or_default();
            for pkg in &out.vendored {
                merged
                    .packages
                    .entry(format!("{}@{}", pkg.name, pkg.version))
                    .or_insert_with(|| pkg.clone());
            }
            Self::write_lockfile(project_root, &merged)?;
        }

        Ok(out)
    }

    async fn resolve_package(
        &self,
        name: &str,
        lock: Option<&Lockfile>,
        perms: &PermissionState,
    ) -> anyhow::Result<Resolved> {
        perms
            .check_net("registry.npmjs.org")
            .map_err(|e| anyhow!("vendor cache needs net permission: {e}"))?;

        // 1. lockfile pin (offline-friendly)
        if let Some(lock) = lock {
            for (key, pkg) in &lock.packages {
                if pkg.name == name {
                    if let Some(hit) = self.cached_package(pkg).await? {
                        return Ok(hit);
                    }
                    // fall through to network with the pinned version
                    return self.download(name, Some(&pkg.version), &pkg.integrity).await;
                }
                let _ = key;
            }
        }

        // 2. registry lookup → latest satisfying version
        let url = format!("{}/{}", REGISTRY, name.replace('/', "%2f"));
        let resp = self
            .client
            .get(&url)
            .send()
            .await
            .with_context(|| format!("registry request `{name}`"))?
            .error_for_status()
            .with_context(|| format!("registry request `{name}`"))?;
        let meta: RegistryPackage = resp.json().await.with_context(|| format!("registry meta `{name}`"))?;
        let latest = meta.dist_tags.get("latest").cloned();
        let pick_version = latest.or_else(|| meta.versions.keys().last().cloned());
        let version = pick_version.ok_or_else(|| anyhow!("package `{name}` has no versions"))?;
        let integrity = meta
            .versions
            .get(&version)
            .map(|v| v.dist.integrity.clone())
            .unwrap_or_default();
        self.download(name, Some(&version), &integrity).await
    }

    /// Return the cached package if the directory + entry already exist.
    async fn cached_package(&self, pkg: &LockedPackage) -> anyhow::Result<Option<Resolved>> {
        let root = self.root.join(format!("{}@{}", pkg.name, pkg.version));
        let entry = root.join(&pkg.entry);
        if entry.is_file() {
            return Ok(Some(Resolved {
                name: pkg.name.clone(),
                version: pkg.version.clone(),
                integrity: pkg.integrity.clone(),
                root,
                entry,
            }));
        }
        Ok(None)
    }

    async fn download(
        &self,
        name: &str,
        version: Option<&str>,
        expected_integrity: &str,
    ) -> anyhow::Result<Resolved> {
        let url = format!("{}/{}", REGISTRY, name.replace('/', "%2f"));
        let resp = self
            .client
            .get(&url)
            .send()
            .await
            .with_context(|| format!("registry request `{name}`"))?
            .error_for_status()?;
        let meta: RegistryPackage = resp.json().await?;

        let version = match version {
            Some(v) => v.to_string(),
            None => meta
                .dist_tags
                .get("latest")
                .cloned()
                .or_else(|| meta.versions.keys().last().cloned())
                .ok_or_else(|| anyhow!("package `{name}` has no versions"))?,
        };
        let dist = meta
            .versions
            .get(&version)
            .map(|v| v.dist.clone())
            .ok_or_else(|| anyhow!("package `{name}` has no version {version}"))?;
        let integrity = if dist.integrity.is_empty() {
            expected_integrity.to_string()
        } else {
            dist.integrity
        };

        // tarball → sha256 verify against `sha512-<base64>` SRI
        let tarball_url = dist
            .tarball
            .rsplit_once('/')
            .map(|(_, file)| {
                // reconstruct registry-hosted tarball path (registry.npmjs.org)
                format!("{}/{}", REGISTRY, file)
            })
            .unwrap_or(dist.tarball.clone());
        let tar_bytes = self
            .client
            .get(&tarball_url)
            .send()
            .await
            .with_context(|| format!("tarball `{tarball_url}`"))?
            .error_for_status()?
            .bytes()
            .await?;

        if !integrity.is_empty() {
            let digest = integrity
                .strip_prefix("sha512-")
                .ok_or_else(|| anyhow!("unsupported integrity prefix in `{integrity}`"))?;
            verify_sha512(&tar_bytes, digest)
                .with_context(|| format!("tarball integrity for `{name}@{version}`"))?;
        }

        // extract package dir (strip the npm "package/" prefix)
        let pkg_dir = self.root.join(format!("{name}@{version}"));
        if pkg_dir.exists() {
            std::fs::remove_dir_all(&pkg_dir)?;
        }
        std::fs::create_dir_all(&pkg_dir)?;
        let gz = flate2::read::GzDecoder::new(tar_bytes.as_ref());
        let mut archive = tar::Archive::new(gz);
        for entry in archive.entries()? {
            let mut entry = entry?;
            let path = entry.path()?.to_path_buf();
            let rel = path
                .components()
                .skip(1) // drop "package/"
                .collect::<PathBuf>();
            if rel.as_os_str().is_empty() {
                continue;
            }
            let target = pkg_dir.join(&rel);
            if let Some(parent) = target.parent() {
                std::fs::create_dir_all(parent)?;
            }
            if entry.header().entry_type().is_file() {
                let mut buf = Vec::new();
                entry.read_to_end(&mut buf)?;
                std::fs::write(&target, buf)?;
            } else if entry.header().entry_type().is_symlink() {
                // skip symlinks (defensive: npm packages rarely need them)
                continue;
            } else {
                std::fs::create_dir_all(&target)?;
            }
        }

        // pick the ESM entry
        let manifest_path = pkg_dir.join("package.json");
        let manifest: PkgManifest = std::fs::read_to_string(&manifest_path)
            .ok()
            .and_then(|t| serde_json::from_str(&t).ok())
            .unwrap_or_default();
        let entry = pick_entry(&pkg_dir, &manifest)
            .ok_or_else(|| anyhow!("package `{name}@{version}` has no usable ESM entry"))?;

        Ok(Resolved {
            name: name.to_string(),
            version,
            integrity,
            root: pkg_dir.clone(),
            entry,
        })
    }

    /// Remove the whole cache (or one package).
    pub fn clean(&self, name: Option<&str>) -> anyhow::Result<usize> {
        match name {
            Some(n) => {
                let mut removed = 0;
                if let Ok(rd) = std::fs::read_dir(&self.root) {
                    for e in rd.flatten() {
                        if e.path().to_string_lossy().starts_with(&format!("{n}@")) {
                            std::fs::remove_dir_all(e.path())?;
                            removed += 1;
                        }
                    }
                }
                Ok(removed)
            }
            None => {
                if self.root.exists() {
                    std::fs::remove_dir_all(&self.root)?;
                }
                Ok(1)
            }
        }
    }

    /// List cached packages as `name@version` with total size in bytes.
    pub fn list(&self) -> Vec<(String, u64)> {
        let mut out = Vec::new();
        if let Ok(rd) = std::fs::read_dir(&self.root) {
            for e in rd.flatten() {
                if e.path().is_dir() {
                    let size = dir_size(&e.path());
                    out.push((e.file_name().to_string_lossy().to_string(), size));
                }
            }
        }
        out.sort();
        out
    }
}

struct Resolved {
    name: String,
    version: String,
    integrity: String,
    root: PathBuf,
    entry: PathBuf,
}

fn verify_sha512(data: &[u8], expected_b64: &str) -> anyhow::Result<()> {
    use base64::Engine;
    let decoded = crate::jsvalue::b64_decode(expected_b64).map_err(|e| anyhow!("integrity is not base64: {e}"))?;
    if decoded.len() == 64 {
        // standard SRI: sha512-<base64>
        let mut hasher = Sha512::new();
        hasher.update(data);
        let digest = hasher.finalize();
        let got = base64::engine::general_purpose::STANDARD.encode(digest);
        if got == expected_b64 {
            return Ok(());
        }
        anyhow::bail!("integrity mismatch: expected sha512 {expected_b64}, got {got}");
    }
    if decoded.len() == 32 {
        // sha256-<base64> also accepted
        let digest = Sha256::digest(data);
        let got = base64::engine::general_purpose::STANDARD.encode(digest);
        if got == expected_b64 {
            return Ok(());
        }
        anyhow::bail!("integrity mismatch: expected {expected_b64}, got {got}");
    }
    anyhow::bail!("unsupported integrity digest length {}", decoded.len())
}

/// package.json → ESM entry resolution order: `module` → `exports.import`
/// → `exports.` (string) → `main` → `index.js`.
fn pick_entry(root: &Path, manifest: &PkgManifest) -> Option<PathBuf> {
    let mut candidates: Vec<String> = Vec::new();
    if !manifest.module.is_empty() {
        candidates.push(manifest.module.clone());
    }
    match &manifest.exports {
        serde_json::Value::String(s) => candidates.push(s.clone()),
        serde_json::Value::Object(map) => {
            let dot = map.get(".");
            if let Some(dot) = dot {
                match dot {
                    serde_json::Value::String(s) => candidates.push(s.clone()),
                    serde_json::Value::Object(m) => {
                        if let Some(serde_json::Value::String(s)) = m.get("import") {
                            candidates.push(s.clone());
                        } else if let Some(serde_json::Value::String(s)) = m.get("default") {
                            candidates.push(s.clone());
                        }
                    }
                    _ => {}
                }
            }
            for key in ["./index.js"] {
                if let Some(serde_json::Value::String(s)) = map.get(key) {
                    candidates.push(s.clone());
                }
            }
        }
        _ => {}
    }
    if !manifest.main.is_empty() {
        candidates.push(manifest.main.clone());
    }
    candidates.push("index.js".to_string());

    for candidate in candidates {
        let p = root.join(candidate.trim_start_matches("./"));
        if p.is_file() {
            return Some(p);
        }
        for ext in [".js", ".mjs"] {
            let with_ext = PathBuf::from(format!("{}{ext}", p.to_string_lossy()));
            if with_ext.is_file() {
                return Some(with_ext);
            }
        }
    }
    None
}

/// The package name part of a specifier: `@scope/pkg/sub/x.js` → `@scope/pkg`.
pub fn package_base(spec: &str) -> String {
    let mut parts = spec.splitn(3, '/');
    let first = parts.next().unwrap_or(spec);
    if first.starts_with('@') {
        match parts.next() {
            Some(second) => format!("{first}/{second}"),
            None => first.to_string(),
        }
    } else {
        first.to_string()
    }
}

/// Extract bare import specifiers from JS/TS source (import/require/dynamic).
pub fn collect_bare_imports(source: &str) -> Vec<String> {
    static RE_FROM: std::sync::OnceLock<regex::Regex> = std::sync::OnceLock::new();
    static RE_PLAIN: std::sync::OnceLock<regex::Regex> = std::sync::OnceLock::new();
    static RE_REQUIRE: std::sync::OnceLock<regex::Regex> = std::sync::OnceLock::new();
    static RE_DYNAMIC: std::sync::OnceLock<regex::Regex> = std::sync::OnceLock::new();

    let re_from = RE_FROM.get_or_init(|| {
        regex::Regex::new(r#"from\s*["']([^"']+)["']"#).unwrap()
    });
    let re_plain = RE_PLAIN.get_or_init(|| {
        regex::Regex::new(r#"import\s*["']([^"']+)["']"#).unwrap()
    });
    let re_require = RE_REQUIRE.get_or_init(|| {
        regex::Regex::new(r#"require\(\s*["']([^"']+)["']\s*\)"#).unwrap()
    });
    let re_dynamic = RE_DYNAMIC.get_or_init(|| {
        regex::Regex::new(r#"import\(\s*["']([^"']+)["']\s*\)"#).unwrap()
    });

    let mut out = Vec::new();
    for caps in re_from.captures_iter(source) {
        out.push(caps[1].to_string());
    }
    for caps in re_plain.captures_iter(source) {
        out.push(caps[1].to_string());
    }
    for caps in re_require.captures_iter(source) {
        out.push(caps[1].to_string());
    }
    for caps in re_dynamic.captures_iter(source) {
        out.push(caps[1].to_string());
    }
    out.retain(|s| {
        !s.starts_with("xen/")
            && !s.starts_with("./")
            && !s.starts_with("../")
            && !s.starts_with('/')
            && crate::modules::NATIVE_BARE.iter().all(|(bare, _)| *bare != s)
            && !s.is_empty()
    });
    out.sort();
    out.dedup();
    out
}

fn dir_size(dir: &Path) -> u64 {
    let mut size = 0;
    if let Ok(rd) = std::fs::read_dir(dir) {
        for e in rd.flatten() {
            if let Ok(meta) = e.metadata() {
                if meta.is_dir() {
                    size += dir_size(&e.path());
                } else {
                    size += meta.len();
                }
            }
        }
    }
    size
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn package_base_extraction() {
        assert_eq!(package_base("ms"), "ms");
        assert_eq!(package_base("ms/lib/index.js"), "ms");
        assert_eq!(package_base("@scope/pkg"), "@scope/pkg");
        assert_eq!(package_base("@scope/pkg/dist/x.js"), "@scope/pkg");
    }

    #[test]
    fn collects_bare_imports_only() {
        let src = r#"
            import { parse } from "yaml";
            import "./local.js";
            import { join } from "path";
            import xen from "xen/http";
            export * from "lodash-es";
            const x = require("ms");
            import("chalk");
            from nowhere;
        "#;
        let specs = collect_bare_imports(src);
        // native-covered bare names (yaml) are resolved by built-ins, not vendored
        assert!(!specs.contains(&"yaml".to_string()));
        assert!(specs.contains(&"lodash-es".to_string()));
        assert!(specs.contains(&"ms".to_string()));
        assert!(specs.contains(&"chalk".to_string()));
        assert!(!specs.contains(&"path".to_string()));
        assert!(!specs.contains(&"xen/http".to_string()));
        assert!(!specs.iter().any(|s| s.starts_with('.')));
    }

    #[test]
    fn lockfile_roundtrip() {
        let lock = Lockfile {
            packages: [(
                "ms@2.1.3".to_string(),
                LockedPackage {
                    name: "ms".into(),
                    version: "2.1.3".into(),
                    integrity: "sha512-abc".into(),
                    entry: "index.js".into(),
                },
            )]
            .into_iter()
            .collect(),
        };
        let text = serde_json::to_string(&lock).unwrap();
        let back: Lockfile = serde_json::from_str(&text).unwrap();
        assert_eq!(back.packages["ms@2.1.3"].version, "2.1.3");
    }
}
