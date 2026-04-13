package com.llamatik.teammate.agent

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Runs Gradle compile and test tasks after proposals are applied and reports results
 * so the agent can iterate automatically on build/test failures.
 */
@Service(Service.Level.PROJECT)
class BuildVerificationService(private val project: Project) {

    companion object {
        private const val COMPILE_TIMEOUT_MS = 120_000
        private const val TEST_TIMEOUT_MS    = 300_000
    }

    /**
     * Runs `./gradlew build -x test --continue` to compile all sources.
     * Calls [callback] with (success, error lines) on any thread.
     */
    fun compile(callback: (success: Boolean, errors: List<String>) -> Unit) {
        val basePath = project.basePath ?: return callback(false, listOf("Cannot determine project root"))
        val gradlew  = findGradlew(basePath)  ?: return callback(false, listOf("No gradlew found in $basePath"))

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val cmd = GeneralCommandLine(gradlew, "build", "-x", "test", "--continue", "--daemon")
                    .withWorkDirectory(basePath)
                val handler = CapturingProcessHandler(cmd)
                val result  = handler.runProcess(COMPILE_TIMEOUT_MS)
                val output  = result.stdout + result.stderr

                if (result.exitCode == 0) {
                    callback(true, emptyList())
                } else {
                    val errors = parseErrors(output)
                    callback(false, errors.ifEmpty { listOf("Build failed (exit ${result.exitCode})") })
                }
            } catch (e: Exception) {
                callback(false, listOf("Failed to start build: ${e.message}"))
            }
        }
    }

    /**
     * Runs `./gradlew test [--tests *.ClassName …]`.
     * If [testClassNames] is empty, all tests run.
     * Calls [callback] with (success, summary) on any thread.
     */
    fun runTests(testClassNames: List<String>, callback: (success: Boolean, output: String) -> Unit) {
        val basePath = project.basePath ?: return callback(false, "Cannot determine project root")
        val gradlew  = findGradlew(basePath)  ?: return callback(false, "No gradlew found in $basePath")

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val args = mutableListOf(gradlew, "test", "--continue", "--daemon")
                testClassNames.forEach { args += listOf("--tests", "*.$it") }

                val cmd     = GeneralCommandLine(args).withWorkDirectory(basePath)
                val handler = CapturingProcessHandler(cmd)
                val result  = handler.runProcess(TEST_TIMEOUT_MS)
                val summary = summariseTestOutput(result.stdout + result.stderr, result.exitCode)
                callback(result.exitCode == 0, summary)
            } catch (e: Exception) {
                callback(false, "Failed to start tests: ${e.message}")
            }
        }
    }

    /** True if the file path looks like a test source (by directory or name convention). */
    fun isTestFile(filePath: String): Boolean {
        val lp = filePath.lowercase().replace('\\', '/')
        return lp.contains("/test/") ||
               filePath.endsWith("Test.kt")  || filePath.endsWith("Tests.kt")  ||
               filePath.endsWith("Test.java") || filePath.endsWith("Tests.java")
    }

    /**
     * Given changed file paths, returns simple class names to pass to `--tests`.
     * For non-test files, guesses the companion test class name.
     */
    fun detectTestClasses(filePaths: List<String>): List<String> =
        filePaths.map { File(it).nameWithoutExtension }.flatMap { name ->
            if (name.endsWith("Test") || name.endsWith("Tests")) listOf(name)
            else listOf("${name}Test", "${name}Tests")
        }.distinct()

    // ── Private ──────────────────────────────────────────────────────────────

    private fun parseErrors(output: String): List<String> =
        output.lines()
            .filter { line ->
                val t = line.trimStart()
                t.startsWith("e: ") ||                     // Kotlin compiler
                t.contains(": error:") ||                   // general
                (t.startsWith("FAILURE") && t.isNotBlank()) // Gradle failure banner
            }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(20)

    private fun summariseTestOutput(raw: String, exitCode: Int): String {
        if (exitCode == 0) return "Tests passed"
        val lines = raw.lines().filter { line ->
            line.isNotBlank() && (
                line.contains("FAILED", ignoreCase = true) ||
                line.contains("tests", ignoreCase = true)  ||
                line.contains("Exception")                 ||
                line.trimStart().startsWith("at ")         ||
                line.startsWith("  ")
            )
        }.take(40)
        return lines.joinToString("\n").take(2000).trim()
            .ifBlank { "Tests failed (exit $exitCode)" }
    }

    private fun findGradlew(basePath: String): String? {
        val unix = File(basePath, "gradlew")
        if (unix.exists()) { unix.setExecutable(true); return unix.absolutePath }
        val win  = File(basePath, "gradlew.bat")
        return if (win.exists()) win.absolutePath else null
    }
}
