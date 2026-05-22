package com.llamatik.teammate.benchmark

import java.io.File
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.time.format.DateTimeFormatter

object BenchmarkReportWriter {

    private val ISO = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'").withZone(ZoneOffset.UTC)

    fun writeAll(
        results: List<BenchmarkResult>,
        reportDir: File,
        suiteStartedAt: Instant,
    ) {
        reportDir.mkdirs()
        writeJson(results, File(reportDir, "results.json"), suiteStartedAt)
        writeMarkdown(results, File(reportDir, "results.md"), suiteStartedAt)
    }

    private fun writeJson(results: List<BenchmarkResult>, file: File, suiteStartedAt: Instant) {
        val sb = StringBuilder()
        sb.appendLine("{")
        sb.appendLine("  \"benchmarkStartedAt\": \"${ISO.format(suiteStartedAt)}\",")
        sb.appendLine("  \"benchmarkFinishedAt\": \"${ISO.format(Instant.now())}\",")
        val version = results.firstOrNull()?.pluginVersion ?: "unknown"
        val model = results.firstOrNull()?.modelLabel ?: "unknown"
        sb.appendLine("  \"pluginVersion\": \"$version\",")
        sb.appendLine("  \"model\": \"$model\",")
        sb.appendLine("  \"totalTasks\": ${results.size},")
        sb.appendLine("  \"passed\": ${results.count { it.passed }},")
        sb.appendLine("  \"failed\": ${results.count { !it.passed }},")
        sb.appendLine("  \"results\": [")
        results.forEachIndexed { i, r ->
            sb.appendLine("    {")
            sb.appendLine("      \"taskId\": \"${r.taskId}\",")
            sb.appendLine("      \"taskName\": \"${r.taskName}\",")
            sb.appendLine("      \"language\": \"${r.language}\",")
            sb.appendLine("      \"promptPath\": \"${r.promptPath.jsonEscape()}\",")
            sb.appendLine("      \"testCommand\": \"${r.testCommand.jsonEscape()}\",")
            sb.appendLine("      \"agentStatus\": \"${r.agentStatus}\",")
            sb.appendLine("      \"passed\": ${r.passed},")
            sb.appendLine("      \"durationMs\": ${r.durationMs},")
            sb.appendLine("      \"startedAt\": \"${ISO.format(r.startedAt)}\",")
            sb.appendLine("      \"finishedAt\": \"${ISO.format(r.finishedAt)}\",")
            sb.appendLine("      \"stdoutPath\": ${r.stdoutPath?.let { "\"${it.jsonEscape()}\"" } ?: "null"},")
            sb.appendLine("      \"stderrPath\": ${r.stderrPath?.let { "\"${it.jsonEscape()}\"" } ?: "null"},")
            sb.appendLine("      \"errorMessage\": ${r.errorMessage?.let { "\"${it.jsonEscape()}\"" } ?: "null"},")
            sb.appendLine("      \"changedFiles\": [${r.changedFiles.joinToString { "\"${it.jsonEscape()}\"" }}]")
            sb.append("    }")
            if (i < results.lastIndex) sb.append(",")
            sb.appendLine()
        }
        sb.appendLine("  ]")
        sb.appendLine("}")
        file.writeText(sb.toString())
    }

    private fun writeMarkdown(results: List<BenchmarkResult>, file: File, suiteStartedAt: Instant) {
        val sb = StringBuilder()
        sb.appendLine("# Llamatik Code Benchmark Results")
        sb.appendLine()
        sb.appendLine("Run: ${ISO.format(suiteStartedAt)}")
        val version = results.firstOrNull()?.pluginVersion ?: "unknown"
        val model = results.firstOrNull()?.modelLabel ?: "unknown"
        sb.appendLine("Plugin: $version | Model: $model")
        sb.appendLine()
        sb.appendLine("| Task | Language | Status | Passed | Duration | Test command |")
        sb.appendLine("|---|---|---|---|---|---|")
        results.forEach { r ->
            val status = r.agentStatus.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercaseChar() }
            val passed = if (r.passed) "✓" else "✗"
            val duration = "%.1fs".format(Locale.US,r.durationMs / 1000.0)
            val cmd = r.testCommand.take(40)
            sb.appendLine("| ${r.taskName} | ${r.language} | $status | $passed | $duration | `$cmd` |")
        }
        sb.appendLine()
        val total = results.size
        val passed = results.count { it.passed }
        val failed = total - passed
        val passRate = if (total > 0) "%.0f%%".format(passed * 100.0 / total) else "n/a"
        val avgDuration = if (results.isNotEmpty()) "%.1fs".format(Locale.US,results.map { it.durationMs }.average() / 1000.0) else "n/a"
        sb.appendLine("## Summary")
        sb.appendLine()
        sb.appendLine("- Total tasks: $total")
        sb.appendLine("- Passed: $passed")
        sb.appendLine("- Failed: $failed")
        sb.appendLine("- Pass rate: $passRate")
        sb.appendLine("- Average duration: $avgDuration")
        file.writeText(sb.toString())
    }

    private fun String.jsonEscape(): String =
        replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")
}
