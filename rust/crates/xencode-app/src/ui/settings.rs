//! Settings workspace view — exact port of ZCode's settings navigation from
//! `packages/ui/src/settings/settingsPageConfig.ts` (desktop variant) with the
//! en-US display strings from `i18n/locales/en-US.ts`.
//!
//! Groups & sections (order preserved verbatim):
//!   Basics: General, Appearance, Model settings, Browser Use, Remote devices,
//!           Computer Use, Keyboard Shortcuts
//!   Agent capabilities: Personalization, Memory, Subagents, Plugins,
//!           MCP Servers, Skills, Commands, Hooks
//!   Data and statistics: Data sync, Usage stats, Projects, Archive management
//!
//! Content panes port incrementally; Appearance carries the real theme-mode
//! segmented control (System / ZAI Dark / ZAI Light — THEME_MODES).

use egui::{Align2, FontId, Pos2, Rect, Rounding, Sense, Stroke, Vec2};

use crate::icons;
use crate::theme;
use crate::AppState;

#[derive(Clone, Copy, PartialEq, Eq, Debug, Default)]
pub enum Section {
    #[default]
    General,
    Appearance,
    ModelProvider,
    Browser,
    RemoteDevices,
    ComputerUse,
    Shortcuts,
    Personalization,
    Memory,
    Subagents,
    Plugin,
    Mcp,
    Skill,
    Commands,
    Hooks,
    DataSync,
    Usage,
    Projects,
    Archives,
}

impl Section {
    fn title(self) -> &'static str {
        match self {
            // en-US.ts keys, verbatim
            Self::General => "General",       // settings.systemTitle
            Self::Appearance => "Appearance", // settings.appearanceTitle
            Self::ModelProvider => "Model settings", // settings.modelProviderTitle
            Self::Browser => "Browser Use",   // settings.browser.title
            Self::RemoteDevices => "Remote devices", // settings.remoteDevicesTitle
            Self::ComputerUse => "Computer Use", // settings.computerUse.title
            Self::Shortcuts => "Keyboard Shortcuts", // settings.shortcuts.title
            Self::Personalization => "Personalization", // settings.personalization.title
            Self::Memory => "Memory",         // settings.memory
            Self::Subagents => "Subagents",   // settings.subagents.title
            Self::Plugin => "Plugins",        // settings.plugins.title
            Self::Mcp => "MCP Servers",       // settings.mcpTitle
            Self::Skill => "Skills",          // settings.skills.title
            Self::Commands => "Commands",     // settings.commands.title
            Self::Hooks => "Hooks",           // settings.hooks.title
            Self::DataSync => "Data sync",    // remoteSync.title
            Self::Usage => "Usage stats",     // settings.usageTitle
            Self::Projects => "Projects",     // projectManagement.nav
            Self::Archives => "Archive management", // archives.nav
        }
    }

    fn icon(self) -> fn(&egui::Painter, Rect, egui::Color32) {
        match self {
            Self::General => icons::sliders,          // Settings2
            Self::Appearance => icons::palette,       // Palette
            Self::ModelProvider => icons::package,    // Package
            Self::Browser => icons::globe,            // Globe2
            Self::RemoteDevices => icons::hard_drive, // HardDrive
            Self::ComputerUse => icons::monitor,      // Monitor
            Self::Shortcuts => icons::keyboard,       // Keyboard
            Self::Personalization => icons::user,     // User
            Self::Memory => icons::brain,             // Brain
            Self::Subagents => icons::bot,            // Bot
            Self::Plugin => icons::blocks,            // Blocks
            Self::Mcp => icons::cable,                // Cable
            Self::Skill => icons::wand,               // WandSparkles
            Self::Commands => icons::terminal_square, // Terminal
            Self::Hooks => icons::anchor,             // Anchor
            Self::DataSync => icons::refresh,         // RefreshCw
            Self::Usage => icons::bar_chart,          // BarChart3
            Self::Projects => icons::folders,         // Folders
            Self::Archives => icons::archive,         // Archive
        }
    }
}

