//! Font installation: bold + CJK + symbol-coverage families.
//!
//! egui ships Ubuntu Light, Hack and Noto Emoji. We add:
//! - "XenSansSC"  (Noto Sans SC Regular) for CJK body text
//! - "XenBold"    (Noto Sans SC Bold) as a real bold family
//! - "XenMonoSC"  (Sarasa Mono SC) as a CJK-capable monospace
//! - DejaVu Sans / Mono appended to every family as the symbol fallback
//!   (chevrons, ticks, branches, ... all render tofu-free).

use egui::{FontData, FontDefinitions, FontFamily};

const CN_DIR: &str = "/usr/share/fonts/truetype/chinese";
const DEJAVU_DIR: &str = "/usr/share/fonts/truetype/dejavu";

fn load(path: &str) -> Option<FontData> {
    std::fs::read(path).ok().map(|bytes| FontData::from_owned(bytes).tweak(egui::FontTweak::default()))
}

/// Install the XenCode font families into the context.
pub fn install(ctx: &egui::Context) {
    let mut fonts = FontDefinitions::default();

    let noto_sc = load(&format!("{CN_DIR}/NotoSansSC-Regular.ttf"));
    let noto_sc_bold = load(&format!("{CN_DIR}/NotoSansSC-Bold.ttf"));
    let sarasa = load(&format!("{CN_DIR}/SarasaMonoSC-Regular.ttf"));
    let dejavu = load(&format!("{DEJAVU_DIR}/DejaVuSans.ttf"));
    let dejavu_bold = load(&format!("{DEJAVU_DIR}/DejaVuSans-Bold.ttf"));
    let dejavu_mono = load(&format!("{DEJAVU_DIR}/DejaVuSansMono.ttf"));

    let mut insert = |name: &str, data: Option<FontData>| {
        if let Some(data) = data {
            fonts.font_data.insert(name.to_string(), data);
            true
        } else {
            false
        }
    };

    let has_sc = insert("XenSansSC", noto_sc);
    insert("XenBold", noto_sc_bold);
    let has_sarasa = insert("XenMonoSC", sarasa);
    let has_dejavu = insert("XenDejaVu", dejavu);
    let has_dejavu_bold = insert("XenDejaVuBold", dejavu_bold);
    let has_dejavu_mono = insert("XenDejaVuMono", dejavu_mono);

    // Proportional: Ubuntu, then CJK, then DejaVu symbols.
    let prop = fonts.families.entry(FontFamily::Proportional).or_default();
    if has_sc {
        prop.push("XenSansSC".into());
    }
    if has_dejavu {
        prop.push("XenDejaVu".into());
    }

    // Bold family: Noto Sans SC Bold with DejaVu Bold fallback.
    fonts.families.insert(
        FontFamily::Name("XenBold".into()),
        {
            let mut fam = Vec::new();
            fam.push("XenBold".into());
            if has_dejavu_bold {
                fam.push("XenDejaVuBold".into());
            } else if has_dejavu {
                fam.push("XenDejaVu".into());
            }
            fam
        },
    );

    // Monospace: Hack, then Sarasa (CJK), then DejaVu Mono.
    let mono = fonts.families.entry(FontFamily::Monospace).or_default();
    if has_sarasa {
        mono.push("XenMonoSC".into());
    }
    if has_dejavu_mono {
        mono.push("XenDejaVuMono".into());
    }

    ctx.set_fonts(fonts);
}
