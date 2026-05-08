package com.llamatik.teammate.codehealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeHealthModelsTest {

    // ── CodeHealthInsight defaults ────────────────────────────────────────

    @Test fun `CodeHealthInsight default line is 0`() {
        val i = CodeHealthInsight("id", InsightType.COMPLEXITY, InsightSeverity.INFO, "T", "D", "Foo.kt")
        assertEquals(0, i.line)
    }

    @Test fun `CodeHealthInsight default confidence is 0_8`() {
        val i = CodeHealthInsight("id", InsightType.COMPLEXITY, InsightSeverity.INFO, "T", "D", "Foo.kt")
        assertEquals(0.8, i.confidence, 0.0001)
    }

    @Test fun `CodeHealthInsight default evidence is empty`() {
        val i = CodeHealthInsight("id", InsightType.COMPLEXITY, InsightSeverity.INFO, "T", "D", "Foo.kt")
        assertTrue(i.evidence.isEmpty())
    }

    @Test fun `CodeHealthInsight default suggestedAction is blank`() {
        val i = CodeHealthInsight("id", InsightType.COMPLEXITY, InsightSeverity.INFO, "T", "D", "Foo.kt")
        assertTrue(i.suggestedAction.isEmpty())
    }

    @Test fun `CodeHealthInsight default canAutoFix is false`() {
        val i = CodeHealthInsight("id", InsightType.COMPLEXITY, InsightSeverity.INFO, "T", "D", "Foo.kt")
        assertFalse(i.canAutoFix)
    }

    @Test fun `CodeHealthInsight default source is static`() {
        val i = CodeHealthInsight("id", InsightType.COMPLEXITY, InsightSeverity.INFO, "T", "D", "Foo.kt")
        assertEquals("static", i.source)
    }

    @Test fun `CodeHealthInsight createdAt is positive`() {
        val before = System.currentTimeMillis()
        val i = CodeHealthInsight("id", InsightType.COMPLEXITY, InsightSeverity.INFO, "T", "D", "Foo.kt")
        assertTrue(i.createdAt >= before)
    }

    @Test fun `CodeHealthInsight explicit values are preserved`() {
        val i = CodeHealthInsight(
            id = "abc", type = InsightType.ERROR_HANDLING, severity = InsightSeverity.HIGH,
            title = "Title", description = "Desc", filePath = "Bar.kt",
            line = 42, confidence = 0.95, evidence = listOf("ev1"),
            suggestedAction = "Fix it", canAutoFix = true, source = "model"
        )
        assertEquals("abc", i.id)
        assertEquals(InsightType.ERROR_HANDLING, i.type)
        assertEquals(InsightSeverity.HIGH, i.severity)
        assertEquals("Bar.kt", i.filePath)
        assertEquals(42, i.line)
        assertEquals(0.95, i.confidence, 0.0001)
        assertEquals(listOf("ev1"), i.evidence)
        assertEquals("Fix it", i.suggestedAction)
        assertTrue(i.canAutoFix)
        assertEquals("model", i.source)
    }

    // ── InsightType enum ──────────────────────────────────────────────────

    @Test fun `InsightType has 10 values`() = assertEquals(10, InsightType.values().size)

    @Test fun `InsightType contains all expected entries`() {
        val names = InsightType.values().map { it.name }.toSet()
        assertTrue("COMPLEXITY" in names)
        assertTrue("MISSING_TESTS" in names)
        assertTrue("DUPLICATION" in names)
        assertTrue("DEPRECATED_API" in names)
        assertTrue("ARCHITECTURE_DRIFT" in names)
        assertTrue("ERROR_HANDLING" in names)
        assertTrue("REGRESSION_RISK" in names)
        assertTrue("REFACTOR_OPPORTUNITY" in names)
        assertTrue("DEPENDENCY_RISK" in names)
        assertTrue("COMMIT_RISK" in names)
    }

    // ── InsightSeverity enum ──────────────────────────────────────────────

    @Test fun `InsightSeverity has 3 values`() = assertEquals(3, InsightSeverity.values().size)

    @Test fun `InsightSeverity ordinal INFO is lowest`() {
        assertTrue(InsightSeverity.INFO.ordinal < InsightSeverity.WARNING.ordinal)
        assertTrue(InsightSeverity.WARNING.ordinal < InsightSeverity.HIGH.ordinal)
    }

    // ── WatchedArea defaults ──────────────────────────────────────────────

    @Test fun `WatchedArea default enabled is true`() {
        val a = WatchedArea("id", "path/to/pkg", WatchedAreaType.PACKAGE)
        assertTrue(a.enabled)
    }

    @Test fun `WatchedArea createdAt is positive`() {
        val before = System.currentTimeMillis()
        val a = WatchedArea("id", "path", WatchedAreaType.FILE)
        assertTrue(a.createdAt >= before)
    }

    @Test fun `WatchedArea explicit disabled`() {
        val a = WatchedArea("id", "path", WatchedAreaType.MODULE, enabled = false)
        assertFalse(a.enabled)
    }

    // ── WatchedAreaType enum ──────────────────────────────────────────────

    @Test fun `WatchedAreaType has 4 values`() = assertEquals(4, WatchedAreaType.values().size)

    @Test fun `WatchedAreaType contains FILE DIRECTORY PACKAGE MODULE`() {
        val names = WatchedAreaType.values().map { it.name }.toSet()
        assertTrue("FILE" in names)
        assertTrue("DIRECTORY" in names)
        assertTrue("PACKAGE" in names)
        assertTrue("MODULE" in names)
    }

    // ── SessionContext defaults ───────────────────────────────────────────

    @Test fun `SessionContext defaults are empty`() {
        val ctx = SessionContext()
        assertTrue(ctx.currentBranch.isEmpty())
        assertTrue(ctx.recentFiles.isEmpty())
        assertTrue(ctx.recentPrompts.isEmpty())
        assertTrue(ctx.watchedAreas.isEmpty())
        assertTrue(ctx.recentBuildFailure.isEmpty())
    }

    @Test fun `SessionContext updatedAt is positive`() {
        val before = System.currentTimeMillis()
        val ctx = SessionContext()
        assertTrue(ctx.updatedAt >= before)
    }

    @Test fun `SessionContext explicit values are preserved`() {
        val ctx = SessionContext(
            currentBranch = "feature/x",
            recentFiles = listOf("Foo.kt", "Bar.kt"),
            recentPrompts = listOf("explain this"),
            watchedAreas = listOf("area-1"),
            recentBuildFailure = "Build failed: missing symbol"
        )
        assertEquals("feature/x", ctx.currentBranch)
        assertEquals(listOf("Foo.kt", "Bar.kt"), ctx.recentFiles)
        assertEquals(listOf("explain this"), ctx.recentPrompts)
        assertEquals(listOf("area-1"), ctx.watchedAreas)
        assertEquals("Build failed: missing symbol", ctx.recentBuildFailure)
    }
}
