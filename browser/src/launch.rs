//! Chromium process launcher — spawns the browser headless with
//! `--remote-debugging-pipe` and hands back the two pipe halves.
//!
//! Wire format on fds 3 (browser reads) and 4 (browser writes):
//! NUL-delimited UTF-8 JSON, one message per frame — see [`crate::cdp`].
//!
//! The parent keeps:
//! - `to_browser`:  our write end  (browser's fd 3)
//! - `from_browser`: our read end (browser's fd 4)

use std::path::{Path, PathBuf};
use std::process::Stdio;

use tokio::net::UnixStream;

#[derive(Debug, thiserror::Error)]
pub enum LaunchError {
    #[error("browser binary not found: {0}")]
    NotFound(String),
    #[error("browser launch failed: {0}")]
    Spawn(String),
    #[error("debug pipes unavailable on this platform (unix required for pipe transport)")]
    UnsupportedPlatform,
}

pub type Result<T> = std::result::Result<T, LaunchError>;

#[derive(Debug)]
pub struct BrowserPipes {
    /// Parent → browser (browser's fd 3).
    pub to_browser: UnixStream,
    /// Browser → parent (browser's fd 4).
    pub from_browser: UnixStream,
    /// Kill handle for the browser process (drop = kill, kill_on_drop).
    pub child: tokio::process::Child,
    /// The user-data-dir the browser runs with.
    pub user_data_dir: PathBuf,
}

/// Extra launch parameters.
#[derive(Debug, Clone)]
pub struct LaunchSpec {
    pub browser_path: PathBuf,
    pub user_data_dir: PathBuf,
    pub window: (u32, u32),
    pub headless: bool,
    pub extra_args: Vec<String>,
}

impl LaunchSpec {
    /// The lean, fleet-friendly default flag set (no first-run beacons, no
    /// background networking, forced 1.0 device scale for stable
    /// coordinates, no shm failures inside containers).
    pub fn chrome_args(&self) -> Vec<String> {
        let mut args = vec![
            format!("--user-data-dir={}", self.user_data_dir.display()),
            format!("--window-size={},{}", self.window.0, self.window.1),
            "--force-device-scale-factor=1".to_string(),
            "--no-first-run".to_string(),
            "--no-default-browser-check".to_string(),
            "--disable-extensions".to_string(),
            "--disable-background-networking".to_string(),
            "--disable-sync".to_string(),
            "--disable-translate".to_string(),
            "--metrics-recording-only".to_string(),
            "--mute-audio".to_string(),
            "--disable-dev-shm-usage".to_string(),
            "--remote-debugging-pipe".to_string(),
        ];
        if self.headless {
            args.push("--headless".to_string());
            args.push("--disable-gpu".to_string());
            args.push("--hide-scrollbars".to_string());
        }
        args.extend(self.extra_args.iter().cloned());
        args
    }
}

/// Spawn the browser and wire up the debug pipes. Unix only for now —
/// `--remote-debugging-pipe` needs an fd pair; a WebSocket fallback for
/// Windows is a documented follow-up.
pub fn spawn_browser(spec: &LaunchSpec) -> Result<BrowserPipes> {
    #[cfg(unix)]
    {
        spawn_unix(spec)
    }
    #[cfg(not(unix))]
    {
        let _ = spec;
        Err(LaunchError::UnsupportedPlatform)
    }
}

