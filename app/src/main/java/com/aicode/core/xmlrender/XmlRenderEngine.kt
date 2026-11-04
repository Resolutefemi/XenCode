package com.aicode.core.xmlrender

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import com.aicode.core.util.FileLogger
import com.aicode.feature.workspace.domain.FileAccessProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

enum class RenderStatus { SUCCESS, APPROXIMATE, FAILED }

/**
 * Outcome of one render: the bitmap (null when [RenderStatus.FAILED]), the effective pixel
 * size, and the diagnostics that explain any approximation. The tool output surfaces these
 * so the model and the UI can tell "faithful" from "approximate" renders.
 */
data class RenderOutcome(
    val status: RenderStatus,
    val bitmap: Bitmap?,
    val widthPx: Int,
    val heightPx: Int,
    val warnings: List<String>,
    val errorMessage: String? = null
)

/**
 * Facade for rendering one Android layout file into a bitmap.
 *
 * Pipeline: read layout XML + build the [ProjectResourceIndex] on IO, parse to a
 * [LayoutNode] tree (pure), preheat drawables off the main thread, inflate views and
 * rasterize on the main thread, then the caller encodes the PNG wherever it needs it.
 * Any thrown error lands in [RenderOutcome.errorMessage]; a render that only produces
 * warnings (missing resources, unsupported tags) is [RenderStatus.APPROXIMATE].
 */
object XmlRenderEngine {

    const val DEFAULT_WIDTH_DP = 360
    const val DEFAULT_HEIGHT_DP = 640
    const val MIN_SIZE_DP = 100
    const val MAX_SIZE_DP = 1600
    const val MAX_RASTER_PX = 4096

    private const val TAG = "XmlRenderEngine"

    /**
     * The resource root is the source-set owner directory: the path segment right above
     * the outermost `res/` folder (e.g. ".../app/src/main" for ".../app/src/main/res/layout/a.xml").
     */
    fun appRootFor(layoutPath: String): String? {
        val normalized = layoutPath.replace('\\', '/')
        val marker = normalized.lastIndexOf("/res/")
        if (marker <= 0) return null
        return normalized.substring(0, marker)
    }

    suspend fun render(
        context: Context,
        fileAccess: FileAccessProvider,
        layoutPath: String,
        widthDp: Int = DEFAULT_WIDTH_DP,
        heightDp: Int = DEFAULT_HEIGHT_DP,
        dark: Boolean = false,
        /** Renders this XML body instead of re-reading [layoutPath] (editor live preview). */
        xmlOverride: String? = null
    ): RenderOutcome {
        val warnings = WarningsCollector()
        val density = context.resources.displayMetrics.density
        val widthPx = (widthDp.coerceIn(MIN_SIZE_DP, MAX_SIZE_DP) * density).toInt()
            .coerceAtMost(MAX_RASTER_PX)
        val heightPx = (heightDp.coerceIn(MIN_SIZE_DP, MAX_SIZE_DP) * density).toInt()
            .coerceAtMost(MAX_RASTER_PX)
        return try {
            val appRoot = appRootFor(layoutPath)
                ?: return RenderOutcome(
                    status = RenderStatus.FAILED,
                    bitmap = null,
                    widthPx = widthPx,
                    heightPx = heightPx,
                    warnings = warnings.snapshot(),
                    errorMessage = "Layout path is not under a res/ folder: $layoutPath"
                )
            val xml = withContext(Dispatchers.IO) {
                xmlOverride ?: fileAccess.readFile(layoutPath)
            }
            val index = withContext(Dispatchers.IO) {
                ProjectResourceIndex.build(fileAccess, appRoot, dark)
            }
            for (w in index.buildWarnings) warnings.add(w)

            val layoutName = layoutPath.substringAfterLast('/').removeSuffix(".xml")
            val root = XmlLayoutParser().parse(xml, layoutPath) { name ->
                if (name == layoutName) xmlOverride ?: xml else index.layout(name)
            }

            val renderer = XmlViewRenderer(context, fileAccess, index, dark, warnings)
            renderer.preheat(root)
            val host = withContext(Dispatchers.Main) {
                renderer.renderHost(root, widthPx, heightPx)
            }
            val bitmap = withContext(Dispatchers.Main) {
                Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888).also { out ->
                    val canvas = Canvas(out)
                    canvas.drawColor(if (dark) 0xFF121212.toInt() else 0xFFFFFFFF.toInt())
                    host.draw(canvas)
                }
            }
            val status = if (warnings.snapshot().isEmpty()) {
                RenderStatus.SUCCESS
            } else {
                RenderStatus.APPROXIMATE
            }
            RenderOutcome(status, bitmap, widthPx, heightPx, warnings.snapshot())
        } catch (e: Exception) {
            FileLogger.e(TAG, "render failed: $layoutPath", e)
            RenderOutcome(
                status = RenderStatus.FAILED,
                bitmap = null,
                widthPx = widthPx,
                heightPx = heightPx,
                warnings = warnings.snapshot(),
                errorMessage = e.message ?: e.javaClass.simpleName
            )
        }
    }

    /** Encodes the bitmap as PNG and writes it through [fileAccess]; parents auto-created. */
    suspend fun savePng(fileAccess: FileAccessProvider, path: String, bitmap: Bitmap): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = ByteArrayOutputStream().use { buffer ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, buffer)
                    buffer.toByteArray()
                }
                fileAccess.writeBytes(path, bytes)
            }.onFailure { FileLogger.e(TAG, "savePng failed: $path", it) }.isSuccess
        }
}
