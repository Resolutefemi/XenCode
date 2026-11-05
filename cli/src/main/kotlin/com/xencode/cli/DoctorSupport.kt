package com.xencode.cli

/**
 * Pure helpers for `xencode doctor`. Only the JVM version probe needs parsing;
 * everything else in DoctorCommand is direct environment inspection.
 */

/**
 * Extract the Java major version from `java.version` system property values.
 *
 * Handles the legacy `1.8.0_392` scheme (returns 8), modern `21.0.13`, early
 * access `21-ea`, and pre-release `17.0.2+13`. Returns 0 for unparsable input.
 */
fun javaMajorVersion(raw: String): Int {
    val s = raw.trim()
    if (s.isEmpty()) return 0
    val main = s.substringBefore('-').substringBefore('+')
    val parts = main.split('.').filter { it.isNotEmpty() }
    val first = parts.firstOrNull()?.toIntOrNull() ?: return 0
    // Legacy scheme: 1.x.y_zz encodes the real major in the second slot.
    return if (first == 1) (parts.getOrNull(1)?.toIntOrNull() ?: 0) else first
}

/** True when the running JVM satisfies the CLI's minimum (17, matching core's 21 toolchain with room). */
fun javaOk(raw: String): Boolean = javaMajorVersion(raw) >= 17
