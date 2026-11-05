package com.xencode.feature.git.domain

/**
 * A git command ended with a non-zero exit code (a real failure), as opposed to the process crashing. Carries git's
 * own output (stderr preferred), letting the UI correct a false "success" into "failure + reason": no identity, unauthorized, merge conflicts, etc.
 *
 * @param output Combined git stdout+stderr text, used as [message] for UI display and log triage.
 */
open class GitCommandFailureException(val output: String) : Exception(output)

/**
 * git output exceeded the engine cap ([com.xencode.feature.agent.domain.container.MAX_UNBOUNDED_CHARS]) and was truncated.
 * Extends [GitCommandFailureException] so existing failure handling (toast) applies automatically; callers
 * needing full content (diff fetching file content) catch it separately and degrade to a "file too large" notice instead of parsing partial data.
 */
class GitOutputTooLargeException : GitCommandFailureException("Output too large and truncated; narrow the scope and retry")
