//! The app shell: sidebar, central conversation, right dock, status bar,
//! cloud settings dialog and stream wiring.

use crate::cloud::{CloudClient, CloudConfig, ChatMessage, StreamEvent};
use crate::composer::{Composer, ComposerAction};
use crate::conversation::{self, ChatItem};
use crate::panes::{self, DockState, DockTab};
use crate::sidebar::{self, SidebarState};
use crate::theme::palette;
use crate::theme;
use egui::{FontId, Margin, SidePanel, TopBottomPanel, Ui};
use std::sync::mpsc::{channel, Receiver, TryRecvError};

/// One conversation in the sidebar.
pub struct Conversation {
    pub title: String,
    pub items: Vec<ChatItem>,
    /// Cloud-side conversation id when synced.
    pub cloud_id: Option<String>,
}

impl Conversation {
    pub fn new(title: impl Into<String>) -> Self {
        Conversation { title: title.into(), items: Vec::new(), cloud_id: None }
    }

    pub fn push(&mut self, item: ChatItem) {
        if let ChatItem::User { text } = &item {
            if self.title == "New chat" {
                self.title = text.chars().take(42).collect::<String>();
            }
        }
        self.items.push(item);
    }
}

impl ChatItem {
    pub(crate) fn matches_text(&self, needle: &str) -> bool {
        match self {
            ChatItem::User { text } | ChatItem::Assistant { text } | ChatItem::System { text } | ChatItem::Error { text } => {
                text.to_lowercase().contains(needle)
            }
            ChatItem::Reasoning { text, .. } => text.to_lowercase().contains(needle),
            ChatItem::Tool { title, .. } => title.to_lowercase().contains(needle),
        }
    }
}

pub struct XenApp {
    pub workspace: String,
    pub conversations: Vec<Conversation>,
    pub active: usize,
    pub composer: Composer,
    pub sidebar_state: SidebarState,
    pub dock: DockState,

    pub cloud: Option<CloudClient>,
    pub cloud_models: Vec<String>,
    pub cloud_model: String,
    pub cloud_status: String,
    pub cloud_dialog: CloudDialog,

    /// Live stream state.
    streaming: Option<StreamState>,
    rx: Receiver<StreamEvent>,
    demo_counter: u64,
}

struct StreamState {
    buf: String,
    reason: String,
    in_reasoning: bool,
}

pub struct CloudDialog {
    pub open: bool,
    pub server_url: String,
    pub token: String,
    pub probe: Option<Result<(String, i64), String>>,
}

impl XenApp {
    pub fn new(workspace: String, cloud_config: Option<CloudConfig>) -> Self {
        let (_tx, rx) = channel();
        let mut app = XenApp {
            workspace: workspace.clone(),
            conversations: vec![Conversation::new("New chat")],
            active: 0,
            composer: Composer::default(),
            sidebar_state: SidebarState::default(),
            dock: DockState::new(&workspace),
            cloud: None,
            cloud_models: vec!["xencode-auto".into(), "fast".into(), "smart".into()],
            cloud_model: "xencode-auto".into(),
            cloud_status: "Demo mode · XenCloud not configured".into(),
            cloud_dialog: CloudDialog { open: false, server_url: String::new(), token: String::new(), probe: None },
            streaming: None,
            rx,
            demo_counter: 0,
        };
        if let Some(cfg) = cloud_config {
            app.cloud_dialog.server_url = cfg.server_url.clone();
            app.cloud_dialog.token = cfg.token.clone();
            if !cfg.model.is_empty() {
                app.cloud_model = cfg.model.clone();
            }
            let client = CloudClient::new(cfg);
            app.cloud_status = "XenCloud · configured".into();
            app.cloud = Some(client);
        }
        app
    }

    fn active_conv(&mut self) -> &mut Conversation {
        let i = self.active.min(self.conversations.len() - 1);
        &mut self.conversations[i]
    }

    pub fn seed_demo(&mut self) {
        let conv = &mut self.conversations[0];
        conv.title = "Workspace tour + gateway streaming".into();
        crate::demo::seed(conv);
        // Open the first tool row so its detail (file content) shows in screenshots.
        if let Some(ChatItem::Tool { open, .. }) = conv.items.iter_mut().find(|it| matches!(it, ChatItem::Tool { .. })) {
            *open = true;
        }
        // Give the terminal pane some scrollback.
        self.dock.terminal.seed_demo();
    }

    pub fn open_dock_tab(&mut self, tab: DockTab) {
        self.dock.open_tab(tab);
    }
}

impl eframe::App for XenApp {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        self.drain_stream();

