package com.xencode.core.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image as ComposeImage
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import com.xencode.R
import com.xencode.core.theme.Spacing
import compose.icons.FeatherIcons
import compose.icons.feathericons.Image
import compose.icons.feathericons.X
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Image source.
 *
 * Deliberately a value type instead of "each call site passing its own suspend loading
 * lambda": value types have stable equals and can serve directly as [produceState] keys; the
 * lambda approach would keep holding the base64 string of a pending attachment — up to 5MB —
 * long after the attachment was sent and the in-memory copy cleared, the viewer would still
 * be keeping it alive.
 */
sealed interface ImageSource {
    /** Absolute path of a host-local file; can be decoded directly. */
    data class LocalFile(val path: String) : ImageSource

    /** Container / remote path from the AI's perspective; must go through the file-access abstraction to land on a host file. */
    data class ContainerPath(val path: String) : ImageSource

    /** In-memory base64 image data. */
    data class Base64(val data: String) : ImageSource
}

/**
 * A single view request.
 *
 * [sources] are tried in order, falling back to the next when one is unavailable — in remote
 * SSH mode the `localPath` recorded for a sent attachment is a `createTempFile` +
 * `deleteOnExit` temp file that vanishes after a process restart or cache wipe, so we must
 * be able to fall back to the container path and fetch it again.
 */
@Immutable
data class ImageViewerRequest(
    val sources: List<ImageSource>,
    /** Used for the accessibility description and failure hint, usually the file name; falls back to "Image preview" when empty. */
    val title: String = ""
)

/** Handle for opening the fullscreen viewer: callers just call show and don't care about loading or gestures. */
@Stable
fun interface ImageViewer {
    fun show(request: ImageViewerRequest)
}

/**
 * The default is a no-op: tapping an image in a composition tree without an [ImageViewerHost]
 * silently does nothing rather than crashing.
 *
 * Same approach as `LocalMarkdownImageTransformer` — a CompositionLocal instead of threading
 * callback parameters, otherwise intermediate layers like `AgentMessageItem` /
 * `ChatInputBar` would need signature changes just to forward one lambda.
 */
val LocalImageViewer = staticCompositionLocalOf<ImageViewer> { ImageViewer { } }

@Stable
class ImageViewerState : ImageViewer {
    var request by mutableStateOf<ImageViewerRequest?>(null)
        private set

    override fun show(request: ImageViewerRequest) {
        this.request = request
    }

    fun dismiss() {
        request = null
    }
}

@Composable
fun rememberImageViewerState(): ImageViewerState = remember { ImageViewerState() }

private sealed interface ViewerLoad {
    object Loading : ViewerLoad
    object Failed : ViewerLoad
    data class Ready(val bitmap: ImageBitmap) : ViewerLoad
}

/**
 * Fullscreen image viewer host. Mount it anywhere in the composition — the [Dialog] is a
 * separate window and takes no space in the parent layout.
 *
 * Why a Dialog instead of an in-composition overlay Box — three hard reasons:
 * 1. `MainActivity` draws the global background watermark **after** `AppNavigation()`, so an
 *    overlay would sit beneath that watermark over the big image;
 * 2. In the two-pane tablet layout the chat panel spans only half the screen; an overlay can
 *    only cover the chat column, not the whole screen;
 * 3. Dialogs close on back by default and their window has focus, naturally outranking the
 *    existing "collapse sidebar / collapse right pane" BackHandlers — no need to reason
 *    about their relative ordering.
 *
 * @param load Decodes an [ImageSource] into a bitmap; the implementation switches to the IO
 * thread itself, and returning null means this source is unavailable.
 */
