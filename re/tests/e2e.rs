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

// ------------------------------------------------------------ XEX (7.3) ----

/// Minimal unencrypted XEX2: one header page, one code page (`li r3,1; blr`),
/// one data page carrying a string — laid out like the xex unit fixture but
/// self-contained for the integration pipeline. `blocks`/`payload` override
/// the FILE_FORMAT_INFO table and the raw payload (for compression tests).
fn xex2_fixture_opts(
    encryption: u16,
    compression: u16,
    blocks: &[(u32, u32)],
    payload: Vec<u8>,
) -> Vec<u8> {
    let be = |v: u32| v.to_be_bytes();
    let so: usize = 0x200;
    let page: u32 = 0x10000; // 64 KiB pages (no page-size-4kb flag)

    // Security block.
    let mut sec = Vec::new();
    let put = |v: &mut Vec<u8>, x: u32| v.extend_from_slice(&x.to_be_bytes());
    put(&mut sec, 0x184 + 3 * 24); // header size (3 descriptors)
    let image_size: u32 = 3 * page;
    put(&mut sec, image_size);
    sec.extend_from_slice(&[0u8; 0x100]); // RSA sig
    sec.extend_from_slice(&[0u8; 0x4]); // unk_108
    put(&mut sec, 0x2000_0000); // image flags: region free, 64 KiB pages
    put(&mut sec, 0x8200_0000); // load address
    sec.extend_from_slice(&[0u8; 0x14]); // section digest
    put(&mut sec, 0); // import table count
    sec.extend_from_slice(&[0u8; 0x14]); // import digest
    sec.extend_from_slice(&[0u8; 0x10]); // xgd2 media id
    sec.extend_from_slice(&[0u8; 0x10]); // aes key slot (never read here)
    put(&mut sec, 0); // export table
    sec.extend_from_slice(&[0u8; 0x14]); // header digest
    put(&mut sec, 0xFFFF_FFFF); // region
    put(&mut sec, 0x7); // allowed media
    put(&mut sec, 3); // page descriptor count
    for (ty, pages) in [(0u32, 1u32), (1, 1), (2, 1)] {
        put(&mut sec, (pages << 4) | ty);
        sec.extend_from_slice(&[0u8; 0x14]); // page digest
    }

    // Optional header blocks.
    let ff_block = {
        let mut b = Vec::new();
        put(&mut b, (8 + blocks.len() * 8) as u32); // struct size
        b.extend_from_slice(&encryption.to_be_bytes());
        b.extend_from_slice(&compression.to_be_bytes());
        for (d, z) in blocks {
            put(&mut b, *d);
            put(&mut b, *z);
        }
        b
    };
    let exec_blob = {
        let mut b = Vec::new();
        put(&mut b, 0); // media id
        put(&mut b, 0x2000_0000); // version 2.0.0.0
        put(&mut b, 0x1000_0000); // base version 1.0.0.0
        put(&mut b, 0x5351_07DB); // title id ('SQ')
        b.extend_from_slice(&[9, 2, 0, 1]); // platform/exec/disc 0 of 1
        put(&mut b, 0x1234_5678); // savegame id
        b
    };

    let total_opt = 4; // entry + image base + file format + execution info
    let mut cur = 0x18 + total_opt * 8;
    let ff_off = cur;
    cur += ff_block.len();
    let exec_off = cur;
    cur += exec_blob.len();
    let so_final = cur.max(so);
    let pe_off = so_final + sec.len();

    let mut out = Vec::new();
    out.extend_from_slice(b"XEX2");
    put(&mut out, 0x81); // module flags: title + user-mode
    put(&mut out, pe_off as u32);
    put(&mut out, 0); // reserved
    put(&mut out, so_final as u32);
    put(&mut out, total_opt as u32);
    for (k, v) in [
        (0x0001_0100u32, 0x8200_1000u32), // ENTRY_POINT
        (0x0001_0201, 0x8200_0000),       // IMAGE_BASE
        (0x0000_03FF, ff_off as u32),     // FILE_FORMAT_INFO
        (0x0004_0006, exec_off as u32),   // EXECUTION_INFO (6 dwords raw)
    ] {
        put(&mut out, k);
        put(&mut out, v);
    }
    while out.len() < ff_off {
        out.push(0);
    }
    out.extend_from_slice(&ff_block);
    out.extend_from_slice(&exec_blob);
    while out.len() < so_final {
        out.push(0);
    }
    out.extend_from_slice(&sec);

    if payload.is_empty() {
        // Default payload = flattened image: header page, code page, data page.
        let mut img = vec![0u8; image_size as usize];
        // Code at image offset 0x10000 (page 1): li r3,1; blr.
        img[0x1_0000..0x1_0008]
            .copy_from_slice(&[0x38, 0x60, 0x00, 0x01, 0x4e, 0x80, 0x00, 0x20]);
        // Data at image offset 0x20000 (page 2): a string.
        img[0x2_0000..0x2_0008].copy_from_slice(b"XENCODE\x00");
        out.extend_from_slice(&img);
    } else {
        out.extend_from_slice(&payload);
    }
    out
}

fn xex2_fixture(encryption: u16, compression: u16) -> Vec<u8> {
    xex2_fixture_opts(encryption, compression, &[], vec![])
}

