//! [`xex`] — the Xbox 360 executable container (Phase 7.3).
//!
//! XEX wraps a PPC64 (Xenon) PE image in a big-endian metadata header: main
//! header, optional headers (execution info, entry point, image base,
//! resources, import/static libraries, TLS, file format), and a security
//! block whose page descriptors describe the loaded sections. This module
//! parses that structure keylessly and reconstructs the flat image for
//! unencrypted payloads (raw or "basic" run-length compression).
//!
//! # Compliance guardrail
//!
//! Container structure only. Encrypted payloads (the retail `normal` AES
//! scheme) are never decrypted — no keys are shipped or derived here, so
//! encrypted files get full header/section metadata and a clear refusal for
//! any byte view. LZX (`normal` compression) and delta patches are likewise
//! refused with guidance instead of a half-correct decode. Analysis targets
//! are authorized/user-owned builds (homebrew, devkit, classroom samples).
//!
//! # Format notes (verified against xenia's `xex2_info.h` + zeroKilo's
//! Ghidra loader, both field-proven)
//!
//! - All header fields are big-endian.
//! - Optional-header size rule from the key's low byte: `0x00/0x01` → value
//!   is inline; `0xFF` → value is an offset to a block whose first dword is
//!   its total size; anything else `n` → `n * 4` bytes at the offset.
//! - Page descriptor word: `type = v & 0xF`, `page_count = v >> 4` (`v` read
//!   big-endian), followed by a 20-byte digest (ignored — we don't verify
//!   signatures).
//! - Basic compression: `(data_size, zero_size)` pairs — copy `data_size`
//!   bytes, leave `zero_size` bytes zero, repeat; the loader zero-fills the
//!   rest of the image up to the security `image_size`.

use serde::Serialize;

use crate::analyzer::{Arch, BinaryInfo, BinaryKind, Endian, Format, Section, SectionFlags};
use anyhow::{bail, Context, Result};

/// Largest decompressed image we will materialize (matches the analyzer's
/// input cap — RE images are rarely bigger, and the cap keeps a hostile
/// header honest).
const MAX_IMAGE_BYTES: u64 = crate::MAX_INPUT_BYTES;

// ------------------------------------------------------------- constants --

/// Optional-header keys (xenia `xex2_header_keys`).
mod key {
    pub const RESOURCE_INFO: u32 = 0x0000_02FF;
    pub const FILE_FORMAT_INFO: u32 = 0x0000_03FF;
    pub const DELTA_PATCH_DESCRIPTOR: u32 = 0x0000_05FF;
    pub const BOUNDING_PATH: u32 = 0x0000_80FF;
    pub const DEVICE_ID: u32 = 0x0000_8105;
    pub const ORIGINAL_BASE_ADDRESS: u32 = 0x0001_0001;
    pub const ENTRY_POINT: u32 = 0x0001_0100;
    pub const IMAGE_BASE_ADDRESS: u32 = 0x0001_0201;
    pub const IMPORT_LIBRARIES: u32 = 0x0001_03FF;
    pub const CHECKSUM_TIMESTAMP: u32 = 0x0001_8002;
    pub const ORIGINAL_PE_NAME: u32 = 0x0001_83FF;
    pub const STATIC_LIBRARIES: u32 = 0x0002_00FF;
    pub const TLS_INFO: u32 = 0x0002_0104;
    pub const DEFAULT_STACK_SIZE: u32 = 0x0002_0200;
    pub const DEFAULT_FILESYSTEM_CACHE_SIZE: u32 = 0x0002_0301;
    pub const DEFAULT_HEAP_SIZE: u32 = 0x0002_0401;
    pub const PAGE_HEAP_SIZE_AND_FLAGS: u32 = 0x0002_8002;
    pub const SYSTEM_FLAGS: u32 = 0x0003_0000;
    pub const EXECUTION_INFO: u32 = 0x0004_0006;
    pub const TITLE_WORKSPACE_SIZE: u32 = 0x0004_0201;
    pub const GAME_RATINGS: u32 = 0x0004_0310;
    pub const LAN_KEY: u32 = 0x0004_0404;
    pub const XBOX360_LOGO: u32 = 0x0004_05FF;
    pub const MULTIDISC_MEDIA_IDS: u32 = 0x0004_06FF;
    pub const ALTERNATE_TITLE_IDS: u32 = 0x0004_07FF;
    pub const ADDITIONAL_TITLE_MEMORY: u32 = 0x0004_0801;
    pub const EXPORTS_BY_NAME: u32 = 0x00E1_0402;
}

/// Section page types (xenia `xex2_section_type` + the type-0 header pages).
const SECTION_TYPE_NAMES: [&str; 8] = [
    "header",  // 0 — covers the XEX header itself
    "code",    // 1
    "data",    // 2
    "rodata",  // 3 — read-only data
    "enc-code", // 4 — encrypted code pages (never decrypted here)
    "enc-data", // 5
    "type6",   // 6..7 unseen in the wild
    "type7",
];

/// Image flags (xenia `xex2_image_flags`).
const IMAGE_FLAGS: [(u32, &str); 18] = [
    (0x0000_0002, "manufacturing-utility"),
    (0x0000_0004, "manufacturing-support-tools"),
    (0x0000_0008, "xgd2-media-only"),
    (0x0000_0100, "cardea-key"),
    (0x0000_0200, "xeika-key"),
    (0x0000_0400, "usermode-title"),
    (0x0000_0800, "usermode-system"),
    (0x0000_1000, "orange0"),
    (0x0000_2000, "orange1"),
    (0x0000_4000, "orange2"),
    (0x0001_0000, "iptv-signup-app"),
    (0x0002_0000, "iptv-title-app"),
    (0x0400_0000, "keyvault-privileges"),
    (0x0800_0000, "online-activation"),
    (0x1000_0000, "page-size-4kb"),
    (0x2000_0000, "region-free"),
    (0x4000_0000, "revocation-optional"),
    (0x8000_0000, "revocation-required"),
];

/// Module flags (xenia `xex2_module_flags`).
const MODULE_FLAGS: [(u32, &str); 8] = [
    (0x01, "title"),
    (0x02, "exports-to-title"),
    (0x04, "system-debugger"),
    (0x08, "dll"),
    (0x10, "module-patch"),
    (0x20, "patch-full"),
    (0x40, "patch-delta"),
    (0x80, "user-mode"),
];

// ---------------------------------------------------------------- structs --

