package com.xencode.core.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.xencode.core.util.FileLogger

/**
 * App-wide unified sampled bitmap decoding.
 *
 * The project doesn't use Coil/Glide (dependencies are deliberately kept lean for reproducible
 * F-Droid builds); all images are decoded with hand-written [BitmapFactory] calls. Sampling
 * and OOM fallback logic is consolidated here so every call site doesn't roll its own copy —
 * and its own bugs.
 */
private const val TAG = "ImageDecode"

/** Thumbnail tier: chat attachment preview cards are only 76dp, so sampling to 180 leaves headroom. */
const val THUMBNAIL_MAX_EDGE = 180

/** Inline image tier for Markdown body text: display height capped at 320dp. */
const val MARKDOWN_MAX_EDGE = 1024

/** Total pixel cap for the fullscreen viewer: about 16MB peak under ARGB_8888. */
private const val VIEWER_MAX_PIXELS = 4_000_000L

/** Number of downsample retries after a decode OOM. */
private const val OOM_RETRIES = 3

/** inSampleSize cap, keeping the fallback loop from running away on pathological dimensions. */
private const val MAX_SAMPLE = 32

/**
 * Computes [BitmapFactory.Options.inSampleSize] by the **longest edge**.
 *
 * The check uses `||`, not `&&`: `&&` effectively constrains the short edge, so a long
 * screenshot like 4000×300 slips past sampling entirely and decodes at full size (~4.8MB) —
 * and long web-page screenshots and long terminal-output screenshots produced by AI are
 * exactly that shape.
 */
fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int): Int {
    if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
    var sample = 1
    while (sample < MAX_SAMPLE && (width / (sample * 2) >= maxEdge || height / (sample * 2) >= maxEdge)) {
        sample *= 2
    }
    return sample
}

/**
 * On top of the max-edge constraint, also caps by total pixels: under ARGB_8888,
 * `maxPixels × 4` bytes is the peak decode memory; images with extreme aspect ratios
 * (longest edge within limits but a huge pixel count) are caught by this layer.
 */
fun calculateInSampleSize(width: Int, height: Int, maxEdge: Int, maxPixels: Long): Int {
    var sample = calculateInSampleSize(width, height, maxEdge)
    if (width <= 0 || height <= 0 || maxPixels <= 0) return sample
    while (sample < MAX_SAMPLE && (width / sample).toLong() * (height / sample) > maxPixels) {
        sample *= 2
    }
    return sample
}

/** Sampled-decodes a local file. Returns null if the file doesn't exist, isn't an image, or fails to decode. */
fun decodeSampledBitmap(path: String, maxEdge: Int, maxPixels: Long = Long.MAX_VALUE): ImageBitmap? {
    val bounds = decodeBounds(path) { BitmapFactory.decodeFile(path, it) } ?: return null
    return decodeWithOomRetry(path, bounds, maxEdge, maxPixels) { BitmapFactory.decodeFile(path, it) }
}

/** Sampled-decodes in-memory image bytes. */
fun decodeSampledBitmap(bytes: ByteArray, maxEdge: Int, maxPixels: Long = Long.MAX_VALUE): ImageBitmap? {
    val what = "byte[${bytes.size}]"
    val decode: (BitmapFactory.Options) -> Bitmap? = { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }
    val bounds = decodeBounds(what, decode) ?: return null
    return decodeWithOomRetry(what, bounds, maxEdge, maxPixels, decode)
}

/** Reads only the image dimensions without allocating pixel memory. */
private fun decodeBounds(what: String, decode: (BitmapFactory.Options) -> Bitmap?): Pair<Int, Int>? {
    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    try {
        decode(options)
    } catch (e: Exception) {
        FileLogger.w(TAG, "Failed to read image bounds: $what", e)
        return null
    }
    return if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
}

/**
 * Decodes at the sampled tier, retrying one tier lower on OOM.
 *
 * [OutOfMemoryError] is an `Error`, not an `Exception`; neither `runCatching {}` nor
 * `catch (e: Exception)` catches it — without catching it explicitly, one oversized image
 * can crash the app.
 */
private fun decodeWithOomRetry(
    what: String,
    bounds: Pair<Int, Int>,
    maxEdge: Int,
    maxPixels: Long,
    decode: (BitmapFactory.Options) -> Bitmap?
): ImageBitmap? {
    var sample = calculateInSampleSize(bounds.first, bounds.second, maxEdge, maxPixels)
    repeat(OOM_RETRIES) {
        try {
            return decode(BitmapFactory.Options().apply { inSampleSize = sample })?.asImageBitmap()
        } catch (e: OutOfMemoryError) {
            FileLogger.w(TAG, "Decode OOM, inSampleSize $sample -> ${sample * 2}: $what")
            sample *= 2
        } catch (e: Exception) {
            FileLogger.w(TAG, "Decode failed: $what", e)
            return null
        }
    }
    FileLogger.w(TAG, "Repeated decode OOM, giving up: $what")
    return null
}

/** Decode tier: max edge (pixels) + total pixel cap. */
data class ImageDecodeSpec(val maxEdge: Int, val maxPixels: Long)

/**
 * The decode tier for the fullscreen viewer, following the current window instead of being
 * hard-coded.
 *
 * The viewer allows zooming in on detail, so it uses 1.5x the window's long edge, clamped to
 * 1024..2048. A hard-coded 2048 is pure waste on 1080p phones (the screen is only 2.6MP) and
 * a genuine OOM risk on 720p low-end devices.
 */
@Composable
fun rememberViewerDecodeSpec(): ImageDecodeSpec {
    val config = LocalConfiguration.current
    val density = LocalDensity.current
    return remember(config.screenWidthDp, config.screenHeightDp, density.density) {
        val longEdgeDp = maxOf(config.screenWidthDp, config.screenHeightDp)
        val longEdgePx = with(density) { longEdgeDp.dp.toPx() }.toInt()
        ImageDecodeSpec(
            maxEdge = (longEdgePx * 3 / 2).coerceIn(1024, 2048),
            maxPixels = VIEWER_MAX_PIXELS
        )
    }
}
