package com.xencode.core.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.styropyr0.prismal.PrismalBackdrop
import com.styropyr0.prismal.PrismalGlassSurface
import com.styropyr0.prismal.shapes.PrismalRoundedRectangle
import com.styropyr0.prismal.sources.PrismalGlassLayer
import com.styropyr0.prismal.sources.prismalGlassLayer
import com.styropyr0.prismal.sources.rememberPrismalGlassLayer

/**
 * Liquid Glass scaffolding (built on PrismalAGSL).
 *
 * Three rules of use — please keep them intact when making changes:
 *
 * 1. **Exactly one backdrop**: each screen may create only one [rememberChatGlassLayer], and
 *    it must be attached with [Modifier.glassBackdrop] to the "content behind the glass"
 *    (the message list on the chat page). Multiple full-screen backdrop captures
 *    significantly inflate GPU and memory cost — exactly what the requirements call out to
 *    avoid. All glass elements (title bar / input area / to-do overlay) share this one layer
 *    instead of sampling on their own.
 *
 * 2. **Local surfaces only**: glass appears only on those floating layers; the page body
 *    stays plain content.
 *
 * 3. **Don't branch on API level yourself**: PrismalAGSL ships a three-tier fallback
 *    (API 25-30 frosted → 31-32 RenderEffect blur → 33+ full AGSL refraction/dispersion);
 *    we never call RuntimeShader in business code or write Build.VERSION branches, otherwise
 *    we'd break compatibility with Android 8.0 (minSdk 26).
 *    Refraction/dispersion parameters are ignored internally by the library on older
 *    versions; only the highlight overlay (self-drawn gradient) always applies.
 */

/** The screen's single background sampling layer; once created, attach it to the background content with [Modifier.glassBackdrop]. */
@Composable
fun rememberChatGlassLayer(): PrismalGlassLayer = rememberPrismalGlassLayer()

/** Registers a subtree as the glass background source (each screen should mount exactly one). */
fun Modifier.glassBackdrop(layer: PrismalGlassLayer): Modifier = this.prismalGlassLayer(layer)

/** Glass luminance state: [luminance] is handed to the glass for adaptive tuning; [contentColor] is the foreground color for text on the glass. */
@androidx.compose.runtime.Stable
class GlassLuminanceState(
    val luminance: Float,
    val contentColor: Color
)

/**
 * Glass luminance and foreground color (theme-derived, zero sampling).
 *
 * History: we originally used PrismalAGSL's rememberPrismalAdaptiveLuminance — every
 * 600~1500ms it did a toImageBitmap + readPixels GPU→CPU readback of the background layer
 * (sampling more densely while scrolling), and every readback forced a render-pipeline
 * flush, the source of the periodic micro-stutters during scrolling/streaming.
 * The chat page background is inherently a stable dark/bright under dark/light themes, and
 * the theme-derived values nearly match measured luminance, so we switched to zero-cost
 * static values; PrismalGlassSurface's internal adaptiveTuning still works from this value.
 * [source] / [enabled] remain only for signature compatibility and no longer take part in
 * sampling.
 */
@Composable
fun rememberGlassLuminance(
    source: PrismalGlassLayer,
    enabled: Boolean = true
): GlassLuminanceState {
    val surface = MaterialTheme.colorScheme.surface
    val contentColor = MaterialTheme.colorScheme.onSurface
    return remember(surface, contentColor) {
        val dark = surface.luminance() <= 0.5f
        GlassLuminanceState(
            luminance = if (dark) 0.12f else 0.92f,
            contentColor = contentColor
        )
    }
}

/**
 * Unified Liquid Glass container.
 *
 * The lighting comes from three layers, all indispensable:
 * 1. **AGSL refraction band** ([refractionHeight] / [refractionAmount] /
 *    [chromaticAberration] / [depthEffect]): an arched bulge along the edges + RGB
 *    dispersion; content sliding under the glass edge gets genuinely "bent" — the core of
 *    the "liquid" feel. Before refraction parameters were passed, the glass was just a
 *    frosted panel;
 * 2. **vibrancy** ([saturation] / [contrast]): boosts the sampled background's saturation
 *    and micro-contrast so the colors behind "come through" instead of mushing into gray;
 * 3. **Self-drawn highlights** ([specularHighlight]): a diagonal gradient sheen + a top
 *    specular bright band + a bright-top/dark-bottom rim stroke, simulating polished-edge
 *    reflection; works on all API levels, no AGSL dependency.
 *
 * - [cornerRadius]: large rounding, the basic Liquid Glass form;
 * - [tintAlpha]: very low, just a light surface tint, avoiding a "large semi-transparent
 *   white slab";
 * - [adaptiveLuminance] + [luminance]: lets blur and color adapt to background brightness
 *   while keeping a contrast floor.
 */
