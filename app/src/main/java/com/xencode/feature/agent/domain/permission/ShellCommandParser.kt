package com.xencode.feature.agent.domain.permission

/**
 * Safe static analyzer for shell commands — the security core of the authorization system.
 *
 * Responsibility: split a command line into "top-level segments" and tokenize them, while deciding whether the command is **statically decidable**.
 * Auto-allow happens only when "decidable AND every segment hits an ALLOW rule"; anything not statically decidable downgrades the command
 * to "must prompt, non-memorable", preventing the authorization from being bypassed.
 *
 * Security conventions (keep them):
 * - Quote-aware: `&& || | ;` inside single/double quotes are not treated as separators, so no wrong splits.
 * - Triggers for "not statically decidable" ([Analysis.analyzable] = false):
 *     · Command substitution `$(...)`, backticks `` `...` `` (still effective inside double quotes, not inside single quotes);
 *     · Subshell/grouping parens `(` `)`, process substitution `<(` `>(` (all caught via a bare `(`);
 *     · Output redirection to an **absolute path** (`> /...`, `>> /...`, including quoted absolute paths) — can bypass cwd to write outside the workspace;
 *     · Unclosed quotes.
 *   Relative redirection (landing inside cwd=workspace) is considered safe. Input redirection `<` doesn't trigger (lower read risk).
 * - Matching uses the **literal first token**: `/usr/bin/node` is not matched by a `node` rule (prefer asking too much over allowing wrongly).
 * - Leading env assignments (`FOO=bar cmd`) are skipped when taking the program name/matching, so `FOO=bar git ...` is still handled as `git`.
 */
object ShellCommandParser {

    /**
     * @param segments Token list of each top-level segment (quotes stripped, whitespace-tokenized; redirect operators are not tokens).
     * @param analyzable Whether statically decidable; false means never auto-allowed/remembered, only allow-once.
     */
    data class Analysis(
        val segments: List<List<String>>,
        val analyzable: Boolean
    )

    private const val NONE = 0
    private const val SINGLE = 1
    private const val DOUBLE = 2

    /** Leading env assignment of the form `NAME=value`. */
    private val ENV_ASSIGN = Regex("^[A-Za-z_][A-Za-z0-9_]*=.*$")

    fun analyze(command: String): Analysis {
        val segments = mutableListOf<List<String>>()
        var current = mutableListOf<String>()
        val token = StringBuilder()
        var tokenStarted = false
        var analyzable = true
        var quote = NONE
        // True when the previous operator was an output redirect (`>`/`>>`): if the next token is an absolute path, the command is not statically decidable.
        var expectRedirectTarget = false
        var i = 0
        val n = command.length

        fun flushToken() {
            if (tokenStarted) {
                val t = token.toString()
                if (expectRedirectTarget) {
                    if (t.startsWith("/")) analyzable = false
                    expectRedirectTarget = false
                }
                current.add(t)
                token.setLength(0)
                tokenStarted = false
            }
        }
        fun flushSegment() {
            flushToken()
            if (current.isNotEmpty()) {
                segments.add(current)
                current = mutableListOf()
            }
        }

        while (i < n) {
            val c = command[i]
            when (quote) {
                SINGLE -> {
                    if (c == '\'') quote = NONE else { token.append(c); tokenStarted = true }
                    i++
                }
                DOUBLE -> {
                    when {
                        c == '"' -> { quote = NONE; tokenStarted = true; i++ }
                        // Command substitution/backticks still execute inside double quotes → not statically decidable.
                        c == '`' -> { analyzable = false; token.append(c); tokenStarted = true; i++ }
                        c == '$' && i + 1 < n && command[i + 1] == '(' -> {
                            analyzable = false; token.append(c); tokenStarted = true; i++
                        }
                        else -> { token.append(c); tokenStarted = true; i++ }
                    }
                }
                else -> { // outside quotes
                    when {
                        c == '\'' -> { quote = SINGLE; tokenStarted = true; i++ }
                        c == '"' -> { quote = DOUBLE; tokenStarted = true; i++ }
                        c == '\\' -> {
                            // A trailing `\<newline>` line continuation: skipped entirely; other escapes take the next character literally.
                            if (i + 1 < n) {
                                val nx = command[i + 1]
                                if (nx != '\n') { token.append(nx); tokenStarted = true }
                                i += 2
                            } else i++
                        }
                        // Backticks / grouping parens / process substitution `<( >(` (all involve a bare `(`) → not statically decidable.
                        c == '`' || c == '(' || c == ')' -> {
                            analyzable = false; token.append(c); tokenStarted = true; i++
                        }
                        // Top-level segment separators.
                        c == '\n' || c == ';' -> { flushSegment(); i++ }
                        c == '&' -> { flushSegment(); i += if (i + 1 < n && command[i + 1] == '&') 2 else 1 }
                        c == '|' -> { flushSegment(); i += if (i + 1 < n && command[i + 1] == '|') 2 else 1 }
                        // Redirection: the operator itself is not a token; output redirects are flagged so the target token can be checked for an absolute path.
                        c == '>' -> { flushToken(); i += if (i + 1 < n && command[i + 1] == '>') 2 else 1; expectRedirectTarget = true }
                        c == '<' -> { flushToken(); i++ }
                        c.isWhitespace() -> { flushToken(); i++ }
                        else -> { token.append(c); tokenStarted = true; i++ }
                    }
                }
            }
        }
        if (quote != NONE) analyzable = false // unclosed quote: untrustworthy
        flushSegment()
        return Analysis(segments, analyzable)
    }

