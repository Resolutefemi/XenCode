//! Git side pane: branch info, working-tree changes, staging, commit,
//! per-file diff and the recent log. All git calls run on a worker thread.

use crate::theme::{self, palette};
use eframe::egui::{FontId, Ui};
use std::process::{Command, Stdio};
use std::sync::mpsc::{channel, Receiver, Sender};
use std::thread;

/// A file entry from `git status --porcelain=v1 -b`.
#[derive(Clone, Debug)]
pub struct GitFile {
    pub path: String,
    pub x: char,
    pub y: char,
    pub staged: bool,
}

impl GitFile {
    pub fn status_label(&self) -> String {
        let s = format!("{}{}", self.x, self.y);
        s.trim().to_string()
    }

    pub fn is_untracked(&self) -> bool {
        self.x == '?' && self.y == '?'
    }
}

enum GitMsg {
    Branch(String, usize, usize),
    Status(Vec<GitFile>),
    Diff(String),
    Log(Vec<String>),
    Done(GitAction),
    Error(GitAction, String),
}

#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum GitAction {
    Refresh,
    Stage,
    Unstage,
    Commit,
}

impl GitAction {
    fn label(self) -> &'static str {
        match self {
            GitAction::Refresh => "refresh",
            GitAction::Stage => "stage",
            GitAction::Unstage => "unstage",
            GitAction::Commit => "commit",
        }
    }
}

pub struct GitPane {
    workspace: String,
    branch: String,
    ahead: usize,
    behind: usize,
    unstaged: Vec<GitFile>,
    staged: Vec<GitFile>,
    log: Vec<String>,
    diff: String,
    diff_title: String,
    selected: Option<String>,
    commit_msg: String,
    busy: bool,
    last_error: Option<String>,
    tx: Sender<GitMsg>,
    rx: Receiver<GitMsg>,
    refresh_queued: bool,
}

impl GitPane {
    pub fn new(workspace: &str) -> Self {
        let (tx, rx) = channel();
        GitPane {
            workspace: workspace.to_string(),
            branch: String::new(),
            ahead: 0,
            behind: 0,
            unstaged: Vec::new(),
            staged: Vec::new(),
            log: Vec::new(),
            diff: String::new(),
            diff_title: String::new(),
            selected: None,
            commit_msg: String::new(),
            busy: false,
            last_error: None,
            tx,
            rx,
            refresh_queued: true,
        }
    }

    pub fn request_refresh(&mut self) {
        self.refresh_queued = true;
    }

    fn spawn(
        &self,
        args: Vec<String>,
        action: GitAction,
        reply: Sender<GitMsg>,
        then: Option<GitAction>,
    ) {
        let ws = self.workspace.clone();
        thread::spawn(move || {
            let out = Command::new("git")
                .args(&args)
                .current_dir(&ws)
                .stderr(Stdio::piped())
                .stdout(Stdio::piped())
                .output();
            match out {
                Ok(o) if o.status.success() => {
                    let _ = reply.send(GitMsg::Done(action));
                    if then == Some(GitAction::Refresh) {
                        // Ask the pane to refresh on the UI side after completion.
                        let _ = reply.send(GitMsg::Done(GitAction::Refresh));
                    }
                }
                Ok(o) => {
                    let _ = reply.send(GitMsg::Error(
                        action,
                        String::from_utf8_lossy(&o.stderr).trim().to_string(),
                    ));
                }
                Err(e) => {
                    let _ = reply.send(GitMsg::Error(action, e.to_string()));
                }
            }
        });
    }

