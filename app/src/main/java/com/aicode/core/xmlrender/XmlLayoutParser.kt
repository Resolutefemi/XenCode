package com.aicode.core.xmlrender

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader

/**
 * Parses Android layout XML into a [LayoutNode] tree.
 *
 * Namespace declarations are intentionally OFF: attribute names are split on ':' manually.
 * This keeps prefixed names stable ("android:text") regardless of the underlying pull parser.
 *
 * Include/merge handling:
 * - `<include layout="@layout/other">` is inlined from [layoutResolver]; extra attributes
 *   on the include tag (except android:id and layout_* ones) override the included root's.
 * - A `<merge>` root reached through an include is spliced into the including parent's children.
 * - A top-level `<merge>` root is kept as-is; the renderer maps it onto a FrameLayout host.
 * - DataBinding files (`<layout>` root with `<data>`) are unwrapped to their first real
 *   view child.
 * - `<fragment>` and unknown class tags are kept as nodes with a [LayoutNode.note] so the
 *   renderer draws labeled placeholders instead of failing the whole render.
 *
 * Include recursion is capped at [MAX_INCLUDE_DEPTH]; deeper chains yield a placeholder.
 */
class XmlLayoutParser {

    fun parse(
        xml: String,
        sourceName: String,
        layoutResolver: (name: String) -> String? = { null }
    ): LayoutNode = parseInternal(xml, sourceName, layoutResolver, depth = 0)

