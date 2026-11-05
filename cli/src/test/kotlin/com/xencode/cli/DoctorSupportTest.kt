package com.xencode.cli

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * JVM version probing for `xencode doctor`.
 */
class DoctorSupportTest {

    @Test
    fun modernScheme() {
        assertEquals(21, javaMajorVersion("21.0.13"))
        assertEquals(17, javaMajorVersion("17"))
        assertEquals(11, javaMajorVersion("11.0.20.1"))
        assertEquals(25, javaMajorVersion("25"))
    }

    @Test
    fun legacyScheme() {
        assertEquals(8, javaMajorVersion("1.8.0_392"))
        assertEquals(7, javaMajorVersion("1.7.0_80"))
    }

    @Test
    fun earlyAccessAndPreRelease() {
        assertEquals(21, javaMajorVersion("21-ea"))
        assertEquals(17, javaMajorVersion("17.0.2+13"))
    }

    @Test
    fun garbageReturnsZero() {
        assertEquals(0, javaMajorVersion(""))
        assertEquals(0, javaMajorVersion("   "))
        assertEquals(0, javaMajorVersion("abc"))
        assertEquals(0, javaMajorVersion("1"))
    }

    @Test
    fun minimumIsSeventeen() {
        assertTrue(javaOk("21.0.13"))
        assertTrue(javaOk("17"))
        assertTrue(javaOk("1.8.0_392").not()) // legacy JVM is below the floor
        assertFalse(javaOk("11.0.2"))
        assertFalse(javaOk("abc"))
    }
}