    fn refresh_async(&self) {
        // Branch + status + log in one worker thread.
        let ws = self.workspace.clone();
        let tx = self.tx.clone();
        thread::spawn(move || {
            let run = |args: &[&str]| -> Result<String, String> {
                let out = Command::new("git")
                    .args(args)
                    .current_dir(&ws)
                    .stderr(Stdio::piped())
                    .output()
                    .map_err(|e| e.to_string())?;
                if out.status.success() {
                    Ok(String::from_utf8_lossy(&out.stdout).into_owned())
                } else {
                    Err(String::from_utf8_lossy(&out.stderr).trim().to_string())
                }
            };
            match run(&["status", "--porcelain=v1", "-b"]) {
                Ok(out) => {
                    let mut branch = String::new();
                    let mut ahead = 0;
                    let mut behind = 0;
                    let mut files = Vec::new();
                    for line in out.lines() {
                        if let Some(rest) = line.strip_prefix("## ") {
                            let head = rest.split("...").next().unwrap_or(rest).trim();
                            branch = head.to_string();
                            if let Some(ab) = rest.split_once("[") {
                                for part in ab.1.trim_end_matches(']').split(',') {
                                    let part = part.trim();
                                    if let Some(n) = part.strip_prefix("ahead ") {
                                        ahead = n.trim().parse().unwrap_or(0);
                                    } else if let Some(n) = part.strip_prefix("behind ") {
                                        behind = n.trim().parse().unwrap_or(0);
                                    }
                                }
                            }
                        } else if line.len() >= 4 {
                            let x = line.as_bytes()[0] as char;
                            let y = line.as_bytes()[1] as char;
                            let path = line[3..].trim().to_string();
                            let staged = x != ' ' && x != '?';
                            files.push(GitFile { path, x, y, staged });
                        }
                    }
                    let _ = tx.send(GitMsg::Branch(branch, ahead, behind));
                    let _ = tx.send(GitMsg::Status(files));
                }
                Err(e) => {
                    let _ = tx.send(GitMsg::Error(GitAction::Refresh, e));
                }
            }
            if let Ok(log) = run(&["log", "--oneline", "-n", "12"]) {
                let lines: Vec<String> = log.lines().map(String::from).collect();
                let _ = tx.send(GitMsg::Log(lines));
            }
        });
    }

    fn request_diff(&mut self, path: &str, staged: bool) {
        self.diff_title = format!("{} {}", if staged { "staged" } else { "diff" }, path);
        let ws = self.workspace.clone();
        let path = path.to_string();
        let tx = self.tx.clone();
        thread::spawn(move || {
            let out = if staged {
                Command::new("git")
                    .args(["diff", "--cached", "--", &path])
                    .current_dir(&ws)
                    .output()
            } else {
                Command::new("git")
                    .args(["diff", "--", &path])
                    .current_dir(&ws)
                    .output()
            };
            let diff = out
                .map(|o| String::from_utf8_lossy(&o.stdout).into_owned())
                .unwrap_or_default();
            let _ = tx.send(GitMsg::Diff(diff));
        });
    }

