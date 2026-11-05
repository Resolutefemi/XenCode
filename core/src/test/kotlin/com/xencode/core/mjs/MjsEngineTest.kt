package com.xencode.core.mjs

import com.xencode.core.state.CheckpointStore
import com.xencode.core.state.DecisionStore
import com.xencode.core.state.XenEventLog
import com.xencode.core.telemetry.Telemetry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.Test
import java.io.File

/**
 * End-to-end `.mjs` engine tests: the ESM shim, the `xencode:core` bindings
 * (fs / ast / pty / sync), decision cards and error paths — executed on the
 * real embedded GraalJS runtime.
 */
class MjsEngineTest {

    @TempDir
    lateinit var tmp: File

    private fun engine(root: File = tmp): MjsEngine {
        val events = XenEventLog(root)
        return MjsEngine(
            workspaceRoot = root,
            events = events,
            decisions = DecisionStore(root),
            checkpoints = CheckpointStore(root),
            telemetry = Telemetry(root, events)
        )
    }

    private fun script(source: String): File {
        val f = File(tmp, "test-${System.nanoTime()}.mjs")
        f.writeText(source)
        return f
    }

    // ---- ESM shim ----

    @Test
    fun esm_transformSupportedForms() {
        val out = MjsEngine.Esm.transform(
            """
            import { fs, ast as a } from "xencode:core";
            import pty from "xencode:core";
            import * as git from "xencode:core";
            import "xencode:core";

            export async function main() { return 1; }
            export function helper() {}
            export const X = 1;
            export let Y = 2;
            export class Thing {}
            export default async function main2() {}
            """.trimIndent()
        )
        assertTrue(out.contains("const { fs, ast: a } = __xencode_core;"), out)
        assertTrue(out.contains("const pty = __xencode_core;"), out)
        assertTrue(out.contains("const git = __xencode_core;"), out)
        assertTrue(out.contains("async function main()"), out)
        assertTrue(out.contains("function helper()"), out)
        assertTrue(out.contains("const X = 1;"), out)
        assertTrue(out.contains("let Y = 2;"), out)
        assertTrue(out.contains("class Thing {}"), out)
        assertTrue(out.contains("async function main2()"), out)
        assertTrue(!out.contains("export"), out)
    }

    @Test
    fun esm_rejectsForeignModules() {
        try {
            MjsEngine.Esm.transform("""import fs from "node:fs";""")
            throw AssertionError("expected MjsSyntaxException")
        } catch (e: MjsEngine.MjsSyntaxException) {
            assertTrue(e.message!!.contains("node:fs"), e.message)
        }
    }

    // ---- Engine runs ----

    @Test
    fun engine_runsMainAndReturnsValue() {
        val f = script(
            """
            import { fs } from "xencode:core";
            export async function main() {
              const text = await fs.readRange("in.txt", 1, 2);
              return "got:" + text;
            }
            """.trimIndent()
        )
        File(tmp, "in.txt").writeText("line1\nline2\nline3")
        val result = engine().run(f)
        assertTrue(result.ok, result.error)
        assertEquals("got:line1\nline2", result.value)
    }

    @Test
    fun engine_fsWriteAtomicGlobAndApplyPatch() {
        File(tmp, "src/deep").mkdirs()
        File(tmp, "src/a.txt").writeText("hello world\n")
        File(tmp, "src/deep/b.txt").writeText("x\n")
        val f = script(
            """
            import { fs } from "xencode:core";
            export async function main() {
              await fs.writeAtomic("out/report.txt", "done");
              const files = await fs.glob("src/**/*.txt");
              const patched = await fs.applyPatch("src/a.txt", "--- a/src/a.txt\n+++ b/src/a.txt\n@@ -1 +1 @@\n-hello world\n+hello patched\n");
              return [files.length, patched, await fs.readRange("out/report.txt", 1, 1)].join(";");
            }
            """.trimIndent()
        )
        val result = engine().run(f)
        assertTrue(result.ok, result.error)
        assertEquals("2;applied 1 hunks (2 lines) to src/a.txt;done", result.value)
        assertEquals("hello patched\n", File(tmp, "src/a.txt").readText())
        assertEquals(2, result.filesWritten.size) // writeAtomic + applyPatch
    }

