package com.xencode.core.mjs

import com.xencode.core.agent.Worktrees
import com.xencode.core.state.CheckpointStore
import com.xencode.core.state.DecisionStore
import com.xencode.core.state.XenEventLog
import com.xencode.core.telemetry.Telemetry
import com.xencode.core.tools.LocalTools
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.Value
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.proxy.ProxyExecutable
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The embedded `.mjs` automation engine — the PC line's rquickjs-parity layer.
 *
 * Scripts follow one documented pattern:
 *
 * ```js
 * import { fs, ast, pty, git, sync } from "xencode:core";
 *
 * export async function main() {
 *   const files = await fs.glob("src/**/*.kt");
 *   await fs.writeAtomic("out/report.txt", JSON.stringify(files, null, 2));
 *   const r = await pty.exec("git status --short");
 *   await sync.emitMobileAndDesktop("STATE_SYNC", { summary: r.output });
 *   return { files: files.length };
 * }
 * ```
 *
 * The engine rewrites that ESM surface into plain JS for GraalJS (see [Esm]),
 * injects the `xencode:core` namespace as a host object and runs `main()`.
 * All host bindings are synchronous from JS's point of view (they block with
 * internal timeouts), so `await` settles before the evaluation boundary and no
 * JS event loop is required. The `main()` promise result is captured through a
 * `.then` chain into a host-side holder.
 */
