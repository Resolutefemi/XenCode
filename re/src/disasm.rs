//! [`disasm`] — capstone-backed disassembly with arch auto-detection.
//!
//! The native layer of the code view: feed a byte slice + base virtual
//! address, get back one [`Insn`] per decoded instruction (addr, raw bytes,
//! mnemonic, operand string). Architectures: x86 (16/32/64), ARM, ARM64 and
//! PPC/PPC64 (the Xenon in the Xbox 360 is PPC64 with Altivec — covered by
//! capstone's PPC core; no Xenon-specific encodings are special-cased yet).
//!
//! x86-64 is the default flavor; real RE workflows disassemble from a known
//! entry point or function symbol (see [`functions`]), so callers pass the
//! VA and this module only decodes what it is given.

use serde::{Deserialize, Serialize};

use crate::analyzer::{Arch, BinaryInfo};
use anyhow::{bail, Result};

/// Architectures the disassembler can decode.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum DisArch {
    X86,
    X86_64,
    Arm,
    Thumb,
    Arm64,
    Ppc,
    Ppc64,
}

impl std::fmt::Display for DisArch {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            DisArch::X86 => "x86",
            DisArch::X86_64 => "x86_64",
            DisArch::Arm => "arm",
            DisArch::Thumb => "thumb",
            DisArch::Arm64 => "arm64",
            DisArch::Ppc => "ppc",
            DisArch::Ppc64 => "ppc64",
        })
    }
}

/// One decoded instruction.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Insn {
    pub addr: u64,
    pub size: u16,
    pub bytes: Vec<u8>,
    pub mnemonic: String,
    pub operands: String,
}

/// Pick a disassembly architecture from the analyzer's arch + bitness.
pub fn arch_from_binary(info: &BinaryInfo) -> Result<DisArch> {
    match (&info.arch, info.bits) {
        (Arch::X86_64, _) | (Arch::X86, 64) => Ok(DisArch::X86_64),
        (Arch::X86, 32) => Ok(DisArch::X86),
        (Arch::Arm64, _) => Ok(DisArch::Arm64),
        (Arch::Arm, _) => Ok(DisArch::Arm),
        (Arch::Ppc, 32) => Ok(DisArch::Ppc),
        (Arch::Ppc64, _) => Ok(DisArch::Ppc64),
        (arch, bits) => bail!("no disassembler for {arch} ({bits}-bit) yet"),
    }
}

/// Parse an `--arch` CLI value.
pub fn parse_arch(s: &str) -> Result<DisArch> {
    match s.to_ascii_lowercase().as_str() {
        "x86" | "i386" => Ok(DisArch::X86),
        "x86_64" | "x64" | "amd64" => Ok(DisArch::X86_64),
        "arm" => Ok(DisArch::Arm),
        "thumb" => Ok(DisArch::Thumb),
        "arm64" | "aarch64" => Ok(DisArch::Arm64),
        "ppc" | "powerpc" | "xenon" => Ok(DisArch::Ppc),
        "ppc64" | "powerpc64" => Ok(DisArch::Ppc64),
        other => bail!(
            "unknown arch `{other}` — expected x86, x86_64, arm, thumb, arm64, ppc or ppc64"
        ),
    }
}

fn capstone_arch(arch: DisArch) -> Result<(capstone::Arch, capstone::Mode, Option<capstone::Endian>)> {
    use capstone::{Arch as CA, Endian, Mode as CM};
    // PPC images are big-endian (Xbox 360 Xenon included); x86/ARM64 LE.
    Ok(match arch {
        DisArch::X86 => (CA::X86, CM::Mode32, None),
        DisArch::X86_64 => (CA::X86, CM::Mode64, None),
        DisArch::Arm => (CA::ARM, CM::Arm, None),
        DisArch::Thumb => (CA::ARM, CM::Thumb, None),
        DisArch::Arm64 => (CA::ARM64, CM::Arm, None),
        DisArch::Ppc => (CA::PPC, CM::Mode32, Some(Endian::Big)),
        DisArch::Ppc64 => (CA::PPC, CM::Mode64, Some(Endian::Big)),
    })
}

/// Disassemble `code` starting at virtual address `base_va`.
///
/// Decoding stops at the first invalid byte (a data blob embedded in the
/// range ends the run — the function view re-slices around it in a later
/// wave). `max_insns` bounds runaway ranges.
pub fn disasm_bytes(
    code: &[u8],
    base_va: u64,
    arch: DisArch,
    max_insns: usize,
) -> Result<Vec<Insn>> {
    let (carch, cmode, cendian) = capstone_arch(arch)?;
    let cs = capstone::Capstone::new_raw(carch, cmode, capstone::NO_EXTRA_MODE, cendian)
        .map_err(|e| anyhow::anyhow!("capstone init ({arch}): {e}"))?;
    let insns = cs
        .disasm_all(code, base_va)
        .map_err(|e| anyhow::anyhow!("disassembly failed: {e}"))?;
    let mut out = Vec::new();
    for insn in insns.iter().take(max_insns.max(1)) {
        out.push(Insn {
            addr: insn.address(),
            size: insn.len() as u16,
            bytes: insn.bytes().to_vec(),
            mnemonic: insn.mnemonic().unwrap_or("").to_string(),
            operands: insn.op_str().unwrap_or("").to_string(),
        });
    }
    Ok(out)
}

