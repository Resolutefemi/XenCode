package com.xencode.desktop

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Brand palette for the desktop app: near-black shell (#0A0C0E), slightly lifted
 * surfaces (#101418), white text and the signature lightning green (#6EE7A0) as
 * the primary color.
 */
object XenPalette {
    val Background = Color(0xFF0A0C0E)
    val Surface = Color(0xFF101418)
    val SurfaceVariant = Color(0xFF1A2026)
    val Primary = Color(0xFF6EE7A0)
    val OnPrimary = Color(0xFF06130C)
    val PrimaryContainer = Color(0xFF173622)
    val OnPrimaryContainer = Color(0xFFC9F6DC)
    val Secondary = Color(0xFFE6EBF0)
    val OnSecondary = Color(0xFF14181C)
    val OnBackground = Color(0xFFE9EDF1)
    val OnSurfaceVariant = Color(0xFF9AA7B0)
    val Outline = Color(0xFF2A323A)
    val OutlineVariant = Color(0xFF20262C)
    val Error = Color(0xFFFF7A7A)
    val OnError = Color(0xFF2A0808)
    val ErrorContainer = Color(0xFF3A1414)
    val OnErrorContainer = Color(0xFFFFD2D2)
}

/** Dark Material 3 scheme used for the whole app (the desktop app is always dark). */
val XenDarkColorScheme = darkColorScheme(
    primary = XenPalette.Primary,
    onPrimary = XenPalette.OnPrimary,
    primaryContainer = XenPalette.PrimaryContainer,
    onPrimaryContainer = XenPalette.OnPrimaryContainer,
    secondary = XenPalette.Secondary,
    onSecondary = XenPalette.OnSecondary,
    secondaryContainer = XenPalette.SurfaceVariant,
    onSecondaryContainer = XenPalette.OnBackground,
    tertiary = XenPalette.Primary,
    onTertiary = XenPalette.OnPrimary,
    background = XenPalette.Background,
    onBackground = XenPalette.OnBackground,
    surface = XenPalette.Surface,
    onSurface = XenPalette.OnBackground,
    surfaceVariant = XenPalette.SurfaceVariant,
    onSurfaceVariant = XenPalette.OnSurfaceVariant,
    inverseSurface = XenPalette.OnBackground,
    inverseOnSurface = XenPalette.Surface,
    outline = XenPalette.Outline,
    outlineVariant = XenPalette.OutlineVariant,
    error = XenPalette.Error,
    onError = XenPalette.OnError,
    errorContainer = XenPalette.ErrorContainer,
    onErrorContainer = XenPalette.OnErrorContainer,
    scrim = Color(0xFF000000)
)

/** App theme: fixed dark colors, default Material 3 typography. */
@Composable
fun XenCodeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = XenDarkColorScheme,
        typography = Typography(),
        content = content
    )
}
