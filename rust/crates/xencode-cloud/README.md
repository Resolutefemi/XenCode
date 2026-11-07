# XenCloud (xencode-cloud)

The XenCode backend: an Axum service that sits between the desktop clients
and the model providers.

| Responsibility | Implementation |
|---|---|
| **Auth & user accounts** | Magic-link login, named API keys, revocable session tokens (opaque `xst_...` / `xk_...`, only SHA-256 hashes stored) |
| **LLM gateway & key protection** | Master `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` / `OPENROUTER_API_KEY` live in server env vars; clients only ever hold session tokens or API keys |
| **Streaming proxy** | OpenAI-compatible `POST /v1/chat/completions` with SSE streaming (`stream: true`) or JSON; native Anthropic message conversion upstream |
| **Token metering & billing** | Per-user usage rows, sliding-window rate limits, credit balances (1 credit per 1k tokens by default), Stripe webhook with signature verification |
| **Conversation & session sync** | Conversations, messages and checkpoint snapshots in Postgres, resumable across machines |
| **Prompt & agent routing** | Server-side system prompt injection, `xencode-auto` / `fast` / `smart` aliases with task classification; update prompts without shipping a desktop build |

## Quick start (zero dependencies)

```bash
cargo run -p xencode-cloud
```

No environment variables needed for a dev run: it starts with the in-memory
store and the mock LLM provider, so the whole pipeline (login, streaming,
metering, billing) can be exercised locally:

```bash
# 1. Login (dev mode returns the magic-link token inline)
curl -s localhost:8080/v1/auth/magic-link -d '{"email":"dev@xencode.app"}' \
  -H 'content-type: application/json'
# -> {"dev_token":"xml_...","expires_in":900}

curl -s localhost:8080/v1/auth/magic-link/verify -d '{"token":"xml_..."}' \
  -H 'content-type: application/json'
# -> {"session_token":"xst_...","user":{...}}

# 2. Stream a completion (mock provider without upstream keys)
curl -N localhost:8080/v1/chat/completions \
  -H "authorization: Bearer xst_..." -H 'content-type: application/json' \
  -d '{"model":"xencode-auto","stream":true,"messages":[{"role":"user","content":"hi"}]}'

# 3. See usage and credits
curl -s localhost:8080/v1/me -H "authorization: Bearer xst_..."
curl -s localhost:8080/v1/usage -H "authorization: Bearer xst_..."
```

## Production

```bash
export XENCODE_DB=postgres
export DATABASE_URL=postgres://user:pass@localhost:5432/xencloud
export ANTHROPIC_API_KEY=sk-ant-...      # any subset of the three
export OPENAI_API_KEY=sk-...
export OPENROUTER_API_KEY=sk-or-...
export XENCODE_STRIPE_WEBHOOK_SECRET=whsec_...
export XENCODE_ADMIN_TOKEN=change-me     # enables POST /v1/admin/prompts
export XENCODE_BIND=0.0.0.0:8080
cargo run --release -p xencode-cloud
```

Migrations in `migrations/` are applied at startup. With `DATABASE_URL` set
but Postgres unreachable, the server logs the error and falls back to the
in-memory store instead of refusing to boot.

`docker-compose up` starts Postgres + the API (see `docker-compose.yml`).

## Endpoints

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/healthz` | none | Liveness + db + mock indicator |
| POST | `/v1/auth/magic-link` | none | Request a login token (returned inline when `XENCODE_DEV=1`) |
| POST | `/v1/auth/magic-link/verify` | none | Exchange it for a session token |
| POST | `/v1/auth/logout` | session | Revoke the calling session |
| POST | `/v1/auth/api-keys` | session | Mint an API key (`xk_...`, shown once) |
| GET | `/v1/auth/api-keys` | session | List key metadata |
| DELETE | `/v1/auth/api-keys/{id}` | session | Revoke a key |
| GET | `/v1/me` | session/key | User + usage + credit balance |
| GET | `/v1/models` | session/key | Models and aliases the gateway exposes |
| GET | `/v1/routing` | none | The routing table |
| POST | `/v1/chat/completions` | session/key | Gateway (SSE when `stream: true`) |
| GET/POST | `/v1/conversations` | session/key | List / create conversations |
| GET/DELETE | `/v1/conversations/{id}` | session/key | Fetch / delete one |
| GET/POST | `/v1/conversations/{id}/messages` | session/key | Sync messages |
| GET/POST | `/v1/conversations/{id}/checkpoints` | session/key | Checkpoint snapshots |
| GET | `/v1/usage` | session/key | Per-model usage rows |
| POST | `/v1/billing/stripe-webhook` | Stripe signature | Credits + suspension events |
| POST | `/v1/admin/prompts` | X-Admin-Token | Upsert server-side prompts (versioned) |

## Environment variables

| Variable | Default | Meaning |
|---|---|---|
| `XENCODE_BIND` | `0.0.0.0:8080` | Listen address |
| `XENCODE_DB` | `memory` | `memory` or `postgres` |
| `DATABASE_URL` | - | Postgres DSN (auto-selects postgres) |
| `XENCODE_DEV` | off | Return magic-link tokens inline |
| `ANTHROPIC_API_KEY` / `OPENAI_API_KEY` / `OPENROUTER_API_KEY` | - | Master provider keys (server-side only) |
| `ANTHROPIC_BASE_URL` / `OPENAI_BASE_URL` / `OPENROUTER_BASE_URL` | provider defaults | Upstream overrides |
| `XENCODE_MOCK_LLM` | auto | Force the mock provider (auto when no keys) |
| `XENCODE_RATE_LIMIT_PER_MIN` | `60` | Requests per user per minute |
| `XENCODE_CREDITS_PER_1K_TOKENS` | `1` | Credit charge per 1k tokens |
| `XENCODE_DEFAULT_CREDITS` | `50000` | Signup bonus |
| `XENCODE_STRIPE_WEBHOOK_SECRET` | - | Stripe webhook signing secret |
| `XENCODE_ADMIN_TOKEN` | - | Enables the admin prompt endpoint |

## Routing

- `xencode-auto` classifies the transcript (length, architecture keywords vs
  quick-edit keywords) and picks fast or smart.
- `fast` maps to the smallest configured model (Haiku / gpt-5-mini class).
- `smart` maps to the flagship (Sonnet / gpt-5 class).
- Explicit ids like `anthropic/claude-sonnet` pin a provider.

Server-side system prompt: `desktop-default` (built in), overridable via
`POST /v1/admin/prompts`; every update bumps the version.