        // Status bar (bottom).
        TopBottomPanel::bottom("status_bar").show(ctx, |ui| {
            ui.horizontal(|ui| {
                ui.add_space(6.0);
                let streaming = self.streaming.is_some();
                ui.colored_label(
                    if streaming { palette::PRIMARY } else { palette::ON_SURFACE_VARIANT },
                    "●",
                );
                ui.style_mut().override_font_id = Some(FontId::proportional(11.0));
                ui.colored_label(
                    palette::ON_SURFACE_VARIANT,
                    if streaming { "streaming" } else { "idle" },
                );
                ui.separator();
                ui.colored_label(
                    palette::ON_SURFACE_VARIANT,
                    format!("Mode: default · Model: {}", self.cloud_model),
                );
                ui.separator();
                ui.colored_label(palette::ON_SURFACE_VARIANT, format!("Workspace: {}", self.workspace));
            });
        });

        // Sidebar (left).
        SidePanel::left("sidebar")
            .resizable(true)
            .default_width(240.0)
            .width_range(200.0..=340.0)
            .show(ctx, |ui| {
                egui::Frame::default().inner_margin(Margin::same(10.0)).show(ui, |ui| {
                    let (action, delete) = sidebar::sidebar_ui(
                        ui,
                        &mut self.sidebar_state,
                        &mut self.conversations,
                        self.active,
                        &self.workspace,
                        &self.cloud_status,
                    );
                    match action {
                        -1 => {
                            self.conversations.push(Conversation::new("New chat"));
                            self.active = self.conversations.len() - 1;
                        }
                        i if i >= 0 => self.active = i as usize,
                        _ => {}
                    }
                    if let Some(d) = delete {
                        self.conversations.remove(d);
                        if self.active >= self.conversations.len() {
                            self.active = self.conversations.len().saturating_sub(1);
                        }
                        if self.conversations.is_empty() {
                            self.conversations.push(Conversation::new("New chat"));
                        }
                    }
                });
            });

        // Dock (right).
        if self.dock.open {
            SidePanel::right("dock")
                .resizable(true)
                .default_width(340.0)
                .width_range(260.0..=560.0)
                .show(ctx, |ui| {
                    egui::Frame::default().inner_margin(Margin::same(10.0)).show(ui, |ui| {
                        panes::dock_ui(ui, &mut self.dock);
                    });
                });
        }

        // Central panel.
        egui::CentralPanel::default().show(ctx, |ui| {
            self.chat_ui(ui, ctx);
        });

        self.cloud_dialog_ui(ctx);

        // Keep streaming alive: request repaints while a stream is open.
        if self.streaming.is_some() {
            ctx.request_repaint();
        }
    }
}

impl XenApp {
    fn chat_ui(&mut self, ui: &mut Ui, ctx: &egui::Context) {
        // Header.
        ui.horizontal(|ui| {
            ui.style_mut().override_font_id = Some(FontId::new(19.0, theme::families::bold()));
            ui.colored_label(palette::ON_BACKGROUND, "Chat");
            ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui| {
                for tab in DockTab::ALL {
                    let active = self.dock.open && self.dock.tab == tab;
                    if ui
                        .add(egui::Button::new(
                            egui::RichText::new(tab.glyph()).color(if active { palette::PRIMARY } else { palette::ON_SURFACE_VARIANT }),
                        ).small())
                        .on_hover_text(tab.label())
                        .clicked()
                    {
                        if active {
                            self.dock.open = false;
                        } else {
                            self.dock.open_tab(tab);
                        }
                    }
                }
                if ui.button(crate::icons::glyph::GEAR).on_hover_text("XenCloud settings").clicked() {
                    let cfg = self.cloud.as_ref().map(|c| c.config.clone());
                    if let Some(cfg) = cfg {
                        self.cloud_dialog.server_url = cfg.server_url.clone();
                        self.cloud_dialog.token = cfg.token.clone();
                    }
                    self.cloud_dialog.probe = None;
                    self.cloud_dialog.open = true;
                }
            });
        });
        ui.add_space(2.0);

        // Transcript.
        let streaming = self.streaming.is_some();
        let streaming_text = self
            .streaming
            .as_ref()
            .map(|s| if s.in_reasoning { s.reason.clone() } else { s.buf.clone() })
            .unwrap_or_default();
        let context_pct = conversation::context_usage_percent(
            &self.conversations[self.active.min(self.conversations.len() - 1)].items,
            &streaming_text,
        );

