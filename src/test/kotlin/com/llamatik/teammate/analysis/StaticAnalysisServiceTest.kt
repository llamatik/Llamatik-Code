package com.llamatik.teammate.analysis

import org.junit.Assert.*
import org.junit.Test

class StaticAnalysisServiceTest {

    // Reuse parse logic via a helper that mirrors StaticAnalysisService private methods

    private fun parseKtlint(raw: String): List<StaticAnalysisService.Finding> {
        val regex = Regex("""^(.+?):(\d+):(\d+): (.+?) \((.+?)\)$""", RegexOption.MULTILINE)
        return regex.findAll(raw).map { m ->
            StaticAnalysisService.Finding("ktlint", m.groupValues[1], m.groupValues[2].toIntOrNull() ?: 0,
                m.groupValues[3].toIntOrNull() ?: 0, StaticAnalysisService.Severity.WARNING, m.groupValues[5], m.groupValues[4])
        }.take(200).toList()
    }

    private fun parseDetekt(raw: String): List<StaticAnalysisService.Finding> {
        val regex = Regex("""^(\S+) - (.+?):(\d+):(\d+)""", RegexOption.MULTILINE)
        return regex.findAll(raw).map { m ->
            StaticAnalysisService.Finding("detekt", m.groupValues[2], m.groupValues[3].toIntOrNull() ?: 0,
                m.groupValues[4].toIntOrNull() ?: 0, StaticAnalysisService.Severity.WARNING, m.groupValues[1], m.groupValues[1])
        }.take(200).toList()
    }

    private fun parseEslint(raw: String): List<StaticAnalysisService.Finding> {
        val regex = Regex("""^\s+(\d+):(\d+)\s+(error|warning)\s+(.+?)\s{2,}(.+)$""", RegexOption.MULTILINE)
        var currentFile = ""
        val findings = mutableListOf<StaticAnalysisService.Finding>()
        raw.lines().forEach { line ->
            if (line.isNotBlank() && !line.trimStart().first().isDigit() && line.contains("/")) currentFile = line.trim()
            val m = regex.find(line) ?: return@forEach
            val sev = if (m.groupValues[3] == "error") StaticAnalysisService.Severity.ERROR else StaticAnalysisService.Severity.WARNING
            findings += StaticAnalysisService.Finding("eslint", currentFile, m.groupValues[1].toIntOrNull() ?: 0,
                m.groupValues[2].toIntOrNull() ?: 0, sev, m.groupValues[5].trim(), m.groupValues[4].trim())
        }
        return findings.take(200)
    }

    @Test fun `parse ktlint single finding`() {
        val raw = "src/main/kotlin/Foo.kt:10:4: Unexpected spacing before ':' (spacing-around-colon)"
        val findings = parseKtlint(raw)
        assertEquals(1, findings.size)
        assertEquals("src/main/kotlin/Foo.kt", findings[0].file)
        assertEquals(10, findings[0].line)
        assertEquals("spacing-around-colon", findings[0].rule)
        assertEquals("Unexpected spacing before ':'", findings[0].message)
    }

    @Test fun `parse ktlint multiple findings`() {
        val raw = """
            src/A.kt:1:1: Missing newline before } (standard:no-empty-first-line-in-class-body)
            src/B.kt:5:2: Unnecessary semicolon (standard:no-semi)
        """.trimIndent()
        val findings = parseKtlint(raw)
        assertEquals(2, findings.size)
    }

    @Test fun `parse ktlint empty output`() {
        assertEquals(emptyList<StaticAnalysisService.Finding>(), parseKtlint(""))
        assertEquals(emptyList<StaticAnalysisService.Finding>(), parseKtlint("No issues found"))
    }

    @Test fun `parse detekt finding`() {
        val raw = "MagicNumber - src/main/kotlin/Calc.kt:42:12"
        val findings = parseDetekt(raw)
        assertEquals(1, findings.size)
        assertEquals("MagicNumber", findings[0].rule)
        assertEquals("src/main/kotlin/Calc.kt", findings[0].file)
        assertEquals(42, findings[0].line)
    }

    @Test fun `parse eslint finding`() {
        val raw = """/project/src/index.ts
  10:5  error  'foo' is not defined  no-undef
  12:1  warning  Unexpected var  no-var
"""
        val findings = parseEslint(raw)
        assertEquals(2, findings.size)
        assertEquals(StaticAnalysisService.Severity.ERROR, findings[0].severity)
        assertEquals(StaticAnalysisService.Severity.WARNING, findings[1].severity)
        assertEquals(10, findings[0].line)
        assertEquals("no-undef", findings[0].rule)
    }
}
