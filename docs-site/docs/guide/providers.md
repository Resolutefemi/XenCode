# AI Vendors & Models

XenCode does not provide models itself — connect a model service first. The entry point is "Settings → AI Vendors".

## Adding a vendor

Tap + at the top-right to open the add panel from the bottom:

- The first entry is **Custom Vendor** (configured manually from scratch).
- Below are built-in official presets; picking one auto-fills the name, upstream format and Base URL — just add the API Key on the edit page and it works. Every field can still be edited manually.

The preset list changes between versions; what is shown on screen prevails.

For custom setup, fill in these four fields:

- **Name**: an easily recognizable alias, e.g. "My relay". The list auto-matches a brand icon by name.
- **Upstream format**: pick one of `OpenAI` / `Anthropic` / `Gemini`; it decides the request protocol. Third-party relays usually pick `OpenAI`.
- **API Key**: your key, like `sk-xxx`. Extra spaces or newlines pasted along are cleaned automatically on save.
- **Base URL**: the API root. Leave it empty to auto-fill the official address for the upstream format; with a third-party relay, enter the relay service's address — with or without `/v1` both work.

Once configured, switch to the "Models" tab to fetch models, then head back to the home page and start chatting.

## Managing the vendor list

Each row shows the name, upstream format, model count and enabled status.

- Tap a row to open its edit page.
- **Swipe a row left** to reveal the red delete button.
- **Long-press and drag the name or upstream format area** to reorder; the order survives restart.

## Multi-key mode

::: tip Version note
Multi-key mode is available since 1.11.0.
:::

If you hold several keys for the same service, turn on **Multi-Key Mode** under "Options" on the edit page: when a key becomes unavailable it switches to the next one and **resends this request**. Once on, the single-key input hides and keys are managed on the "Multi-Key Management" page (the key you had entered becomes the first entry).

The management page has three sections:

**Key list**: every key is a collapsible card — collapsed it shows the index and a masked preview; tap to expand, edit that key and set its **scheduling weight** (default `1`, max `1,000,000`; `≤ 0` keeps the key but excludes it from scheduling). Swipe left to delete, long-press the card header and drag to reorder (survives restart), and the eye icon toggles plaintext/masked. Weight only affects the **round-robin** strategy — the higher the value, the more new sessions land on that key (smooth weighted distribution, e.g. `1:3` is roughly a one-to-three split).

**Pickup strategy**:

- `Sequential`: always use the front-most key; switch to the next only after failures reach the threshold.
- `Round robin`: new sessions start from keys in turn, weighted by each key's weight, to spread quota consumption across keys.

To preserve the server-side prompt cache, **a single session keeps the same key by default**; it switches only when the current key becomes unavailable.

**Failover**:

- `Auto-switch status codes`: on these HTTP status codes it switches immediately and resends; default `401,402,403,429`. Leave empty for the default, or add/remove as needed (comma-separated).
- `Cooldown`: how long a switched-away key waits before rejoining the candidates; default 5 minutes, and it can also be set to no cooldown.

A request tries the keys in the list one by one and only errors out when all have failed (message: "N keys all failed"). A streaming reply that has already started outputting is not resent (to avoid duplicated output); only the key used by later requests switches, and when a switch triggers, the chat area shows "Switched to key N/M".

Auth failure, insufficient permission, rate limiting, or exhausted balance/quota count as "key unavailable" and trigger a switch; server 5xx, timeouts and unreachable networks cannot be fixed by switching keys, so they still go through the regular network retries. If every key is cooling down, it errors out right away and asks you to retry later.

After an app restart it returns to the first key, and cooldown records are cleared.

## Options

The "Options" group on the edit page also has:

- **Full URL**: when on, the Base URL is the complete request address and no default path is appended.
- **New protocol endpoints**: with upstream format OpenAI you can enable "Response API (new)"; with Gemini, "Interactions API (new)". Off by default — turn on only after confirming the service supports the new protocol.

### Custom request headers

Appends headers to all requests of this vendor, fully overriding same-named defaults (e.g. setting `User-Agent` replaces the default UA). Only a few relay gateways need this. Placeholders available in values:

::: v-pre
| Placeholder | Description |
| :--- | :--- |
| `{{SESSION_ID}}` | Current session id |
| `{{API_KEY}}` | The key actually used this time |
:::