    private fun parseInternal(
        xml: String,
        sourceName: String,
        layoutResolver: (String) -> String?,
        depth: Int
    ): LayoutNode {
        val parser = XmlPullParserFactory.newInstance().apply {
            isNamespaceAware = false
        }.newPullParser()
        parser.setInput(StringReader(xml))
        while (parser.next() != XmlPullParser.START_TAG) {
            if (parser.eventType == XmlPullParser.END_DOCUMENT) {
                return placeholder("empty", "Empty or malformed layout: $sourceName")
            }
        }
        // DataBinding files wrap the real root in <layout> with a <data> sibling; unwrap
        // to the first non-<data> child so the render starts at the actual view root.
        if (parser.name == "layout") {
            while (parser.next() != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        if (parser.name == "data") {
                            drainElement(parser)
                            continue
                        }
                        return parseNode(parser, sourceName, layoutResolver, depth)
                    }
                    XmlPullParser.END_TAG -> break
                }
            }
            return placeholder("empty", "DataBinding layout has no view root: $sourceName")
        }
        return parseNode(parser, sourceName, layoutResolver, depth)
    }

    /** Consumes through the END_TAG of the element the parser currently sits on. */
    private fun drainElement(parser: XmlPullParser) {
        var pending = 1
        while (pending > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> pending++
                XmlPullParser.END_TAG -> pending--
                XmlPullParser.END_DOCUMENT ->
                    throw IllegalStateException("Unterminated element <$${parser.name}>")
            }
        }
    }

    private fun parseNode(
        parser: XmlPullParser,
        sourceName: String,
        layoutResolver: (String) -> String?,
        depth: Int
    ): LayoutNode {
        val tag = parser.name.orEmpty()
        val attrs = collectAttrs(parser)

        if (tag == "include") {
            return parseInclude(parser, attrs, sourceName, layoutResolver, depth)
        }

        val node = LayoutNode(tag = tag, attrs = attrs, source = sourceName)
        when {
            tag == "fragment" ->
                node.note = "Fragment tag renders as a placeholder; its fragment class is not instantiated"
            looksLikeCustomView(tag) ->
                node.note = "Unknown view '$tag' renders as a placeholder"
        }

        var inside = parser.next()
        while (inside != XmlPullParser.END_TAG) {
            if (inside == XmlPullParser.START_TAG) {
                val child = parseNode(parser, sourceName, layoutResolver, depth)
                // <merge> children splice directly into the including parent (Android
                // semantics); a top-level merge is kept as a node for the render host.
                if (child.tag == "merge" && node.tag != "merge") {
                    node.children += child.children
                } else {
                    node.children += child
                }
            }
            inside = parser.next()
        }
        return node
    }

    private fun parseInclude(
        parser: XmlPullParser,
        attrs: Map<String, String>,
        sourceName: String,
        layoutResolver: (String) -> String?,
        depth: Int
    ): LayoutNode {
        val ref = attrs["layout"]?.removePrefix("@layout/")?.takeIf { it.isNotBlank() }
            ?: return placeholder("include", "include without layout attribute")
        if (depth >= MAX_INCLUDE_DEPTH) {
            return placeholder("include", "Include chain too deep (limit $MAX_INCLUDE_DEPTH): $ref")
        }
        val xml = runCatching { layoutResolver(ref) }.getOrNull()
            ?: return placeholder("include", "Included layout not found: @layout/$ref")
        val root = parseInternal(xml, "@layout/$ref", layoutResolver, depth = depth + 1)
        val inlined = root
        // The parent's child loop must stay aligned with the outer parser: consume the
        // include element itself (self-closing or with ignored nested content).
        var pending = 1
        while (pending > 0) {
            when (parser.next()) {
                XmlPullParser.START_TAG -> pending++
                XmlPullParser.END_TAG -> pending--
                XmlPullParser.END_DOCUMENT ->
                    throw IllegalStateException("Unterminated <include> in $sourceName")
            }
        }
        for ((key, value) in attrs) {
            if (key == "layout" || key == "android:id" || key.startsWith("layout_")) {
                continue
            }
            if (inlined.tag == "merge") {
                // Extra attrs on an include of a merge target have no single root to
                // apply to; drop them like Android does.
                continue
            }
            inlined.attrs = inlined.attrs + (key to value)
        }
        return inlined
    }

    private fun collectAttrs(parser: XmlPullParser): Map<String, String> {
        val map = LinkedHashMap<String, String>()
        for (i in 0 until parser.attributeCount) {
            val rawName = parser.getAttributeName(i)
            val prefix = parser.getAttributePrefix(i)
            val key = if (prefix.isNullOrBlank()) rawName else "$prefix:$rawName"
            map[key] = parser.getAttributeValue(i) ?: ""
        }
        return map
    }

    private fun placeholder(tag: String, note: String): LayoutNode = LayoutNode(
        tag = tag,
        attrs = emptyMap(),
        source = null,
        note = note
    )

    private fun looksLikeCustomView(tag: String): Boolean {
        if (!tag.contains('.')) return false
        return tag.substringAfterLast('.') !in KNOWN_SIMPLE_NAMES
    }

    private companion object {
        const val MAX_INCLUDE_DEPTH = 8

        /** Custom-view tags whose simple class names map to renderable framework widgets. */
        val KNOWN_SIMPLE_NAMES = setOf(
            "TextView", "EditText", "Button", "ImageView", "ImageButton", "CheckBox",
            "RadioButton", "Switch", "CheckedTextView", "ProgressBar", "SeekBar", "RatingBar",
            "Spinner", "AutoCompleteTextView", "MultiAutoCompleteTextView",
            "LinearLayout", "FrameLayout", "RelativeLayout", "GridLayout",
            "TableLayout", "TableRow", "ScrollView", "HorizontalScrollView", "Space",
            "View", "Toolbar", "ViewFlipper", "ViewAnimator", "ViewSwitcher",
            "ListView", "GridView", "WebView", "TextureView", "SurfaceView", "VideoView",
            "CardView", "ConstraintLayout", "Guideline", "CoordinatorLayout", "RecyclerView",
            "ViewPager", "ViewPager2", "SwipeRefreshLayout", "NestedScrollView",
            "FloatingActionButton", "Chip", "ChipGroup", "TabLayout", "TabItem",
            "BottomNavigationView", "NavigationView", "AppBarLayout",
            "CollapsingToolbarLayout", "DrawerLayout", "TextInputLayout", "TextInputEditText",
            "MaterialButton", "MaterialTextView", "MaterialCardView", "ShapeableImageView",
            "LinearLayoutCompat", "requestFocus"
        )
    }
}
