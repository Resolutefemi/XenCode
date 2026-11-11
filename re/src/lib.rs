//! XenRE — the binary reverse-engineering toolkit inside XenCode (Phase 7).
//!
//! One native Rust layer that parses executables, renders hex/strings/imports
//! views and disassembles code — no Python, no external runtimes, one static
//! binary. Orchestrated backends (Ghidra headless, jadx, ILSpy, RetDec) land
//! in later waves; everything in this crate is fully native.
//!
//! Layering:
//! - [`analyzer`] parses ELF / PE / Mach-O (goblin) into one unified
//!   [`analyzer::BinaryInfo`]: arch, endianness, entry point, sections,
//!   symbols, imports, exports, libs, plus a virtual-address ↔ file-offset
//!   map used by every other view.
//! - [`strings`] extracts printable ASCII and UTF-16LE runs with offsets.
//! - [`hexview`] renders byte ranges as offset/hex/ASCII gutter rows.
//! - [`disasm`] disassembles byte ranges through capstone (x86, x64, ARM,
//!   ARM64, PPC/Xenon) with arch auto-detection from [`analyzer::BinaryInfo`].
//! - [`functions`] builds the function list from symbol tables and the entry
//!   point.
//! - Phase 7.2 adds the app-package surface:
//!   [`apk`] (AndroidManifest via [`axml`], DEX class inventory via [`dex`],
//!   native libs, signing-block detection), [`ipa`] (Info.plist via
//!   [`plist`], embedded Mach-O reuses [`analyzer`]) and [`jadx`] — the
//!   first orchestrated backend: probe, managed on-demand download from
//!   GitHub releases, headless decompile + output inventory.
//!
//! # Compliance guardrail
//!
//! XenRE is for analysis of authorized, user-owned targets: CTF images, own
//! binaries, builds with debug symbols, classroom samples. No DRM keys and
//! no circumvention tooling is bundled — XEX decryption keys are never
//! shipped, the later XEX parser only reads container structure.

pub mod analyzer;
pub mod apk;
pub mod axml;
pub mod dex;
pub mod disasm;
pub mod functions;
pub mod hexview;
pub mod ipa;
pub mod jadx;
pub mod plist;
pub mod strings;

pub use analyzer::{
    analyze_file, analyze_bytes, Arch, BinaryInfo, BinaryKind, Endian, Export, Format, Import,
    Section, SectionFlags, Symbol, SymbolKind, SymbolScope,
};
pub use apk::{analyze_apk, is_apk, ApkComponent, ApkInfo, ApkOpts, ApkSigning, DexFileInfo, NativeLib};
pub use axml::{parse_axml, AttrValue, AxmlNode};
pub use disasm::{arch_from_binary, disasm_bytes, parse_arch, render_insn, DisArch, Insn};
pub use dex::{access_flags_str, parse_dex, DexClass, DexInfo};
pub use functions::{functions, Function};
pub use hexview::{clamp_range, hex_rows, render_hex_row, HexRow, DEFAULT_ROW_WIDTH};
pub use ipa::{analyze_ipa, is_ipa, IpaInfo, IpaOpts};
pub use jadx::{
    ensure as jadx_ensure, fetch_url, install_root, probe as jadx_probe, run as jadx_run,
    JadxOpts, JadxRun, JadxStatus,
};
pub use plist::{parse_plist, PlistValue};
pub use strings::{extract_strings, FoundString, StrEnc};

/// Largest file the analyzer will load into memory (512 MiB — RE targets are
/// rarely bigger; the cap keeps a stray `xencode re <dir>` honest).
pub const MAX_INPUT_BYTES: u64 = 512 * 1024 * 1024;
