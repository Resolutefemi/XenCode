package com.aicode.core.xmlrender

import com.aicode.testutil.TestFileAccessProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * JVM tests for the project resource index against a synthetic res/ tree (real files via
 * [TestFileAccessProvider]): values parsing, -night overlays, style chains, drawable
 * name resolution and reference chasing.
 */
class ProjectResourceIndexTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun write(resRelative: String, content: String): String {
        val file = File(tmp.root, resRelative)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return file.path
    }

    private fun build(dark: Boolean = false): ProjectResourceIndex =
        ProjectResourceIndex.build(TestFileAccessProvider(), tmp.root.path, dark)

    @Test
    fun `missing res directory is reported but does not throw`() {
        val index = build()
        assertTrue(index.buildWarnings.any { it.contains("Resource directory not found") })
        assertNull(index.color("anything"))
    }

    @Test
    fun `parses colors strings dimens ints bools and chases references`() {
        write("res/values/values.xml", """
            <resources>
                <color name="brand">#FF112233</color>
                <color name="brandAlias">@color/brand</color>
                <string name="welcome">Hello %1s</string>
                <dimen name="gap">16dp</dimen>
                <dimen name="gap2">@dimen/gap</dimen>
                <integer name="count">3</integer>
                <bool name="on">true</bool>
                <item name="itemColor" type="color">#FFABCDEF</item>
            </resources>
        """.trimIndent())

        val index = build()

        assertEquals("#FF112233", index.color("brand"))
        assertEquals("#FF112233", index.color("brandAlias"))
        assertEquals("#FFABCDEF", index.color("itemColor"))
        assertEquals("Hello %1s", index.string("welcome"))
        assertEquals("16dp", index.dimen("gap"))
        assertEquals("16dp", index.dimen("gap2"))
        assertEquals(3, index.integer("count"))
        assertEquals(true, index.boolean("on"))
    }

    @Test
    fun `night values only apply to dark renders`() {
        write("res/values/colors.xml", "<resources><color name=\"bg\">#FFFFFFFF</color></resources>")
        write("res/values-night/colors.xml", "<resources><color name=\"bg\">#FF111111</color></resources>")

        assertEquals("#FFFFFFFF", build(dark = false).color("bg"))
        assertEquals("#FF111111", build(dark = true).color("bg"))
    }

    @Test
    fun `styles parse with parent chains and items`() {
        write("res/values/styles.xml", """
            <resources>
                <style name="BaseText">
                    <item name="android:textSize">16sp</item>
                    <item name="android:textColor">#FF000000</item>
                </style>
                <style name="BigText" parent="BaseText">
                    <item name="android:textSize">22sp</item>
                </style>
            </resources>
        """.trimIndent())

        val index = build()

        assertEquals(2, index.styles.size)
        val big = index.style("BigText") ?: index.style("@style/BigText")
        assertNotNull(big)
        assertEquals("BaseText", big!!.parent)
        assertEquals("22sp", big.items["android:textSize"])
        assertEquals("16sp", index.style("BaseText")!!.items["android:textSize"])
    }

    @Test
    fun `layouts are indexed for include inlining`() {
        write(
            "res/layout/main.xml",
            "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"/>"
        )
        write(
            "res/layout-v21/header.xml",
            "<FrameLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"/>"
        )

        val index = build()

        assertNotNull(index.layout("main"))
        assertNotNull(index.layout("header"))
        assertNull(index.layout("nothing"))
    }

    @Test
    fun `drawables prefer themed folders per render theme`() {
        val base = write("res/drawable/icon.xml", "<vector/>")
        val night = write("res/drawable-night/icon.xml", "<vector/>")
        val raster = write("res/mipmap-xhdpi/launch.png", "fake")

        val light = build(dark = false)
        val dark = build(dark = true)

        assertEquals(base, light.drawablePath("icon"))
        assertEquals(night, dark.drawablePath("icon"))
        assertEquals(raster, dark.drawablePath("launch"))
        assertNull(light.drawablePath("missing"))
    }

    @Test
    fun `color state lists collapse to their first concrete color`() {
        write("res/color/chip_bg.xml", """
            <selector xmlns:android="http://schemas.android.com/apk/res/android">
                <item android:color="#FFFF0000" android:state_pressed="true"/>
                <item android:color="#FF00FF00"/>
            </selector>
        """.trimIndent())

        val index = build()

        assertEquals("#FF00FF00", index.color("chip_bg"))
    }

    @Test
    fun `malformed value files become warnings instead of crashes`() {
        write("res/values/broken.xml", "<resources><color name=")

        val index = build()

        assertTrue(index.buildWarnings.isNotEmpty())
        assertNull(index.color("brand"))
        assertNull(index.layout("main"))
    }
}
