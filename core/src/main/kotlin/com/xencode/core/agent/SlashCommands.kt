package com.xencode.core.agent

/**
 * The shared slash-command table for the interactive surfaces (CLI chat REPL,
 * desktop chat pane). One parser, one help text — front ends only implement the
 * rendering and the handful of surface-local actions.
 */
object SlashCommands {

    data class Parsed(val name: String, val arg: String) {
        val hasArg: Boolean get() = arg.isNotBlank()
    }

    /** name -> one-line help (rendered by /help in both front ends). */
    val COMMANDS: List<Pair<String, String>> = listOf(
        "/help" to "list the available commands",
        "/clear" to "clear the conversation history",
        "/compact" to "compact the context now (keeps first ask + recent turns)",
        "/model" to "show the active provider and model",
        "/todo" to "show the shared TODO list; /todo add <text> · /todo done <text>",
        "/checkpoint" to "create a checkpoint; /checkpoint list · /checkpoint restore <id>",
        "/session" to "show session info; /session list · /session save",
        "/goal" to "goal DAG: /goal list · /goal add <title> after g1 · /goal done g1 · /goal run",
        "/dag" to "show the goal DAG",
        "/xp" to "XP level, streak and recent awards",
        "/costs" to "lifetime token usage from the shared database (llm_call_records)",
        "/mcp" to "MCP server status and tools",
        "/events" to "recent sync events (Windows UI + Mobile App feed)",
        "/decide" to "open decision cards; /decide approve|reject <id>",
        "/radar" to "spec radar (test results) and command streak",
        "/system" to "print the current system prompt",
        "/yolo" to "toggle YOLO permission mode (allow everything)",
        "/exit" to "leave the chat"
    )

    /** Parses "/name arg..." (case-insensitive name); null when not a slash command. */
    fun parse(line: String): Parsed? {
        val trimmed = line.trim()
        if (!trimmed.startsWith("/")) return null
        val namePart = trimmed.takeWhile { it != ' ' }
        if (namePart.length < 2) return null
        val name = namePart.lowercase()
        val arg = trimmed.substring(namePart.length).trim()
        return Parsed(name, arg)
    }

    fun help(): String = COMMANDS.joinToString("\n") { (cmd, desc) -> "  ${cmd.padEnd(14)} $desc" }

    /** true when the command must be handled by the front end itself. */
    fun isLocal(name: String): Boolean = name in setOf("/help", "/clear", "/exit")
}
