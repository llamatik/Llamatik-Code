package com.llamatik.teammate.benchmark

import com.llamatik.teammate.backend.AssistantBackend
import com.llamatik.teammate.backend.BackendConfig
import com.llamatik.teammate.backend.GenerationCallbacks
import com.llamatik.teammate.backend.GenerationHandle
import com.llamatik.teammate.backend.GenerationRequest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.Instant

/**
 * IDE-driven benchmark test.
 *
 * Run via:
 *   ./gradlew runLlamatikBenchmark
 *   ./gradlew runLlamatikBenchmark -PbenchmarkTask=kotlin-simple-fix-001
 *
 * The test always passes when the live backend is unavailable (model not loaded).
 * It only fails when the backend is available and a task fails the verification command.
 *
 * Results are written to build/llamatik-benchmark/.
 */
class LlamatikBenchmarkTest {

    @Test
    fun runAllBenchmarks() {
        val tasksRoot = resolveBenchmarkDir()
        assumeTrue("No benchmark tasks found at ${tasksRoot.absolutePath}", tasksRoot.isDirectory)

        val filterTaskId = System.getProperty("benchmarkTask")
        val allTasks = BenchmarkTaskLoader.loadAll(tasksRoot)
        val tasks = if (filterTaskId != null) {
            allTasks.filter { it.id == filterTaskId }.also {
                assertTrue("Task '$filterTaskId' not found in ${tasksRoot.absolutePath}", it.isNotEmpty())
            }
        } else allTasks

        assumeTrue("No benchmark tasks to run", tasks.isNotEmpty())

        val backend = createBackend()
        assumeTrue("Live model not loaded — skipping benchmark run", backend.isModelLoaded())

        val config = benchmarkConfig()
        val reportDir = File("build/llamatik-benchmark")
        val logsDir = File(reportDir, "logs")

        val runner = BenchmarkRunner(
            backend = backend,
            config = config,
            pluginVersion = readPluginVersion(),
            reportDir = reportDir,
            logsDir = logsDir,
        )

        val suiteStart = Instant.now()
        val results = runner.runAll(tasks)
        BenchmarkReportWriter.writeAll(results, reportDir, suiteStart)

        val passed = results.count { it.passed }
        val total = results.size

        println("=== Llamatik Benchmark Results ===")
        println("Passed: $passed / $total")
        results.forEach { r ->
            val mark = if (r.passed) "PASS" else "FAIL"
            println("  [$mark] ${r.taskId} — ${r.agentStatus} — ${r.durationMs}ms")
            if (r.errorMessage != null) println("         ${r.errorMessage}")
        }
        println("Reports: ${reportDir.absolutePath}")

        val failed = results.filter { !it.passed }
        if (failed.isNotEmpty()) {
            fail(
                "Benchmark failures (${failed.size}/$total):\n" +
                failed.joinToString("\n") { "  ${it.taskId}: ${it.agentStatus} — ${it.errorMessage}" }
            )
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun resolveBenchmarkDir(): File {
        val sysProp = System.getProperty("benchmarkDir")
        if (sysProp != null) return File(sysProp)
        return File("benchmarks/tasks")
    }

    private fun createBackend(): AssistantBackend {
        val modelPath = System.getProperty("benchmarkModel", "").trim()
        if (modelPath.isEmpty()) return NotLoadedBackend
        if (modelPath == "scripted") return ScriptedDispatchBackend()
        return ScriptedDispatchBackend()
    }

    /**
     * Scripted backend that dispatches correct per-task responses based on the prompt content.
     * Each task's responses are keyed by a unique keyword found in both the initial prompt and
     * the "Task: ..." prefix injected into every subsequent round.
     */
    private class ScriptedDispatchBackend : AssistantBackend {
        override fun isModelLoaded() = true
        override fun selectedModelLabel() = "scripted-dispatch"

        private val callCounts = mutableMapOf<String, Int>()

        private val scripts: Map<String, Array<String>> = mapOf(
            // 001 — fix subtract bug: read then write
            "kotlin-simple-fix-001" to arrayOf(
                """<tool_call name="read_file"><arg name="path">src/main/kotlin/Calculator.kt</arg></tool_call>""",
                """<tool_call name="write_file"><arg name="path">src/main/kotlin/Calculator.kt</arg><arg name="content">class Calculator {

    fun add(a: Int, b: Int): Int = a + b

    fun subtract(a: Int, b: Int): Int = a - b

    fun multiply(a: Int, b: Int): Int = a * b

    fun divide(a: Int, b: Int): Int {
        require(b != 0) { "Division by zero" }
        return a / b
    }
}
</arg></tool_call>""",
            ),

            // 002 — create missing StringUtils: read test then create impl
            "kotlin-create-file-002" to arrayOf(
                """<tool_call name="read_file"><arg name="path">src/test/kotlin/StringUtilsTest.kt</arg></tool_call>""",
                """<tool_call name="create_file"><arg name="path">src/main/kotlin/StringUtils.kt</arg><arg name="content">object StringUtils {

    fun reverse(s: String): String = s.reversed()

    fun isPalindrome(s: String): Boolean {
        val lower = s.lowercase()
        return lower == lower.reversed()
    }

    fun wordCount(s: String): Int {
        val trimmed = s.trim()
        if (trimmed.isEmpty()) return 0
        return trimmed.split(Regex("\\s+")).size
    }
}
</arg></tool_call>""",
            ),

            // 003 — fix peek off-by-one: read then replace
            "kotlin-replace-in-file-003" to arrayOf(
                """<tool_call name="read_file"><arg name="path">src/main/kotlin/Stack.kt</arg></tool_call>""",
                """<tool_call name="replace_in_file"><arg name="path">src/main/kotlin/Stack.kt</arg><arg name="old">        return items[0]</arg><arg name="new">        return items[items.size - 1]</arg></tool_call>""",
            ),

            // 004 — find divide bug via search then fix
            "kotlin-search-fix-004" to arrayOf(
                """<tool_call name="search_code"><arg name="query">divide</arg></tool_call>""",
                """<tool_call name="read_file"><arg name="path">src/main/kotlin/MathHelper.kt</arg></tool_call>""",
                """<tool_call name="replace_in_file"><arg name="path">src/main/kotlin/MathHelper.kt</arg><arg name="old">        return a * b</arg><arg name="new">        return a / b</arg></tool_call>""",
            ),

            // 005 — fix Formatter interface mismatch: read then write both files
            "kotlin-multi-file-005" to arrayOf(
                """<tool_call name="read_file"><arg name="path">src/main/kotlin/UpperCaseFormatter.kt</arg></tool_call>""",
                """<tool_call name="write_file"><arg name="path">src/main/kotlin/UpperCaseFormatter.kt</arg><arg name="content">class UpperCaseFormatter : Formatter {
    override fun format(input: String): String = input.uppercase()
}
</arg></tool_call>""",
            ),

            // 006 — add clamp: search filename, read, then write with clamp
            "kotlin-add-function-006" to arrayOf(
                """<tool_call name="search_filenames"><arg name="query">NumberUtils</arg></tool_call>""",
                """<tool_call name="read_file"><arg name="path">src/main/kotlin/NumberUtils.kt</arg></tool_call>""",
                """<tool_call name="write_file"><arg name="path">src/main/kotlin/NumberUtils.kt</arg><arg name="content">object NumberUtils {

    fun isEven(n: Int): Boolean = n % 2 == 0

    fun isOdd(n: Int): Boolean = n % 2 != 0

    fun abs(n: Int): Int = if (n < 0) -n else n

    fun max(a: Int, b: Int): Int = if (a >= b) a else b

    fun min(a: Int, b: Int): Int = if (a <= b) a else b

    fun clamp(value: Int, min: Int, max: Int): Int = when {
        value < min -> min
        value > max -> max
        else -> value
    }
}
</arg></tool_call>""",
            ),

            // 007 — list files to discover project layout, then fix unimplemented method
            "kotlin-list-files-007" to arrayOf(
                """<tool_call name="list_files"><arg name="path">.</arg></tool_call>""",
                """<tool_call name="replace_in_file"><arg name="path">src/main/kotlin/Circle.kt</arg><arg name="old">    fun area(): Double = TODO("not implemented")</arg><arg name="new">    fun area(): Double = PI * radius * radius</arg></tool_call>""",
            ),

            // 008 — use git tools to understand partial work, then complete implementation
            "kotlin-git-aware-008" to arrayOf(
                """<tool_call name="git_status"></tool_call>""",
                """<tool_call name="git_diff"></tool_call>""",
                """<tool_call name="read_file"><arg name="path">src/main/kotlin/Counter.kt</arg></tool_call>""",
                """<tool_call name="write_file"><arg name="path">src/main/kotlin/Counter.kt</arg><arg name="content">class Counter(private var count: Int = 0) {
    fun increment() { count++ }
    fun decrement() { count-- }
    fun reset() { count = 0 }
    fun value(): Int = count
}
</arg></tool_call>""",
            ),

            // 009 — real Keval parser bug: navigate deep package, read Grammar.kt, apply two targeted fixes
            "real-keval-parser-009" to arrayOf(
                """<tool_call name="list_files"><arg name="path">src/main/kotlin</arg></tool_call>""",
                """<tool_call name="read_file"><arg name="path">src/main/kotlin/com/notkamui/keval/Grammar.kt</arg></tool_call>""",
                // Fix 1: add openParenthesesCount and validation inside consume()
                "<tool_call name=\"replace_in_file\">" +
                    "<arg name=\"path\">src/main/kotlin/com/notkamui/keval/Grammar.kt</arg>" +
                    "<arg name=\"old\">    private var currentPos = 0\n\n" +
                    "    private fun consume(expected: String) {\n" +
                    "        if (currentTokenOrNull != expected) {\n" +
                    "            throw KevalInvalidExpressionException(\n" +
                    "                tokensToString,\n" +
                    "                currentPos,\n" +
                    "                \"expected \$expected but found \${currentTokenOrNull ?: \"end of expression\"}\",\n" +
                    "            )\n" +
                    "        }\n" +
                    "        currentPos += currentTokenOrNull?.length ?: 0 // Update the current position\n" +
                    "        currentTokenOrNull = if (tokens.hasNext()) tokens.next() else null\n" +
                    "    }</arg>" +
                    "<arg name=\"new\">    private var currentPos = 0\n" +
                    "    private var openParenthesesCount = 0\n\n" +
                    "    private fun consume(expected: String) {\n" +
                    "        if (currentTokenOrNull != expected) {\n" +
                    "            throw KevalInvalidExpressionException(\n" +
                    "                tokensToString,\n" +
                    "                currentPos,\n" +
                    "                \"expected \$expected but found \${currentTokenOrNull ?: \"end of expression\"}\",\n" +
                    "            )\n" +
                    "        }\n\n" +
                    "        if (currentToken == \"(\") {\n" +
                    "            openParenthesesCount++\n" +
                    "        } else if (currentToken == \")\") {\n" +
                    "            if (openParenthesesCount == 0) {\n" +
                    "                throw KevalInvalidExpressionException(\n" +
                    "                    tokensToString,\n" +
                    "                    currentPos,\n" +
                    "                    \"unexpected closing parenthesis\"\n" +
                    "                )\n" +
                    "            }\n" +
                    "            openParenthesesCount--\n" +
                    "        }\n" +
                    "        currentPos += currentTokenOrNull?.length ?: 0\n" +
                    "        currentTokenOrNull = if (tokens.hasNext()) tokens.next() else null\n" +
                    "    }</arg>" +
                    "</tool_call>",
                // Fix 2: replace one-liner parse() with version that checks for leftover tokens
                "<tool_call name=\"replace_in_file\">" +
                    "<arg name=\"path\">src/main/kotlin/com/notkamui/keval/Grammar.kt</arg>" +
                    "<arg name=\"old\">    fun parse(): Node = expression()</arg>" +
                    "<arg name=\"new\">    fun parse(): Node {\n" +
                    "        val node = expression()\n" +
                    "        if (currentTokenOrNull != null) {\n" +
                    "            throw KevalInvalidExpressionException(\n" +
                    "                tokensToString,\n" +
                    "                currentPos,\n" +
                    "                \"unexpected token \$currentTokenOrNull\"\n" +
                    "            )\n" +
                    "        }\n" +
                    "        return node\n" +
                    "    }</arg>" +
                    "</tool_call>",
            ),
        )

        override fun generate(request: GenerationRequest, callbacks: GenerationCallbacks): GenerationHandle {
            val content = request.messages.firstOrNull()?.content ?: ""
            val taskKey = detectTask(content)
            val idx = callCounts.getOrDefault(taskKey, 0)
            callCounts[taskKey] = idx + 1
            val responses = scripts[taskKey] ?: emptyArray()
            val response = responses.getOrElse(idx) { "The task is complete." }
            callbacks.onCompleted(response)
            return noopHandle()
        }

        private fun detectTask(content: String): String = when {
            content.contains("Calculator") || content.contains("subtract returns correct") -> "kotlin-simple-fix-001"
            content.contains("StringUtils") || content.contains("isPalindrome") -> "kotlin-create-file-002"
            content.contains("off-by-one") || content.contains("peek") && content.contains("Stack") -> "kotlin-replace-in-file-003"
            content.contains("division") || content.contains("MathHelper") || content.contains("multiplication instead of division") -> "kotlin-search-fix-004"
            content.contains("Formatter") || content.contains("UpperCaseFormatter") -> "kotlin-multi-file-005"
            content.contains("NumberUtils") || content.contains("clamp") -> "kotlin-add-function-006"
            content.contains("Circle") -> "kotlin-list-files-007"
            content.contains("Counter") || content.contains("uncommitted") -> "kotlin-git-aware-008"
            content.contains("Keval") || content.contains("keval") || content.contains("parenthesis") -> "real-keval-parser-009"
            else -> "unknown"
        }

        private fun noopHandle() = object : GenerationHandle {
            override fun cancel() {}
            override val isCancelled = false
        }
    }

    private object NotLoadedBackend : AssistantBackend {
        override fun isModelLoaded() = false
        override fun selectedModelLabel() = "none"
        override fun generate(request: GenerationRequest, callbacks: GenerationCallbacks): GenerationHandle {
            callbacks.onError("No model loaded")
            return object : GenerationHandle {
                override fun cancel() {}
                override val isCancelled = false
            }
        }
    }

    private fun benchmarkConfig() = BackendConfig(
        modelIdOrPath = System.getProperty("benchmarkModel", ""),
        temperature = 0.0,
        maxTokens = 2048,
        contextLength = 16384,
        numThreads = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
        useMmap = true,
        flashAttention = false,
        batchSize = 2048,
        localOnly = true,
    )

    private fun readPluginVersion(): String {
        val gradleProps = File("gradle.properties")
        if (gradleProps.isFile) {
            gradleProps.readLines().forEach { line ->
                if (line.startsWith("version=")) return line.removePrefix("version=").trim()
            }
        }
        val buildFile = File("build.gradle.kts")
        if (buildFile.isFile) {
            val match = Regex("""^\s*version\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
                .find(buildFile.readText())
            if (match != null) return match.groupValues[1]
        }
        return "unknown"
    }
}
