package com.xencode.core.db

/**
 * Splits an SQL script into statements, recognizing comments and literals so that semicolons
 * inside them do not take part in the split.
 *
 * The old implementation did a plain `split(";")`, so a semicolon inside a string literal
 * (e.g. `';base64,'`) cut the statement in half and the whole migration failed. The state
 * machine covers: `--` line comments, `/* */` block comments, single/double/backtick quoted
 * literals (including the doubled-quote escape `''`), and `[...]` identifiers. Per SQLite
 * semantics, block comments do not nest.
 */
object SqlScriptSplitter {

    fun split(script: String): List<String> {
        val statements = mutableListOf<String>()
        val current = StringBuilder()
        var currentHasContent = false
        var i = 0
        val n = script.length
        while (i < n) {
            val c = script[i]
            when {
                c == '-' && i + 1 < n && script[i + 1] == '-' -> {
                    current.append("--")
                    i += 2
                    while (i < n && script[i] != '\n') {
                        current.append(script[i])
                        i++
                    }
                }

                c == '/' && i + 1 < n && script[i + 1] == '*' -> {
                    current.append("/*")
                    i += 2
                    while (i < n && !(script[i] == '*' && i + 1 < n && script[i + 1] == '/')) {
                        current.append(script[i])
                        i++
                    }
                    if (i < n) {
                        current.append("*/")
                        i += 2
                    }
                }

                c == '\'' || c == '"' || c == '`' -> {
                    current.append(c)
                    i++
                    while (i < n) {
                        if (script[i] == c) {
                            // A doubled quote is an escape (SQL's '', ``, ""); keep scanning inside the literal
                            current.append(c)
                            i++
                            if (i < n && script[i] == c) {
                                current.append(c)
                                i++
                            } else {
                                break
                            }
                        } else {
                            current.append(script[i])
                            i++
                        }
                    }
                    currentHasContent = true
                }

                c == '[' -> {
                    current.append(c)
                    i++
                    while (i < n && script[i] != ']') {
                        current.append(script[i])
                        i++
                    }
                    if (i < n) {
                        current.append(']')
                        i++
                    }
                    currentHasContent = true
                }

                c == ';' -> {
                    if (currentHasContent) {
                        val stmt = current.toString().trim()
                        if (stmt.isNotEmpty()) statements.add(stmt)
                    }
                    current.setLength(0)
                    currentHasContent = false
                    i++
                }

                else -> {
                    current.append(c)
                    if (!c.isWhitespace()) currentHasContent = true
                    i++
                }
            }
        }
        val last = current.toString().trim()
        if (currentHasContent && last.isNotEmpty()) statements.add(last)
        return statements
    }
}