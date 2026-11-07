//! Terminal side pane: command runner with persistent cwd, scrollback,
//! history recall and output coloring. Each command runs `bash -c` in the
//! tracked working directory; `cd` is handled locally.

use crate::theme::palette;
use egui::{Color32, FontId, Ui};
use std::process::{Command, Stdio};
use std::sync::mpsc::{channel, Receiver, Sender};
use std::thread;

/// One scrollback line with a color.
pub struct TermLine {
    pub text: String,
    pub color: Color32,
}

enum TermMsg {
    Output(String),
    Done { code: i32 },
    Failed(String),
}

pub struct TerminalPane {
    workspace: String,
    cwd: String,
    lines: Vec<TermLine>,
    input: String,
    history: Vec<String>,
    history_idx: Option<usize>,
    busy: bool,
    tx: Sender<TermMsg>,
    rx: Receiver<TermMsg>,
}

impl TerminalPane {
    pub fn new(workspace: &str) -> Self {
        let (tx, rx) = channel();
        TerminalPane {
            workspace: workspace.to_string(),
            cwd: workspace.to_string(),
            lines: vec![TermLine {
                text: format!("XenCode terminal · {} · type a command", workspace),
                color: palette::ON_SURFACE_VARIANT,
            }],
            input: String::new(),
            history: Vec::new(),
            history_idx: None,
            busy: false,
            tx,
            rx,
        }
    }

    /// Demo seeding: run a couple of representative commands so the pane is
    /// not empty in screenshots (`--demo`).
    pub fn seed_demo(&mut self) {
        self.run("git log --oneline -n 3");
        self.run("ls rust/crates");
    }

    fn prompt(&self) -> String {
        let short = self
            .cwd
            .strip_prefix(&format!("{}/", self.workspace))
            .unwrap_or(&self.cwd)
            .to_string();
        format!("{} $", if short.is_empty() { "." } else { &short })
    }

    fn run(&mut self, raw: &str) {
        let cmdline = raw.trim();
        if cmdline.is_empty() {
            return;
        }
        self.history.push(cmdline.to_string());
        self.history_idx = None;
        self.lines.push(TermLine {
            text: format!("{} {}", self.prompt(), cmdline),
            color: palette::PRIMARY,
        });

        // Builtins.
        if cmdline == "clear" || cmdline == "cls" {
            self.lines.clear();
            return;
        }
        if let Some(target) = cmdline.strip_prefix("cd ") {
            self.change_dir(target.trim());
            return;
        }
        if cmdline == "cd" {
            self.cwd = self.workspace.clone();
            return;
        }

        self.busy = true;
        let cwd = self.cwd.clone();
        let tx = self.tx.clone();
        let cmd = cmdline.to_string();
        thread::spawn(move || {
            let child = Command::new("bash")
                .arg("-c")
                .arg(&cmd)
                .current_dir(&cwd)
                .stdout(Stdio::piped())
                .stderr(Stdio::piped())
                .output();
            match child {
                Ok(out) => {
                    let mut text = String::from_utf8_lossy(&out.stdout).into_owned();
                    let stderr = String::from_utf8_lossy(&out.stderr).into_owned();
                    if !stderr.is_empty() {
                        if !text.is_empty() && !text.ends_with('\n') {
                            text.push('\n');
                        }
                        text.push_str(&stderr);
                    }
                    let _ = tx.send(TermMsg::Output(text));
                    let _ = tx.send(TermMsg::Done {
                        code: out.status.code().unwrap_or(-1),
                    });
                }
                Err(e) => {
                    let _ = tx.send(TermMsg::Failed(e.to_string()));
                }
            }
        });
    }

    fn change_dir(&mut self, target: &str) {
        let base = std::path::PathBuf::from(&self.cwd);
        let next = if target == ".." {
            base.parent().map(|p| p.to_path_buf()).unwrap_or(base)
        } else {
            base.join(target)
        };
        match std::fs::canonicalize(&next) {
            Ok(path) => self.cwd = path.to_string_lossy().into_owned(),
            Err(_) => self.lines.push(TermLine {
                text: format!("cd: no such directory: {target}"),
                color: palette::ERROR,
            }),
        }
    }

    fn drain(&mut self) {
        while let Ok(msg) = self.rx.try_recv() {
            match msg {
                TermMsg::Output(text) => {
                    for line in text.lines() {
                        self.lines.push(TermLine { text: line.to_string(), color: palette::ON_BACKGROUND });
                    }
                }
                TermMsg::Done { code } => {
                    if code != 0 {
                        self.lines.push(TermLine {
                            text: format!("exit code {code}"),
                            color: palette::ERROR,
                        });
                    }
                    self.busy = false;
                }
                TermMsg::Failed(err) => {
                    self.lines.push(TermLine { text: format!("error: {err}"), color: palette::ERROR });
                    self.busy = false;
                }
            }
        }
    }

    pub fn ui(&mut self, ui: &mut Ui) {
        self.drain();

        // Scrollback.
        egui::ScrollArea::vertical()
            .stick_to_bottom(true)
            .id_salt("terminal_scroll")
            .show(ui, |ui| {
                egui::Frame::default()
                    .fill(palette::CODE_BG)
                    .rounding(egui::Rounding::same(6.0))
                    .inner_margin(egui::Margin::same(8.0))
                    .show(ui, |ui| {
                        ui.set_width(ui.available_width());
                        ui.style_mut().override_font_id = Some(FontId::monospace(11.5));
                        for line in &self.lines {
                            ui.add(egui::Label::new(egui::RichText::new(line.text.as_str()).color(line.color)).truncate().selectable(true));
                        }
                        if self.busy {
                            ui.colored_label(palette::WARNING, "...running");
                        }
                    });
            });

        ui.add_space(4.0);

        // Input.
        ui.horizontal(|ui| {
            ui.style_mut().override_font_id = Some(FontId::monospace(11.5));
            ui.colored_label(palette::PRIMARY, self.prompt());
            let resp = ui.add(
                egui::TextEdit::singleline(&mut self.input)
                    .hint_text(if self.busy { "running..." } else { "command" })
                    .desired_width(ui.available_width() - 60.0)
                    .interactive(!self.busy),
            );
            if resp.lost_focus() && ui.input(|i| i.key_pressed(egui::Key::Enter)) && !self.busy {
                let cmd = std::mem::take(&mut self.input);
                self.run(&cmd);
                resp.request_focus();
            }
            // History recall.
            if resp.has_focus() {
                if ui.input(|i| i.key_pressed(egui::Key::ArrowUp)) {
                    if !self.history.is_empty() {
                        let idx = self.history_idx.map(|i| i.saturating_sub(1)).unwrap_or(self.history.len() - 1);
                        self.history_idx = Some(idx);
                        self.input = self.history[idx].clone();
                    }
                }
                if ui.input(|i| i.key_pressed(egui::Key::ArrowDown)) {
                    if let Some(idx) = self.history_idx {
                        if idx + 1 < self.history.len() {
                            self.history_idx = Some(idx + 1);
                            self.input = self.history[idx + 1].clone();
                        } else {
                            self.history_idx = None;
                            self.input.clear();
                        }
                    }
                }
            }
            if ui.small_button("Clear").clicked() {
                self.lines.clear();
            }
        });
    }
}