@Composable
fun ImageViewerHost(
    state: ImageViewerState,
    load: suspend (ImageSource) -> ImageBitmap?
) {
    val request = state.request ?: return
    val loadState by produceState<ViewerLoad>(ViewerLoad.Loading, request, load) {
        value = request.sources.firstNotNullOfOrNull { load(it) }
            ?.let { ViewerLoad.Ready(it) }
            ?: ViewerLoad.Failed
    }

    Dialog(
        onDismissRequest = state::dismiss,
        properties = DialogProperties(
            // Without this the dialog window is WRAP_CONTENT and the content won't fill the screen
            usePlatformDefaultWidth = false,
            // Without this the content is squeezed into the safe area by system bar insets; the
            // black backdrop breaks off at the status bar, revealing the chat UI behind
            decorFitsSystemWindows = false
        )
    ) {
        // In light themes the system bar icons are dark and invisible on the black backdrop. The
        // dialog has its own window; adjust its icon colors separately.
        val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
        SideEffect {
            dialogWindow?.let { window ->
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = false
                    isAppearanceLightNavigationBars = false
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                // The media viewer backdrop deliberately ignores the theme: it should be near-black
                // even in light mode; making it a semantic color would be wrong
                .background(Color.Black.copy(alpha = BACKDROP_ALPHA))
        ) {
            when (val loaded = loadState) {
                ViewerLoad.Loading -> CircularProgressIndicator(
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )

                ViewerLoad.Failed -> LoadFailedPlaceholder(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .clickable(onClick = state::dismiss)
                        .padding(Spacing.lg)
                )

                is ViewerLoad.Ready -> ZoomableImage(
                    bitmap = loaded.bitmap,
                    contentDescription = request.title.ifBlank { stringResource(R.string.common_image_preview) },
                    onTap = state::dismiss,
                    modifier = Modifier.fillMaxSize()
                )
            }

            IconButton(
                onClick = state::dismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    // With decorFitsSystemWindows = false we must dodge the system bars ourselves
                    .systemBarsPadding()
                    .padding(Spacing.sm)
            ) {
                Icon(
                    imageVector = FeatherIcons.X,
                    contentDescription = stringResource(R.string.common_close),
                    tint = Color.White
                )
            }
        }
    }
}

@Composable
private fun LoadFailedPlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Icon(
            imageVector = FeatherIcons.Image,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.size(40.dp)
        )
        Text(
            text = stringResource(R.string.common_image_load_failed),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.8f)
        )
    }
}

/** Backdrop opacity. Compose's Dialog already adds a dim layer to the window, so it doesn't need to be fully opaque. */
private const val BACKDROP_ALPHA = 0.92f

private const val MIN_SCALE = 1f

/**
 * Lower bound of the zoom ceiling. Extra-long images are already tiny once fitted to the
 * screen, and 5x alone can't reveal any detail, so the actual ceiling is separately set to
 * "can zoom to 1:1 decoded pixels" — see maxScale in [ZoomableImage].
 */
private const val MAX_SCALE_FLOOR = 5f

private const val DOUBLE_TAP_SCALE = 2.5f
private const val ZOOM_ANIM_MS = 220

/** Float comparison tolerance: for judging whether we're still at original size. */
private const val SCALE_EPS = 0.01f

/**
 * The actual drawn size of the image after fitting into the container with [ContentScale.Fit].
 *
 * Pan bounds must be computed from this size, not the container size, or the image could be
 * dragged into the black bars on either side.
 */
fun fitInside(image: IntSize, container: IntSize): Size {
    if (image.width <= 0 || image.height <= 0 || container.width <= 0 || container.height <= 0) {
        return Size.Zero
    }
    val ratio = minOf(
        container.width.toFloat() / image.width,
        container.height.toFloat() / image.height
    )
    return Size(image.width * ratio, image.height * ratio)
}

/** Max pan on one axis: the overflow past the container after zooming, split in half. Zero when there's no overflow, pinning the image dead-center and eliminating drift. */
fun maxPan(contentPx: Float, containerPx: Float, scale: Float): Float =
    ((contentPx * scale - containerPx) / 2f).coerceAtLeast(0f)

/** Clamps the pan back into the visible range. */
fun clampPan(offset: Offset, content: Size, container: IntSize, scale: Float): Offset {
    val limitX = maxPan(content.width, container.width.toFloat(), scale)
    val limitY = maxPan(content.height, container.height.toFloat(), scale)
    return Offset(offset.x.coerceIn(-limitX, limitX), offset.y.coerceIn(-limitY, limitY))
}

/**
 * The new pan after zooming anchored at [centroid] (container coordinates): the pixel under
 * the finger stays put while pinching.
 *
 * [graphicsLayer] defaults transformOrigin to the center, so everything is relative to the
 * container center [containerCenter].
 * Let v = centroid − containerCenter; the screen position of content point p is
 * `p·scale + offset`; requiring the content point at v to land at v + pan after the gesture
 * solves to
 * `newOffset = v + pan − (v − oldOffset)·(newScale / oldScale)`.
 *
 * Two degenerate cases serve as checks: pure translation (newScale == oldScale) yields
 * `oldOffset + pan`; pure center zoom (v = 0, pan = 0) yields `oldOffset · k`.
 */
