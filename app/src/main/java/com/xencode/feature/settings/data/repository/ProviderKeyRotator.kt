package com.xencode.feature.settings.data.repository

import com.xencode.core.util.FileLogger
import com.xencode.feature.settings.domain.model.AIProviderConfig
import com.xencode.feature.settings.domain.model.KeyRotationStrategy
import com.xencode.feature.settings.domain.model.ProviderKey
import javax.inject.Inject
import javax.inject.Singleton

/** Result of one key switch, for the caller to rewrite credentials and show "switched to key N". */
data class KeySwitchResult(
    val newKey: String,
    /** 1-based index of the new key. */
    val newIndex: Int,
    val total: Int
)

/**
 * Multi-key rotator: decides which API Key a provider should use on a given session, and switches to the next key when one becomes unavailable.
 *
 * All state is runtime in-memory and not persisted: after a process restart it returns to the first key with cooldowns cleared — the point of multi-key is
 * to immediately bypass auth/quota/rate-limit problems, with no semantics that must survive across processes.
 *
 * **Session stickiness** is the core constraint here: server-side prompt caches are isolated per API Key, so rotating keys per request within one session would land
 * every turn on a key with no cache, zeroing the cache hit rate and worsening both cost and first-token latency. Therefore a key is switched only when the session first
 * acquires one, or when the current key is deemed unavailable; otherwise the same key is kept.
 */
@Singleton
class ProviderKeyRotator @Inject constructor() {

    /** Provider key configuration recorded by the most recent [activeKey], read during failure reporting for candidates and cooldown duration. */
    private data class KeySetup(
        val keys: List<ProviderKey>,
        val strategy: KeyRotationStrategy,
        val cooldownMillis: Long
    )

    private val lock = Any()

    private val setups = mutableMapOf<String, KeySetup>()

    /** providerId + key → cooldown deadline timestamp (milliseconds). */
    private val cooldownUntil = mutableMapOf<String, Long>()

    /** providerId → key value → current weight for smooth weighted round robin (runtime in-memory state). */
    private val weightedState = mutableMapOf<String, MutableMap<String, Long>>()

    /** providerId → most recently selected key, for side requests like balance queries to fetch the "current active key". */
    private val lastSelected = mutableMapOf<String, String>()

