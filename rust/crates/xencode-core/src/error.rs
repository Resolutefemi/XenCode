use thiserror::Error;

/// Canonical XenCode core error.
#[derive(Debug, Error)]
pub enum XeError {
    #[error("config error: {0}")]
    Config(String),

    #[error("tool error: {0}")]
    Tool(String),

    #[error("permission denied: {0}")]
    Permission(String),

    #[error("provider error: {0}")]
    Provider(String),

    #[error("protocol error ({kind}): {message}")]
    Protocol { kind: String, message: String },

    #[error("io error: {0}")]
    Io(#[from] std::io::Error),

    #[error("json error: {0}")]
    Json(#[from] serde_json::Error),

    #[error("http error: {0}")]
    Http(#[from] reqwest::Error),

    #[error("git error: {0}")]
    Git(String),

    #[error("mcp error: {0}")]
    Mcp(String),

    #[error("automation error: {0}")]
    Automation(String),

    #[error("db error: {0}")]
    Db(String),
}

pub type Result<T> = std::result::Result<T, XeError>;

/// Render an error the way tool results surface it to the model.
pub fn tool_err<E: std::fmt::Display>(e: E) -> String {
    format!("ERROR: {e}")
}