/// One reconstructed XEX section (merged run of same-type pages).
#[derive(Debug, Clone, Serialize)]
pub struct XexSection {
    /// Page-type id (0=header, 1=code, 2=data, 3=rodata, 4/5=encrypted).
    pub page_type: u8,
    pub type_name: String,
    /// Virtual address (`load_address + image_offset`).
    pub va: u64,
    /// Offset of the section contents inside the decompressed image.
    pub image_offset: u64,
    /// `page_count * page_size`.
    pub size: u64,
    pub page_count: u32,
}

/// One `RESOURCE_INFO` entry.
#[derive(Debug, Clone, Serialize)]
pub struct XexResource {
    pub name: String,
    pub address: u64,
    pub size: u64,
}

/// One static-library record (`STATIC_LIBRARIES`).
#[derive(Debug, Clone, Serialize)]
pub struct XexStaticLib {
    pub name: String,
    /// `major.minor.build.qfe` (qfe = the low byte at 0xF per xenia).
    pub version: String,
}

/// One `(data, zero)` run of the basic compression block table.
#[derive(Debug, Clone, Serialize)]
pub struct XexBasicBlock {
    pub data_size: u64,
    pub zero_size: u64,
}

/// TLS directory (`TLS_INFO`).
#[derive(Debug, Clone, Serialize)]
pub struct XexTls {
    pub slot_count: u64,
    pub raw_data_address: u64,
    pub data_size: u64,
    pub raw_data_size: u64,
}

/// Full keyless parse of a XEX container.
#[derive(Debug, Clone, Serialize)]
pub struct XexInfo {
    /// `"XEX2"`, `"XEX1"` or `"XEX0"`.
    pub magic: String,
    pub module_flags: u32,
    pub module_flag_names: Vec<String>,
    /// Offset of the PE payload in the container (`header_size` at 0x8).
    pub pe_data_offset: u64,
    pub security_offset: u64,
    pub optional_count: u32,
    /// Plain-image size from the security block.
    pub image_size: u64,
    pub load_address: u64,
    pub image_flags: u32,
    pub image_flag_names: Vec<String>,
    pub region: u32,
    pub allowed_media_types: u32,
    pub page_descriptor_count: u32,
    /// 0x1000 or 0x10000 (image flag `page-size-4kb`).
    pub page_size: u64,
    /// `"none"` or `"aes"` — never the key material itself.
    pub encryption: String,
    /// `"none"`, `"basic"`, `"lzx"` or `"delta"`.
    pub compression: String,
    /// Block table when `compression == "basic"`.
    pub basic_blocks: Vec<XexBasicBlock>,
    /// LZX window size when `compression == "lzx"` (informational).
    pub lzx_window_size: Option<u64>,
    /// Entry point (plain u32 from the ENTRY_POINT header).
    pub entry_point: Option<u64>,
    /// `IMAGE_BASE_ADDRESS` optional header (usually equals `load_address`).
    pub image_base: Option<u64>,
    pub original_base: Option<u64>,
    pub title_id: Option<u64>,
    /// Two-char publisher tag when the title id's high half is printable
    /// ASCII (e.g. `0x5351…` → `SQ`).
    pub title_tag: Option<String>,
    /// Execution-info version bitfields (`major.minor.build.qfe`).
    pub version: Option<String>,
    pub base_version: Option<String>,
    pub platform: Option<u8>,
    pub executable_table: Option<u8>,
    pub disc_number: Option<u8>,
    pub disc_count: Option<u8>,
    pub savegame_id: Option<u64>,
    /// ORIGINAL_PE_NAME (e.g. `default.exe`).
    pub original_pe_name: Option<String>,
    /// Import-library names (string table of `IMPORT_LIBRARIES`).
    pub import_libraries: Vec<String>,
    pub static_libraries: Vec<XexStaticLib>,
    pub resources: Vec<XexResource>,
    pub tls: Option<XexTls>,
    pub default_stack_size: Option<u64>,
    pub system_flags: Option<u64>,
    /// `(checksum, timestamp)` of `CHECKSUM_TIMESTAMP` — identifiers only.
    pub checksum_timestamp: Option<(u64, u64)>,
    pub media_id_hex: Option<String>,
    /// Merged section list (types 1–3 + encrypted page types).
    pub sections: Vec<XexSection>,
    /// Optional-header keys we saw but did not decode (RE interest signal).
    pub unrecognized_headers: Vec<u32>,
}

/// A decompressed XEX: the flat load image plus its parsed metadata.
#[derive(Debug, Clone)]
pub struct XexImage {
    pub info: XexInfo,
    /// `image_size` bytes at `load_address` (zero-filled tails included).
    pub image: Vec<u8>,
}

// ------------------------------------------------------------------ sniff --

/// True when the bytes open with a XEX magic (`XEX2`/`XEX1`/`XEX0`).
pub fn is_xex(data: &[u8]) -> bool {
    data.len() >= 4
        && (data.starts_with(b"XEX2") || data.starts_with(b"XEX1") || data.starts_with(b"XEX0"))
}

// ------------------------------------------------------------------ parse --