    pub fn ui(&mut self, ui: &mut Ui) {
        self.drain();

        if self.refresh_queued && !self.busy {
            self.refresh_queued = false;
            self.busy = true;
            self.staged.clear();
            self.unstaged.clear();
            self.refresh_async();
        }

        // Header: branch + ahead/behind + refresh.
        ui.horizontal(|ui| {
            ui.colored_label(palette::primary(), crate::icons::glyph::GIT_BRANCH);
            ui.style_mut().override_font_id =
                Some(FontId::new(13.0, crate::theme::families::bold()));
            ui.colored_label(palette::on_background(), self.branch.clone());
            if self.ahead > 0 {
                theme::chip(
                    ui,
                    &format!("↑{}", self.ahead),
                    palette::on_primary_container(),
                    palette::primary_container(),
                );
            }
            if self.behind > 0 {
                theme::chip(
                    ui,
                    &format!("↓{}", self.behind),
                    palette::on_surface_variant(),
                    palette::surface_variant(),
                );
            }
            ui.with_layout(eframe::egui::Layout::right_to_left(eframe::egui::Align::Center), |ui| {
                if ui.small_button(crate::icons::glyph::SYNC).clicked() {
                    self.request_refresh();
                }
            });
        });
        if let Some(err) = &self.last_error {
            ui.style_mut().override_font_id = Some(FontId::proportional(11.0));
            ui.colored_label(palette::error(), format!("git: {err}"));
        }
        ui.add_space(4.0);

        // Commit box.
        ui.horizontal(|ui| {
            let enabled =
                !self.commit_msg.trim().is_empty() && !self.staged.is_empty() && !self.busy;
            if ui
                .add_enabled(
                    enabled,
                    eframe::egui::Button::new(
                        eframe::egui::RichText::new("Commit")
                            .color(palette::on_primary())
                            .strong(),
                    )
                    .fill(palette::primary()),
                )
                .clicked()
            {
                let msg = self.commit_msg.trim().to_string();
                self.busy = true;
                self.spawn(
                    vec!["commit".into(), "-m".into(), msg],
                    GitAction::Commit,
                    self.tx.clone(),
                    Some(GitAction::Refresh),
                );
                self.commit_msg.clear();
                self.request_refresh();
            }
            if ui.small_button("Stage all").clicked() {
                self.busy = true;
                self.spawn(
                    vec!["add".into(), "-A".into()],
                    GitAction::Stage,
                    self.tx.clone(),
                    Some(GitAction::Refresh),
                );
                self.request_refresh();
            }
        });
        ui.add(
            eframe::egui::TextEdit::singleline(&mut self.commit_msg)
                .hint_text("Commit message")
                .desired_width(ui.available_width()),
        );
        ui.add_space(6.0);

        // File lists.
        eframe::egui::ScrollArea::vertical().show(ui, |ui| {
            theme::section_header(ui, &format!("Staged ({})", self.staged.len()));
            if self.staged.is_empty() {
                ui.style_mut().override_font_id = Some(FontId::proportional(11.5));
                ui.colored_label(palette::on_surface_variant(), "nothing staged");
            }
            let staged_files: Vec<GitFile> = self.staged.clone();
            for f in &staged_files {
                self.file_row(ui, f, true);
            }

            theme::section_header(ui, &format!("Changes ({})", self.unstaged.len()));
            if self.unstaged.is_empty() {
                ui.style_mut().override_font_id = Some(FontId::proportional(11.5));
                ui.colored_label(palette::on_surface_variant(), "working tree clean");
            }
            let unstaged_files: Vec<GitFile> = self.unstaged.clone();
            for f in &unstaged_files {
                self.file_row(ui, f, false);
            }

            if !self.diff.is_empty() || !self.diff_title.is_empty() {
                theme::section_header(ui, &self.diff_title);
                eframe::egui::Frame::default()
                    .fill(palette::code_bg())
                    .rounding(eframe::egui::Rounding::same(6.0))
                    .inner_margin(eframe::egui::Margin::same(8.0))
                    .show(ui, |ui| {
                        ui.set_width(ui.available_width());
                        ui.style_mut().override_font_id = Some(FontId::monospace(11.0));
                        for line in self.diff.lines().take(400) {
                            let color = if line.starts_with('+') {
                                palette::diff_add()
                            } else if line.starts_with('-') {
                                palette::diff_del()
                            } else if line.starts_with("@@") {
                                palette::link()
                            } else {
                                palette::on_surface_variant()
                            };
                            ui.add(
                                eframe::egui::Label::new(
                                    eframe::egui::RichText::new(line.to_string()).color(color),
                                )
                                .truncate()
                                .selectable(true),
                            );
                        }
                    });
            }

            theme::section_header(ui, "Recent commits");
            for line in &self.log {
                ui.style_mut().override_font_id = Some(FontId::monospace(11.0));
                let (sha, rest) = line.split_once(' ').unwrap_or((line.as_str(), ""));
                ui.horizontal(|ui| {
                    ui.colored_label(palette::primary(), sha.to_string());
                    ui.add(
                        eframe::egui::Label::new(
                            eframe::egui::RichText::new(rest).color(palette::on_surface_variant()),
                        )
                        .truncate(),
                    );
                });
            }
        });
    }

