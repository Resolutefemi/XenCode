//! [`analyzer`] — parse ELF / PE / Mach-O into one unified [`BinaryInfo`].
//!
//! goblin does the container parsing; this module normalizes the three
//! formats into one model so the hex viewer, strings extractor, function
//! list and disassembler never care which format they are looking at. Every
//! view also shares the address map ([`BinaryInfo::va_to_offset`] /
//! [`BinaryInfo::offset_to_va`]) built here from program headers (ELF),
//! segments (Mach-O) or the section table (PE).
//!
//! Imports/exports are defined format-independently:
//! - ELF: dynamic undefined symbols (`.dynsym`) import; defined `.dynsym`
//!   globals with default visibility export.
//! - PE: the import table (DLL + name/ordinal) imports; the export
//!   directory exports.
//! - Mach-O: undefined external `nlist` entries import; the LC_DYLD export
//!   trie (`mach.exports()`) exports.

use std::fmt;
use std::path::Path;

use anyhow::{bail, Context, Result};
use serde::{Deserialize, Serialize};

/// File container format.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Format {
    Elf,
    Pe,
    MachO,
    Coff,
    /// Xbox 360 XEX container (Phase 7.3) — structural info only; byte views
    /// run on the decompressed image ([`crate::xex::decompress`]).
    Xex,
    Unknown,
}

impl fmt::Display for Format {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Format::Elf => "ELF",
            Format::Pe => "PE",
            Format::MachO => "Mach-O",
            Format::Coff => "COFF",
            Format::Xex => "XEX",
            Format::Unknown => "unknown",
        })
    }
}

/// Target CPU architecture (superset — the disassembler narrows this).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Arch {
    X86,
    X86_64,
    Arm,
    Arm64,
    Ppc,
    Ppc64,
    Mips,
    Mips64,
    Riscv,
    Other(String),
}

impl fmt::Display for Arch {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Arch::X86 => "x86",
            Arch::X86_64 => "x86_64",
            Arch::Arm => "arm",
            Arch::Arm64 => "arm64",
            Arch::Ppc => "ppc",
            Arch::Ppc64 => "ppc64",
            Arch::Mips => "mips",
            Arch::Mips64 => "mips64",
            Arch::Riscv => "riscv",
            Arch::Other(s) => s,
        })
    }
}

/// Byte order.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum Endian {
    Little,
    Big,
}

impl fmt::Display for Endian {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Endian::Little => "little",
            Endian::Big => "big",
        })
    }
}

/// What the file is for, in loose portable terms.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum BinaryKind {
    /// Executable image (ELF ET_EXEC/ET_DYN, PE image, Mach-O execute).
    Exe,
    /// Shared library / DLL (PE DLL, Mach-O dylib/bundle).
    Dll,
    /// Relocatable object file (ELF ET_REL, COFF, Mach-O object).
    Object,
    /// Core dump (ELF ET_CORE, Mach-O MH_CORE).
    Core,
    Other(String),
}

impl fmt::Display for BinaryKind {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            BinaryKind::Exe => "executable",
            BinaryKind::Dll => "shared library",
            BinaryKind::Object => "object file",
            BinaryKind::Core => "core dump",
            BinaryKind::Other(s) => s,
        })
    }
}

/// One section (ELF section header, PE section table row, Mach-O section).
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Section {
    pub name: String,
    /// Virtual address at load time (`None` for non-loadable sections).
    pub va: Option<u64>,
    /// In-memory size (memsz / virtual_size / sect.size).
    pub size: u64,
    /// Offset of the section contents in the file.
    pub file_off: u64,
    /// On-disk size (filesz — `size` minus BSS tail).
    pub file_size: u64,
    pub flags: SectionFlags,
}

impl fmt::Display for Section {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.name)?;
        if let Some(va) = self.va {
            write!(f, " @ {va:#x}")?;
        }
        write!(f, " ({} bytes)", self.size)?;
        let mut fl = String::new();
        if self.flags.read {
            fl.push('r');
        }
        if self.flags.write {
            fl.push('w');
        }
        if self.flags.exec {
            fl.push('x');
        }
        if self.flags.tls {
            fl.push('t');
        }
        if self.flags.debug {
            fl.push('d');
        }
        if !fl.is_empty() {
            write!(f, " [{fl}]")?;
        }
        Ok(())
    }
}

/// Permission/usage flags for a section.
#[derive(Debug, Clone, Copy, Default, Serialize, Deserialize)]
pub struct SectionFlags {
    pub read: bool,
    pub write: bool,
    pub exec: bool,
    /// Debug / non-loadable (`.debug*`, `__DWARF`).
    pub debug: bool,
    /// Thread-local storage.
    pub tls: bool,
}