### Custom dashboard scripts

Show a balance or usage card above the input box for this vendor. Scripts live under `~/.xencode/scripts/`, support Python, Bash and Node, and can be tested from the edit page via "Run test".

Script arguments are injected as `AICODE_KEY_<KEY>` environment variables (KEY upper-cased, keeping only letters, digits and underscores). Placeholders available in values:

::: v-pre
| Placeholder | Description |
| :--- | :--- |
| `{{PROVIDER_API_KEY}}` | The API key currently in effect |
| `{{PROVIDER_ID}}` | Vendor record ID |
| `{{PROVIDER_NAME}}` | Vendor name |
| `{{PROVIDER_TYPE}}` | Upstream format (OPENAI / ANTHROPIC / GEMINI) |
| `{{BASE_URL}}` | Vendor Base URL |
| `{{DEFAULT_MODEL}}` | Default model |
| `{{SELECTED_MODEL}}` | Currently selected model |
| `{{MODEL}}` | The model actually in effect (selected first, then default) |
:::

For the environment variables readable by scripts (tokens, session state, workspace, vendors, etc.) and the card JSON structure, see [Custom dashboard cards](/advanced/dashboard-cards).

## Model management

Switch to the "Models" tab of the edit page.

**Fetch models**: tap "Fetch models" to sync the available list from the server; results are grouped by brand and searchable. Each model carries `Image` (image input supported) and `Tools` (tool calls supported) tags, with `↑` followed by the context length and `↓` by the max output. "Add all" at the top-right of the dialog batch-adds the new models to the current vendor in one tap.

A successful fetch automatically reconciles the local list: models the remote no longer offers are removed (a failed fetch or empty list leaves the local list untouched), and manually added models missing from the remote results are removed too. To opt out, turn off "Auto-remove stale models after fetching" in [Preferences](/guide/general).

**Batch operations & multi-select**: when the model list is not empty, a multi-select button appears at the right of the edit page top bar — tap it to enter batch selection mode:
- **Select all / deselect all**: check every model or clear the selection in one tap.
- **Batch test**: run connectivity tests on the selected models one by one.
- **Batch delete**: delete in bulk after a second confirmation.
- Tap the back arrow at the top-left or press Back to exit.

**Manual add**: relay model lists are sometimes incomplete — tap + and type it in. In the edit dialog you can customize the model:

- **Total context window**: combined input and output capacity; affects when automatic context compression triggers.
- **Input window**: the maximum input length the model accepts; set independently of the total context window.
- **Output window**: the maximum length of a single reply.
- **Pricing**: input / output / cache-read / cache-write tiers, in USD per million tokens; used for cost statistics.
- **Capability toggles**: image input, image output, tool calls, reasoning.

Window values must be valid positive integers, or be left empty to use the auto-detected value; entering 0, negatives, decimals or out-of-range integers shows a warning and blocks saving. When editing an existing model, changing only the price leaves the existing total-context, input and output windows untouched.

Manually entered values win; empty ones fall back to auto-detection, and custom settings always attach to the original model name under the current vendor. Auto-detection first matches the full name, then tries ignoring case; if still not found it strips common suffixes like `-thinking`, `-preview`, `-high` and tries dropping the vendor brand prefix. Only after all of those fail does it cut at the last `-` and everything after it for one more match — it never truncates the name repeatedly.

**Test**: every model row has a "Test" button that sends one real request to verify connectivity, with the result shown below the row.

**Reorder**: long-press and drag the model name area to reorder; it takes effect immediately on save (both the model picker and the default model list follow this order) and survives restart.

**Delete**: swipe a model row left to delete. If the deleted model is in use (the currently selected model, the new-session default, a dedicated model for image recognition / generation / compression / title summarization, or a model bound to a historical session), the related selections fall back to the default model automatically.

## Thinking effort

The thinking-effort button on the chat input box sets the model's reasoning tier. When the model's supported tiers can be detected, only those are listed; otherwise (no tier info on the model, or the relay renamed it) all tiers are listed — `Off` / `Lowest` / `Low` / `Medium` / `High` / `Highest` / `Max` — and you decide. The choice is remembered and carried over the next time a new session uses that model.