        egui::ScrollArea::vertical()
            .stick_to_bottom(true)
            .id_salt("transcript")
            .show(ui, |ui| {
                ui.add_space(6.0);
                let idx = self.active.min(self.conversations.len() - 1);
                let conv = &mut self.conversations[idx];
                let is_empty = conv.items.is_empty() && streaming_text.is_empty();
                if is_empty {
                    ui.add_space(48.0);
                    ui.vertical_centered(|ui| {
                        ui.add_space(24.0);
                        ui.colored_label(palette::PRIMARY, crate::icons::glyph::BOLT);
                        ui.style_mut().override_font_id = Some(FontId::new(16.0, theme::families::bold()));
                        ui.colored_label(palette::ON_BACKGROUND, "Ask XenCode anything about your workspace.");
                        ui.style_mut().override_font_id = Some(FontId::proportional(13.0));
                        ui.colored_label(
                            palette::ON_SURFACE_VARIANT,
                            "Replies stream in live. File edits and command runs show up as tool rows.",
                        );
                        ui.add_space(16.0);
                    });
                }
                for item in &mut conv.items {
                    conversation::item_ui(ui, item, false);
                    ui.add_space(2.0);
                }
                if !streaming_text.is_empty() {
                    let mut tmp = if self.streaming.as_ref().map(|s| s.in_reasoning).unwrap_or(false) {
                        ChatItem::Reasoning { text: streaming_text.clone(), seconds: 0, open: true }
                    } else {
                        ChatItem::Assistant { text: streaming_text.clone() }
                    };
                    conversation::item_ui(ui, &mut tmp, true);
                }
                ui.add_space(8.0);
            });

        ui.add_space(6.0);

