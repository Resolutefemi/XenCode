//! Browser-backed `BrowserDriver` (Phase 6.3) — the host side of the
//! `xen.browser` QuickJS surface.
//!
//! The script crate stays browser-free: it defines the command/reply
//! vocabulary and the [`BrowserDriver`] trait; this module implements the
//! trait over a real XenBrowser [`Space`]. Every command runs on the
//! captured main-runtime `Handle` (the script itself executes on a
//! dedicated thread whose current-thread runtime must never touch the
//! browser's pipe IO — tokio IO resources are runtime-bound), replies come
//! back as plain data.
//!
//! The Space launches lazily on the first command and is kept alive for the
//! script's lifetime (reuse across steps is the point of a recording);
//! `Close` drops it early.

use std::future::Future;
use std::path::PathBuf;
use std::pin::Pin;
use std::sync::{Arc, Mutex};

use anyhow::Context as _;
use xencode_browser::{Space, SpaceKind, SpaceOptions};
use xencode_script::host::browser::{BrowserCommand, BrowserDriver, BrowserReply, png_to_base64};

/// Space configuration for a driver-launched browser.
#[derive(Clone)]
pub struct SpaceLaunchConfig {
    pub kind: SpaceKind,
    pub headless: bool,
    pub browser_path: Option<PathBuf>,
    /// Managed-download consent (`--download`).
    pub allow_download: bool,
}

impl Default for SpaceLaunchConfig {
    fn default() -> Self {
        SpaceLaunchConfig {
            kind: SpaceKind::CleanRoom,
            headless: true,
            browser_path: None,
            allow_download: false,
        }
    }
}

struct DriverInner {
    space: Option<Arc<Space>>,
}

/// Clonable handle implementing [`BrowserDriver`]. Clones share the Space.
pub struct SpaceDriver {
    handle: tokio::runtime::Handle,
    cfg: SpaceLaunchConfig,
    inner: Arc<Mutex<DriverInner>>,
}

impl SpaceDriver {
    pub fn new(handle: tokio::runtime::Handle, cfg: SpaceLaunchConfig) -> Self {
        SpaceDriver {
            handle,
            cfg,
            inner: Arc::new(Mutex::new(DriverInner { space: None })),
        }
    }

    /// Launch (once) and return the shared Space. Runs on the main runtime.
    async fn space(&self) -> Result<Arc<Space>, String> {
        if let Some(s) = self.inner.lock().unwrap().space.clone() {
            return Ok(s);
        }
        let space = Space::launch(SpaceOptions {
            kind: self.cfg.kind.clone(),
            headless: self.cfg.headless,
            browser_path: self.cfg.browser_path.clone(),
            allow_download: self.cfg.allow_download,
            ..Default::default()
        })
        .await
        .map_err(|e| format!("browser launch failed: {e:#}"))?;
        let space = Arc::new(space);
        *self.inner.lock().unwrap() = DriverInner { space: Some(space.clone()) };
        Ok(space)
    }