/// One symbol from a symbol table.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Symbol {
    pub name: String,
    /// Virtual address (0 for undefined).
    pub va: u64,
    /// Size in bytes (0 when unknown).
    pub size: u64,
    pub kind: SymbolKind,
    pub scope: SymbolScope,
    /// Owning section name when known.
    pub section: Option<String>,
    /// True when the symbol is defined inside this binary.
    pub defined: bool,
    /// True when the symbol comes from the *dynamic* view (.dynsym, PE
    /// import/export tables, Mach-O export trie) rather than the full
    /// static symbol table.
    pub dynamic: bool,
}

impl fmt::Display for Symbol {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.name)?;
        if self.va != 0 {
            write!(f, " @ {:#x}", self.va)?;
        }
        if self.size != 0 {
            write!(f, " ({} bytes)", self.size)?;
        }
        write!(f, " [{:?}/{:?}]", self.kind, self.scope)?;
        Ok(())
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum SymbolKind {
    Func,
    Object,
    File,
    Section,
    Other,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum SymbolScope {
    Local,
    Global,
    Weak,
}

/// An imported symbol: what this binary pulls in from outside.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Import {
    pub name: String,
    /// Providing library (PE/Mach-O always name one).
    pub dll: Option<String>,
    /// Import-by-ordinal (PE).
    pub ordinal: Option<u16>,
}

impl fmt::Display for Import {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match (&self.dll, &self.ordinal) {
            (Some(d), Some(o)) if self.name.is_empty() => write!(f, "{d}!ordinal#{o}"),
            (Some(d), _) => write!(f, "{d}!{}", self.name),
            _ => write!(f, "{}", self.name),
        }
    }
}

/// An exported symbol: what this binary offers to others.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Export {
    pub name: String,
    pub va: u64,
    pub size: u64,
    pub ordinal: Option<u32>,
}

impl fmt::Display for Export {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{} @ {:#x}", self.name, self.va)?;
        if let Some(o) = self.ordinal {
            write!(f, " (#{o})")?;
        }
        Ok(())
    }
}

/// A contiguous file-backed virtual range: `[va_start, va_end)` ↔
/// `[off_start, off_start + len)`.
#[derive(Debug, Clone, Copy)]
struct AddrMap {
    va_start: u64,
    va_end: u64,
    off_start: u64,
    len: u64,
}

/// The unified analysis result every XenRE view consumes.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct BinaryInfo {
    pub format: Format,
    pub arch: Arch,
    pub endian: Endian,
    /// 32 or 64 (0 when unknown).
    pub bits: u8,
    pub kind: BinaryKind,
    /// Position-independent (ELF ET_DYN exec, PE DYNAMIC_BASE).
    pub pie: bool,
    /// Entry point virtual address (not for object files).
    pub entry_va: Option<u64>,
    /// Base virtual address where the image maps.
    pub image_base: Option<u64>,
    /// File size in bytes.
    pub size: u64,
    pub sections: Vec<Section>,
    /// Static symbol table first, dynamic symbols appended (`dynamic: true`).
    pub symbols: Vec<Symbol>,
    pub imports: Vec<Import>,
    pub exports: Vec<Export>,
    /// ELF `.interp` content.
    pub interpreter: Option<String>,
    /// Required libraries: DT_NEEDED, PE import DLLs, Mach-O dylibs.
    pub libs: Vec<String>,
    /// va ↔ offset ranges (private — use the conversion helpers).
    #[serde(skip)]
    addr_map: Vec<AddrMap>,
}

impl BinaryInfo {
    /// Crate-internal fixture constructor (tests, GUI sample pages): an
    /// empty 64-bit x86_64 executable shell with every collection clear.
    #[doc(hidden)]
    pub(crate) fn fixture() -> BinaryInfo {
        BinaryInfo {
            format: Format::Elf,
            arch: Arch::X86_64,
            endian: Endian::Little,
            bits: 64,
            kind: BinaryKind::Exe,
            pie: false,
            entry_va: None,
            image_base: None,
            size: 0,
            sections: Vec::new(),
            symbols: Vec::new(),
            imports: Vec::new(),
            exports: Vec::new(),
            interpreter: None,
            libs: Vec::new(),
            addr_map: Vec::new(),
        }
    }

    /// File offset for a virtual address, if it lands in a mapped range.
    pub fn va_to_offset(&self, va: u64) -> Option<u64> {
        self.addr_map
            .iter()
            .find(|m| va >= m.va_start && va < m.va_end)
            .map(|m| m.off_start + (va - m.va_start))
    }

    /// Virtual address for a file offset, if it maps back.
    pub fn offset_to_va(&self, off: u64) -> Option<u64> {
        self.addr_map
            .iter()
            .find(|m| off >= m.off_start && off < m.off_start + m.len)
            .map(|m| m.va_start + (off - m.off_start))
    }

    /// Section containing a virtual address.
    pub fn section_at_va(&self, va: u64) -> Option<&Section> {
        self.sections.iter().find(|s| {
            s.va.map(|base| va >= base && va < base + s.size).unwrap_or(false)
        })
    }

    /// Executable sections, in file order.
    pub fn exec_sections(&self) -> Vec<&Section> {
        self.sections.iter().filter(|s| s.flags.exec).collect()
    }

