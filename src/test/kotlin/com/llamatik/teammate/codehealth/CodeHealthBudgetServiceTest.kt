package com.llamatik.teammate.codehealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeHealthBudgetServiceTest {

    // ── Budget constant correctness ───────────────────────────────────────

    @Test fun `free LIGHT max files is 5`() =
        assertEquals(5, CodeHealthBudgets.maxFiles(AnalysisMode.LIGHT, false))

    @Test fun `pro BALANCED max files is 30`() =
        assertEquals(30, CodeHealthBudgets.maxFiles(AnalysisMode.BALANCED, true))

    @Test fun `pro AGGRESSIVE max files is 150`() =
        assertEquals(150, CodeHealthBudgets.maxFiles(AnalysisMode.AGGRESSIVE, true))

    @Test fun `free runtime is 8000ms`() =
        assertEquals(8_000L, CodeHealthBudgets.maxRuntimeMs(AnalysisMode.LIGHT, false))

    @Test fun `pro BALANCED runtime is 30000ms`() =
        assertEquals(30_000L, CodeHealthBudgets.maxRuntimeMs(AnalysisMode.BALANCED, true))

    @Test fun `pro AGGRESSIVE runtime is 120000ms`() =
        assertEquals(120_000L, CodeHealthBudgets.maxRuntimeMs(AnalysisMode.AGGRESSIVE, true))

    @Test fun `free max model calls per hour is 3`() =
        assertEquals(3, CodeHealthBudgets.maxModelCallsPerHour(AnalysisMode.LIGHT, false))

    @Test fun `pro BALANCED max model calls is 20`() =
        assertEquals(20, CodeHealthBudgets.maxModelCallsPerHour(AnalysisMode.BALANCED, true))

    @Test fun `pro AGGRESSIVE max model calls is 50`() =
        assertEquals(50, CodeHealthBudgets.maxModelCallsPerHour(AnalysisMode.AGGRESSIVE, true))

    @Test fun `free max insights per file is 3`() =
        assertEquals(3, CodeHealthBudgets.maxInsightsPerFile(AnalysisMode.LIGHT, false))

    @Test fun `pro BALANCED max insights per file is 10`() =
        assertEquals(10, CodeHealthBudgets.maxInsightsPerFile(AnalysisMode.BALANCED, true))

    @Test fun `pro AGGRESSIVE max insights per file is 20`() =
        assertEquals(20, CodeHealthBudgets.maxInsightsPerFile(AnalysisMode.AGGRESSIVE, true))

    // ── Pro > Free invariants ─────────────────────────────────────────────

    @Test fun `pro always has more max files than free`() {
        assertTrue(CodeHealthBudgets.maxFiles(AnalysisMode.BALANCED, true) > CodeHealthBudgets.maxFiles(AnalysisMode.LIGHT, false))
    }

    @Test fun `pro always has longer max runtime than free`() {
        assertTrue(CodeHealthBudgets.maxRuntimeMs(AnalysisMode.BALANCED, true) > CodeHealthBudgets.maxRuntimeMs(AnalysisMode.LIGHT, false))
    }

    @Test fun `aggressive always has more budget than balanced`() {
        assertTrue(CodeHealthBudgets.maxFiles(AnalysisMode.AGGRESSIVE, true) > CodeHealthBudgets.maxFiles(AnalysisMode.BALANCED, true))
    }

    // ── Enum completeness ─────────────────────────────────────────────────

    @Test fun `AnalysisMode has 4 values`() = assertEquals(4, AnalysisMode.values().size)

    @Test fun `AnalysisMode has OFF LIGHT BALANCED AGGRESSIVE`() {
        val names = AnalysisMode.values().map { it.name }
        assertTrue("OFF" in names)
        assertTrue("LIGHT" in names)
        assertTrue("BALANCED" in names)
        assertTrue("AGGRESSIVE" in names)
    }

    @Test fun `RunTrigger has 4 values`() = assertEquals(4, RunTrigger.values().size)

    @Test fun `RunTrigger has MANUAL ON_SAVE IDLE BEFORE_COMMIT`() {
        val names = RunTrigger.values().map { it.name }
        assertTrue("MANUAL" in names)
        assertTrue("ON_SAVE" in names)
        assertTrue("IDLE" in names)
        assertTrue("BEFORE_COMMIT" in names)
    }

    // ── maxFiles falls back to free when not pro ──────────────────────────

    @Test fun `non-pro BALANCED is capped at free budget`() {
        assertEquals(CodeHealthBudgets.FREE_MAX_FILES, CodeHealthBudgets.maxFiles(AnalysisMode.BALANCED, false))
    }

    @Test fun `non-pro AGGRESSIVE is capped at free budget`() {
        assertEquals(CodeHealthBudgets.FREE_MAX_FILES, CodeHealthBudgets.maxFiles(AnalysisMode.AGGRESSIVE, false))
    }
}
