//! Runtime configuration from environment variables.

use std::env;

#[derive(Clone, Debug)]
pub struct Config {
    pub bind: String,
    /// "memory" (zero-dependency dev default) or "postgres".
    pub db: String,
    pub database_url: Option<String>,
    /// Dev conveniences: magic-link tokens are returned inline in the response.
    pub dev: bool,
    pub anthropic_key: Option<String>,
    pub anthropic_base: String,
    pub openai_key: Option<String>,
    pub openai_base: String,
    pub openrouter_key: Option<String>,
    pub openrouter_base: String,
    pub stripe_webhook_secret: Option<String>,
    pub admin_token: Option<String>,
    pub rate_limit_per_min: u32,
    pub credits_per_1k_tokens: i64,
    pub signup_credits: i64,
    /// Force the mock provider even when keys are configured.
    pub mock_llm: bool,
}

impl Config {
    pub fn from_env() -> Self {
        let anthropic_key = env::var("ANTHROPIC_API_KEY").ok().filter(|s| !s.is_empty());
        let openai_key = env::var("OPENAI_API_KEY").ok().filter(|s| !s.is_empty());
        let openrouter_key = env::var("OPENROUTER_API_KEY")
            .ok()
            .filter(|s| !s.is_empty());
        Config {
            bind: env::var("XENCODE_BIND").unwrap_or_else(|_| "0.0.0.0:8080".into()),
            db: env::var("XENCODE_DB").unwrap_or_else(|_| "memory".into()),
            database_url: env::var("DATABASE_URL").ok().filter(|s| !s.is_empty()),
            dev: env::var("XENCODE_DEV")
                .map(|v| v == "1" || v.eq_ignore_ascii_case("true"))
                .unwrap_or(false),
            anthropic_key: anthropic_key.clone(),
            anthropic_base: env::var("ANTHROPIC_BASE_URL")
                .unwrap_or_else(|_| "https://api.anthropic.com".into()),
            openai_key: openai_key.clone(),
            openai_base: env::var("OPENAI_BASE_URL")
                .unwrap_or_else(|_| "https://api.openai.com".into()),
            openrouter_key: openrouter_key.clone(),
            openrouter_base: env::var("OPENROUTER_BASE_URL")
                .unwrap_or_else(|_| "https://openrouter.ai/api".into()),
            stripe_webhook_secret: env::var("XENCODE_STRIPE_WEBHOOK_SECRET")
                .ok()
                .filter(|s| !s.is_empty()),
            admin_token: env::var("XENCODE_ADMIN_TOKEN")
                .ok()
                .filter(|s| !s.is_empty()),
            rate_limit_per_min: env::var("XENCODE_RATE_LIMIT_PER_MIN")
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(60),
            credits_per_1k_tokens: env::var("XENCODE_CREDITS_PER_1K_TOKENS")
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(1),
            signup_credits: env::var("XENCODE_DEFAULT_CREDITS")
                .ok()
                .and_then(|v| v.parse().ok())
                .unwrap_or(50_000),
            mock_llm: env::var("XENCODE_MOCK_LLM")
                .map(|v| v == "1" || v.eq_ignore_ascii_case("true"))
                .unwrap_or(false),
        }
    }

    /// The mock provider kicks in when no upstream keys are configured
    /// (dev/sandbox) or when explicitly forced.
    pub fn use_mock_llm(&self) -> bool {
        self.mock_llm
            || (self.anthropic_key.is_none()
                && self.openai_key.is_none()
                && self.openrouter_key.is_none())
    }

    /// Models exposed by the gateway, based on configured providers.
    pub fn available_models(&self) -> Vec<ModelInfo> {
        let mut models = vec![
            ModelInfo {
                id: "xencode-auto".into(),
                provider: "routing".into(),
                note: "Classifies the task and routes to the best model".into(),
            },
            ModelInfo {
                id: "fast".into(),
                provider: "routing".into(),
                note: "Smallest configured model".into(),
            },
            ModelInfo {
                id: "smart".into(),
                provider: "routing".into(),
                note: "Flagship model".into(),
            },
        ];
        if self.anthropic_key.is_some() {
            models.push(ModelInfo {
                id: "anthropic/claude-sonnet".into(),
                provider: "anthropic".into(),
                note: "Native Anthropic Messages API".into(),
            });
            models.push(ModelInfo {
                id: "anthropic/claude-haiku".into(),
                provider: "anthropic".into(),
                note: "Native Anthropic Messages API".into(),
            });
        }
        if self.openai_key.is_some() {
            models.push(ModelInfo {
                id: "openai/gpt".into(),
                provider: "openai".into(),
                note: "OpenAI chat completions".into(),
            });
        }
        if self.openrouter_key.is_some() {
            models.push(ModelInfo {
                id: "openrouter/auto".into(),
                provider: "openrouter".into(),
                note: "OpenRouter".into(),
            });
        }
        if self.use_mock_llm() {
            models.push(ModelInfo {
                id: "mock/local".into(),
                provider: "mock".into(),
                note: "Canned streaming replies (no upstream keys configured)".into(),
            });
        }
        models
    }
}

#[derive(Clone, Debug, serde::Serialize)]
pub struct ModelInfo {
    pub id: String,
    pub provider: String,
    pub note: String,
}
