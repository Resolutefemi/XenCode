//! XenCode desktop (Rust rewrite): entry point.
//!
//! Flags:
//!   --demo            seed a demo conversation (great for screenshots)
//!   --pane <tab>      open the right dock on a specific tab (git|terminal|files)
//!   --workspace <dir> override the workspace (default: cwd)

mod app;

pub use app::Conversation;
mod cloud;
mod composer;
mod conversation;
mod demo;
mod fonts;
mod icons;
mod markdown;
mod panes;
mod sidebar;
mod theme;

use app::XenApp;
use panes::DockTab;

fn main() -> eframe::Result {
    let args: Vec<String> = std::env::args().collect();
    let demo = args.iter().any(|a| a == "--demo");

    let workspace = flag_value(&args, "--workspace")
        .map(String::from)
        .or_else(|| std::env::var("XENCODE_WORKSPACE").ok())
        .unwrap_or_else(|| {
            std::env::current_dir()
                .map(|p| p.to_string_lossy().into_owned())
                .unwrap_or_else(|_| ".".into())
        });

    let pane = flag_value(&args, "--pane").and_then(|p| match p {
        "git" => Some(DockTab::Git),
        "terminal" | "term" => Some(DockTab::Terminal),
        "files" => Some(DockTab::Files),
        _ => None,
    });

    let cloud_config = cloud::CloudConfig::load().filter(|c| c.configured());

    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_inner_size([1440.0, 900.0])
            .with_min_inner_size([1000.0, 640.0])
            .with_title("XenCode"),
        ..Default::default()
    };

    eframe::run_native(
        "XenCode",
        options,
        Box::new(move |cc| {
            theme::apply(&cc.egui_ctx);
            fonts::install(&cc.egui_ctx);
            let mut app = XenApp::new(workspace, cloud_config);
            if demo {
                app.seed_demo();
            }
            if let Some(tab) = pane {
                app.open_dock_tab(tab);
            }
            Ok(Box::new(app))
        }),
    )
}

fn flag_value<'a>(args: &'a [String], flag: &str) -> Option<&'a str> {
    args.iter()
        .position(|a| a == flag)
        .and_then(|i| args.get(i + 1))
        .map(|s| s.as_str())
        .filter(|s| !s.starts_with("--"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn flag_parsing() {
        let args: Vec<String> = [
            "xencode-desktop",
            "--demo",
            "--pane",
            "git",
            "--workspace",
            "/tmp",
        ]
        .iter()
        .map(|s| s.to_string())
        .collect();
        assert!(args.iter().any(|a| a == "--demo"));
        assert_eq!(flag_value(&args, "--pane"), Some("git"));
        assert_eq!(flag_value(&args, "--workspace"), Some("/tmp"));
        assert_eq!(flag_value(&args, "--missing"), None);
    }
}
