//! XenRE e2e — full-pipeline checks against handcrafted real images.
//!
//! Each fixture is a byte-exact minimal ELF64 / PE64+ / Mach-O arm64 image
//! built here (deterministic, offline), then pushed through the whole
//! analyze → hex → strings → disasm → functions pipeline. A real system ELF
//! is analyzed too when present (portability guard: skipped elsewhere).


use xencode_re::analyzer::{BinaryKind, Format, SymbolKind};
use xencode_re::*;

// ------------------------------------------------------------- fixtures ----

/// Minimal static ELF64 x86_64 executable: one PT_LOAD, `.text` with a
/// real prologue, `.symtab` carrying `main`, plus string tables.
fn elf64_fixture() -> Vec<u8> {
    let mut f = vec![0u8; 0x228];
    // ELF header
    f[0..4].copy_from_slice(b"\x7fELF");
    f[4] = 2; // EI_CLASS: 64-bit
    f[5] = 1; // EI_DATA: little endian
    f[6] = 1; // EI_VERSION
    // e_type = ET_EXEC(2), e_machine = EM_X86_64(0x3e), e_version = 1
    f[16..18].copy_from_slice(&2u16.to_le_bytes());
    f[18..20].copy_from_slice(&0x3eu16.to_le_bytes());
    f[20..24].copy_from_slice(&1u32.to_le_bytes());
    f[24..32].copy_from_slice(&0x400078u64.to_le_bytes()); // e_entry
    f[32..40].copy_from_slice(&0x40u64.to_le_bytes()); // e_phoff
    f[40..48].copy_from_slice(&0xe8u64.to_le_bytes()); // e_shoff
    f[48..52].copy_from_slice(&0u32.to_le_bytes()); // e_flags
    f[52..54].copy_from_slice(&64u16.to_le_bytes()); // e_ehsize
    f[54..56].copy_from_slice(&56u16.to_le_bytes()); // e_phentsize
    f[56..58].copy_from_slice(&1u16.to_le_bytes()); // e_phnum
    f[58..60].copy_from_slice(&64u16.to_le_bytes()); // e_shentsize
    f[60..62].copy_from_slice(&5u16.to_le_bytes()); // e_shnum
    f[62..64].copy_from_slice(&4u16.to_le_bytes()); // e_shstrndx

    // Program header: PT_LOAD R+X covering the whole file.
    let ph = 0x40;
    f[ph..ph + 4].copy_from_slice(&1u32.to_le_bytes()); // PT_LOAD
    f[ph + 4..ph + 8].copy_from_slice(&5u32.to_le_bytes()); // R+X
    f[ph + 8..ph + 16].copy_from_slice(&0u64.to_le_bytes()); // p_offset
    f[ph + 16..ph + 24].copy_from_slice(&0x400000u64.to_le_bytes()); // p_vaddr
    f[ph + 24..ph + 32].copy_from_slice(&0x400000u64.to_le_bytes()); // p_paddr
    f[ph + 32..ph + 40].copy_from_slice(&0x228u64.to_le_bytes()); // p_filesz
    f[ph + 40..ph + 48].copy_from_slice(&0x228u64.to_le_bytes()); // p_memsz
    f[ph + 48..ph + 56].copy_from_slice(&0x1000u64.to_le_bytes()); // p_align

    // .text at 0x78: push rbp; mov rbp,rsp; sub rsp,0x20; ret (9 bytes)
    let text_off = 0x78;
    let prologue: [u8; 9] = [0x55, 0x48, 0x89, 0xe5, 0x48, 0x83, 0xec, 0x20, 0xc3];
    f[text_off..text_off + 9].copy_from_slice(&prologue);

    // .symtab at 0x88: null + main(STT_FUNC, .text, 0x400078, size 9)
    let symtab_off = 0x88;
    f[symtab_off + 24..symtab_off + 28].copy_from_slice(&1u32.to_le_bytes()); // st_name
    f[symtab_off + 28] = 0x12; // GLOBAL|FUNC
    f[symtab_off + 30..symtab_off + 32].copy_from_slice(&1u16.to_le_bytes()); // .text
    f[symtab_off + 32..symtab_off + 40].copy_from_slice(&0x400078u64.to_le_bytes());
    f[symtab_off + 40..symtab_off + 48].copy_from_slice(&9u64.to_le_bytes());

    // .strtab at 0xb8: "\0main\0" (6 bytes)
    f[0xb8] = 0;
    f[0xb9..0xbe].copy_from_slice(b"main\0");

    // .shstrtab at 0xc0: "\0.text\0.symtab\0.strtab\0.shstrtab\0" (33 bytes)
    f[0xc0..0xc0 + 33].copy_from_slice(b"\0.text\0.symtab\0.strtab\0.shstrtab\0");

    // Section headers at 0xe8 (NULL, .text, .symtab, .strtab, .shstrtab)
    let sh = |f: &mut Vec<u8>, i: usize, name: u32, ty: u32, flags: u64, addr: u64, off: u64, size: u64, link: u32, entsize: u64| {
        let base = 0xe8 + i * 64;
        f[base..base + 4].copy_from_slice(&name.to_le_bytes());
        f[base + 4..base + 8].copy_from_slice(&ty.to_le_bytes());
        f[base + 8..base + 16].copy_from_slice(&flags.to_le_bytes());
        f[base + 16..base + 24].copy_from_slice(&addr.to_le_bytes()); // sh_addr
        f[base + 24..base + 32].copy_from_slice(&off.to_le_bytes());
        f[base + 32..base + 40].copy_from_slice(&size.to_le_bytes());
        f[base + 40..base + 44].copy_from_slice(&link.to_le_bytes());
        f[base + 44..base + 48].copy_from_slice(&0u32.to_le_bytes()); // sh_info
        f[base + 48..base + 56].copy_from_slice(&0u64.to_le_bytes()); // sh_addralign
        f[base + 56..base + 64].copy_from_slice(&entsize.to_le_bytes());
    };
    sh(&mut f, 0, 0, 0, 0, 0, 0, 0, 0, 0);
    sh(&mut f, 1, 1, 1, 0x6, 0x400078, text_off as u64, 9, 0, 0); // .text PROGBITS AX
    sh(&mut f, 2, 7, 2, 0, 0, symtab_off as u64, 48, 3, 24); // .symtab → .strtab
    sh(&mut f, 3, 15, 3, 0, 0, 0xb8, 6, 0, 0); // .strtab
    sh(&mut f, 4, 23, 3, 0, 0, 0xc0, 33, 0, 0); // .shstrtab
    f
}