/// createSettingsPageConfig({ isDesktop: true }) minus hidden sections,
/// order preserved from BASE_SETTINGS_SECTIONS.
fn groups() -> Vec<(&'static str, Vec<Section>)> {
    vec![
        (
            "Basics", // settings.sidebar.group.basics
            vec![
                Section::General,
                Section::Appearance,
                Section::ModelProvider,
                Section::Browser,
                Section::RemoteDevices,
                Section::ComputerUse,
                Section::Shortcuts,
            ],
        ),
        (
            "Agent capabilities", // settings.sidebar.group.agentCapabilities
            vec![
                Section::Personalization,
                Section::Memory,
                Section::Subagents,
                Section::Plugin,
                Section::Mcp,
                Section::Skill,
                Section::Commands,
                Section::Hooks,
            ],
        ),
        (
            "Data and statistics", // settings.sidebar.group.dataAndStats
            vec![
                Section::DataSync,
                Section::Usage,
                Section::Projects,
                Section::Archives,
            ],
        ),
    ]
}

#[derive(Default)]
pub struct SettingsState {
    pub open: bool,
    pub section: Section,
}

fn text(p: &egui::Painter, pos: Pos2, anchor: Align2, size: f32, color: egui::Color32, s: &str) {
    p.text(pos, anchor, s, FontId::proportional(size), color);
}

