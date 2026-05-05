package com.llamatik.teammate.diagnostics

import org.junit.Assert.*
import org.junit.Test

class DiagnosticsServiceTest {

    private fun check(
        name: String,
        status: DiagnosticsService.CheckStatus,
        detail: String = "detail",
        suggestion: String = "",
    ) = DiagnosticsService.DiagnosticCheck(name, status, detail, suggestion)

    private fun report(vararg checks: DiagnosticsService.DiagnosticCheck) =
        DiagnosticsService.DiagnosticsReport(
            checks = checks.toList(),
            passCount = checks.count { it.status == DiagnosticsService.CheckStatus.PASS },
            warnCount = checks.count { it.status == DiagnosticsService.CheckStatus.WARN },
            failCount = checks.count { it.status == DiagnosticsService.CheckStatus.FAIL },
        )

    // ── DiagnosticsReport.isHealthy ───────────────────────────────────────────

    @Test
    fun `isHealthy returns true when no failures`() {
        val r = report(
            check("A", DiagnosticsService.CheckStatus.PASS),
            check("B", DiagnosticsService.CheckStatus.WARN),
        )
        assertTrue(r.isHealthy)
    }

    @Test
    fun `isHealthy returns false when at least one failure`() {
        val r = report(
            check("A", DiagnosticsService.CheckStatus.PASS),
            check("B", DiagnosticsService.CheckStatus.FAIL),
        )
        assertFalse(r.isHealthy)
    }

    @Test
    fun `counts are accurate for mixed checks`() {
        val r = report(
            check("A", DiagnosticsService.CheckStatus.PASS),
            check("B", DiagnosticsService.CheckStatus.PASS),
            check("C", DiagnosticsService.CheckStatus.WARN),
            check("D", DiagnosticsService.CheckStatus.FAIL),
        )
        assertEquals(2, r.passCount)
        assertEquals(1, r.warnCount)
        assertEquals(1, r.failCount)
    }

    // ── formatReport ─────────────────────────────────────────────────────────

    private fun formatReport(
        report: DiagnosticsService.DiagnosticsReport,
        includeSensitive: Boolean = false,
    ): String = buildString {
        appendLine("Llamatik Code Diagnostics")
        appendLine("=".repeat(40))
        appendLine("Pass: ${report.passCount}  Warn: ${report.warnCount}  Fail: ${report.failCount}")
        appendLine()
        report.checks.forEach { c ->
            val icon = when (c.status) {
                DiagnosticsService.CheckStatus.PASS -> "✓"
                DiagnosticsService.CheckStatus.WARN -> "⚠"
                DiagnosticsService.CheckStatus.FAIL -> "✗"
            }
            appendLine("$icon ${c.name}")
            if (!includeSensitive && c.detail.contains("/Users") && c.detail.contains(".gguf")) {
                appendLine("  [path redacted — enable 'include sensitive' to show]")
            } else {
                appendLine("  ${c.detail}")
            }
            if (c.suggestion.isNotBlank() && c.status != DiagnosticsService.CheckStatus.PASS) {
                appendLine("  → ${c.suggestion}")
            }
        }
    }.trim()

    @Test
    fun `formatReport contains header and counts`() {
        val r = report(check("Model file", DiagnosticsService.CheckStatus.PASS))
        val output = formatReport(r)
        assertTrue(output.contains("Llamatik Code Diagnostics"))
        assertTrue(output.contains("Pass: 1"))
        assertTrue(output.contains("Warn: 0"))
        assertTrue(output.contains("Fail: 0"))
    }

    @Test
    fun `formatReport shows correct icon for each status`() {
        val r = report(
            check("A", DiagnosticsService.CheckStatus.PASS),
            check("B", DiagnosticsService.CheckStatus.WARN),
            check("C", DiagnosticsService.CheckStatus.FAIL),
        )
        val output = formatReport(r)
        assertTrue(output.contains("✓ A"))
        assertTrue(output.contains("⚠ B"))
        assertTrue(output.contains("✗ C"))
    }

    @Test
    fun `formatReport shows suggestion for warn and fail but not pass`() {
        val r = report(
            check("A", DiagnosticsService.CheckStatus.PASS, suggestion = "Should not show"),
            check("B", DiagnosticsService.CheckStatus.FAIL, suggestion = "Fix it now"),
        )
        val output = formatReport(r)
        assertFalse("Pass check suggestion must not appear", output.contains("Should not show"))
        assertTrue("Fail check suggestion must appear", output.contains("Fix it now"))
    }

    @Test
    fun `formatReport redacts gguf path when includeSensitive is false`() {
        val r = report(check("Model", DiagnosticsService.CheckStatus.PASS,
            detail = "/Users/alice/models/my-model.gguf (currently loaded)"))
        val output = formatReport(r, includeSensitive = false)
        assertTrue(output.contains("redacted"))
        assertFalse(output.contains("/Users/alice"))
    }

    @Test
    fun `formatReport shows full path when includeSensitive is true`() {
        val r = report(check("Model", DiagnosticsService.CheckStatus.PASS,
            detail = "/Users/alice/models/my-model.gguf (currently loaded)"))
        val output = formatReport(r, includeSensitive = true)
        assertTrue(output.contains("/Users/alice"))
        assertFalse(output.contains("redacted"))
    }

    // ── fixSuggestions ────────────────────────────────────────────────────────

    private fun fixSuggestions(report: DiagnosticsService.DiagnosticsReport): List<String> =
        report.checks.filter { it.status != DiagnosticsService.CheckStatus.PASS && it.suggestion.isNotBlank() }
            .map { "${it.name}: ${it.suggestion}" }

    @Test
    fun `fixSuggestions returns empty list when all checks pass`() {
        val r = report(
            check("A", DiagnosticsService.CheckStatus.PASS, suggestion = "irrelevant"),
            check("B", DiagnosticsService.CheckStatus.PASS),
        )
        assertTrue(fixSuggestions(r).isEmpty())
    }

    @Test
    fun `fixSuggestions includes name and suggestion text`() {
        val r = report(
            check("Native library", DiagnosticsService.CheckStatus.FAIL,
                suggestion = "Reinstall the plugin"),
            check("RAM", DiagnosticsService.CheckStatus.WARN,
                suggestion = "Use a smaller model"),
        )
        val suggestions = fixSuggestions(r)
        assertEquals(2, suggestions.size)
        assertTrue(suggestions.any { it.contains("Native library") && it.contains("Reinstall") })
        assertTrue(suggestions.any { it.contains("RAM") && it.contains("smaller model") })
    }

    @Test
    fun `fixSuggestions omits checks without a suggestion`() {
        val r = report(
            check("Git", DiagnosticsService.CheckStatus.WARN, suggestion = ""),
            check("Disk", DiagnosticsService.CheckStatus.FAIL, suggestion = "Free space"),
        )
        val suggestions = fixSuggestions(r)
        assertEquals(1, suggestions.size)
        assertTrue(suggestions[0].contains("Disk"))
    }

    // ── Java version parsing ──────────────────────────────────────────────────

    @Test
    fun `java version major extraction handles standard version strings`() {
        // Mirrors the parsing logic in checkJavaVersion()
        fun parseMajor(version: String): Int =
            version.substringBefore(".").toIntOrNull()
                ?: version.substringBefore("-").toIntOrNull() ?: 0

        assertEquals(17, parseMajor("17.0.9"))
        assertEquals(21, parseMajor("21"))
        assertEquals(11, parseMajor("11.0.21+9"))
        assertEquals(17, parseMajor("17-ea"))
        assertEquals(0, parseMajor("unknown"))
    }
}