#[test]
fn xex_pipeline_end_to_end() {
    let data = xex2_fixture(0, 0);
    let info = analyze_bytes(&data).expect("xex fixture must parse");

    assert_eq!(info.format, Format::Xex);
    assert_eq!(info.arch.to_string(), "ppc64");
    assert_eq!(info.bits, 64);
    assert_eq!(info.endian.to_string(), "big");
    assert_eq!(info.kind, BinaryKind::Exe);
    assert!(!info.pie);
    assert_eq!(info.entry_va, Some(0x8200_1000));
    assert_eq!(info.image_base, Some(0x8200_0000));
    assert_eq!(info.size, data.len() as u64);

    // Sections: code + data (header pages never surface).
    assert_eq!(info.sections.len(), 2);
    let code = &info.sections[0];
    assert_eq!(code.name, ".xex-code");
    assert_eq!(code.va, Some(0x8201_0000));
    assert_eq!(code.file_off, 0x1_0000, "image offset");
    assert!(code.flags.exec && !code.flags.write);
    let data_sec = &info.sections[1];
    assert_eq!(data_sec.name, ".xex-data");
    assert!(data_sec.flags.write && !data_sec.flags.exec);

    // Flat image map round-trips.
    assert_eq!(info.va_to_offset(0x8201_0000), Some(0x1_0000));
    assert_eq!(info.offset_to_va(0x2_0000), Some(0x8202_0000));

    // Byte views run on the decompressed image (compression none → copy).
    let (img_info, image) = xex_payload_image(&data).expect("payload materializes");
    assert_eq!(image.len(), 3 * 0x1_0000);
    assert_eq!(img_info.entry_va, Some(0x8200_1000));

    // Disassembly at the entry: PPC64 BE `li r3, 1` + `blr`.
    let arch = arch_from_binary(&img_info).unwrap();
    assert_eq!(arch, DisArch::Ppc64);
    let insns = disasm_bytes(&image[0x1_0000..0x1_0008], 0x8200_1000, arch, 16).unwrap();
    assert_eq!(insns.len(), 2);
    assert_eq!(insns[0].mnemonic, "li");
    assert_eq!(insns[0].operands, "r3, 1");
    assert_eq!(insns[1].mnemonic, "blr");

    // Strings over the image find the data-page text.
    let hits = extract_strings(&image, 4);
    assert!(hits.iter().any(|s| s.text == "XENCODE"));

    // Functions: just the entry point (no symbol tables in XEX).
    let fns = functions(&img_info);
    assert_eq!(fns.len(), 1);
    assert_eq!(fns[0].name, "<entry>");
    assert_eq!(fns[0].va, 0x8200_1000);

    // Structured metadata renders, and no key material leaks into JSON.
    let meta = parse_xex(&data).expect("structure parses");
    assert_eq!(meta.magic, "XEX2");
    assert_eq!(meta.encryption, "none");
    assert_eq!(meta.compression, "none");
    assert_eq!(meta.title_id, Some(0x5351_07DB));
    assert_eq!(meta.title_tag.as_deref(), Some("SQ"));
    assert_eq!(meta.version.as_deref(), Some("2.0.0.0"));
    let json = serde_json::to_string(&meta).expect("serializes");
    assert!(!json.to_ascii_lowercase().contains("aes_key"));
    assert!(!json.to_ascii_lowercase().contains("\"key\""));
}

#[test]
fn xex_encrypted_refuses_byte_views_but_parses_structure() {
    let data = xex2_fixture(1, 0); // encryption = normal (AES)
    let info = analyze_bytes(&data).expect("structure still analyzes");
    assert_eq!(info.format, Format::Xex);
    assert_eq!(info.entry_va, Some(0x8200_1000));

    let err = xex_payload_image(&data).expect_err("byte views must refuse");
    let msg = err.to_string();
    assert!(msg.contains("AES-encrypted"), "{msg}");
    assert!(msg.contains("keys are never bundled"), "{msg}");

    // Structure metadata is unaffected (headers are plaintext).
    let meta = parse_xex(&data).expect("parses");
    assert_eq!(meta.encryption, "aes");
    assert_eq!(meta.load_address, 0x8200_0000);
}

#[test]
fn xex_basic_compression_end_to_end() {
    // Basic compression: pair (data 0x10008, zero 0x1FFF8) streams the
    // header page + code through and zero-fills the rest of the image.
    let mut payload = vec![0u8; 0x1_0000];
    payload.extend_from_slice(&[0x38, 0x60, 0x00, 0x01, 0x4e, 0x80, 0x00, 0x20]);
    let data = xex2_fixture_opts(0, 1, &[(0x1_0008, 0x1_FFF8)], payload);

    let (img_info, image) = xex_payload_image(&data).expect("reconstructs");
    assert_eq!(img_info.format, Format::Xex);
    assert_eq!(image.len(), 3 * 0x1_0000);
    assert_eq!(image[0x1_0000..0x1_0008], [0x38, 0x60, 0x00, 0x01, 0x4e, 0x80, 0x00, 0x20]);
    // Zero run left the tail zeroed (the stream ended at the code bytes).
    assert!(image[0x2_0000..0x2_0008].iter().all(|&b| b == 0));
    let meta = parse_xex(&data).expect("structure parses");
    assert_eq!(meta.compression, "basic");
    assert_eq!(meta.basic_blocks.len(), 1);
}