    /// Replace the va↔offset map from flat `(va_start, va_end, off_start)`
    /// ranges. Crate-internal — the XEX bridge builds one flat image map.
    pub(crate) fn set_addr_maps(&mut self, maps: impl IntoIterator<Item = (u64, u64, u64)>) {
        self.addr_map = maps
            .into_iter()
            .map(|(va_start, va_end, off_start)| AddrMap {
                va_start,
                va_end,
                off_start,
                len: va_end.saturating_sub(va_start),
            })
            .collect();
    }
}

/// Parse and analyze a file from disk.
pub fn analyze_file(path: impl AsRef<Path>) -> Result<BinaryInfo> {
    let path = path.as_ref();
    let meta = std::fs::metadata(path)
        .with_context(|| format!("cannot stat {}", path.display()))?;
    if meta.is_dir() {
        bail!("{} is a directory — XenRE analyzes files", path.display());
    }
    if meta.len() > crate::MAX_INPUT_BYTES {
        bail!(
            "{} is {} bytes — over the {} analysis cap",
            path.display(),
            meta.len(),
            crate::MAX_INPUT_BYTES
        );
    }
    let bytes = std::fs::read(path)
        .with_context(|| format!("cannot read {}", path.display()))?;
    analyze_bytes(&bytes)
}

/// Analyze an in-memory image.
pub fn analyze_bytes(data: &[u8]) -> Result<BinaryInfo> {
    use goblin::Object;

    // XEX containers route through the dedicated keyless parser (Phase 7.3).
    if crate::xex::is_xex(data) {
        let xex = crate::xex::parse_xex(data)?;
        let mut info = crate::xex::to_binary_info(&xex);
        info.size = data.len() as u64;
        return Ok(info);
    }

    let mut info = match Object::parse(data)
        .map_err(|e| anyhow::anyhow!("format parse failed: {e}"))?
    {
        Object::Elf(elf) => analyze_elf(&elf, data.len())?,
        Object::PE(pe) => analyze_pe(&pe, data)?,
        Object::Mach(goblin::mach::Mach::Binary(macho)) => analyze_macho(&macho, data)?,
        Object::Mach(goblin::mach::Mach::Fat(fat)) => bail!(
            "fat/universal Mach-O with {} architectures — slice out one arch first (7.2)",
            fat.arches().map(|a| a.len()).unwrap_or(0)
        ),
        Object::Archive(_) => bail!("ar archives land with APK/IPA support (7.2)"),
        Object::TE(_) => bail!("UEFI/TE images land with the firmware track (7.3+)"),
        Object::COFF(coff) => analyze_coff(&coff, data.len())?,
        Object::Unknown(magic) => bail!(
            "unrecognized image (magic {magic:#x}) — ELF/PE/Mach-O are handled in 7.1; \
             XEX/JAR/DEX land in 7.2/7.3"
        ),
        _ => bail!("unrecognized image format — ELF/PE/Mach-O are handled in 7.1"),
    };
    info.size = data.len() as u64;
    Ok(info)
}

// ---------------------------------------------------------------- ELF ----