        // Composer.
        let cloud_label = self.cloud_status.clone();
        let models = if self.cloud_models.is_empty() { vec!["xencode-auto".into()] } else { self.cloud_models.clone() };
        let mut picked_model: Option<String> = None;
        let action = self.composer.ui(
            ui,
            streaming,
            &self.cloud_model,
            &models,
            context_pct,
            &cloud_label,
            &mut |m| picked_model = Some(m),
        );
        if let Some(model) = picked_model {
            self.cloud_model = model.clone();
            if let Some(client) = &mut self.cloud {
                client.config.model = model;
                let _ = client.config.save();
            }
        }
        match action {
            ComposerAction::Send(text) => self.start_send(text, ctx),
            ComposerAction::Stop => {
                self.streaming = None;
            }
            ComposerAction::None => {}
        }
    }

    fn start_send(&mut self, text: String, _ctx: &egui::Context) {
        {
            let conv = self.active_conv();
            conv.push(ChatItem::user(text.clone()));
        }

        // Cloud path.
        if let Some(client) = &self.cloud {
            let idx = self.active.min(self.conversations.len() - 1);
            let history: Vec<ChatMessage> = self.conversations[idx]
                .items
                .iter()
                .filter_map(|it| match it {
                    ChatItem::User { text } => Some(ChatMessage { role: "user".into(), content: text.clone() }),
                    ChatItem::Assistant { text } => Some(ChatMessage { role: "assistant".into(), content: text.clone() }),
                    _ => None,
                })
                .collect();
            let (tx, rx) = channel();
            self.rx = rx;
            client.stream_chat(
                history,
                &self.cloud_model,
                self.composer.effort.api_value(),
                tx,
            );
            // Conversation sync: create on first turn, then append the user message.
            {
                let conv = &mut self.conversations[idx];
                if conv.cloud_id.is_none() {
                    conv.cloud_id = client.push_conversation(&conv.title, &self.cloud_model).ok();
                }
                if let Some(id) = conv.cloud_id.clone() {
                    let _ = client.push_message(&id, "user", &text);
                }
            }
            self.streaming = Some(StreamState {
                buf: String::new(),
                reason: String::new(),
                in_reasoning: false,
            });
            return;
        }

        // Demo path: canned streaming reply.
        self.demo_counter += 1;
        let reply = demo_reply(text.as_str(), self.demo_counter);
        let (tx, rx) = channel();
        self.rx = rx;
        self.streaming = Some(StreamState {
            buf: String::new(),
            reason: String::new(),
            in_reasoning: false,
        });
        std::thread::spawn(move || {
            for word in reply.split_inclusive(' ') {
                std::thread::sleep(std::time::Duration::from_millis(18));
                if tx.send(StreamEvent::Delta(word.to_string())).is_err() {
                    return;
                }
            }
            let _ = tx.send(StreamEvent::Done);
        });
    }

    fn drain_stream(&mut self) {
        loop {
            match self.rx.try_recv() {
                Ok(ev) => match ev {
                    StreamEvent::Delta(d) => {
                        if let Some(s) = &mut self.streaming {
                            if s.in_reasoning {
                                s.reason.push_str(&d);
                            } else {
                                s.buf.push_str(&d);
                            }
                        }
                    }
                    StreamEvent::Usage { input, output } => {
                        self.cloud_status = format!("XenCloud · {input}+{output} tokens metered");
                    }
                    StreamEvent::Done => {
                        self.finish_turn();
                    }
                    StreamEvent::Error(e) => {
                        let idx = self.active.min(self.conversations.len() - 1);
                        self.conversations[idx].push(ChatItem::Error { text: e });
                        self.streaming = None;
                    }
                },
                Err(TryRecvError::Empty) => break,
                Err(TryRecvError::Disconnected) => {
                    self.finish_turn();
                    break;
                }
            }
        }
    }

    fn finish_turn(&mut self) {
        let Some(state) = self.streaming.take() else {
            return;
        };
        let idx = self.active.min(self.conversations.len() - 1);
        if !state.reason.trim().is_empty() {
            let seconds = (state.reason.len() as f64 / 260.0).ceil().max(1.0) as u32;
            self.conversations[idx].items.push(ChatItem::Reasoning {
                text: state.reason,
                seconds,
                open: false,
            });
        }
        if !state.buf.trim().is_empty() {
            let reply = state.buf.clone();
            self.conversations[idx].push(ChatItem::assistant(reply.clone()));
            // Sync the assistant message when a cloud conversation exists.
            if let Some(client) = &self.cloud {
                if let Some(id) = self.conversations[idx].cloud_id.clone() {
                    let _ = client.push_message(&id, "assistant", &reply);
                }
            }
        }
    }

    fn cloud_dialog_ui(&mut self, ctx: &egui::Context) {
        if !self.cloud_dialog.open {
            return;
        }
        let mut close = false;
        let mut save = false;
        let mut probe = false;
        egui::Window::new("XenCloud")
            .open(&mut self.cloud_dialog.open)
            .resizable(false)
            .show(ctx, |ui| {
                ui.style_mut().override_font_id = Some(FontId::proportional(13.0));
                ui.label("Server URL");
                ui.add(
                    egui::TextEdit::singleline(&mut self.cloud_dialog.server_url)
                        .hint_text("http://127.0.0.1:8080")
                        .desired_width(340.0),
                );
                ui.label("Session token or API key");
                ui.add(
                    egui::TextEdit::singleline(&mut self.cloud_dialog.token)
                        .hint_text("xst_... or xk_...")
                        .desired_width(340.0)
                        .password(true),
                );
                ui.add_space(6.0);
                ui.horizontal(|ui| {
                    if ui.button("Probe").clicked() {
                        probe = true;
                    }
                    if ui.button("Save").clicked() {
                        save = true;
                    }
                    if ui.button("Close").clicked() {
                        close = true;
                    }
                });
                if let Some(probe_result) = &self.cloud_dialog.probe {
                    match probe_result {
                        Ok((email, credits)) => {
                            ui.colored_label(palette::PRIMARY, format!("Signed in as {email} · {credits} credits"));
                        }
                        Err(e) => {
                            ui.colored_label(palette::ERROR, format!("probe failed: {e}"));
                        }
                    }
                }
                ui.add_space(4.0);
                ui.colored_label(
                    palette::ON_SURFACE_VARIANT,
                    "Tokens are stored locally at ~/.xencode/cloud.json. Provider master keys stay on the server.",
                );
            });
        if close {
            self.cloud_dialog.open = false;
        }
        if save {
            let cfg = CloudConfig {
                server_url: self.cloud_dialog.server_url.trim().to_string(),
                token: self.cloud_dialog.token.trim().to_string(),
                model: self.cloud_model.clone(),
            };
            let _ = cfg.save();
            self.cloud = Some(CloudClient::new(cfg));
            self.cloud_status = "XenCloud · configured".into();
        }
        if probe {
            let cfg = CloudConfig {
                server_url: self.cloud_dialog.server_url.trim().to_string(),
                token: self.cloud_dialog.token.trim().to_string(),
                model: String::new(),
            };
            let client = CloudClient::new(cfg);
            let who = client.whoami();
            let ok = who.is_ok();
            self.cloud_dialog.probe = Some(who);
            if ok {
                if let Ok(models) = client.fetch_models() {
                    if !models.is_empty() {
                        self.cloud_models = models;
                    }
                }
                self.cloud_status = "XenCloud · connected".into();
            }
        }
    }
}

impl DockTab {
    pub fn glyph(self) -> &'static str {
        match self {
            DockTab::Git => crate::icons::glyph::GIT_BRANCH,
            DockTab::Terminal => crate::icons::glyph::TERMINAL,
            DockTab::Files => crate::icons::glyph::DOC,
        }
    }
}

/// Canned replies for demo mode (no provider configured).
fn demo_reply(prompt: &str, counter: u64) -> String {
    match counter {
        1 => format!(
            "Here's the plan for **{prompt}**:\n\n1. Survey the workspace structure\n2. Draft the change\n3. Run the checks\n\n```bash\ncargo check --workspace\n```\n\nConnect XenCloud from the gear menu to stream real model replies; until then this is demo mode."
        ),
        _ => "Understood. In demo mode I keep replies short; wire up XenCloud (gear icon, top right) for real completions, or ask me to explore the side panes.".to_string(),
    }
}
