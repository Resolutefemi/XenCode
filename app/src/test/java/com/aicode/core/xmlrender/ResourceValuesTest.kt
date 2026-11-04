package com.aicode.core.xmlrender

import android.view.Gravity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** JVM tests for the attribute token parsers (colors, dimensions, gravity). */
class ResourceValuesTest {

    @Test
    fun `parses all hex color shapes`() {
        assertEquals(0xFF112233u.toInt(), ResourceValues.parseColor("#112233"))
        assertEquals(0x80112233u.toInt(), ResourceValues.parseColor("#80112233"))
        assertEquals(0xFF012345u.toInt(), ResourceValues.parseColor("#012345"))
        assertEquals(0xFFFF1122u.toInt(), ResourceValues.parseColor("#F12"))          // #RGB: digits repeat as RR GG BB
        assertEquals(0x88FF1122u.toInt(), ResourceValues.parseColor("#8F12"))         // #ARGB: digits expand
        assertNull(ResourceValues.parseColor("red"))
        assertNull(ResourceValues.parseColor("#12345"))
        assertNull(ResourceValues.parseColor(""))
    }

    @Test
    fun `dimensions scale by density`() {
        val density = 2f
        val scaled = 3f
        assertEquals(32, ResourceValues.parseDimensionPx("16dp", density, scaled))
        assertEquals(48, ResourceValues.parseDimensionPx("16sp", density, scaled))
        assertEquals(16, ResourceValues.parseDimensionPx("16px", density, scaled))
        assertEquals(16, ResourceValues.parseDimensionPx("8", density, scaled))      // bare = dp
        assertEquals(10, ResourceValues.parseDimensionPx("5dip", density, scaled))
        assertNull(ResourceValues.parseDimensionPx("50%", density, scaled))
        assertNull(ResourceValues.parseDimensionPx("", density, scaled))
        assertNull(ResourceValues.parseDimensionPx("abc", density, scaled))
    }

    @Test
    fun `gravity tokens combine axes`() {
        assertEquals(Gravity.TOP or Gravity.CENTER_HORIZONTAL, ResourceValues.parseGravity("top|center_horizontal"))
        assertEquals(Gravity.CENTER, ResourceValues.parseGravity("center"))
        assertEquals(Gravity.START or Gravity.BOTTOM, ResourceValues.parseGravity("start|bottom"))
        assertEquals(Gravity.FILL, ResourceValues.parseGravity("fill"))
        assertEquals(0, ResourceValues.parseGravity("nonsense|token"))
    }
}