    /**
     * providerId + sessionId → the key this session sticks to. Sessions are unbounded, so an access-order LRU caps memory;
     * an evicted old session simply gets a key reassigned on its next request — no correctness issue.
     */
    private val sessionKeys = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean =
            size > MAX_TRACKED_SESSIONS
    }

    /**
     * Returns the key [config] should currently use on [sessionId]; returns null when there is no usable key, or all keys are cooling down.
     * Also snapshots the provider's key configuration for later use by [reportFailure].
     */
    fun activeKey(config: AIProviderConfig, sessionId: String?): String? {
        val keys = config.effectiveApiKeys
        if (keys.isEmpty()) return null
        synchronized(lock) {
            setups[config.id] = KeySetup(
                keys = keys,
                strategy = config.keyRotationStrategy,
                cooldownMillis = config.keyCooldownMinutes.coerceAtLeast(0) * 60_000L
            )

            val bindKey = sessionId?.let { sessionBinding(config.id, it) }
            val bound = bindKey?.let { sessionKeys[bindKey] }
            if (bound != null && keys.any { it.value == bound } && !isCoolingDown(config.id, bound)) return bound

            val chosen = pick(config.id, keys, config.keyRotationStrategy, exclude = emptySet()) ?: return null
            if (bindKey != null) sessionKeys[bindKey] = chosen
            lastSelected[config.id] = chosen
            return chosen
        }
    }

    /**
     * Read-only fetch of the provider's currently active key: creates no session binding and advances no rotation cursor.
     * For side requests like balance queries — they should reflect "which key chat is using" without disturbing the rotation rhythm.
     */
    fun currentKey(config: AIProviderConfig): String? {
        val keys = config.effectiveApiKeys
        if (keys.isEmpty()) return null
        synchronized(lock) {
            val last = lastSelected[config.id]
            if (last != null && keys.any { it.value == last } && !isCoolingDown(config.id, last)) return last
            return keys.firstOrNull { !isCoolingDown(config.id, it.value) }?.value ?: keys.first().value
        }
    }

    /**
     * Reports a failure attributable to [key]: **immediately** switches to the next usable key, puts [key] into cooldown and rebinds the session,
     * returning the switch result; returns null when there is no candidate to switch to (all candidates tried, or all cooling down) — in that case [key] is **not** cooled down,
     * avoiding locking out the last usable key and leaving the user completely unable to make requests for a while.
     *
     * [triedKeys] are the keys already tried in this request (including [key] itself), used to avoid retrying the same key within one request.
     */
    fun reportFailure(
        providerId: String,
        sessionId: String?,
        key: String,
        triedKeys: Set<String> = emptySet()
    ): KeySwitchResult? {
        synchronized(lock) {
            val setup = setups[providerId] ?: return null
            if (setup.keys.none { it.value == key }) return null

            // Confirm there is a switchable candidate first; otherwise do not cool down — cooling the last usable key would only leave the user fully unavailable for a while
            val next = pick(providerId, setup.keys, setup.strategy, exclude = triedKeys + key) ?: return null
            if (setup.cooldownMillis > 0) {
                cooldownUntil[stateKey(providerId, key)] = System.currentTimeMillis() + setup.cooldownMillis
            }
            sessionId?.let { sessionKeys[sessionBinding(providerId, it)] = next }
            lastSelected[providerId] = next
            val index = setup.keys.indexOfFirst { it.value == next } + 1
            FileLogger.i(TAG, "Key switch provider=$providerId ${key.masked()} → ${next.masked()} (no. $index/${setup.keys.size})")
            return KeySwitchResult(newKey = next, newIndex = index, total = setup.keys.size)
        }
    }

    /**
     * Picks an eligible key per strategy: excludes [exclude] (already tried this round) and cooling-down keys; keys with weight ≤ 0 do not participate;
     * returns null when none is eligible. No "all cooling down, so fall back to the earliest expiring one and retry" fallback — that would be hammering a key already known to be unusable.
     */
    private fun pick(
        providerId: String,
        keys: List<ProviderKey>,
        strategy: KeyRotationStrategy,
        exclude: Set<String>
    ): String? {
        val participants = keys.filter {
            it.weight > 0 && it.value !in exclude && !isCoolingDown(providerId, it.value)
        }
        if (participants.isEmpty()) return null
        return when (strategy) {
            KeyRotationStrategy.SEQUENTIAL -> participants.first().value
            KeyRotationStrategy.ROUND_ROBIN -> weightedRoundRobin(providerId, participants)
        }
    }

    /**
     * Smooth weighted round robin (SWRR): each round adds each candidate's own weight, picks the one with the highest current weight, then subtracts the total candidate weight from the chosen one.
     * No need to expand weights into a virtual list; weights can be in the millions. Distribution is even and never keeps landing on the same key.
     */
    private fun weightedRoundRobin(providerId: String, participants: List<ProviderKey>): String {
        val current = weightedState.getOrPut(providerId) { mutableMapOf() }
        var total = 0L
        var best = participants.first()
        var bestWeight = Long.MIN_VALUE
        for (candidate in participants) {
            val next = (current[candidate.value] ?: 0L) + candidate.weight
            current[candidate.value] = next
            total += candidate.weight
            if (next > bestWeight) {
                bestWeight = next
                best = candidate
            }
        }
        current[best.value] = (current[best.value] ?: 0L) - total
        return best.value
    }

    private fun isCoolingDown(providerId: String, key: String): Boolean {
        val until = cooldownUntil[stateKey(providerId, key)] ?: return false
        if (System.currentTimeMillis() >= until) {
            cooldownUntil.remove(stateKey(providerId, key))
            return false
        }
        return true
    }

    private fun stateKey(providerId: String, key: String) = "$providerId\u0000$key"

    private fun sessionBinding(providerId: String, sessionId: String) = "$providerId\u0000$sessionId"

    /** Log masking: keeps only the last 4 characters, avoiding writing full keys into log files. */
    private fun String.masked(): String = if (length <= 4) "****" else "****${takeLast(4)}"

    private companion object {
        const val TAG = "ProviderKeyRotator"
        const val MAX_TRACKED_SESSIONS = 200
    }
}
