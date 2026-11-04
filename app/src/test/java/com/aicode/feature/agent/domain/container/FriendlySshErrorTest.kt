package com.aicode.feature.agent.domain.container

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * SSH 连接异常 → 友好提示文案的翻译逻辑（[friendlySshError]）。
 */
class FriendlySshErrorTest {

    @Test
    fun connection_refused() {
        assertEquals(
            "Cannot reach the SSH server (connection refused). Confirm the host is up and the port is correct",
            friendlySshError(RuntimeException("ECONNREFUSED (Connection refused)"))
        )
        assertEquals(
            "Cannot reach the SSH server (connection refused). Confirm the host is up and the port is correct",
            friendlySshError(RuntimeException("Connection refused: 10.0.0.2:22"))
        )
    }

    @Test
    fun unknown_host() {
        assertEquals(
            "Cannot reach the SSH server (unknown host). Check the hostname",
            friendlySshError(RuntimeException("UnknownHostException: foo.example.com"))
        )
        assertEquals(
            "Cannot reach the SSH server (unknown host). Check the hostname",
            friendlySshError(RuntimeException("unknown host foo"))
        )
    }

    @Test
    fun auth_failed() {
        assertEquals(
            "SSH auth failed. Check the username and password",
            friendlySshError(RuntimeException("Auth fail"))
        )
        assertEquals(
            "SSH auth failed. Check the username and password",
            friendlySshError(RuntimeException("authentication failed"))
        )
    }

    @Test
    fun network_unreachable() {
        assertEquals(
            "Network is unreachable. Check the connection",
            friendlySshError(RuntimeException("Network is unreachable"))
        )
    }

    @Test
    fun timeout() {
        assertEquals(
            "Timed out connecting to the SSH server. Check the network or server status",
            friendlySshError(RuntimeException("SocketTimeoutException: connect timed out"))
        )
        assertEquals(
            "Timed out connecting to the SSH server. Check the network or server status",
            friendlySshError(RuntimeException("timed out"))
        )
    }

    @Test
    fun not_connected() {
        assertEquals(
            "SSH is not connected. Wait for it to recover or check Settings",
            friendlySshError(RuntimeException("SSH 未连接"))
        )
    }

    @Test
    fun unknown_error_falls_back() {
        assertEquals("SSH connection failed: boom", friendlySshError(RuntimeException("boom")))
        assertEquals("SSH connection failed: unknown error", friendlySshError(RuntimeException()))
    }
}