/// Parse the container structure (no decompression, no crypto).
pub fn parse_xex(data: &[u8]) -> Result<XexInfo> {
    if !is_xex(data) {
        bail!("not a XEX container — magic is {:?}", String::from_utf8_lossy(&data[..4.min(data.len())]));
    }
    let magic = String::from_utf8_lossy(&data[..4]).into_owned();
    if data.len() < 0x18 {
        bail!("XEX truncated: {} bytes is shorter than the 24-byte main header", data.len());
    }
    let be32 = |off: usize| -> Result<u32> {
        let b = data
            .get(off..off + 4)
            .ok_or_else(|| anyhow::anyhow!("XEX truncated reading dword at {off:#x}"))?;
        Ok(u32::from_be_bytes([b[0], b[1], b[2], b[3]]))
    };
    let module_flags = be32(0x4)?;
    let pe_data_offset = be32(0x8)? as u64;
    let security_offset = be32(0x10)? as u64;
    let optional_count = be32(0x14)?;

    let mut info = XexInfo {
        magic,
        module_flags,
        module_flag_names: flag_names(module_flags, &MODULE_FLAGS),
        pe_data_offset,
        security_offset,
        optional_count,
        image_size: 0,
        load_address: 0,
        image_flags: 0,
        image_flag_names: vec![],
        region: 0,
        allowed_media_types: 0,
        page_descriptor_count: 0,
        page_size: 0x10000,
        encryption: "unknown".into(),
        compression: "unknown".into(),
        basic_blocks: vec![],
        lzx_window_size: None,
        entry_point: None,
        image_base: None,
        original_base: None,
        title_id: None,
        title_tag: None,
        version: None,
        base_version: None,
        platform: None,
        executable_table: None,
        disc_number: None,
        disc_count: None,
        savegame_id: None,
        original_pe_name: None,
        import_libraries: vec![],
        static_libraries: vec![],
        resources: vec![],
        tls: None,
        default_stack_size: None,
        system_flags: None,
        checksum_timestamp: None,
        media_id_hex: None,
        sections: vec![],
        unrecognized_headers: vec![],
    };

    // ---- optional headers -------------------------------------------------
    // Key low byte: 0x00/0x01 inline; 0xFF → struct with its own total size
    // dword (payload returned WITHOUT that dword); else n → n*4 raw bytes.
    let opt_blob = |value: u32, low: u8| -> Result<(usize, &[u8])> {
        match low {
            0x00 | 0x01 => bail!("inline value {value:#x}"),
            0xFF => {
                let off = value as usize;
                let size = be32(off)? as usize;
                if size < 4 {
                    bail!("optional header block at {off:#x} has bogus size {size}");
                }
                let end = off
                    .checked_add(size)
                    .context("optional header block overflows")?;
                Ok((
                    size,
                    data.get(off + 4..end).ok_or_else(|| {
                        anyhow::anyhow!("optional header block at {off:#x} runs past EOF")
                    })?,
                ))
            }
            n => {
                let off = value as usize;
                let len = (n as usize) * 4;
                Ok((
                    len,
                    data.get(off..off + len)
                        .ok_or_else(|| anyhow::anyhow!("optional header data at {off:#x} runs past EOF"))?,
                ))
            }
        }
    };
    let inline = |value: u32, low: u8| -> Option<u32> {
        (low == 0x00 || low == 0x01).then_some(value)
    };

    for i in 0..optional_count {
        let rec = 0x18 + (i as usize) * 8;
        if rec + 8 > data.len() {
            bail!("optional header table runs past EOF (header {i} at {rec:#x})");
        }
        let k = be32(rec)?;
        let value = be32(rec + 4)?;
        let low = (k & 0xFF) as u8;
        match k {
            key::ENTRY_POINT => {
                // Plain u32 (xenia + zeroKilo read it verbatim — no flag bit).
                if let Some(v) = inline(value, low) {
                    info.entry_point = Some(v as u64);
                }
            }
            key::IMAGE_BASE_ADDRESS => info.image_base = inline(value, low).map(|v| v as u64),
            key::ORIGINAL_BASE_ADDRESS => info.original_base = inline(value, low).map(|v| v as u64),
            key::DEFAULT_STACK_SIZE => info.default_stack_size = inline(value, low).map(|v| v as u64),
            key::SYSTEM_FLAGS => info.system_flags = inline(value, low).map(|v| v as u64),
            key::EXECUTION_INFO => {
                if let Ok((_, blob)) = opt_blob(value, low) {
                    if blob.len() >= 0x18 {
                        let rd = |off: usize| {
                            u32::from_be_bytes([
                                blob[off],
                                blob[off + 1],
                                blob[off + 2],
                                blob[off + 3],
                            ])
                        };
                        info.version = Some(format_version(rd(4)));
                        info.base_version = Some(format_version(rd(8)));
                        info.title_id = Some(rd(0xC) as u64);
                        let t = rd(0xC).to_be_bytes();
                        if t[..2].iter().all(|c| (0x20..0x7f).contains(c)) {
                            info.title_tag =
                                Some(String::from_utf8_lossy(&t[..2]).into_owned());
                        }
                        info.platform = Some(blob[0x10]);
                        info.executable_table = Some(blob[0x11]);
                        info.disc_number = Some(blob[0x12]);
                        info.disc_count = Some(blob[0x13]);
                        info.savegame_id = Some(rd(0x14) as u64);
                    }
                }
            }
            key::FILE_FORMAT_INFO => {
                if let Ok((total, blob)) = opt_blob(value, low) {
                    if blob.len() >= 4 {
                        let enc = u16::from_be_bytes([blob[0], blob[1]]);
                        let comp = u16::from_be_bytes([blob[2], blob[3]]);
                        info.encryption = match enc {
                            0 => "none".into(),
                            1 => "aes".into(),
                            _ => format!("unknown({enc})"),
                        };
                        info.compression = match comp {
                            0 => "none".into(),
                            1 => "basic".into(),
                            2 => "lzx".into(),
                            3 => "delta".into(),
                            _ => format!("unknown({comp})"),
                        };
                        match comp {
                            1 => {
                                // total = 4(size) + 2 + 2 + blocks*8 →
                                // blocks = (total - 8) / 8 pairs (xenia).
                                if total >= 8 {
                                    let count = (total - 8) / 8;
                                    for b in 0..count {
                                        let off = 4 + b * 8;
                                        if off + 8 > blob.len() {
                                            break;
                                        }
                                        info.basic_blocks.push(XexBasicBlock {
                                            data_size: u32::from_be_bytes([
                                                blob[off],
                                                blob[off + 1],
                                                blob[off + 2],
                                                blob[off + 3],
                                            ]) as u64,
                                            zero_size: u32::from_be_bytes([
                                                blob[off + 4],
                                                blob[off + 5],
                                                blob[off + 6],
                                                blob[off + 7],
                                            ]) as u64,
                                        });
                                    }
                                }
                            }
                            2 => {
                                // window_size right after enc/comp (@+4).
                                if blob.len() >= 8 {
                                    info.lzx_window_size = Some(u32::from_be_bytes([
                                        blob[4], blob[5], blob[6], blob[7],
                                    ]) as u64);
                                }
                            }
                            _ => {}
                        }
                    }
                }
            }
            key::RESOURCE_INFO => {
                if let Ok((_, blob)) = opt_blob(value, low) {
                    // blob = 16-byte records (size dword already stripped).
                    let count = blob.len() / 16;
                    for r in 0..count {
                        let off = r * 16;
                        let name = blob[off..off + 8]
                            .iter()
                            .position(|&c| c == 0)
                            .map(|z| &blob[off..off + z])
                            .unwrap_or(&blob[off..off + 8]);
                        info.resources.push(XexResource {
                            name: String::from_utf8_lossy(name).into_owned(),
                            address: u32::from_be_bytes([
                                blob[off + 8],
                                blob[off + 9],
                                blob[off + 10],
                                blob[off + 11],
                            ]) as u64,
                            size: u32::from_be_bytes([
                                blob[off + 12],
                                blob[off + 13],
                                blob[off + 14],
                                blob[off + 15],
                            ]) as u64,
                        });
                    }
                }
            }
            key::IMPORT_LIBRARIES => {
                if let Ok((_, blob)) = opt_blob(value, low) {
                    info.import_libraries = parse_import_names(blob);
                }
            }
            key::STATIC_LIBRARIES => {
                if let Ok((_, blob)) = opt_blob(value, low) {
                    // blob = 16-byte records.
                    let count = blob.len() / 16;
                    for l in 0..count {
                        let off = l * 16;
                        let name = blob[off..off + 8]
                            .iter()
                            .position(|&c| c == 0)
                            .map(|z| &blob[off..off + z])
                            .unwrap_or(&blob[off..off + 8]);
                        let name = String::from_utf8_lossy(name).into_owned();
                        if name.is_empty() {
                            continue;
                        }
                        let rd16 = |o: usize| u16::from_be_bytes([blob[o], blob[o + 1]]);
                        info.static_libraries.push(XexStaticLib {
                            name,
                            version: format!(
                                "{}.{}.{}.{}",
                                rd16(off + 8),
                                rd16(off + 10),
                                rd16(off + 12),
                                blob[off + 15],
                            ),
                        });
                    }
                }
            }
            key::ORIGINAL_PE_NAME => {
                if let Ok((_, blob)) = opt_blob(value, low) {
                    let text = String::from_utf8_lossy(blob).into_owned();
                    info.original_pe_name = Some(text.trim_end_matches('\0').to_string());
                }
            }
            key::TLS_INFO => {
                if let Ok((_, blob)) = opt_blob(value, low) {
                    if blob.len() >= 16 {
                        let rd = |o: usize| {
                            u32::from_be_bytes([blob[o], blob[o + 1], blob[o + 2], blob[o + 3]])
                        };
                        info.tls = Some(XexTls {
                            slot_count: rd(0) as u64,
                            raw_data_address: rd(4) as u64,
                            data_size: rd(8) as u64,
                            raw_data_size: rd(12) as u64,
                        });
                    }
                }
            }
            key::CHECKSUM_TIMESTAMP => {
                if let Ok((_, blob)) = opt_blob(value, low) {
                    if blob.len() >= 8 {
                        let rd = |o: usize| {
                            u32::from_be_bytes([blob[o], blob[o + 1], blob[o + 2], blob[o + 3]])
                        };
                        info.checksum_timestamp = Some((rd(0) as u64, rd(4) as u64));
                    }
                }
            }
            // Key-bearing headers are never decoded — the compliance
            // guardrail (LAN_KEY, DELTA_PATCH_DESCRIPTOR's image keys).
            key::LAN_KEY | key::DELTA_PATCH_DESCRIPTOR => {}
            _ => {
                // Recognized-but-unhandled vs unknown: track unknown keys so
                // `re xex` can surface RE signals without decoding them.
                if !matches!(
                    k,
                    key::BOUNDING_PATH
                        | key::DEVICE_ID
                        | key::GAME_RATINGS
                        | key::XBOX360_LOGO
                        | key::MULTIDISC_MEDIA_IDS
                        | key::ALTERNATE_TITLE_IDS
                        | key::ADDITIONAL_TITLE_MEMORY
                        | key::EXPORTS_BY_NAME
                        | key::TITLE_WORKSPACE_SIZE
                        | key::DEFAULT_FILESYSTEM_CACHE_SIZE
                        | key::DEFAULT_HEAP_SIZE
                        | key::PAGE_HEAP_SIZE_AND_FLAGS
                ) {
                    info.unrecognized_headers.push(k);
                }
            }
        }
    }

    // ---- security block ---------------------------------------------------
    if security_offset == 0 || security_offset as usize + 0x184 > data.len() {
        bail!(
            "XEX security block at {security_offset:#x} is missing/truncated — \
             unsigned containers may omit it, but then no sections can be mapped"
        );
    }
    let so = security_offset as usize;
    let (image_size, load_address, image_flags, region, allowed_media, pd_count, pd_off) =
        if info.magic == "XEX2" {
            (
                be32(so + 0x4)? as u64,
                be32(so + 0x110)? as u64,
                be32(so + 0x10C)?,
                be32(so + 0x178)?,
                be32(so + 0x17C)?,
                be32(so + 0x180)?,
                so + 0x184,
            )
        } else {
            // XEX1/XEX0 security layout (xenia `xex1_security_info`).
            (
                be32(so + 0x4)? as u64,
                be32(so + 0x130)? as u64,
                be32(so + 0x158)?,
                be32(so + 0x154)?,
                be32(so + 0x160)?,
                be32(so + 0x164)?,
                so + 0x168,
            )
        };
    info.image_size = image_size;
    info.load_address = load_address;
    info.image_flags = image_flags;
    info.image_flag_names = flag_names(image_flags, &IMAGE_FLAGS);
    info.region = region;
    info.allowed_media_types = allowed_media;
    info.page_descriptor_count = pd_count;
    info.page_size = if image_flags & 0x1000_0000 != 0 { 0x1000 } else { 0x10000 };
    let media = data
        .get(so + 0x140..so + 0x150)
        .map(|b| b.iter().map(|x| format!("{x:02x}")).collect::<String>());
    info.media_id_hex = media;

    if pd_off + (pd_count as usize) * 24 > data.len() {
        bail!(
            "XEX page descriptor table ({pd_count} × 24 bytes at {pd_off:#x}) runs past EOF"
        );
    }
    info.sections = merge_sections(data, pd_off, pd_count, load_address, info.page_size);
    Ok(info)
}