/// Minimal PE64+ console image: one `.text` section, entry RVA 0x1000,
/// DYNAMIC_BASE set, real x86-64 code in the raw section body.
fn pe64_fixture() -> Vec<u8> {
    let mut f = vec![0u8; 0x400];
    f[0..2].copy_from_slice(b"MZ");
    f[0x3c..0x40].copy_from_slice(&0x80u32.to_le_bytes()); // e_lfanew
    f[0x80..0x84].copy_from_slice(b"PE\0\0");
    // COFF header
    f[0x84..0x86].copy_from_slice(&0x8664u16.to_le_bytes()); // x64
    f[0x86..0x88].copy_from_slice(&1u16.to_le_bytes()); // 1 section
    f[0x8c..0x90].copy_from_slice(&0u32.to_le_bytes()); // no COFF syms
    f[0x90..0x94].copy_from_slice(&0u32.to_le_bytes()); // number_of_symbol_table
    f[0x94..0x96].copy_from_slice(&240u16.to_le_bytes()); // optional header size
    f[0x96..0x98].copy_from_slice(&0x0022u16.to_le_bytes()); // EXEC_IMAGE|LARGE_ADDR

    // Optional header PE32+ at 0x98
    let o = 0x98;
    f[o..o + 2].copy_from_slice(&0x20bu16.to_le_bytes()); // PE32+
    f[o + 12..o + 16].copy_from_slice(&0x1000u32.to_le_bytes()); // entry RVA
    f[o + 16..o + 20].copy_from_slice(&0x1000u32.to_le_bytes()); // base_of_code
    let w = o + 24; // Windows fields
    f[w..w + 8].copy_from_slice(&0x140000000u64.to_le_bytes()); // image base
    f[w + 8..w + 12].copy_from_slice(&0x1000u32.to_le_bytes()); // section align
    f[w + 12..w + 16].copy_from_slice(&0x200u32.to_le_bytes()); // file align
    f[w + 32..w + 36].copy_from_slice(&0x2000u32.to_le_bytes()); // size_of_image
    f[w + 36..w + 40].copy_from_slice(&0x200u32.to_le_bytes()); // size_of_headers
    f[w + 44..w + 46].copy_from_slice(&3u16.to_le_bytes()); // subsystem: console
    f[w + 46..w + 48].copy_from_slice(&0x40u16.to_le_bytes()); // DYNAMIC_BASE
    f[w + 84..w + 88].copy_from_slice(&16u32.to_le_bytes()); // rva count

    // Section header at 0x188: .text, VA 0x1000, raw 0x200@0x200, CRWX
    let s = 0x188;
    f[s..s + 8].copy_from_slice(b".text\0\0\0");
    f[s + 8..s + 12].copy_from_slice(&0x100u32.to_le_bytes()); // virtual size
    f[s + 12..s + 16].copy_from_slice(&0x1000u32.to_le_bytes()); // VA
    f[s + 16..s + 20].copy_from_slice(&0x200u32.to_le_bytes()); // raw size
    f[s + 20..s + 24].copy_from_slice(&0x200u32.to_le_bytes()); // raw ptr
    f[s + 36..s + 40].copy_from_slice(&0x6000_0020u32.to_le_bytes()); // CXR code

    // Real code in the raw section body.
    let prologue: [u8; 9] = [0x55, 0x48, 0x89, 0xe5, 0x48, 0x83, 0xec, 0x20, 0xc3];
    f[0x200..0x209].copy_from_slice(&prologue);
    // Strings in the body too.
    f[0x210..0x21c].copy_from_slice(b"hello\x00world\x00");
    f
}