    @Test
    fun engine_ptyExecCapturesExitAndOutput() {
        val f = script(
            """
            import { pty } from "xencode:core";
            export async function main() {
              const r = await pty.exec("echo marker");
              return "exit=" + r.exit + " out=" + String(r.output).trim();
            }
            """.trimIndent()
        )
        val result = engine().run(f)
        assertTrue(result.ok, result.error)
        assertTrue(result.value!!.startsWith("exit=0 out=marker"), result.value)
        assertEquals(1, result.commandsRun.size)
    }

    @Test
    fun engine_syncEmitsEventsRecordsTestsAndCreatesDecisionCard() {
        val f = script(
            """
            import { sync } from "xencode:core";
            export async function main() {
              await sync.emitMobileAndDesktop("STATE_SYNC", { summary: "working", count: 3 });
              await sync.recordTest("unit-a", true);
              await sync.recordTest("unit-b", false);
              const cardId = await sync.requestUserDecision({ title: "Pick one", body: "choose", options: ["approve", "reject"] });
              return cardId;
            }
            """.trimIndent()
        )
        val result = engine().run(f)
        assertTrue(result.ok, result.error)
        val cardId = result.value!!
        assertTrue(cardId.startsWith("d"))
        val lines = XenEventLog(tmp).latest(10)
        assertTrue(lines.any { it.contains("STATE_SYNC") && it.contains("working") }, lines.toString())
        assertTrue(lines.any { it.contains("DECISION_REQUESTED") }, lines.toString())
        val decisions = DecisionStore(tmp)
        assertEquals(cardId, decisions.list().single().id)
        assertEquals(1, result.decisionsCreated)
        val radar = Telemetry(tmp).load().radar
        assertEquals(2, radar.size)
        assertTrue(radar.any { it.id == "unit-a" && it.passed })
        assertTrue(radar.any { it.id == "unit-b" && !it.passed })
    }

    @Test
    fun engine_syntaxErrorReportsMessage() {
        val f = script(
            """
            import { fs } from "xencode:core";
            export async function main() {
              throw new Error("boom");
            }
            """.trimIndent()
        )
        val result = engine().run(f)
        assertTrue(!result.ok)
        assertTrue(result.error!!.contains("boom"), result.error)
    }

    @Test
    fun engine_missingMainIsReported() {
        val f = script("""import { fs } from "xencode:core"; export async function other() {}""")
        val result = engine().run(f)
        assertTrue(!result.ok)
        assertTrue(result.error!!.contains("main"), result.error)
    }

    @Test
    fun engine_sandboxRejectsEscapingPaths() {
        val f = script(
            """
            import { fs } from "xencode:core";
            export async function main() {
              return await fs.writeAtomic("../escape.txt", "nope");
            }
            """.trimIndent()
        )
        val result = engine().run(f)
        // main() returns the error string; the engine itself succeeds.
        assertTrue(result.ok, result.error)
        assertTrue(result.value!!.contains("ERROR"), result.value)
    }

    @Test
    fun engine_astSymbolsFindPatternReplaceSymbol() {
        File(tmp, "Sample.kt").writeText(
            """
            class Outer {
                fun first(): Int = 1
                fun second(): Int = 2
            }
            """.trimIndent()
        )
        val f = script(
            """
            import { ast } from "xencode:core";
            export async function main() {
              const syms = await ast.symbols("Sample.kt");
              const found = await ast.findPattern("Sample.kt", "fun second");
              const replaced = await ast.replaceSymbol("Sample.kt", "second", "    fun secondRenamed(): Int = 22");
              return [syms.length > 0, found.length, replaced].join(";");
            }
            """.trimIndent()
        )
        val result = engine().run(f)
        assertTrue(result.ok, result.error)
        assertTrue(result.value!!.startsWith("true;1;"), result.value)
        val next = File(tmp, "Sample.kt").readText()
        assertTrue(next.contains("secondRenamed"), next)
        assertTrue(!next.contains("fun second()"), next)
    }
}
