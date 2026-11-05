package com.xencode.feature.agent.domain.tool

import java.util.Collections
import javax.inject.Singleton

@Singleton
class ToolRegistry {
    // LinkedHashMap preserves registration order (fixed order for built-in tools + dynamic MCP appends), so adding/removing tools does not
    // drift the prefix order and break the implicit prefix cache; synchronizedMap keeps concurrent register/read safe.
    private val tools = Collections.synchronizedMap(LinkedHashMap<String, AgentTool>())

    fun register(name: String, tool: AgentTool) {
        tools[name] = tool
    }

    fun getTool(name: String): AgentTool? {
        return tools[name]
    }

    fun unregister(name: String) {
        tools.remove(name)
    }

    fun getToolNames(): Set<String> {
        return tools.keys.toSet()
    }

    /**
     * Returns the available tool list. In PLAN mode it still returns all tool definitions (so the AI knows they exist and can reference them in plans);
     * write tools are intercepted at runtime by [com.xencode.feature.agent.domain.permission.ToolPermissionPolicyEngine] and returned as PLAN_MODE_REJECTED.
     */
    fun getAvailableTools(): List<AgentTool> {
        synchronized(tools) {
            return tools.values.toList()
        }
    }

    fun hasTool(name: String): Boolean {
        return tools.containsKey(name)
    }
}
