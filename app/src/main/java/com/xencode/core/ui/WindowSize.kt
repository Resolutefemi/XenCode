package com.xencode.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Window width tiers; breakpoints follow the Material 3 window size class: 600dp / 840dp.
 *
 * - [COMPACT]  Phone portrait, narrow windows after splitting
 * - [MEDIUM]   Most tablets in portrait, phones in landscape, foldables open in portrait
 * - [EXPANDED] Tablets in landscape, desktop window mode — two panes and a persistent sidebar
 *   start at this tier
 */
enum class WindowWidthClass { COMPACT, MEDIUM, EXPANDED }

/** Breakpoint check. A pure function, easy to cover boundary values in unit tests. */
fun windowWidthClassOf(widthDp: Int): WindowWidthClass = when {
    widthDp < 600 -> WindowWidthClass.COMPACT
    widthDp < 840 -> WindowWidthClass.MEDIUM
    else -> WindowWidthClass.EXPANDED
}

/**
 * The current window width tier.
 *
 * Uses `Configuration.screenWidthDp` rather than the physical screen size: under split-screen /
 * freeform windows it gives **this window's** width, so a tablet dragged into a 1/3 narrow
 * window automatically falls back to the single-pane [WindowWidthClass.COMPACT] layout.
 */
@Composable
fun currentWindowWidthClass(): WindowWidthClass {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return remember(widthDp) { windowWidthClassOf(widthDp) }
}

/** Whether this is a large screen (tablet landscape and up); the toggle condition for two-pane layouts. */
@Composable
fun isExpandedWidth(): Boolean = currentWindowWidthClass() == WindowWidthClass.EXPANDED

/** Whether this is compact width (phone portrait, narrow split-screen, etc., < 600dp). */
@Composable
fun isCompactWidth(): Boolean = currentWindowWidthClass() == WindowWidthClass.COMPACT

object ContentWidth {
    /** Max width of the body column: any wider and a line of text gets too long, costing the
        eyes too much back-and-forth scanning. */
    val readable = 800.dp

    /** Sidebar width: keeps its size in narrow windows; gives the file tree a bit more
        horizontal room on large screens. */
    val drawerCompact = 300.dp
    val drawerWide = 340.dp
}

/**
 * Body column width cap: large screens narrow to [ContentWidth.readable] and center; other
 * tiers are unrestricted.
 *
 * When it returns [Dp.Unspecified], `Modifier.widthIn(max = …)` is equivalent to no
 * constraint, so call sites need no branching.
 */
@Composable
fun readableContentMaxWidth(): Dp =
    if (isExpandedWidth()) ContentWidth.readable else Dp.Unspecified

/** Sidebar width: switches with the window tier. */
@Composable
fun drawerWidth(): Dp =
    if (currentWindowWidthClass() == WindowWidthClass.COMPACT) {
        ContentWidth.drawerCompact
    } else {
        ContentWidth.drawerWide
    }
