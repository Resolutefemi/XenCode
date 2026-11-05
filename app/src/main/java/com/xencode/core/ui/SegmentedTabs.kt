package com.xencode.core.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.xencode.core.theme.Radius
import com.xencode.core.theme.semanticColors

/**
 * iOS-style segmented control: neutral gray track + white thumb (raised gray in dark mode);
 * on selection the thumb springs over to the corresponding segment.
 *
 * No theme-color fill — it stays in harmony with the scan-style list below, expressing depth
 * with neutral colors and a light shadow alone.
 * The sidebar top and the MCP editing dialog share this one component, keeping the switching
 * feel consistent.
 */
@Composable
fun SegmentedTabs(
    selected: Int,
    labels: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val trackColor = MaterialTheme.semanticColors.mutedSurface
    val thumbColor = if (dark) MaterialTheme.semanticColors.capsuleSurface else MaterialTheme.semanticColors.cardSurface

    val trackShape = RoundedCornerShape(Radius.xl)
    val thumbShape = RoundedCornerShape(Radius.mdLarge)
    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(trackShape)
            .background(trackColor)
            .padding(2.dp)
    ) {
        val thumbWidth = maxWidth / labels.size
        // Selected thumb offset: critically damped spring (no overshoot); the thumb glides
        // into place on switching rather than hard-cutting.
        val thumbOffset by animateDpAsState(
            targetValue = thumbWidth * selected,
            animationSpec = spring(dampingRatio = 1f, stiffness = Spring.StiffnessMediumLow),
            label = "segmented-thumb"
        )
        Surface(
            modifier = Modifier
                .offset(x = thumbOffset)
                .width(thumbWidth)
                .fillMaxHeight(),
            shape = thumbShape,
            color = thumbColor,
            shadowElevation = 2.dp,
            content = {}
        )
        val rippleColor = MaterialTheme.colorScheme.primary
        Row(modifier = Modifier.fillMaxSize()) {
            labels.forEachIndexed { index, label ->
                val isSelected = selected == index
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(thumbShape)
                        .clickable(
                            interactionSource = remember(index) { MutableInteractionSource() },
                            indication = ripple(bounded = true, color = rippleColor)
                        ) { onSelect(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (isSelected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
