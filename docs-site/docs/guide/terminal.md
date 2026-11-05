# Terminal

XenCode has a full terminal built in. In local mode it connects to the Linux container on your phone; in remote mode it connects to your SSH server — both are real shells where you can install packages, compile code, and run services.

Enter it via "Terminal" in the title bar at the top of the chat page. Tap the back button in the top-left corner to return to the chat page; repeated taps during the transition will not exit the chat page further. Going back does not close the terminal tab.

## Tab Management

The terminal supports multiple tabs at once. The tab bar at the top scrolls horizontally, and the active tab automatically scrolls into view when you switch.

- The dot before a tab name indicates status: green means running, gray means finished.
- Tap the × on the right of a tab to close it, or the + at the end of the tab bar to create a new one. Closing the current tab automatically switches to an adjacent one.
- Terminals opened while the AI runs commands also show up here, so you can switch over at any time to check on them.

## Typing and Copying

Tap the terminal area to focus it and start typing. Long-press to select and copy output.

## Extra Keys Bar

Phone keyboards lack Esc, Ctrl, and arrow keys, so the terminal provides a horizontally scrollable extra keys bar at the bottom:

- **Control keys**: `ESC`, `TAB`, `CTRL`, `ALT`. Ctrl and Alt light up when pressed; then press a letter key to form a key combination.
- **Arrow keys**: Up, down, left, right; hold to send repeatedly and move the cursor quickly.
- **Common combos**: `C-c` interrupts the current command, `C-d` sends EOF or exits, `C-z` suspends the process, `C-l` clears the screen.
- **Common symbols**: `|`, `~`, `/`, `-`, `_`, `>`, `:`, `$`, `"`, `'`, and more, so you can skip switching to the symbol keyboard.

## Appearance Settings

Tap the gear icon in the top-right corner of the terminal page (or go to "Settings → General Settings → Terminal Settings") to adjust:

- **Color theme**: Five built-in themes — Termius Dark, Dracula, One Dark, Monokai, and GitHub Dark. A selection takes effect immediately across all open tabs.
- **Font size**: Adjusts continuously from 5 to 22, with a live preview at the top of the panel; you can also pinch to zoom directly in the terminal area — it drives the same value as here.
- **Font** (**since 1.11.0**): The default is JetBrains Mono (the no-ligatures variant), which keeps `0` vs `O` and `1` vs `l` clearly distinct. You can also switch to the system monospace font, or tap "Import" to pick a `ttf` / `otf` / `ttc` font file from your phone; imported fonts appear in the list, and tapping the × on a font chip deletes it — deleting the one in use falls back to the system monospace font. Chinese characters are always rendered by the system font. The font only affects the terminal, not the AI chat area.
- **Cursor style**: Block, underline, or bar.
