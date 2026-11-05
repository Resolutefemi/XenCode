package com.xencode.feature.settings.data.repository

import com.xencode.feature.settings.domain.model.AIProviderConfig
import com.xencode.feature.settings.domain.model.KeyRotationStrategy
import com.xencode.feature.settings.domain.model.ProviderKey
import com.xencode.feature.settings.domain.model.ProviderType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderKeyRotatorTest {

    private fun config(
        id: String = "provider-1",
        apiKey: String = "key-1",
        apiKeys: List<ProviderKey> = emptyList(),
        multiKeyEnabled: Boolean = false,
        keyRotationStrategy: KeyRotationStrategy = KeyRotationStrategy.SEQUENTIAL,
        keyFailoverThreshold: Int = 2,
        keyCooldownMinutes: Int = 5
    ) = AIProviderConfig(
        id = id,
        name = "test provider",
        type = ProviderType.OPENAI,
        apiKey = apiKey,
        baseUrl = "https://example.com",
        defaultModel = "gpt-test",
        multiKeyEnabled = multiKeyEnabled,
        apiKeys = apiKeys,
        keyRotationStrategy = keyRotationStrategy,
        keyFailoverThreshold = keyFailoverThreshold,
        keyCooldownMinutes = keyCooldownMinutes
    )

    /** Multi-key mode: effectiveApiKeys takes apiKeys (those non-blank after trimming). */
    private fun multiKeyConfig(
        keys: List<String>,
        strategy: KeyRotationStrategy = KeyRotationStrategy.SEQUENTIAL,
        threshold: Int = 2,
        cooldownMinutes: Int = 5,
        id: String = "provider-1"
    ) = config(
        id = id,
        apiKey = "unused",
        apiKeys = keys.map { ProviderKey(it) },
        multiKeyEnabled = true,
        keyRotationStrategy = strategy,
        keyFailoverThreshold = threshold,
        keyCooldownMinutes = cooldownMinutes
    )

    // ---------- activeKey ----------

    /** A single key is returned directly, no selection logic needed. */
    @Test
    fun activeKey_singleKey_returnsIt() {
        val rotator = ProviderKeyRotator()

        assertEquals("key-1", rotator.activeKey(config(), "session-1"))
    }

    /** Returns null when no key is usable (single key blank, multi-key unconfigured). */
    @Test
    fun activeKey_emptyKeys_returnsNull() {
        val rotator = ProviderKeyRotator()

        assertNull(rotator.activeKey(config(apiKey = ""), "session-1"))
    }

    /** Sequential strategy: repeated calls in the same session stick to the same key (server-side prompt caches are isolated per key). */
    @Test
    fun activeKey_sequential_sameSessionIsSticky() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))

        val first = rotator.activeKey(cfg, "session-1")
        repeat(5) {
            assertEquals(first, rotator.activeKey(cfg, "session-1"))
        }
    }

    /** Sequential strategy: every new session starts from the first non-cooling key. */
    @Test
    fun activeKey_sequential_newSessionStartsFromFirst() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))

        assertEquals("key-a", rotator.activeKey(cfg, "session-1"))
        assertEquals("key-a", rotator.activeKey(cfg, "session-2"))
        assertEquals("key-a", rotator.activeKey(cfg, "session-3"))
    }

    /** Round-robin strategy: new sessions take turns picking the starting key. */
    @Test
    fun activeKey_roundRobin_rotatesStartingKeyPerNewSession() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(
            keys = listOf("key-a", "key-b", "key-c"),
            strategy = KeyRotationStrategy.ROUND_ROBIN
        )

        assertEquals("key-a", rotator.activeKey(cfg, "session-1"))
        assertEquals("key-b", rotator.activeKey(cfg, "session-2"))
        assertEquals("key-c", rotator.activeKey(cfg, "session-3"))
        assertEquals("key-a", rotator.activeKey(cfg, "session-4"))
    }

    /** Under round-robin the same session still sticks to one key; no cursor drift. */
    @Test
    fun activeKey_roundRobin_sameSessionStaysSticky() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(
            keys = listOf("key-a", "key-b"),
            strategy = KeyRotationStrategy.ROUND_ROBIN
        )

        val first = rotator.activeKey(cfg, "session-1")
        repeat(3) {
            assertEquals(first, rotator.activeKey(cfg, "session-1"))
        }
        // Other sessions are unaffected and keep rotating
        assertEquals("key-b", rotator.activeKey(cfg, "session-2"))
    }

    /** With a null sessionId no binding is created: round-robin advances the cursor on every call. */
    @Test
    fun activeKey_nullSession_roundRobinAdvancesEveryCall() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(
            keys = listOf("key-a", "key-b"),
            strategy = KeyRotationStrategy.ROUND_ROBIN
        )

        assertEquals("key-a", rotator.activeKey(cfg, null))
        assertEquals("key-b", rotator.activeKey(cfg, null))
        assertEquals("key-a", rotator.activeKey(cfg, null))
    }

    /** A key in cooldown is not picked by new sessions. */
    @Test
    fun activeKey_skipsCoolingDownKey() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"), cooldownMinutes = 60)

        // First establish the config snapshot and select key-a; then key-a fails repeatedly up to the threshold and enters cooldown
        rotator.activeKey(cfg, "session-1")
        rotator.reportFailure("provider-1", "session-1", "key-a")
        rotator.reportFailure("provider-1", "session-1", "key-a")

        // A new session must not pick the cooling key-a
        assertEquals("key-b", rotator.activeKey(cfg, "session-2"))
    }

    // ---------- currentKey ----------

    /** currentKey returns the key most recently selected by activeKey. */
    @Test
    fun currentKey_returnsLastSelectedKey() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))

        rotator.activeKey(cfg, "session-1") // selected key-a

        assertEquals("key-a", rotator.currentKey(cfg))
    }

    /** currentKey does not advance the round-robin cursor: after a read-only call, the first new session still starts from the original cursor position. */
    @Test
    fun currentKey_doesNotAdvanceRoundRobinCursor() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(
            keys = listOf("key-a", "key-b"),
            strategy = KeyRotationStrategy.ROUND_ROBIN
        )

        // Only currentKey calls (no activeKey)
        rotator.currentKey(cfg)
        rotator.currentKey(cfg)

        // The cursor has not advanced; the first new session still starts from key-a
        assertEquals("key-a", rotator.activeKey(cfg, "session-1"))
    }

    /** On a cold start (no prior calls at all), currentKey returns the first non-cooling key. */
    @Test
    fun currentKey_noSelection_returnsFirstUsableKey() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))

        // currentKey does not depend on the config snapshot built by activeKey and works directly
        assertEquals("key-a", rotator.currentKey(cfg))
    }

    // ---------- reportFailure ----------

    /** Failures switch immediately: no counting is accumulated; the first report already returns the switch result. */
    @Test
    fun reportFailure_switchesImmediately() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))

        rotator.activeKey(cfg, "session-1") // establish the config snapshot

        val switched = rotator.reportFailure("provider-1", "session-1", "key-a")

        assertNotNull(switched)
        assertEquals("key-b", switched!!.newKey)
        assertEquals(2, switched.newIndex)
        assertEquals(2, switched.total)
    }

    /** After a failure switch, the session rebinds to the new key: subsequent calls in the same session all use it. */
    @Test
    fun reportFailure_rebindsSessionToNewKey() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))

        rotator.activeKey(cfg, "session-1") // key-a

        val switched = rotator.reportFailure("provider-1", "session-1", "key-a")

        assertNotNull(switched)
        assertEquals("key-b", switched!!.newKey)
        assertEquals(2, switched.newIndex)
        assertEquals(2, switched.total)
        // The session is rebound to the new key
        assertEquals("key-b", rotator.activeKey(cfg, "session-1"))
    }

    /** Returns null when no config snapshot was ever established (activeKey never called). */
    @Test
    fun reportFailure_withoutPriorSetup_returnsNull() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))

        assertNull(rotator.reportFailure("provider-1", "session-1", "key-a"))
    }

    /** A single-key config has no switch target: returns null. */
    @Test
    fun reportFailure_singleKey_returnsNull() {
        val rotator = ProviderKeyRotator()
        val cfg = config()

        rotator.activeKey(cfg, "session-1")

        assertNull(rotator.reportFailure("provider-1", "session-1", "key-1"))
        assertNull(rotator.reportFailure("provider-1", "session-1", "key-1"))
    }

    /** Reporting a key that does not belong to this provider: returns null. */
    @Test
    fun reportFailure_unknownKey_returnsNull() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"))

        rotator.activeKey(cfg, "session-1")

        assertNull(rotator.reportFailure("provider-1", "session-1", "key-unknown"))
    }

    /** After a failure switch, the original key enters cooldown (when the cooldown duration is non-zero). */
    @Test
    fun reportFailure_switchAppliesCooldown() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"), cooldownMinutes = 60)

        rotator.activeKey(cfg, "session-1") // key-a
        rotator.reportFailure("provider-1", "session-1", "key-a")
        rotator.reportFailure("provider-1", "session-1", "key-a")

        // key-a is cooling down; new sessions no longer pick it
        assertEquals("key-b", rotator.activeKey(cfg, "session-2"))
    }

    /** A cooldown duration of 0 means no cooldown: after the switch, the original key can still be picked by new sessions. */
    @Test
    fun reportFailure_cooldownZero_noCooldownApplied() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"), cooldownMinutes = 0)

        rotator.activeKey(cfg, "s1") // key-a
        rotator.reportFailure("provider-1", "s1", "key-a")
        rotator.reportFailure("provider-1", "s1", "key-a")

        // No cooldown, key-a is usable again; under the sequential strategy a new session starts from the first non-cooling key
        assertEquals("key-a", rotator.activeKey(cfg, "s2"))
    }

    /** Under round-robin, failure switching works too, and the switch keeps advancing the round-robin cursor. */
    @Test
    fun reportFailure_roundRobin_switchesAndAdvancesCursor() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(
            keys = listOf("key-a", "key-b", "key-c"),
            strategy = KeyRotationStrategy.ROUND_ROBIN
        )

        rotator.activeKey(cfg, "s1") // key-a (cursor 0→1)
        val switched = rotator.reportFailure("provider-1", "s1", "key-a")

        // Cursor=1: candidates [b,c,a], skipping key-a → key-b
        assertEquals("key-b", switched!!.newKey)
        // The cursor advanced (1→2); the next new session starts from key-c
        assertEquals("key-c", rotator.activeKey(cfg, "s2"))
    }

    /** Already-tried keys are not returned again; null once every candidate has been tried. */
    @Test
    fun reportFailure_excludesTriedKeysAndReturnsNullWhenExhausted() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b", "key-c"))

        rotator.activeKey(cfg, "session-1")
        val switched = rotator.reportFailure(
            "provider-1", "session-1", "key-c", setOf("key-a", "key-b", "key-c")
        )

        assertNull(switched)
    }

    /** With no switchable candidate, the current key is not cooled down, avoiding locking up the last usable key. */
    @Test
    fun reportFailure_noAlternative_doesNotCoolDownCurrentKey() {
        val rotator = ProviderKeyRotator()
        val cfg = multiKeyConfig(keys = listOf("key-a", "key-b"), cooldownMinutes = 60)

        rotator.activeKey(cfg, "s1") // key-a
        rotator.reportFailure("provider-1", "s1", "key-a") // switch to key-b, key-a cools down

        // key-b is the last usable candidate: returns null and is not cooled down
        assertNull(rotator.reportFailure("provider-1", "s1", "key-b"))
        assertEquals("key-b", rotator.activeKey(cfg, "s2"))
    }

    // ---------- Weighted round-robin ----------

    /** Weighted round-robin: new sessions are allocated by weight ratio (with 1:3, the high-weight key gets about three times the low-weight one). */
    @Test
    fun activeKey_weightedRoundRobin_distributesByWeight() {
        val rotator = ProviderKeyRotator()
        val cfg = config(
            apiKeys = listOf(ProviderKey("key-a", 1), ProviderKey("key-b", 3)),
            multiKeyEnabled = true,
            keyRotationStrategy = KeyRotationStrategy.ROUND_ROBIN
        )

        val counts = mutableMapOf<String, Int>()
        repeat(40) { i ->
            val key = rotator.activeKey(cfg, "session-$i")!!
            counts[key] = (counts[key] ?: 0) + 1
        }

        assertEquals(10, counts["key-a"])
        assertEquals(30, counts["key-b"])
    }

    /** Keys with weight ≤ 0 do not join scheduling: never picked under round-robin. */
    @Test
    fun activeKey_nonPositiveWeightIsExcluded() {
        val rotator = ProviderKeyRotator()
        val cfg = config(
            apiKeys = listOf(ProviderKey("key-a", 0), ProviderKey("key-b", 1)),
            multiKeyEnabled = true,
            keyRotationStrategy = KeyRotationStrategy.ROUND_ROBIN
        )

        repeat(5) { i ->
            assertEquals("key-b", rotator.activeKey(cfg, "session-$i"))
        }
    }

    /** When every key has weight ≤ 0 there is nothing to schedule; returns null. */
    @Test
    fun activeKey_allNonPositiveWeights_returnsNull() {
        val rotator = ProviderKeyRotator()
        val cfg = config(
            apiKeys = listOf(ProviderKey("key-a", 0), ProviderKey("key-b", 0)),
            multiKeyEnabled = true
        )

        assertNull(rotator.activeKey(cfg, "session-1"))
    }
}