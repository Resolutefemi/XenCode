//! XenCode — 100% Rust native GUI.
//!
//! Headless render mode: `xencode --screenshot --out DIR` draws every primary
//! page through the same renderer the live window uses (tiny-skia software
//! rasterizer) and writes PNGs — the visual ground truth of the GUI.

mod fonts;
mod gfx;
mod icons;
mod svgpath;
mod theme;
mod ui;

use gfx::Canvas;

fn main() {
    let mut out = String::from("/home/z/my-project/download/xencode-rust-screenshots");
    let args: Vec<String> = std::env::args().collect();
    let mut mode = String::from("screenshot");
    let mut i = 0;
    while i < args.len() {
        match args[i].as_str() {
            "--out" if i + 1 < args.len() => {
                out = args[i + 1].clone();
                i += 1;
            }
            other => mode = other.to_string(),
        }
        i += 1;
    }

    match mode.as_str() {
        "--screenshot" | "screenshot" | "" => screenshot(&out),
        "--window" => {
            eprintln!("live window mode: not wired yet (winit+softbuffer next); use --screenshot");
            std::process::exit(2);
        }
        other => {
            eprintln!("unknown mode: {other}");
            std::process::exit(2);
        }
    }
}

const SCALE: f32 = 2.0;

fn screenshot(out: &str) {
    std::fs::create_dir_all(out).expect("create out dir");
    let pages: &[(&str, fn(&mut Canvas))] = &[
        ("01-splash", ui::splash::draw),
        ("02-home", ui::home),
        ("03-chat", ui::chat),
        ("04-settings-appearance", ui::settings),
        ("05-git-pane", ui::git),
        ("06-terminal-dock", ui::terminal),
    ];
    for (name, draw) in pages {
        let mut c = Canvas::new(ui::shell::WIN_W, ui::shell::WIN_H, SCALE);
        draw(&mut c);
        let path = format!("{out}/{name}.png");
        c.px.save_png(&path).unwrap_or_else(|e| panic!("save {path}: {e:?}"));
        println!("rendered {path}");
    }
}
