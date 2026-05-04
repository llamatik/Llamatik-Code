package com.llamatik.teammate.tools.impl

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.benchmark.ModelBenchmarkService
import com.llamatik.teammate.settings.LlamatikAppSettings
import com.llamatik.teammate.tools.LlamatikTool
import com.llamatik.teammate.tools.ToolCall
import com.llamatik.teammate.tools.ToolExecutionContext
import com.llamatik.teammate.tools.ToolResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ModelBenchmarkListTool(project: Project) : BaseProjectTool(project) {
    override val name = "model_benchmark_list"
    override val description = "List benchmark results for installed models. No arguments."

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val svc = service<ModelBenchmarkService>()
        val runs = svc.getResults()
        if (runs.isEmpty()) return ToolResult(name, true,
            "No benchmark results. Run model_benchmark_run to benchmark the current model.")
        val byModel = runs.groupBy { it.modelLabel }
        val text = byModel.entries.joinToString("\n\n") { (label, modelRuns) ->
            svc.formatResults(modelRuns)
        }
        return ToolResult(name, true, text)
    }
}

class ModelBenchmarkRunTool(project: Project) : BaseProjectTool(project) {
    override val name = "model_benchmark_run"
    override val description = "Benchmark the current model. Arguments: prompts (optional, comma-sep: code_explain,bug_fix,small_refactor,patch_gen)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val settings = service<LlamatikAppSettings>().data()
        val modelPath = settings.modelPath.ifBlank { settings.modelIdentifier }
        if (modelPath.isBlank()) return ToolResult(name, false, "No model configured")
        val promptNames = call.arguments["prompts"]?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            ?: ModelBenchmarkService().PROMPTS.keys.toList()
        val svc = service<ModelBenchmarkService>()
        val latch = CountDownLatch(1)
        var results: List<ModelBenchmarkService.BenchmarkRun> = emptyList()
        svc.run(modelPath, promptNames, onProgress = {}, onDone = { runs ->
            results = runs
            latch.countDown()
        })
        val timeout = promptNames.size * 70L
        latch.await(timeout, TimeUnit.SECONDS)
        if (results.isEmpty()) return ToolResult(name, false, "Benchmark timed out or failed")
        return ToolResult(name, true, svc.formatResults(results) + "\n\nBenchmark complete. Report these results to the user — do NOT run again.")
    }
}

class ModelBenchmarkResultsTool(project: Project) : BaseProjectTool(project) {
    override val name = "model_benchmark_results"
    override val description = "Get benchmark results for a specific model path. Arguments: model_path (optional)"

    override fun execute(call: ToolCall, context: ToolExecutionContext): ToolResult {
        val svc = service<ModelBenchmarkService>()
        val modelPath = call.arguments["model_path"]
            ?: service<LlamatikAppSettings>().data().modelPath
        val runs = if (modelPath.isNotBlank()) svc.getResultsForModel(modelPath) else svc.getResults()
        if (runs.isEmpty()) return ToolResult(name, true, "No results for specified model")
        return ToolResult(name, true, svc.formatResults(runs))
    }
}
