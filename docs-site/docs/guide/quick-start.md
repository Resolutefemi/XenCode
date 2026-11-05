# Quick Start

From installation to your first AI conversation. To watch it in action first, see the [video tutorials](/guide/video-tutorials).

## System requirements

- Android 8.0 (API 26) or higher
- arm64-v8a or x86_64 device

## Download & install

Download from [GitHub Releases](https://github.com/Resolutefemi/XenCode/releases/latest) — pick one of the three packages for your device:

| Package | Best for |
| --- | --- |
| `armsolo` | Real devices (arm64), smallest size |
| `x86solo` | Emulators, Chromebooks (x86_64) |
| `universal` | Universal package containing both architectures, larger size |

::: warning Pick the right architecture
A mismatched architecture breaks the built-in container. When in doubt, use `universal`.
:::

The recommended way is to tap "Download APK" on the [docs site homepage](/) — it fetches the latest stable `universal` package through an accelerated mirror (automatically falling back to the original GitHub link when the mirror is unavailable).

## 1. Configure an AI vendor

::: tip First launch has an onboarding guide
On first launch the onboarding guide starts automatically (new in 1.12.0), walking you through the steps below in order; you can tap "Skip" at any time, or relaunch it later via "Settings → Re-run onboarding".
:::

XenCode does not provide models itself — connect a model service first.

1. Tap the menu button at the top-left of the home page to open the sidebar, then tap "Settings" at the bottom.
2. Go to "AI Config → AI Vendors", tap + at the top-right to create one, and fill in four fields on the "Config" tab:
   - **Name**: an alias, e.g. "OpenAI Official".
   - **Upstream format**: pick one of `OpenAI` / `Anthropic` / `Gemini`. Third-party relays usually pick `OpenAI`.
   - **API Key**: your key, like `sk-xxx`.
   - **Base URL**: the API root — **leave it empty to use the official address for the upstream format**; with a third-party relay, enter the relay service's address.
3. Switch to the "Models" tab at the bottom to add models:
   - Tap "Fetch models" to sync the available list from the server, then add them one by one with the "+" on each row, or batch-add with "Add all" at the top-right.
   - Tap + at the top-right to type a model name manually (e.g. `gpt-4o`). Use this when a relay's model list does not fetch completely.
   - Optional: tap a model row to edit its metadata (context windows, pricing, and capability toggles like image input and tool calls).

Everything saves automatically when you leave the edit page — there is no separate save button.

## 2. Select a model

Back on the chat home, tap the model button in the toolbar below the input box and pick the model you just added in the dialog. The search box at the top filters; models are grouped by vendor, and tapping a group title folds or unfolds it.

## 3. Prepare the execution environment

The built-in local container is used by default with zero configuration. On first use, open the terminal from the home page and complete the one-time initialization when prompted (extracting the system, installing base tools) — after that the AI can run commands normally.

To use a remote server as the execution backend, see [Remote SSH mode](/guide/remote-ssh); for detailed container and image settings see [Container & images](/guide/container).

## 4. Start a conversation

Type in the input box and hit send. The mode button on the far left of the toolbar below the input box switches between BUILD, PLAN and AUTO — see [Three modes](/guide/modes) for the differences.

## Useful tips

- The model selected in an empty conversation on the home page is remembered as the default for new conversations and reused automatically.
- Image recognition, image generation and title summarization can each have a dedicated model; see [Default & dedicated models](/guide/default-models).
- The AI snapshots code automatically before modifying it, with one-tap safe rollback; see [Checkpoints & undo](/guide/checkpoint).
- Before long-running tasks like compilation, enable background keep-alive under [System permissions](/guide/app-permissions) to keep the system from killing the process when you switch away.