fn analyze_elf(elf: &goblin::elf::Elf<'_>, file_len: usize) -> Result<BinaryInfo> {
    use goblin::elf::header as eh;
    use goblin::elf::program_header as ph;
    use goblin::elf::section_header as sh;
    use goblin::elf::sym as es;

    let (arch, bits) = match elf.header.e_machine {
        eh::EM_X86_64 => (Arch::X86_64, 64),
        eh::EM_386 => (Arch::X86, 32),
        eh::EM_AARCH64 => (Arch::Arm64, 64),
        eh::EM_ARM => (Arch::Arm, 32),
        eh::EM_PPC => (Arch::Ppc, 32),
        eh::EM_PPC64 => (Arch::Ppc64, 64),
        eh::EM_MIPS => (Arch::Mips, 32),
        eh::EM_RISCV => (Arch::Riscv, 32),
        other => (Arch::Other(format!("EM_{other}")), 0),
    };
    let endian = if elf.little_endian { Endian::Little } else { Endian::Big };
    let kind = if elf.header.e_type == eh::ET_REL {
        BinaryKind::Object
    } else if elf.header.e_type == eh::ET_CORE {
        BinaryKind::Core
    } else if elf.is_lib {
        BinaryKind::Dll
    } else {
        BinaryKind::Exe
    };

    // va ↔ offset from PT_LOAD segments.
    let mut addr_map = Vec::new();
    for ph in elf.program_headers.iter() {
        if ph.p_type == ph::PT_LOAD && ph.p_filesz > 0 {
            addr_map.push(AddrMap {
                va_start: ph.p_vaddr,
                va_end: ph.p_vaddr + ph.p_filesz,
                off_start: ph.p_offset,
                len: ph.p_filesz,
            });
        }
    }

    let mut sections = Vec::new();
    for s in elf.section_headers.iter() {
        let name = elf.shdr_strtab.get_at(s.sh_name as usize).unwrap_or("").to_string();
        let is_debug = name.starts_with(".debug") || name == ".symtab" || name == ".strtab";
        let is_tls = s.sh_flags & sh::SHF_TLS as u64 != 0;
        let nobits = s.sh_type == sh::SHT_NOBITS;
        let file_size = if nobits {
            0
        } else {
            s.sh_offset
                .saturating_add(s.sh_size)
                .min(file_len as u64)
                .saturating_sub(s.sh_offset)
        };
        sections.push(Section {
            name,
            va: Some(s.sh_addr),
            size: s.sh_size,
            file_off: s.sh_offset,
            file_size,
            flags: SectionFlags {
                read: true,
                write: s.sh_flags & sh::SHF_WRITE as u64 != 0,
                exec: s.sh_flags & sh::SHF_EXECINSTR as u64 != 0,
                debug: is_debug,
                tls: is_tls,
            },
        });
    }

    // Symbols: static .symtab first, then .dynsym (dynamic: true).
    let section_name = |idx: usize| -> Option<String> {
        elf.section_headers
            .get(idx)
            .and_then(|s| elf.shdr_strtab.get_at(s.sh_name as usize))
            .map(str::to_string)
    };
    let mut symbols = Vec::new();
    let static_syms: Vec<_> = elf.syms.iter().collect();
    for sym in &static_syms {
        push_elf_symbol(
            &mut symbols,
            elf.strtab.get_at(sym.st_name as usize).unwrap_or(""),
            sym,
            &section_name,
            false,
        );
    }
    let dyn_syms: Vec<_> = elf.dynsyms.iter().collect();
    for sym in &dyn_syms {
        push_elf_symbol(
            &mut symbols,
            elf.dynstrtab.get_at(sym.st_name as usize).unwrap_or(""),
            sym,
            &section_name,
            true,
        );
    }

    // Imports: dynamic undefined symbols.
    let mut imports = Vec::new();
    for sym in &dyn_syms {
        if sym.st_shndx as u32 == sh::SHN_UNDEF && sym.st_value == 0 {
            if let Some(name) = elf.dynstrtab.get_at(sym.st_name as usize) {
                if !name.is_empty() {
                    imports.push(Import {
                        name: name.to_string(),
                        dll: None,
                        ordinal: None,
                    });
                }
            }
        }
    }

    // Exports: defined dynamic globals/weak with default visibility.
    let mut exports = Vec::new();
    for sym in &dyn_syms {
        let defined_global = sym.st_shndx as u32 != sh::SHN_UNDEF
            && sym.st_value != 0
            && sym.st_visibility() == es::STV_DEFAULT
            && (sym.st_bind() == es::STB_GLOBAL || sym.st_bind() == es::STB_WEAK);
        if defined_global {
            if let Some(name) = elf.dynstrtab.get_at(sym.st_name as usize) {
                if !name.is_empty() {
                    exports.push(Export {
                        name: name.to_string(),
                        va: sym.st_value,
                        size: sym.st_size,
                        ordinal: None,
                    });
                }
            }
        }
    }

    let entry_va = (elf.entry != 0).then_some(elf.entry);
    let image_base = elf
        .program_headers
        .iter()
        .find(|ph| ph.p_type == ph::PT_LOAD)
        .map(|ph| ph.p_vaddr.saturating_sub(ph.p_offset));

    Ok(BinaryInfo {
        format: Format::Elf,
        arch,
        endian,
        bits,
        kind,
        pie: elf.header.e_type == eh::ET_DYN && !elf.is_lib,
        entry_va,
        image_base,
        size: 0,
        sections,
        symbols,
        imports,
        exports,
        interpreter: elf.interpreter.map(str::to_string),
        libs: elf.libraries.iter().map(|l| (*l).to_string()).collect(),
        addr_map,
    })
}

fn push_elf_symbol(
    symbols: &mut Vec<Symbol>,
    name: &str,
    sym: &goblin::elf::sym::Sym,
    section_name: &dyn Fn(usize) -> Option<String>,
    dynamic: bool,
) {
    use goblin::elf::section_header as sh;
    use goblin::elf::sym as es;

    // Null / anonymous section symbols carry no name — skip them.
    if name.is_empty() {
        return;
    }

    let kind = match sym.st_info & 0xf {
        es::STT_FUNC => SymbolKind::Func,
        es::STT_OBJECT => SymbolKind::Object,
        es::STT_FILE => SymbolKind::File,
        es::STT_SECTION => SymbolKind::Section,
        _ => SymbolKind::Other,
    };
    let scope = match sym.st_bind() {
        es::STB_GLOBAL => SymbolScope::Global,
        es::STB_WEAK => SymbolScope::Weak,
        _ => SymbolScope::Local,
    };
    let defined = sym.st_shndx as u32 != sh::SHN_UNDEF;
    let section = if defined && sym.st_shndx < 0xff00 {
        section_name(sym.st_shndx)
    } else {
        None
    };
    symbols.push(Symbol {
        name: name.to_string(),
        va: sym.st_value,
        size: sym.st_size,
        kind,
        scope,
        section,
        defined,
        dynamic,
    });
}

