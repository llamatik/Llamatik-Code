package com.llamatik.teammate.benchmark

import com.llamatik.teammate.backend.AssistantBackend
import com.llamatik.teammate.backend.BackendConfig
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Orchestrates a benchmark run over a set of [BenchmarkTask]s.
 *
 * Each task's repo fixture is copied to a fresh temp directory before the agent runs,
 * so the original fixture is never modified.
 */
class BenchmarkRunner(
    private val backend: AssistantBackend,
    private val config: BackendConfig,
    private val pluginVersion: String = "unknown",
    private val reportDir: File = File("build/llamatik-benchmark"),
    private val logsDir: File = File("build/llamatik-benchmark/logs"),
) {

    fun runAll(tasks: List<BenchmarkTask>): List<BenchmarkResult> {
        reportDir.mkdirs()
        logsDir.mkdirs()
        return tasks.map { task -> runTask(task) }
    }

    fun runTask(task: BenchmarkTask): BenchmarkResult {
        val startedAt = Instant.now()
        reportDir.mkdirs()
        logsDir.mkdirs()
        val tempDir = Files.createTempDirectory("llamatik-bench-${task.id}").toFile()

        return try {
            copyTree(task.repoDir, tempDir)
            injectGradleWrapper(tempDir)

            task.setupCommand?.let { cmd ->
                val result = runCommand(cmd, tempDir, task.timeoutMinutes)
                if (result.exitCode != 0) error("Setup command failed (exit ${result.exitCode}): ${result.stderr.take(200)}")
            }

            val snapshotBefore = BenchmarkTaskLoader.snapshotFiles(tempDir)

            val agent = HeadlessAgent(
                backend = backend,
                config = config,
                projectRoot = tempDir,
                maxRounds = 5,
                maxToolCallsPerRound = 4,
            )

            val agentResult = agent.run(task.prompt, task.timeoutMinutes)

            val changedFiles = BenchmarkTaskLoader.computeChangedFiles(snapshotBefore, tempDir)

            val agentStatus = when {
                agentResult.error?.contains("Timeout", ignoreCase = true) == true -> AgentStatus.TIMEOUT
                agentResult.error != null -> AgentStatus.AGENT_ERROR
                else -> AgentStatus.SUCCESS
            }

            if (agentStatus != AgentStatus.SUCCESS) {
                return buildResult(
                    task = task,
                    startedAt = startedAt,
                    agentStatus = agentStatus,
                    passed = false,
                    changedFiles = changedFiles,
                    stdoutPath = null,
                    stderrPath = null,
                    errorMessage = agentResult.error,
                )
            }

            val verifyResult = runVerification(task, tempDir)

            val logBase = File(logsDir, "${task.id}.log")
            logBase.writeText(buildString {
                appendLine("=== Agent output ===")
                appendLine(agentResult.finalMessage)
                appendLine()
                appendLine("=== Tools executed ===")
                appendLine(agentResult.toolsExecuted.joinToString("\n"))
                appendLine()
                appendLine("=== Verification stdout ===")
                appendLine(verifyResult.stdout)
                appendLine()
                appendLine("=== Verification stderr ===")
                appendLine(verifyResult.stderr)
            })

            val stdoutPath = File(logsDir, "${task.id}-stdout.txt").also { it.writeText(verifyResult.stdout) }.absolutePath
            val stderrPath = File(logsDir, "${task.id}-stderr.txt").also { it.writeText(verifyResult.stderr) }.absolutePath

            buildResult(
                task = task,
                startedAt = startedAt,
                agentStatus = if (verifyResult.exitCode == 0) AgentStatus.SUCCESS else AgentStatus.VERIFICATION_FAILED,
                passed = verifyResult.exitCode == 0,
                changedFiles = changedFiles,
                stdoutPath = stdoutPath,
                stderrPath = stderrPath,
                errorMessage = if (verifyResult.exitCode != 0) "Verification exited with code ${verifyResult.exitCode}" else null,
            )

        } catch (e: Exception) {
            buildResult(
                task = task,
                startedAt = startedAt,
                agentStatus = AgentStatus.SETUP_ERROR,
                passed = false,
                changedFiles = emptyList(),
                stdoutPath = null,
                stderrPath = null,
                errorMessage = e.message,
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }

    private fun buildResult(
        task: BenchmarkTask,
        startedAt: Instant,
        agentStatus: AgentStatus,
        passed: Boolean,
        changedFiles: List<String>,
        stdoutPath: String?,
        stderrPath: String?,
        errorMessage: String?,
    ) = BenchmarkResult(
        taskId = task.id,
        taskName = task.name,
        language = task.language,
        promptPath = task.promptFile.absolutePath,
        testCommand = task.testCommand,
        passed = passed,
        agentStatus = agentStatus,
        durationMs = Instant.now().toEpochMilli() - startedAt.toEpochMilli(),
        stdoutPath = stdoutPath,
        stderrPath = stderrPath,
        changedFiles = changedFiles,
        errorMessage = errorMessage,
        startedAt = startedAt,
        finishedAt = Instant.now(),
        modelLabel = backend.selectedModelLabel(),
        pluginVersion = pluginVersion,
    )

    private data class VerifyResult(val stdout: String, val stderr: String, val exitCode: Int)

    private fun runVerification(task: BenchmarkTask, workDir: File): VerifyResult =
        runCommand(task.testCommand.trim(), workDir, task.timeoutMinutes)

    private fun runCommand(rawCmd: String, workDir: File, timeoutMinutes: Int): VerifyResult {
        return try {
            val proc = ProcessBuilder(buildCommand(rawCmd))
                .directory(workDir)
                .start()

            // Read stdout and stderr concurrently to avoid blocking when either buffer fills.
            val stdoutFuture = java.util.concurrent.Executors.newSingleThreadExecutor().submit<String> {
                proc.inputStream.bufferedReader().readText()
            }
            val stderrFuture = java.util.concurrent.Executors.newSingleThreadExecutor().submit<String> {
                proc.errorStream.bufferedReader().readText()
            }

            val timeoutMs = TimeUnit.MINUTES.toMillis(timeoutMinutes.toLong())
            val timedOut = !proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (timedOut) proc.destroyForcibly()

            val stdout = runCatching { stdoutFuture.get(10, TimeUnit.SECONDS) }.getOrDefault("")
            val stderr = runCatching { stderrFuture.get(10, TimeUnit.SECONDS) }.getOrDefault("")
            val exitCode = if (timedOut) -1 else proc.exitValue()

            VerifyResult(stdout, stderr, exitCode)
        } catch (e: Exception) {
            VerifyResult("", e.message ?: "Command failed", -1)
        }
    }

    private fun buildCommand(rawCmd: String): List<String> {
        val isWindows = System.getProperty("os.name", "").lowercase().startsWith("win")
        return if (isWindows) {
            listOf("cmd", "/c", rawCmd)
        } else {
            listOf("sh", "-c", rawCmd)
        }
    }

    private fun injectGradleWrapper(dst: File) {
        val projectRoot = File(System.getProperty("user.dir"))
        val srcGradlew = File(projectRoot, "gradlew").takeIf { it.isFile } ?: return
        val srcGradlewBat = File(projectRoot, "gradlew.bat")
        val srcWrapperDir = File(projectRoot, "gradle/wrapper")

        srcGradlew.copyTo(File(dst, "gradlew"), overwrite = true).also { it.setExecutable(true, false) }
        if (srcGradlewBat.isFile) srcGradlewBat.copyTo(File(dst, "gradlew.bat"), overwrite = true)
        if (srcWrapperDir.isDirectory) {
            val dstWrapperDir = File(dst, "gradle/wrapper")
            dstWrapperDir.mkdirs()
            srcWrapperDir.listFiles()?.forEach { f ->
                f.copyTo(File(dstWrapperDir, f.name), overwrite = true)
            }
        }

        // Inject shared build files unless the task repo provides its own.
        val sharedDir = File(projectRoot, "benchmarks/shared")
        if (sharedDir.isDirectory) {
            sharedDir.listFiles()?.forEach { f ->
                val target = File(dst, f.name)
                if (!target.exists()) f.copyTo(target)
            }
        }
    }

    private fun copyTree(src: File, dst: File) {
        src.walkTopDown().forEach { file ->
            val rel = file.relativeTo(src)
            val target = File(dst, rel.path)
            if (file.isDirectory) {
                target.mkdirs()
            } else {
                file.copyTo(target, overwrite = true)
                // Restore executable bit — File.copyTo drops +x on POSIX systems.
                if (file.canExecute()) target.setExecutable(true, false)
            }
        }
    }
}
