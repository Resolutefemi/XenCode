package com.aicode.core.xmlrender

/**
 * Translates ConstraintLayout `app:layout_constraint*` attributes on a child node into
 * RelativeLayout rule attributes, so a ConstraintLayout container can be approximated with
 * a stock RelativeLayout and its existing rule engine.
 *
 * Pure string mapping: no Android classes, unit-testable on any JVM. Suffix matching on
 * attribute keys accepts any namespace prefix ("app:layout_constraintTop_toTopOf" and
 * custom res-auto declarations alike); `android:` declares no conflicting names.
 *
 * Approximations, each surfaced through [Result.notes]:
 * - Parent-anchored edges become alignParent* rules; sibling-anchored edges become
 *   below/above/toEndOf/toStartOf/align* rules, like RelativeLayout.
 * - A dimension of 0dp with both edges of its axis anchored becomes match_parent on that
 *   axis (RelativeLayout has no real match-constraint); 0dp otherwise degrades to
 *   wrap_content.
 * - Both edges of an axis anchored centers the child on that axis; bias values are not
 *   representable in RelativeLayout and only reported.
 * - Baseline maps to alignBaseline against a sibling; circular constraints are dropped.
 */
object ConstraintApprox {

    /**
     * RelativeLayout attributes produced for one child, keyed like node attrs
     * ("android:layout_alignParentTop" -> "true"). [notes] describe local degradations;
     * the caller feeds them into its warnings collector.
     */
    data class Result(
        val rules: Map<String, String>,
        val notes: List<String>
    )

    fun translate(attrs: Map<String, String>): Result {
        val rules = LinkedHashMap<String, String>()
        val notes = mutableListOf<String>()

        // ---- Horizontal axis ---------------------------------------------------------
        val hSelf = constraint(attrs, "Start_toStartOf") ?: constraint(attrs, "Left_toLeftOf")
        val hCross = constraint(attrs, "Start_toEndOf") ?: constraint(attrs, "Left_toRightOf")
        val hEnd = constraint(attrs, "End_toEndOf") ?: constraint(attrs, "Right_toRightOf")
        val hCrossEnd = constraint(attrs, "End_toStartOf") ?: constraint(attrs, "Right_toLeftOf")
        val hAnchored = hSelf != null || hCross != null
        val hEndAnchored = hEnd != null || hCrossEnd != null
        val hBoth = hAnchored && hEndAnchored

        if (hBoth) {
            rules["android:layout_centerHorizontal"] = "true"
        } else {
            edgeRule(hSelf, "alignParentStart", "alignStart", rules)
            crossRule(hCross, "toEndOf", rules)
            edgeRule(hEnd, "alignParentEnd", "alignEnd", rules)
            crossRule(hCrossEnd, "toStartOf", rules)
        }

        // ---- Vertical axis -----------------------------------------------------------
        val vTop = constraint(attrs, "Top_toTopOf")
        val vCrossTop = constraint(attrs, "Top_toBottomOf")
        val vBottom = constraint(attrs, "Bottom_toBottomOf")
        val vCrossBottom = constraint(attrs, "Bottom_toTopOf")
        val vAnchored = vTop != null || vCrossTop != null
        val vBottomAnchored = vBottom != null || vCrossBottom != null
        val vBoth = vAnchored && vBottomAnchored

        if (vBoth) {
            rules["android:layout_centerVertical"] = "true"
        } else {
            edgeRule(vTop, "alignParentTop", "alignTop", rules)
            crossRule(vCrossTop, "below", rules)
            edgeRule(vBottom, "alignParentBottom", "alignBottom", rules)
            crossRule(vCrossBottom, "above", rules)
        }

        // ---- Baseline & circular & bias ------------------------------------------------
        constraint(attrs, "Baseline_toBaselineOf")?.let { target ->
            if (target == "parent") {
                notes.add("baseline constraint against parent ignored")
            } else {
                target.substringAfterLast('/').takeIf { it.isNotBlank() }?.let { id ->
                    rules["android:layout_alignBaseline"] = id
                }
            }
        }
        if (CIRCULAR_SUFFIXES.any { constraint(attrs, it) != null }) {
            notes.add("circular constraints are dropped in this approximation")
        }
        if (constraint(attrs, "Horizontal_bias") != null && hBoth) {
            notes.add("horizontal bias approximated with centered placement")
        }
        if (constraint(attrs, "Vertical_bias") != null && vBoth) {
            notes.add("vertical bias approximated with centered placement")
        }

        // ---- Dimensions ------------------------------------------------------------------
        if (dimension(attrs, height = false) == "0dp") {
            rules["android:layout_width"] = if (hBoth) MATCH_PARENT else WRAP_CONTENT
            if (!hBoth) notes.add("0dp width without both horizontal anchors renders as wrap_content")
        }
        if (dimension(attrs, height = true) == "0dp") {
            rules["android:layout_height"] = if (vBoth) MATCH_PARENT else WRAP_CONTENT
            if (!vBoth) notes.add("0dp height without both vertical anchors renders as wrap_content")
        }
        if (constraint(attrs, "Width_percent") != null || constraint(attrs, "Height_percent") != null) {
            notes.add("percent sizes render at intrinsic dimension in this approximation")
        }

        return Result(rules, notes)
    }

    /** True when the attribute map declares at least one layout_constraint* attribute. */
    fun isConstrained(attrs: Map<String, String>): Boolean =
        attrs.keys.any { it.substringAfterLast(':').startsWith("layout_constraint") }

    // ---- Internals ---------------------------------------------------------------------

    private const val MATCH_PARENT = "match_parent"
    private const val WRAP_CONTENT = "wrap_content"

    private val CIRCULAR_SUFFIXES = setOf("Circle", "CircleRadius", "CircleAngle")

    /** First value of any attribute whose name ends with [suffix] (any namespace prefix). */
    private fun constraint(attrs: Map<String, String>, suffix: String): String? {
        for ((key, value) in attrs) {
            if (key.substringAfterLast(':').endsWith(suffix) && value.isNotBlank()) {
                return value.trim()
            }
        }
        return null
    }

    private fun dimension(attrs: Map<String, String>, height: Boolean): String {
        val key = if (height) "android:layout_height" else "android:layout_width"
        return attrs[key]?.trim().orEmpty()
    }

    /** Parent target -> alignParent rule; sibling id target -> align* rule. */
    private fun edgeRule(
        target: String?,
        parentRule: String,
        siblingRule: String,
        rules: MutableMap<String, String>
    ) {
        when {
            target == null -> return
            target == "parent" -> rules["android:layout_$parentRule"] = "true"
            target.startsWith("@") ->
                rules["android:layout_$siblingRule"] = target.substringAfterLast('/')
        }
    }

    /** Cross-edge targets only make sense against a sibling id. */
    private fun crossRule(target: String?, rule: String, rules: MutableMap<String, String>) {
        if (target != null && target.startsWith("@")) {
            rules["android:layout_$rule"] = target.substringAfterLast('/')
        }
    }
}
