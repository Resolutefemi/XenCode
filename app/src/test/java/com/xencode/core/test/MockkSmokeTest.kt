package com.xencode.core.test

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Test infrastructure smoke check: verifies MockK + kotlinx-coroutines-test work in the current JVM environment
 * (dependency resolution, bytecode agent, and coroutine virtual time all function correctly).
 */
class MockkSmokeTest {

    private interface Greeter {
        suspend fun greet(name: String): String
    }

    @Test
    fun mockkAndRunTest_workTogether() = runTest {
        val greeter = mockk<Greeter>()
        coEvery { greeter.greet(any()) } returns "hello"

        assertEquals("hello", greeter.greet("world"))
    }
}