/// Parse the IMPORT_LIBRARIES string table (names only — the per-import
/// tables are import-descriptor records we don't need for the views).
/// `blob` excludes the struct's own size dword: [table_size][count][names…].
fn parse_import_names(blob: &[u8]) -> Vec<String> {
    let rd32 = |o: usize| -> Option<u32> {
        let b = blob.get(o..o + 4)?;
        Some(u32::from_be_bytes([b[0], b[1], b[2], b[3]]))
    };
    let Some(table_size) = rd32(0) else { return vec![] };
    let Some(count) = rd32(4) else { return vec![] };
    let mut names = Vec::new();
    let mut o = 8usize;
    let end = 8usize.saturating_add(table_size as usize).min(blob.len());
    for _ in 0..count {
        if o >= end {
            break;
        }
        let nul = blob[o..end]
            .iter()
            .position(|&c| c == 0)
            .map(|p| o + p)
            .unwrap_or(end);
        let name = String::from_utf8_lossy(&blob[o..nul]).into_owned();
        if !name.is_empty() {
            names.push(name);
        }
        // String table entries pad to 4-byte alignment.
        o = nul + 1;
        if o % 4 != 0 {
            o += 4 - (o % 4);
        }
    }
    names
}

/// Merge same-type page-descriptor runs into sections.
fn merge_sections(
    data: &[u8],
    pd_off: usize,
    count: u32,
    load_address: u64,
    page_size: u64,
) -> Vec<XexSection> {
    let mut sections = Vec::new();
    let mut page: u64 = 0;
    let mut run_type: Option<u8> = None;
    let mut run_pages: u32 = 0;
    let flush = |ty: u8, pages: u32, start_page: &mut u64, out: &mut Vec<XexSection>| {
        if pages == 0 {
            return;
        }
        let image_offset = *start_page * page_size;
        // Type 0 pages cover the header — they advance the address counter
        // but never appear as sections (xenia maps only 1–5).
        if ty != 0 {
            out.push(XexSection {
                page_type: ty,
                type_name: SECTION_TYPE_NAMES
                    .get(ty as usize)
                    .unwrap_or(&"unknown")
                    .to_string(),
                va: load_address + image_offset,
                image_offset,
                size: pages as u64 * page_size,
                page_count: pages,
            });
        }
        *start_page += pages as u64;
    };
    for i in 0..count as usize {
        let off = pd_off + i * 24;
        let b = &data[off..off + 4];
        let v = u32::from_be_bytes([b[0], b[1], b[2], b[3]]);
        let ty = (v & 0xF) as u8;
        let pages = v >> 4;
        if pages == 0 {
            continue;
        }
        match run_type {
            Some(t) if t == ty => run_pages += pages,
            Some(t) => {
                flush(t, run_pages, &mut page, &mut sections);
                run_type = Some(ty);
                run_pages = pages;
            }
            None => {
                run_type = Some(ty);
                run_pages = pages;
            }
        }
    }
    if let Some(t) = run_type {
        flush(t, run_pages, &mut page, &mut sections);
    }
    sections
}