pub fn render(ctx: &egui::Context, state: &mut AppState) {
    egui::CentralPanel::default()
        .frame(egui::Frame::none().fill(theme::BG))
        .show(ctx, |ui| {
            // ---------- header: back + breadcrumb ----------
            let (hb, _) = ui.allocate_exact_size(
                Vec2::new(ui.available_width(), theme::TOPBAR_HEIGHT),
                Sense::hover(),
            );
            {
                let p = &ui.painter().clone();
                p.line_segment(
                    [
                        Pos2::new(hb.left(), hb.bottom()),
                        Pos2::new(hb.right(), hb.bottom()),
                    ],
                    Stroke::new(1.0_f32, theme::BORDER),
                );
                let back = Rect::from_center_size(
                    Pos2::new(hb.left() + 22.0, hb.center().y),
                    Vec2::splat(28.0),
                );
                let bresp = ui.allocate_rect(back, Sense::click());
                if bresp.hovered() {
                    p.rect_filled(back, 6.0, theme::SURFACE_HOVER);
                }
                icons::arrow_left(
                    p,
                    back.shrink(7.0),
                    if bresp.hovered() {
                        theme::FG
                    } else {
                        theme::FG_SUBTLE
                    },
                );
                if bresp.clicked() {
                    state.settings.open = false;
                }
                text(
                    p,
                    Pos2::new(back.right() + 6.0, hb.center().y),
                    Align2::LEFT_CENTER,
                    13.0,
                    theme::FG,
                    state.settings.section.title(),
                );
            }

            // ---------- body: nav + content ----------
            let body = ui.available_rect_before_wrap();
            let nav_w = 232.0;
            let nav = Rect::from_min_max(
                body.left_top() + Vec2::new(8.0, 10.0),
                Pos2::new(body.left() + nav_w, body.bottom()),
            );
            let content = Rect::from_min_max(
                Pos2::new(body.left() + nav_w + 8.0, body.top()),
                Pos2::new(body.right(), body.bottom()),
            );

            // ---- nav ----
            {
                let p = &ui.painter().clone();
                let mut y = nav.top();
                for (group, sections) in groups() {
                    text(
                        p,
                        Pos2::new(nav.left() + 12.0, y + 9.0),
                        Align2::LEFT_CENTER,
                        11.0,
                        theme::FG_SUBTLEST,
                        group,
                    );
                    y += 26.0;
                    for section in sections {
                        let r = Rect::from_min_max(
                            Pos2::new(nav.left() + 4.0, y),
                            Pos2::new(nav.right() - 4.0, y + 30.0),
                        );
                        let resp = ui.allocate_rect(r, Sense::click());
                        let selected = state.settings.section == section;
                        if selected {
                            p.rect_filled(r, Rounding::same(theme::RADIUS_ROW), theme::SURFACE);
                        } else if resp.hovered() {
                            p.rect_filled(
                                r,
                                Rounding::same(theme::RADIUS_ROW),
                                theme::SURFACE_HOVER,
                            );
                        }
                        let ir = Rect::from_center_size(
                            Pos2::new(r.left() + 18.0, r.center().y),
                            Vec2::splat(16.0),
                        );
                        section.icon()(
                            p,
                            ir,
                            if selected || resp.hovered() {
                                theme::FG
                            } else {
                                theme::FG_SUBTLE
                            },
                        );
                        text(
                            p,
                            Pos2::new(r.left() + 34.0, r.center().y),
                            Align2::LEFT_CENTER,
                            12.5,
                            if selected {
                                theme::FG
                            } else {
                                theme::FG_SUBTLE
                            },
                            section.title(),
                        );
                        if resp.clicked() {
                            state.settings.section = section;
                        }
                        y += 30.0;
                    }
                    y += 10.0;
                }
            }

            // ---- content ----
            ui.allocate_rect(content, Sense::hover());
            {
                let p = &ui.painter().clone();
                let mut y = content.top() + 18.0;
                text(
                    p,
                    Pos2::new(content.left() + 24.0, y + 10.0),
                    Align2::LEFT_CENTER,
                    18.0,
                    theme::FG,
                    state.settings.section.title(),
                );
                y += 44.0;

                match state.settings.section {
                    Section::Appearance => {
                        // THEME_MODES: system / zai-dark / zai-light
                        text(
                            p,
                            Pos2::new(content.left() + 24.0, y + 12.0),
                            Align2::LEFT_CENTER,
                            12.5,
                            theme::FG_SUBTLE,
                            "Theme",
                        );
                        y += 34.0;
                        type ThemeModeEntry<'a> = (
                            &'a str,
                            fn(&egui::Painter, Rect, egui::Color32),
                            crate::ThemeMode,
                        );
                        let modes: Vec<ThemeModeEntry<'_>> = vec![
                            ("System", icons::monitor, crate::ThemeMode::System),
                            ("ZAI Dark", icons::moon, crate::ThemeMode::Dark),
                            ("ZAI Light", icons::sun, crate::ThemeMode::Light),
                        ];
                        let mut x = content.left() + 24.0;
                        for (label, icon, mode) in modes {
                            let r = Rect::from_min_size(Pos2::new(x, y), Vec2::new(104.0, 30.0));
                            let resp = ui.allocate_rect(r, Sense::click());
                            let selected = state.theme == mode;
                            p.rect_filled(
                                r,
                                Rounding::same(8.0),
                                if selected {
                                    theme::SURFACE
                                } else {
                                    theme::SURFACE_HOVER
                                },
                            );
                            p.rect_stroke(
                                r,
                                Rounding::same(8.0),
                                Stroke::new(
                                    1.0_f32,
                                    if selected {
                                        theme::BORDER_HOVER
                                    } else {
                                        theme::BORDER
                                    },
                                ),
                            );
                            icon(
                                p,
                                Rect::from_center_size(
                                    Pos2::new(r.left() + 16.0, r.center().y),
                                    Vec2::splat(14.0),
                                ),
                                if selected {
                                    theme::FG
                                } else {
                                    theme::FG_SUBTLE
                                },
                            );
                            text(
                                p,
                                Pos2::new(r.left() + 28.0, r.center().y),
                                Align2::LEFT_CENTER,
                                12.0,
                                if selected {
                                    theme::FG
                                } else {
                                    theme::FG_SUBTLE
                                },
                                label,
                            );
                            if resp.clicked() {
                                state.theme = mode;
                            }
                            x += 104.0 + 8.0;
                        }
                    }
                    _ => {
                        // Content pane ports land section by section; the shell,
                        // nav and titles are the verbatim contract for now.
                    }
                }
            }
        });
}
