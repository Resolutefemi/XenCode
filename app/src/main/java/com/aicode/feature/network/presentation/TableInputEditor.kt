package com.aicode.feature.network.presentation

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.semanticColors
import com.aicode.feature.network.domain.TableInputRow
import com.aicode.feature.network.domain.jsonToTableRows
import com.aicode.feature.network.domain.tableRowsToJson
import compose.icons.FeatherIcons
import compose.icons.feathericons.Minus
import compose.icons.feathericons.Plus

@Composable
fun TableInputEditor(
    jsonText: String,
    onJsonChange: (String) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    var rows by remember { mutableStateOf(jsonToTableRows(jsonText)) }
    var lastSent by remember { mutableStateOf(jsonText) }

    LaunchedEffect(jsonText) {
        if (jsonText != lastSent) {
            rows = runCatching { jsonToTableRows(jsonText) }.getOrElse { listOf(TableInputRow()) }
            lastSent = jsonText
        }
    }

    fun emit(next: List<TableInputRow>) {
        val normalized = next.ifEmpty { listOf(TableInputRow()) }
        rows = normalized
        val json = runCatching { tableRowsToJson(normalized) }.getOrDefault(jsonText)
        lastSent = json
        onJsonChange(json)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        TableInputHeader()
        HorizontalDivider(
            thickness = 0.5.dp,
            color = MaterialTheme.semanticColors.subtleBorder
        )
        rows.forEachIndexed { index, row ->
            TableInputLine(
                row = row,
                enabled = enabled,
                onKeyChange = { value ->
                    emit(rows.mapIndexed { i, item -> if (i == index) item.copy(key = value) else item })
                },
                onValueChange = { value ->
                    emit(rows.mapIndexed { i, item -> if (i == index) item.copy(value = value) else item })
                },
                onEnabledChange = { checked ->
                    emit(rows.mapIndexed { i, item -> if (i == index) item.copy(enabled = checked) else item })
                },
                onRemove = {
                    emit(rows.filterIndexed { i, _ -> i != index })
                }
            )
            if (index != rows.lastIndex) {
                HorizontalDivider(
                    thickness = 0.5.dp,
                    color = MaterialTheme.semanticColors.subtleBorder.copy(alpha = 0.6f)
                )
            }
        }
        TextButton(
            onClick = { emit(rows + TableInputRow()) },
            enabled = enabled,
            contentPadding = PaddingValues(horizontal = Spacing.md, vertical = Spacing.xs),
            modifier = Modifier.padding(start = Spacing.xs, top = Spacing.xs, bottom = Spacing.sm)
        ) {
            Icon(
                imageVector = FeatherIcons.Plus,
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
            Spacer(Modifier.width(Spacing.xs))
            Text(
                text = stringResource(R.string.network_request_table_add_row),
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@Composable
private fun TableInputHeader() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(48.dp))
        Text(
            text = stringResource(R.string.network_request_table_key),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(Spacing.sm))
        Text(
            text = stringResource(R.string.network_request_table_value),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.width(36.dp))
    }
}

@Composable
private fun TableInputLine(
    row: TableInputRow,
    enabled: Boolean,
    onKeyChange: (String) -> Unit,
    onValueChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        Box(
            modifier = Modifier.width(48.dp),
            contentAlignment = Alignment.Center
        ) {
            Checkbox(
                checked = row.enabled,
                onCheckedChange = if (enabled) onEnabledChange else null,
                enabled = enabled,
                colors = CheckboxDefaults.colors(
                    checkedColor = MaterialTheme.colorScheme.primary,
                    uncheckedColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
        UnderlineCell(
            value = row.key,
            onValueChange = onKeyChange,
            placeholder = stringResource(R.string.network_request_table_key),
            enabled = enabled,
            modifier = Modifier.weight(1f)
        )
        UnderlineCell(
            value = row.value,
            onValueChange = onValueChange,
            placeholder = stringResource(R.string.network_request_table_value),
            enabled = enabled,
            modifier = Modifier.weight(1f)
        )
        IconButton(
            onClick = onRemove,
            enabled = enabled,
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = FeatherIcons.Minus,
                contentDescription = stringResource(R.string.network_request_table_remove_row),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

@Composable
private fun UnderlineCell(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val lineColor = when {
        focused -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.semanticColors.subtleBorder.copy(alpha = 0.55f)
    }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodySmall.copy(
            color = MaterialTheme.colorScheme.onSurface
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = interaction,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
        modifier = modifier.clipToBounds(),
        decorationBox = { inner ->
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(24.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (value.isEmpty()) {
                        Text(
                            text = placeholder,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            maxLines = 1,
                            overflow = TextOverflow.Clip
                        )
                    }
                    inner()
                }
                HorizontalDivider(
                    thickness = if (focused) 1.5.dp else 0.5.dp,
                    color = lineColor
                )
            }
        }
    )
}
