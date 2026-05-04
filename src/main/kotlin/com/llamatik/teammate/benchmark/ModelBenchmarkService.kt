package com.llamatik.teammate.benchmark

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.llamatik.teammate.backend.AssistantBackend
import com.llamatik.teammate.backend.BackendConfig
import com.llamatik.teammate.backend.GenerationCallbacks
import com.llamatik.teammate.backend.GenerationHandle
import com.llamatik.teammate.backend.GenerationRequest
import com.llamatik.teammate.backend.SessionTag
import com.llamatik.teammate.llamatik.LlamatikBackendService
import com.llamatik.teammate.model.ChatMessage
import com.llamatik.teammate.model.ChatRole
import com.llamatik.teammate.settings.LlamatikAppSettings
import com.llamatik.teammate.util.PluginLogger
import java.util.concurrent.atomic.AtomicBoolean

@State(name = "LlamatikBenchmarks", storages = [Storage("llamatik_benchmarks.xml")])
@Service(Service.Level.APP)
class ModelBenchmarkService : PersistentStateComponent<ModelBenchmarkService.State> {

    private val log = PluginLogger.logger<ModelBenchmarkService>()

    data class BenchmarkRun(
        var modelPath: String = "",
        var modelLabel: String = "",
        var promptName: String = "",
        var tokensPerSecond: Double = 0.0,
        var timeToFirstTokenMs: Long = 0,
        var totalTokens: Int = 0,
        var durationMs: Long = 0,
        var succeeded: Boolean = false,
        var runAtMs: Long = 0,
    )

    data class State(
        var runs: MutableList<BenchmarkRun> = mutableListOf(),
    )

    private var myState = State()
    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    val PROMPTS = mapOf(
        "code_explain" to "Explain in 2 sentences what this Kotlin function does: fun fib(n: Int): Int = if (n <= 1) n else fib(n-1) + fib(n-2)",
        "bug_fix" to "Fix this Kotlin bug: fun divide(a: Int, b: Int) = a / b. Handle division by zero.",
        "small_refactor" to "Refactor this to use idiomatic Kotlin: val list = ArrayList<String>(); list.add(\"a\"); list.add(\"b\"); return list",
        "patch_gen" to "Write a Kotlin function that parses a version string like '1.2.3' into (major, minor, patch) integers.",
    )

    fun getResults(): List<BenchmarkRun> = myState.runs.toList()

    fun getResultsForModel(modelPath: String): List<BenchmarkRun> =
        myState.runs.filter { it.modelPath == modelPath }

    fun run(
        modelPath: String,
        promptNames: List<String> = PROMPTS.keys.toList(),
        onProgress: (String) -> Unit,
        onDone: (List<BenchmarkRun>) -> Unit,
    ) {
        val settings = service<LlamatikAppSettings>().data()
        val backend = service<LlamatikBackendService>()
        val config = BackendConfig(
            modelIdOrPath = modelPath,
            temperature = 0.0,
            maxTokens = 128,
            contextLength = 2048,
            numThreads = settings.numThreads,
            useMmap = settings.useMmap,
            flashAttention = settings.flashAttention,
            batchSize = settings.batchSize,
            localOnly = true,
        )
        ApplicationManager.getApplication().executeOnPooledThread {
            val results = mutableListOf<BenchmarkRun>()
            promptNames.forEach { promptName ->
                val prompt = PROMPTS[promptName] ?: return@forEach
                onProgress("Benchmarking $promptName…")
                val run = runSingle(backend, config, modelPath, promptName, prompt)
                results += run
                myState.runs.removeIf { it.modelPath == modelPath && it.promptName == promptName }
                myState.runs += run
            }
            if (myState.runs.size > 200) {
                myState.runs = myState.runs.takeLast(200).toMutableList()
            }
            onDone(results)
        }
    }

    fun formatResults(runs: List<BenchmarkRun>): String {
        if (runs.isEmpty()) return "No benchmark results"
        return buildString {
            appendLine("⚠️  Results are approximate and depend on hardware load.")
            appendLine()
            appendLine("Model: ${runs.first().modelLabel}")
            appendLine()
            runs.forEach { r ->
                val tok = if (r.tokensPerSecond > 0) "${"%.1f".format(r.tokensPerSecond)} tok/s" else "n/a"
                val ttft = if (r.timeToFirstTokenMs > 0) "${r.timeToFirstTokenMs}ms TTFT" else ""
                appendLine("  ${r.promptName.padEnd(16)} ${if (r.succeeded) "✓" else "✗"}  $tok  $ttft")
            }
            val avgTps = runs.filter { it.succeeded && it.tokensPerSecond > 0 }.map { it.tokensPerSecond }
            if (avgTps.isNotEmpty()) appendLine("\nAvg: ${"%.1f".format(avgTps.average())} tok/s")
        }.trim()
    }

    private fun runSingle(
        backend: AssistantBackend,
        config: BackendConfig,
        modelPath: String,
        promptName: String,
        prompt: String,
    ): BenchmarkRun {
        val run = BenchmarkRun(
            modelPath = modelPath,
            modelLabel = backend.selectedModelLabel(),
            promptName = promptName,
            runAtMs = System.currentTimeMillis(),
        )
        val done = AtomicBoolean(false)
        val startMs = System.currentTimeMillis()
        var firstTokenMs = 0L
        var tokenCount = 0

        val handle = backend.generate(
            GenerationRequest(
                systemPrompt = "You are a coding assistant.",
                messages = listOf(ChatMessage(role = ChatRole.USER, content = prompt)),
                config = config,
                noThink = true,
                sessionTag = SessionTag.AGENT,
            ),
            object : GenerationCallbacks {
                override fun onToken(token: String) {
                    if (firstTokenMs == 0L) firstTokenMs = System.currentTimeMillis() - startMs
                    tokenCount++
                }
                override fun onCompleted(fullText: String) {
                    val durationMs = System.currentTimeMillis() - startMs
                    run.durationMs = durationMs
                    run.timeToFirstTokenMs = firstTokenMs
                    run.totalTokens = tokenCount
                    run.tokensPerSecond = if (durationMs > 0) tokenCount * 1000.0 / durationMs else 0.0
                    run.succeeded = fullText.isNotBlank()
                    done.set(true)
                }
                override fun onError(message: String, throwable: Throwable?) {
                    run.succeeded = false
                    done.set(true)
                }
            },
        )
        // Wait for completion (synchronous in pooled thread)
        val deadline = System.currentTimeMillis() + 60_000
        while (!done.get() && System.currentTimeMillis() < deadline) Thread.sleep(50)
        if (!done.get()) { handle.cancel(); run.succeeded = false }
        return run
    }
}
