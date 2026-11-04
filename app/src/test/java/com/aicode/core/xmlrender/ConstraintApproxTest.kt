package com.aicode.core.xmlrender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the ConstraintLayout-to-RelativeLayout attribute translation. Pure string
 * rules, no Android classes.
 */
class ConstraintApproxTest {

    private fun appVars(): Map<String, String> = mapOf(
        "xmlns:app" to "http://schemas.android.com/apk/res-auto"
    )

    private fun constrained(vararg pairs: Pair<String, String>): Map<String, String> =
        appVars() + mapOf(*pairs)

    @Test
    fun `parent-anchored edges become alignParent rules`() {
        val result = ConstraintApprox.translate(
            constrained(
                "app:layout_constraintTop_toTopOf" to "parent",
                "app:layout_constraintStart_toStartOf" to "parent"
            )
        )

        assertEquals("true", result.rules["android:layout_alignParentTop"])
        assertEquals("true", result.rules["android:layout_alignParentStart"])
        assertNull(result.rules["android:layout_centerVertical"])
        assertTrue(result.notes.isEmpty())
    }

    @Test
    fun `single-side sibling edges become below and toEndOf rules`() {
        val result = ConstraintApprox.translate(
            constrained(
                "app:layout_constraintTop_toBottomOf" to "@+id/header",
                "app:layout_constraintStart_toEndOf" to "@id/avatar"
            )
        )

        assertEquals("header", result.rules["android:layout_below"])
        assertEquals("avatar", result.rules["android:layout_toEndOf"])
        assertNull(result.rules["android:layout_alignParentTop"])
        assertNull(result.rules["android:layout_centerHorizontal"])
    }

    @Test
    fun `both parent anchors on one axis collapse to centering`() {
        val result = ConstraintApprox.translate(
            constrained(
                "app:layout_constraintTop_toTopOf" to "parent",
                "app:layout_constraintBottom_toBottomOf" to "parent",
                "app:layout_constraintStart_toStartOf" to "parent",
                "app:layout_constraintVertical_bias" to "0.3"
            )
        )

        assertEquals("true", result.rules["android:layout_centerVertical"])
        assertEquals("true", result.rules["android:layout_alignParentStart"])
        // Only the fully-anchored axis is centered; single-side edges keep their rule.
        assertNull(result.rules["android:layout_centerHorizontal"])
        assertTrue(result.notes.any { it.contains("bias", ignoreCase = true) })
    }

    @Test
    fun `dual sibling anchors center and drop the cross rules`() {
        val result = ConstraintApprox.translate(
            constrained(
                "app:layout_constraintStart_toEndOf" to "@id/a",
                "app:layout_constraintEnd_toStartOf" to "@id/b"
            )
        )
        assertEquals("true", result.rules["android:layout_centerHorizontal"])
        assertNull(result.rules["android:layout_toEndOf"])
        assertNull(result.rules["android:layout_toStartOf"])
    }

    @Test
    fun `0dp dimension becomes match_parent only with both axis anchors`() {
        val both = ConstraintApprox.translate(
            constrained(
                "android:layout_width" to "0dp",
                "app:layout_constraintStart_toStartOf" to "parent",
                "app:layout_constraintEnd_toEndOf" to "parent"
            )
        )
        assertEquals("match_parent", both.rules["android:layout_width"])

        val single = ConstraintApprox.translate(
            constrained(
                "android:layout_width" to "0dp",
                "app:layout_constraintStart_toStartOf" to "parent"
            )
        )
        assertEquals("wrap_content", single.rules["android:layout_width"])
        assertTrue(single.notes.any { it.contains("0dp width", ignoreCase = true) })
    }

    @Test
    fun `0dp height handled independently from width`() {
        val result = ConstraintApprox.translate(
            constrained(
                "android:layout_height" to "0dp",
                "app:layout_constraintTop_toTopOf" to "parent",
                "app:layout_constraintBottom_toBottomOf" to "parent"
            )
        )
        assertEquals("match_parent", result.rules["android:layout_height"])
        assertFalse(result.rules.containsKey("android:layout_width"))
    }

    @Test
    fun `left-right spellings are accepted as fallbacks`() {
        val result = ConstraintApprox.translate(
            constrained(
                "app:layout_constraintLeft_toLeftOf" to "parent",
                "app:layout_constraintTop_toBottomOf" to "@id/bar"
            )
        )
        assertEquals("true", result.rules["android:layout_alignParentStart"])
        assertEquals("bar", result.rules["android:layout_below"])
    }

    @Test
    fun `baseline against sibling becomes alignBaseline`() {
        val result = ConstraintApprox.translate(
            constrained("app:layout_constraintBaseline_toBaselineOf" to "@id/other")
        )
        assertEquals("other", result.rules["android:layout_alignBaseline"])
    }

    @Test
    fun `circular constraints are reported and dropped`() {
        val result = ConstraintApprox.translate(
            constrained(
                "app:layout_constraintCircle" to "@id/anchor",
                "app:layout_constraintCircleRadius" to "48dp",
                "app:layout_constraintCircleAngle" to "45"
            )
        )
        assertTrue(result.rules.isEmpty())
        assertTrue(result.notes.any { it.contains("circular", ignoreCase = true) })
    }

    @Test
    fun `percent sizes are reported`() {
        val result = ConstraintApprox.translate(
            constrained("app:layout_constraintWidth_percent" to "0.5")
        )
        assertTrue(result.notes.any { it.contains("percent", ignoreCase = true) })
    }

    @Test
    fun `isConstrained detects only layout_constraint attributes`() {
        assertTrue(
            ConstraintApprox.isConstrained(
                constrained("app:layout_constraintTop_toTopOf" to "parent")
            )
        )
        assertFalse(
            ConstraintApprox.isConstrained(
                mapOf(
                    "xmlns:app" to "http://schemas.android.com/apk/res-auto",
                    "android:layout_width" to "match_parent"
                )
            )
        )
    }
}
