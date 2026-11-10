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
//!
//! # Compliance guardrail
//!
//! XenRE is for analysis of authorized, user-owned targets: CTF images, own
//! binaries, builds with debug symbols, classroom samples. No DRM keys and
//! no circumvention tooling is bundled — XEX decryption keys are never
//! shipped, the later XEX parser only reads container structure.

pub mod analyzer;
pub mod disasm;
pub mod functions;
pub mod hexview;
pub mod strings;

pub use analyzer::{
    analyze_file, analyze_bytes, Arch, BinaryInfo, BinaryKind, Endian, Export, Format, Import,
    Section, SectionFlags, Symbol, SymbolKind, SymbolScope,
};
pub use disasm::{arch_from_binary, disasm_bytes, parse_arch, render_insn, DisArch, Insn};
pub use functions::{functions, Function};
pub use hexview::{clamp_range, hex_rows, render_hex_row, HexRow, DEFAULT_ROW_WIDTH};
pub use strings::{extract_strings, FoundString, StrEnc};

/// Largest file the analyzer will load into memory (512 MiB — RE targets are
/// rarely bigger; the cap keeps a stray `xencode re <dir>` honest).
pub const MAX_INPUT_BYTES: u64 = 512 * 1024 * 1024;
