//! TypeScript support — users write TS, we strip types in-process via oxc
//! (no external transpiler, microsecond-scale for typical scripts).
//!
//! Strategy: parse TS with oxc_parser → run the oxc TypeScript transform
//! (annotation/enums/namespaces erasure) → print JS with oxc_codegen.
//! `.d.ts` declarations parse but produce an empty module (types only).

use std::path::Path;

use oxc_allocator::Allocator;
use oxc_codegen::{Codegen, CodegenOptions};
use oxc_parser::Parser;
use oxc_semantic::SemanticBuilder;
use oxc_span::SourceType;
use oxc_transformer::{TransformOptions, Transformer};

/// Strip TypeScript from `source` down to plain ESM JavaScript.
pub fn strip(source: &str, file_name: &str) -> Result<String, String> {
    let source_type = source_type_for(file_name);
    if !source_type.is_typescript() {
        return Ok(source.to_string());
    }

    let allocator = Allocator::default();
    let parser_ret = Parser::new(&allocator, source, source_type).parse();
    if parser_ret.fatal_error {
        return Err("TypeScript parser failed with a fatal error".into());
    }
    let mut errors: Vec<String> = parser_ret
        .diagnostics
        .iter()
        .map(|e| e.to_string())
        .collect();
    let mut program = parser_ret.program;

    // semantic analysis feeds the TS transform; enum evaluation is required
    // by the transformer to inline enum members correctly
    let semantic = SemanticBuilder::new().with_enum_eval(true).build(&program).semantic;
    let scoping = semantic.into_scoping();

    let transformer = Transformer::new(&allocator, Path::new(file_name), &TransformOptions::default());
    let ret = transformer.build_with_scoping(scoping, &mut program);
    for d in ret.diagnostics.iter() {
        errors.push(d.to_string());
    }
    if !errors.is_empty() {
        return Err(format!(
            "TypeScript transform failed in {file_name}: {}",
            errors.join("; ")
        ));
    }

    Ok(Codegen::new()
        .with_options(CodegenOptions::default())
        .build(&program)
        .code)
}

/// Map a file name to an oxc source type (ts/mts/cts/tsx→jsx stripped too).
fn source_type_for(file_name: &str) -> SourceType {
    SourceType::from_path(file_name)
        .unwrap_or_else(|_| SourceType::default().with_typescript(true))
}

/// True for extensions we transpile in-process.
pub fn is_typescript_path(path: &str) -> bool {
    let lower = path.to_ascii_lowercase();
    lower.ends_with(".ts")
        || lower.ends_with(".mts")
        || lower.ends_with(".cts")
        || lower.ends_with(".tsx")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn strips_annotations_and_interfaces() {
        let src = r#"
interface Opts { name: string; count?: number }
type Alias = string | number;

export function greet(o: Opts, times: Alias): string {
  const out: string = `hi ${o.name}`;
  let n: number = o.count ?? 1;
  return out.repeat(n as number);
}
"#;
        let js = strip(src, "script.ts").unwrap();
        assert!(!js.contains("interface"), "interfaces erased");
        assert!(!js.contains("Opts"), "type refs erased");
        assert!(!js.contains(": string"), "annotations erased");
        assert!(js.contains("greet"), "functions kept");
        assert!(js.contains("export function greet(o, times)"), "{js}");
    }

    #[test]
    fn strips_enums_and_type_only_imports() {
        let src = r#"
import type { Config } from "./config";
export enum Mode { On, Off }
const m: Mode = Mode.On;
export const isOn = m === Mode.On;
"#;
        let js = strip(src, "m.ts").unwrap();
        assert!(!js.contains("import type"), "type-only imports erased");
        assert!(!js.contains("enum Mode"), "enums erased");
        assert!(js.contains("0"), "enum inlined as number");
    }

    #[test]
    fn keeps_plain_js_untouched() {
        let src = "export const x = 1 + 2;";
        let js = strip(src, "plain.js").unwrap();
        assert!(js.contains("export const x = 1 + 2;"), "{js}");
    }

    #[test]
    fn detection_by_extension() {
        assert!(is_typescript_path("a/b.ts"));
        assert!(is_typescript_path("a/b.MTS"));
        assert!(!is_typescript_path("a/b.js"));
        assert!(!is_typescript_path("a/b.json"));
    }
}