fn flag_names(bits: u32, table: &[(u32, &str)]) -> Vec<String> {
    table
        .iter()
        .filter(|(bit, _)| bits & bit != 0)
        .map(|(_, name)| (*name).to_string())
        .collect()
}

/// `major.minor.build.qfe` from the `xex2_version` bitfields. The word is
/// allocated MSB-first on the big-endian console: major = bits 31–28,
/// minor = 27–24, build = 23–8, qfe = 7–0.
fn format_version(v: u32) -> String {
    format!(
        "{}.{}.{}.{}",
        v >> 28,
        (v >> 24) & 0xF,
        (v >> 8) & 0xFFFF,
        v & 0xFF
    )
}

// ----------------------------------------------------------- decompress ----

/// Reconstruct the flat load image — unencrypted raw/basic payloads only.
///
/// Encrypted (AES) and LZX/delta-compressed containers are refused with a
/// compliance/limitation message instead of producing garbage.
pub fn decompress(data: &[u8]) -> Result<XexImage> {
    let info = parse_xex(data)?;
    if info.encryption != "none" {
        bail!(
            "XEX payload is AES-encrypted (`{}` encryption) — XenRE parses container \
             structure only; decryption keys are never bundled (compliance guardrail). \
             Feed a decrypted/extracted image to disasm/ghidra instead.",
            info.encryption
        );
    }
    if info.pe_data_offset > data.len() as u64 {
        bail!("XEX PE data offset {:#x} is past EOF", info.pe_data_offset);
    }
    if info.image_size as u64 > MAX_IMAGE_BYTES {
        bail!(
            "XEX image size {} exceeds the {} materialization cap",
            info.image_size,
            MAX_IMAGE_BYTES
        );
    }
    let payload = &data[info.pe_data_offset as usize..];
    let image_size = info.image_size as usize;

    let image = match info.compression.as_str() {
        "none" => {
            let mut img = vec![0u8; image_size];
            let take = payload.len().min(image_size);
            img[..take].copy_from_slice(&payload[..take]);
            img
        }
        "basic" => {
            if info.basic_blocks.is_empty() {
                bail!("XEX says `basic` compression but the block table is empty");
            }
            let mut img = vec![0u8; image_size];
            let mut src = 0usize;
            let mut dst = 0usize;
            for (i, block) in info.basic_blocks.iter().enumerate() {
                let d = block.data_size as usize;
                let z = block.zero_size as usize;
                let data_end = src.checked_add(d).context("basic block overflows payload")?;
                if data_end > payload.len() {
                    bail!(
                        "basic block {i} reads past the payload ({d} bytes at {src:#x}, \
                         payload {})",
                        payload.len()
                    );
                }
                if dst + d > image_size {
                    bail!("basic block {i} writes past the image size");
                }
                img[dst..dst + d].copy_from_slice(&payload[src..src + d]);
                src = data_end;
                dst += d + z;
            }
            img
        }
        "lzx" => bail!(
            "XEX uses LZX (`normal`) compression — not supported in-container \
             (retail LZX payloads are AES-encrypted regardless). Extract the \
             image with an authorized owner's toolchain and feed the raw image \
             to `xencode re ghidra --raw-image` / `re disasm` instead."
        ),
        "delta" => bail!("XEX delta patches are not supported in-container"),
        other => bail!("XEX compression `{other}` is unknown"),
    };
    Ok(XexImage { info, image })
}

// -------------------------------------------------------------- analyzer ---

/// Build the shared [`BinaryInfo`] view over a XEX's structure.
///
/// Sections carry image offsets (not container offsets): byte-level views
/// must run on [`decompress`]'s flat image (see `payload_image`). Symbols/
/// imports stay empty — XEX has no symbol tables, only import-library names.
pub fn to_binary_info(info: &XexInfo) -> BinaryInfo {
    let mut bi = BinaryInfo::fixture();
    bi.format = Format::Xex;
    bi.arch = Arch::Ppc64;
    bi.endian = Endian::Big;
    bi.bits = 64;
    bi.kind = if info.module_flags & 0x08 != 0 {
        BinaryKind::Dll
    } else {
        BinaryKind::Exe
    };
    bi.pie = false;
    bi.entry_va = info.entry_point;
    bi.image_base = Some(info.load_address);
    bi.libs = info.import_libraries.clone();
    bi.sections = info
        .sections
        .iter()
        .map(|s| Section {
            name: section_name(s, info),
            va: Some(s.va),
            size: s.size,
            file_off: s.image_offset,
            file_size: s.size,
            flags: SectionFlags {
                read: true,
                write: s.page_type == 2,
                exec: s.page_type == 1,
                tls: false,
                debug: false,
            },
        })
        .collect();
    // Flat image: [load_address, load_address + image_size) ↔ [0, image_size).
    let end = info.load_address.saturating_add(info.image_size);
    bi.set_addr_maps([(info.load_address, end, 0)]);
    bi
}

