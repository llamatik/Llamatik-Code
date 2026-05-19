package com.llamatik.teammate.benchmark

import com.llamatik.teammate.agent.AgentLoopTest
import com.llamatik.teammate.backend.BackendConfig
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
        // When run from Gradle, working directory is the project root.
        return File("benchmarks/tasks")
    }

    private fun createBackend(): com.llamatik.teammate.backend.AssistantBackend {
        // A real model path must be supplied via -PbenchmarkModel=<path> (forwarded as a
        // system property by the Gradle task).  Without it we return a stub that reports
        // isModelLoaded() = false so the test is skipped cleanly — no spurious failures in CI.
        val modelPath = System.getProperty("benchmarkModel", "").trim()
        if (modelPath.isEmpty()) return NotLoadedBackend
        // Model path provided: use FakeBackend for now so the harness itself can be verified
        // end-to-end without a native library.  Replace this with LlamatikBackendService when
        // running genuine agent evaluations.
        return AgentLoopTest.FakeBackend("Done, no file changes were needed.")
    }

    private object NotLoadedBackend : com.llamatik.teammate.backend.AssistantBackend {
        override fun isModelLoaded() = false
        override fun selectedModelLabel() = "none"
        override fun generate(
            request: com.llamatik.teammate.backend.GenerationRequest,
            callbacks: com.llamatik.teammate.backend.GenerationCallbacks,
        ): com.llamatik.teammate.backend.GenerationHandle {
            callbacks.onError("No model loaded")
            return object : com.llamatik.teammate.backend.GenerationHandle {
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
        // Gradle writes the version into a properties file during build.
        val gradleProps = File("gradle.properties")
        if (gradleProps.isFile) {
            gradleProps.readLines().forEach { line ->
                if (line.startsWith("version=")) return line.removePrefix("version=").trim()
            }
        }
        // Fallback: read from build.gradle.kts "version = ..."
        val buildFile = File("build.gradle.kts")
        if (buildFile.isFile) {
            val match = Regex("""^\s*version\s*=\s*"([^"]+)"""", RegexOption.MULTILINE)
                .find(buildFile.readText())
            if (match != null) return match.groupValues[1]
        }
        return "unknown"
    }
}