/// Minimal Mach-O arm64 executable: `__TEXT` segment with `__text`,
/// LC_MAIN at entryoff 0x200, real `ret` in the body.
fn macho64_fixture() -> Vec<u8> {
    let mut f = vec![0u8; 0x220];
    // mach_header_64 — magic 0xfeedfacf, little-endian bytes
    f[0..4].copy_from_slice(&[0xcf, 0xfa, 0xed, 0xfe]);
    f[4..8].copy_from_slice(&0x0100_000cu32.to_le_bytes()); // CPU_TYPE_ARM64
    f[8..12].copy_from_slice(&0u32.to_le_bytes()); // subtype
    f[12..16].copy_from_slice(&2u32.to_le_bytes()); // MH_EXECUTE
    f[16..20].copy_from_slice(&2u32.to_le_bytes()); // ncmds
    f[20..24].copy_from_slice(&176u32.to_le_bytes()); // sizeofcmds
    f[24..28].copy_from_slice(&0u32.to_le_bytes()); // flags

    // LC_SEGMENT_64 __TEXT at 0x20 (cmdsize 152 = 72 + one 80-byte section)
    let c = 0x20;
    f[c..c + 4].copy_from_slice(&0x19u32.to_le_bytes()); // LC_SEGMENT_64
    f[c + 4..c + 8].copy_from_slice(&152u32.to_le_bytes());
    f[c + 8..c + 14].copy_from_slice(b"__TEXT");
    f[c + 24..c + 32].copy_from_slice(&0x1_0000_0000u64.to_le_bytes()); // vmaddr
    f[c + 32..c + 40].copy_from_slice(&0x4000u64.to_le_bytes()); // vmsize
    f[c + 40..c + 48].copy_from_slice(&0u64.to_le_bytes()); // fileoff
    f[c + 48..c + 56].copy_from_slice(&0x220u64.to_le_bytes()); // filesize
    f[c + 56..c + 60].copy_from_slice(&7u32.to_le_bytes()); // maxprot RWX
    f[c + 60..c + 64].copy_from_slice(&5u32.to_le_bytes()); // initprot R+X
    f[c + 64..c + 68].copy_from_slice(&1u32.to_le_bytes()); // nsects

    // section_64 __text at c+72
    let sc = c + 72;
    f[sc..sc + 6].copy_from_slice(b"__text");
    f[sc + 16..sc + 22].copy_from_slice(b"__TEXT");
    f[sc + 32..sc + 40].copy_from_slice(&0x1_0000_0000u64.to_le_bytes()); // addr
    f[sc + 40..sc + 48].copy_from_slice(&8u64.to_le_bytes()); // size
    f[sc + 48..sc + 52].copy_from_slice(&0x200u32.to_le_bytes()); // offset
    f[sc + 64..sc + 68].copy_from_slice(&0x8000_0400u32.to_le_bytes()); // PURE|SOME instr

    // LC_MAIN at 0xb8 (cmd 0x28|LC_REQ_DYLD, size 24, entryoff 0x200)
    let m = 0xb8;
    f[m..m + 4].copy_from_slice(&0x8000_0028u32.to_le_bytes());
    f[m + 4..m + 8].copy_from_slice(&24u32.to_le_bytes());
    f[m + 8..m + 16].copy_from_slice(&0x200u64.to_le_bytes()); // entryoff

    // ret at file offset 0x200 (va 0x100000200)
    f[0x200..0x204].copy_from_slice(&[0xc0, 0x03, 0x5f, 0xd6]);
    f
}