// ----------------------------------------------------------------- PE ----

fn analyze_pe(pe: &goblin::pe::PE<'_>, data: &[u8]) -> Result<BinaryInfo> {
    use goblin::pe::section_table as ps;

    let machine = pe.header.coff_header.machine;
    let (arch, bits) = match machine {
        goblin::pe::header::COFF_MACHINE_X86_64 => (Arch::X86_64, 64),
        goblin::pe::header::COFF_MACHINE_X86 => (Arch::X86, 32),
        goblin::pe::header::COFF_MACHINE_ARM64 => (Arch::Arm64, 64),
        goblin::pe::header::COFF_MACHINE_ARMNT | goblin::pe::header::COFF_MACHINE_ARM => {
            (Arch::Arm, 32)
        }
        goblin::pe::header::COFF_MACHINE_RISCV64 => (Arch::Riscv, 64),
        goblin::pe::header::COFF_MACHINE_RISCV32 => (Arch::Riscv, 32),
        other => (Arch::Other(format!("machine=0x{other:x}")), 0),
    };

    let image_base = pe.image_base;
    let entry_va = (pe.entry != 0).then(|| image_base + pe.entry as u64);
    let pie = pe
        .header
        .optional_header
        .as_ref()
        .map(|o| o.windows_fields.dll_characteristics & 0x0040 != 0) // DYNAMIC_BASE
        .unwrap_or(false);
    let kind = if pe.is_lib { BinaryKind::Dll } else { BinaryKind::Exe };

    let mut sections = Vec::new();
    let mut addr_map = Vec::new();
    for s in pe.sections.iter() {
        let name = pe_section_name(&s.name);
        let is_debug = name.starts_with(".debug");
        let is_tls = name == ".tls";
        let chars = s.characteristics;
        let raw_size = s.size_of_raw_data as u64;
        let file_off = s.pointer_to_raw_data as u64;
        let file_size = if raw_size == 0 {
            0
        } else {
            file_off
                .saturating_add(raw_size)
                .min(data.len() as u64)
                .saturating_sub(file_off)
        };
        let va = image_base + s.virtual_address as u64;
        if file_size > 0 {
            addr_map.push(AddrMap {
                va_start: va,
                va_end: va + (s.virtual_size as u64).max(raw_size),
                off_start: file_off,
                len: file_size,
            });
        }
        sections.push(Section {
            name,
            va: Some(va),
            size: s.virtual_size as u64,
            file_off,
            file_size,
            flags: SectionFlags {
                read: chars & ps::IMAGE_SCN_MEM_READ != 0 || chars & ps::IMAGE_SCN_CNT_CODE != 0,
                write: chars & ps::IMAGE_SCN_MEM_WRITE != 0,
                exec: chars & ps::IMAGE_SCN_MEM_EXECUTE != 0 || chars & ps::IMAGE_SCN_CNT_CODE != 0,
                debug: is_debug,
                tls: is_tls,
            },
        });
    }

    let mut imports = Vec::new();
    let mut libs = Vec::new();
    for imp in &pe.imports {
        if !libs.iter().any(|l| l == imp.dll) {
            libs.push(imp.dll.to_string());
        }
        imports.push(Import {
            name: imp.name.to_string(),
            dll: Some(imp.dll.to_string()),
            ordinal: (imp.ordinal != 0).then_some(imp.ordinal),
        });
    }

    let mut exports = Vec::new();
    for exp in &pe.exports {
        if let Some(name) = exp.name {
            exports.push(Export {
                name: name.to_string(),
                va: image_base + exp.rva as u64,
                size: exp.size as u64,
                ordinal: None, // goblin's PE Export carries no ordinal
            });
        }
    }

    // COFF symbol table (mingw / debug builds carry one; stripped images
    // don't). Parsed by hand — goblin exposes it only on `Coff`.
    let symbols = parse_coff_symbols(data, &pe.header.coff_header, image_base);

    Ok(BinaryInfo {
        format: Format::Pe,
        arch,
        endian: Endian::Little,
        bits,
        kind,
        pie,
        entry_va,
        image_base: Some(image_base),
        size: 0,
        sections,
        symbols,
        imports,
        exports,
        interpreter: None,
        libs,
        addr_map,
    })
}

/// C-string trim for the 8-byte PE section name field.
fn pe_section_name(raw: &[u8; 8]) -> String {
    let end = raw.iter().position(|&b| b == 0).unwrap_or(8);
    String::from_utf8_lossy(&raw[..end]).into_owned()
}

