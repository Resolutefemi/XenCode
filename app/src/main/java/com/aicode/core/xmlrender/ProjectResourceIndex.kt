package com.aicode.core.xmlrender

import com.aicode.feature.workspace.domain.FileAccessProvider
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Resource index for one Android source set, resolved against [FileAccessProvider]
 * (works for local containers and remote SSH workspaces alike).
 *
 * Only what the renderer needs is indexed: colors, strings, dimens, integers, booleans,
 * styles (view-relevant items), layout XML bodies (for include inlining), and
 * drawable/mipmap paths picked per theme (dark prefers -night variants). Qualifier
 * variants other than -night are collapsed (base folders scanned first, later wins) —
 * an accepted approximation for preview rendering.
 *
 * Values are read from all `values*` resource folders (skipping `-night` for light renders).
 * Latest file wins, mirroring aapt's later-resource-precedence closely enough for previews.
 *
 * Construction does blocking IO; callers dispatch to an IO dispatcher.
 */
class ProjectResourceIndex private constructor(
    /** Resource root owner directory, e.g. "~/workspace/app/src/main". */
    val appRoot: String,
    private val colorMap: Map<String, String>,
    private val stringMap: Map<String, String>,
    private val dimenMap: Map<String, String>,
    private val integerMap: Map<String, String>,
    private val boolMap: Map<String, String>,
    val styles: Map<String, StyleDef>,
    /** Layout name (without extension) -> XML body. */
    val layouts: Map<String, String>,
    /** Drawable/mipmap name -> workspace path, theme-preferred. */
    private val drawables: Map<String, String>,
    /** Diagnostics collected while building (missing res/ dir, unreadable files). */
    val buildWarnings: List<String>
) {

    data class StyleDef(
        val name: String,
        val parent: String?,
        /** Item name (prefixed, e.g. "android:textSize") -> raw value. */
        val items: Map<String, String>
    )

    /** Resolved color value for "@color/name", or null when unknown. */
    fun color(name: String): String? = chase(colorMap[name])
    fun string(name: String): String? = chase(stringMap[name])
    fun dimen(name: String): String? = chase(dimenMap[name])
    fun integer(name: String): Int? = chase(integerMap[name])?.trim()?.toIntOrNull()
    fun boolean(name: String): Boolean? = chase(boolMap[name])?.trim()?.toBooleanStrictOrNull()

    /** Style lookup by bare name or "@style/name". */
    fun style(ref: String): StyleDef? = styles[ref.removePrefix("@")]

    /** Workspace path for @drawable/@mipmap name, or null when missing. */
    fun drawablePath(name: String): String? = drawables[name]

    /** Body of `@layout/name`, or null. */
    fun layout(name: String): String? = layouts[name]

    /**
     * Chases "@color/" / "@string/" / "@dimen/" reference chains until a literal value is
     * reached. Unknown references abort the chase and return null.
     */
    private fun chase(raw: String?): String? {
        var value = raw?.trim() ?: return null
        var depth = 0
        while (depth++ < MAX_REF_DEPTH) {
            val next: String? = when {
                value.startsWith("@color/") -> colorMap[value.removePrefix("@color/")]
                value.startsWith("@string/") -> stringMap[value.removePrefix("@string/")]
                value.startsWith("@dimen/") -> dimenMap[value.removePrefix("@dimen/")]
                else -> return value
            }
            value = next?.trim() ?: return null
        }
        return null
    }

    private class Builder(
        private val fileAccess: FileAccessProvider,
        private val dark: Boolean
    ) {
        val warnings = mutableListOf<String>()
        val colors = linkedMapOf<String, String>()
        val strings = linkedMapOf<String, String>()
        val dimens = linkedMapOf<String, String>()
        val integers = linkedMapOf<String, String>()
        val bools = linkedMapOf<String, String>()
        val styles = linkedMapOf<String, StyleDef>()
        val layoutBodies = linkedMapOf<String, String>()
        val drawablePaths = linkedMapOf<String, MutableList<Pair<String, Boolean>>>()

        fun scan(resDir: String) {
            if (!fileAccess.exists(resDir) || !fileAccess.isDirectory(resDir)) {
                warnings += "Resource directory not found: $resDir"
                return
            }
            val subdirs = runCatching { fileAccess.listFiles(resDir) }.getOrElse {
                warnings += "Cannot list $resDir: ${it.message}"
                emptyList()
            }
            // Sorted so base folders are scanned before qualifier variants (e.g. values
            // before values-night): later scans win, mirroring aapt's precedence regardless
            // of the filesystem's listing order.
            for (dir in subdirs.filter { it.isDirectory }.map { it.name }.sorted()) {
                val dirPath = "$resDir/$dir"
                when {
                    dir.startsWith("values") -> {
                        // values-night only contributes when rendering with a dark theme.
                        if (!dir.startsWith("values-night") || dark) scanValuesDir(dirPath)
                    }
                    dir.startsWith("layout") -> scanLayoutDir(dirPath)
                    dir.startsWith("drawable") || dir.startsWith("mipmap") ->
                        scanDrawableDir(dirPath, isNight = dir.contains("night"))
                    dir == "color" || dir.startsWith("color-") -> scanColorStateListDir(dirPath)
                }
            }
        }

        private fun contentFiles(dirPath: String): List<Pair<String, String>> =
            runCatching {
                fileAccess.listFiles(dirPath)
                    .filter { !it.isDirectory }
                    .map { it.name to "$dirPath/${it.name}" }
                    .sortedBy { it.second }
            }.getOrElse {
                warnings += "Cannot list $dirPath: ${it.message}"
                emptyList()
            }

        private fun scanValuesDir(dirPath: String) {
            for ((name, path) in contentFiles(dirPath)) {
                if (!name.endsWith(".xml")) continue
                val body = runCatching { fileAccess.readFile(path) }
                    .getOrElse { warnings += "Cannot read $path: ${it.message}"; continue }
                runCatching { parseValuesXml(body) }
                    .onFailure { warnings += "Malformed values XML $path: ${it.message}" }
            }
        }

        private fun parseValuesXml(xmlBody: String) {
            val parser = newPullParser(xmlBody)
            val styleName = StringBuilder()
            var parent: String? = null
            var items: LinkedHashMap<String, String>? = null
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "style" -> {
                            styleName.clear()
                            styleName.append(
                                (parser.getAttributeValue(null, "name") ?: "").substringAfterLast('/')
                            )
                            parent = parser.getAttributeValue(null, "parent")
                            items = LinkedHashMap()
                        }
                        "color" -> parser.getAttributeValue(null, "name")?.let { colors[it] = parser.nextText().trim() }
                        "string" -> parser.getAttributeValue(null, "name")?.let { strings[it] = parser.nextText() }
                        "dimen" -> parser.getAttributeValue(null, "name")?.let { dimens[it] = parser.nextText().trim() }
                        "integer" -> parser.getAttributeValue(null, "name")?.let { integers[it] = parser.nextText().trim() }
                        "bool" -> parser.getAttributeValue(null, "name")?.let { bools[it] = parser.nextText().trim() }
                        "item" -> {
                            val itemName = parser.getAttributeValue(null, "name")
                            val itemType = parser.getAttributeValue(null, "type")
                            val value = parser.nextText().trim()
                            when {
                                items != null && itemName != null -> items[itemName] = value
                                itemName != null && itemType != null -> when (itemType) {
                                    "color" -> colors[itemName] = value
                                    "string" -> strings[itemName] = value
                                    "dimen" -> dimens[itemName] = value
                                    "integer" -> integers[itemName] = value
                                    "bool" -> bools[itemName] = value
                                }
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "style" && styleName.isNotEmpty() && items != null) {
                            styles[styleName.toString()] = StyleDef(styleName.toString(), parent, items!!.toMap())
                        }
                        if (parser.name == "style") {
                            styleName.clear()
                            parent = null
                            items = null
                        }
                    }
                }
            }
        }

        private fun scanLayoutDir(dirPath: String) {
            for ((name, path) in contentFiles(dirPath)) {
                if (!name.endsWith(".xml")) continue
                val body = runCatching { fileAccess.readFile(path) }
                    .getOrElse { warnings += "Cannot read $path: ${it.message}"; continue }
                layoutBodies[name.removeSuffix(".xml")] = body
            }
        }

        private fun scanDrawableDir(dirPath: String, isNight: Boolean) {
            for ((_, path) in contentFiles(dirPath)) {
                val base = path.substringAfterLast('/').substringBefore('.')
                if (base.isBlank()) continue
                drawablePaths.getOrPut(base) { mutableListOf() } += path to isNight
            }
        }

        private fun scanColorStateListDir(dirPath: String) {
            for ((name, path) in contentFiles(dirPath)) {
                if (!name.endsWith(".xml")) continue
                val body = runCatching { fileAccess.readFile(path) }
                    .getOrElse { warnings += "Cannot read $path: ${it.message}"; continue }
                val first = runCatching { firstColorInColorStateList(body) }.getOrNull()
                if (first != null) colors[name.removeSuffix(".xml")] = first
            }
        }

        fun index(appRoot: String): ProjectResourceIndex {
            val resolved = linkedMapOf<String, String>()
            for ((name, candidates) in drawablePaths) {
                // Themed variants first for their theme; among equals the alphabetical
                // scan order (base drawable/mipmap dir before typed variants) wins.
                val pick = candidates
                    .filter { it.second == dark }
                    .firstOrNull()
                    ?: candidates.firstOrNull()
                if (pick != null) resolved[name] = pick.first
            }
            return ProjectResourceIndex(
                appRoot = appRoot,
                colorMap = colors.toMap(),
                stringMap = strings.toMap(),
                dimenMap = dimens.toMap(),
                integerMap = integers.toMap(),
                boolMap = bools.toMap(),
                styles = styles.toMap(),
                layouts = layoutBodies.toMap(),
                drawables = resolved,
                buildWarnings = warnings.toList()
            )
        }
    }

    companion object {
        private const val MAX_REF_DEPTH = 8

        fun build(fileAccess: FileAccessProvider, appRoot: String, dark: Boolean): ProjectResourceIndex {
            val builder = Builder(fileAccess, dark)
            builder.scan("$appRoot/res")
            return builder.index(appRoot)
        }

        fun newPullParser(xml: String): XmlPullParser {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = false
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))
            return parser
        }

        /** Default color from a ColorStateList body: the state-less item if present
         *  (what the list shows at rest), otherwise the first concrete color found. */
        private fun firstColorInColorStateList(xml: String): String? {
            val parser = newPullParser(xml)
            var inItem = false
            var itemAttrs = -1
            var firstColor: String? = null
            var firstText: String? = null
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "item") {
                            inItem = true
                            itemAttrs = parser.attributeCount
                            val color = parser.attrValueByName("color")
                            if (!color.isNullOrBlank()) {
                                val trimmed = color.trim()
                                if (itemAttrs == 1) {
                                    return trimmed
                                }
                                if (firstColor == null) firstColor = trimmed
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        inItem = false
                        itemAttrs = -1
                    }
                    XmlPullParser.TEXT -> {
                        if (inItem) {
                            val text = parser.text.trim()
                            if (text.isNotEmpty()) {
                                if (itemAttrs == 0) return text
                                if (firstText == null) firstText = text
                            }
                        }
                    }
                }
            }
            return firstText ?: firstColor
        }

        /**
         * Index-based attribute lookup by name. With namespaces off the pull parser
         * reports prefixed attributes under their full raw name ("android:color") with a
         * null prefix, so both spellings are matched here.
         */
        private fun XmlPullParser.attrValueByName(name: String): String? {
            for (i in 0 until attributeCount) {
                val attributeName = getAttributeName(i)
                if (attributeName == name || attributeName == "android:$name") {
                    return getAttributeValue(i)
                }
            }
            return null
        }

        /** Minimal framework color table for "@android:color/x" references. */
        val ANDROID_COLORS: Map<String, String> = mapOf(
            "white" to "#FFFFFFFF",
            "black" to "#FF000000",
            "transparent" to "#00000000",
            "background_light" to "#FFFFFFFF",
            "background_dark" to "#FF000000",
            "holo_blue_light" to "#FF00DDFF",
            "holo_green_light" to "#FF99CC00",
            "holo_red_light" to "#FFFF4444",
            "darker_gray" to "#FFAAAAAA",
            "lighter_gray" to "#FFCCCCCC"
        )
    }
}
