package com.aicode.feature.network.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aicode.core.theme.Spacing
import com.aicode.feature.network.domain.JsonTreeNode
import compose.icons.FeatherIcons
import compose.icons.feathericons.ChevronDown
import compose.icons.feathericons.ChevronRight

@Composable
fun JsonTreeView(
    nodes: List<JsonTreeNode>?,
    emptyText: String,
    invalidText: String,
    modifier: Modifier = Modifier
) {
    when {
        nodes == null -> {
            Text(
                text = invalidText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier.padding(Spacing.lg)
            )
        }
        nodes.isEmpty() -> {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier.padding(Spacing.lg)
            )
        }
        else -> {
            var expanded by remember(nodes) {
                mutableStateOf(nodes.filter { it.children.isNotEmpty() }.map { it.key }.toSet())
            }
            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(Spacing.md),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                nodes.forEach { node ->
                    JsonTreeRow(
                        node = node,
                        depth = 0,
                        path = node.key,
                        expanded = expanded,
                        onToggle = { path ->
                            expanded = if (path in expanded) expanded - path else expanded + path
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun JsonTreeRow(
    node: JsonTreeNode,
    depth: Int,
    path: String,
    expanded: Set<String>,
    onToggle: (String) -> Unit
) {
    val hasChildren = node.children.isNotEmpty()
    val isExpanded = path in expanded
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (hasChildren) Modifier.clickable { onToggle(path) } else Modifier)
            .padding(start = (depth * 12).dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        if (hasChildren) {
            Icon(
                imageVector = if (isExpanded) FeatherIcons.ChevronDown else FeatherIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp)
            )
        }
        Text(
            text = node.key,
            style = MaterialTheme.typography.bodySmall.copy(
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                lineHeight = 16.sp
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = node.preview,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                lineHeight = 16.sp
            ),
            color = MaterialTheme.colorScheme.onSurface
        )
    }
    if (hasChildren && isExpanded) {
        node.children.forEachIndexed { index, child ->
            JsonTreeRow(
                node = child,
                depth = depth + 1,
                path = "$path/$index/${child.key}",
                expanded = expanded,
                onToggle = onToggle
            )
        }
    }
}
