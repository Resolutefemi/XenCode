package com.aicode.core.xmlrender

/**
 * Pure token parsers for attribute values shared by the renderer and unit tests.
 * No Android view classes — only conversion rules, so these run on any JVM.
 */
object ResourceValues {

    /** Parses "#RGB", "#ARGB", "#RRGGBB", "#AARRGGBB" (case-insensitive) into ARGB int. */
    fun parseColor(raw: String): Int? {
        val value = raw.trim()
        if (!value.startsWith("#")) return null
        val hex = value.substring(1)
        return when (hex.length) {
            3 -> {
                val r = hex[0].digitToIntOrNull(16) ?: return null
                val g = hex[1].digitToIntOrNull(16) ?: return null
                val b = hex[2].digitToIntOrNull(16) ?: return null
                0xFF000000.toInt() or (r * 17 shl 16) or (g * 17 shl 8) or (b * 17)
            }
            4 -> {
                val a = hex[0].digitToIntOrNull(16) ?: return null
                val r = hex[1].digitToIntOrNull(16) ?: return null
                val g = hex[2].digitToIntOrNull(16) ?: return null
                val b = hex[3].digitToIntOrNull(16) ?: return null
                (a * 17 shl 24) or (r * 17 shl 16) or (g * 17 shl 8) or (b * 17)
            }
            6 -> {
                val rgb = hex.toIntOrNull(16) ?: return null
                0xFF000000.toInt() or rgb
            }
            8 -> hex.toLongOrNull(16)?.toInt()
            else -> null
        }
    }

    /**
     * Parses a dimension token into pixels. "dp"/"dip" scale by [density], "sp" by
     * [scaledDensity], bare numbers count as dp (aapt requires units; previews are lenient).
     * Returns null for unsupported units (%, in, mm) or malformed input.
     */
    fun parseDimensionPx(raw: String, density: Float, scaledDensity: Float): Int? {
        val value = raw.trim().lowercase()
        if (value.isEmpty()) return null
        val (number, scale) = when {
            value.endsWith("dp") || value.endsWith("dip") ->
                value.trimEnd('p', 'd', 'i') to density
            value.endsWith("sp") -> value.dropLast(2) to scaledDensity
            value.endsWith("px") -> value.dropLast(2) to 1f
            else -> value to density
        }
        if (number.isBlank()) return null
        doubleNumber(number)?.let { return Math.round(it * scale).toInt() }
        return null
    }

    /** Parses "14", "14.5", "-3". */
    fun doubleNumber(raw: String): Double? = raw.trim().toDoubleOrNull()

    /**
     * Parses a gravity token like "left|center_vertical" into gravity bits.
     * Vertical: top/bottom/center_vertical/fill_vertical/clip_vertical; horizontal:
     * left/right/start/end/center_horizontal/fill_horizontal/clip_horizontal; "center"/"fill"
     * both axes. Unknown parts ignored.
     */
    fun parseGravity(raw: String): Int {
        val verticalBits = mapOf(
            "top" to android.view.Gravity.TOP,
            "bottom" to android.view.Gravity.BOTTOM,
            "center_vertical" to android.view.Gravity.CENTER_VERTICAL,
            "fill_vertical" to android.view.Gravity.FILL_VERTICAL,
            "clip_vertical" to android.view.Gravity.CLIP_VERTICAL
        )
        val horizontalBits = mapOf(
            "left" to android.view.Gravity.LEFT,
            "right" to android.view.Gravity.RIGHT,
            "start" to android.view.Gravity.START,
            "end" to android.view.Gravity.END,
            "center_horizontal" to android.view.Gravity.CENTER_HORIZONTAL,
            "fill_horizontal" to android.view.Gravity.FILL_HORIZONTAL,
            "clip_horizontal" to android.view.Gravity.CLIP_HORIZONTAL
        )
        var result = 0
        for (part in raw.trim().lowercase().split('|')) {
            when (part) {
                "center" -> result = result or android.view.Gravity.CENTER_HORIZONTAL or
                        android.view.Gravity.CENTER_VERTICAL
                "fill" -> result = result or android.view.Gravity.FILL_HORIZONTAL or
                        android.view.Gravity.FILL_VERTICAL
                in verticalBits -> result = result or (verticalBits[part] ?: 0)
                in horizontalBits -> result = result or (horizontalBits[part] ?: 0)
            }
        }
        return result
    }
}
