//! Glyph icons and labels shared across the app.
//!
//! Plain unicode glyphs (no icon font dependency); the registered DejaVu
//! fallback family guarantees coverage for every symbol used here.

pub mod glyph {
    pub const CHEVRON_RIGHT: &str = "\u{25B8}"; // ▸
    pub const CHEVRON_DOWN: &str = "\u{25BE}"; // ▾
    pub const BOLT: &str = "\u{26A1}"; // ⚡
    pub const SPARK: &str = "\u{2726}"; // ✦
    pub const DOC: &str = "\u{25A4}"; // ▤ read
    pub const PENCIL: &str = "\u{270E}"; // ✎ edit
    pub const TERMINAL: &str = "\u{275F}"; // ❟ execute (prompt glyph)
    pub const MAGNIFIER: &str = "\u{2315}"; // ⌕ search
    pub const GLOBE: &str = "\u{25CE}"; // ◎ fetch
    pub const CHECK: &str = "\u{2713}"; // ✓
    pub const CROSS: &str = "\u{2717}"; // ✗
    pub const CLOCK: &str = "\u{25F7}"; // ◧ running
    pub const TARGET: &str = "\u{25CE}"; // ◎ goal
    pub const BRAIN: &str = "\u{2313}"; // ⌓ thinking
    pub const PLUG: &str = "\u{25CF}"; // ● mcp
    pub const LIST: &str = "\u{2630}"; // ☰ todo
    pub const FILE: &str = "\u{2022}";
    pub const GIT_BRANCH: &str = "\u{2442}"; // ⑂
    pub const PLUS: &str = "+";
    pub const MINUS: &str = "\u{2212}"; // −
    pub const GEAR: &str = "\u{2699}"; // ⚙
    pub const SYNC: &str = "\u{27F3}"; // ⟳
    pub const CLOUD: &str = "\u{2601}"; // ☁
}

/// Tool execution status for transcript rows.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum ToolStatus {
    Running,
    Completed,
    Failed,
}

/// Tool kinds rendered in the transcript, ZCode ToolLayout style.
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub enum ToolKind {
    Read,
    Edit,
    Write,
    Execute,
    Search,
    Fetch,
    Todo,
    Goal,
    Skill,
    Mcp,
}

impl ToolKind {
    pub fn glyph(self) -> &'static str {
        match self {
            ToolKind::Read => glyph::DOC,
            ToolKind::Edit | ToolKind::Write => glyph::PENCIL,
            ToolKind::Execute => glyph::TERMINAL,
            ToolKind::Search => glyph::MAGNIFIER,
            ToolKind::Fetch => glyph::GLOBE,
            ToolKind::Todo => glyph::LIST,
            ToolKind::Goal => glyph::TARGET,
            ToolKind::Skill => glyph::SPARK,
            ToolKind::Mcp => glyph::PLUG,
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            ToolKind::Read => "Read",
            ToolKind::Edit => "Edit",
            ToolKind::Write => "Write",
            ToolKind::Execute => "Execute",
            ToolKind::Search => "Search",
            ToolKind::Fetch => "Fetch",
            ToolKind::Todo => "Todo",
            ToolKind::Goal => "Goal",
            ToolKind::Skill => "Skill",
            ToolKind::Mcp => "MCP",
        }
    }

    /// Map an agent tool name onto a kind for display.
    pub fn from_tool_name(name: &str) -> (ToolKind, String) {
        let lower = name.to_ascii_lowercase();
        let kind = if lower.contains("read") || lower.contains("glob") {
            ToolKind::Read
        } else if lower.contains("edit") {
            ToolKind::Edit
        } else if lower.contains("write") {
            ToolKind::Write
        } else if lower.contains("bash")
            || lower.contains("exec")
            || lower.contains("run")
            || lower.contains("command")
        {
            ToolKind::Execute
        } else if lower.contains("grep") || lower.contains("search") || lower.contains("find") {
            ToolKind::Search
        } else if lower.contains("fetch") || lower.contains("web") || lower.contains("http") {
            ToolKind::Fetch
        } else if lower.contains("todo") {
            ToolKind::Todo
        } else if lower.contains("goal") || lower.contains("dag") {
            ToolKind::Goal
        } else if lower.contains("skill") {
            ToolKind::Skill
        } else if lower.contains("mcp") {
            ToolKind::Mcp
        } else {
            ToolKind::Execute
        };
        (kind, name.to_string())
    }
}
