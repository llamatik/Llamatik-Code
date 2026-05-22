package com.llamatik.teammate.release

import java.io.File
import java.time.Instant

/**
 * Aggregates results from all release validation tasks and writes
 * build/release-validation/results.json and results.md.
 *
 * Called by the releaseValidationReport Gradle task via JUnit's
 * afterSuite hooks or directly from the Gradle task action.
 */
object ReleaseValidationReport {

    data class TaskResult(
        val task: String,
        val passed: Boolean,
        val total: Int,
        val failed: Int,
        val skipped: Int,
        val durationMs: Long,
        val failureMessages: List<String>,
    )

    fun write(results: List<TaskResult>, reportDir: File, pluginVersion: String) {
        reportDir.mkdirs()
        writeJson(results, File(reportDir, "results.json"), pluginVersion)
        writeMarkdown(results, File(reportDir, "results.md"), pluginVersion)
    }

    private fun writeJson(results: List<TaskResult>, out: File, version: String) {
        val passed = results.count { it.passed }
        val total = results.size
        val sb = StringBuilder()
        sb.appendLine("{")
        sb.appendLine("  \"pluginVersion\": \"$version\",")
        sb.appendLine("  \"timestamp\": \"${Instant.now()}\",")
        sb.appendLine("  \"summary\": { \"passed\": $passed, \"total\": $total },")
        sb.appendLine("  \"tasks\": [")
        results.forEachIndexed { i, r ->
            val comma = if (i < results.size - 1) "," else ""
            sb.appendLine("    {")
            sb.appendLine("      \"task\": \"${r.task}\",")
            sb.appendLine("      \"passed\": ${r.passed},")
            sb.appendLine("      \"total\": ${r.total},")
            sb.appendLine("      \"failed\": ${r.failed},")
            sb.appendLine("      \"skipped\": ${r.skipped},")
            sb.appendLine("      \"durationMs\": ${r.durationMs}")
            sb.append("    }$comma")
            if (i < results.size - 1) sb.appendLine() else sb.appendLine()
        }
        sb.appendLine("  ]")
        sb.appendLine("}")
        out.writeText(sb.toString())
    }

    private fun writeMarkdown(results: List<TaskResult>, out: File, version: String) {
        val passed = results.count { it.passed }
        val total = results.size
        val sb = StringBuilder()
        sb.appendLine("# Release Validation — v$version")
        sb.appendLine()
        sb.appendLine("Generated: ${Instant.now()}")
        sb.appendLine()
        sb.appendLine("## Summary")
        sb.appendLine()
        sb.appendLine("**$passed / $total** task groups passed.")
        sb.appendLine()
        sb.appendLine("| Task | Status | Tests | Failed | Duration |")
        sb.appendLine("|---|---|---|---|---|")
        results.forEach { r ->
            val status = if (r.passed) "PASS" else "FAIL"
            sb.appendLine("| ${r.task} | $status | ${r.total} | ${r.failed} | ${r.durationMs}ms |")
        }
        if (results.any { !it.passed }) {
            sb.appendLine()
            sb.appendLine("## Failures")
            sb.appendLine()
            results.filter { !it.passed }.forEach { r ->
                sb.appendLine("### ${r.task}")
                r.failureMessages.forEach { msg -> sb.appendLine("- $msg") }
            }
        }
        out.writeText(sb.toString())
    }
}
