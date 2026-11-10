//! [`functions`] — the function list for the sidebar rail.
//!
//! Source of truth: symbol tables (ELF `STT_FUNC`, PE COFF records with the
//! function type, Mach-O nlist entries in executable sections), plus the
//! entry point when no symbol sits on it. Stripped binaries still get the
//! entry point; a full prologue scanner is a later R&D track.

use serde::{Deserialize, Serialize};

use crate::analyzer::{BinaryInfo, Section, SymbolKind};

/// One entry in the function list.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct Function {
    /// Virtual address.
    pub va: u64,
    /// Symbol name (mangled as-is) or a synthesized `<entry>`/`<sub_…>` label.
    pub name: String,
    /// Size in bytes when the symbol table says so (0 = unknown).
    pub size: u64,
    /// Where the address came from.
    pub source: FunctionSource,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum FunctionSource {
    Symbol,
    Export,
    Entry,
}

/// Build the function list: symbols of function kind first, exports whose
/// address isn't already covered, then the entry point. Sorted by `va`,
/// deduplicated by address.
pub fn functions(info: &BinaryInfo) -> Vec<Function> {
    let mut out: Vec<Function> = Vec::new();

    for sym in &info.symbols {
        if sym.kind != SymbolKind::Func || !sym.defined || sym.va == 0 {
            continue;
        }
        if out.iter().any(|f| f.va == sym.va) {
            continue;
        }
        out.push(Function {
            va: sym.va,
            name: sym.name.clone(),
            size: sym.size,
            source: FunctionSource::Symbol,
        });
    }

    for exp in &info.exports {
        if exp.va == 0 || out.iter().any(|f| f.va == exp.va) {
            continue;
        }
        out.push(Function {
            va: exp.va,
            name: exp.name.clone(),
            size: exp.size,
            source: FunctionSource::Export,
        });
    }

    if let Some(entry) = info.entry_va {
        if entry != 0 && !out.iter().any(|f| f.va == entry) {
            out.push(Function {
                va: entry,
                name: "<entry>".to_string(),
                size: 0,
                source: FunctionSource::Entry,
            });
        }
    }

    out.sort_by_key(|f| f.va);
    out
}

/// Function list restricted to executable sections (what the rail shows).
pub fn functions_in_exec(info: &BinaryInfo) -> Vec<Function> {
    let exec: Vec<(u64, u64)> = info
        .exec_sections()
        .iter()
        .filter_map(|s: &&Section| s.va.map(|base| (base, base + s.size)))
        .collect();
    functions(info)
        .into_iter()
        .filter(|f| exec.iter().any(|(lo, hi)| f.va >= *lo && f.va < *hi))
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::analyzer::{Export, Section, SectionFlags, Symbol, SymbolKind, SymbolScope};

    fn info_with(symbols: Vec<Symbol>, exports: Vec<Export>, entry: Option<u64>) -> BinaryInfo {
        let mut info = BinaryInfo::fixture();
        info.entry_va = entry;
        info.image_base = Some(0x400000);
        info.size = 100;
        info.sections = vec![Section {
            name: ".text".into(),
            va: Some(0x400000),
            size: 0x1000,
            file_off: 0x400,
            file_size: 0x1000,
            flags: SectionFlags { exec: true, read: true, ..Default::default() },
        }];
        info.symbols = symbols;
        info.exports = exports;
        info
    }

    #[test]
    fn symbols_exports_and_entry_merge_deduped() {
        let info = info_with(
            vec![Symbol {
                name: "main".into(),
                va: 0x400800,
                size: 42,
                kind: SymbolKind::Func,
                scope: SymbolScope::Global,
                section: Some(".text".into()),
                defined: true,
                dynamic: false,
            }],
            vec![
                Export { name: "main".into(), va: 0x400800, size: 42, ordinal: None },
                Export { name: "helper".into(), va: 0x400900, size: 0, ordinal: None },
            ],
            Some(0x400800), // same as main — must not duplicate
        );
        let fns = functions(&info);
        assert_eq!(fns.len(), 2, "entry on main must not duplicate");
        assert_eq!(fns[0].name, "main");
        assert_eq!(fns[0].source, FunctionSource::Symbol);
        assert_eq!(fns[1].name, "helper");
        assert_eq!(fns[1].source, FunctionSource::Export);
    }

    #[test]
    fn entry_lands_in_list_when_orphaned() {
        let info = info_with(vec![], vec![], Some(0x400123));
        let fns = functions(&info);
        assert_eq!(fns.len(), 1);
        assert_eq!(fns[0].name, "<entry>");
        assert_eq!(fns[0].source, FunctionSource::Entry);
    }

    #[test]
    fn undefined_and_nonfunction_symbols_skipped() {
        let info = info_with(
            vec![
                Symbol {
                    name: "puts".into(),
                    va: 0,
                    size: 0,
                    kind: SymbolKind::Func,
                    scope: SymbolScope::Global,
                    section: None,
                    defined: false,
                    dynamic: true,
                },
                Symbol {
                    name: "global_var".into(),
                    va: 0x400c00,
                    size: 8,
                    kind: SymbolKind::Object,
                    scope: SymbolScope::Global,
                    section: Some(".data".into()),
                    defined: true,
                    dynamic: false,
                },
            ],
            vec![],
            None,
        );
        assert!(functions(&info).is_empty());
    }

    #[test]
    fn exec_filter_keeps_text_only() {
        let mut info = info_with(
            vec![
                Symbol {
                    name: "in_text".into(),
                    va: 0x400500,
                    size: 0,
                    kind: SymbolKind::Func,
                    scope: SymbolScope::Local,
                    section: Some(".text".into()),
                    defined: true,
                    dynamic: false,
                },
                Symbol {
                    name: "in_data".into(),
                    va: 0x500000,
                    size: 0,
                    kind: SymbolKind::Func,
                    scope: SymbolScope::Global,
                    section: Some(".data".into()),
                    defined: true,
                    dynamic: false,
                },
            ],
            vec![],
            None,
        );
        // add a non-exec section
        info.sections.push(Section {
            name: ".data".into(),
            va: Some(0x500000),
            size: 0x100,
            file_off: 0x2000,
            file_size: 0x100,
            flags: SectionFlags { write: true, read: true, ..Default::default() },
        });
        let fns = functions_in_exec(&info);
        assert_eq!(fns.len(), 1);
        assert_eq!(fns[0].name, "in_text");
    }

    #[test]
    fn sorted_by_address() {
        let mk = |va: u64, name: &str| Symbol {
            name: name.into(),
            va,
            size: 0,
            kind: SymbolKind::Func,
            scope: SymbolScope::Local,
            section: Some(".text".into()),
            defined: true,
            dynamic: false,
        };
        let info = info_with(vec![mk(0x400900, "c"), mk(0x400100, "a"), mk(0x400500, "b")], vec![], None);
        let fns = functions(&info);
        assert_eq!(
            fns.iter().map(|f| f.name.as_str()).collect::<Vec<_>>(),
            ["a", "b", "c"]
        );
    }
}
