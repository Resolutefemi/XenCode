package com.xencode.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xencode.R

/** Touch target width: visually just a thin line, but fingers need enough width to grab. */
private val HandleTouchWidth = 14.dp

/**
 * Draggable divider between the left and right panes. [onDragDelta] receives horizontal
 * displacement in pixels; the caller converts it to a pane ratio.
 */
@Composable
fun VerticalSplitHandle(
    onDragDelta: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val dragging by interactionSource.collectIsDraggedAsState()
    val description = stringResource(R.string.workbench_split_handle)
    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(HandleTouchWidth)
            .semantics { contentDescription = description }
            .draggable(
                state = rememberDraggableState { onDragDelta(it) },
                orientation = Orientation.Horizontal,
                interactionSource = interactionSource
            )
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(if (dragging) 3.dp else 1.dp)
                .background(
                    if (dragging) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outlineVariant
                    }
                )
        )
    }
}
