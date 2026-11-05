package com.xencode.feature.agent.domain.tool

import kotlinx.serialization.json.JsonElement

/**
 * Base class for tools that can only run with session context ([com.xencode.feature.agent.domain.model.AgentContext]).
 *
 * The unified workflow `StatefulAgentWorkflow` routes every tool through [executeWithContext] and never calls [execute] directly.
 * So subclasses only override [executeWithContext], and this base class implements [execute] as "throw `UnsupportedOperationException`"
 * to state clearly, at compile time and call time, that this tool has no context-free execution path. Compared with "return a fixed Error",
 * throwing exposes a mistaken call immediately (e.g. someone later adding a non-context execution branch and forgetting the context path), instead of
 * disguising "missing context" as a seemingly normal tool failure.
 */
abstract class AbstractContextualTool : AgentTool() {

    /**
     * This tool only supports [executeWithContext]; this path is not provided and calling it is a programming error.
     * The implementation exists only to satisfy the abstract signature of [AgentTool.execute].
     */
    final override suspend fun execute(args: Map<String, JsonElement>): ToolResult {
        throw UnsupportedOperationException(
            "${javaClass.simpleName} can only run via executeWithContext and needs AgentContext."
        )
    }
}