/// Render one instruction like an objdump line:
/// `00001000  48 8b 05 c8  mov rax, [rip+0xc8]`.
pub fn render_insn(insn: &Insn) -> String {
    let hex: String = insn
        .bytes
        .iter()
        .map(|b| format!("{b:02x} "))
        .collect::<String>();
    format!(
        "{:08x}  {:<24} {} {}",
        insn.addr,
        hex.trim_end(),
        insn.mnemonic,
        insn.operands
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn x86_64_prologue_decodes() {
        // push rbp; mov rbp, rsp; sub rsp, 0x20; ret
        let code = [0x55, 0x48, 0x89, 0xe5, 0x48, 0x83, 0xec, 0x20, 0xc3];
        let insns = disasm_bytes(&code, 0x1000, DisArch::X86_64, 64).unwrap();
        assert_eq!(insns.len(), 4);
        assert_eq!(insns[0].mnemonic, "push");
        assert_eq!(insns[0].operands, "rbp");
        assert_eq!(insns[0].addr, 0x1000);
        assert_eq!(insns[1].mnemonic, "mov");
        assert_eq!(insns[3].mnemonic, "ret");
        assert_eq!(insns[3].size, 1);
    }

    #[test]
    fn arm64_decodes() {
        // ret  (0xd65f03c0)
        let code = [0xc0, 0x03, 0x5f, 0xd6];
        let insns = disasm_bytes(&code, 0x100, DisArch::Arm64, 16).unwrap();
        assert_eq!(insns.len(), 1);
        assert_eq!(insns[0].mnemonic, "ret");
        assert_eq!(insns[0].size, 4);
    }

    #[test]
    fn arm_thumb_decodes() {
        // Thumb `bx lr` = 0x4770 (little-endian bytes 70 47)
        let code = [0x70, 0x47];
        let insns = disasm_bytes(&code, 0x100, DisArch::Thumb, 16).unwrap();
        assert_eq!(insns.len(), 1);
        assert_eq!(insns[0].mnemonic, "bx");
    }

    #[test]
    fn ppc64_decodes() {
        // blr = branch to link register (0x4e800020)
        let code = [0x4e, 0x80, 0x00, 0x20];
        let insns = disasm_bytes(&code, 0x1000, DisArch::Ppc64, 16).unwrap();
        assert_eq!(insns.len(), 1);
        assert_eq!(insns[0].mnemonic, "blr");
    }

    #[test]
    fn invalid_bytes_stop_the_run() {
        let code = [0x55, 0xff, 0xff, 0xff, 0xff, 0xc3];
        let insns = disasm_bytes(&code, 0x10, DisArch::X86_64, 64).unwrap();
        assert_eq!(insns.len(), 1, "0xff.. is invalid — decode stops");
        assert_eq!(insns[0].mnemonic, "push");
    }

    #[test]
    fn arch_mapping_covers_the_toolkit() {
        let info = |arch: Arch, bits: u8| {
            let mut i = crate::analyzer::BinaryInfo::fixture();
            i.arch = arch;
            i.bits = bits;
            i
        };
        assert_eq!(arch_from_binary(&info(Arch::X86_64, 64)).unwrap(), DisArch::X86_64);
        assert_eq!(arch_from_binary(&info(Arch::Arm64, 64)).unwrap(), DisArch::Arm64);
        assert_eq!(arch_from_binary(&info(Arch::Ppc, 32)).unwrap(), DisArch::Ppc);
        assert!(arch_from_binary(&info(Arch::Riscv, 64)).is_err());
    }

    #[test]
    fn parse_arch_aliases() {
        assert_eq!(parse_arch("x64").unwrap(), DisArch::X86_64);
        assert_eq!(parse_arch("aarch64").unwrap(), DisArch::Arm64);
        assert_eq!(parse_arch("Xenon").unwrap(), DisArch::Ppc);
        assert!(parse_arch("mips").is_err());
    }

    #[test]
    fn render_matches_objdump_shape() {
        let code = [0x55u8];
        let insn = &disasm_bytes(&code, 0x1000, DisArch::X86_64, 1).unwrap()[0];
        let line = render_insn(insn);
        assert!(line.starts_with("00001000"), "got: {line}");
        assert!(line.contains("push rbp"));
    }
}
