# Built-in Browser

The app includes a WebView browser that supports AI-driven web automation. The AI can operate the browser in the background, and screenshots are taken in the background as well (no need to open the panel first).

## Opening the Browser

Tap "Built-in Browser" on the card at the bottom of the sidebar:

- **Large screens (tablets)**: the browser opens side by side with the chat in the right pane; drag the divider to adjust the width.
- **Narrow screens (phones)**: the browser opens full screen; press the back key to return to the chat.

## Manual Browsing

Type a URL in the address bar and press Enter to navigate; if what you type doesn't look like a URL (for example, it contains spaces or Chinese characters), it is automatically handed to the built-in search engine, Bing. To the right of the address bar are a developer tools toggle and a refresh button; the bottom bar below offers back, forward, new tab, dark mode toggle, and multi-tab management. While a page is loading, a linear progress bar appears below the address bar.

## Local File Preview

The address bar supports local file addresses, so HTML files on the device can be opened directly:

- Real device path: `file:///storage/emulated/0/Download/index.html`
- Container path (the path as the AI sees it inside the container, automatically mapped to the real file): `~/workspace/index.html`, `/etc/xxx.html`

The AI can also open local pages via the `browser` tool's `navigate` and analyze them with screenshots.

**Note**: in remote workspace mode, workspace files live on the remote side, so only the page itself can be loaded; relative resources referenced by the HTML (CSS/JS/images) will fail. Local workspaces are unaffected.

## Developer Tools

Tap the `</>` (code icon) to the right of the address bar to open or collapse the mobile developer tools (based on Eruda) at any time:
- **Full-featured console**: includes Console (view logs and execute JavaScript), Elements (inspect and live-edit the DOM tree and CSS styles), Network (inspect network requests and responses), Resources (view LocalStorage, cookies, and other data), Sources, and more.
- **Instantly available**: once enabled, a floating gear icon appears in the bottom-right corner of the page; tap it to expand the full console panel. Tap the developer tools button in the address bar again to close it completely and remove the floating ball.
- **Desktop debugging link**: enabling it also turns on Chromium's `WebContentsDebugging`, so you can connect the phone to a computer over USB and do desktop-grade remote inspection in Chrome via `chrome://inspect`.

## Dark Mode

The browser has a built-in dark mode that follows the app appearance by default: pages darken automatically when the app is dark and revert when the app is light. The moon/sun icon in the bottom bar toggles it manually; after a manual choice it stops following the app appearance, and it automatically resumes following once you switch it back to match the app appearance.

Dark mode uses a dual-track rendering architecture:
- **Native dark first**: the page is told `prefers-color-scheme: dark` and `color-scheme: dark`, so modern sites (GitHub, Google, etc.) automatically enable their officially fine-tuned native dark themes—text stays clear and dark areas are not washed out.
- **Dimming light-background sites**: for light-background sites without dark styles, a semi-transparent dark overlay is laid over the top of the page to dim it overall (original colors are kept, only brightness is reduced—no inverting), which looks gentle. The overlay applies to page rendering, so AI screenshots also show the dimmed palette.
- **Anti-flash**: a dark viewport background is applied immediately at page startup and the dark preference is injected ahead of time, avoiding the white flash when a page refreshes.

## AI Automation

The AI can control the browser through the `browser` tool to perform these actions:

| Action | Description |
| --- | --- |
| `navigate` | Navigate to the specified URL (supports `http(s)`; local files support `file://` or container paths; input that doesn't look like a URL is searched by keyword with the built-in search engine, Bing), waiting for the page to finish loading |
| `evaluate` | Execute arbitrary JavaScript (supports Promise/async, returns native JSON) |
| `click` | Click an element (full event chain, compatible with React/Vue) |
| `fill` | Fill in form fields (native setter + React valueTracker hack) |
| `select` | Select from a native `<select>` dropdown (by value or visible text) |
| `hover` | Hover over an element (dispatches mouseenter/over/move; can open dropdown menus) |
| `press` | Press a key (Enter/Escape/Tab/arrow keys/Backspace/Delete/space/plain characters) |
| `getText` | Extract page text (a selector can be specified); script/style already filtered out |
| `getHtml` | Extract page HTML (a selector can be specified) |
| `getBackbone` | Extract the accessibility tree (role/name/ref; `maxDepth` can be specified); refs can be used directly in subsequent actions |
| `screenshot` | Capture the current page and return the image for a vision model to analyze (supports background offscreen screenshots) |
| `console` | Get page console logs (filterable by level; can be cleared) |
| `wait` | Wait for a condition (`text=` / `text*=` / `selector=` / `domStable`; `timeout` can be set) |
| `scroll` | Scroll the page (to a given element or to the bottom) |
| `dialog` | Handle a pending `confirm`/`prompt` dialog (accept or dismiss) |
| `back` / `forward` / `reload` | Browser navigation controls; back/forward wait for navigation to complete |

When a page pops up a `confirm`/`prompt`, the page is suspended: a `pendingDialog` field appears in the tool response, and the `dialog` action accepts or dismisses it (auto-dismissed after 30 seconds if unhandled).

## Selector Format

Selectors for `click`, `fill`, `hover`, `getText`, `getHtml`, `scroll`, and `wait` support:

- `ref=e22`: an element reference returned by `getBackbone`, pinpointing the element in the snapshot
- CSS: `#id`, `.class`, `a[href=...]`
- `text=Login`: exact match on element text
- `text*=Login`: contains match
- `role=button[name="Login"]`: match by role and name
- `xpath=//a[@href]`: an XPath expression

`getBackbone` returns the accessibility tree: each node looks like `{role,name,ref,url,value,children}`, where `role` is the accessible role derived from the tag/`role` attribute (`link`/`button`/`textbox`/`heading`/`navigation`…), `name` is the accessible name (`aria-label`/`aria-labelledby`/`alt`/`placeholder`/an associated `label`/content text), and `ref` is assigned only to interactive elements. `script`/`style` and invisible elements are already filtered out; nodes beyond `maxDepth` are marked `truncated:true`.

## Differences from websearch / webfetch

- **websearch**: queries a search engine and returns a summary of the results.
- **webfetch**: fetches a page's HTTP content (plain text or HTML); no JS rendering.
- **browser**: full WebView rendering with support for JS-driven pages, interactions, and screenshot analysis.

Use the `browser` tool when page content depends on JavaScript rendering, is only accessible after logging in, or needs interactions such as clicking or filling in forms.
