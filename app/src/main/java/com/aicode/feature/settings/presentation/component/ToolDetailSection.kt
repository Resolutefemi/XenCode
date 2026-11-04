package com.aicode.feature.settings.presentation.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.feature.settings.data.local.ToolCatalogEntry
import com.aicode.feature.settings.data.local.ToolCatalogParam

/** Tool detail page: name/summary card, permission card, parameters card. Display only
 * (data from assets/tools_catalog.json), same grouped-card look as the skill detail page. */
@Composable
internal fun ToolDetailSection(
    entry: ToolCatalogEntry
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        // Card 1: tool name + title + description
        SettingsGroup {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = 12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    McpPill(
                        text = entry.name,
                        textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        backgroundColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = entry.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }

        // Card 2: permission mode
        SettingsGroupHeader(text = stringResource(R.string.tools_permission_header))
        SettingsGroup {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.tools_permission_mode),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
                McpPill(
                    text = stringResource(
                        if (entry.permission == "auto") R.string.tools_permission_auto
                        else R.string.tools_permission_ask
                    ),
                    textColor = if (entry.permission == "auto") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    backgroundColor = (if (entry.permission == "auto") MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary).copy(alpha = 0.12f)
                )
            }
            SettingsDivider()
            Text(
                text = stringResource(
                    if (entry.permission == "auto") R.string.tools_permission_note_auto
                    else R.string.tools_permission_note_ask
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg, vertical = 12.dp)
            )
        }

        // Card 3: parameters
        SettingsGroupHeader(text = stringResource(R.string.tools_parameters_header))
        SettingsGroup {
            if (entry.parameters.isEmpty()) {
                Text(
                    text = stringResource(R.string.tools_parameters_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg, vertical = 12.dp)
                )
            } else {
                entry.parameters.forEachIndexed { index, param ->
                    if (index > 0) SettingsDivider()
                    ToolParamRow(param)
                }
            }
        }
    }
}

/** Single parameter row: name (monospace) + type/required pills + description. */
@Composable
private fun ToolParamRow(
    param: ToolCatalogParam
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = param.name,
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.width(Spacing.sm))
            McpPill(
                text = param.type.lowercase(),
                textColor = MaterialTheme.colorScheme.onSurfaceVariant,
                backgroundColor = MaterialTheme.colorScheme.surfaceVariant
            )
            Spacer(modifier = Modifier.width(6.dp))
            McpPill(
                text = stringResource(
                    if (param.required) R.string.tools_param_required else R.string.tools_param_optional
                ),
                textColor = if (param.required) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                backgroundColor = (if (param.required) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline).copy(alpha = 0.12f)
            )
        }
        if (param.description.isNotBlank()) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = param.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