/// Parse a raw COFF symbol table (18-byte records + trailing string table).
/// Used for PE images that still carry debug symbols.
fn parse_coff_symbols(
    data: &[u8],
    coff: &goblin::pe::header::CoffHeader,
    image_base: u64,
) -> Vec<Symbol> {
    let n = coff.number_of_symbol_table as usize;
    if n == 0 || coff.pointer_to_symbol_table == 0 {
        return Vec::new();
    }
    let table_off = coff.pointer_to_symbol_table as usize;
    let table_end = table_off + n * 18;
    if table_end > data.len() {
        return Vec::new();
    }
    // String table starts right after the symbol records.
    let strtab = data.get(table_end..).unwrap_or(&[]);
    let str_at = |off: usize| -> Option<String> {
        let rest = strtab.get(off..)?;
        let end = rest.iter().position(|&b| b == 0).unwrap_or(rest.len());
        Some(String::from_utf8_lossy(&rest[..end]).into_owned())
    };

    let mut out = Vec::new();
    let mut i = 0usize;
    while i < n {
        let rec = &data[table_off + i * 18..table_off + (i + 1) * 18];
        let name: String = if rec[0..4] == [0, 0, 0, 0] {
            let off = u32::from_le_bytes([rec[4], rec[5], rec[6], rec[7]]) as usize;
            str_at(off.saturating_sub(4)).unwrap_or_default()
        } else {
            let end = rec[..8].iter().position(|&b| b == 0).unwrap_or(8);
            String::from_utf8_lossy(&rec[..end]).into_owned()
        };
        if !name.is_empty() {
            let value = u32::from_le_bytes([rec[8], rec[9], rec[10], rec[11]]);
            let section_number = i16::from_le_bytes([rec[12], rec[13]]);
            let sym_type = u16::from_le_bytes([rec[14], rec[15]]);
            let storage_class = rec[16];
            let defined = section_number > 0;
            let kind = if sym_type & 0x20 != 0 {
                SymbolKind::Func
            } else {
                SymbolKind::Other
            };
            let scope = if storage_class == 2 {
                SymbolScope::Global
            } else {
                SymbolScope::Local
            };
            out.push(Symbol {
                name,
                // In images the value is an RVA; in objects a section offset.
                va: if defined { image_base + value as u64 } else { value as u64 },
                size: 0,
                kind,
                scope,
                section: None,
                defined,
                dynamic: false,
            });
        }
        // Primary record + its auxiliary records (each 18 bytes).
        i += 1 + rec[17] as usize;
    }
    out
}

// --------------------------------------------------------------- COFF ----

fn analyze_coff(coff: &goblin::pe::Coff<'_>, file_len: usize) -> Result<BinaryInfo> {
    let machine = coff.header.machine;
    let (arch, bits) = match machine {
        goblin::pe::header::COFF_MACHINE_X86_64 => (Arch::X86_64, 64),
        goblin::pe::header::COFF_MACHINE_X86 => (Arch::X86, 32),
        goblin::pe::header::COFF_MACHINE_ARM64 => (Arch::Arm64, 64),
        _ => (Arch::Other(format!("coff machine=0x{machine:x}")), 0),
    };
    let mut sections = Vec::new();
    let mut addr_map = Vec::new();
    for s in coff.sections.iter() {
        let name = pe_section_name(&s.name);
        let is_debug = name.starts_with(".debug");
        let chars = s.characteristics;
        let raw_size = s.size_of_raw_data as u64;
        let file_off = s.pointer_to_raw_data as u64;
        let file_size = if raw_size == 0 {
            0
        } else {
            file_off.saturating_add(raw_size).min(file_len as u64).saturating_sub(file_off)
        };
        let va = s.virtual_address as u64;
        if file_size > 0 {
            addr_map.push(AddrMap {
                va_start: va,
                va_end: va + file_size,
                off_start: file_off,
                len: file_size,
            });
        }
        sections.push(Section {
            name,
            va: Some(va),
            size: s.virtual_size as u64,
            file_off,
            file_size,
            flags: SectionFlags {
                read: true,
                write: chars & ps_mem_write() != 0,
                exec: chars & ps_mem_exec() != 0,
                debug: is_debug,
                tls: false,
            },
        });
    }

    // Symbols: goblin's SymbolTable yields (name, record) pairs.
    let mut symbols = Vec::new();
    if let Some(syms) = &coff.symbols {
        for i in 0..(coff.header.number_of_symbol_table as usize) {
            let Some((name, sym)) = syms.get(i) else { continue };
            let Some(name) = name else { continue };
            let defined = sym.section_number > 0;
            symbols.push(Symbol {
                name: name.to_string(),
                va: sym.value as u64,
                size: 0,
                kind: if sym.derived_type() == 0x20 {
                    SymbolKind::Func
                } else {
                    SymbolKind::Other
                },
                scope: if sym.storage_class == 2 {
                    SymbolScope::Global
                } else {
                    SymbolScope::Local
                },
                section: None,
                defined,
                dynamic: false,
            });
        }
    }

    Ok(BinaryInfo {
        format: Format::Coff,
        arch,
        endian: Endian::Little,
        bits,
        kind: BinaryKind::Object,
        pie: false,
        entry_va: None,
        image_base: None,
        size: 0,
        sections,
        symbols,
        imports: Vec::new(),
        exports: Vec::new(),
        interpreter: None,
        libs: Vec::new(),
        addr_map,
    })
}

