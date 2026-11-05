package com.xencode.core.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetDefaults
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetState
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Surface
import androidx.compose.material3.contentColorFor
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xencode.core.theme.Spacing

/**
 * Works around a known Material3 ModalBottomSheet bug (issuetracker 486562294):
 * when the content is near full-screen height and scrollable, a fast upward fling hitting the
 * content boundary passes the unconsumed upward velocity to the sheet's drag state, making the
 * sheet oscillate — repeatedly "dragged down then springing back".
 *
 * Attach it to the outer layer of scrollable content: once the sheet is fully expanded it
 * swallows the leftover upward fling velocity so it no longer reaches the sheet's
 * AnchoredDraggableState; the content's own inertial scrolling is unaffected (the leftover
 * amount is only intercepted after the child scroll consumes what it can).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun rememberSheetFlingFix(sheetState: SheetState): NestedScrollConnection =
    remember(sheetState) {
        object : NestedScrollConnection {
            override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
                if ((sheetState.currentValue == SheetValue.Expanded || sheetState.targetValue == SheetValue.Expanded) && available.y < 0f) {
                    available
                } else {
                    Velocity.Zero
                }
        }
    }

/**
 * Adaptive modal bottom sheet / dialog:
 * - Compact screens (phone portrait, narrow split-screen, etc., < 600dp): renders as a
 *   standard bottom-anchored [ModalBottomSheet] that keeps the pull-down gesture and drag
 *   handle, with [rememberSheetFlingFix] built in to eliminate the jittery oscillation on fast
 *   flings to the edge;
 * - Wide screens/tablets (>= 600dp): renders as a proper centered modal [Dialog] with the drag
 *   handle removed, a capped max width, and centered placement.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdaptiveModalBottomSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(),
    shape: Shape = BottomSheetDefaults.ExpandedShape,
    containerColor: Color = MaterialTheme.colorScheme.surface,
    contentColor: Color = contentColorFor(containerColor),
    tonalElevation: Dp = BottomSheetDefaults.Elevation,
    scrimColor: Color = BottomSheetDefaults.ScrimColor,
    dragHandle: @Composable (() -> Unit)? = { BottomSheetDefaults.DragHandle() },
    contentWindowInsets: @Composable () -> WindowInsets = { BottomSheetDefaults.windowInsets },
    properties: ModalBottomSheetProperties = ModalBottomSheetDefaults.properties,
    sheetGesturesEnabled: Boolean = true,
    dialogMaxWidth: Dp = 560.dp,
    content: @Composable ColumnScope.() -> Unit
) {
    if (isCompactWidth()) {
        val flingFix = rememberSheetFlingFix(sheetState)
        ModalBottomSheet(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            sheetState = sheetState,
            shape = shape,
            containerColor = containerColor,
            contentColor = contentColor,
            tonalElevation = tonalElevation,
            scrimColor = scrimColor,
            dragHandle = dragHandle,
            contentWindowInsets = contentWindowInsets,
            properties = properties,
            sheetGesturesEnabled = sheetGesturesEnabled
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .nestedScroll(flingFix)
            ) {
                content()
            }
        }
    } else {
        Dialog(
            onDismissRequest = onDismissRequest,
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Surface(
                modifier = modifier
                    .widthIn(min = 280.dp, max = dialogMaxWidth)
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = Spacing.xl)
                    .heightIn(max = (LocalConfiguration.current.screenHeightDp * 0.85f).dp),
                shape = RoundedCornerShape(16.dp),
                color = containerColor,
                contentColor = contentColor,
                tonalElevation = tonalElevation
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = Spacing.md)
                ) {
                    content()
                }
            }
        }
    }
}