#[cfg(unix)]
fn spawn_unix(spec: &LaunchSpec) -> Result<BrowserPipes> {
    use std::os::unix::io::AsRawFd;

    if !spec.browser_path.is_file() {
        return Err(LaunchError::NotFound(spec.browser_path.display().to_string()));
    }

    // socketpair(2) via std: full-duplex, but we use each pair half-duplex
    // so the fd semantics match what Chromium expects from the pipe.
    let (to_browser, browser_read) =
        std::os::unix::net::UnixStream::pair().map_err(|e| LaunchError::Spawn(e.to_string()))?;
    let (browser_write, from_browser) =
        std::os::unix::net::UnixStream::pair().map_err(|e| LaunchError::Spawn(e.to_string()))?;

    let to_browser_fd = to_browser.as_raw_fd();
    let from_browser_fd = from_browser.as_raw_fd();
    let browser_read_fd = browser_read.as_raw_fd();
    let browser_write_fd = browser_write.as_raw_fd();

    let mut cmd = tokio::process::Command::new(&spec.browser_path);
    cmd.args(spec.chrome_args())
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::piped())
        .kill_on_drop(true);

    // SAFETY: runs once in the forked child before exec. We dup the socket
    // ends onto fds 3/4 and close every other copy so the child sees exactly
    // the two pipe fds.
    unsafe {
        cmd.pre_exec(move || {
            if libc::dup2(browser_read_fd, 3) == -1 {
                return Err(std::io::Error::last_os_error());
            }
            if libc::dup2(browser_write_fd, 4) == -1 {
                return Err(std::io::Error::last_os_error());
            }
            for fd in [browser_read_fd, browser_write_fd, to_browser_fd, from_browser_fd] {
                if fd > 4 {
                    libc::close(fd);
                }
            }
            Ok(())
        });
    }

    let mut child = cmd
        .spawn()
        .map_err(|e| LaunchError::Spawn(format!("{}: {e}", spec.browser_path.display())))?;

    // Drain the browser's own stderr so a chatty Chrome can never wedge on
    // a full pipe. The task dies with the process.
    drain_stderr(&mut child);

    // Parent ends must be non-blocking for tokio.
    to_browser.set_nonblocking(true).map_err(|e| LaunchError::Spawn(e.to_string()))?;
    from_browser.set_nonblocking(true).map_err(|e| LaunchError::Spawn(e.to_string()))?;
    let to_browser = UnixStream::from_std(to_browser)
        .map_err(|e| LaunchError::Spawn(e.to_string()))?;
    let from_browser = UnixStream::from_std(from_browser)
        .map_err(|e| LaunchError::Spawn(e.to_string()))?;

    if let Ok(Some(exit)) = child.try_wait() {
        return Err(LaunchError::Spawn(format!(
            "browser exited immediately with {exit}; flags: {:?}",
            spec.chrome_args()
        )));
    }

    Ok(BrowserPipes {
        to_browser,
        from_browser,
        child,
        user_data_dir: spec.user_data_dir.clone(),
    })
}

/// Pump the browser's stderr into the void so a chatty Chrome can never
/// block on a full stderr buffer. The task dies with the process.
fn drain_stderr(child: &mut tokio::process::Child) {
    if let Some(stderr) = child.stderr.take() {
        tokio::spawn(async move {
            use tokio::io::AsyncReadExt;
            let mut stderr = stderr;
            let mut buf = [0u8; 8192];
            loop {
                match stderr.read(&mut buf).await {
                    Ok(0) | Err(_) => break,
                    Ok(_) => continue,
                }
            }
        });
    }
}

/// Best-effort removal of a clean-room user-data-dir on drop.
pub fn remove_dir_best_effort(dir: &Path) {
    let _ = std::fs::remove_dir_all(dir);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn flag_set_is_lean_and_fleet_ready() {
        let spec = LaunchSpec {
            browser_path: PathBuf::from("/usr/bin/chromium"),
            user_data_dir: PathBuf::from("/tmp/ud"),
            window: (1280, 800),
            headless: true,
            extra_args: vec!["--lang=en-US".into()],
        };
        let args = spec.chrome_args();
        for expected in [
            "--user-data-dir=/tmp/ud",
            "--window-size=1280,800",
            "--remote-debugging-pipe",
            "--headless",
            "--force-device-scale-factor=1",
            "--disable-dev-shm-usage",
            "--lang=en-US",
        ] {
            assert!(args.iter().any(|a| a == expected), "missing {expected} in {args:?}");
        }
    }

    #[test]
    fn missing_binary_is_not_found() {
        let spec = LaunchSpec {
            browser_path: PathBuf::from("/nonexistent/xencode-fake-chrome"),
            user_data_dir: PathBuf::from("/tmp/ud"),
            window: (1280, 800),
            headless: true,
            extra_args: vec![],
        };
        let err = spawn_browser(&spec).unwrap_err();
        assert!(matches!(err, LaunchError::NotFound(_)), "got {err:?}");
    }
}