    /// Execute one command on the main runtime (see module docs).
    async fn run(self, cmd: BrowserCommand) -> Result<BrowserReply, String> {
        let err = |e: anyhow::Error| format!("{e:#}");
        let space = self.space().await?;
        match cmd {
            BrowserCommand::Navigate { url } => {
                let timeout = std::time::Duration::from_secs(30);
                let nav = tokio::time::timeout(timeout, space.navigate(&url)).await;
                match nav {
                    Err(_) => Err(format!("navigate timed out after 30s: {url}")),
                    Ok(Err(e)) => Err(err(e)),
                    Ok(Ok(())) => Ok(BrowserReply::Unit),
                }
            }
            BrowserCommand::Click { selector } => {
                space
                    .click(&xencode_browser::Selector::parse(&selector))
                    .await
                    .map_err(err)?;
                Ok(BrowserReply::Unit)
            }
            BrowserCommand::Fill { selector, text } => {
                space
                    .fill(&xencode_browser::Selector::parse(&selector), &text)
                    .await
                    .map_err(err)?;
                Ok(BrowserReply::Unit)
            }
            BrowserCommand::Select { selector, value } => {
                space
                    .select(&xencode_browser::Selector::parse(&selector), &value)
                    .await
                    .map_err(err)?;
                Ok(BrowserReply::Unit)
            }
            BrowserCommand::Press { key } => {
                space.press(&key).await.map_err(err)?;
                Ok(BrowserReply::Unit)
            }
            BrowserCommand::Scroll { dx, dy } => {
                space.scroll(dx, dy).await.map_err(err)?;
                Ok(BrowserReply::Unit)
            }
            BrowserCommand::ScrollTo { x, y } => {
                space.scroll_to(x, y).await.map_err(err)?;
                Ok(BrowserReply::Unit)
            }
            BrowserCommand::Wait { ms } => {
                tokio::time::sleep(std::time::Duration::from_millis(ms)).await;
                Ok(BrowserReply::Unit)
            }
            BrowserCommand::Snapshot => {
                let (outline, _truncated) = space.outline().await.map_err(err)?;
                Ok(BrowserReply::Text(outline))
            }
            BrowserCommand::Screenshot { path } => {
                let frame = space.screenshot().await.map_err(err)?;
                let mut reply = serde_json::json!({
                    "width": frame.width,
                    "height": frame.height,
                });
                match path {
                    Some(p) => {
                        if let Some(parent) = std::path::Path::new(&p).parent() {
                            let _ = std::fs::create_dir_all(parent);
                        }
                        write_png(&frame, &p).map_err(err)?;
                        reply["path"] = serde_json::Value::String(p);
                    }
                    None => {
                        let bytes = encode_png(&frame).map_err(err)?;
                        reply["png"] = serde_json::Value::String(png_to_base64(&bytes));
                    }
                }
                Ok(BrowserReply::Json(reply))
            }
            BrowserCommand::Url => {
                let u = space.url().await.map_err(err)?;
                Ok(BrowserReply::Text(u))
            }
            BrowserCommand::Title => {
                let t = space.title().await.map_err(err)?;
                Ok(BrowserReply::Text(t))
            }
            BrowserCommand::Eval { js } => {
                let v = space.evaluate(&js).await.map_err(err)?;
                Ok(BrowserReply::Json(v))
            }
            BrowserCommand::Close => {
                if let Some(space) = self.inner.lock().unwrap().space.take() {
                    // Drop impl kills the child (kill_on_drop) and wipes a
                    // clean-room profile.
                    drop(space);
                }
                Ok(BrowserReply::Unit)
            }
        }
    }
}

impl BrowserDriver for SpaceDriver {
    fn execute(
        &self,
        cmd: BrowserCommand,
    ) -> Pin<Box<dyn Future<Output = Result<BrowserReply, String>> + Send>> {
        let this = self.clone();
        Box::pin(async move {
            // Space ops must run where their IO was registered — the main
            // runtime, not the script thread's current-thread runtime.
            let handle = this.handle.clone();
            let jh = handle.spawn(async move { this.run(cmd).await });
            jh.await
                .map_err(|e| format!("browser task failed: {e}"))?
        })
    }
}

impl Clone for SpaceDriver {
    fn clone(&self) -> Self {
        SpaceDriver {
            handle: self.handle.clone(),
            cfg: self.cfg.clone(),
            inner: self.inner.clone(),
        }
    }
}

/// Encode a frame as PNG bytes (in-memory).
fn encode_png(frame: &xencode_browser::Frame) -> anyhow::Result<Vec<u8>> {
    let mut buf = std::io::Cursor::new(Vec::new());
    let mut enc = png::Encoder::new(&mut buf, frame.width, frame.height);
    enc.set_color(png::ColorType::Rgba);
    enc.set_depth(png::BitDepth::Eight);
    let mut writer = enc.write_header().context("png header")?;
    writer.write_image_data(&frame.rgba).context("png data")?;
    writer.finish()?;
    Ok(buf.into_inner())
}

/// Write a frame to a PNG file.
fn write_png(frame: &xencode_browser::Frame, path: &str) -> anyhow::Result<()> {
    let bytes = encode_png(frame)?;
    std::fs::write(path, bytes).with_context(|| format!("write {}", path))
}