fun zoomAround(
    centroid: Offset,
    containerCenter: Offset,
    oldScale: Float,
    newScale: Float,
    oldOffset: Offset,
    pan: Offset
): Offset {
    if (oldScale <= 0f) return oldOffset
    val v = centroid - containerCenter
    return v + pan - (v - oldOffset) * (newScale / oldScale)
}

/**
 * Image with two-finger pinch zoom + one-finger drag when zoomed + double-tap toggling
 * between original size and zoomed.
 *
 * Scale and pan go entirely through [graphicsLayer] with no re-layout; bounds are clamped by
 * [clampPan] against the actual drawn size after Fit, so releasing never drifts and the image
 * can't be dragged into the black bars.
 *
 * @param onTap Single-tap callback. Fires only when not zoomed — an accidental tap closing
 * the viewer while zoomed into details would be far too easy.
 */
@Composable
fun ZoomableImage(
    bitmap: ImageBitmap,
    contentDescription: String?,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    var container by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableFloatStateOf(MIN_SCALE) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var zoomAnim by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    val imageSize = remember(bitmap) { IntSize(bitmap.width, bitmap.height) }
    val content = remember(imageSize, container) { fitInside(imageSize, container) }
    val center = remember(container) { Offset(container.width / 2f, container.height / 2f) }
    // The ceiling is "back to 1:1 decoded pixels"; otherwise a long screenshot shrinks tiny
    // after Fit and even 5x can't make the text readable
    val maxScale = remember(imageSize, content) {
        if (content.width <= 0f) MAX_SCALE_FLOOR else maxOf(MAX_SCALE_FLOOR, imageSize.width / content.width)
    }

    // After rotating the screen or dragging the tablet split divider, the container size
    // changes and the old pan may be out of bounds. AndroidManifest's configChanges includes
    // orientation, so the Activity isn't recreated and scale/offset survive — we must re-clamp
    // here.
    // scale is deliberately excluded from the key: including it would recompose on every zoom,
    // wasting the benefit of graphicsLayer.
    LaunchedEffect(content, container) {
        offset = clampPan(offset, content, container, scale)
    }

    Box(
        modifier = modifier
            .clipToBounds()
            .onSizeChanged { container = it }
            // tap must come before transform: in the same modifier chain, later elements are
            // deeper, and the Main pass reaches them first. Letting transform consume first
            // means that once a pinch crosses touchSlop, tap's waitForUpOrCancellation sees
            // isConsumed and gives up; with the order reversed, one pinch would close the
            // viewer.
            //
            // The keys must include imageSize / container: when pointerInput's keys are
            // unchanged it doesn't restart the running gesture coroutine, it only swaps the
            // lambda reference, so content / center / maxScale captured by value in the
            // callbacks would stay at stale sizes — after rotating, zoom would use the wrong
            // center and pan would be clamped to the wrong bounds.
            .pointerInput(imageSize, container) {
                detectTapGestures(
                    onTap = { if (scale <= MIN_SCALE + SCALE_EPS) onTap() },
                    onDoubleTap = { tap ->
                        val from = scale
                        val to = if (from > MIN_SCALE + SCALE_EPS) {
                            MIN_SCALE
                        } else {
                            minOf(DOUBLE_TAP_SCALE, maxScale)
                        }
                        val fromOffset = offset
                        val toOffset = if (to <= MIN_SCALE + SCALE_EPS) {
                            Offset.Zero
                        } else {
                            clampPan(
                                zoomAround(tap, center, from, to, fromOffset, Offset.Zero),
                                content,
                                container,
                                to
                            )
                        }
                        zoomAnim?.cancel()
                        zoomAnim = scope.launch {
                            animate(0f, 1f, animationSpec = tween(ZOOM_ANIM_MS)) { fraction, _ ->
                                scale = from + (to - from) * fraction
                                offset = lerp(fromOffset, toOffset, fraction)
                            }
                        }
                    }
                )
            }
            .pointerInput(imageSize, container) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    zoomAnim?.cancel()
                    val from = scale
                    val to = (from * zoom).coerceIn(MIN_SCALE, maxScale)
                    val moved = zoomAround(centroid, center, from, to, offset, pan)
                    scale = to
                    offset = clampPan(moved, content, container, to)
                }
            }
    ) {
        ComposeImage(
            bitmap = bitmap,
            contentDescription = contentDescription,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offset.x
                    translationY = offset.y
                }
        )
    }
}
