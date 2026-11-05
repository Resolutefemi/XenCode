package com.xencode.core.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.xencode.core.theme.Radius
import com.xencode.core.theme.semanticColors

/**
 * App-wide text field color spec:
 * - Light mode: themed card background + soft outline + focused primary highlight;
 * - Dark mode: dark surface base (surface/surfaceVariant) + border + focused primary glow.
 */
@Composable
fun appTextFieldColors(
    isLight: Boolean = MaterialTheme.colorScheme.background.luminance() > 0.5f,
    unfocusedContainerColor: Color = MaterialTheme.semanticColors.cardSurface,
    focusedContainerColor: Color = if (isLight) MaterialTheme.semanticColors.cardSurface
    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
): TextFieldColors {
    return OutlinedTextFieldDefaults.colors(
        unfocusedBorderColor = if (isLight) MaterialTheme.colorScheme.outlineVariant
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        unfocusedContainerColor = unfocusedContainerColor,
        focusedContainerColor = focusedContainerColor,
        focusedLabelColor = MaterialTheme.colorScheme.primary,
        unfocusedLabelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        cursorColor = MaterialTheme.colorScheme.primary,
        focusedLeadingIconColor = MaterialTheme.colorScheme.primary,
        unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        focusedTrailingIconColor = MaterialTheme.colorScheme.primary,
        unfocusedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        // The disabled state keeps the unfocused container color: M3 defaults the disabled
        // container to transparent, so toggling enabled makes the background jump between
        // colored and transparent — it reads as the field flickering. The disabled semantics
        // are conveyed by the lighter border and text instead.
        disabledContainerColor = unfocusedContainerColor,
        disabledBorderColor = if (isLight) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
    )
}

/**
 * Text field colors for dialogs (containers like AlertDialog that bring their own themed
 * background): fully transparent containers so the dialog's own color shows through.
 *
 * Dialog backgrounds change with the theme, and the card white used on regular pages would
 * look like a piece of paper pasted on in such dialogs.
 */
@Composable
fun dialogTextFieldColors(): TextFieldColors = appTextFieldColors(
    unfocusedContainerColor = Color.Transparent,
    focusedContainerColor = Color.Transparent
)

/**
 * App-wide base text field component:
 * 12dp rounded card feel, automatically adapting to light pure-white / dark deep-blue
 * backgrounds, with support for leading/trailing icons and full keyboard configuration.
 */
@Composable
fun AppTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
    label: String? = null,
    placeholder: String? = null,
    labelComposable: (@Composable () -> Unit)? = null,
    placeholderComposable: (@Composable () -> Unit)? = null,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    supportingText: (@Composable () -> Unit)? = null,
    isError: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    singleLine: Boolean = true,
    maxLines: Int = if (singleLine) 1 else Int.MAX_VALUE,
    minLines: Int = 1,
    readOnly: Boolean = false,
    enabled: Boolean = true,
    textStyle: TextStyle = MaterialTheme.typography.bodyMedium,
    shape: Shape = RoundedCornerShape(Radius.mdLarge),
    colors: TextFieldColors = appTextFieldColors(),
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() }
) {
    val actualLabel: (@Composable () -> Unit)? = labelComposable ?: label?.let { { Text(it) } }
    val actualPlaceholder: (@Composable () -> Unit)? = placeholderComposable ?: placeholder?.let { { Text(it) } }

    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier,
        enabled = enabled,
        readOnly = readOnly,
        textStyle = textStyle,
        label = actualLabel,
        placeholder = actualPlaceholder,
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        supportingText = supportingText,
        isError = isError,
        visualTransformation = visualTransformation,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        singleLine = singleLine,
        maxLines = maxLines,
        minLines = minLines,
        interactionSource = interactionSource,
        shape = shape,
        colors = colors
    )
}
