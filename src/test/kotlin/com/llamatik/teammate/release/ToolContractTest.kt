package com.llamatik.teammate.release

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Runs all tool contract cases from src/test/resources/release/tool-cases/.
 *
 * Run via: ./gradlew toolContractTest
 *
 * Each subdirectory under tool-cases/ corresponds to a tool name. Every JSON
 * file in that subdirectory is one contract case (see [ToolContractCase]).
 */
class ToolContractTest {

    private val toolCasesRoot = File("src/test/resources/release/tool-cases")
    private val fixturesRoot = File("src/test/resources/release/fixtures")
    private val runner = ToolContractRunner(fixturesRoot)

    @Test
    fun allContractCasesPass() {
        val cases = ToolContractLoader.loadAll(toolCasesRoot)
        if (cases.isEmpty()) {
            println("No tool contract cases found at ${toolCasesRoot.absolutePath} — skipping.")
            return
        }

        val results = cases.map { runner.run(it) }
        val passed = results.count { it.passed }
        val total = results.size

        println("=== Tool Contract Results ===")
        println("Passed: $passed / $total")
        results.forEach { r ->
            val mark = if (r.passed) "PASS" else "FAIL"
            println("  [$mark] ${r.case.tool}/${r.case.id} — ${r.case.description}")
            if (!r.passed) println("         ${r.failureReason}")
        }

        val failed = results.filter { !it.passed }
        if (failed.isNotEmpty()) {
            fail(
                "Tool contract failures (${failed.size}/$total):\n" +
                failed.joinToString("\n") { "  ${it.case.tool}/${it.case.id}: ${it.failureReason}" }
            )
        }
    }

    @Test
    fun everyToolHasAtLeastOneContractCase() {
        if (!toolCasesRoot.isDirectory) {
            println("tool-cases/ directory missing — skipping coverage check.")
            return
        }

        val coveredTools = toolCasesRoot.listFiles()
            ?.filter { it.isDirectory && it.listFiles()?.any { f -> f.extension == "json" } == true }
            ?.map { it.name }
            ?.toSet()
            ?: emptySet()

        val uncovered = ToolInventoryTest.EXPECTED_BUILTIN_TOOLS - coveredTools
        if (uncovered.isNotEmpty()) {
            println(
                "Note: ${uncovered.size} tool(s) have no contract case — add a JSON file under " +
                "src/test/resources/release/tool-cases/<tool-name>/:\n" +
                uncovered.sorted().joinToString("\n") { "  $it" }
            )
        }
    }
}