// ----------------------------------------------------------------- e2e ----

#[test]
fn elf64_pipeline_end_to_end() {
    let data = elf64_fixture();
    let info = analyze_bytes(&data).expect("elf64 fixture must parse");

    assert_eq!(info.format, Format::Elf);
    assert_eq!(info.arch.to_string(), "x86_64");
    assert_eq!(info.bits, 64);
    assert_eq!(info.endian.to_string(), "little");
    assert_eq!(info.kind, BinaryKind::Exe);
    assert!(!info.pie);
    assert_eq!(info.entry_va, Some(0x400078));
    assert_eq!(info.size, 0x228);

    // Sections: NULL, .text, .symtab, .strtab, .shstrtab
    let names: Vec<&str> = info.sections.iter().map(|s| s.name.as_str()).collect();
    assert_eq!(names, ["", ".text", ".symtab", ".strtab", ".shstrtab"]);
    let text = &info.sections[1];
    assert_eq!(text.va, Some(0x400078));
    assert!(text.flags.exec);
    assert_eq!(text.file_off, 0x78);
    assert_eq!(text.file_size, 9);

    // Address map: entry → .text body, round-trips.
    assert_eq!(info.va_to_offset(0x400078), Some(0x78));
    assert_eq!(info.offset_to_va(0x78), Some(0x400078));
    assert_eq!(info.section_at_va(0x400078).map(|s| s.name.as_str()), Some(".text"));

    // Symbols: `main` STT_FUNC defined at the entry point.
    let main = info
        .symbols
        .iter()
        .find(|s| s.name == "main")
        .expect("main must be in the symtab");
    assert_eq!(main.kind, SymbolKind::Func);
    assert_eq!(main.va, 0x400078);
    assert_eq!(main.size, 9);
    assert!(main.defined);

    // Functions: main + <entry> collapsed (same address).
    let fns = functions(&info);
    assert_eq!(fns.len(), 1, "entry and main share an address");
    assert_eq!(fns[0].name, "main");

    // Disassembly at the entry point decodes the known prologue.
    let arch = arch_from_binary(&info).unwrap();
    let off = info.va_to_offset(0x400078).unwrap() as usize;
    let insns = disasm_bytes(&data[off..off + 9], 0x400078, arch, 16).unwrap();
    assert_eq!(insns.len(), 4);
    assert_eq!(insns[0].mnemonic, "push");
    assert_eq!(insns.last().unwrap().mnemonic, "ret");

    // Strings inside the file (shstrtab yields a few).
    let hits = extract_strings(&data, 4);
    assert!(hits.iter().any(|s| s.text == ".shstrtab"));
    assert!(hits.iter().all(|s| s.encoding == StrEnc::Ascii));

    // Hex view rows over the header, magic readable in the gutter.
    let rows = hex_rows(&data, 0, 16);
    assert_eq!(rows.len(), (0x228 + 15) / 16);
    assert_eq!(rows[0].bytes[0..4], [0x7f, b'E', b'L', b'F']);
}

