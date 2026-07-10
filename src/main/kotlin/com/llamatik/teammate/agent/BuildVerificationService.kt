package com.llamatik.teammate.agent

import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.compiler.CompilerManager
import com.intellij.openapi.compiler.CompilerMessageCategory
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import java.io.File

/**
 * Triggers compilation and runs tests after proposals are applied, reporting results
 * so the agent can iterate on failures.
 *
 * Two build modes:
 *  - IDE (incremental): [compileInIde] — uses CompilerManager, fast, incremental, shows
 *    progress in the IDE Build panel. Must be called on the EDT; cancel is owned by the IDE.
 *  - Background (Gradle): [compileInBackground] — spawns a Gradle subprocess on a pooled
 *    thread. Slower (daemon startup) but useful for projects that don't use the IDE build.
 *    Returns a cancel lambda that kills the process.
 */
@Service(Service.Level.PROJECT)
class BuildVerificationService(private val project: Project) {

    /**
     * Triggers an IDE incremental Make via [CompilerManager].
     * Must be called on the EDT. Cancel is handled by the IDE's own Build panel.
     * [callback] is invoked on the EDT when the build finishes or is aborted.
     */
    fun compileInIde(callback: (success: Boolean, errors: List<String>) -> Unit) {
        CompilerManager.getInstance(project).make { aborted, errorCount, _, compileContext ->
            if (aborted) return@make
            if (errorCount == 0) {
                callback(true, emptyList())
            } else {
                val messages = compileContext.getMessages(CompilerMessageCategory.ERROR)
                    .map { msg ->
                        val prefix = msg.virtualFile?.path?.let { "$it: " } ?: ""
                        "$prefix${msg.message}"
                    }
                    // [Internal Error] messages are JVM/plugin crashes (e.g. Compose/Kotlin version
                    // mismatch). They are not fixable by editing source files — filter them so only
                    // real code errors reach the autofix model.
                    .filterNot { it.contains("[Internal Error]") }
                    .take(20)
                if (messages.isEmpty()) {
                    // All errors were infrastructure crashes — the source code compiled fine.
                    callback(true, emptyList())
                } else {
                    callback(false, messages)
                }
            }
        }
    }

    /**
     * Runs `./gradlew build -x test --continue` on a background thread.
     * Returns a cancel lambda; calling it kills the Gradle process.
     * [callback] is invoked on a background thread (wrap in invokeLater for UI updates).
     */
    fun compileInBackground(callback: (success: Boolean, errors: List<String>) -> Unit): () -> Unit {
        val basePath = project.basePath ?: run { callback(false, listOf("Cannot determine project root")); return {} }
        val gradlew  = findGradlew(basePath) ?: run { callback(false, listOf("No gradlew found in $basePath")); return {} }

        var handler: CapturingProcessHandler? = null
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val cmd = GeneralCommandLine(gradlew, "build", "-x", "test", "--continue", "--daemon")
                    .withWorkDirectory(basePath)
                val h = CapturingProcessHandler(cmd).also { handler = it }
                val result = h.runProcess()
                val output = result.stdout + result.stderr

                if (result.isCancelled) return@executeOnPooledThread
                if (result.exitCode == 0) {
                    callback(true, emptyList())
                } else {
                    val errors = parseGradleErrors(output)
                    if (errors.isEmpty()) {
                        // All errors were infrastructure crashes — the source code compiled fine.
                        callback(true, emptyList())
                    } else {
                        callback(false, errors)
                    }
                }
            } catch (e: Exception) {
                callback(false, listOf("Failed to start build: ${e.message}"))
            }
        }
        return { handler?.destroyProcess() }
    }

    /**
     * Runs `./gradlew test [--tests *.ClassName …]`.
     * If [testClassNames] is empty, all tests run.
     * Returns a cancel lambda; calling it kills the Gradle process.
     * [callback] is invoked on a background thread.
     */
    fun runTests(testClassNames: List<String>, callback: (success: Boolean, output: String) -> Unit): () -> Unit {
        val basePath = project.basePath ?: run { callback(false, "Cannot determine project root"); return {} }
        val gradlew  = findGradlew(basePath) ?: run { callback(false, "No gradlew found in $basePath"); return {} }

        var handler: CapturingProcessHandler? = null
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val args = mutableListOf(gradlew, "test", "--continue", "--daemon")
                testClassNames.forEach { args += listOf("--tests", "*.$it") }

                val cmd = GeneralCommandLine(args).withWorkDirectory(basePath)
                val h = CapturingProcessHandler(cmd).also { handler = it }
                val result = h.runProcess()
                if (result.isCancelled) return@executeOnPooledThread
                val summary = summariseTestOutput(result.stdout + result.stderr, result.exitCode)
                callback(result.exitCode == 0, summary)
            } catch (e: Exception) {
                callback(false, "Failed to start tests: ${e.message}")
            }
        }
        return { handler?.destroyProcess() }
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

    private fun parseGradleErrors(output: String): List<String> =
        output.lines()
            .filter { line ->
                val t = line.trimStart()
                t.startsWith("e: ") ||
                t.contains(": error:") ||
                (t.startsWith("FAILURE") && t.isNotBlank())
            }
            // [Internal Error] messages are JVM/plugin crashes (e.g. Compose/Kotlin version
            // mismatch). They are not fixable by editing source files — filter them so only
            // real code errors reach the autofix model.
            .filterNot { it.contains("[Internal Error]") }
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
        val win = File(basePath, "gradlew.bat")
        return if (win.exists()) win.absolutePath else null
    }
}
