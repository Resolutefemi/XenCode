package com.aicode.core.xmlrender

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import androidx.core.graphics.PathParser
import com.aicode.feature.workspace.domain.FileAccessProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xmlpull.v1.XmlPullParser
import kotlin.math.roundToInt

/**
 * Miniature drawable factory for the XML layout renderer.
 *
 * Rebuilds the subset of drawable XML the preview needs without instantiating arbitrary
 * project classes: rasters via [BitmapFactory] (through [FileAccessProvider.copyToLocal],
 * so remote workspaces work too), vector drawables by re-drawing their paths into a
 * bitmap, shapes as [GradientDrawable], layer-lists as real stacked [LayerDrawable]s with
 * per-layer insets, and selectors/ripples by picking their default layer. Anything
 * unsupported degrades to a neutral placeholder plus a warning — rendering never aborts
 * because one drawable is missing.
 */
class MiniDrawables(
    private val context: Context,
    private val fileAccess: FileAccessProvider,
    private val index: ProjectResourceIndex,
    private val dark: Boolean,
    private val warnings: WarningsCollector
) {

    private val density = context.resources.displayMetrics.density

    /**
     * Resolves a background/src token into a Drawable, or null when unsupported
     * (a warning is recorded). Must run off the main thread.
     */
    suspend fun resolve(token: String): Drawable? {
        val value = token.trim()
        return when {
            value.startsWith("@drawable/") || value.startsWith("@mipmap/") -> {
                val name = value.substringAfterLast('/')
                val path = index.drawablePath(name)
                when {
                    path == null -> {
                        warnings.add("Resource not found: $value")
                        null
                    }
                    else -> loadFromPath(path, value)
                }
            }
            value.startsWith("@android:drawable/") -> frameworkDrawable(value)
            value.startsWith("@color/") -> index.color(value.removePrefix("@color/"))
                ?.let { ResourceValues.parseColor(it)?.let(::ColorDrawable) }
                ?: run {
                    warnings.add("Color resource not found: $value")
                    null
                }
            value.startsWith("@android:color/") -> ProjectResourceIndex.ANDROID_COLORS[
                value.removePrefix("@android:color/")
            ]?.let { ResourceValues.parseColor(it)?.let(::ColorDrawable) }
            value.startsWith("#") -> ResourceValues.parseColor(value)?.let(::ColorDrawable)
            value.startsWith("?") -> themeColor(value)?.let(::ColorDrawable)
            else -> {
                warnings.add("Unsupported background value: $value")
                null
            }
        }
    }

    /**
     * Synchronous resolver for tokens that never touch the filesystem: colors,
     * theme attributes and framework drawables. Called during view building (main thread);
     * file-backed tokens ("@drawable/..") were resolved earlier via [resolve].
     */
    fun resolveSync(tokenRaw: String): Drawable? {
        val value = tokenRaw.trim()
        return when {
            value.startsWith("@android:drawable/") -> frameworkDrawable(value)
            value.startsWith("@drawable/") || value.startsWith("@mipmap/") -> null
            value.startsWith("@color/") -> index.color(value.removePrefix("@color/"))
                ?.let { ResourceValues.parseColor(it)?.let(::ColorDrawable) }
            value.startsWith("@android:color/") -> ProjectResourceIndex.ANDROID_COLORS[
                value.removePrefix("@android:color/")
            ]?.let { ResourceValues.parseColor(it)?.let(::ColorDrawable) }
            value.startsWith("#") -> ResourceValues.parseColor(value)?.let(::ColorDrawable)
            value.startsWith("?") -> themeColor(value)?.let(::ColorDrawable)
            else -> null
        }
    }

    /** Resolves "?attr/x" against a fixed Material palette (works without app modules). */
    fun themeColor(token: String): Int? {
        val key = token.removePrefix("?").removePrefix("attr/").substringAfterLast('/').lowercase()
        return SINGLE[key] ?: PAIRS[key]?.let { pair -> if (dark) pair.second else pair.first }
    }

    private suspend fun loadFromPath(path: String, displayToken: String): Drawable? =
        withContext(Dispatchers.IO) {
            val local = runCatching { fileAccess.copyToLocal(path) }.getOrNull()
            if (local == null) {
                warnings.add("Cannot fetch drawable file: $displayToken")
                return@withContext null
            }
            when (path.substringAfterLast('.').lowercase()) {
                "png", "jpg", "jpeg", "webp", "bmp", "gif" ->
                    decodeBounded(local.absolutePath)?.let { BitmapDrawable(context.resources, it) }
                        ?: run {
                            warnings.add("Cannot decode raster: $displayToken")
                            null
                        }
                "xml" -> {
                    val body = runCatching { fileAccess.readFile(path) }.getOrNull()
                    if (body == null) {
                        warnings.add("Cannot read drawable XML: $displayToken")
                        null
                    } else {
                        runCatching { buildFromXml(body, displayToken) }.getOrNull()
                            ?: null.also { warnings.add("Unsupported drawable XML: $displayToken") }
                    }
                }
                else -> null
            }
        }

    private fun frameworkDrawable(token: String): Drawable? {
        val id = context.resources.getIdentifier(
            token.removePrefix("@android:drawable/"), "drawable", "android"
        )
        if (id == 0) {
            warnings.add("Unknown framework drawable: $token")
            return null
        }
        return runCatching { context.getDrawable(id) }.getOrNull()
            ?: run {
                warnings.add("Framework drawable unavailable: $token")
                null
            }
    }

    private fun decodeBounded(path: String): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (sample <= 16 &&
            bounds.outWidth / (sample * 2) >= MAX_RASTER_EDGE &&
            bounds.outHeight / (sample * 2) >= MAX_RASTER_EDGE
        ) {
            sample *= 2
        }
        return BitmapFactory.decodeFile(
            path,
            BitmapFactory.Options().apply { inSampleSize = sample }
        )
    }

    private suspend fun buildFromXml(body: String, displayToken: String): Drawable? {
        val parser = ProjectResourceIndex.newPullParser(body)
        while (parser.eventType != XmlPullParser.START_TAG) {
            if (parser.next() == XmlPullParser.END_DOCUMENT) return null
        }
        return dispatchElement(parser, parser.name, displayToken, depth = 0)
    }

    /**
     * Builds one drawable element; the parser must be sitting on its START_TAG, and the
     * element is consumed exactly through its END_TAG. Shared by the document root and
     * inline drawable children of layer-list/selector items.
     */
    private suspend fun dispatchElement(
        parser: XmlPullParser,
        name: String,
        displayToken: String,
        depth: Int
    ): Drawable? {
        if (depth > MAX_NESTED_DRAWABLE_DEPTH) {
            warnings.add("Drawable nesting too deep: $displayToken")
            return null
        }
        return when (name) {
            "vector" -> vectorDrawable(parser)
            "shape" -> shapeDrawable(parser)
            "layer-list" -> layerList(parser, displayToken, depth)
            "selector", "ripple" -> selectorLayer(parser, displayToken, depth)
            "inset" -> insetDrawable(parser, displayToken, depth)
            else -> {
                warnings.add("Unsupported drawable XML <$name>: $displayToken")
                skipElement(parser)
                null
            }
        }
    }

    /** Consumes through the END_TAG of the element the parser currently sits on. */
    private fun skipElement(parser: XmlPullParser) {
        var pending = 1
        while (pending > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> pending++
                XmlPullParser.END_TAG -> pending--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /**
     * Resolves one <item> layer: either its android:drawable reference / android:color,
     * or the first inline drawable element it nests. The parser enters on the item's
     * START_TAG and consumes the item element COMPLETELY, including its (synthetic or
     * explicit) END_TAG. Callers must count the item's START without expecting to read
     * its END themselves.
     */
    private suspend fun itemDrawable(
        parser: XmlPullParser,
        displayToken: String,
        depth: Int
    ): Drawable? {
        var ref: String? = null
        var color: Int? = null
        for (i in 0 until parser.attributeCount) {
            when (parser.getAttributeName(i)) {
                "drawable" -> ref = parser.getAttributeValue(i)
                "color" -> color = drawableColor(parser, "color")
            }
        }
        var event = parser.next()
        while (event == XmlPullParser.TEXT || event == XmlPullParser.COMMENT) {
            event = parser.next()
        }
        return when {
            event == XmlPullParser.START_TAG -> {
                val inline = dispatchElement(parser, parser.name, displayToken, depth + 1)
                drainToItemEnd(parser)
                inline
            }
            ref != null && ref.startsWith("@") -> resolve(ref)
            else -> if (ref != null) {
                // Non-resource reference inside an item has no representation here.
                warnings.add("Layer reference unsupported: $ref in $displayToken")
                null
            } else {
                color?.let(::ColorDrawable)
            }
        }
    }

    /**
     * Consumes events up to and including the enclosing <item> END_TAG. Used after an
     * inline child finished consuming its own element, leaving the item's END next.
     */
    private fun drainToItemEnd(parser: XmlPullParser) {
        var pending = 1
        while (pending > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> pending++
                XmlPullParser.END_TAG -> pending--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /** Draws a <vector>'s paths into a bitmap; no VectorDrawable dependency needed. */
    private fun vectorDrawable(parser: XmlPullParser): Drawable? {
        var widthPx = 0
        var heightPx = 0
        for (i in 0 until parser.attributeCount) {
            when (parser.getAttributeName(i)) {
                "width" -> widthPx =
                    ResourceValues.parseDimensionPx(parser.getAttributeValue(i), density, density) ?: 0
                "height" -> heightPx =
                    ResourceValues.parseDimensionPx(parser.getAttributeValue(i), density, density) ?: 0
            }
        }
        if (widthPx <= 0 || heightPx <= 0) {
            widthPx = (24 * density).roundToInt()
            heightPx = (24 * density).roundToInt()
        }
        widthPx = widthPx.coerceIn(1, MAX_RASTER_EDGE)
        heightPx = heightPx.coerceIn(1, MAX_RASTER_EDGE)

        val paths = mutableListOf<PathSpec>()
        var current: PathSpec? = null
        var pending = 1
        while (pending > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    pending++
                    if (parser.name == "path") {
                        current = PathSpec()
                        for (i in 0 until parser.attributeCount) {
                            when (parser.getAttributeName(i)) {
                                "pathData" -> current!!.pathData = parser.getAttributeValue(i)
                                "fillColor" -> current!!.fillColor = parser.getAttributeValue(i)
                                "strokeColor" -> current!!.strokeColor = parser.getAttributeValue(i)
                                "strokeWidth" ->
                                    current!!.strokeWidth = parser.getAttributeValue(i)?.toFloatOrNull() ?: 0f
                            }
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    pending--
                    if (parser.name == "path") {
                        current?.let { if (it.pathData != null) paths += it }
                        current = null
                    }
                }
                XmlPullParser.END_DOCUMENT -> return null
            }
        }
        if (paths.isEmpty()) return null

        val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        // Vectors are authored on a 24x24 viewport; scale path coordinates to the bitmap.
        val matrix = android.graphics.Matrix().apply {
            postScale(widthPx / VIEWPORT, heightPx / VIEWPORT)
        }
        for (spec in paths) {
            val path = runCatching {
                spec.pathData?.let { PathParser.createPathFromPathData(it) }
            }.getOrNull() ?: continue
            path.transform(matrix)
            val fill = spec.fillColor?.let { ResourceValues.parseColor(it) } ?: Color.BLACK
            if (Color.alpha(fill) != 0) {
                canvas.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill })
            }
            val stroke = spec.strokeColor?.let { ResourceValues.parseColor(it) }
            if (stroke != null && Color.alpha(stroke) != 0 && spec.strokeWidth > 0f) {
                canvas.drawPath(
                    path,
                    Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        style = Paint.Style.STROKE
                        color = stroke
                        strokeWidth = spec.strokeWidth
                        isDither = true
                    }
                )
            }
        }
        return BitmapDrawable(context.resources, bitmap)
    }

    private class PathSpec {
        var pathData: String? = null
        var fillColor: String? = null
        var strokeColor: String? = null
        var strokeWidth: Float = 0f
    }

    private fun shapeDrawable(parser: XmlPullParser): Drawable {
        val drawable = GradientDrawable()
        drawable.shape = when (attrOf(parser, "shape")) {
            "oval" -> GradientDrawable.OVAL
            "line" -> GradientDrawable.LINE
            "ring" -> GradientDrawable.RING
            else -> GradientDrawable.RECTANGLE
        }
        var pending = 1
        while (pending > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    pending++
                    when (parser.name) {
                        "solid" -> drawableColor(parser, "color")?.let { drawable.setColor(it) }
                        "corners" -> {
                            dimAttr(parser, "radius")?.let { drawable.cornerRadius = it }
                            cornerRadii(parser)?.let { drawable.setCornerRadii(it) }
                        }
                        "stroke" -> {
                            val width = (dimAttr(parser, "width") ?: 0f).roundToInt()
                            val color = drawableColor(parser, "color")
                            val dash = dimAttr(parser, "dashWidth")
                            val gap = dimAttr(parser, "dashGap")
                            when {
                                color == null -> Unit
                                dash != null && dash > 0f && gap != null ->
                                    drawable.setStroke(width, color, dash, gap)
                                else -> drawable.setStroke(width, color)
                            }
                        }
                        "gradient" -> applyGradient(parser, drawable)
                        "size" -> {
                            val width = dimAttr(parser, "width")?.roundToInt() ?: 0
                            val height = dimAttr(parser, "height")?.roundToInt() ?: 0
                            if (width > 0 && height > 0) drawable.setSize(width, height)
                        }
                    }
                }
                XmlPullParser.END_TAG -> pending--
                XmlPullParser.END_DOCUMENT -> return drawable
            }
        }
        return drawable
    }

    private fun applyGradient(parser: XmlPullParser, drawable: GradientDrawable) {
        val start = drawableColor(parser, "startColor") ?: drawableColor(parser, "color") ?: DEFAULT_GRADIENT
        val middle = drawableColor(parser, "centerColor")
        val end = drawableColor(parser, "endColor") ?: start
        when {
            middle != null -> drawable.setColors(intArrayOf(start, middle, end))
            else -> drawable.setColors(intArrayOf(start, end))
        }
        drawable.setOrientation(gradientOrientation(angleAttr(parser)))
        when (attrOf(parser, "type")) {
            "radial" -> {
                drawable.setGradientType(GradientDrawable.RADIAL_GRADIENT)
                val radius = dimAttr(parser, "gradientRadius")
                drawable.setGradientRadius(radius ?: (24 * density))
            }
            "sweep" -> drawable.setGradientType(GradientDrawable.SWEEP_GRADIENT)
        }
        val centerX = attrOf(parser, "centerX")?.toFloatOrNull()
        val centerY = attrOf(parser, "centerY")?.toFloatOrNull()
        if (centerX != null || centerY != null) {
            drawable.setGradientCenter(centerX ?: 0.5f, centerY ?: 0.5f)
        }
    }

    private fun gradientOrientation(angle: Int) = when (angle) {
        in 45..135 -> GradientDrawable.Orientation.BL_TR
        in 136..225 -> GradientDrawable.Orientation.RIGHT_LEFT
        in 226..315 -> GradientDrawable.Orientation.TR_BL
        else -> GradientDrawable.Orientation.TOP_BOTTOM
    }

    private fun cornerRadii(parser: XmlPullParser): FloatArray? {
        val tl = dimAttr(parser, "topLeftRadius")
        val tr = dimAttr(parser, "topRightRadius")
        val bl = dimAttr(parser, "bottomLeftRadius")
        val br = dimAttr(parser, "bottomRightRadius")
        if (tl == null && tr == null && bl == null && br == null) return null
        val topLeft = tl ?: 0f
        return floatArrayOf(
            topLeft, topLeft,
            tr ?: 0f, tr ?: 0f,
            br ?: 0f, br ?: 0f,
            bl ?: 0f, bl ?: 0f
        )
    }

    /**
     * Full-fidelity layer-list: every item becomes a real layer in a [LayerDrawable],
     * first item bottom-most like on Android, honoring per-item inset offsets.
     */
    private suspend fun layerList(
        parser: XmlPullParser,
        displayToken: String,
        depth: Int
    ): Drawable? {
        val layers = mutableListOf<Drawable>()
        val insets = mutableListOf<IntArray>()
        var pending = 1
        while (pending > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "item" && pending == 1) {
                        // itemDrawable consumes the whole item element (attrs, optional
                        // inline child, and its END_TAG), so depth stays balanced: the
                        // item's START is accounted for here, its END never reaches us.
                        insets += itemInsets(parser)
                        val layer = itemDrawable(parser, displayToken, depth)
                        if (layer != null) layers += layer else insets.removeAt(insets.lastIndex)
                    } else {
                        pending++
                    }
                }
                XmlPullParser.END_TAG -> pending--
                XmlPullParser.END_DOCUMENT -> pending = 0
            }
        }
        if (layers.isEmpty()) {
            warnings.add("Layer-list has no drawable layer: $displayToken")
            return null
        }
        val drawable = LayerDrawable(layers.toTypedArray())
        for (i in layers.indices) {
            val inset = insets.getOrNull(i) ?: continue
            drawable.setLayerInset(i, inset[0], inset[1], inset[2], inset[3])
        }
        return drawable
    }

    /** Per-item inset offsets (LTR mapping of left/start, right/end). */
    private fun itemInsets(parser: XmlPullParser): IntArray {
        val left = dimAttr(parser, "left") ?: dimAttr(parser, "start") ?: 0f
        val right = dimAttr(parser, "right") ?: dimAttr(parser, "end") ?: 0f
        val top = dimAttr(parser, "top") ?: 0f
        val bottom = dimAttr(parser, "bottom") ?: 0f
        return intArrayOf(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())
    }

    /**
     * Selectors render their default (state-less) layer, falling back to the first
     * concrete layer; ripples degrade identically (static snapshot).
     */
    private suspend fun selectorLayer(
        parser: XmlPullParser,
        displayToken: String,
        depth: Int
    ): Drawable? {
        val layers = mutableListOf<Drawable>()
        var pending = 1
        while (pending > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "item" && pending == 1) {
                        // itemDrawable owns the item's full element, including its END.
                        itemDrawable(parser, displayToken, depth)?.let { layers += it }
                    } else {
                        pending++
                    }
                }
                XmlPullParser.END_TAG -> pending--
                XmlPullParser.END_DOCUMENT -> pending = 0
            }
        }
        val default = layers.firstOrNull()
        if (default == null) {
            warnings.add("Selector without a drawable layer: $displayToken")
        }
        return default
    }

    /** Inset drawables wrap their inner drawable; offsets honored via real InsetDrawable. */
    private suspend fun insetDrawable(
        parser: XmlPullParser,
        displayToken: String,
        depth: Int
    ): Drawable? {
        val inset = intArrayOf(
            (dimAttr(parser, "insetLeft") ?: dimAttr(parser, "insetStart") ?: 0f).roundToInt(),
            dimAttr(parser, "insetTop")?.roundToInt() ?: 0,
            (dimAttr(parser, "insetRight") ?: dimAttr(parser, "insetEnd") ?: 0f).roundToInt(),
            (dimAttr(parser, "insetBottom") ?: dimAttr(parser, "insetTop") ?: 0f).roundToInt()
        )
        var ref: String? = null
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i) == "drawable") ref = parser.getAttributeValue(i)
        }
        var inline: Drawable? = null
        var inlineSeen = false
        var pending = 1
        while (pending > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> {
                    if (!inlineSeen) {
                        inlineSeen = true
                        // dispatchElement consumes the child element completely, so it is
                        // depth-neutral here; only the inset's own END moves pending.
                        inline = dispatchElement(parser, parser.name, displayToken, depth + 1)
                    } else {
                        skipElement(parser)
                    }
                }
                XmlPullParser.END_TAG -> pending--
                XmlPullParser.END_DOCUMENT -> pending = 0
            }
        }
        if (inline == null && ref != null) inline = resolve(ref)
        return inline?.let { InsetDrawable(it, inset[0], inset[1], inset[2], inset[3]) }
            ?: null.also { warnings.add("Empty inset drawable: $displayToken") }
    }

    private fun attrOf(parser: XmlPullParser, name: String): String? {
        for (i in 0 until parser.attributeCount) {
            if (parser.getAttributeName(i) == name) return parser.getAttributeValue(i)
        }
        return null
    }

    private fun dimAttr(parser: XmlPullParser, name: String): Float? {
        val raw = attrOf(parser, name) ?: return null
        return ResourceValues.parseDimensionPx(raw, density, density)?.toFloat()
    }

    /** Resolves a color attr that may be "#hex", "@color/x", "@android:color/x" or "?attr/x". */
    private fun drawableColor(parser: XmlPullParser, name: String): Int? {
        val raw = attrOf(parser, name) ?: return null
        val value = raw.trim()
        return when {
            value.startsWith("#") -> ResourceValues.parseColor(value)
            value.startsWith("@color/") -> index.color(value.removePrefix("@color/"))
                ?.let { ResourceValues.parseColor(it) }
                ?: run {
                    warnings.add("Color resource not found: $value")
                    null
                }
            value.startsWith("@android:color/") -> ProjectResourceIndex.ANDROID_COLORS[
                value.removePrefix("@android:color/")
            ]?.let { ResourceValues.parseColor(it) }
            value.startsWith("?") -> themeColor(value)
            else -> {
                warnings.add("Unsupported color value in drawable: $value")
                null
            }
        }
    }

    private fun angleAttr(parser: XmlPullParser): Int = attrOf(parser, "angle")?.toIntOrNull() ?: 0

    private companion object {
        const val MAX_RASTER_EDGE = 2048
        const val MAX_NESTED_DRAWABLE_DEPTH = 6
        const val VIEWPORT = 24f
        val DEFAULT_GRADIENT = 0xFF9E9E9E.toInt()

        /** Theme attr names shared by both themes. */
        val SINGLE = mapOf(
            "colordivider" to 0x1F000000.toInt(),
            "colorcontrolnormal" to 0x8A000000.toInt()
        )

        /** Theme attr names with (light, dark) pairs, keyed lowercase. */
        val PAIRS = mapOf(
            "colorprimary" to (0xFF3F51B5.toInt() to 0xFF2196F3.toInt()),
            "colorprimarydark" to (0xFF303F9F.toInt() to 0xFF1976D2.toInt()),
            "colorprimaryvariant" to (0xFF303F9F.toInt() to 0xFF1976D2.toInt()),
            "coloronprimary" to (0xFFFFFFFF.toInt() to 0xFFFFFFFF.toInt()),
            "colorprimarycontainer" to (0xFFE8EAF6.toInt() to 0xFF3C4256.toInt()),
            "coloronprimarycontainer" to (0xFF1A237E.toInt() to 0xFFE8EAF6.toInt()),
            "colorsecondary" to (0xFF009688.toInt() to 0xFF4DB6AC.toInt()),
            "coloronsecondary" to (0xFFFFFFFF.toInt() to 0xFF00332E.toInt()),
            "colorsecondarycontainer" to (0xFFE0F2F1.toInt() to 0xFF00504A.toInt()),
            "coloronsecondarycontainer" to (0xFF00695C.toInt() to 0xFFB2DFDB.toInt()),
            "coloraccent" to (0xFFFF4081.toInt() to 0xFFFFAB40.toInt()),
            "colorsurface" to (0xFFFFFFFF.toInt() to 0xFF121212.toInt()),
            "coloronsurface" to (0xDE000000.toInt() to 0xFFFFFFFF.toInt()),
            "colorsurfacevariant" to (0xFFF2F3F5.toInt() to 0xFF2A2C2F.toInt()),
            "coloronsurfacevariant" to (0x8A000000.toInt() to 0xB3FFFFFF.toInt()),
            "coloroutline" to (0x61000000.toInt() to 0x8AFFFFFF.toInt()),
            "colorerror" to (0xFFB00020.toInt() to 0xFFCF6679.toInt()),
            "coloronerror" to (0xFFFFFFFF.toInt() to 0xFF220101.toInt()),
            "colorbuttonnormal" to (0xFFE0E0E0.toInt() to 0xFF5A5A5A.toInt()),
            "colorswitchthumbnormal" to (0xFFF1F1F1.toInt() to 0xFF5A5A5A.toInt()),
            "androidcolorprimary" to (0xFF3F51B5.toInt() to 0xFF2196F3.toInt()),
            "androidcoloraccent" to (0xFFFF4081.toInt() to 0xFFFFAB40.toInt()),
            "androidtextcolorprimary" to (0xDE000000.toInt() to 0xFFFFFFFF.toInt()),
            "androidtextcolorsecondary" to (0x8A000000.toInt() to 0xB3FFFFFF.toInt()),
            "androidtextcolortertiary" to (0x61000000.toInt() to 0x8AFFFFFF.toInt()),
            "androidtextcolorprimaryinverse" to (0xFFFFFFFF.toInt() to 0xFF000000.toInt()),
            "androidtextcolorhint" to (0x61000000.toInt() to 0x8AFFFFFF.toInt())
        )
    }
}

/** Shared cap on distinct warnings so pathological layouts don't flood tool output. */
class WarningsCollector {

    private val items = LinkedHashSet<String>()

    fun add(message: String) {
        if (items.size < MAX) items += message
    }

    fun snapshot(): List<String> = items.toList()

    private companion object {
        const val MAX = 50
    }
}