fn ps_mem_write() -> u32 {
    goblin::pe::section_table::IMAGE_SCN_MEM_WRITE
}
fn ps_mem_exec() -> u32 {
    goblin::pe::section_table::IMAGE_SCN_MEM_EXECUTE
}

// ------------------------------------------------------------- Mach-O ----

fn analyze_macho(macho: &goblin::mach::MachO<'_>, data: &[u8]) -> Result<BinaryInfo> {
    use goblin::mach::constants::cputype;
    use goblin::mach::header as mh;

    let file_len = data.len();
    let (arch, bits) = match macho.header.cputype {
        cputype::CPU_TYPE_X86_64 => (Arch::X86_64, 64),
        cputype::CPU_TYPE_X86 => (Arch::X86, 32),
        cputype::CPU_TYPE_ARM64 => (Arch::Arm64, 64),
        cputype::CPU_TYPE_ARM => (Arch::Arm, 32),
        cputype::CPU_TYPE_POWERPC => (Arch::Ppc, 32),
        cputype::CPU_TYPE_POWERPC64 => (Arch::Ppc64, 64),
        other => (Arch::Other(format!("cputype={other}")), 0),
    };
    let endian = if macho.little_endian { Endian::Little } else { Endian::Big };
    let kind = match macho.header.filetype {
        mh::MH_EXECUTE => BinaryKind::Exe,
        mh::MH_DYLIB | mh::MH_BUNDLE => BinaryKind::Dll,
        mh::MH_OBJECT => BinaryKind::Object,
        mh::MH_CORE => BinaryKind::Core,
        other => BinaryKind::Other(format!("filetype={other}")),
    };

    // Segments → sections + va↔offset map.
    let mut addr_map = Vec::new();
    let mut sections = Vec::new();
    for seg in macho.segments.iter() {
        if seg.filesize > 0 && seg.nsects > 0 {
            addr_map.push(AddrMap {
                va_start: seg.vmaddr,
                va_end: seg.vmaddr + seg.filesize.min(file_len as u64),
                off_start: seg.fileoff,
                len: seg.filesize.min(file_len as u64),
            });
        }
        let writable = seg.initprot & 0x2 != 0; // VM_PROT_WRITE
        let Ok(sects) = seg.sections() else { continue };
        for (sect, _sd) in sects {
                        let cstr = |raw: &[u8; 16]| -> String {
                let end = raw.iter().position(|&b| b == 0).unwrap_or(16);
                String::from_utf8_lossy(&raw[..end]).into_owned()
            };
            let name = cstr(&sect.sectname);
            let segname = cstr(&sect.segname);
            let flags = sect.flags;
            // S_ATTR_PURE_INSTRUCTIONS | S_ATTR_SOME_INSTRUCTIONS | stubs.
            let exec = flags & 0x8000_0000 != 0 || flags & 0x400 != 0 || flags & 0x8 == 0x8;
            let zerofill = flags & 0x1 != 0 || flags & 0xc == 0xc || flags & 0x12 == 0x12;
            let tls = flags & 0x1f >= 0x11 && flags & 0x1f <= 0x15;
            let file_size = if zerofill { 0 } else { sect.size.min(file_len as u64) };
            sections.push(Section {
                name: format!("{segname},{name}"),
                va: Some(sect.addr),
                size: sect.size,
                file_off: seg.fileoff + sect.offset as u64,
                file_size,
                flags: SectionFlags {
                    read: true,
                    write: writable && !zerofill,
                    exec,
                    debug: segname.starts_with("__DWARF") || flags & 0x0200_0000 != 0,
                    tls,
                },
            });
        }
    }

    // Static symbol table.
    let mut symbols = Vec::new();
    if let Some(syms) = &macho.symbols {
        for entry in syms.iter() {
            let Ok((name, nlist)) = entry else { continue };
            let n_type = nlist.n_type;
            let n_type_mask = n_type & 0x0e; // N_TYPE
            let ext = n_type & 0x01 != 0; // N_EXT
            let weak = nlist.n_desc & 0x80 != 0; // N_WEAK_DEF
            let section = if n_type_mask == 0x0e {
                sections
                    .get(nlist.n_sect.saturating_sub(1) as usize)
                    .map(|s: &Section| s.name.clone())
            } else {
                None
            };
            symbols.push(Symbol {
                name: name.trim_start_matches('_').to_string(),
                va: nlist.n_value,
                size: 0,
                kind: SymbolKind::Other,
                scope: if !ext {
                    SymbolScope::Local
                } else if weak {
                    SymbolScope::Weak
                } else {
                    SymbolScope::Global
                },
                section,
                defined: n_type_mask != 0x00,
                dynamic: false,
            });
        }
    }

    // Imports from the dyld bind info.
    let mut imports = Vec::new();
    if let Ok(imps) = macho.imports() {
        for imp in imps {
            imports.push(Import {
                name: imp.name.to_string(),
                dll: (!imp.dylib.is_empty()).then(|| imp.dylib.to_string()),
                ordinal: None,
            });
        }
    }
    // Exports from the LC_DYLD_INFO export trie.
    let mut exports = Vec::new();
    if let Ok(exps) = macho.exports() {
        for exp in exps {
            let va = macho
                .segments
                .iter()
                .find_map(|seg| {
                    let off = exp.offset;
                    (off >= seg.fileoff && off < seg.fileoff + seg.filesize)
                        .then(|| seg.vmaddr + (off - seg.fileoff))
                })
                .unwrap_or(exp.offset);
            exports.push(Export {
                name: exp.name.trim_start_matches('_').to_string(),
                va,
                size: exp.size as u64,
                ordinal: None,
            });
        }
    }

    // Entry: LC_MAIN gives a file offset → map into a virtual address.
    let mut entry_file_off = None;
    for lc in macho.load_commands.iter() {
        if let goblin::mach::load_command::CommandVariant::Main(main) = lc.command {
            entry_file_off = Some(main.entryoff as u64);
        }
    }
    let entry_va = entry_file_off.and_then(|off| macho_offset_to_va(&addr_map, off));
    let pie = matches!(kind, BinaryKind::Exe); // modern macOS executables are PIE

    // Required libraries: LC_LOAD_DYLIB-family commands, read from the raw
    // command bytes (goblin's DylibCommand only carries the name *offset*).
    let libs = macho_dylibs(data, macho);

    Ok(BinaryInfo {
        format: Format::MachO,
        arch,
        endian,
        bits,
        kind,
        pie,
        entry_va,
        image_base: addr_map
            .first()
            .map(|m| m.va_start.saturating_sub(m.off_start)),
        size: 0,
        sections,
        symbols,
        imports,
        exports,
        interpreter: None,
        libs,
        addr_map,
    })
}

