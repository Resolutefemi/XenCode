# Network Request

In the card at the bottom of the sidebar, between Browser and Settings, there is a "Network Request" entry for manually sending HTTP requests and viewing responses.

## Opening the page

- **Phone**: tapping the entry opens it full-screen; press Back to return to the chat.
- **Tablet**: likewise a standalone page from the sidebar — back at the left of the top bar; the three-dot menu on the right can clear the current request, import / export cURL, or open history.

## Sending a request

The bottom of the page is a floating chip navigation for swiping between Request and Response. The Request page itself scrolls as a whole; when the keyboard pops up, the edit area slides behind the floating bottom bar instead of being squeezed.

The Request page, top to bottom:

1. Enter the URL. If `http://` or `https://` is missing, `https://` is prepended automatically on send.
2. Pick the method horizontally: GET, POST, PUT, PATCH, DELETE, HEAD, OPTIONS.
3. Tap the tall, slim Send button to send. While in flight the button shows a progress ring.
4. Below Send are five tabs: Query, Headers, Body, Authorization, Settings.

Query, Headers, Body and Authorization can each switch between three views — JSON, Tree, Tabled Input:

- **JSON**: the same code editor as the file editor, editing JSON directly with syntax highlighting.
- **Tree**: a read-only, small-font nested tree for quickly scanning the structure. Invalid JSON shows a parse error.
- **Tabled Input**: a two-column table. The checkbox on the left decides whether the row is written into the request JSON; the middle is the Key and the right is the Value; the minus at the end of the row deletes it. Below the table an "Add row" button adds more rows. Unchecked rows never appear in the JSON, and switching back to the JSON view shows the same result.

Settings toggles following redirects and certificate validation, and sets the timeout in seconds.

On success it slides to the Response page automatically; on failure the Request page shows the error. Every send writes a history JSON in the app home directory — these files are also visible in the terminal.

## Viewing the response

The top of the Response page is a tab bar (no close button):

- **Headers (count)**: response headers. Shown as a table by default, switchable to JSON.
- **Body**: the response body. Formatted as syntax-highlighted JSON by default, switchable to a table. The JSON view stretches behind the floating bottom bar, with a copy button at the top-right.
- **Metrics**: request ID, status code, protocol, elapsed time, DNS / connect / TLS timings, request and response sizes, and so on. No view switching on this tab.

Tapping the "Table", "JSON" or "Tabled Input" text below the tabs opens a bottom sheet choosing how the current tab is displayed. Tables and JSON share the same rounded-card layout. Very large responses are truncated for display so the page stays responsive.

## Importing and exporting cURL

Tap the three-dot menu on the right of the top bar:

- **Import cURL**: opens a near-full-screen bottom sheet titled Import cURL. Paste the command into the editor and tap the floating button — method, URL, Query, Headers, Body and Authorization get filled in.
- **Export cURL**: the same full-screen bottom sheet, showing the cURL for the current request read-only. Tapping Export cURL copies it to the clipboard.

If parsing fails it stays in the sheet with a parse error.

## History

Tap History in the three-dot menu at the top-right to open a bottom sheet listing this and previously saved requests. The currently loaded one has a check mark on the right; tapping any entry fills back its URL, method, Query, Headers, Body, Authorization and Settings.

These history files live under `network-history` in the app home directory — `~/.xencode/network-history/` in the container terminal. You can also clear them under "Settings → Storage" by expanding "AI configuration & artifacts" and checking "Network request history".
