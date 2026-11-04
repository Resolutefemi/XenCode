package com.aicode.core.xmlrender

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.AutoCompleteTextView
import android.widget.Button
import android.widget.CheckBox
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MultiAutoCompleteTextView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RatingBar
import android.widget.RelativeLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Space
import android.widget.Spinner
import android.widget.Switch
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import android.widget.Toolbar
import android.widget.ViewAnimator
import android.widget.ViewFlipper
import android.widget.ViewSwitcher
import com.aicode.feature.workspace.domain.FileAccessProvider

/**
 * Turns a [LayoutNode] tree into real framework views and applies a whitelisted attribute
 * subset. Lives on the main thread while invoked (view instantiation); resource-file work
 * happens earlier in [preheat].
 *
 * Design constraints:
 * - No arbitrary class instantiation: unknown/custom/fragment tags become labeled
 *   placeholders (recorded as warnings), so previews stay deterministic and safe.
 * - androidx Material/AppCompat tags map onto lookalike framework widgets; support-library
 *   specifics (ConstraintLayout rules, CoordinatorLayout behaviors) degrade with a note.
 * - Missing resources degrade to defaults plus warnings; rendering never aborts mid-build.
 */
class XmlViewRenderer(
    context: Context,
    fileAccess: FileAccessProvider,
    val index: ProjectResourceIndex,
    private val dark: Boolean,
    private val warnings: WarningsCollector
) {

    private val themedContext = ContextThemeWrapper(
        context,
        if (dark) android.R.style.Theme_Material_NoActionBar
        else android.R.style.Theme_Material_Light_NoActionBar
    )
    private val density: Float = themedContext.resources.displayMetrics.density
    private val scaledDensity: Float = themedContext.resources.displayMetrics.scaledDensity
    private val drawables = MiniDrawables(themedContext, fileAccess, index, dark, warnings)
    private val idRegistry = LinkedHashMap<String, Int>()
    private val drawableCache = LinkedHashMap<String, Drawable?>()

    /** Collects ids and decodes file-backed drawables off the main thread, into caches. */
    suspend fun preheat(root: LayoutNode) {
        collectIds(root)
        val tokens = LinkedHashSet<String>()
        collectDrawableTokens(root, tokens)
        for (token in tokens) {
            drawableCache[token] = runCatching { drawables.resolve(token) }.getOrNull()
        }
    }

    /** Builds the render host measured exactly [widthPx] x [heightPx]. Main thread only. */
    fun renderHost(root: LayoutNode, widthPx: Int, heightPx: Int): FrameLayout {
        val host = FrameLayout(themedContext)
        host.setBackgroundColor(if (dark) HOST_BG_DARK else HOST_BG_LIGHT)
        val roots = if (root.tag == "merge") root.children else listOf(root)
        for (childNode in roots) {
            attach(host, childNode, topLevel = true)
        }
        host.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        )
        host.layout(0, 0, widthPx, heightPx)
        return host
    }

    // ---- Ids ------------------------------------------------------------------

    private fun collectIds(node: LayoutNode) {
        node.idName?.takeIf { it.isNotBlank() && it !in idRegistry }?.let {
            idRegistry[it] = View.generateViewId()
        }
        for (child in node.children) collectIds(child)
    }

    private fun idFor(ref: String?): Int? {
        if (ref == null) return null
        if (!ref.startsWith("@id/") && !ref.startsWith("@+id/")) return null
        val name = ref.substringAfterLast('/')
        return idRegistry[name] ?: run {
            warnings.add("Unresolved id reference: $ref")
            null
        }
    }

    // ---- Value helpers ----------------------------------------------------------

    private fun drawableFor(token: String?): Drawable? {
        if (token.isNullOrBlank()) return null
        val value = token.trim()
        if (value.startsWith("@drawable/") || value.startsWith("@mipmap/")) {
            return drawableCache[value]
        }
        // File-backed tokens were preheated; the rest resolve synchronously (no IO).
        return runCatching { drawables.resolveSync(value) }.getOrNull()
    }

    private fun colorFor(token: String?): Int? {
        if (token.isNullOrBlank()) return null
        val value = token.trim()
        return when {
            value.startsWith("#") -> ResourceValues.parseColor(value)
            value.startsWith("@color/") -> index.color(value.removePrefix("@color/"))
                ?.let { ResourceValues.parseColor(it) }
                ?: null.also { warnings.add("Color resource not found: $value") }
            value.startsWith("@android:color/") -> ProjectResourceIndex.ANDROID_COLORS[
                value.removePrefix("@android:color/")
            ]?.let { ResourceValues.parseColor(it) }
            value.startsWith("?") -> drawables.themeColor(value)
            else -> ResourceValues.parseColor(value)
        }
    }

    private fun textFor(token: String?): CharSequence? {
        if (token.isNullOrBlank()) return null
        val value = token.trim()
        return when {
            value.startsWith("@string/") -> index.string(value.removePrefix("@string/"))
                ?: null.also { warnings.add("String resource not found: $value") }
            value.startsWith("@android:string/") -> {
                val id = themedContext.resources.getIdentifier(
                    value.removePrefix("@android:string/"), "string", "android"
                )
                if (id != 0) themedContext.resources.getString(id) else null
            }
            else -> unescape(value)
        }
    }

    private fun unescape(raw: String): String = raw
        .replace("\\n", "\n")
        .replace("\\t", "\t")
        .replace("\\'", "'")
        .replace("\\\"", "\"")

    private fun dimenFor(token: String?): Int? {
        if (token.isNullOrBlank()) return null
        val value = token.trim()
        val resolved = if (value.startsWith("@dimen/")) {
            index.dimen(value.removePrefix("@dimen/"))
                ?: null.also { warnings.add("Dimen resource not found: $value") }
        } else value
        return resolved?.let { ResourceValues.parseDimensionPx(it, density, scaledDensity) }
    }

    // ---- Attr source: style chain merged under node attrs ------------------------

    private fun effectiveAttrs(node: LayoutNode): Map<String, String> {
        val merged = LinkedHashMap<String, String>()
        // Walk the parent chain root-first: parents apply, then children, then node attrs.
        val resolved = ArrayList<ProjectResourceIndex.StyleDef>()
        val seen = LinkedHashSet<String>()
        var current: String? = node.attrs["style"]
        var depth = 0
        while (current != null && depth++ < MAX_STYLE_DEPTH) {
            val name = current.removePrefix("@style/")
            if (name.isEmpty() || !seen.add(name)) break
            val style = index.style(name)
            if (style == null) {
                // Framework/library appearances (TextAppearance.AppCompat and friends) are not
                // re-declared in the project; skip silently, they only default text styling.
                if (!name.contains('.')) warnings.add("Style not found: $name")
                break
            }
            resolved.add(0, style)
            current = style.parent
        }
        for (style in resolved) {
            for ((k, v) in style.items) merged[k] = v
        }
        for ((k, v) in node.attrs) {
            if (k == "style") continue
            merged[k] = v
        }
        return merged
    }

    private fun collectDrawableTokens(node: LayoutNode, out: LinkedHashSet<String>) {
        for ((key, value) in node.attrs) {
            if (key in DRAWABLE_ATTR_KEYS &&
                (value.startsWith("@drawable/") || value.startsWith("@mipmap/"))
            ) {
                out += value.trim()
            }
        }
        // Styles can carry drawable values too.
        node.attrs["style"]?.removePrefix("@style/")?.let { name ->
            mergeStyleChain(name, out)
        }
        for (child in node.children) collectDrawableTokens(child, out)
    }

    private fun mergeStyleChain(name: String, out: LinkedHashSet<String>) {
        var current: String? = name
        var depth = 0
        while (current != null && depth++ < MAX_STYLE_DEPTH) {
            val n = current.removePrefix("@style/")
            val style = index.style(n) ?: return
            for (v in style.items.values) {
                if (v.startsWith("@drawable/") || v.startsWith("@mipmap/")) out += v.trim()
            }
            current = style.parent
        }
    }

    // ---- Tag mapping -------------------------------------------------------------

    private class TagMapping(val simple: String?, val placeholderLabel: String?)

    private fun mapTag(rawTag: String): TagMapping {
        var tag = rawTag
        for (prefix in STRIPPABLE_PREFIXES) {
            while (tag.startsWith(prefix)) tag = tag.removePrefix(prefix)
        }
        val simple = tag.substringAfterLast('.')
        return when {
            simple in WHITELIST -> TagMapping(simple, null)
            simple in DEGRADED_MAP -> {
                val mapped = DEGRADED_MAP[simple]!!
                if (mapped == null) {
                    TagMapping(null, simple)
                } else {
                    when (simple) {
                        "ConstraintLayout" -> warnings.add(
                            "ConstraintLayout rendered as RelativeLayout; parent and sibling constraints are translated, chains and behaviors are approximated"
                        )
                        else -> warnings.add("$simple is rendered as a lookalike framework widget")
                    }
                    TagMapping(mapped, null)
                }
            }
            else -> TagMapping(null, simple)
        }
    }

    // ---- Building ----------------------------------------------------------------

    private fun attach(parent: ViewGroup, node: LayoutNode, topLevel: Boolean) {
        val view = buildView(node) ?: return
        view.layoutParams = makeLayoutArgs(parent, effectiveAttrs(node), topLevel)
        parent.addView(view)
    }

    private fun buildView(node: LayoutNode): View? {
        if (node.tag.substringAfterLast('.') in IGNORED_TAGS) return null
        val mapping = mapTag(node.tag)
        val attrs = effectiveAttrs(node)
        val view: View = if (mapping.simple != null) {
            val built = try {
                instantiate(mapping.simple)
            } catch (e: Exception) {
                warnings.add("Cannot instantiate ${node.tag}: ${e.message}")
                return placeholder(node, mapping.placeholderLabel ?: node.tag)
            }
            applyCommon(built, attrs)
            applyTyped(built, node.tag, attrs)
            built
        } else {
            val custom = runCatching { customView(node, mapping.placeholderLabel ?: node.tag, attrs) }
                .getOrElse {
                    warnings.add("Custom widget failed: ${node.tag}: ${it.message}")
                    null
                }
            custom ?: return placeholder(node, mapping.placeholderLabel ?: node.tag)
            applyCommon(custom, attrs)
            custom
        }
        attachChildren(view, node)
        node.note?.let { warnings.add(it) }
        return view
    }

    /**
     * Attaches a node's children to a freshly built container. When the container stands
     * in for a ConstraintLayout, sibling/parent constraint attributes are translated into
     * RelativeLayout rules beforehand ([ConstraintApprox]); Guideline children vanish,
     * mirroring their invisible real counterparts.
     */
    private fun attachChildren(container: View, node: LayoutNode) {
        if (container !is ViewGroup) return
        val fromConstraintLayout = node.tag.substringAfterLast('.') == "ConstraintLayout"
        for (child in node.children) {
            if (fromConstraintLayout && !IGNORED_TAGS.contains(child.tag.substringAfterLast('.'))) {
                translateConstraints(child)
            }
            attach(container, child, topLevel = false)
        }
    }

    /** Merges ConstraintApprox rules into a child node's attrs and collects the notes. */
    private fun translateConstraints(child: LayoutNode) {
        if (!ConstraintApprox.isConstrained(child.attrs)) return
        val result = ConstraintApprox.translate(child.attrs)
        child.attrs = child.attrs + result.rules
        for (note in result.notes) {
            warnings.add(child.idName?.let { "Constraint #$it: $note" } ?: "Constraint on ${child.tag}: $note")
        }
    }

    private fun instantiate(simpleName: String): View = when (simpleName) {
        "TextView" -> TextView(themedContext)
        "EditText" -> EditText(themedContext)
        "Button" -> Button(themedContext)
        "ImageView" -> ImageView(themedContext)
        "ImageButton" -> ImageButton(themedContext)
        "CheckBox" -> CheckBox(themedContext)
        "RadioButton" -> RadioButton(themedContext)
        "Switch" -> Switch(themedContext)
        "CheckedTextView" -> CheckedTextView(themedContext)
        "ProgressBar" -> ProgressBar(themedContext)
        "SeekBar" -> SeekBar(themedContext)
        "RatingBar" -> RatingBar(themedContext)
        "Spinner" -> Spinner(themedContext)
        "AutoCompleteTextView" -> AutoCompleteTextView(themedContext)
        "MultiAutoCompleteTextView" -> MultiAutoCompleteTextView(themedContext)
        "LinearLayout" -> LinearLayout(themedContext)
        "TableLayout" -> TableLayout(themedContext)
        "TableRow" -> TableRow(themedContext)
        "FrameLayout" -> FrameLayout(themedContext)
        "RelativeLayout" -> RelativeLayout(themedContext)
        "GridLayout" -> GridLayout(themedContext)
        "ScrollView" -> ScrollView(themedContext)
        "HorizontalScrollView" -> HorizontalScrollView(themedContext)
        "Space" -> Space(themedContext)
        "View" -> View(themedContext)
        "Toolbar" -> Toolbar(themedContext)
        "ViewFlipper" -> ViewFlipper(themedContext)
        "ViewAnimator" -> ViewAnimator(themedContext)
        "ViewSwitcher" -> ViewSwitcher(themedContext)
        else -> throw IllegalArgumentException("Tag not whitelisted: $simpleName")
    }

    private fun placeholder(node: LayoutNode, label: String): View {
        val box = FrameLayout(themedContext)
        val background = GradientDrawable().apply {
            setColor(if (dark) PLACEHOLDER_BG_DARK else PLACEHOLDER_BG_LIGHT)
            setStroke(
                (density * 0.75f).toInt().coerceAtLeast(1),
                PLACEHOLDER_STROKE
            )
            cornerRadius = 6 * density
        }
        box.background = background
        val text = TextView(themedContext).apply {
            text = label.substringAfterLast('.')
            setTextColor(if (dark) PLACEHOLDER_TEXT_DARK else PLACEHOLDER_TEXT_LIGHT)
            textSize = 11f
            gravity = Gravity.CENTER
        }
        box.addView(
            text,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        )
        if (node.tag != "empty" && node.tag != "include") {
            warnings.add("'${node.tag}' rendered as a labeled placeholder")
        }
        return box
    }

    // ---- Custom approximations (Material/library lookalikes) ------------------------

    /**
     * Hand-built approximations for Material/library widgets whose stock labeled
     * placeholder would misrepresent the screen. Each builder answers for its app:*
     * attributes; [buildView] applies common attributes and attaches children afterwards.
     * Returns null when the widget has no tailored approximation (plain placeholder).
     */
    private fun customView(node: LayoutNode, label: String, attrs: Map<String, String>): View? =
        when (label) {
            "CardView", "MaterialCardView" -> cardView(attrs)
            "FloatingActionButton" -> floatingActionButton(attrs)
            "RecyclerView", "ListView", "GridView", "NavigationView" -> listSkeleton(label)
            "TabLayout" -> tabBar(node, attrs)
            "BottomNavigationView" -> bottomBar(attrs)
            "TextInputLayout" -> textInputLayout(attrs)
            "AppBarLayout" -> appBarLayout(attrs)
            else -> null
        }

    private fun cardView(attrs: Map<String, String>): FrameLayout {
        val card = FrameLayout(themedContext)
        val radius = dimenFor(attrs["app:cardCornerRadius"]) ?: (2 * density).toInt()
        val color = attrs["app:cardBackgroundColor"]?.trim()?.takeIf { it.isNotBlank() }
            ?.let { colorFor(it) }
            ?: drawables.themeColor("?attr/colorSurface")
            ?: (if (dark) SURFACE_DARK else SURFACE_LIGHT)
        card.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
        }
        attrs["app:cardElevation"]?.let { dimenFor(it)?.toFloat()?.let { px -> card.elevation = px } }
        if (paddingValue(attrs) == null) {
            val base = dimenFor(attrs["app:contentPadding"])
            val left = dimenFor(attrs["app:contentPaddingLeft"]) ?: base
            val top = dimenFor(attrs["app:contentPaddingTop"]) ?: base
            val right = dimenFor(attrs["app:contentPaddingRight"]) ?: base
            val bottom = dimenFor(attrs["app:contentPaddingBottom"]) ?: base
            if (left != null || top != null || right != null || bottom != null) {
                card.setPadding(left ?: 0, top ?: 0, right ?: 0, bottom ?: 0)
            }
        }
        return card
    }

    private fun floatingActionButton(attrs: Map<String, String>): FrameLayout {
        val fab = FrameLayout(themedContext)
        val px = ((if (attrs["app:fabSize"] == "mini") 40 else 56) * density).toInt()
        fab.minimumWidth = px
        fab.minimumHeight = px
        fab.elevation = 8f * density
        val color = attrs["app:backgroundTint"]?.trim()?.takeIf { it.isNotBlank() }
            ?.let { colorFor(it) }
            ?: drawables.themeColor("?attr/colorAccent")
            ?: FAB_ACCENT
        fab.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
        }
        val src = attrs["android:src"] ?: attrs["app:srcCompat"]
        if (src != null) {
            val icon = ImageView(themedContext)
            drawableFor(src)?.let { icon.setImageDrawable(it) }
            icon.setColorFilter(
                attrs["app:tint"]?.let { colorFor(it) } ?: 0xFFFFFFFF.toInt()
            )
            fab.addView(
                icon,
                FrameLayout.LayoutParams((24 * density).toInt(), (24 * density).toInt(), Gravity.CENTER)
            )
        }
        return fab
    }

    /** Neutral sample-rows skeleton standing in for adapter-driven list containers. */
    private fun listSkeleton(kind: String): FrameLayout {
        warnings.add("$kind contents approximated with sample rows")
        val host = FrameLayout(themedContext)
        val column = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (8 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        if (kind == "GridView") {
            val tile = (64 * density).toInt()
            val gap = (3 * density).toInt()
            for (rowIndex in 0 until 3) {
                val row = LinearLayout(themedContext).apply { orientation = LinearLayout.HORIZONTAL }
                for (col in 0 until 2) {
                    val lp = LinearLayout.LayoutParams(0, tile, 1f)
                    lp.setMargins(gap, gap, gap, gap)
                    row.addView(sampleTile(), lp)
                }
                column.addView(row)
            }
        } else {
            val barHeight = ((if (kind == "NavigationView") 40 else 48) * density).toInt()
            val gap = (3 * density).toInt()
            for (i in 0 until 6) {
                val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, barHeight)
                lp.setMargins(0, gap, 0, gap)
                column.addView(sampleTile(), lp)
            }
        }
        host.addView(
            column,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        return host
    }

    /** Rounded neutral sample block; callers provide its LayoutParams. */
    private fun sampleTile(): View {
        val tile = View(themedContext)
        tile.background = GradientDrawable().apply {
            cornerRadius = 8 * density
            setColor(if (dark) PLACEHOLDER_BG_DARK else PLACEHOLDER_BG_LIGHT)
            setStroke((density * 0.5f).toInt().coerceAtLeast(1), PLACEHOLDER_STROKE)
        }
        return tile
    }

    private fun tabBar(node: LayoutNode, attrs: Map<String, String>): FrameLayout {
        val host = FrameLayout(themedContext)
        host.minimumHeight = (48 * density).toInt()
        val row = LinearLayout(themedContext).apply { orientation = LinearLayout.HORIZONTAL }
        val items = node.children.filter { it.tag.substringAfterLast('.') == "TabItem" }
        val labels = items.mapNotNull { it.attrs["android:text"] }
        val slots = if (labels.isNotEmpty()) labels else List(3) { null }
        for (slot in slots) {
            val tab = TextView(themedContext).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                slot?.let { text = textFor(it) }
                setTextColor(
                    attrs["app:tabTextColor"]?.let { colorFor(it) }
                        ?: drawables.themeColor("?attr/colorOnSurface")
                        ?: TEXT_ON_SURFACE
                )
            }
            row.addView(tab, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        host.addView(
            row,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        val indicator = View(themedContext)
        indicator.setBackgroundColor(
            attrs["app:tabIndicatorColor"]?.let { colorFor(it) }
                ?: drawables.themeColor("?attr/colorAccent")
                ?: FAB_ACCENT
        )
        host.addView(
            indicator,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (2 * density).toInt(),
                Gravity.BOTTOM
            )
        )
        return host
    }

    private fun bottomBar(attrs: Map<String, String>): FrameLayout {
        val bar = FrameLayout(themedContext)
        bar.minimumHeight = (56 * density).toInt()
        bar.setBackgroundColor(
            attrs["android:background"]?.let { colorFor(it)?.takeIf { c -> c != 0 } }
                ?: drawables.themeColor("?attr/colorSurface")
                ?: (if (dark) SURFACE_DARK else SURFACE_LIGHT)
        )
        val row = LinearLayout(themedContext).apply { orientation = LinearLayout.HORIZONTAL }
        val iconSize = (24 * density).toInt()
        for (i in 0 until 3) {
            val slot = FrameLayout(themedContext)
            slot.addView(
                bottomNavIcon(iconSize),
                FrameLayout.LayoutParams(iconSize, iconSize, Gravity.CENTER)
            )
            row.addView(slot, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        bar.addView(
            row,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        return bar
    }

    /** Bottom-nav sample icon: filled circle tinted like an inactive item. */
    private fun bottomNavIcon(size: Int): View {
        val icon = View(themedContext)
        icon.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(
                drawables.themeColor("?attr/colorOnSurfaceVariant")
                    ?: (if (dark) 0x66FFFFFF.toInt() else 0x66000000.toInt())
            )
        }
        return icon
    }

    private fun textInputLayout(attrs: Map<String, String>): LinearLayout {
        val box = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (12 * density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        box.background = GradientDrawable().apply {
            cornerRadius = 4 * density
            setColor(if (dark) PLACEHOLDER_BG_DARK else PLACEHOLDER_BG_LIGHT)
            setStroke((density).toInt().coerceAtLeast(1), PLACEHOLDER_STROKE)
        }
        val hint = attrs["android:hint"]?.takeIf { it.isNotBlank() }
        if (hint != null) {
            val label = TextView(themedContext).apply {
                textSize = 11f
                text = textFor(hint)
                setTextColor(
                    attrs["android:textColorHint"]?.let { colorFor(it) }
                        ?: drawables.themeColor("?attr/colorOnSurfaceVariant")
                        ?: (if (dark) 0xB3FFFFFF.toInt() else 0x8A000000.toInt())
                )
            }
            box.addView(label)
            box.addView(View(themedContext), LinearLayout.LayoutParams(0, (4 * density).toInt()))
        }
        return box
    }

    private fun appBarLayout(attrs: Map<String, String>): LinearLayout {
        val bar = LinearLayout(themedContext).apply { orientation = LinearLayout.VERTICAL }
        bar.setBackgroundColor(
            attrs["android:background"]?.let { colorFor(it)?.takeIf { c -> c != 0 } }
                ?: drawables.themeColor("?attr/colorPrimary")
                ?: 0xFF3F51B5.toInt()
        )
        bar.elevation = 4f * density
        return bar
    }

    // ---- Attribute application ----------------------------------------------------

    private fun applyCommon(view: View, attrs: Map<String, String>) {
        view.id = idFor(attrs["android:id"]) ?: view.id

        // Padding family: base < horizontal/vertical < directional < start/end.
        paddingValue(attrs)?.let { (left, top, right, bottom) ->
            view.setPadding(left, top, right, bottom)
        }

        attrs["android:background"]?.let { token ->
            val drawable = drawableFor(token)
            if (drawable != null) {
                view.background = drawable
            } else {
                warnings.add("Background not resolved: $token")
            }
        }
        attrs["android:backgroundTint"]?.let { token ->
            colorFor(token)?.let { c ->
                view.backgroundTintList = android.content.res.ColorStateList.valueOf(c)
            } ?: warnings.add("Background tint not resolved: $token")
        }
        // Since API 23 every View carries a foreground; snippets commonly use it for
        // overlays and touch ripples.
        attrs["android:foreground"]?.let { token ->
            drawableFor(token)?.let { view.foreground = it }
                ?: warnings.add("Foreground not resolved: $token")
        }
        attrs["android:foregroundGravity"]?.let { view.foregroundGravity = ResourceValues.parseGravity(it) }

        when (attrs["android:visibility"]) {
            "gone" -> view.visibility = View.GONE
            "invisible" -> view.visibility = View.INVISIBLE
        }
        attrs["android:alpha"]?.toFloatOrNull()?.let { view.alpha = it }
        attrs["android:elevation"]?.let { dimenFor(it)?.toFloat()?.let { px -> view.elevation = px } }
        attrs["android:rotation"]?.toFloatOrNull()?.let { view.rotation = it }
        attrs["android:rotationX"]?.toFloatOrNull()?.let { view.rotationX = it }
        attrs["android:rotationY"]?.toFloatOrNull()?.let { view.rotationY = it }
        attrs["android:scaleX"]?.toFloatOrNull()?.let { view.scaleX = it }
        attrs["android:scaleY"]?.toFloatOrNull()?.let { view.scaleY = it }
        attrs["android:translationX"]?.let { dimenFor(it)?.toFloat()?.let { px -> view.translationX = px } }
        attrs["android:translationY"]?.let { dimenFor(it)?.toFloat()?.let { px -> view.translationY = px } }
        attrs["android:translationZ"]?.let { dimenFor(it)?.toFloat()?.let { px -> view.translationZ = px } }
        attrs["android:enabled"]?.let { view.isEnabled = it.toBoolean() }
        attrs["android:minimumWidth"]?.let { dimenFor(it)?.let { v -> view.minimumWidth = v } }
        attrs["android:minimumHeight"]?.let { dimenFor(it)?.let { v -> view.minimumHeight = v } }
        if (view is ViewGroup) {
            attrs["android:clipToPadding"]?.toBoolean()?.let { view.clipToPadding = it }
        }
    }

    private fun paddingValue(attrs: Map<String, String>): IntArray? {
        val hasAny = attrs.keys.any { it.startsWith("android:padding") }
        if (!hasAny) return null
        fun px(key: String): Int? = dimenFor(attrs[key])
        // Resolve in priority order; later calls refine individual edges (LTR mapping).
        val any = px("android:padding")
        val horizontal = px("android:paddingHorizontal")
        val vertical = px("android:paddingVertical")
        var left = any ?: horizontal ?: 0
        var right = any ?: horizontal ?: 0
        var top = any ?: vertical ?: 0
        var bottom = any ?: vertical ?: 0
        px("android:paddingLeft")?.let { left = it }
        px("android:paddingRight")?.let { right = it }
        px("android:paddingTop")?.let { top = it }
        px("android:paddingBottom")?.let { bottom = it }
        px("android:paddingStart")?.let { left = it }
        px("android:paddingEnd")?.let { right = it }
        return intArrayOf(left, top, right, bottom)
    }

    private fun applyTyped(view: View, rawTag: String, attrs: Map<String, String>) {
        fun get(key: String): String? = attrs[key]?.takeIf { it.isNotBlank() }

        if (view is TextView) {
            get("android:text")?.let { textFor(it)?.let { t -> view.text = t } }
            get("android:hint")?.let { textFor(it)?.let { t -> view.hint = t } }
            get("android:textSize")?.let { dimenFor(it)?.let { px -> view.textSize = px / scaledDensity } }
            get("android:textColor")?.let { colorFor(it)?.let { c -> view.setTextColor(c) } }
            get("android:textColorHint")?.let { colorFor(it)?.let { c -> view.setHintTextColor(c) } }
            get("android:textStyle")?.let { style ->
                val bold = style.contains("bold")
                val italic = style.contains("italic")
                view.setTypeface(view.typeface, (if (bold) Typeface.BOLD else 0) or
                        (if (italic) Typeface.ITALIC else 0))
            }
            get("android:typeface")?.let { face ->
                view.typeface = when (face) {
                    "monospace" -> Typeface.MONOSPACE
                    "serif" -> Typeface.SERIF
                    "sans_serif" -> Typeface.SANS_SERIF
                    else -> Typeface.DEFAULT
                }
            }
            for (key in listOf("android:gravity", "android:textAlignment")) {
                if (key == "android:gravity") {
                    get(key)?.let { view.gravity = ResourceValues.parseGravity(it) }
                } else {
                    get(key)?.let { alignment ->
                        if (alignment != "inherit") {
                            view.textAlignment = when (alignment) {
                                "center" -> TextView.TEXT_ALIGNMENT_CENTER
                                "textStart" -> TextView.TEXT_ALIGNMENT_TEXT_START
                                "textEnd" -> TextView.TEXT_ALIGNMENT_TEXT_END
                                "viewStart" -> TextView.TEXT_ALIGNMENT_VIEW_START
                                "viewEnd" -> TextView.TEXT_ALIGNMENT_VIEW_END
                                else -> TextView.TEXT_ALIGNMENT_GRAVITY
                            }
                        }
                    }
                }
            }
            get("android:ellipsize")?.let { value ->
                view.ellipsize = when (value) {
                    "start" -> TextUtils.TruncateAt.START
                    "middle" -> TextUtils.TruncateAt.MIDDLE
                    "end", "marquee" -> TextUtils.TruncateAt.END
                    else -> null
                }
            }
            get("android:maxLines")?.toIntOrNull()?.let { view.maxLines = it }
            get("android:minLines")?.toIntOrNull()?.let { view.minLines = it }
            get("android:lines")?.toIntOrNull()?.let { view.setLines(it) }
            get("android:ems")?.toIntOrNull()?.let { view.setEms(it) }
            get("android:letterSpacing")?.toFloatOrNull()?.let { view.letterSpacing = it }
            get("android:textAllCaps")?.toBoolean()?.let { view.isAllCaps = it }
            get("android:drawablePadding")?.let { dimenFor(it)?.let { px -> view.compoundDrawablePadding = px } }
            get("android:shadowColor")?.let { colorFor(it)?.let { c ->
                val radius = get("android:shadowRadius")?.toFloatOrNull() ?: 0f
                val dx = get("android:shadowDx")?.toFloatOrNull() ?: 0f
                val dy = get("android:shadowDy")?.toFloatOrNull() ?: 0f
                view.setShadowLayer(radius * density, dx * density, dy * density, c)
            } }
            val lineExtra = get("android:lineSpacingExtra")?.let { dimenFor(it)?.toFloat() }
            val lineMult = get("android:lineSpacingMultiplier")?.toFloatOrNull()
            if (lineExtra != null || lineMult != null) {
                view.setLineSpacing(lineExtra ?: view.lineSpacingExtra, lineMult ?: view.lineSpacingMultiplier)
            }
            get("android:includeFontPadding")?.toBoolean()?.let { view.includeFontPadding = it }
            get("android:textColorHighlight")?.let { colorFor(it)?.let { c -> view.highlightColor = c } }
            get("android:fontFamily")?.let { family ->
                val style = (if (get("android:textStyle")?.contains("bold") == true) Typeface.BOLD else 0) or
                        (if (get("android:textStyle")?.contains("italic") == true) Typeface.ITALIC else 0)
                view.typeface = Typeface.create(family, style)
            }
            // Compound drawables: prefer start/end, fall back to the directional pair.
            val start = drawableFor(get("android:drawableStart") ?: get("android:drawableLeft"))
            val end = drawableFor(get("android:drawableEnd") ?: get("android:drawableRight"))
            val top = drawableFor(get("android:drawableTop"))
            val bottom = drawableFor(get("android:drawableBottom"))
            if (anyNotNull(start, end, top, bottom)) {
                view.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    start, top, end, bottom
                )
            }
        }

        if (view is EditText) {
            get("android:inputType")?.let { type -> view.inputType = inputTypeFor(type) }
            get("android:imeOptions")?.let { options -> view.imeOptions = imeOptionsFor(options) }
            get("android:maxLength")?.toIntOrNull()?.let { max ->
                view.filters = arrayOf(android.text.InputFilter.LengthFilter(max))
            }
        }

        if (view is android.widget.CompoundButton) {
            get("android:checked")?.toBoolean()?.let { view.isChecked = it }
            get("android:buttonTint")?.let { colorFor(it)?.let { c ->
                view.buttonTintList = android.content.res.ColorStateList.valueOf(c)
            } }
            if (view is Switch) {
                get("android:thumbTint")?.let { colorFor(it)?.let { c -> view.thumbTintList = android.content.res.ColorStateList.valueOf(c) } }
                get("android:trackTint")?.let { colorFor(it)?.let { c -> view.trackTintList = android.content.res.ColorStateList.valueOf(c) } }
            }
        }
        if (view is CheckedTextView) {
            get("android:checked")?.toBoolean()?.let { view.isChecked = it }
            get("android:checkMark")?.let { drawableFor(it)?.let { d -> view.checkMarkDrawable = d } }
            get("android:checkMarkTint")?.let { colorFor(it)?.let { c ->
                view.checkMarkTintList = android.content.res.ColorStateList.valueOf(c)
            } }
        }

        if (view is ImageView) {
            // AppCompat widgets carry their source in app:srcCompat; android:src wins.
            val srcToken = get("android:src") ?: get("app:srcCompat")
            srcToken?.let { token ->
                when {
                    drawableFor(token) != null -> view.setImageDrawable(drawableFor(token))
                    colorFor(token) != null -> {
                        // Color sources draw as a filled rectangle with no intrinsic bounds.
                        view.setImageDrawable(
                            android.graphics.drawable.ColorDrawable(colorFor(token)!!)
                        )
                        if (get("android:scaleType") == null) {
                            view.scaleType = ImageView.ScaleType.FIT_XY
                        }
                    }
                    else -> warnings.add("Src not resolved: $token")
                }
            }
            get("android:scaleType")?.let { value ->
                view.scaleType = when (value) {
                    "fitXY" -> ImageView.ScaleType.FIT_XY
                    "fitStart" -> ImageView.ScaleType.FIT_START
                    "fitEnd" -> ImageView.ScaleType.FIT_END
                    "center" -> ImageView.ScaleType.CENTER
                    "centerCrop" -> ImageView.ScaleType.CENTER_CROP
                    "centerInside" -> ImageView.ScaleType.CENTER_INSIDE
                    "matrix" -> ImageView.ScaleType.MATRIX
                    else -> ImageView.ScaleType.FIT_CENTER
                }
            }
            val tintToken = get("android:tint") ?: get("app:tint")
            tintToken?.let { colorFor(it)?.let { c -> view.setColorFilter(c) } }
            get("android:adjustViewBounds")?.toBoolean()?.let { view.adjustViewBounds = it }
            get("android:cropToPadding")?.toBoolean()?.let { view.cropToPadding = it }
            get("android:maxWidth")?.let { dimenFor(it)?.let { v -> view.maxWidth = v } }
            get("android:maxHeight")?.let { dimenFor(it)?.let { v -> view.maxHeight = v } }
        }

        if (view is ProgressBar) {
            get("android:max")?.toIntOrNull()?.let { view.max = it }
            get("android:progress")?.toIntOrNull()?.let { view.progress = it }
            get("android:secondaryProgress")?.toIntOrNull()?.let { view.secondaryProgress = it }
            get("android:indeterminate")?.toBoolean()?.let { view.isIndeterminate = it }
            get("android:progressTint")?.let { colorFor(it)?.let { c -> view.progressTintList = android.content.res.ColorStateList.valueOf(c) } }
            get("android:progressBackgroundTint")?.let { colorFor(it)?.let { c -> view.progressBackgroundTintList = android.content.res.ColorStateList.valueOf(c) } }
            get("android:secondaryProgressTint")?.let { colorFor(it)?.let { c -> view.secondaryProgressTintList = android.content.res.ColorStateList.valueOf(c) } }
            get("android:indeterminateTint")?.let { colorFor(it)?.let { c -> view.indeterminateTintList = android.content.res.ColorStateList.valueOf(c) } }
            if (view is SeekBar) {
                get("android:thumb")?.let { drawableFor(it)?.let { d -> view.thumb = d } }
                get("android:thumbTint")?.let { colorFor(it)?.let { c -> view.thumbTintList = android.content.res.ColorStateList.valueOf(c) } }
            }
        }

        if (view is RatingBar) {
            get("android:numStars")?.toIntOrNull()?.let { view.numStars = it }
            get("android:rating")?.toFloatOrNull()?.let { view.rating = it }
            get("android:stepSize")?.toFloatOrNull()?.let { view.stepSize = it }
            get("android:isIndicator")?.toBoolean()?.let { view.setIsIndicator(it) }
        }

        if (view is Spinner) {
            get("android:gravity")?.let { view.gravity = ResourceValues.parseGravity(it) }
            if (get("android:popupBackground") != null) {
                warnings.add("Spinner popup background not previewed")
            }
        }

        if (view is LinearLayout) {
            get("android:orientation")?.let { value ->
                view.orientation = if (value == "vertical") {
                    LinearLayout.VERTICAL
                } else {
                    LinearLayout.HORIZONTAL
                }
            }
            get("android:gravity")?.let { view.gravity = ResourceValues.parseGravity(it) }
            get("android:weightSum")?.toFloatOrNull()?.let { view.weightSum = it }
            get("android:baselineAligned")?.toBoolean()?.let { view.isBaselineAligned = it }
            get("android:measureWithLargestChild")?.toBoolean()?.let { view.isMeasureWithLargestChildEnabled = it }
            get("android:divider")?.let { drawableFor(it)?.let { d -> view.dividerDrawable = d } }
            get("android:showDividers")?.let { value ->
                var mode = 0
                for (part in value.split('|').map { it.trim() }) {
                    when (part) {
                        "beginning" -> mode = mode or LinearLayout.SHOW_DIVIDER_BEGINNING
                        "middle" -> mode = mode or LinearLayout.SHOW_DIVIDER_MIDDLE
                        "end" -> mode = mode or LinearLayout.SHOW_DIVIDER_END
                    }
                }
                view.showDividers = mode
            }
            get("android:dividerPadding")?.let { dimenFor(it)?.let { px -> view.dividerPadding = px } }
        }

        if (view is TableLayout) {
            get("android:shrinkAllColumns")?.toBoolean()?.let { view.setShrinkAllColumns(it) }
            get("android:stretchAllColumns")?.toBoolean()?.let { view.setStretchAllColumns(it) }
        }

        if (view is GridLayout) {
            get("android:rowCount")?.toIntOrNull()?.let { view.rowCount = it }
            get("android:columnCount")?.toIntOrNull()?.let { view.columnCount = it }
            get("android:orientation")?.let { value ->
                view.orientation = if (value == "vertical") GridLayout.VERTICAL else GridLayout.HORIZONTAL
            }
            get("android:useDefaultMargins")?.toBoolean()?.let { view.useDefaultMargins = it }
        }

        if (view is ScrollView) {
            get("android:fillViewport")?.toBoolean()?.let { view.setFillViewport(it) }
        }
        if (view is HorizontalScrollView) {
            get("android:fillViewport")?.toBoolean()?.let { view.setFillViewport(it) }
        }

        if (view is Toolbar) {
            get("android:title")?.let { textFor(it)?.let { t -> view.title = t } }
            get("android:subtitle")?.let { textFor(it)?.let { t -> view.subtitle = t } }
            (get("android:navigationIcon") ?: get("app:navigationIcon"))?.let {
                drawableFor(it)?.let { d -> view.navigationIcon = d }
            }
            get("android:logo")?.let { drawableFor(it)?.let { d -> view.logo = d } }
            get("android:titleTextColor")?.let { colorFor(it)?.let { c -> view.setTitleTextColor(c) } }
            get("android:subtitleTextColor")?.let { colorFor(it)?.let { c -> view.setSubtitleTextColor(c) } }
        }
    }

    private fun anyNotNull(vararg values: Any?): Boolean = values.any { it != null }

    private fun inputTypeFor(raw: String): Int {
        var type = InputType.TYPE_NULL
        for (part in raw.split('|').map { it.trim() }) {
            type = type or when (part) {
                "text" -> InputType.TYPE_CLASS_TEXT
                "textEmailAddress" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                "textPassword" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                "textVisiblePassword" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                "textMultiLine" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                "textCapWords" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
                "textCapSentences" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                "textAutoCorrect" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_AUTO_CORRECT
                "textUri" -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                "number" -> InputType.TYPE_CLASS_NUMBER
                "numberDecimal" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                "numberSigned" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
                "numberPassword" -> InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
                "phone" -> InputType.TYPE_CLASS_PHONE
                "datetime" -> InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_NORMAL
                "date" -> InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE
                "time" -> InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
                else -> 0
            }
        }
        return if (type == InputType.TYPE_NULL) InputType.TYPE_CLASS_TEXT else type
    }

    private fun imeOptionsFor(raw: String): Int {
        var options = 0
        for (part in raw.split('|').map { it.trim() }) {
            options = options or when (part) {
                "actionDone" -> android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                "actionGo" -> android.view.inputmethod.EditorInfo.IME_ACTION_GO
                "actionNext" -> android.view.inputmethod.EditorInfo.IME_ACTION_NEXT
                "actionSearch" -> android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH
                "actionSend" -> android.view.inputmethod.EditorInfo.IME_ACTION_SEND
                "flagNoExtractUi" -> android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI
                else -> 0
            }
        }
        return options
    }

    // ---- Layout params ------------------------------------------------------------

    private fun makeLayoutArgs(
        parent: ViewGroup,
        attrs: Map<String, String>,
        topLevel: Boolean
    ): ViewGroup.LayoutParams {
        fun get(key: String): String? = attrs[key]?.takeIf { it.isNotBlank() }
        fun dimension(key: String, default: Int): Int {
            val raw = get(key) ?: return default
            return when (raw) {
                "match_parent", "fill_parent" -> ViewGroup.LayoutParams.MATCH_PARENT
                "wrap_content" -> ViewGroup.LayoutParams.WRAP_CONTENT
                else -> dimenFor(raw) ?: default
            }
        }

        val defaultSize = if (topLevel) {
            ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            ViewGroup.LayoutParams.WRAP_CONTENT
        }
        val width = dimension("android:layout_width", defaultSize)
        val height = dimension("android:layout_height", defaultSize)

        val layoutParams: ViewGroup.LayoutParams = when (parent) {
            is GridLayout -> GridLayout.LayoutParams(
                gridSpec(attrs, "Row", horizontal = false),
                gridSpec(attrs, "Column", horizontal = true)
            ).apply {
                this.width = width
                this.height = height
                applyMargins(this, attrs)
            }
            is LinearLayout -> LinearLayout.LayoutParams(width, height).apply {
                get("android:layout_weight")?.toFloatOrNull()?.let { weight = it }
                get("android:layout_gravity")?.let { gravity = ResourceValues.parseGravity(it) }
                applyMargins(this, attrs)
            }
            is RelativeLayout -> RelativeLayout.LayoutParams(width, height).apply {
                applyRelativeRules(this, attrs)
                applyMargins(this, attrs)
            }
            is FrameLayout -> FrameLayout.LayoutParams(width, height).apply {
                get("android:layout_gravity")?.let { gravity = ResourceValues.parseGravity(it) }
                applyMargins(this, attrs)
            }
            else -> ViewGroup.MarginLayoutParams(width, height).apply { applyMargins(this, attrs) }
        }
        return layoutParams
    }

    /**
     * GridLayout child spec honoring layout_row/column, their *Span siblings and
     * layout_gravity (alignment only). Undefined coordinates flow through the grid in
     * declaration order, like Android's auto-indexing.
     */
    private fun gridSpec(attrs: Map<String, String>, axis: String, horizontal: Boolean): GridLayout.Spec {
        fun intOf(name: String): Int? = attrs["android:layout_$name"]?.trim()?.toIntOrNull()
        val start = intOf(axis)
        val span = intOf(axis + "Span")
        val alignment = attrs["android:layout_gravity"]
            ?.takeIf { it.isNotBlank() }
            ?.let { ResourceValues.parseGravity(it) }
            ?.takeIf { it != 0 }
            ?.let { gridAlignment(it, horizontal) }
        return when {
            alignment != null && span != null && start != null -> GridLayout.spec(start, span, alignment)
            alignment != null && start != null -> GridLayout.spec(start, alignment)
            alignment != null -> GridLayout.spec(GridLayout.UNDEFINED, alignment)
            span != null && start != null -> GridLayout.spec(start, span)
            start != null -> GridLayout.spec(start)
            else -> GridLayout.spec(GridLayout.UNDEFINED)
        }
    }

    /**
     * Maps gravity bits onto GridLayout.Alignment. GridLayout.getAlignment is not part
     * of the public compile SDK, so the axes are decoded by hand; fill wins over
     * center, center wins over edge anchors, and the default is CENTER.
     */
    private fun gridAlignment(gravity: Int, horizontal: Boolean): GridLayout.Alignment =
        if (horizontal) {
            when {
                gravity and Gravity.FILL_HORIZONTAL == Gravity.FILL_HORIZONTAL -> GridLayout.FILL
                gravity and Gravity.CENTER_HORIZONTAL != 0 -> GridLayout.CENTER
                gravity and Gravity.START != 0 || gravity and Gravity.LEFT != 0 -> GridLayout.LEFT
                gravity and Gravity.END != 0 || gravity and Gravity.RIGHT != 0 -> GridLayout.RIGHT
                else -> GridLayout.CENTER
            }
        } else {
            when {
                gravity and Gravity.FILL_VERTICAL == Gravity.FILL_VERTICAL -> GridLayout.FILL
                gravity and Gravity.CENTER_VERTICAL != 0 -> GridLayout.CENTER
                gravity and Gravity.TOP != 0 -> GridLayout.TOP
                gravity and Gravity.BOTTOM != 0 -> GridLayout.BOTTOM
                else -> GridLayout.CENTER
            }
        }

    private fun applyMargins(lp: ViewGroup.MarginLayoutParams, attrs: Map<String, String>) {
        fun get(key: String): String? = attrs[key]?.takeIf { it.isNotBlank() }
        fun px(key: String): Int? {
            val raw = get(key) ?: return null
            return dimenFor(raw)
        }
        val all = px("android:layout_margin")
        val horizontal = px("android:layout_marginHorizontal")
        val vertical = px("android:layout_marginVertical")
        var left = all ?: horizontal ?: 0
        var right = all ?: horizontal ?: 0
        var top = all ?: vertical ?: 0
        var bottom = all ?: vertical ?: 0
        px("android:layout_marginLeft")?.let { left = it }
        px("android:layout_marginRight")?.let { right = it }
        px("android:layout_marginTop")?.let { top = it }
        px("android:layout_marginBottom")?.let { bottom = it }
        px("android:layout_marginStart")?.let { left = it }
        px("android:layout_marginEnd")?.let { right = it }
        lp.setMargins(left, top, right, bottom)
    }

    private fun applyRelativeRules(lp: RelativeLayout.LayoutParams, attrs: Map<String, String>) {
        fun get(key: String): String? = attrs[key]?.takeIf { it.isNotBlank() }
        fun boolRule(key: String, rule: Int) {
            if (get(key)?.toBoolean() == true) lp.addRule(rule, RelativeLayout.TRUE)
        }
        fun idRule(key: String, rule: Int) {
            get(key)?.let { idFor(it)?.let { id -> lp.addRule(rule, id) } }
        }
        boolRule("android:layout_centerInParent", RelativeLayout.CENTER_IN_PARENT)
        boolRule("android:layout_centerHorizontal", RelativeLayout.CENTER_HORIZONTAL)
        boolRule("android:layout_centerVertical", RelativeLayout.CENTER_VERTICAL)
        boolRule("android:layout_alignParentTop", RelativeLayout.ALIGN_PARENT_TOP)
        boolRule("android:layout_alignParentBottom", RelativeLayout.ALIGN_PARENT_BOTTOM)
        boolRule("android:layout_alignParentStart", RelativeLayout.ALIGN_PARENT_START)
        boolRule("android:layout_alignParentEnd", RelativeLayout.ALIGN_PARENT_END)
        boolRule("android:layout_alignParentLeft", RelativeLayout.ALIGN_PARENT_LEFT)
        boolRule("android:layout_alignParentRight", RelativeLayout.ALIGN_PARENT_RIGHT)
        idRule("android:layout_toStartOf", RelativeLayout.START_OF)
        idRule("android:layout_toEndOf", RelativeLayout.END_OF)
        idRule("android:layout_toLeftOf", RelativeLayout.LEFT_OF)
        idRule("android:layout_toRightOf", RelativeLayout.RIGHT_OF)
        idRule("android:layout_below", RelativeLayout.BELOW)
        idRule("android:layout_above", RelativeLayout.ABOVE)
        idRule("android:layout_alignStart", RelativeLayout.ALIGN_START)
        idRule("android:layout_alignEnd", RelativeLayout.ALIGN_END)
        idRule("android:layout_alignTop", RelativeLayout.ALIGN_TOP)
        idRule("android:layout_alignBottom", RelativeLayout.ALIGN_BOTTOM)
    }

    private companion object {
        const val MAX_STYLE_DEPTH = 8
        val HOST_BG_LIGHT = 0xFFFFFFFF.toInt()
        val HOST_BG_DARK = 0xFF121212.toInt()
        val PLACEHOLDER_BG_LIGHT = 0xFFF0F1F3.toInt()
        val PLACEHOLDER_BG_DARK = 0xFF26282C.toInt()
        val PLACEHOLDER_STROKE = 0x33999999.toInt()
        val PLACEHOLDER_TEXT_LIGHT = 0xFF5F6368.toInt()
        val PLACEHOLDER_TEXT_DARK = 0xFF9AA0A6.toInt()
        val SURFACE_LIGHT = 0xFFFFFFFF.toInt()
        val SURFACE_DARK = 0xFF2A2C2F.toInt()
        val TEXT_ON_SURFACE = 0xDE000000.toInt()
        val FAB_ACCENT = 0xFFFF4081.toInt()

        /** Semantic no-visual tags Android itself ignores during inflation. */
        val IGNORED_TAGS = setOf("Guideline", "requestFocus")

        val DRAWABLE_ATTR_KEYS = setOf(
            "android:background", "android:foreground", "android:src", "app:srcCompat",
            "android:drawableStart", "android:drawableLeft", "android:drawableTop",
            "android:drawableEnd", "android:drawableRight", "android:drawableBottom",
            "android:divider", "android:checkMark", "android:thumb", "android:button",
            "android:navigationIcon", "app:navigationIcon", "android:logo"
        )

        val WHITELIST = setOf(
            "TextView", "EditText", "Button", "ImageView", "ImageButton", "CheckBox",
            "RadioButton", "Switch", "CheckedTextView", "ProgressBar", "SeekBar", "RatingBar",
            "Spinner", "AutoCompleteTextView", "MultiAutoCompleteTextView",
            "LinearLayout", "TableLayout", "TableRow", "FrameLayout", "RelativeLayout",
            "GridLayout", "ScrollView", "HorizontalScrollView", "Space", "View",
            "Toolbar", "ViewFlipper", "ViewAnimator", "ViewSwitcher"
        )

        /**
         * Support-library/Material tags -> lookalike framework widget (null = custom
         * approximation if one exists in [customView], otherwise labeled placeholder).
         */
        val DEGRADED_MAP = mapOf(
            "ConstraintLayout" to "RelativeLayout",
            "CoordinatorLayout" to "FrameLayout",
            "CardView" to null,
            "MaterialCardView" to null,
            "AppBarLayout" to null,
            "CollapsingToolbarLayout" to "FrameLayout",
            "RecyclerView" to null,
            "ViewPager" to null,
            "ViewPager2" to null,
            "NestedScrollView" to "ScrollView",
            "SwipeRefreshLayout" to "FrameLayout",
            "TabLayout" to null,
            "TabItem" to null,
            "BottomNavigationView" to null,
            "NavigationView" to null,
            "DrawerLayout" to "FrameLayout",
            "LinearLayoutCompat" to "LinearLayout",
            "TextInputLayout" to null,
            "TextInputEditText" to "EditText",
            "Chip" to "CheckBox",
            "ChipGroup" to "LinearLayout",
            "FloatingActionButton" to null,
            "MaterialButton" to "Button",
            "MaterialTextView" to "TextView",
            "ShapeableImageView" to "ImageView",
            "WebView" to null,
            "ListView" to null,
            "GridView" to null,
            "VideoView" to null,
            "SurfaceView" to null,
            "TextureView" to null,
            "ViewStub" to null
        )

        val STRIPPABLE_PREFIXES = listOf(
            "android.widget.", "android.view.", "android.support.v7.widget.",
            "android.support.v4.widget.", "androidx.appcompat.widget.",
            "androidx.core.widget.", "androidx.recyclerview.widget.",
            "androidx.cardview.widget.", "androidx.constraintlayout.widget.",
            "androidx.constraintlayout.helper.widget.", "androidx.coordinatorlayout.widget.",
            "androidx.viewpager.widget.", "androidx.viewpager2.widget.",
            "androidx.swiperefreshlayout.widget.", "androidx.drawerlayout.widget.",
            "androidx.gridlayout.widget.", "com.google.android.material.button.",
            "com.google.android.material.textview.", "com.google.android.material.card.",
            "com.google.android.material.floatingactionbutton.",
            "com.google.android.material.chip.", "com.google.android.material.bottomnavigation.",
            "com.google.android.material.navigation.", "com.google.android.material.textfield.",
            "com.google.android.material.appbar.", "com.google.android.material.tabs.",
            "com.google.android.material.imageview.", "com.google.android.material.swiperefreshlayout."
        )
    }
}
