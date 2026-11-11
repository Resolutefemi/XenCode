//! IPA analysis (Phase 7.2) — iOS app bundles are ZIP archives with a
//! `Payload/<App>.app/` directory: an `Info.plist`, the embedded Mach-O,
//! frameworks, extensions and (for dev builds) a provisioning profile.
//!
//! The plist goes through [`crate::plist`]; the embedded binary through the
//! existing 7.1 analyzer ([`crate::analyzer::analyze_bytes`]) so arch/
//! sections/symbols come from the same code path as `xencode re info`.

use std::io::{Cursor, Read};

use anyhow::{bail, Context, Result};
use serde::Serialize;

use crate::analyzer::{analyze_bytes, BinaryInfo};
use crate::plist::parse_plist;

/// IPA analysis options.
#[derive(Debug, Clone, Copy)]
pub struct IpaOpts {
    /// Also analyze the embedded Mach-O (default on; the binary can be
    /// large — pass `false` when only bundle metadata is needed).
    pub analyze_binary: bool,
}

impl Default for IpaOpts {
    fn default() -> Self {
        IpaOpts { analyze_binary: true }
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct IpaInfo {
    /// `Payload/Xen.app` — the .app directory inside the archive.
    pub app_dir: String,
    pub bundle_id: Option<String>,
    pub display_name: Option<String>,
    /// `CFBundleShortVersionString` (marketing version).
    pub version: Option<String>,
    /// `CFBundleVersion` (build number).
    pub build_version: Option<String>,
    pub min_os_version: Option<String>,
    pub executable: Option<String>,
    /// Architectures of the embedded binary (thin → one, universal → many).
    pub architectures: Vec<String>,
    /// Full 7.1 analysis of the embedded binary (when requested and parseable).
    pub binary: Option<BinaryInfo>,
    /// Embedded framework bundle names (`Frameworks/*.framework`).
    pub frameworks: Vec<String>,
    /// Extension/appex bundle names (`PlugIns/*.appex`).
    pub plugins: Vec<String>,
    /// `embedded.mobileprovision` present (dev / ad-hoc builds).
    pub provisioned: bool,
    /// `_CodeSignature/CodeResources` present.
    pub code_signature: bool,
    /// Zip member count.
    pub entries: u64,
    /// Sum of uncompressed member sizes.
    pub uncompressed_size: u64,
}

/// Structural sniff: a ZIP carrying `Payload/*.app/Info.plist`.
pub fn is_ipa(bytes: &[u8]) -> bool {
    if bytes.len() < 4 || &bytes[..4] != b"PK\x03\x04" {
        return false;
    }
    let mut hit = false;
    scan_members(bytes, |name| {
        if !hit
            && name.starts_with("Payload/")
            && name.contains(".app/")
            && name.ends_with("Info.plist")
        {
            hit = true;
        }
    });
    hit
}

/// Walk central-directory member names without inflating (shared shape with
/// [`crate::apk::is_apk`] — bounded scan, stops after 4096 entries).
fn scan_members(bytes: &[u8], mut visit: impl FnMut(&str)) {
    if bytes.len() < 46 {
        return;
    }
    let mut seen = 0usize;
    let mut i = 0usize;
    let end = bytes.len().saturating_sub(4);
    while i < end && seen < 4096 {
        if &bytes[i..i + 4] == b"PK\x01\x02" && i + 46 <= bytes.len() {
            let name_len = u16::from_le_bytes([bytes[i + 28], bytes[i + 29]]) as usize;
            let start = i + 46;
            let stop = start + name_len;
            if stop <= bytes.len() {
                if let Ok(name) = std::str::from_utf8(&bytes[start..stop]) {
                    visit(name);
                }
            }
            seen += 1;
        }
        i += 1;
    }
}

/// Full IPA analysis over the raw archive bytes.
pub fn analyze_ipa(bytes: &[u8], opts: IpaOpts) -> Result<IpaInfo> {
    if bytes.len() as u64 > crate::MAX_INPUT_BYTES {
        bail!("IPA exceeds the {} analysis cap", crate::MAX_INPUT_BYTES);
    }
    let mut zip = zip::ZipArchive::new(Cursor::new(bytes))
        .context("not a readable ZIP archive (IPAs are zips)")?;

    // Find the .app dir.
    let mut app_dir: Option<String> = None;
    for i in 0..zip.len() {
        let Ok(f) = zip.by_index(i) else { continue };
        let name = f.name().to_string();
        let mut parts = name.split('/');
        if parts.next() == Some("Payload") {
            if let Some(app) = parts.next() {
                if app.ends_with(".app") {
                    app_dir = Some(format!("Payload/{app}"));
                    break;
                }
            }
        }
    }
    let app_dir = app_dir.ok_or_else(|| anyhow::anyhow!("no Payload/*.app directory — not an IPA"))?;

    // Info.plist (binary or XML).
    let plist_name = format!("{app_dir}/Info.plist");
    let plist_bytes = read_member(&mut zip, &plist_name)
        .ok_or_else(|| anyhow::anyhow!("no {plist_name} — not a valid IPA bundle"))?;
    let plist = parse_plist(&plist_bytes)
        .with_context(|| format!("{plist_name} is not a parseable property list"))?;
    let dict = plist
        .as_dict()
        .ok_or_else(|| anyhow::anyhow!("{plist_name} top level is not a dict"))?;
    let get = |k: &str| -> Option<String> {
        dict.get(k).and_then(|v| v.as_str()).map(|s| s.to_string())
    };

    let mut info = IpaInfo {
        app_dir: app_dir.clone(),
        bundle_id: get("CFBundleIdentifier"),
        display_name: get("CFBundleDisplayName").or_else(|| get("CFBundleName")),
        version: get("CFBundleShortVersionString"),
        build_version: get("CFBundleVersion"),
        min_os_version: get("MinimumOSVersion"),
        executable: get("CFBundleExecutable"),
        architectures: Vec::new(),
        binary: None,
        frameworks: Vec::new(),
        plugins: Vec::new(),
        provisioned: false,
        code_signature: false,
        entries: zip.len() as u64,
        uncompressed_size: 0,
    };

    // Embedded binary + archive stats.
    let exec = info.executable.clone().unwrap_or_else(|| {
        // Xcode defaults the executable name to the bundle basename.
        app_dir
            .rsplit('/')
            .next()
            .and_then(|b| b.strip_suffix(".app"))
            .unwrap_or_default()
            .to_string()
    });
    let bin_path = format!("{app_dir}/{exec}");
    let mut bin_bytes: Option<Vec<u8>> = None;
    for i in 0..zip.len() {
        let Ok(mut f) = zip.by_index(i) else { continue };
        let name = f.name().to_string();
        info.uncompressed_size += f.size();
        if name == format!("{app_dir}/embedded.mobileprovision") {
            info.provisioned = true;
        } else if name == format!("{app_dir}/_CodeSignature/CodeResources") {
            info.code_signature = true;
        } else if let Some(rest) = name
            .strip_prefix(&format!("{app_dir}/Frameworks/"))
        {
            if let Some(base) = rest.split('/').next() {
                if base.ends_with(".framework") && !info.frameworks.contains(&base.to_string()) {
                    info.frameworks.push(base.to_string());
                }
            }
        } else if let Some(rest) = name.strip_prefix(&format!("{app_dir}/PlugIns/")) {
            if let Some(base) = rest.split('/').next() {
                if base.ends_with(".appex") && !info.plugins.contains(&base.to_string()) {
                    info.plugins.push(base.to_string());
                }
            }
        } else if name == bin_path {
            let mut raw = Vec::with_capacity(f.size() as usize);
            f.read_to_end(&mut raw)?;
            bin_bytes = Some(raw);
        }
    }
    info.frameworks.sort();

    if opts.analyze_binary {
        if let Some(raw) = bin_bytes {
            match analyze_bytes(&raw) {
                Ok(binfo) => {
                    info.architectures = vec![binfo.arch.to_string()];
                    info.binary = Some(binfo);
                }
                Err(_) => {
                    // Fat binaries / odd loads — report the failure softly:
                    // the bundle analysis stays valid, arch list stays empty.
                    info.architectures = vec!["universal (thin parse failed)".to_string()];
                }
            }
        }
    }

    Ok(info)
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

    /// Binary plist fixture (reuses the plist test builder shape).
    fn info_plist() -> Vec<u8> {
        // dict {"CFBundleIdentifier":"com.xen.ios","CFBundleExecutable":"Xen",
        //       "CFBundleShortVersionString":"2.1.0","CFBundleVersion":"415",
        //       "MinimumOSVersion":"15.0","CFBundleDisplayName":"Xen"}
        fn s(text: &str) -> Vec<u8> {
            assert!(text.len() < 16, "fixture strings must be short");
            let mut v = vec![0x50 | text.len() as u8];
            v.extend_from_slice(text.as_bytes());
            v
        }
        // Keys are long (CFBundleShortVersionString = 25 chars) → use the
        // escaped-length encoding.
        fn long_s(text: &str) -> Vec<u8> {
            let mut v = vec![0x5f, 0x11];
            v.extend_from_slice(&(text.len() as u16).to_be_bytes());
            v.extend_from_slice(text.as_bytes());
            v
        }
        let key_id = long_s("CFBundleIdentifier");
        let val_id = s("com.xen.ios");
        let key_exec = long_s("CFBundleExecutable");
        let val_exec = s("Xen");
        let key_ver = long_s("CFBundleShortVersionString");
        let val_ver = s("2.1.0");
        let key_build = long_s("CFBundleVersion");
        let val_build = s("415");
        let key_min = long_s("MinimumOSVersion");
        let val_min = s("15.0");
        let key_name = long_s("CFBundleDisplayName");
        let val_name = s("Xen");
        let dict = {
            let mut v = vec![0xd6]; // dict of 6 (0xD0 | 6)
            v.extend_from_slice(&[0x02, 0x04, 0x06, 0x08, 0x0a, 0x0c]); // keys
            v.extend_from_slice(&[0x03, 0x05, 0x07, 0x09, 0x0b, 0x0d]); // values
            v
        };
        let objects: Vec<Vec<u8>> = vec![
            vec![0x00],
            dict,
            key_id,
            val_id,
            key_exec,
            val_exec,
            key_ver,
            val_ver,
            key_build,
            val_build,
            key_min,
            val_min,
            key_name,
            val_name,
        ];
        let mut body = b"bplist00".to_vec();
        let mut offsets = Vec::new();
        let mut pos = body.len() as u64;
        for o in &objects {
            offsets.push(pos);
            pos += o.len() as u64;
            body.extend_from_slice(o);
        }
        let table_off = body.len() as u64;
        for off in &offsets {
            body.extend_from_slice(&(*off as u16).to_be_bytes());
        }
        let mut trailer = vec![0u8; 6];
        trailer.push(2); // offset size
        trailer.push(1); // ref size
        trailer.extend_from_slice(&(objects.len() as u64).to_be_bytes());
        trailer.extend_from_slice(&1u64.to_be_bytes());
        trailer.extend_from_slice(&table_off.to_be_bytes());
        body.extend_from_slice(&trailer);
        body
    }

    /// Minimal Mach-O arm64 executable — byte-for-byte the same shape as the
    /// 7.1 e2e fixture (`__TEXT` segment, LC_MAIN at 0x200, `ret` in body).
    fn macho_binary() -> Vec<u8> {
        let mut f = vec![0u8; 0x220];
        f[0..4].copy_from_slice(&[0xcf, 0xfa, 0xed, 0xfe]); // magic (LE)
        f[4..8].copy_from_slice(&0x0100_000cu32.to_le_bytes()); // CPU_TYPE_ARM64
        f[8..12].copy_from_slice(&0u32.to_le_bytes()); // subtype
        f[12..16].copy_from_slice(&2u32.to_le_bytes()); // MH_EXECUTE
        f[16..20].copy_from_slice(&2u32.to_le_bytes()); // ncmds
        f[20..24].copy_from_slice(&176u32.to_le_bytes()); // sizeofcmds
        f[24..28].copy_from_slice(&0u32.to_le_bytes()); // flags

        let c = 0x20; // LC_SEGMENT_64 __TEXT (cmdsize 152 = 72 + one section)
        f[c..c + 4].copy_from_slice(&0x19u32.to_le_bytes());
        f[c + 4..c + 8].copy_from_slice(&152u32.to_le_bytes());
        f[c + 8..c + 14].copy_from_slice(b"__TEXT");
        f[c + 24..c + 32].copy_from_slice(&0x1_0000_0000u64.to_le_bytes()); // vmaddr
        f[c + 32..c + 40].copy_from_slice(&0x4000u64.to_le_bytes()); // vmsize
        f[c + 40..c + 48].copy_from_slice(&0u64.to_le_bytes()); // fileoff
        f[c + 48..c + 56].copy_from_slice(&0x220u64.to_le_bytes()); // filesize
        f[c + 56..c + 60].copy_from_slice(&7u32.to_le_bytes()); // maxprot RWX
        f[c + 60..c + 64].copy_from_slice(&5u32.to_le_bytes()); // initprot R+X
        f[c + 64..c + 68].copy_from_slice(&1u32.to_le_bytes()); // nsects

        let sc = c + 72; // section_64 __text
        f[sc..sc + 6].copy_from_slice(b"__text");
        f[sc + 16..sc + 22].copy_from_slice(b"__TEXT");
        f[sc + 32..sc + 40].copy_from_slice(&0x1_0000_0000u64.to_le_bytes()); // addr
        f[sc + 40..sc + 48].copy_from_slice(&8u64.to_le_bytes()); // size
        f[sc + 48..sc + 52].copy_from_slice(&0x200u32.to_le_bytes()); // offset
        f[sc + 64..sc + 68].copy_from_slice(&0x8000_0400u32.to_le_bytes()); // flags

        let m = 0xb8; // LC_MAIN
        f[m..m + 4].copy_from_slice(&0x8000_0028u32.to_le_bytes());
        f[m + 4..m + 8].copy_from_slice(&24u32.to_le_bytes());
        f[m + 8..m + 16].copy_from_slice(&0x200u64.to_le_bytes());
        f[0x200..0x204].copy_from_slice(&[0xc0, 0x03, 0x5f, 0xd6]); // ret
        f
    }

    fn build_ipa() -> Vec<u8> {
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
        add(&mut w, "Payload/Xen.app/Info.plist", &info_plist());
        add(&mut w, "Payload/Xen.app/Xen", &macho_binary());
        add(&mut w, "Payload/Xen.app/embedded.mobileprovision", b"fake-profile-blob");
        add(&mut w, "Payload/Xen.app/_CodeSignature/CodeResources", b"plist-resources");
        add(&mut w, "Payload/Xen.app/Frameworks/XenKit.framework/XenKit", b"fake-fw");
        add(&mut w, "Payload/Xen.app/PlugIns/Share.appex/Info.plist", b"fake-appex");
        add(&mut w, "iTunesMetadata.plist", b"fake-metadata");
        w.finish().unwrap().into_inner()
    }

    #[test]
    fn ipa_end_to_end() {
        let bytes = build_ipa();
        assert!(is_ipa(&bytes));
        let info = analyze_ipa(&bytes, IpaOpts::default()).expect("analyzes");

        assert_eq!(info.app_dir, "Payload/Xen.app");
        assert_eq!(info.bundle_id.as_deref(), Some("com.xen.ios"));
        assert_eq!(info.display_name.as_deref(), Some("Xen"));
        assert_eq!(info.version.as_deref(), Some("2.1.0"));
        assert_eq!(info.build_version.as_deref(), Some("415"));
        assert_eq!(info.min_os_version.as_deref(), Some("15.0"));
        assert_eq!(info.executable.as_deref(), Some("Xen"));
        assert_eq!(info.architectures, vec!["arm64".to_string()]);

        let bin = info.binary.as_ref().expect("embedded Mach-O analyzed");
        assert_eq!(bin.arch.to_string(), "arm64");
        assert_eq!(bin.entry_va, Some(0x1_0000_0200));

        assert_eq!(info.frameworks, vec!["XenKit.framework".to_string()]);
        assert_eq!(info.plugins, vec!["Share.appex".to_string()]);
        assert!(info.provisioned);
        assert!(info.code_signature);
        assert_eq!(info.entries, 7);
        assert!(info.uncompressed_size > 0);
    }

    #[test]
    fn ipa_without_binary_analysis() {
        let bytes = build_ipa();
        let info = analyze_ipa(&bytes, IpaOpts { analyze_binary: false }).expect("analyzes");
        assert!(info.binary.is_none());
        assert!(info.architectures.is_empty());
        assert_eq!(info.bundle_id.as_deref(), Some("com.xen.ios"));
    }

    #[test]
    fn ipa_rejects_non_ipa() {
        assert!(!is_ipa(b"PK\x03\x04tiny"));
        let mut w = zip::ZipWriter::new(Cursor::new(Vec::new()));
        w.start_file("Other/Thing.app/Info.plist", zip::write::SimpleFileOptions::default()).unwrap();
        std::io::Write::write_all(&mut w, b"x").unwrap();
        let zip_bytes = w.finish().unwrap().into_inner();
        assert!(!is_ipa(&zip_bytes));
        assert!(analyze_ipa(&zip_bytes, IpaOpts::default()).is_err());
    }
}
