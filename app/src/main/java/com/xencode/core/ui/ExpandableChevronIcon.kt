package com.xencode.core.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.xencode.core.theme.Brand
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight

/**
 * Rotation semantics styles for the expand/collapse chevron:
 * - [UP_DOWN]: base points down (ChevronDown); 0° when collapsed, flips 180° to point up when
 *   expanded; used for in-card collapsing, the thinking panel, top-bar boards, etc.;
 * - [RIGHT_DOWN]: base points right (ChevronRight); 0° when collapsed, rotates 90° clockwise
 *   to point down when expanded; used for full-run execution progress, sidebar time groups,
 *   Git status groups, etc.
 */
enum class ChevronRotationStyle {
    UP_DOWN,
    RIGHT_DOWN,
}

private const val CHEVRON_ANIM_DURATION_MS = 200

/**
 * Unified expand/collapse chevron component: wraps the smooth rotation animation with a
 * shared duration/easing curve.
 */
@Composable
fun ExpandableChevronIcon(
    expanded: Boolean,
    modifier: Modifier = Modifier,
    style: ChevronRotationStyle = ChevronRotationStyle.UP_DOWN,
    size: Dp = 18.dp,
    tint: Color = Brand.IconGray,
    contentDescription: String? = null
) {
    val targetRotation = when (style) {
        ChevronRotationStyle.UP_DOWN -> if (expanded) 180f else 0f
        ChevronRotationStyle.RIGHT_DOWN -> if (expanded) 90f else 0f
    }
    val rotation by animateFloatAsState(
        targetValue = targetRotation,
        animationSpec = tween(durationMillis = CHEVRON_ANIM_DURATION_MS, easing = FastOutSlowInEasing),
        label = "chevron_rotation"
    )
    val baseIcon: ImageVector = when (style) {
        ChevronRotationStyle.UP_DOWN -> FeatherIcons.ChevronDown
        ChevronRotationStyle.RIGHT_DOWN -> FeatherIcons.ChevronRight
    }
    Icon(
        imageVector = baseIcon,
        contentDescription = contentDescription,
        tint = tint,
        modifier = modifier
            .size(size)
            .rotate(rotation)
    )
}
