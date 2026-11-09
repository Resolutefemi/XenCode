//! XenCode theme — exact port of ZCode's dark theme tokens from
//! `packages/ui/src/styles.css` (.dark block) resolved over the Tailwind v4 palette.
//!
//! Token math: color-mix(white X%, transparent) composited over --color-background
//! (neutral-900 #171717) gives the resolved sRGB value below.
//!
//! Some tokens are ported ahead of the screens that use them (chat bubbles,
//! destructive states, etc.) — they are the contract for the upcoming ports.

#![allow(dead_code)]

use egui::Color32;

// Tailwind v4 base palette (resolved from the token chains)
pub const NEUTRAL_50: Color32 = Color32::from_rgb(0xfa, 0xfa, 0xfa);
pub const NEUTRAL_200: Color32 = Color32::from_rgb(0xe5, 0xe5, 0xe5);
pub const NEUTRAL_500: Color32 = Color32::from_rgb(0x73, 0x73, 0x73);
pub const NEUTRAL_700: Color32 = Color32::from_rgb(0x40, 0x40, 0x40);
pub const NEUTRAL_800: Color32 = Color32::from_rgb(0x26, 0x26, 0x26);
pub const NEUTRAL_900: Color32 = Color32::from_rgb(0x17, 0x17, 0x17);
pub const NEUTRAL_950: Color32 = Color32::from_rgb(0x0a, 0x0a, 0x0a);
pub const WHITE: Color32 = Color32::from_rgb(0xff, 0xff, 0xff);

// .dark token resolutions
/// --color-sidebar: neutral-950
pub const SIDEBAR: Color32 = NEUTRAL_950;
/// --color-background / --color-panel / --color-header: neutral-900
pub const BG: Color32 = NEUTRAL_900;
/// --color-card / --color-popover / --color-input: neutral-800
pub const CARD: Color32 = NEUTRAL_800;
/// --color-card-selected: neutral-700
pub const CARD_SELECTED: Color32 = NEUTRAL_700;
/// --color-foreground: neutral-200
pub const FG: Color32 = NEUTRAL_200;
/// --color-foreground-subtle: neutral-200 @ 60% over BG
pub const FG_SUBTLE: Color32 = Color32::from_rgb(0x93, 0x93, 0x93);
/// --color-foreground-subtlest: neutral-200 @ 30% over BG
pub const FG_SUBTLEST: Color32 = Color32::from_rgb(0x55, 0x55, 0x55);
/// --color-border: white @ 10% over BG
pub const BORDER: Color32 = Color32::from_rgb(0x2e, 0x2e, 0x2e);
/// --color-border-hover: white @ 30% over BG
pub const BORDER_HOVER: Color32 = Color32::from_rgb(0x5d, 0x5d, 0x5d);
/// --color-surface: white @ 5% over BG
pub const SURFACE: Color32 = Color32::from_rgb(0x23, 0x23, 0x23);
/// --color-surface-hover: white @ 10% over BG
pub const SURFACE_HOVER: Color32 = Color32::from_rgb(0x2e, 0x2e, 0x2e);
/// --color-user-message-bubble: white @ 8% over BG
pub const USER_BUBBLE: Color32 = Color32::from_rgb(0x2a, 0x2a, 0x2a);
/// --color-brand: sky-500
pub const BRAND: Color32 = Color32::from_rgb(0x0e, 0xa5, 0xe9);
/// sky-400 (resize handle / bright brand)
pub const BRAND_BRIGHT: Color32 = Color32::from_rgb(0x38, 0xbd, 0xf8);
/// --color-primary: neutral-50 (button face), foreground: neutral-950
pub const PRIMARY: Color32 = NEUTRAL_50;
pub const PRIMARY_FG: Color32 = NEUTRAL_950;
/// --color-destructive: red-500
pub const DESTRUCTIVE: Color32 = Color32::from_rgb(0xef, 0x44, 0x44);
/// --color-success: green-500
pub const SUCCESS: Color32 = Color32::from_rgb(0x22, 0xc5, 0x5e);
/// --color-warning: yellow-500
pub const WARNING: Color32 = Color32::from_rgb(0xea, 0xb3, 0x08);
/// --color-idle-task: violet-400
pub const IDLE_TASK: Color32 = Color32::from_rgb(0xa7, 0x8b, 0xfa);
/// trajectory accent (reasoning) violet
pub const TRAJECTORY_REASONING: Color32 = Color32::from_rgb(0xa7, 0x8b, 0xfa);

pub const RADIUS_CARD: f32 = 16.0;
pub const RADIUS_ROW: f32 = 8.0;
pub const RADIUS_CHIP: f32 = 10.0;
pub const SIDEBAR_WIDTH: f32 = 272.0;
pub const TOPBAR_HEIGHT: f32 = 44.0;
pub const TERMINAL_DOCK_HEIGHT: f32 = 190.0;