class MjsEngine(
    private val workspaceRoot: File,
    private val events: XenEventLog,
    private val decisions: DecisionStore,
    private val checkpoints: CheckpointStore,
    private val telemetry: Telemetry
) {

    /** Structured outcome of one script run, for `xencode run` and the desktop pane. */
    data class MjsResult(
        val ok: Boolean,
        val value: String? = null,
        val error: String? = null,
        val durationMs: Long = 0,
        val filesWritten: List<String> = emptyList(),
        val commandsRun: List<String> = emptyList(),
        val decisionsCreated: Int = 0
    )

    class MjsSyntaxException(message: String) : Exception(message)

    // ------------------------------------------------------------------
    // ESM shim — pure string transform, unit-tested
    // ------------------------------------------------------------------

    object Esm {
        private val IMPORT_FROM = Regex("""(?m)^[ \t]*import\s+([^\n;]+?)\s+from\s+["']([^"']+)["'][ \t]*;?[ \t]*""")
        private val BARE_IMPORT = Regex("""(?m)^[ \t]*import\s+["']([^"']+)["'][ \t]*;?[ \t]*""")
        private const val CORE = "__xencode_core"

        /** Rewrites the supported ESM forms into plain JS. */
        fun transform(source: String): String {
            var out = source

            out = BARE_IMPORT.replace(out) { m ->
                if (m.groupValues[1] != "xencode:core") throw MjsSyntaxException(
                    "only the \"xencode:core\" module can be imported (found \"${m.groupValues[1]}\")"
                )
                ""
            }

            out = IMPORT_FROM.replace(out) { m ->
                val module = m.groupValues[2]
                if (module != "xencode:core") throw MjsSyntaxException(
                    "only the \"xencode:core\" module can be imported (found \"$module\")"
                )
                rewriteClause(m.groupValues[1])
            }

            out = out
                .replace(Regex("""(?m)^([ \t]*)export[ \t]+default[ \t]+async[ \t]+function[ \t]*"""), "$1async function ")
                .replace(Regex("""(?m)^([ \t]*)export[ \t]+default[ \t]+function[ \t]*"""), "$1function ")
                .replace(Regex("""(?m)^([ \t]*)export[ \t]+async[ \t]+function[ \t]*"""), "$1async function ")
                .replace(Regex("""(?m)^([ \t]*)export[ \t]+function[ \t]*"""), "$1function ")
                .replace(Regex("""(?m)^([ \t]*)export[ \t]+class[ \t]*"""), "$1class ")
                .replace(Regex("""(?m)^([ \t]*)export[ \t]+(const|let|var)[ \t]+"""), "$1$2 ")
                .replace(Regex("""(?m)^[ \t]*export[ \t]+\{[^}]*\}[ \t]*;?[ \t]*$"""), "")
                .replace(Regex("""(?m)^([ \t]*)export[ \t]+default[ \t]+"""), "$1var __xencode_default = ")

            return out
        }

        private fun rewriteClause(clause: String): String {
            // Split on top-level commas only (commas inside { } / ( ) belong to
            // the destructuring pattern itself).
            val parts = ArrayList<String>()
            val current = StringBuilder()
            var depth = 0
            for (c in clause) {
                when (c) {
                    '{', '(' -> { depth++; current.append(c) }
                    '}', ')' -> { depth--; current.append(c) }
                    ',' -> if (depth == 0) {
                        parts.add(current.toString())
                        current.setLength(0)
                    } else current.append(c)
                    else -> current.append(c)
                }
            }
            if (current.isNotBlank()) parts.add(current.toString())

            val sb = StringBuilder()
            for (part in parts) {
                val p = part.trim()
                when {
                    p.isEmpty() -> Unit
                    p.startsWith("{") && p.endsWith("}") -> {
                        val names = p.removeSurrounding("{", "}").split(',').joinToString(", ") { n ->
                            val t = n.trim()
                            if (t.contains(" as ")) {
                                val (src, alias) = t.split(Regex("\\s+as\\s+"))
                                "${src.trim()}: ${alias.trim()}"
                            } else t
                        }
                        sb.appendLine("const { $names } = $CORE;")
                    }
                    p.startsWith("*") -> {
                        val alias = p.substringAfter("as").trim().ifEmpty { "core" }
                        sb.appendLine("const $alias = $CORE;")
                    }
                    else -> sb.appendLine("const $p = $CORE;")
                }
            }
            return sb.toString()
        }
    }

    // ------------------------------------------------------------------
    // Host value conversion
    // ------------------------------------------------------------------

    private object JsValues {
        fun toJava(v: Value): Any? = when {
            v.isNull -> null
            v.isString -> v.asString()
            v.isNumber -> {
                val d = v.asDouble()
                if (d == Math.floor(d) && !d.isInfinite() && Math.abs(d) < 9.007199254740992E15) d.toLong() else d
            }
            v.isBoolean -> v.asBoolean()
            v.hasArrayElements() -> (0 until v.arraySize).map { toJava(v.getArrayElement(it)) }
            v.hasMembers() && v.memberKeys.isNotEmpty() -> v.memberKeys.associateWith { toJava(v.getMember(it)) }
            else -> v.toString()
        }

        fun asString(v: Value): String = when {
            v.isString -> v.asString()
            v.isNull -> "null"
            else -> v.toString()
        }
    }

    // ------------------------------------------------------------------
    // Runner
    // ------------------------------------------------------------------

    /** Runs one `.mjs` script; never throws (all failures land in [MjsResult]). */
    fun run(script: File): MjsResult {
        val started = System.currentTimeMillis()
        val filesWritten = ArrayList<String>()
        val commandsRun = ArrayList<String>()
        var decisionsCreated = 0
        if (!script.isFile) return MjsResult(ok = false, error = "not a file: ${script.path}", durationMs = 0)

        val transformed = try {
            Esm.transform(script.readText(Charsets.UTF_8))
        } catch (e: MjsSyntaxException) {
            return MjsResult(ok = false, error = e.message, durationMs = System.currentTimeMillis() - started)
        }

        val core = buildCoreMap(filesWritten, commandsRun) { decisionsCreated++ }
        val outcome = Outcome()

        val context = try {
            Context.newBuilder("js")
                .allowHostAccess(HostAccess.ALL)
                .allowHostClassLookup { false }
                .option("js.ecmascript-version", "2022")
                .build()
        } catch (e: Exception) {
            return MjsResult(ok = false, error = "engine start failed: ${e.message}", durationMs = System.currentTimeMillis() - started)
        }

        return try {
            context.getBindings("js").putMember(CORE_REF, core)
            context.getBindings("js").putMember("__xe_finish", ProxyExecutable { args ->
                val v = args.firstOrNull()
                if (v != null) outcome.capture(v)
                null
            })
            context.eval("js", transformed)
            val main = context.getBindings("js").getMember("main")
            if (!main.canExecute()) {
                MjsResult(ok = false, error = "script must export an async function main()", durationMs = System.currentTimeMillis() - started)
            } else {
                context.eval(
                    "js",
                    "Promise.resolve(main()).then(" +
                        "v => __xe_finish({ok: true, value: (v === null || v === undefined) ? null : String(v)}), " +
                        "e => __xe_finish({ok: false, error: String((e && e.message) || e)}));"
                )
                if (!outcome.settled) context.eval("js", "0") // force one microtask drain
                MjsResult(
                    ok = outcome.settled && outcome.ok,
                    value = outcome.value,
                    error = when {
                        !outcome.settled -> "script did not settle (main() never resolved)"
                        !outcome.ok -> outcome.error ?: "script failed"
                        else -> null
                    },
                    durationMs = System.currentTimeMillis() - started,
                    filesWritten = filesWritten.toList(),
                    commandsRun = commandsRun.toList(),
                    decisionsCreated = decisionsCreated
                )
            }
        } catch (e: org.graalvm.polyglot.PolyglotException) {
            MjsResult(ok = false, error = "script error: ${e.message ?: e.javaClass.simpleName}", durationMs = System.currentTimeMillis() - started, filesWritten = filesWritten.toList(), commandsRun = commandsRun.toList(), decisionsCreated = decisionsCreated)
        } catch (e: Exception) {
            MjsResult(ok = false, error = "engine error: ${e.message ?: e.javaClass.simpleName}", durationMs = System.currentTimeMillis() - started)
        } finally {
            runCatching { context.close() }
            events.emit(
                XenEventLog.Types.MJS_RUN,
                "${script.name} ${if (outcome.settled && outcome.ok) "ok" else "failed"}",
                "script" to script.name, "durationMs" to (System.currentTimeMillis() - started)
            )
        }
    }

    private class Outcome {
        var settled = false
        var ok = false
        var value: String? = null
        var error: String? = null

        fun capture(v: Value) {
            settled = true
            ok = runCatching { v.getMember("ok").asBoolean() }.getOrDefault(false)
            value = runCatching { v.getMember("value")?.takeIf { !it.isNull }?.asString() }.getOrNull()
            error = runCatching { v.getMember("error")?.takeIf { !it.isNull }?.asString() }.getOrNull()
        }
    }

    // ------------------------------------------------------------------
    // xencode:core bindings
    // ------------------------------------------------------------------

    private fun fn(block: (Array<Value>) -> Any?): ProxyExecutable = ProxyExecutable { args -> block(args) }

    private fun buildCoreMap(filesWritten: MutableList<String>, commandsRun: MutableList<String>, onDecision: () -> Unit): Map<String, Any> {
        val root = workspaceRoot.normalize()

        val fs = linkedMapOf<String, Any>(
            "glob" to fn { args ->
                val pattern = JsValues.asString(args.getOrNull(0) ?: return@fn "ERROR: glob(pattern) needs a pattern")
                val dir = LocalTools.resolve(root, args.getOrNull(1)?.takeIf { !it.isNull }?.let { JsValues.asString(it) } ?: ".") ?: root
                val regex = LocalTools.globToRegex(pattern)
                walkFiles(dir).filter { regex.matches(it.toRelativeString(root).replace('\\', '/')) }
                    .take(200).map { it.toRelativeString(root).replace('\\', '/') }
            },
            "readRange" to fn { args ->
                val path = JsValues.asString(args.getOrNull(0) ?: return@fn "ERROR: readRange(path, start, end)")
                val f = LocalTools.resolve(root, path) ?: return@fn "ERROR: path escapes the workspace"
                if (!f.isFile) return@fn "ERROR: not a file: $path"
                val lines = f.readText(Charsets.UTF_8).lines()
                val start = ((args.getOrNull(1)?.takeIf { !it.isNull }?.asInt() ?: 1)).coerceAtLeast(1)
                val end = ((args.getOrNull(2)?.takeIf { !it.isNull }?.asInt() ?: lines.size)).coerceIn(start, lines.size)
                lines.subList(start - 1, end).joinToString("\n")
            },
            "writeAtomic" to fn { args ->
                val path = JsValues.asString(args.getOrNull(0) ?: return@fn "ERROR: writeAtomic(path, content)")
                val content = args.getOrNull(1)?.takeIf { !it.isNull }?.let { JsValues.asString(it) } ?: ""
                val f = LocalTools.resolve(root, path) ?: return@fn "ERROR: path escapes the workspace"
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(content, Charsets.UTF_8)
                if (!tmp.renameTo(f)) { f.writeText(content, Charsets.UTF_8); tmp.delete() }
                filesWritten.add(path)
                "wrote ${content.length} chars to $path"
            },
            "applyPatch" to fn { args ->
                val path = JsValues.asString(args.getOrNull(0) ?: return@fn "ERROR: applyPatch(path, diff)")
                val diff = args.getOrNull(1)?.takeIf { !it.isNull }?.let { JsValues.asString(it) } ?: ""
                val f = LocalTools.resolve(root, path) ?: return@fn "ERROR: path escapes the workspace"
                if (!f.isFile) return@fn "ERROR: not a file: $path"
                val (next, result) = DiffPatch.applyToString(f.readText(Charsets.UTF_8), diff)
                when (result) {
                    is DiffPatch.Result.Applied -> {
                        f.writeText(next, Charsets.UTF_8)
                        filesWritten.add(path)
                        "applied ${result.hunks} hunks (${result.linesChanged} lines) to $path"
                    }
                    is DiffPatch.Result.Rejected -> "ERROR: patch rejected: ${result.reason}"
                }
            }
        )

        val ast = linkedMapOf<String, Any>(
            "symbols" to fn { args ->
                val path = JsValues.asString(args.getOrNull(0) ?: return@fn emptyList<String>())
                val f = LocalTools.resolve(root, path) ?: return@fn listOf("ERROR: path escapes the workspace")
                if (!f.isFile) return@fn listOf("ERROR: not a file: $path")
                AstLite.symbols(f.readText(Charsets.UTF_8), path)
            },
            "findPattern" to fn { args ->
                val path = JsValues.asString(args.getOrNull(0) ?: return@fn emptyList<String>())
                val pattern = args.getOrNull(1)?.takeIf { !it.isNull }?.let { JsValues.asString(it) } ?: ""
                val f = LocalTools.resolve(root, path) ?: return@fn listOf("ERROR: path escapes the workspace")
                if (!f.isFile) return@fn listOf("ERROR: not a file: $path")
                AstLite.findPattern(f.readText(Charsets.UTF_8), pattern)
            },
            "replaceSymbol" to fn { args ->
                val path = JsValues.asString(args.getOrNull(0) ?: return@fn "ERROR: replaceSymbol(path, symbol, newCode)")
                val symbol = JsValues.asString(args.getOrNull(1) ?: return@fn "ERROR: replaceSymbol(path, symbol, newCode)")
                val newCode = args.getOrNull(2)?.takeIf { !it.isNull }?.let { JsValues.asString(it) } ?: ""
                val f = LocalTools.resolve(root, path) ?: return@fn "ERROR: path escapes the workspace"
                if (!f.isFile) return@fn "ERROR: not a file: $path"
                val next = AstLite.replaceSymbol(f.readText(Charsets.UTF_8), path, symbol, newCode)
                if (next == null) "ERROR: symbol `$symbol` not found in $path"
                else {
                    f.writeText(next, Charsets.UTF_8)
                    filesWritten.add(path)
                    "replaced `$symbol` in $path"
                }
            }
        )

        val pty = linkedMapOf<String, Any>(
            "exec" to fn { args ->
                val command = JsValues.asString(args.getOrNull(0) ?: return@fn mapOf("exit" to -1, "output" to "ERROR: exec(command) needs a command"))
                val opts = args.getOrNull(1)?.takeIf { !it.isNull && it.hasMembers() }
                val cwdRel = opts?.getMember("cwd")?.takeIf { !it.isNull }?.let { JsValues.asString(it) } ?: "."
                val timeoutMs = opts?.getMember("timeoutMs")?.takeIf { !it.isNull }?.asInt()?.toLong() ?: 30_000L
                val cwd = LocalTools.resolve(root, cwdRel) ?: return@fn mapOf("exit" to -1, "output" to "ERROR: cwd escapes the workspace")
                commandsRun.add(command)
                execCommand(command, cwd, timeoutMs.coerceIn(1_000, 600_000))
            }
        )

        val git = linkedMapOf<String, Any>(
            "worktreeCreate" to fn { args ->
                val id = JsValues.asString(args.getOrNull(0) ?: return@fn mapOf("ok" to false, "error" to "worktreeCreate(id) needs an id"))
                if (!Worktrees.isGitRepo(root)) return@fn mapOf("ok" to false, "error" to "not a git repository")
                val wt = Worktrees.create(root, id)
                    ?: return@fn mapOf("ok" to false, "error" to (Worktrees.lastError ?: "worktree create failed"))
                mapOf("ok" to true, "path" to wt.path, "branch" to wt.branch)
            },
            "checkpoint" to fn { args ->
                val label = JsValues.asString(args.getOrNull(0) ?: return@fn mapOf("ok" to false, "error" to "checkpoint(nodeId, message) needs a label"))
                val message = args.getOrNull(1)?.takeIf { !it.isNull }?.let { JsValues.asString(it) } ?: "verified"
                val cp = checkpoints.create(label, message)
                if (cp == null) mapOf("ok" to false, "error" to (checkpoints.lastError ?: "checkpoint failed"))
                else {
                    events.emit(XenEventLog.Types.CHECKPOINT, "${cp.id} ${cp.commit ?: "-"}", "label" to cp.label, "commit" to cp.commit)
                    mapOf("ok" to true, "id" to cp.id, "commit" to (cp.commit ?: ""), "filesChanged" to cp.filesChanged)
                }
            },
            "diffStat" to fn { _ ->
                if (!Worktrees.isGitRepo(root)) "not a git repository"
                else execCommand("git diff --stat", root, 15_000)["output"] ?: ""
            }
        )

        val sync = linkedMapOf<String, Any>(
            "emitMobileAndDesktop" to fn { args ->
                val type = JsValues.asString(args.getOrNull(0) ?: return@fn "ERROR: emit(eventType, payload) needs a type")
                val payload = args.getOrNull(1)?.let { JsValues.toJava(it) }
                val fields = (payload as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value } ?: emptyMap()
                events.emit(type, fields["summary"]?.toString(), fields)
            },
            "requestUserDecision" to fn { args ->
                val payload = args.getOrNull(0)?.let { JsValues.toJava(it) } as? Map<*, *>
                    ?: return@fn "ERROR: requestUserDecision({title, body, options})"
                val options = (payload["options"] as? List<*>)?.map { it.toString() } ?: listOf("approve", "reject")
                val card = decisions.create(
                    title = payload["title"]?.toString() ?: "Decision needed",
                    body = payload["body"]?.toString() ?: "",
                    options = options
                )
                onDecision()
                events.emit(XenEventLog.Types.DECISION_REQUESTED, card.title, "card" to card.id)
                card.id
            },
            "recordTest" to fn { args ->
                val id = JsValues.asString(args.getOrNull(0) ?: return@fn "ERROR: recordTest(id, passed)")
                val passed = args.getOrNull(1)?.isBoolean == true && args[1].asBoolean()
                telemetry.recordTest(id, passed)
                "recorded $id ${if (passed) "GREEN" else "RED"}"
            }
        )

        return mapOf("fs" to fs, "ast" to ast, "pty" to pty, "git" to git, "sync" to sync)
    }

    private fun execCommand(command: String, cwd: File, timeoutMs: Long): Map<String, Any> {
        val isWindows = System.getProperty("os.name").lowercase().contains("windows")
        val parts = if (isWindows) listOf("cmd", "/c", command) else listOf("sh", "-c", command)
        return try {
            val proc = ProcessBuilder(parts).directory(cwd).redirectErrorStream(true).start()
            val buf = java.io.ByteArrayOutputStream()
            proc.inputStream.use { input ->
                val chunk = ByteArray(8 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    if (buf.size() > 12_000) break
                    buf.write(chunk, 0, n)
                }
            }
            val finished = proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                proc.destroyForcibly()
                mapOf("exit" to -1, "output" to buf.toString(Charsets.UTF_8) + "\nERROR: timed out after ${timeoutMs}ms")
            } else {
                val exit = proc.exitValue()
                val output = buf.toString(Charsets.UTF_8)
                telemetry.recordCommand(command, exit)
                mapOf("exit" to exit, "output" to output)
            }
        } catch (e: Exception) {
            mapOf("exit" to -1, "output" to "ERROR: ${e.javaClass.simpleName}: ${e.message ?: "no message"}")
        }
    }

    private fun walkFiles(dir: File): List<File> {
        if (!dir.isDirectory) return emptyList()
        val result = ArrayList<File>()
        fun rec(d: File, depth: Int) {
            if (depth > 12 || result.size > 5000) return
            val children = d.listFiles() ?: return
            for (c in children.sortedBy { it.name }) {
                if (c.isDirectory) {
                    if (c.name !in setOf(".git", "node_modules", "build", ".gradle", ".idea", "__pycache__", ".xencode")) rec(c, depth + 1)
                } else result.add(c)
            }
        }
        rec(dir, 0)
        return result
    }

    companion object {
        private const val CORE_REF = "__xencode_core"

        init {
            // Stock-JVM GraalJS prints a fallback-implementation warning on every
            // engine start; silence it once for the whole process.
            System.setProperty("polyglot.engine.WarnInterpreterOnly", "false")
        }

        /** The canonical example printed by `xencode run --example` and the docs. */
        const val EXAMPLE_SCRIPT = """
            import { fs, pty, sync } from "xencode:core";

            export async function main() {
              // 1. Batch-inspect the repo with native-speed file tools.
              const kt = await fs.glob("**/*.kt");
              const report = ["files=" + kt.length];
              // 2. Run a command non-interactively and capture its output.
              const status = await pty.exec("git status --short");
              report.push("exit=" + status.exit);
              report.push(status.output.trim().split("\n")[0]);
              // 3. Persist an atomic report file.
              await fs.writeAtomic(".xencode/mjs-report.txt", report.join("\n"));
              // 4. Emit a structured event (Windows UI + Mobile App render these).
              await sync.emitMobileAndDesktop("STATE_SYNC", { summary: "report written", files: kt.length });
              return report.join(" | ");
            }
        """
    }
}
