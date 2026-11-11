//! APK analysis (Phase 7.2) — AndroidManifest (AXML), DEX inventory, native
//! libs and signing-blob detection in one pass over the zip structure.
//!
//! Everything here is native: the archive is walked with the `zip` crate,
//! manifests go through [`crate::axml`], classes*.dex through [`crate::dex`].
//! Signing is detected structurally (META-INF cert members → v1; the
//! `APK Sig Block 42` block between entries and the central directory →
//! v2/v3/v3.1 id pairs). No DRM/circumvention logic lives here — this is a
//! read-only inspector for authorized, user-owned builds.

use std::collections::BTreeSet;
use std::io::{Cursor, Read};

use anyhow::{bail, Context, Result};
use serde::Serialize;

use crate::axml::{parse_axml, AttrValue, AxmlNode};
use crate::dex::{parse_dex, DexClass, MAX_CLASSES_LISTED};

/// APK analysis options.
#[derive(Debug, Clone, Copy)]
pub struct ApkOpts {
    /// Per-dex class listing cap (counts stay exact regardless).
    pub max_classes_per_dex: usize,
}

impl Default for ApkOpts {
    fn default() -> Self {
        ApkOpts { max_classes_per_dex: 512 }
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct ApkInfo {
    pub package: String,
    pub version_name: Option<String>,
    pub version_code: Option<i64>,
    pub min_sdk: Option<i64>,
    pub target_sdk: Option<i64>,
    pub compile_sdk: Option<i64>,
    pub app_label: Option<String>,
    pub debuggable: Option<bool>,
    /// `uses-permission` / `uses-permission-sdk-23` names in manifest order.
    pub permissions: Vec<String>,
    pub components: Vec<ApkComponent>,
    /// One entry per classes*.dex member (name, size, counts, class listing).
    pub dex_files: Vec<DexFileInfo>,
    /// `lib/<abi>/*.so` members.
    pub native_libs: Vec<NativeLib>,
    /// ABIs that ship native code, sorted.
    pub abis: Vec<String>,
    pub signing: ApkSigning,
    /// Zip member count.
    pub entries: u64,
    /// Sum of uncompressed member sizes.
    pub uncompressed_size: u64,
    pub has_resources_arsc: bool,
    /// `assets/` member count.
    pub asset_files: u64,
}

#[derive(Debug, Clone, Serialize)]
pub struct ApkComponent {
    pub name: String,
    /// activity | service | receiver | provider
    pub kind: &'static str,
    pub exported: Option<bool>,
    /// Has an intent-filter with MAIN action + LAUNCHER category.
    pub launcher: bool,
    /// Native methods declared by any dex class with this component's name
    /// (cheap JNI surface hint, matched by descriptor).
    pub native_methods: usize,
}

#[derive(Debug, Clone, Serialize)]
pub struct DexFileInfo {
    /// Zip member name (`classes.dex`, `classes2.dex`, …).
    pub name: String,
    pub size: u64,
    pub version: String,
    pub class_count: u32,
    pub method_count: u32,
    pub string_count: u32,
    /// Class listing (capped by [`ApkOpts::max_classes_per_dex`]).
    pub classes: Vec<DexClass>,
}

#[derive(Debug, Clone, Serialize)]
pub struct NativeLib {
    pub abi: String,
    pub path: String,
    pub size: u64,
}

#[derive(Debug, Clone, Default, Serialize)]
pub struct ApkSigning {
    /// v1 (JAR) signing: META-INF/*.RSA|DSA|EC members present.
    pub v1: bool,
    /// v2: APK Signing Block with the 0x7109871a id pair.
    pub v2: bool,
    /// v3: id pair 0xf05368c0.
    pub v3: bool,
    /// v3.1: id pair 0x1b93ad61.
    pub v3_1: bool,
    /// Cert/signature member names (v1).
    pub cert_files: Vec<String>,
}

/// Structural sniff: a ZIP that carries AndroidManifest.xml or classes*.dex.
pub fn is_apk(bytes: &[u8]) -> bool {
    if bytes.len() < 4 || &bytes[..4] != b"PK\x03\x04" {
        return false;
    }
    apk_or_ipa_member_hit(bytes, |name| {
        name == "AndroidManifest.xml"
            || name == "classes.dex"
            || (name.starts_with("classes") && name.ends_with(".dex"))
    })
}

/// Cheap EOCD scan: walk central-directory file headers and run `pred` on
/// each member name without inflating anything.
fn apk_or_ipa_member_hit(bytes: &[u8], mut pred: impl FnMut(&str) -> bool) -> bool {
    // Central-directory header signature 0x02014b50; scan is bounded so a
    // non-zip blob bails fast.
    if bytes.len() < 22 {
        return false;
    }
    let mut hits = 0usize;
    let mut i = 0usize;
    let end = bytes.len().saturating_sub(4);
    while i < end && hits < 4096 {
        if &bytes[i..i + 4] == b"PK\x01\x02" {
            // name_len@28, extra_len@30, comment_len@32, name@46
            if i + 46 <= bytes.len() {
                let name_len = u16::from_le_bytes([bytes[i + 28], bytes[i + 29]]) as usize;
                let extra_len = u16::from_le_bytes([bytes[i + 30], bytes[i + 31]]) as usize;
                let comment_len = u16::from_le_bytes([bytes[i + 32], bytes[i + 33]]) as usize;
                let start = i + 46;
                let stop = start + name_len;
                if stop <= bytes.len() {
                    if let Ok(name) = std::str::from_utf8(&bytes[start..stop]) {
                        if pred(name) {
                            return true;
                        }
                    }
                }
                let _ = extra_len;
                let _ = comment_len;
                hits += 1;
            }
        }
        i += 1;
    }
    false
}

/// Full APK analysis. `bytes` must be the whole APK file (the signing block
/// is detected in the raw bytes, not through the zip layer).
pub fn analyze_apk(bytes: &[u8], opts: ApkOpts) -> Result<ApkInfo> {
    if bytes.len() as u64 > crate::MAX_INPUT_BYTES {
        bail!("APK exceeds the {} analysis cap", crate::MAX_INPUT_BYTES);
    }
    let mut zip = zip::ZipArchive::new(Cursor::new(bytes))
        .context("not a readable ZIP archive (APKs are zips)")?;

    // --- manifest ----------------------------------------------------------
    let manifest_bytes = read_member(&mut zip, "AndroidManifest.xml")
        .ok_or_else(|| anyhow::anyhow!("no AndroidManifest.xml member — not an APK"))?;
    let manifest = parse_axml(&manifest_bytes)
        .context("AndroidManifest.xml is not a parseable binary XML")?;

    let mut info = ApkInfo {
        package: manifest.attr_str("package").unwrap_or_default(),
        version_name: manifest.attr_str("versionName"),
        version_code: manifest.attr_int("versionCode"),
        compile_sdk: manifest.attr_int("compileSdkVersion"),
        min_sdk: None,
        target_sdk: None,
        app_label: None,
        debuggable: None,
        permissions: Vec::new(),
        components: Vec::new(),
        dex_files: Vec::new(),
        native_libs: Vec::new(),
        abis: Vec::new(),
        signing: ApkSigning::default(),
        entries: zip.len() as u64,
        uncompressed_size: 0,
        has_resources_arsc: false,
        asset_files: 0,
    };

    // minSdk/targetSdk live on uses-sdk (aapt2 folds compileSdk into the
    // manifest attrs; older builds carry uses-sdk).
    let mut sdk_nodes = Vec::new();
    manifest.find_all("uses-sdk", &mut sdk_nodes);
    if let Some(sdk) = sdk_nodes.first() {
        info.min_sdk = sdk.attr_int("minSdkVersion");
        info.target_sdk = sdk.attr_int("targetSdkVersion");
    }
    // Modern AAPT2 output embeds them as manifest attrs.
    if info.min_sdk.is_none() {
        info.min_sdk = manifest.attr_int("minSdkVersion");
    }
    if info.target_sdk.is_none() {
        info.target_sdk = manifest.attr_int("targetSdkVersion");
    }

    // Permissions, in manifest order.
    let mut perm_nodes = Vec::new();
    manifest.find_all("uses-permission", &mut perm_nodes);
    manifest.find_all("uses-permission-sdk-23", &mut perm_nodes);
    for p in &perm_nodes {
        if let Some(name) = p.attr_str("name") {
            info.permissions.push(name);
        }
    }

    // Application label/debuggable/icon.
    if let Some(app) = manifest.children_named("application").first() {
        info.app_label = app.attr_str("label");
        info.debuggable = app.attr_bool("debuggable");
    }

    // Components: activity / service / receiver / provider.
    let dex_native = collect_dex_native(&mut zip, opts, &mut info)?;
    if let Some(app) = manifest.children_named("application").first() {
        for kind in ["activity", "service", "receiver", "provider"] {
            for node in app.children_named(kind) {
                let name = node.attr_str("name").unwrap_or_default();
                if name.is_empty() {
                    continue;
                }
                let descriptor = format!("L{};", name.replace('.', "/"));
                info.components.push(ApkComponent {
                    name: name.clone(),
                    kind,
                    exported: match node.attr("exported") {
                        Some(AttrValue::Bool(b)) => Some(*b),
                        _ => None,
                    },
                    launcher: is_launcher(node),
                    native_methods: dex_native.get(&descriptor).copied().unwrap_or(0),
                });
            }
        }
    }

    // --- zip-wide stats ------------------------------------------------------
    let mut abi_set = BTreeSet::new();
    for i in 0..zip.len() {
        let Ok(file) = zip.by_index(i) else { continue };
        let name = file.name().to_string();
        info.uncompressed_size += file.size();
        if name == "resources.arsc" {
            info.has_resources_arsc = true;
        } else if name.starts_with("assets/") {
            info.asset_files += 1;
        } else if let Some(rest) = name.strip_prefix("lib/") {
            if let Some((abi, base)) = rest.split_once('/') {
                if base.ends_with(".so") {
                    abi_set.insert(abi.to_string());
                    info.native_libs.push(NativeLib {
                        abi: abi.to_string(),
                        path: name.clone(),
                        size: file.size(),
                    });
                }
            }
        }
    }
    info.abis = abi_set.into_iter().collect();

    // --- signing -------------------------------------------------------------
    info.signing = detect_signing(bytes, &mut zip)?;

    Ok(info)
}

/// MAIN action + LAUNCHER category → launcher activity.
fn is_launcher(activity: &AxmlNode) -> bool {
    let mut has_main = false;
    let mut has_launcher_cat = false;
    for filter in activity.children_named("intent-filter") {
        for action in filter.children_named("action") {
            if action.attr_str("name").as_deref() == Some("android.intent.action.MAIN") {
                has_main = true;
            }
        }
        for cat in filter.children_named("category") {
            if cat.attr_str("name").as_deref() == Some("android.intent.category.LAUNCHER") {
                has_launcher_cat = true;
            }
        }
    }
    has_main && has_launcher_cat
}

/// Parse every classes*.dex member; returns a descriptor → native-method
/// count map (for the component hint) while filling `info.dex_files`.
fn collect_dex_native(
    zip: &mut zip::ZipArchive<Cursor<&[u8]>>,
    opts: ApkOpts,
    info: &mut ApkInfo,
) -> Result<std::collections::HashMap<String, usize>> {
    let mut names: Vec<String> = Vec::new();
    for i in 0..zip.len() {
        let Ok(file) = zip.by_index(i) else { continue };
        let name = file.name().to_string();
        if name == "classes.dex" || (name.starts_with("classes") && name.ends_with(".dex")) {
            names.push(name);
        }
    }
    names.sort();
    let mut native = std::collections::HashMap::new();
    for name in names {
        let mut raw;
        {
            let mut f = zip
                .by_name(&name)
                .with_context(|| format!("re-open {name}"))?;
            raw = Vec::with_capacity(f.size() as usize);
            f.read_to_end(&mut raw)?;
        }
        let dex = parse_dex(&raw).with_context(|| format!("parse {name}"))?;
        for cls in &dex.classes {
            if !cls.native_methods.is_empty() {
                *native.entry(cls.descriptor.clone()).or_insert(0) += cls.native_methods.len();
            }
        }
        info.dex_files.push(DexFileInfo {
            name,
            size: dex.file_size as u64,
            version: dex.version.clone(),
            class_count: dex.class_count,
            method_count: dex.method_count,
            string_count: dex.string_count,
            classes: dex
                .classes
                .into_iter()
                .take(opts.max_classes_per_dex.min(MAX_CLASSES_LISTED))
                .collect(),
        });
    }
    Ok(native)
}

/// v1 = META-INF cert members; v2/v3/v3.1 = APK Signing Block id pairs.
fn detect_signing(
    bytes: &[u8],
    zip: &mut zip::ZipArchive<Cursor<&[u8]>>,
) -> Result<ApkSigning> {
    let mut signing = ApkSigning::default();
    for i in 0..zip.len() {
        let Ok(file) = zip.by_index(i) else { continue };
        let name = file.name().to_string();
        if let Some(base) = name.strip_prefix("META-INF/") {
            let upper = base.to_ascii_uppercase();
            if upper.ends_with(".RSA") || upper.ends_with(".DSA") || upper.ends_with(".EC") {
                signing.v1 = true;
                signing.cert_files.push(name);
            }
        }
    }
    if let Some((ids, _off)) = locate_signing_block(bytes) {
        signing.v2 = ids.contains(&0x7109_871a);
        signing.v3 = ids.contains(&0xf053_68c0);
        signing.v3_1 = ids.contains(&0x1b93_ad61);
    }
    Ok(signing)
}

/// Locate the APK Signing Block: `size8 | id-value pairs | size8 | magic16`
/// immediately before the central directory; magic is "APK Sig Block 42".
/// Returns (block ids, block start offset).
fn locate_signing_block(bytes: &[u8]) -> Option<(Vec<u32>, usize)> {
    const MAGIC: &[u8; 16] = b"APK Sig Block 42";
    let needle = bytes
        .windows(16)
        .rposition(|w| w == MAGIC)?; // last occurrence = the real block
    if needle < 8 || needle + 16 > bytes.len() {
        return None;
    }
    let block_size = u64::from_le_bytes(bytes[needle - 8..needle].try_into().ok()?) as usize;
    // Block start = magic_end - (block_size + 8 footer... per spec the
    // leading size field is excluded from `block_size`, and the trailing
    // size field mirrors it).
    let magic_end = needle + 16;
    let start = magic_end.checked_sub(block_size + 8)?;
    if start + 8 > bytes.len() {
        return None;
    }
    let lead_size =
        u64::from_le_bytes(bytes[start..start + 8].try_into().ok()?) as usize;
    if lead_size != block_size {
        return None; // mismatched footer/header → not a real signing block
    }
    // Walk id-value pairs between start+8 and needle-8.
    let mut ids = Vec::new();
    let mut cur = start + 8;
    let end = needle - 8;
    while cur + 12 <= end && ids.len() < 32 {
        let pair_len =
            u64::from_le_bytes(bytes[cur..cur + 8].try_into().ok()?) as usize;
        if pair_len < 4 || cur + 8 + pair_len > end {
            break;
        }
        let id = u32::from_le_bytes(bytes[cur + 8..cur + 12].try_into().ok()?);
        ids.push(id);
        cur += 8 + pair_len;
    }
    Some((ids, start))
}

fn read_member(zip: &mut zip::ZipArchive<Cursor<&[u8]>>, name: &str) -> Option<Vec<u8>> {
    let mut f = zip.by_name(name).ok()?;
    let mut out = Vec::with_capacity(f.size() as usize);
    f.read_to_end(&mut out).ok()?;
    Some(out)
}

// ------------------------------------------------------------------ tests --

#[cfg(test)]
mod tests {
    use super::*;
    use crate::axml::fixture::manifest_axml;
    use crate::dex::tests::build_dex;

    /// Assemble a full APK zip: manifest + dex + native lib + cert + arsc.
    fn build_apk() -> Vec<u8> {
        let mut w = zip::ZipWriter::new(Cursor::new(Vec::new()));
        let add = |w: &mut zip::ZipWriter<Cursor<Vec<u8>>>, name: &str, data: &[u8]| {
            w.start_file(
                name,
                zip::write::SimpleFileOptions::default()
                    .compression_method(zip::CompressionMethod::Deflated),
            )
            .unwrap();
            std::io::Write::write_all(w, data).unwrap();
        };
        add(&mut w, "AndroidManifest.xml", &manifest_axml(false));
        let dex = build_dex();
        add(&mut w, "classes.dex", &dex);
        add(&mut w, "lib/arm64-v8a/libxen.so", b"ELF-fake-native-lib");
        add(&mut w, "lib/x86_64/libxen.so", b"ELF-fake-native-lib-64");
        add(&mut w, "META-INF/CERT.RSA", b"fake-cert");
        add(&mut w, "META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n");
        add(&mut w, "resources.arsc", b"\x02\x00\x1c\x00fake-arsc");
        add(&mut w, "assets/data.bin", b"asset-bytes");
        let mut c = w.finish().unwrap().into_inner();
        // v2 signing block: drop the magic + id pair just before the EOCD so
        // locate_signing_block() finds a well-formed block.
        let block = {
            let mut b = Vec::new();
            b.extend_from_slice(&4u64.to_le_bytes()); // pair len = id (4 bytes)
            b.extend_from_slice(&0x7109_871au32.to_le_bytes()); // v2 id
            b
        };
        let mut sig = Vec::new();
        sig.extend_from_slice(&((block.len() + 24) as u64).to_le_bytes()); // leading size
        sig.extend_from_slice(&block);
        sig.extend_from_slice(&((block.len() + 24) as u64).to_le_bytes()); // trailing size
        sig.extend_from_slice(b"APK Sig Block 42");
        c.extend_from_slice(&sig);
        c
    }

    #[test]
    fn apk_end_to_end() {
        let bytes = build_apk();
        assert!(is_apk(&bytes));
        let info = analyze_apk(&bytes, ApkOpts::default()).expect("analyzes");

        assert_eq!(info.package, "com.xen.demo");
        assert_eq!(info.version_name.as_deref(), Some("0.7.0"));
        assert_eq!(info.version_code, Some(7));
        assert_eq!(info.min_sdk, None); // fixture manifest has no uses-sdk
        assert_eq!(info.app_label.as_deref(), Some("XenDemo"));
        assert_eq!(info.debuggable, Some(false));

        assert_eq!(info.permissions.len(), 2);
        assert_eq!(info.permissions[0], "android.permission.INTERNET");
        assert_eq!(info.permissions[1], "android.permission.CAMERA");

        // components: 1 activity (launcher), 1 service, 1 receiver
        assert_eq!(info.components.len(), 3);
        let act = info.components.iter().find(|c| c.kind == "activity").unwrap();
        assert_eq!(act.name, "com.xen.demo.App");
        assert!(act.launcher);
        assert_eq!(act.exported, Some(true));
        assert!(info.components.iter().any(|c| c.kind == "service"));
        assert!(info.components.iter().any(|c| c.kind == "receiver"));

        // dex: 2 classes, 4 methods; App declares one native method
        assert_eq!(info.dex_files.len(), 1);
        let dex = &info.dex_files[0];
        assert_eq!(dex.name, "classes.dex");
        assert_eq!(dex.class_count, 2);
        assert_eq!(dex.method_count, 4);
        let app_cls = dex
            .classes
            .iter()
            .find(|c| c.descriptor == "Lcom/xen/demo/App;")
            .expect("App class listed");
        assert_eq!(app_cls.native_methods, vec!["attachBaseContext".to_string()]);
        let act = info.components.iter().find(|c| c.kind == "activity").unwrap();
        assert_eq!(act.native_methods, 1, "component hint matches App class");

        // native libs + abis
        assert_eq!(info.abis, vec!["arm64-v8a".to_string(), "x86_64".to_string()]);
        assert_eq!(info.native_libs.len(), 2);
        assert!(info
            .native_libs
            .iter()
            .any(|l| l.abi == "arm64-v8a" && l.path == "lib/arm64-v8a/libxen.so"));

        // stats + signing
        assert!(info.has_resources_arsc);
        assert_eq!(info.asset_files, 1);
        assert!(info.signing.v1, "META-INF/CERT.RSA → v1");
        assert!(info.signing.v2, "APK Sig Block 42 with 0x7109871a → v2");
        assert!(!info.signing.v3);
        assert!(info.signing.cert_files.contains(&"META-INF/CERT.RSA".to_string()));
        assert!(info.entries >= 8);
        assert!(info.uncompressed_size > 0);
    }

    #[test]
    fn apk_rejects_non_apk() {
        assert!(!is_apk(b"not a zip"));
        assert!(!is_apk(b"PK\x03\x04short"));
        // a zip without a manifest is not an APK
        let mut w = zip::ZipWriter::new(Cursor::new(Vec::new()));
        w.start_file("readme.txt", zip::write::SimpleFileOptions::default()).unwrap();
        std::io::Write::write_all(&mut w, b"hi").unwrap();
        let zip_bytes = w.finish().unwrap().into_inner();
        assert!(!is_apk(&zip_bytes));
        assert!(analyze_apk(&zip_bytes, ApkOpts::default()).is_err());
    }

    #[test]
    fn signing_block_ids_v3() {
        // v3-only block: id 0xf05368c0.
        let mut w = zip::ZipWriter::new(Cursor::new(Vec::new()));
        w.start_file("AndroidManifest.xml", zip::write::SimpleFileOptions::default()).unwrap();
        std::io::Write::write_all(&mut w, &manifest_axml(false)).unwrap();
        let mut c = w.finish().unwrap().into_inner();
        let block = {
            let mut b = Vec::new();
            b.extend_from_slice(&4u64.to_le_bytes()); // pair len = id (4 bytes)
            b.extend_from_slice(&0xf053_68c0u32.to_le_bytes()); // v3 id
            b
        };
        let total = block.len() + 24;
        c.extend_from_slice(&(total as u64).to_le_bytes());
        c.extend_from_slice(&block);
        c.extend_from_slice(&(total as u64).to_le_bytes());
        c.extend_from_slice(b"APK Sig Block 42");
        let info = analyze_apk(&c, ApkOpts::default()).expect("analyzes");
        assert!(info.signing.v3);
        assert!(!info.signing.v2);
        assert!(!info.signing.v1);
    }
}
