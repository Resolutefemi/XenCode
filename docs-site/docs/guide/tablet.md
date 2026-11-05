# Tablets and Large Screens

::: tip Version note
The large-screen layout described on this page is available starting from 1.11.0.
:::

On large screens XenCode automatically switches to a two-pane layout: the sidebar stays put, and code or a terminal can sit beside the chat. The interface and interactions on phones stay exactly the same.

## When the Layout Switches

The deciding factor is the **current window width**, not the screen size:

| Window width | Layout |
| --- | --- |
| Narrow (most phones in portrait) | Single pane; the sidebar slides out from the hamburger button, same as before |
| Medium (tablets held upright, phones in landscape) | Single pane, with a slightly wider sidebar |
| Wide (tablets in landscape, foldables unfolded, desktop windows) | Persistent sidebar; the chat page can open a right pane |

Because the deciding factor is window width, dragging XenCode to half or a third of the screen on a tablet makes it fall back to the single-pane layout instead of getting cramped.

## Persistent Sidebar

On a wide screen entering the chat page, the session list and file tree are pinned to the left — no need to slide them out every time. The hamburger button in the title bar disappears (the sidebar is already persistent).

Tapping a file in the file tree or selecting another session never collapses the sidebar, so you can keep tapping to browse.

## Side-by-Side Workbench

On a wide screen, a panel can be displayed alongside the chat page:

- Tap the **Terminal** icon in the title bar → the right pane opens a terminal; tap again to collapse it
- Tap the **Git** icon in the title bar → the right pane opens the Git panel; tap again to collapse it
- Tap a file in the file tree, or a file path link in an AI reply → the right pane opens the code editor (images in the file tree open as a fullscreen preview instead, and APKs open with the system installer)

Whichever panel the right pane is showing, its icon in the title bar is highlighted.

Watch the AI edit code while checking file contents or running commands on the right — no need to flip back and forth between pages.

**Adjust the width**: Drag the vertical divider to any ratio between 30/70 and 70/30; the ratio is remembered.

**Close the right pane**: Tap the ✕ in its top-left corner, or press the back button once. If the editor has unsaved changes, the save prompt still appears first.

## Two-Pane Settings

On a wide screen, Settings shows the item list on the left (always visible) and the selected item's content on the right. Switching items only changes the right half — no more whole-page jumps. The back arrow in the top-left leaves Settings and returns to the chat.

## Rotation and Split Screen No Longer Interrupt

When rotating the screen or resizing a split-screen window, the layout reflows but nothing reloads:

- Commands running in the terminal keep running, and their output is not lost
- Unsaved changes in the editor are still there
- The chat list stays right where it was

## Reading Width

On wide screens the chat content does not stretch across the whole screen; it is centered at a comfortable reading width — overly long lines are harder to read. When the right pane opens, the left half narrows and the content narrows along with it.

## Mouse and Trackpad

With a mouse connected, moving the pointer over the pane divider turns it into a drag cursor; hold and drag left or right to adjust the two panes' widths.
