package com.llamatik.agent.core.host

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

data class ShellResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val succeeded: Boolean,
    /** True when the host gave up waiting; the other fields are then not meaningful. */
    val timedOut: Boolean = false,
)

/** Runs shell commands in the project and remembers their output. */
interface ShellRunner {
    /** Runs [command] in [cwd], blocking up to [timeoutMs]. Never throws for a failing command. */
    fun run(command: String, cwd: String, timeoutMs: Long): ShellResult

    fun lastOutput(command: String): ShellResult?

    /** Output of the most recently finished command. */
    fun lastOutputAny(): ShellResult?
}

/** Commands the agent may run without asking the user. */
object CommandAllowlist {
    val ALLOWLIST: Set<String> = setOf(
        "./gradlew test", "./gradlew build", "./gradlew lint", "./gradlew check",
        "./gradlew assemble", "./gradlew assembleDebug", "./gradlew assembleRelease",
        "./gradlew clean", "./gradlew ktlintCheck",
        "./gradlew detekt", "./gradlew dependencies",
        // KMM / Android-specific compile tasks (variants and module-qualified)
        "./gradlew compileDebugKotlinAndroid", "./gradlew compileReleaseKotlinAndroid",
        "./gradlew compileDebugKotlin", "./gradlew compileReleaseKotlin",
        "./gradlew compileKotlin", "./gradlew compileCommonMainKotlinMetadata",
        "gradlew.bat test", "gradlew.bat build", "gradlew.bat lint",
        "npm test", "npm run build", "npm run lint", "npm run check",
        "ktlint", "detekt",
    )

    private val SAFE_GRADLE_TASKS = setOf(
        "build", "test", "check", "lint", "assemble", "assembleDebug", "assembleRelease",
        "compileKotlin", "compileDebugKotlin", "compileReleaseKotlin",
        "compileDebugKotlinAndroid", "compileReleaseKotlinAndroid",
        "compileCommonMainKotlinMetadata", "clean", "ktlintCheck", "detekt", "dependencies",
    )

    fun allowedCommands(): List<String> = ALLOWLIST.sorted()

    fun isAllowed(command: String): Boolean {
        val trimmed = command.trim()
        if (ALLOWLIST.any { allowed ->
                trimmed == allowed || trimmed.startsWith("$allowed ") || trimmed.startsWith("$allowed\t")
            }) return true

        // Also allow module-qualified Gradle tasks: ./gradlew :module:taskName
        // This covers KMM/Android tasks like ./gradlew :shared:compileDebugKotlinAndroid
        if (trimmed.startsWith("./gradlew ") || trimmed.startsWith("gradlew.bat ")) {
            val afterGradlew = trimmed.removePrefix("./gradlew ").removePrefix("gradlew.bat ")
            val firstTaskToken = afterGradlew.split(" ", "\t").firstOrNull { it.isNotBlank() } ?: return false
            val taskName = firstTaskToken.substringAfterLast(':')
            if (taskName in SAFE_GRADLE_TASKS) return true
        }
        return false
    }

    /** Splits a command line on spaces, honouring single and double quotes. */
    fun splitCommand(cmd: String): List<String> {
        val result = mutableListOf<String>()
        val buf = StringBuilder()
        var inQuote = false
        var quoteChar = ' '
        for (c in cmd.trim()) {
            when {
                inQuote && c == quoteChar -> { inQuote = false }
                !inQuote && (c == '"' || c == '\'') -> { inQuote = true; quoteChar = c }
                !inQuote && c == ' ' -> { if (buf.isNotEmpty()) { result += buf.toString(); buf.clear() } }
                else -> buf.append(c)
            }
        }
        if (buf.isNotEmpty()) result += buf.toString()
        return result
    }
}

/** [ShellRunner] on [ProcessBuilder], for hosts without an IDE. */
class ProcessShellRunner : ShellRunner {
    private val lastOutputs = ConcurrentHashMap<String, ShellResult>()
    @Volatile private var lastCommand: String? = null

    override fun run(command: String, cwd: String, timeoutMs: Long): ShellResult {
        val start = System.currentTimeMillis()
        val result = try {
            val process = ProcessBuilder(CommandAllowlist.splitCommand(command))
                .directory(File(cwd))
                .start()
            process.outputStream.close()
            // Drain both pipes concurrently or a chatty process blocks on a full buffer.
            var stdout = ""
            var stderr = ""
            val outReader = Thread { stdout = process.inputStream.readBytes().toString(Charsets.UTF_8) }.apply { isDaemon = true; start() }
            val errReader = Thread { stderr = process.errorStream.readBytes().toString(Charsets.UTF_8) }.apply { isDaemon = true; start() }
            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly().waitFor()
            outReader.join(2_000)
            errReader.join(2_000)
            val exit = if (finished) process.exitValue() else -1
            ShellResult(
                command = command,
                exitCode = exit,
                stdout = stdout.take(50_000),
                stderr = stderr.take(20_000),
                durationMs = System.currentTimeMillis() - start,
                succeeded = exit == 0,
            )
        } catch (e: Exception) {
            ShellResult(command, -1, "", e.message ?: "Failed", System.currentTimeMillis() - start, false)
        }
        lastOutputs[command] = result
        lastCommand = command
        return result
    }

    override fun lastOutput(command: String): ShellResult? = lastOutputs[command]

    override fun lastOutputAny(): ShellResult? = lastCommand?.let { lastOutputs[it] }
}
