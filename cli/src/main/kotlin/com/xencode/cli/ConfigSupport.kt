package com.xencode.cli

import com.xencode.core.provider.ProviderConfig

/**
 * Pure helpers for `xencode config`. Keeping them side-effect free means the
 * masking, header parsing and provider-validation wiring are unit-tested
 * without touching `~/.xencode/pc-config.json`.
 */

/**
 * Mask an API key for display: first 6 characters + "...". Short keys (the
 * common "none" placeholder for local endpoints) are shown whole plus the
 * ellipsis so the column stays recognizable.
 */
fun maskKey(key: String): String = key.take(6) + "..."

/**
 * Parse repeatable `--header k=v` values into a map.
 *
 * @return null and an error message for entries without `=`, empty keys, or
 * duplicate keys; the map otherwise.
 */
fun parseHeaderPairs(raw: List<String>): Pair<Map<String, String>, String?> {
    val out = LinkedHashMap<String, String>()
    for (entry in raw) {
        val trimmed = entry.trim()
        if (trimmed.isEmpty()) continue
        val idx = trimmed.indexOf('=')
        if (idx <= 0 || idx == trimmed.length - 1) {
            return emptyMap<String, String>() to "--header expects k=v form, got: `$trimmed`"
        }
        val k = trimmed.substring(0, idx).trim()
        val v = trimmed.substring(idx + 1).trim()
        if (out.containsKey(k)) {
            return emptyMap<String, String>() to "duplicate --header key: `$k`"
        }
        out[k] = v
    }
    return out to null
}

/**
 * Assemble a [ProviderConfig] from `config add` inputs. Validation is delegated
 * to [ProviderConfig.validate] (the same gate the Android side uses before
 * saving); the result is the rejection reason or null when acceptable.
 */
fun buildProvider(
    name: String,
    kind: String,
    baseUrl: String,
    apiKey: String,
    model: String,
    temperature: Double?,
    maxTokens: Int?,
    headers: Map<String, String>
): ProviderConfig = ProviderConfig(
    name = name.trim(),
    kind = kind.trim().lowercase(),
    baseUrl = baseUrl.trim(),
    apiKey = apiKey.trim(),
    model = model.trim(),
    temperature = temperature,
    maxTokens = maxTokens,
    extraHeaders = headers
)

/** True when `name` would collide with an existing provider entry. */
fun isDuplicateName(existing: List<ProviderConfig>, name: String): Boolean =
    existing.any { it.name.equals(name.trim(), ignoreCase = true) }
