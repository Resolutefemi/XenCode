package com.xencode.core.mjs

import java.io.File

/**
 * Lightweight symbol-level operations for the `.mjs` `ast` binding.
 *
 * This is a pragmatic "surgical edit" layer, not a full tree-sitter: it scans
 * declaration lines by regex for the common brace languages (Kotlin, Java, JS,
 * TS, C-like) plus Python, and can list symbols, find pattern lines, and
 * replace a whole declaration block (from its declaration line to the line
 * where the brace depth returns to zero, or the end of the indented block for
 * Python-style files). Enough for batch refactors; anything fancier belongs in
 * a real parser.
 */
object AstLite {

    private data class LangDecl(val regexes: List<Regex>, val braces: Boolean)

    private fun langFor(path: String): LangDecl? {
        val ext = path.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "kt", "kts", "java", "js", "mjs", "cjs", "ts", "tsx", "jsx", "cs", "go", "rs", "swift", "c", "h", "cpp", "hpp" -> LangDecl(
                listOf(
                    Regex("^\\s*(?:public |private |internal |protected |open |abstract |sealed |final |static |override |inline |suspend |export |default |async |extern )*\\b(class|interface|object|enum|struct|trait|fun|func|function|fn|def|method|impl|type)\\s+[A-Za-z_][A-Za-z0-9_]*"),
                    Regex("^\\s*(?:public |private |internal |protected |const |val |var |let |static |final )*\\b(const|val|var|let)\\s+[A-Za-z_][A-Za-z0-9_]*\\s*(?::|=)")
                ),
                braces = true
            )
            "py" -> LangDecl(
                listOf(
                    Regex("^\\s*(async\\s+)?def\\s+[A-Za-z_][A-Za-z0-9_]*\\s*\\("),
                    Regex("^\\s*class\\s+[A-Za-z_][A-Za-z0-9_]*")
                ),
                braces = false
            )
            else -> null
        }
    }

    /** One symbol per declaration line: "L<line> <kind> <name>". */
    fun symbols(content: String, path: String): List<String> {
        val lang = langFor(path) ?: return emptyList()
        val out = ArrayList<String>()
        content.lines().forEachIndexed { idx, line ->
            for (rx in lang.regexes) {
                val m = rx.find(line) ?: continue
                val kind = m.groupValues[1].ifBlank { "decl" }
                val name = Regex("[A-Za-z_][A-Za-z0-9_]*").findAll(line.substring(m.range.first))
                    .firstOrNull { it.value !in setOf("class", "interface", "object", "enum", "struct", "trait", "fun", "func", "function", "fn", "def", "method", "impl", "type", "const", "val", "var", "let", "public", "private", "internal", "protected", "open", "abstract", "sealed", "final", "static", "override", "inline", "suspend", "export", "default", "async", "extern") }
                    ?.value ?: continue
                out.add("L${idx + 1} $kind $name")
                break
            }
        }
        return out
    }

    /** All lines matching [pattern], rendered as "L<line>: text". */
    fun findPattern(content: String, pattern: String, ignoreCase: Boolean = false): List<String> {
        val rx = try {
            Regex(pattern, if (ignoreCase) setOf(RegexOption.IGNORE_CASE) else emptySet())
        } catch (e: Exception) {
            return emptyList()
        }
        return content.lines().mapIndexedNotNull { idx, line ->
            if (rx.containsMatchIn(line)) "L${idx + 1}: ${line.trimEnd()}" else null
        }
    }

    /**
     * Replaces the declaration block of [symbolName] with [newCode].
     * A "block" runs from the declaration line until braces balance (brace
     * languages) or until the indentation returns to the declaration level
     * (Python). @return the new content, or null when the symbol was not found.
     */
    fun replaceSymbol(content: String, path: String, symbolName: String, newCode: String): String? {
        val lang = langFor(path) ?: return null
        val lines = content.lines()
        var declIdx = -1
        for ((idx, line) in lines.withIndex()) {
            if (line.contains(Regex("\\b${Regex.escape(symbolName)}\\b")) &&
                lang.regexes.any { it.containsMatchIn(line) }
            ) {
                declIdx = idx
                break
            }
        }
        if (declIdx < 0) return null

        val end: Int
        if (lang.braces) {
            var depth = 0
            var sawBrace = false
            var i = declIdx
            while (i < lines.size) {
                for (c in stripCommentsAndStrings(lines[i])) {
                    when (c) {
                        '{' -> { depth++; sawBrace = true }
                        '}' -> depth--
                    }
                }
                i++
                if (sawBrace && depth <= 0) break
                if (!sawBrace && i > declIdx && lines[i - 1].trimEnd().endsWith(";")) break
            }
            end = i
        } else {
            val declIndent = lines[declIdx].indexOfFirst { it != ' ' }.let { if (it < 0) 0 else it }
            var i = declIdx + 1
            while (i < lines.size) {
                val l = lines[i]
                if (l.isBlank()) { i++; continue }
                val indent = l.indexOfFirst { it != ' ' }.let { if (it < 0) 0 else it }
                if (indent <= declIndent) break
                i++
            }
            end = i
        }
        val replacement = newCode.trimEnd('\n').split('\n')
        val next = lines.subList(0, declIdx) + replacement + lines.subList(end, lines.size)
        return next.joinToString("\n")
    }

    /** Removes // and "..." content so brace counting is not confused by literals. */
    private fun stripCommentsAndStrings(line: String): String {
        val sb = StringBuilder()
        var inString = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inString -> if (c == '\\') i++ else if (c == '"') inString = false
                c == '"' || c == '\'' -> inString = true
                c == '/' && i + 1 < line.length && line[i + 1] == '/' -> return sb.toString()
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString()
    }
}