#[test]
fn pe64_pipeline_end_to_end() {
    let data = pe64_fixture();
    let info = analyze_bytes(&data).expect("pe64 fixture must parse");

    assert_eq!(info.format, Format::Pe);
    assert_eq!(info.arch.to_string(), "x86_64");
    assert_eq!(info.bits, 64);
    assert_eq!(info.kind, BinaryKind::Exe);
    assert!(info.pie, "DYNAMIC_BASE is set → PIE");
    assert_eq!(info.image_base, Some(0x140000000));
    assert_eq!(info.entry_va, Some(0x140001000));
    assert_eq!(info.size, 0x400);

    let text = info
        .sections
        .iter()
        .find(|s| s.name == ".text")
        .expect(".text must exist");
    assert_eq!(text.va, Some(0x140001000));
    assert!(text.flags.exec && !text.flags.write);

    assert_eq!(info.va_to_offset(0x140001000), Some(0x200));
    assert_eq!(info.offset_to_va(0x200), Some(0x140001000));

    // Disassemble the section body at its VA.
    let insns = disasm_bytes(&data[0x200..0x209], 0x140001000, DisArch::X86_64, 16).unwrap();
    assert_eq!(insns[0].mnemonic, "push");

    // Strings embedded after the code.
    let hits = extract_strings(&data, 4);
    assert!(hits.iter().any(|s| s.text == "hello"));
    assert!(hits.iter().any(|s| s.text == "world"));

    // No import table / no export directory in this fixture.
    assert!(info.imports.is_empty());
    assert!(info.exports.is_empty());

    // Function list still carries the entry point.
    let fns = functions(&info);
    assert_eq!(fns.len(), 1);
    assert_eq!(fns[0].name, "<entry>");
    assert_eq!(fns[0].va, 0x140001000);
}

#[test]
fn macho64_pipeline_end_to_end() {
    let data = macho64_fixture();
    let info = analyze_bytes(&data).expect("mach-o fixture must parse");

    assert_eq!(info.format, Format::MachO);
    assert_eq!(info.arch.to_string(), "arm64");
    assert_eq!(info.bits, 64);
    assert_eq!(info.kind, BinaryKind::Exe);
    assert_eq!(info.entry_va, Some(0x1_0000_0200));
    assert_eq!(info.image_base, Some(0x1_0000_0000));

    let text = info
        .sections
        .iter()
        .find(|s| s.name == "__TEXT,__text")
        .expect("__text must exist");
    assert!(text.flags.exec);
    assert_eq!(text.file_off, 0x200);

    assert_eq!(info.va_to_offset(0x1_0000_0200), Some(0x200));

    // The one instruction is `ret`.
    let insns = disasm_bytes(&data[0x200..0x204], 0x1_0000_0200, DisArch::Arm64, 8).unwrap();
    assert_eq!(insns.len(), 1);
    assert_eq!(insns[0].mnemonic, "ret");
}

#[test]
fn rejects_garbage_and_directories() {
    // Random bytes → unknown magic.
    let garbage = [0xdeu8, 0xad, 0xbe, 0xef, 0x00, 0x01, 0x02, 0x03, 0x55, 0xaa];
    assert!(analyze_bytes(&garbage).is_err());

    // Truncated "ELF-ish" prefix still fails cleanly.
    assert!(analyze_bytes(b"\x7fELF-broken").is_err());

    let dir = tempfile::tempdir().unwrap();
    assert!(analyze_file(dir.path()).is_err());

    // A real file that is not an image.
    let p = dir.path().join("text.txt");
    std::fs::write(&p, "plain text, not a binary").unwrap();
    assert!(analyze_file(&p).is_err());
}

#[test]
fn real_system_elf_round_trips() {
    // Portable: analyze a stock system ELF when present (CI sandboxes have it).
    let candidates = ["/usr/bin/env", "/bin/true", "/bin/ls", "/bin/cat"];
    let Some(path) = candidates.iter().find(|p| std::path::Path::new(p).exists()) else {
        eprintln!("no stock system ELF found — skipping");
        return;
    };
    let info = analyze_file(path).expect("stock ELF must parse");
    assert_eq!(info.format, Format::Elf);
    assert!(!info.sections.is_empty());
    assert!(info.entry_va.is_some());

    // Every section offset must map back to its VA and vice versa.
    for s in info.sections.iter() {
        if s.file_size > 0 && s.flags.exec {
            let va = s.va.unwrap();
            let off = info.va_to_offset(va).expect("exec section VA must map");
            assert!(off >= s.file_off && off < s.file_off + s.file_size);
        }
    }
}
