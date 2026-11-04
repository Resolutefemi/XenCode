package com.aicode.core.xmlrender

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * JVM tests for the XML layout DOM parser: attrs, include inlining, merge splicing,
 * unknown-tag placeholders and the include depth guard. Runs against kxml2 (see
 * app/build.gradle.kts) because android.jar ships XmlPullParser stubs only.
 */
class XmlLayoutParserTest {

    private val parser = XmlLayoutParser()

    private fun attr(node: LayoutNode, name: String): String? =
        node.attrs[name]?.takeIf { it.isNotBlank() }

    @Test
    fun `parses nested nodes with prefixed attributes`() {
        val xml = """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:orientation="vertical"
                android:layout_width="match_parent"
                android:layout_height="match_parent">
                <TextView
                    android:id="@+id/title"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="Hello"/>
            </LinearLayout>
        """.trimIndent()

        val root = parser.parse(xml, "test.xml")

        assertEquals("LinearLayout", root.tag)
        assertEquals("vertical", attr(root, "android:orientation"))
        assertEquals("match_parent", attr(root, "android:layout_width"))
        assertEquals(1, root.children.size)
        val text = root.children[0]
        assertEquals("TextView", text.tag)
        assertEquals("title", text.idName)
        assertEquals("Hello", attr(text, "android:text"))
        assertNull(text.note)
    }

    @Test
    fun `inlines include from resolver and strips non-overlayable extra attrs`() {
        val included = """
            <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent"
                android:layout_height="48dp"
                android:visibility="visible">
                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"/>
            </FrameLayout>
        """.trimIndent()
        val main = """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent" android:layout_height="match_parent">
                <include layout="@layout/header" android:visibility="gone"/>
                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"/>
            </LinearLayout>
        """.trimIndent()

        val root = parser.parse(main, "main.xml") { name ->
            if (name == "header") included else null
        }

        assertEquals(2, root.children.size)
        val inlined = root.children[0]
        assertEquals("FrameLayout", inlined.tag)
        // visibility overlays the included root; android:id and layout_* attrs are skipped.
        assertEquals("gone", attr(inlined, "android:visibility"))
        assertEquals("48dp", attr(inlined, "android:layout_height"))
        assertEquals("@layout/header", inlined.source)
        assertEquals(1, inlined.children.size)
    }

    @Test
    fun `missing include becomes a placeholder with a note`() {
        val main = """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <include layout="@layout/does_not_exist"/>
            </LinearLayout>
        """.trimIndent()

        val root = parser.parse(main, "main.xml") { null }

        val node = root.children.single()
        assertEquals("include", node.tag)
        assertNotNull(node.note)
        assertEquals(0, node.children.size)
    }

    @Test
    fun `merge inside include is spliced into the parent`() {
        val included = """
            <merge xmlns:android="http://schemas.android.com/apk/res/android">
                <TextView android:layout_width="wrap_content" android:layout_height="wrap_content"/>
                <Button android:layout_width="wrap_content" android:layout_height="wrap_content"/>
            </merge>
        """.trimIndent()
        val main = """
            <FrameLayout xmlns:android="http://schemas.android.com/apk/res/android"
                android:layout_width="match_parent" android:layout_height="match_parent">
                <include layout="@layout/buttons"/>
            </FrameLayout>
        """.trimIndent()

        val root = parser.parse(main, "main.xml") { if (it == "buttons") included else null }

        assertEquals(2, root.children.size)
        assertEquals("TextView", root.children[0].tag)
        assertEquals("Button", root.children[1].tag)
    }

    @Test
    fun `unknown custom and fragment tags are kept as noted placeholders`() {
        val xml = """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <com.example.widgets.FancyView android:layout_width="wrap_content" android:layout_height="wrap_content"/>
                <fragment
                    android:name="com.example.MainFragment"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"/>
            </LinearLayout>
        """.trimIndent()

        val root = parser.parse(xml, "main.xml")

        val custom = root.children[0]
        assertEquals("com.example.widgets.FancyView", custom.tag)
        assertEquals("Unknown view 'com.example.widgets.FancyView' renders as a placeholder", custom.note)
        val fragment = root.children[1]
        assertEquals("fragment", fragment.tag)
        assertNotNull(fragment.note)
    }

    @Test
    fun `include recursion depth is capped with a placeholder`() {
        val deep = """
            <LinearLayout xmlns:android="http://schemas.android.com/apk/res/android">
                <include layout="@layout/loop"/>
            </LinearLayout>
        """.trimIndent()

        val root = parser.parse(deep, "loop.xml") { if (it == "loop") deep else null }

        // Walk down the include chain until the guard placeholder appears.
        var node: LayoutNode = root
        var guardSeen = false
        var guardLeft = 16
        while (guardLeft-- > 0) {
            if (node.children.size != 1) break
            node = node.children[0]
            if (node.tag == "include" && node.note?.contains("too deep", ignoreCase = true) == true) {
                guardSeen = true
                break
            }
        }
        assertEquals(true, guardSeen)
    }

    @Test
    fun `databinding layout root is unwrapped to its first view child`() {
        val xml = """
            <layout xmlns:android="http://schemas.android.com/apk/res/android"
                xmlns:app="http://schemas.android.com/apk/res-auto">
                <data>
                    <variable name="vm" type="com.example.MainViewModel"/>
                    <import type="android.view.View"/>
                </data>
                <LinearLayout
                    android:layout_width="match_parent"
                    android:layout_height="match_parent">
                    <TextView
                        android:id="@+id/label"
                        android:layout_width="wrap_content"
                        android:layout_height="wrap_content"/>
                </LinearLayout>
            </layout>
        """.trimIndent()

        val root = parser.parse(xml, "bound.xml")

        assertEquals("LinearLayout", root.tag)
        assertNull(root.note)
        assertEquals(1, root.children.size)
        assertEquals("TextView", root.children[0].tag)
        assertEquals("label", root.children[0].idName)
    }

    @Test
    fun `databinding layout without a view root degrades to an empty placeholder`() {
        val xml = """
            <layout xmlns:android="http://schemas.android.com/apk/res/android">
                <data>
                    <variable name="x" type="String"/>
                </data>
            </layout>
        """.trimIndent()

        val root = parser.parse(xml, "bound-empty.xml")

        assertEquals("empty", root.tag)
        assertNotNull(root.note)
    }
}
