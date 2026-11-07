//! Demo seeding: a representative transcript used by `--demo` (and the
//! screenshots) so the UI can be shown without a provider key.

use crate::conversation::ChatItem;
use crate::icons::{ToolKind, ToolStatus};

/// Append a demo exchange to a conversation.
pub fn seed(conversation: &mut crate::Conversation) {
    conversation.items.push(ChatItem::user("Walk me through the rust workspace layout and how the gateway streams"));

    conversation.items.push(ChatItem::reasoning(
        "The user wants both a structural overview of rust/ and the streaming path. I should list the crates, \
then trace a request: desktop composer -> cloud.rs SSE -> gateway.rs unfold over provider chunks -> metering. \
A table of crates plus a small code snippet of the SSE shape will land well.",
        4,
    ));

    conversation.items.push(ChatItem::tool(
        ToolKind::Read,
        "read",
        "rust/crates/xencode-desktop/src/app.rs",
        ToolStatus::Completed,
        "   1  pub struct XenApp {\n   2      pub conversations: Vec<Conversation>,\n   3      pub active: usize,\n   4      pub dock: DockState,\n   5      pub cloud: CloudClient,\n   6  }",
    ));

    conversation.items.push(ChatItem::tool(
        ToolKind::Execute,
        "run_command",
        "cargo tree -p xencode-cloud --depth 1",
        ToolStatus::Completed,
        "xencode-cloud v0.1.0\n├── axum v0.8.9\n├── sqlx v0.9.0\n├── reqwest v0.13.5\n├── serde v1.0\n└── tokio v1.x",
    ));

    conversation.items.push(ChatItem::assistant(
        "## Workspace layout\n\nThe Rust line lives under `rust/` as a two-crate Cargo workspace:\n\n| Crate | Role |\n|---|---|\n| `xencode-desktop` | The egui app: chat, markdown, side panes, the XenCloud client |\n| `xencode-cloud` | The Axum backend: auth, gateway, metering, sync, routing |\n\n**How streaming flows** when XenCloud is configured:\n\n1. The composer sends the transcript to `POST /v1/chat/completions` with `stream: true`\n2. The gateway injects the server-side system prompt, resolves the model alias, checks credits\n3. Deltas stream back as SSE chunks, one `data:` line per token batch:\n\n```json\n{\"choices\":[{\"delta\":{\"content\":\"tok\"}}]}\n```\n\n4. Usage is metered per user, then the finished turn syncs into Postgres\n\n> Tip: the thinking chip cycles Off, Low, Medium, High, exactly like ZCode's effort control.\n\nOpen the **Git** pane to stage this change, or the **Terminal** pane to run `cargo check` yourself.",
    ));

    conversation.items.push(ChatItem::tool(
        ToolKind::Edit,
        "edit",
        "rust/crates/xencode-desktop/src/theme.rs",
        ToolStatus::Completed,
        "@@ -10,7 +10,7 @@\n-    pub const PRIMARY: Color32 = Color32::from_rgb(0x6E, 0xE7, 0xA0);\n+    pub const PRIMARY: Color32 = Color32::from_rgb(0x6E, 0xE7, 0xA0); // lightning green\n     pub const ON_PRIMARY: Color32 = Color32::from_rgb(0x06, 0x13, 0x0C);",
    ));

    conversation.items.push(ChatItem::user("Nice. Ship it to XenAI too?"));

    conversation.items.push(ChatItem::assistant(
        "The mobile app already lives in its own repo: **Resolutefemi/XenAI**. XenCode keeps the desktop app, \
the CLI and the cloud backend, so mobile and PC can release on their own cadence. \
Anything else, say the word.",
    ));
}
