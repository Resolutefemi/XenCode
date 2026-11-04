package com.aicode.core.xmlrender

/**
 * A single node of an Android XML layout tree.
 *
 * This is a neutral DOM, not an Android View: parsing is pure JVM and unit-testable,
 * while the renderer ([XmlViewRenderer]) turns nodes into views on the main thread.
 * Tag names keep their original casing/prefix ("TextView", "androidx.cardview.widget.CardView").
 */
data class LayoutNode(
    val tag: String,
    /** Raw attribute values keyed by prefixed name, e.g. "android:layout_width" -> "match_parent". Mutable: <include> overlays extra attributes onto the inlined root. */
    var attrs: Map<String, String> = emptyMap(),
    val children: MutableList<LayoutNode> = mutableListOf(),
    /** Layout file this node came from (include chains also link their original file here). */
    val source: String? = null,
    /** Non-fatal diagnostic attached to this node; surfaces as an "approximate render" warning. */
    var note: String? = null
) {
    /** First defined android:id resource name without "@+id/", "@id/" or "@android:id/" prefix. */
    val idName: String?
        get() = attrs["android:id"]
            ?.removePrefix("@+id/")
            ?.removePrefix("@id/")
            ?.removePrefix("@android:id/")
            ?.takeIf { it.isNotBlank() }
}