fn section_name(s: &XexSection, info: &XexInfo) -> String {
    let same = info.sections.iter().filter(|o| o.page_type == s.page_type).count();
    if same > 1 {
        let idx = info
            .sections
            .iter()
            .filter(|o| o.page_type == s.page_type)
            .position(|o| std::ptr::eq(o, s))
            .unwrap_or(0);
        format!(".xex-{}-{idx}", s.type_name)
    } else {
        format!(".xex-{}", s.type_name)
    }
}

/// Byte source for the code/data views: the decompressed flat image when the
/// container allows it, a clear error otherwise.
pub fn payload_image(data: &[u8]) -> Result<(BinaryInfo, Vec<u8>)> {
    let img = decompress(data)?;
    let bi = to_binary_info(&img.info);
    Ok((bi, img.image))
}

// ------------------------------------------------------------------ tests --

#[cfg(test)]
mod tests {
    use super::*;

    /// Build a minimal XEX2 fixture. `payload` is placed at `pe_data_offset`;
    /// `blocks`/`compression`/`encryption` shape the FILE_FORMAT header.
    struct XexFixture {
        module_flags: u32,
        image_flags: u32,
        load_address: u32,
        entry: u32,
        image_size: u32,
        descriptors: Vec<(u8, u32)>, // (type, page_count)
        encryption: u16,
        compression: u16,
        blocks: Vec<(u32, u32)>,
        title_id: u32,
        pe_name: Option<String>,
        imports: Vec<String>,
        resources: Vec<(String, u32, u32)>,
        static_libs: Vec<(String, u16, u16, u16, u8, u8)>,
        payload: Vec<u8>,
    }

    impl Default for XexFixture {
        fn default() -> Self {
            XexFixture {
                module_flags: 0x81, // title + user-mode
                image_flags: 0x2000_0000, // region-free, 64 KiB pages
                load_address: 0x8200_0000,
                entry: 0x8200_1000,
                image_size: 0,
                descriptors: vec![(0, 1), (1, 2), (2, 3)],
                encryption: 0,
                compression: 0,
                blocks: vec![],
                title_id: 0x5351_07DB,
                pe_name: Some("default.exe".into()),
                imports: vec!["xam.xex".into(), "xboxkrnl.exe".into()],
                resources: vec![],
                static_libs: vec![("XAPILIB".into(), 2, 0, 6534, 0, 0)],
                payload: vec![],
            }
        }
    }

    impl XexFixture {
        fn page_size(&self) -> u64 {
            if self.image_flags & 0x1000_0000 != 0 { 0x1000 } else { 0x10000 }
        }
        fn image_size(&self) -> u32 {
            if self.image_size != 0 {
                return self.image_size;
            }
            let total: u32 = self.descriptors.iter().map(|(_, p)| *p).sum();
            (total as u64 * self.page_size()) as u32
        }

