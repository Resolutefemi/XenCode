//! PTY session — spawns the real shell (PowerShell on Windows / login shell on
//! Unix via ConPTY / openpty) and pumps its output through the ANSI grid.
//! This replaces the static mock terminal from milestone 1.

use std::io::Write;
use std::sync::{Arc, Mutex};

use portable_pty::{native_pty_system, CommandBuilder, PtySize};

use crate::term::TermCore;

pub struct TermSession {
    pub core: Arc<Mutex<TermCore>>,
    pub label: String,
    writer: Box<dyn Write + Send>,
    master: Box<dyn portable_pty::MasterPty + Send>,
    pub cols: usize,
    pub rows: usize,
}

impl TermSession {
    /// Spawn the shell attached to a PTY of the given size and start the
    /// reader thread (which repaints the UI context on every output chunk).
    pub fn spawn(cols: usize, rows: usize, cwd: &str, ctx: egui::Context) -> anyhow::Result<Self> {
        let pty_system = native_pty_system();
        let pair = pty_system.openpty(PtySize {
            rows: (rows.max(2)) as u16,
            cols: (cols.max(8)) as u16,
            pixel_width: 0,
            pixel_height: 0,
        })?;

        let (cmd, label) = shell_command(cwd);
        let _child = pair.slave.spawn_command(cmd)?;

        let writer = pair.master.take_writer()?;
        let mut reader = pair
            .master
            .try_clone_reader()
            .map_err(|e| anyhow::anyhow!("pty reader: {e}"))?;

        let core = Arc::new(Mutex::new(TermCore::new(cols.max(8), rows.max(2))));
        let core_reader = Arc::clone(&core);
        let ctx_reader = ctx.clone();

        std::thread::Builder::new()
            .name("xencode-pty-read".into())
            .spawn(move || {
                let mut buf = [0u8; 8192];
                loop {
                    match reader.read(&mut buf) {
                        Ok(0) => {
                            if let Ok(mut c) = core_reader.lock() {
                                c.grid.closed = true;
                            }
                            ctx_reader.request_repaint();
                            break;
                        }
                        Ok(n) => {
                            {
                                let mut c = core_reader.lock().expect("term core poisoned");
                                c.feed(&buf[..n]);
                            }
                            ctx_reader.request_repaint();
                        }
                        Err(_) => break,
                    }
                }
            })
            .map_err(|e| anyhow::anyhow!("spawn reader: {e}"))?;

        Ok(Self {
            core,
            label,
            writer,
            master: pair.master,
            cols: cols.max(8),
            rows: rows.max(2),
        })
    }

    pub fn write(&mut self, bytes: &[u8]) {
        let _ = self.writer.write_all(bytes);
        let _ = self.writer.flush();
    }

    pub fn resize(&mut self, cols: usize, rows: usize) {
        let cols = cols.max(8);
        let rows = rows.max(2);
        if cols == self.cols && rows == self.rows {
            return;
        }
        self.cols = cols;
        self.rows = rows;
        let _ = self.master.resize(PtySize {
            rows: rows as u16,
            cols: cols as u16,
            pixel_width: 0,
            pixel_height: 0,
        });
        if let Ok(mut c) = self.core.lock() {
            c.grid.resize(cols, rows);
        }
    }

    pub fn clear(&mut self) {
        if let Ok(mut c) = self.core.lock() {
            let cols = c.grid.cols;
            let rows = c.grid.rows;
            c.grid = crate::term::Grid::new(cols, rows);
        }
    }
}

fn shell_command(cwd: &str) -> (CommandBuilder, String) {
    #[cfg(windows)]
    {
        let mut cmd = CommandBuilder::new("powershell.exe");
        if !cwd.is_empty() && std::path::Path::new(cwd).exists() {
            cmd.cwd(cwd);
        }
        (cmd, "POWERSHELL".to_string())
    }
    #[cfg(not(windows))]
    {
        let shell = std::env::var("SHELL").unwrap_or_else(|_| "/bin/bash".to_string());
        let mut cmd = CommandBuilder::new(&shell);
        cmd.arg("-l");
        if !cwd.is_empty() && std::path::Path::new(cwd).exists() {
            cmd.cwd(cwd);
        }
        let label = shell.rsplit('/').next().unwrap_or("shell").to_uppercase();
        (cmd, label)
    }
}