@Composable
fun GlassSurface(
    /**
     * The background sampling layer; when null, degrades to a plain container of
     * "semi-transparent surface + ultra-light border".
     * Callers then don't need two layouts — the glass toggle is just null / non-null.
     */
    backdrop: PrismalBackdrop?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 22.dp,
    blurRadius: Dp = 12.dp,
    tintAlpha: Float = 0.10f,
    adaptiveLuminance: Boolean = true,
    luminance: () -> Float = { 0.5f },
    /** Refraction band thickness (width of the arched bulge from the edge inward); 0.dp disables refraction. */
    refractionHeight: Dp = 14.dp,
    /** Refraction bend strength (pixel-level displacement); larger means more visible edge distortion. */
    refractionAmount: Dp = 30.dp,
    /** RGB dispersion strength [0,1], the rainbow fringe at the refraction band edge; 0 disables. */
    chromaticAberration: Float = 0.16f,
    /** Sampled-background saturation boost (iOS vibrancy); 1f = no boost. */
    saturation: Float = 1.35f,
    /** Top specular highlight and rim stroke (self-drawn, works on all API levels). */
    specularHighlight: Boolean = true,
    content: @Composable BoxScope.() -> Unit
) {
    if (backdrop == null) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(cornerRadius))
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.98f))
                .border(
                    1.dp,
                    MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    RoundedCornerShape(cornerRadius)
                ),
            content = content
        )
        return
    }
    val density = LocalDensity.current
    PrismalGlassSurface(
        backdrop = backdrop,
        modifier = modifier,
        shape = { PrismalRoundedRectangle(cornerRadius) },
        adaptiveLuminance = adaptiveLuminance,
        luminance = luminance,
        blurRadius = blurRadius,
        tint = MaterialTheme.colorScheme.surface,
        tintAlpha = tintAlpha,
        saturation = saturation,
        contrast = 1.04f,
        refractionHeightPx = with(density) { refractionHeight.toPx() },
        refractionAmountPx = with(density) { refractionAmount.toPx() },
        depthEffect = true,
        chromaticAberration = chromaticAberration
    ) {
        if (specularHighlight) {
            GlassSheenOverlay(cornerRadius = cornerRadius)
        }
        content()
        if (specularHighlight) {
            GlassRimOverlay(cornerRadius = cornerRadius)
        }
    }
}

/**
 * Glass volumetric light: a diagonal sheen (slightly bright top-left, ultra-light
 * reflection at the bottom) + a top specular highlight band.
 * Drawn beneath the content, it only adds the sense of "light passing through glass"
 * without hurting readability.
 *
 * Brushes are remember-ed: GlassSurface recompositions no longer allocate 3 new Brush
 * objects per frame, reducing GC pressure and GPU shader-pipeline rebuilds during
 * scrolling/streaming output.
 */
@Composable
private fun BoxScope.GlassSheenOverlay(cornerRadius: Dp) {
    val shape = RoundedCornerShape(cornerRadius)
    // In light themes the background is already bright; keep the highlights more restrained to avoid a wash of white
    val scale = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) 0.55f else 1f
    val sheenBrush = remember(scale) {
        Brush.linearGradient(
            0.0f to Color.White.copy(alpha = 0.10f * scale),
            0.22f to Color.White.copy(alpha = 0.03f * scale),
            0.5f to Color.Transparent,
            1.0f to Color.White.copy(alpha = 0.05f * scale),
            start = Offset.Zero,
            end = Offset.Infinite
        )
    }
    val specularBrush = remember(scale) {
        Brush.verticalGradient(
            0.0f to Color.White.copy(alpha = 0.22f * scale),
            1.0f to Color.Transparent
        )
    }
    Box(
        modifier = Modifier
            .matchParentSize()
            .clip(shape)
            .background(sheenBrush)
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(16.dp)
            .align(Alignment.TopCenter)
            .clip(shape)
            .background(specularBrush)
    )
}

/**
 * Rim light stroke: bright top edge, faint side edges, a bit of bounce reflection at the
 * bottom — the edge reflection of polished-edge glass.
 * Drawn above the content (only 1dp, doesn't occlude content) so the glass outline stays
 * crisp on busy backgrounds.
 */
@Composable
private fun BoxScope.GlassRimOverlay(cornerRadius: Dp) {
    val scale = if (MaterialTheme.colorScheme.surface.luminance() > 0.5f) 0.5f else 1f
    val rimBrush = remember(scale) {
        Brush.verticalGradient(
            0.0f to Color.White.copy(alpha = 0.55f * scale),
            0.12f to Color.White.copy(alpha = 0.20f * scale),
            0.5f to Color.White.copy(alpha = 0.07f * scale),
            1.0f to Color.White.copy(alpha = 0.30f * scale)
        )
    }
    Box(
        modifier = Modifier
            .matchParentSize()
            .border(
                width = 1.dp,
                brush = rimBrush,
                shape = RoundedCornerShape(cornerRadius)
            )
    )
}
