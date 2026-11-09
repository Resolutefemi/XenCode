//! Font setup — registers a real bold face under the "Bold" font family so
//! markdown headings/bold runs can use it in LayoutJobs. Falls back to the
//! regular proportional stack when no bold face is found on the system.

use egui::FontData;

const BOLD_CANDIDATES: &[&str] = &[
    // Linux
    "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
    "/usr/share/fonts/truetype/noto/NotoSans-Bold.ttf",
    "/usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf",
    "/usr/share/fonts/truetype/ubuntu/Ubuntu-Bold.ttf",
    // Windows
    "C:\\Windows\\Fonts\\segoeuib.ttf",
    "C:\\Windows\\Fonts\\arialbd.ttf",
    // macOS
    "/System/Library/Fonts/Supplemental/Arial Bold.ttf",
    "/Library/Fonts/Arial Bold.ttf",
];

pub fn install(ctx: &egui::Context) {
    let mut fonts = egui::FontDefinitions::default();

    let bold_bytes = BOLD_CANDIDATES.iter().find_map(|p| std::fs::read(p).ok());

    let bold_stack = match bold_bytes {
        Some(bytes) => {
            fonts
                .font_data
                .insert("xencode-bold".to_string(), FontData::from_owned(bytes));
            vec!["xencode-bold".to_string(), "Ubuntu-Light".to_string()]
        }
        None => fonts
            .families
            .get(&egui::FontFamily::Proportional)
            .cloned()
            .unwrap_or_default(),
    };
    fonts
        .families
        .insert(egui::FontFamily::Name("Bold".into()), bold_stack);

    ctx.set_fonts(fonts);
}
