//! XenCode theme — ported 1:1 from `packages/ui/src/styles.css`, `.theme-zai-dark`
//! (the default theme, `useTheme.ts` fallback "zai-dark").
//! Every value below is the exact token from the reference stylesheet.

use tiny_skia::Color;

#[allow(non_snake_case)]
pub struct T;

impl T {
    // Surfaces (zai-dark)
    pub fn BG() -> Color { Color::from_rgba8(0x16, 0x16, 0x16, 255) } // --color-background
    pub fn WIN_ALT() -> Color { Color::from_rgba8(0x2b, 0x2b, 0x2b, 255) } // --color-background-win-alt
    pub fn PANEL() -> Color { Color::from_rgba8(0x20, 0x20, 0x20, 255) } // --color-panel / header
    pub fn CARD() -> Color { Color::from_rgba8(0x2b, 0x2b, 0x2b, 255) } // --color-card / input / popover
    pub fn SECONDARY() -> Color { Color::from_rgba8(0x36, 0x36, 0x36, 255) } // --color-secondary / menu-hover / tag

    // Borders & fills (white alphas over the dark surfaces)
    pub fn BORDER() -> Color { Color::from_rgba8(255, 255, 255, 26) } // rgba(255,255,255,0.10)
    pub fn BORDER_HOVER() -> Color { Color::from_rgba8(255, 255, 255, 38) } // 0.15
    pub fn DIVIDER() -> Color { Color::from_rgba8(255, 255, 255, 13) } // border/50
    pub fn HOVER() -> Color { Color::from_rgba8(255, 255, 255, 13) } // 0.05
    pub fn SELECTED() -> Color { Color::from_rgba8(255, 255, 255, 26) } // 0.10
    pub fn SURFACE() -> Color { Color::from_rgba8(255, 255, 255, 13) } // 0.05
    pub fn SURFACE_HOVER() -> Color { Color::from_rgba8(255, 255, 255, 26) } // 0.10
    pub fn USER_BUBBLE() -> Color { Color::from_rgba8(255, 255, 255, 20) } // 0.08

    // Foreground
    pub fn FG() -> Color { Color::from_rgba8(0xd4, 0xd4, 0xd4, 255) } // neutral-300
    pub fn FG_SUBTLE() -> Color { Color::from_rgba8(0xd4, 0xd4, 0xd4, 153) } // 60%
    pub fn FG_SUBTLEST() -> Color { Color::from_rgba8(0xd4, 0xd4, 0xd4, 77) } // 30%
    pub fn WHITE() -> Color { Color::from_rgba8(255, 255, 255, 255) }
    pub fn BLACK() -> Color { Color::from_rgba8(0, 0, 0, 255) }

    // Brand / status
    pub fn BRAND() -> Color { Color::from_rgba8(255, 255, 255, 255) } // --color-brand (zai-dark = #ffffff)
    pub fn FG_INVERSE() -> Color { Color::from_rgba8(0, 0, 0, 255) } // --color-foreground-inverse
    pub fn SUCCESS() -> Color { Color::from_rgba8(0x46, 0xbf, 0x72, 255) }
    pub fn DESTRUCTIVE() -> Color { Color::from_rgba8(0xff, 0x5c, 0x5c, 255) }
    pub fn WARNING() -> Color { Color::from_rgba8(0xff, 0x8a, 0x30, 255) }
    pub fn ICON_BLUE() -> Color { Color::from_rgba8(0x40, 0x99, 0xff, 255) }
    pub fn ACCENT() -> Color { Color::from_rgba8(0x00, 0x1d, 0x3d, 255) }
    pub fn SKY() -> Color { Color::from_rgba8(0x38, 0xbd, 0xf8, 255) } // sky-400 (task dot, dark)

    // Git status colors
    pub fn GIT_MODIFIED() -> Color { Color::from_rgba8(0xff, 0x8a, 0x30, 255) }
    pub fn GIT_ADDED() -> Color { Color::from_rgba8(0x46, 0xbf, 0x72, 255) }
    pub fn GIT_DELETED() -> Color { Color::from_rgba8(0xff, 0x5c, 0x5c, 255) }
    pub fn GIT_RENAMED() -> Color { Color::from_rgba8(0x40, 0x99, 0xff, 255) }

    // Terminal ANSI (zai-dark block in styles.css)
    pub fn TERM_RED() -> Color { Color::from_rgba8(0xff, 0x5c, 0x5c, 255) }
    pub fn TERM_GREEN() -> Color { Color::from_rgba8(0x46, 0xbf, 0x72, 255) }
    pub fn TERM_YELLOW() -> Color { Color::from_rgba8(0xff, 0x8a, 0x30, 255) }
    pub fn TERM_BLUE() -> Color { Color::from_rgba8(0x40, 0x99, 0xff, 255) }
    pub fn TERM_MAGENTA() -> Color { Color::from_rgba8(0x7b, 0x5c, 0xe5, 255) }
    pub fn TERM_CYAN() -> Color { Color::from_rgba8(0x42, 0xc8, 0xc8, 255) }
    pub fn TERM_WHITE() -> Color { Color::from_rgba8(0xad, 0xad, 0xad, 255) }
    pub fn TERM_BRIGHT_BLACK() -> Color { Color::from_rgba8(0x74, 0x74, 0x74, 255) }
    pub fn TERM_BRIGHT_WHITE() -> Color { Color::from_rgba8(0xf8, 0xf8, 0xf8, 255) }

    // Code highlighting (GitHub Dark family, used by the Shiki dual theme)
    pub fn CODE_PLAIN() -> Color { Color::from_rgba8(0xe6, 0xed, 0xf3, 255) }
    pub fn CODE_KW() -> Color { Color::from_rgba8(0xff, 0x7b, 0x72, 255) }
    pub fn CODE_STR() -> Color { Color::from_rgba8(0xa5, 0xd6, 0xff, 255) }
    pub fn CODE_FN() -> Color { Color::from_rgba8(0xd2, 0xa8, 0xff, 255) }
    pub fn CODE_CONST() -> Color { Color::from_rgba8(0x79, 0xc0, 0xff, 255) }
    pub fn CODE_COMMENT() -> Color { Color::from_rgba8(0x8b, 0x94, 0x9e, 255) }

    // Diff fills (surface tints)
    pub fn DIFF_ADD_BG() -> Color { Color::from_rgba8(0x46, 0xbf, 0x72, 30) }
    pub fn DIFF_DEL_BG() -> Color { Color::from_rgba8(0xff, 0x5c, 0x5c, 30) }

    // Splash badge gradient (packages/desktop/src/renderer/index.html)
    pub fn SPLASH_TOP() -> Color { Color::from_rgba8(0x00, 0x00, 0x00, 255) }
    pub fn SPLASH_BOTTOM() -> Color { Color::from_rgba8(0x15, 0x17, 0x18, 255) }
    pub fn SPLASH_RING() -> Color { Color::from_rgba8(255, 255, 255, 26) } // rgba(255,255,255,0.1)
}