        fn build(&self) -> Vec<u8> {
            let _page_size = self.page_size() as u32;
            let image_size = self.image_size();

            // Security block (fixed 0x184 + descriptors), placed at `so`.
            let mut sec = Vec::new();
            let put32 = |v: &mut Vec<u8>, x: u32| v.extend_from_slice(&x.to_be_bytes());
            put32(&mut sec, 0x184 + (self.descriptors.len() * 24) as u32); // header size
            put32(&mut sec, image_size);
            sec.extend_from_slice(&[0u8; 0x100]); // RSA signature @0x8
            sec.extend_from_slice(&[0u8; 0x4]); // unk_108 (dword)
            put32(&mut sec, self.image_flags); // 0x10C
            put32(&mut sec, self.load_address); // 0x110
            sec.extend_from_slice(&[0u8; 0x14]); // section digest @0x114
            put32(&mut sec, 0); // import table count @0x128
            sec.extend_from_slice(&[0u8; 0x14]); // import digest @0x12C
            sec.extend_from_slice(&[0u8; 0x10]); // xgd2 media id @0x140
            sec.extend_from_slice(&[0u8; 0x10]); // AES key slot @0x150 (zeros, never read)
            put32(&mut sec, 0); // export table @0x160
            sec.extend_from_slice(&[0u8; 0x14]); // header digest @0x164
            put32(&mut sec, 0x00FF_0000); // region (PAL) @0x178
            put32(&mut sec, 0x0000_0007); // allowed media @0x17C
            put32(&mut sec, self.descriptors.len() as u32); // @0x180
            for (ty, pages) in &self.descriptors {
                put32(&mut sec, ((*pages << 4) | (*ty as u32 & 0xF)) as u32);
                sec.extend_from_slice(&[0u8; 0x14]); // digest
            }

            // Optional header blocks (placed right after the header table).
            let mut blocks: Vec<(u32, Vec<u8>)> = Vec::new(); // (key, payload)
            if let Some(name) = &self.pe_name {
                let mut b = Vec::new();
                put32(&mut b, (4 + name.len()) as u32);
                b.extend_from_slice(name.as_bytes());
                blocks.push((key::ORIGINAL_PE_NAME, b));
            }
            if !self.imports.is_empty() {
                let mut names = Vec::new();
                for n in &self.imports {
                    names.extend_from_slice(n.as_bytes());
                    names.push(0);
                    while names.len() % 4 != 0 {
                        names.push(0);
                    }
                }
                let mut b = Vec::new();
                put32(&mut b, (12 + names.len()) as u32); // block size
                put32(&mut b, names.len() as u32); // string table size
                put32(&mut b, self.imports.len() as u32); // count
                b.extend_from_slice(&names);
                blocks.push((key::IMPORT_LIBRARIES, b));
            }
            if !self.resources.is_empty() {
                let mut b = Vec::new();
                put32(&mut b, (4 + self.resources.len() * 16) as u32);
                for (name, addr, size) in &self.resources {
                    let mut nb = name.as_bytes().to_vec();
                    nb.resize(8, 0);
                    b.extend_from_slice(&nb);
                    put32(&mut b, *addr);
                    put32(&mut b, *size);
                }
                blocks.push((key::RESOURCE_INFO, b));
            }
            if !self.static_libs.is_empty() {
                // name[8] + major/minor/build u16 BE + approval u8 + qfe u8.
                let mut b = Vec::new();
                put32(&mut b, (4 + self.static_libs.len() * 16) as u32);
                for (name, maj, mino, build, approval, qfe) in &self.static_libs {
                    let mut nb = name.as_bytes().to_vec();
                    nb.resize(8, 0);
                    b.extend_from_slice(&nb);
                    b.extend_from_slice(&maj.to_be_bytes());
                    b.extend_from_slice(&mino.to_be_bytes());
                    b.extend_from_slice(&build.to_be_bytes());
                    b.push(*approval);
                    b.push(*qfe);
                }
                blocks.push((key::STATIC_LIBRARIES, b));
            }
            {
                // EXECUTION_INFO — key low byte is 0x06 → raw 6 dwords at the
                // offset (no size prefix). Version bitfields MSB-first:
                // 2.0.0.0 = 0x2000_0000, 1.0.0.0 = 0x1000_0000.
                let mut b = Vec::new();
                put32(&mut b, 0); // media id
                put32(&mut b, 0x2000_0000); // version 2.0.0.0
                put32(&mut b, 0x1000_0000); // base version 1.0.0.0
                put32(&mut b, self.title_id);
                b.push(9); b.push(2); b.push(0); b.push(1); // platform/exec/disc 0 of 1
                put32(&mut b, 0x1234_5678); // savegame id
                blocks.push((key::EXECUTION_INFO, b));
            }
            {
                // FILE_FORMAT_INFO.
                let mut b = Vec::new();
                let payload_size = 8 + self.blocks.len() * 8;
                put32(&mut b, payload_size as u32);
                b.extend_from_slice(&self.encryption.to_be_bytes());
                b.extend_from_slice(&self.compression.to_be_bytes());
                for (d, z) in &self.blocks {
                    put32(&mut b, *d);
                    put32(&mut b, *z);
                }
                blocks.push((key::FILE_FORMAT_INFO, b));
            }

            // Assemble: [header records + blocks] · [security @ so] · [payload].
            // so must clear the header blocks; the payload starts after the
            // security block (pe_data_offset = so + sec.len()).
            let mut block_offsets = Vec::new();
            let inline_records: Vec<(u32, u32)> = vec![
                (key::ENTRY_POINT, self.entry),
                (key::IMAGE_BASE_ADDRESS, self.load_address),
                (key::ORIGINAL_BASE_ADDRESS, self.load_address),
            ];
            let total_opt = blocks.len() + inline_records.len();
            let mut cur = 0x18 + total_opt * 8;
            for (_, b) in &blocks {
                block_offsets.push(cur as u32);
                cur += b.len();
            }
            let so = cur.max(0x200); // security offset (fixture-picked floor)
            let pe_off = so + sec.len(); // payload starts after security
            let mut out = Vec::new();
            out.extend_from_slice(b"XEX2");
            put32(&mut out, self.module_flags);
            put32(&mut out, pe_off as u32); // 0x8 pe data offset
            put32(&mut out, 0); // reserved
            put32(&mut out, so as u32);
            put32(&mut out, total_opt as u32);

            // Records: inline ones first (entry, image base, original base),
            // then offset ones in block order.
            let mut records: Vec<(u32, u32)> = inline_records.clone();
            for (i, (k, _)) in blocks.iter().enumerate() {
                records.push((*k, block_offsets[i]));
            }
            for (k, v) in &records {
                put32(&mut out, *k);
                put32(&mut out, *v);
            }
            // Blocks after the record table.
            for (i, (_, b)) in blocks.iter().enumerate() {
                while out.len() < block_offsets[i] as usize {
                    out.push(0);
                }
                out.extend_from_slice(b);
            }
            // Security block lands exactly at `so`, payload right after.
            while out.len() < so {
                out.push(0);
            }
            out.extend_from_slice(&sec);
            out.extend_from_slice(&self.payload);
            out
        }
    }

    #[test]
    fn raw_uncompressed_xex_parses() {
        let code = vec![0x4e, 0x80, 0x00, 0x20]; // blr
        let f = XexFixture {
            payload: {
                let mut p = vec![0xAA; 0x10000]; // header page
                p.extend_from_slice(&code);
                p.resize(0x30000, 0x00);
                p
            },
            ..Default::default()
        };
        let data = f.build();
        let info = parse_xex(&data).expect("parses");
        assert_eq!(info.magic, "XEX2");
        assert_eq!(info.load_address, 0x8200_0000);
        assert_eq!(info.image_size, 0x6_0000); // 6 pages × 0x10000
        assert_eq!(info.page_size, 0x10000);
        assert_eq!(info.encryption, "none");
        assert_eq!(info.compression, "none");
        assert_eq!(info.entry_point, Some(0x8200_1000));
        assert_eq!(info.title_id, Some(0x5351_07DB));
        assert_eq!(info.title_tag.as_deref(), Some("SQ"));
        assert_eq!(info.version.as_deref(), Some("2.0.0.0"));
        assert_eq!(info.original_pe_name.as_deref(), Some("default.exe"));
        assert_eq!(info.import_libraries, vec!["xam.xex", "xboxkrnl.exe"]);
        assert_eq!(info.static_libraries.len(), 1);
        assert_eq!(info.static_libraries[0].name, "XAPILIB");
        assert_eq!(info.static_libraries[0].version, "2.0.6534.0");
        assert_eq!(info.module_flag_names, vec!["title", "user-mode"]);
        assert_eq!(info.region, 0x00FF_0000);
        assert!(info.media_id_hex.is_some());
    }

    #[test]
    fn sections_merge_and_map() {
        let f = XexFixture {
            descriptors: vec![(0, 1), (1, 2), (1, 1), (2, 3)],
            payload: vec![0u8; 7 * 0x10000],
            ..Default::default()
        };
        let info = parse_xex(&f.build()).expect("parses");
        assert_eq!(info.page_descriptor_count, 4);
        let sections = &info.sections;
        assert_eq!(sections.len(), 2, "code run merges 2+1 pages, header pages excluded");
        assert_eq!(sections[0].page_type, 1);
        assert_eq!(sections[0].type_name, "code");
        assert_eq!(sections[0].page_count, 3);
        assert_eq!(sections[0].va, 0x8201_0000);
        assert_eq!(sections[0].image_offset, 0x10000);
        assert_eq!(sections[0].size, 3 * 0x10000);
        assert_eq!(sections[1].page_type, 2);
        assert_eq!(sections[1].type_name, "data");
        assert_eq!(sections[1].page_count, 3);
        assert_eq!(sections[1].va, 0x8204_0000);
        // Every page accounted for across merged sections (1 header + 3 code + 3 data).
        let total: u64 = (info.sections.iter().map(|s| s.page_count as u64).sum::<u64>()) + 1;
        assert_eq!(total, 7);
    }

