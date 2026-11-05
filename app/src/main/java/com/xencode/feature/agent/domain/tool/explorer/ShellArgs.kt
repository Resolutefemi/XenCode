package com.xencode.feature.agent.domain.tool.explorer

/**
 * Shared shell argument parsing and security allowlist logic for the explorer tools (search/list).
 *
 * Models often write shell pipes like `| head -30` directly into tool args (e.g. `search(args="-n \"foo\" src | head -30")`).
 * This splits `|` out of the rg/ls arguments and parses it separately: **only head line truncation is allowed**; all other pipe
 * commands are rejected, preventing arbitrary command execution in the container via pipe syntax.
 */

/** Splits args into tokens using shell lexing; returns null on an unclosed quote. `\|`/`|` parse as the standalone token `|`; a `|` inside quotes is a literal. */
fun parseShellWords(input: String): List<String>? {
    val result = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var tokenStarted = false
    var i = 0
    while (i < input.length) {
        val c = input[i]
        when {
            quote == '\'' -> {
                if (c == '\'') quote = null else current.append(c)
                tokenStarted = true
            }
            quote == '"' -> {
                when (c) {
                    '"' -> quote = null
                    '\\' -> {
                        if (i + 1 < input.length) {
                            i++
                            current.append(input[i])
                        } else {
                            current.append(c)
                        }
                    }
                    else -> current.append(c)
                }
                tokenStarted = true
            }
            c.isWhitespace() -> {
                if (tokenStarted) {
                    result.add(current.toString())
                    current.clear()
                    tokenStarted = false
                }
            }
            c == '\'' || c == '"' -> {
                quote = c
                tokenStarted = true
            }
            c == '\\' -> {
                if (i + 1 < input.length) {
                    i++
                    current.append(input[i])
                } else {
                    current.append(c)
                }
                tokenStarted = true
            }
            else -> {
                current.append(c)
                tokenStarted = true
            }
        }
        i++
    }
    if (quote != null) return null
    if (tokenStarted) result.add(current.toString())
    return result
}

/**
 * Splits tokens on standalone `|` into (tokens before the pipe, list of pipe segments).
 * With no pipe, returns (tokens, empty list); returns null if there are no arguments before `|`.
 * A `|` inside quotes (e.g. `"a|b"`) belongs to the token itself and does not trigger a split.
 */
fun splitPipes(tokens: List<String>): Pair<List<String>, List<List<String>>>? {
    val pipeIndex = tokens.indexOf("|")
    if (pipeIndex < 0) return tokens to emptyList()
    val headTokens = tokens.subList(0, pipeIndex)
    if (headTokens.isEmpty()) return null
    val segments = mutableListOf<List<String>>()
    var i = pipeIndex
    while (i < tokens.size) {
        if (tokens[i] != "|") return null
        i++
        val segStart = i
        while (i < tokens.size && tokens[i] != "|") i++
        segments += tokens.subList(segStart, i)
    }
    return headTokens to segments
}

/**
 * head pipe segment allowlist: only `head` (default 10 lines), `head -N`, `head -n N`, where N must be purely numeric.
 * Returns the number of lines the segment may output; returns null if the command is not head, or carries other arguments (`;`, redirection, `head -c`, etc.).
 */
fun headLinesOf(seg: List<String>): Int? = when {
    seg.isEmpty() -> null
    seg[0] != "head" -> null
    seg.size == 1 -> 10
    seg.size == 2 -> {
        val n = seg[1]
        if (n.length > 1 && n[0] == '-' && n.drop(1).all(Char::isDigit)) n.drop(1).toInt() else null
    }
    seg.size == 3 && seg[1] == "-n" -> {
        val n = seg[2]
        if (n.isNotEmpty() && n.all(Char::isDigit)) n.toInt() else null
    }
    else -> null
}

/**
 * Assembles the rg search command passed to the container: base arguments + all argument tokens (single-quoted, `~/` expanded to [home]);
 * if a pipe is present, the allowlist-validated `| head ...` segment is appended verbatim. Returns null if a pipe segment contains a non-head command or invalid arguments.
 *
 * [home] is the home of the command execution environment (/root for the local PRoot container, the remote user's home for remote SSH),
 * used to expand `~/` arguments — once quoted, the shell will not expand `~` automatically, so it is expanded explicitly when building the command.
 */
fun buildSearchCommand(tokens: List<String>, home: String = "/root"): String? {
    val (rgTokens, pipeSegments) = splitPipes(tokens) ?: return null
    val args = mutableListOf(
        "rg",
        "--line-number",
        "--no-heading",
        "--with-filename",
        "--color",
        "never"
    )
    args.addAll(rgTokens.map { shellQuote(expandTilde(it, home)) })
    var command = args.joinToString(" ")
    for (seg in pipeSegments) {
        if (headLinesOf(seg) == null) return null
        command += " | " + seg.joinToString(" ")
    }
    return command
}

/** Expands path arguments starting with `~`/`~/` to [home] paths, since the shell will not expand `~` once wrapped in single quotes. */
fun expandTilde(arg: String, home: String = "/root"): String =
    when {
        arg == "~" -> home
        arg.startsWith("~/") -> home.trimEnd('/') + arg.removePrefix("~")
        else -> arg
    }

/** Wraps the value in single quotes; safe for shell commands. */
fun shellQuote(value: String): String {
    return "'" + value.replace("'", "'\"'\"'") + "'"
}
