package com.llamatik.teammate.run

import com.intellij.execution.ExecutionManager
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.process.ProcessAdapter
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import com.llamatik.teammate.util.PluginLogger
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class RunConfigService(private val project: Project) {

    private val log = PluginLogger.logger<RunConfigService>()

    data class RunConfigInfo(
        val name: String,
        val type: String,
    )

    data class RunOutput(
        val configName: String,
        val exitCode: Int?,
        val stdout: String,
        val stderr: String,
        val succeeded: Boolean,
    )

    private val lastOutputs = ConcurrentHashMap<String, RunOutput>()

    fun listConfigs(): List<RunConfigInfo> {
        return ApplicationManager.getApplication().runReadAction<List<RunConfigInfo>> {
            RunManager.getInstance(project).allSettings.map { setting ->
                RunConfigInfo(
                    name = setting.name,
                    type = setting.type.displayName,
                )
            }
        }
    }

    fun getLastOutput(configName: String): RunOutput? = lastOutputs[configName]

    /**
     * Execute the named run configuration via the IDE execution framework.
     * Attaches a process listener BEFORE launch by intercepting the execution via
     * [ExecutionManager.addRunnerAndConfigurationSettings] override approach.
     *
     * In practice this uses ProgramRunnerUtil and then listens on the returned content
     * descriptor's process handler via the ExecutionManager's running descriptor list.
     */
    fun execute(
        configName: String,
        onDone: (RunOutput) -> Unit,
        onError: (String) -> Unit,
    ) {
        ApplicationManager.getApplication().invokeLater {
            try {
                val runManager = RunManager.getInstance(project)
                val setting = runManager.allSettings.find { it.name == configName }
                    ?: return@invokeLater onError("Run configuration '$configName' not found")

                val executor = DefaultRunExecutor.getRunExecutorInstance()
                val stdoutBuf = StringBuilder()
                val stderrBuf = StringBuilder()

                // Build and execute the environment; capture output by hooking into
                // the running content descriptor after launch.
                val env = com.intellij.execution.runners.ExecutionEnvironmentBuilder
                    .create(executor, setting)
                    .build()

                env.runner.execute(env) { descriptor ->
                    val processHandler = descriptor.processHandler ?: return@execute
                    processHandler.addProcessListener(object : ProcessAdapter() {
                        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                            val text = event.text ?: return
                            if (outputType == ProcessOutputTypes.STDERR) stderrBuf.append(text)
                            else stdoutBuf.append(text)
                        }
                        override fun processTerminated(event: ProcessEvent) {
                            val output = RunOutput(
                                configName = configName,
                                exitCode = event.exitCode,
                                stdout = stdoutBuf.toString().take(20_000),
                                stderr = stderrBuf.toString().take(10_000),
                                succeeded = event.exitCode == 0,
                            )
                            lastOutputs[configName] = output
                            onDone(output)
                        }
                    })
                }
            } catch (e: Exception) {
                log.warn("Failed to execute run config '$configName': ${e.message}")
                onError("Failed to execute '$configName': ${e.message}")
            }
        }
    }

    fun analyzeFailure(output: RunOutput): String {
        if (output.succeeded) return "Run succeeded with exit code 0."
        val combined = (output.stderr + "\n" + output.stdout).take(8000)
        val errorLines = combined.lines().filter { line ->
            val t = line.trimStart()
            t.contains("Exception") || t.contains("Error") ||
            t.contains(": error:") || t.startsWith("e: ") ||
            t.startsWith("at ") || t.contains("FAILED") ||
            t.contains("NullPointerException") || t.contains("ClassCastException")
        }.take(30)
        return buildString {
            appendLine("Exit code: ${output.exitCode}")
            appendLine("Errors and stack trace:")
            appendLine(errorLines.joinToString("\n").take(3000).ifBlank { "No structured errors detected." })
        }.trim()
    }
}
