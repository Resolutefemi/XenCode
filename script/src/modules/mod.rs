//! Built-in Rust modules — the top ~20 npm use-cases with zero install,
//! zero network, native speed (plan.md "no npm" Tier 1).
//!
//! Scripts import them as ESM, either through the `xen/` prefix
//! (`import { join } from "xen/path"`) or through the npm-style bare name
//! (`import { parse } from "yaml"`) — the runtime resolver maps the bare
//! names to the native modules automatically.

pub mod ansi;
pub mod csv;
pub mod dotenv;
pub mod env;
pub mod glob;
pub mod markdown;
pub mod path;
pub mod semver;
pub mod sqlite;
pub mod toml;
pub mod yaml;
pub mod zip;

/// Bare npm-style specifier → native module name.
pub const NATIVE_BARE: &[(&str, &str)] = &[
    ("path", "xen/path"),
    ("glob", "xen/glob"),
    ("yaml", "xen/yaml"),
    ("toml", "xen/toml"),
    ("csv", "xen/csv"),
    ("zip", "xen/zip"),
    ("sqlite", "xen/sqlite"),
    ("sqlite3", "xen/sqlite"),
    ("dotenv", "xen/dotenv"),
    ("env", "xen/env"),
    ("semver", "xen/semver"),
    ("ansi", "xen/ansi"),
    ("markdown", "xen/markdown"),
    // node: builtins we cover
    ("node:path", "xen/path"),
    ("node:glob", "xen/glob"),
    ("node:yaml", "xen/yaml"),
    ("node:semver", "xen/semver"),
    ("node:sqlite", "xen/sqlite"),
    ("node:process", "xen/env"),
];