fn macho_offset_to_va(addr_map: &[AddrMap], off: u64) -> Option<u64> {
    addr_map
        .iter()
        .find(|m| off >= m.off_start && off < m.off_start + m.len)
        .map(|m| m.va_start + (off - m.off_start))
}

/// Walk the raw load-command region for LC_LOAD_DYLIB / LC_LOAD_WEAK_DYLIB /
/// LC_REEXPORT_DYLIB and pull the library path strings (goblin's DylibCommand
/// only carries the name *offset*, not the resolved path).
fn macho_dylibs(data: &[u8], macho: &goblin::mach::MachO<'_>) -> Vec<String> {
    const LC_LOAD_DYLIB: u32 = 0xc;
    const LC_LOAD_WEAK_DYLIB: u32 = 0x18;
    const LC_REEXPORT_DYLIB: u32 = 0x8000_001f;

    let is_64 = macho.header.cputype & 0x0100_0000 != 0; // CPU_ARCH_ABI64
    let header_size: usize = if is_64 { 32 } else { 28 };
    let cmds_len = macho.header.sizeofcmds as usize;
    let Some(cmds) = data.get(header_size..(header_size + cmds_len).min(data.len())) else {
        return Vec::new();
    };

    let mut out: Vec<String> = Vec::new();
    let mut cursor = 0usize;
    while cursor + 8 <= cmds.len() {
        let cmd = u32::from_le_bytes([
            cmds[cursor],
            cmds[cursor + 1],
            cmds[cursor + 2],
            cmds[cursor + 3],
        ]);
        let cmdsize = u32::from_le_bytes([
            cmds[cursor + 4],
            cmds[cursor + 5],
            cmds[cursor + 6],
            cmds[cursor + 7],
        ]) as usize;
        if cmdsize < 8 || cursor + cmdsize > cmds.len() {
            break;
        }
        if matches!(cmd, LC_LOAD_DYLIB | LC_LOAD_WEAK_DYLIB | LC_REEXPORT_DYLIB)
            && cmdsize >= 24
        {
            // DylibCommand: name offset at byte 8 (relative to command start).
            let name_off = u32::from_le_bytes([
                cmds[cursor + 8],
                cmds[cursor + 9],
                cmds[cursor + 10],
                cmds[cursor + 11],
            ]) as usize;
            if name_off < cmdsize {
                if let Some(rest) = cmds.get(cursor + name_off..cursor + cmdsize) {
                    let end = rest.iter().position(|&b| b == 0).unwrap_or(rest.len());
                    let path = String::from_utf8_lossy(&rest[..end]).into_owned();
                    if !path.is_empty() && !out.iter().any(|l| *l == path) {
                        out.push(path);
                    }
                }
            }
        }
        cursor += cmdsize;
    }
    // Two-level namespaces: bind info dylibs not covered by load commands.
    for imp in macho.imports().unwrap_or_default() {
        if !imp.dylib.is_empty() && !out.iter().any(|l| *l == imp.dylib) {
            out.push(imp.dylib.to_string());
        }
    }
    out
}
