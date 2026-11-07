//! Files side pane: an expandable workspace tree with kind + size info.

use crate::theme::palette;
use eframe::egui::{FontId, Ui};
use std::collections::HashSet;
use std::path::{Path, PathBuf};

pub struct FilesPane {
    workspace: String,
    expanded: HashSet<PathBuf>,
    selected: Option<PathBuf>,
    selected_info: String,
}

impl FilesPane {
    pub fn new(workspace: &str) -> Self {
        FilesPane {
            workspace: workspace.to_string(),
            expanded: HashSet::new(),
            selected: None,
            selected_info: String::new(),
        }
    }

    pub fn ui(&mut self, ui: &mut Ui) {
        let root = PathBuf::from(&self.workspace);
        ui.horizontal(|ui| {
            ui.style_mut().override_font_id = Some(FontId::proportional(11.0));
            ui.colored_label(palette::on_surface_variant(), self.workspace.clone());
            ui.with_layout(eframe::egui::Layout::right_to_left(eframe::egui::Align::Center), |ui| {
                if ui.small_button("Collapse").clicked() {
                    self.expanded.clear();
                }
            });
        });
        ui.separator();

        eframe::egui::ScrollArea::vertical()
            .id_salt("files_scroll")
            .show(ui, |ui| {
                self.dir_ui(ui, &root, 0);
            });

        ui.separator();
        ui.style_mut().override_font_id = Some(FontId::monospace(10.5));
        if let Some(sel) = &self.selected {
            ui.colored_label(palette::on_surface_variant(), sel.to_string_lossy().as_ref());
            ui.colored_label(palette::primary(), &self.selected_info);
        } else {
            ui.colored_label(
                palette::on_surface_variant(),
                "select a file to see its details",
            );
        }
    }

    fn dir_ui(&mut self, ui: &mut Ui, dir: &Path, depth: usize) {
        let Ok(entries) = std::fs::read_dir(dir) else {
            return;
        };
        let mut items: Vec<(PathBuf, bool, u64)> = entries
            .filter_map(|e| e.ok())
            .filter(|e| !e.file_name().to_string_lossy().starts_with('.'))
            .filter_map(|e| {
                let path = e.path();
                let meta = e.metadata().ok()?;
                let is_dir = meta.is_dir();
                let size = meta.len();
                Some((path, is_dir, size))
            })
            .collect();
        items.sort_by(|a, b| match (a.1, b.1) {
            (true, false) => std::cmp::Ordering::Less,
            (false, true) => std::cmp::Ordering::Greater,
            _ => a.0.file_name().cmp(&b.0.file_name()),
        });

        for (path, is_dir, size) in items {
            let name = path
                .file_name()
                .map(|n| n.to_string_lossy().into_owned())
                .unwrap_or_default();
            let indent = depth as f32 * 14.0;
            let is_expanded = self.expanded.contains(&path);
            let selected = self.selected.as_deref() == Some(path.as_path());

            let frame = if selected {
                eframe::egui::Frame::default()
                    .fill(palette::surface_variant())
                    .rounding(eframe::egui::Rounding::same(6.0))
            } else {
                eframe::egui::Frame::default()
            };
            let resp = frame
                .show(ui, |ui| {
                    ui.set_width(ui.available_width());
                    ui.horizontal(|ui| {
                        ui.add_space(indent);
                        ui.style_mut().override_font_id = Some(FontId::proportional(12.5));
                        if is_dir {
                            let arrow = if is_expanded {
                                crate::icons::glyph::CHEVRON_DOWN
                            } else {
                                crate::icons::glyph::CHEVRON_RIGHT
                            };
                            ui.colored_label(palette::primary(), arrow);
                            ui.colored_label(palette::on_background(), name);
                        } else {
                            ui.colored_label(
                                palette::on_surface_variant(),
                                crate::icons::glyph::FILE,
                            );
                            ui.colored_label(palette::on_surface_variant(), name);
                        }
                    });
                })
                .response;
            if resp.clicked() {
                if is_dir {
                    if self.expanded.contains(&path) {
                        self.expanded.remove(&path);
                    } else {
                        self.expanded.insert(path.clone());
                    }
                } else {
                    self.selected = Some(path.clone());
                    self.selected_info = format!("{} · {}", kind_of(&path), human_size(size));
                }
            }
            if is_dir && is_expanded {
                self.dir_ui(ui, &path, depth + 1);
            }
        }
    }
}

fn kind_of(path: &Path) -> &'static str {
    match path
        .extension()
        .and_then(|e| e.to_str())
        .unwrap_or("")
        .to_ascii_lowercase()
        .as_str()
    {
        "rs" => "Rust source",
        "kt" | "kts" => "Kotlin source",
        "md" => "Markdown",
        "json" => "JSON",
        "yml" | "yaml" => "YAML",
        "toml" => "TOML",
        "js" | "mjs" => "JavaScript",
        "py" => "Python",
        "html" => "HTML",
        "css" => "CSS",
        "png" | "jpg" | "jpeg" | "webp" | "gif" => "Image",
        "sql" => "SQL",
        "sh" => "Shell script",
        _ => "File",
    }
}

fn human_size(bytes: u64) -> String {
    if bytes < 1024 {
        format!("{bytes} B")
    } else if bytes < 1024 * 1024 {
        format!("{:.1} KB", bytes as f64 / 1024.0)
    } else {
        format!("{:.1} MB", bytes as f64 / (1024.0 * 1024.0))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sizes_and_kinds() {
        assert_eq!(human_size(512), "512 B");
        assert_eq!(human_size(2048), "2.0 KB");
        assert_eq!(human_size(3 * 1024 * 1024), "3.0 MB");
        assert_eq!(kind_of(Path::new("a/b/main.rs")), "Rust source");
        assert_eq!(kind_of(Path::new("x.png")), "Image");
        assert_eq!(kind_of(Path::new("noext")), "File");
    }
}
