//! XenBrowser — the embedded agent browser (Phase 6) + network capture and
//! API discovery (Phase 8).
//!
//! A pure-Rust CDP client that drives a real Chromium: no Node, no Python,
//! no Playwright — one static binary talking the DevTools protocol over the
//! `--remote-debugging-pipe` fd pair (3 = Chromium reads, 4 = it writes),
//! framed as NUL-delimited JSON.
//!
//! Layering:
//! - [`discover`] finds a usable Chrome/Edge/Chromium binary (env override,
//!   well-known install paths on Linux/macOS/Windows, Playwright caches).
//! - [`launch`] spawns it headless with an isolated user-data-dir and hands
//!   back the pipe ends.
//! - [`cdp`] is the connection: id-matched calls, session routing, events.
//! - [`space`] is the agent-facing surface: a Space is one isolated browser
//!   context (clean-room profile, or an inheriting profile) exposing
//!   navigate / click / fill / press / scroll / snapshot / screenshot /
//!   screencast — with auto-wait, like ego(lite) but cross-platform.
//! - [`snapshot`] turns `DOMSnapshot.captureSnapshot` into a node tree and
//!   renders an ego-style outline; [`diff`] matches two snapshots by
//!   backendNodeId so only deltas go to the model.
//! - [`frame`] decodes screencast/screenshot frames (PNG + JPEG) into RGBA
//!   buffers for the native renderer.
//! - [`netcapture`] records every XHR/fetch a page makes (CDP Network
//!   domain) as secret-redacted [`netcapture::NetEntry`] exchanges.
//! - [`apidisco`] turns captures into an endpoint catalog (URL templating,
//!   auth detection, JSON-schema inference) with OpenAPI export, copy-as
//!   replay snippets and the endpoint monitor diff.

pub mod apidisco;
pub mod cdp;
pub mod diff;
pub mod discover;
pub mod frame;
pub mod launch;
pub mod netcapture;
pub mod snapshot;
pub mod space;

pub use apidisco::{
    build_catalog, copy_as, diff_catalogs, infer_schema, render_catalog_text, schema_brief,
    to_openapi, ApiDiff, CopyAs, Endpoint, EndpointCatalog,
};
pub use cdp::{CdpError, Connection, Event};
pub use diff::{diff_snapshots, SnapshotDiff};
pub use discover::{find_browser, BrowserCandidate};
pub use frame::Frame;
pub use netcapture::{
    redact_entry, CaptureFile, CaptureOptions, CaptureState, NetCapture, NetEntry,
};
pub use snapshot::{snapshot_outline, SnapNode, Snapshot, OUTLINE_BUDGET};
pub use space::{ScreenCast, Selector, Space, SpaceKind, SpaceOptions};

/// Default viewport for new Spaces (logical px, device scale forced to 1).
pub const DEFAULT_VIEWPORT: (u32, u32) = (1280, 800);