    #[test]
    fn basic_compression_reconstructs() {
        // Image: 4 bytes code + 4 zero bytes + 2 bytes data + zero tail.
        let f = XexFixture {
            descriptors: vec![(1, 1)],
            compression: 1,
            blocks: vec![(6, 4)],
            image_size: 0x10000,
            payload: vec![0x4e, 0x80, 0x00, 0x20, 0x38, 0x60],
            ..Default::default()
        };
        let data = f.build();
        let img = decompress(&data).expect("decompresses");
        assert_eq!(img.image.len(), 0x10000);
        assert_eq!(&img.image[..6], &[0x4e, 0x80, 0x00, 0x20, 0x38, 0x60]);
        assert!(img.image[6..10].iter().all(|&b| b == 0));
        assert_eq!(img.info.compression, "basic");
        assert_eq!(img.info.basic_blocks.len(), 1);
    }

    #[test]
    fn four_kb_pages_flag() {
        let f = XexFixture {
            image_flags: 0x1000_0000,
            descriptors: vec![(1, 4)],
            payload: vec![0u8; 4 * 0x1000],
            ..Default::default()
        };
        let info = parse_xex(&f.build()).expect("parses");
        assert_eq!(info.page_size, 0x1000);
        assert!(info.image_flag_names.contains(&"page-size-4kb".to_string()));
    }

    #[test]
    fn encrypted_payload_refuses_to_materialize() {
        let f = XexFixture {
            encryption: 1,
            payload: vec![0u8; 0x1000],
            ..Default::default()
        };
        let data = f.build();
        // Structure parse still works (headers are plaintext).
        let info = parse_xex(&data).expect("structure parses");
        assert_eq!(info.encryption, "aes");
        // Byte views refuse with the compliance message.
        let err = decompress(&data).expect_err("must refuse");
        assert!(err.to_string().contains("AES-encrypted"), "{err}");
        assert!(err.to_string().contains("keys are never bundled"), "{err}");
    }

    #[test]
    fn lzx_and_delta_refuse() {
        let f = XexFixture {
            compression: 2,
            payload: vec![0u8; 0x100],
            ..Default::default()
        };
        let err = decompress(&f.build()).expect_err("lzx refused");
        assert!(err.to_string().contains("LZX"), "{err}");
        let f = XexFixture {
            compression: 3,
            payload: vec![0u8; 0x100],
            ..Default::default()
        };
        let err = decompress(&f.build()).expect_err("delta refused");
        assert!(err.to_string().contains("delta"), "{err}");
    }

    #[test]
    fn entry_point_reads_verbatim() {
        let f = XexFixture::default();
        let info = parse_xex(&f.build()).expect("parses");
        assert_eq!(info.entry_point, Some(0x8200_1000));
    }

    #[test]
    fn dll_module_flag_maps_to_shared_library() {
        let f = XexFixture {
            module_flags: 0x08, // dll
            ..Default::default()
        };
        let bi = to_binary_info(&parse_xex(&f.build()).expect("parses"));
        assert_eq!(bi.kind, BinaryKind::Dll);
    }

    #[test]
    fn binary_info_bridge_round_trips_addresses() {
        let f = XexFixture {
            descriptors: vec![(0, 1), (1, 2), (2, 3)],
            payload: vec![0u8; 6 * 0x10000],
            ..Default::default()
        };
        let info = parse_xex(&f.build()).expect("parses");
        let bi = to_binary_info(&info);
        assert_eq!(bi.format, Format::Xex);
        assert_eq!(bi.arch, Arch::Ppc64);
        assert_eq!(bi.endian, Endian::Big);
        assert_eq!(bi.bits, 64);
        assert_eq!(bi.image_base, Some(0x8200_0000));
        assert_eq!(bi.entry_va, Some(0x8200_1000));
        assert_eq!(bi.sections.len(), 2, "header pages excluded, code+data kept");
        let code = &bi.sections[0];
        assert_eq!(code.name, ".xex-code");
        assert!(code.flags.exec);
        assert!(!code.flags.write);
        assert_eq!(code.va, Some(0x8201_0000));
        assert_eq!(code.file_off, 0x1_0000, "image offset");
        // VA ↔ image-offset round trip over the flat map.
        assert_eq!(bi.va_to_offset(0x8201_0000), Some(0x1_0000));
        assert_eq!(bi.offset_to_va(0x2_0000), Some(0x8202_0000));
        assert_eq!(bi.va_to_offset(0x8300_0000), None);
        assert!(bi.exec_sections().len() == 1);
        assert_eq!(bi.libs, vec!["xam.xex", "xboxkrnl.exe"]);
    }

    #[test]
    fn resources_and_tls_decode() {
        let f = XexFixture {
            resources: vec![("GFXDATA".into(), 0x8203_0000, 0x1234)],
            ..Default::default()
        };
        let info = parse_xex(&f.build()).expect("parses");
        assert_eq!(info.resources.len(), 1);
        assert_eq!(info.resources[0].name, "GFXDATA");
        assert_eq!(info.resources[0].address, 0x8203_0000);
        assert_eq!(info.resources[0].size, 0x1234);
    }

    #[test]
    fn garbage_and_truncation_reject() {
        assert!(!is_xex(b"ELF\x02..."));
        assert!(parse_xex(b"XEX2").is_err(), "4 bytes only");
        let f = XexFixture::default();
        let data = f.build();
        assert!(parse_xex(&data[..data.len() / 3]).is_err(), "truncated");
        // Corrupted security offset.
        let mut d = data.clone();
        d[0x10] = 0xFF;
        assert!(parse_xex(&d).is_err());
    }

    #[test]
    fn basic_block_overflow_rejects() {
        let f = XexFixture {
            descriptors: vec![(1, 1)],
            compression: 1,
            blocks: vec![(0xFFFF, 0)],
            image_size: 0x10000,
            payload: vec![0u8; 8],
            ..Default::default()
        };
        let err = decompress(&f.build()).expect_err("over-read refused");
        assert!(err.to_string().contains("past the payload"), "{err}");
    }

    #[test]
    fn xex1_magic_sniffs() {
        assert!(is_xex(b"XEX1\x00\x00"));
        assert!(is_xex(b"XEX0\x00\x00"));
        assert!(is_xex(b"XEX2\x00\x00"));
        assert!(!is_xex(b"XEXP\x00\x00"));
        assert!(!is_xex(b"NOPE"));
    }
}