    fn file_row(&mut self, ui: &mut Ui, f: &GitFile, staged: bool) {
        let selected = self.selected.as_deref() == Some(f.path.as_str());
        let resp = eframe::egui::Frame::default()
            .fill(if selected {
                palette::surface_variant()
            } else {
                eframe::egui::Color32::TRANSPARENT
            })
            .rounding(eframe::egui::Rounding::same(6.0))
            .inner_margin(eframe::egui::Margin::symmetric(6.0, 4.0))
            .show(ui, |ui| {
                ui.set_width(ui.available_width());
                ui.horizontal(|ui| {
                    let badge_color = if f.is_untracked() {
                        palette::on_surface_variant()
                    } else {
                        palette::warning()
                    };
                    theme::chip(ui, &f.status_label(), badge_color, palette::surface_variant());
                    ui.style_mut().override_font_id = Some(FontId::monospace(11.5));
                    ui.add(
                        eframe::egui::Label::new(
                            eframe::egui::RichText::new(f.path.as_str()).color(palette::on_background()),
                        )
                        .truncate(),
                    );
                    ui.with_layout(eframe::egui::Layout::right_to_left(eframe::egui::Align::Center), |ui| {
                        if ui.small_button("diff").clicked() {
                            self.request_diff(&f.path, staged);
                        }
                        if staged {
                            if ui
                                .small_button(crate::icons::glyph::MINUS)
                                .on_hover_text("unstage")
                                .clicked()
                            {
                                self.busy = true;
                                self.spawn(
                                    vec![
                                        "restore".into(),
                                        "--staged".into(),
                                        "--".into(),
                                        f.path.clone(),
                                    ],
                                    GitAction::Unstage,
                                    self.tx.clone(),
                                    Some(GitAction::Refresh),
                                );
                                self.request_refresh();
                            }
                        } else if ui
                            .small_button(crate::icons::glyph::PLUS)
                            .on_hover_text("stage")
                            .clicked()
                        {
                            self.busy = true;
                            self.spawn(
                                vec!["add".into(), "--".into(), f.path.clone()],
                                GitAction::Stage,
                                self.tx.clone(),
                                Some(GitAction::Refresh),
                            );
                            self.request_refresh();
                        }
                    });
                });
            })
            .response;
        if resp.clicked() {
            self.selected = Some(f.path.clone());
        }
    }

    fn drain(&mut self) {
        while let Ok(msg) = self.rx.try_recv() {
            match msg {
                GitMsg::Branch(branch, ahead, behind) => {
                    self.branch = branch;
                    self.ahead = ahead;
                    self.behind = behind;
                }
                GitMsg::Status(files) => {
                    self.staged = files.iter().filter(|f| f.staged).cloned().collect();
                    self.unstaged = files.iter().filter(|f| !f.staged).cloned().collect();
                    self.busy = false;
                }
                GitMsg::Diff(diff) => {
                    self.diff = diff;
                    if self.diff.trim().is_empty() {
                        self.diff_title = format!("{} (no textual diff)", self.diff_title);
                    }
                }
                GitMsg::Log(log) => self.log = log,
                GitMsg::Done(action) => {
                    if matches!(action, GitAction::Refresh) {
                        self.busy = false;
                    }
                    self.last_error = None;
                }
                GitMsg::Error(action, err) => {
                    self.busy = false;
                    self.last_error = Some(format!("{}: {}", action.label(), err));
                }
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_porcelain_status() {
        let out = "## main...origin/main [ahead 1]\n M src/main.rs\n?? notes.txt\nM  staged.kt\n";
        let mut branch = String::new();
        let mut ahead = 0;
        let mut files = Vec::new();
        for line in out.lines() {
            if let Some(rest) = line.strip_prefix("## ") {
                branch = rest.split("...").next().unwrap_or(rest).trim().to_string();
                if let Some(ab) = rest.split_once("[") {
                    for part in ab.1.trim_end_matches(']').split(',') {
                        let part = part.trim();
                        if let Some(n) = part.strip_prefix("ahead ") {
                            ahead = n.trim().parse().unwrap_or(0);
                        }
                    }
                }
            } else if line.len() >= 4 {
                let x = line.as_bytes()[0] as char;
                let y = line.as_bytes()[1] as char;
                let path = line[3..].trim().to_string();
                files.push(GitFile {
                    path,
                    x,
                    y,
                    staged: x != ' ' && x != '?',
                });
            }
        }
        assert_eq!(branch, "main");
        assert_eq!(ahead, 1);
        assert_eq!(files.len(), 3);
        assert_eq!(files[0].status_label(), "M");
        assert!(files[1].is_untracked());
        assert!(files[2].staged);
    }
}