    /** Effective tokens after skipping leading env assignments (used for taking the program name and matching). */
    private fun effectiveTokens(tokens: List<String>): List<String> {
        var idx = 0
        while (idx < tokens.size && ENV_ASSIGN.matches(tokens[idx])) idx++
        return if (idx == 0) tokens else tokens.subList(idx, tokens.size)
    }

    /** Command prefix remembered for this segment by default = the program-name token; null for an empty segment. */
    fun programPrefix(tokens: List<String>): String? = effectiveTokens(tokens).firstOrNull()

    /**
     * Known "subcommand dispatcher" programs: their first non-flag argument is a subcommand (`git pull`/`npm install`/`cargo build`).
     * For these, "Always allow" should remember the subcommand too, so remembering just `git` doesn't also allow
     * `git push`/`git reset --hard` and the like. Non-dispatchers are still remembered whole by program name (`cat`/`ls` etc.).
     */
    private val SUBCOMMAND_DISPATCHERS = setOf(
        "git", "gh",
        "npm", "npx", "yarn", "pnpm", "pnpx",
        "cargo", "rustup",
        "go",
        "pip", "pip3", "poetry", "uv", "conda",
        "gradle", "mvn", "sbt",
        "docker", "kubectl", "helm",
        "adb", "fastlane",
        "brew", "apt", "apt-get", "gem", "bundle",
        "az", "aws", "gcloud", "terraform", "ansible"
    )

    data class RmInfo(
        val isRm: Boolean,
        val isRecursive: Boolean,
        val isWildcard: Boolean,
        val targetPaths: List<String>
    )

    /** Parse whether this command segment is an rm deletion and its argument info (recursive or not, wildcards, and target paths). */
    fun parseRmInfo(tokens: List<String>): RmInfo {
        val eff = effectiveTokens(tokens)
        val program = eff.firstOrNull()
        if (program != "rm" && program?.endsWith("/rm") != true) {
            return RmInfo(isRm = false, isRecursive = false, isWildcard = false, targetPaths = emptyList())
        }
        var isRecursive = false
        var isWildcard = false
        val targetPaths = mutableListOf<String>()
        var hasDoubleDash = false
        for (i in 1 until eff.size) {
            val t = eff[i]
            if (!hasDoubleDash && t == "--") {
                hasDoubleDash = true
                continue
            }
            if (!hasDoubleDash && t.startsWith("-") && t != "-") {
                if (t == "--recursive" || (!t.startsWith("--") && (t.contains('r') || t.contains('R')))) {
                    isRecursive = true
                }
                continue
            }
            if (t.contains('*') || t.contains('?')) {
                isWildcard = true
            }
            targetPaths.add(t)
        }
        return RmInfo(isRm = true, isRecursive = isRecursive, isWildcard = isWildcard, targetPaths = targetPaths)
    }

    /**
     * The command prefix rememberable by "Always allow":
     * - rm handled precisely: recursive/wildcard deletions are non-memorable; a plain single-file deletion is remembered precisely as the full command prefix;
     * - Non-dispatchers: program name only (`cat`/`ls`), same as [programPrefix];
     * - Subcommand dispatchers: program name + the first following non-flag subcommand (`git pull`/`git clone`/`npm install`),
     *   degrading to the program name alone when a flag immediately follows (e.g. `git --version`). Null for an empty segment.
     *
     * Matching still uses [matches]'s token-prefix semantics: `git pull` matches `git pull origin main` but not `git clone`,
     * so different subcommands are remembered independently and never cross-allow.
     */
    fun rememberablePrefix(tokens: List<String>): String? {
        val eff = effectiveTokens(tokens)
        val program = eff.firstOrNull() ?: return null
        val rmInfo = parseRmInfo(tokens)
        if (rmInfo.isRm) {
            if (rmInfo.targetPaths.isEmpty() || rmInfo.isRecursive || rmInfo.isWildcard) {
                return null
            }
            return eff.joinToString(" ")
        }
        if (program !in SUBCOMMAND_DISPATCHERS) return program
        val subcommand = eff.getOrNull(1)?.takeIf { !it.startsWith("-") } ?: return program
        return "$program $subcommand"
    }

    /** Token-prefix matching: hits when the rule [pattern]'s token sequence is a prefix of the segment's effective token sequence. */
    fun matches(pattern: String, segmentTokens: List<String>): Boolean {
        val patternTokens = pattern.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (patternTokens.isEmpty()) return false
        val eff = effectiveTokens(segmentTokens)
        if (eff.size < patternTokens.size) return false
        for (k in patternTokens.indices) if (eff[k] != patternTokens[k]) return false
        return true
    }
}
