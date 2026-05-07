package com.llamatik.teammate.testing

import org.junit.Assert.*
import org.junit.Test

class TestCoverageHeuristicServiceTest {

    // ── suggestTestPath ───────────────────────────────────────────────────────

    @Test
    fun `suggestTestPath replaces src main kotlin with src test kotlin`() {
        val result = TestCoverageHeuristicService.suggestTestPath("src/main/kotlin/com/example/Foo.kt")
        assertEquals("src/test/kotlin/com/example/FooTest.kt", result)
    }

    @Test
    fun `suggestTestPath replaces src main java with src test java`() {
        val result = TestCoverageHeuristicService.suggestTestPath("src/main/java/com/example/Bar.java")
        assertEquals("src/test/java/com/example/BarTest.java", result)
    }

    @Test
    fun `suggestTestPath replaces app src main kotlin`() {
        val result = TestCoverageHeuristicService.suggestTestPath("app/src/main/kotlin/com/example/Baz.kt")
        assertEquals("app/src/test/kotlin/com/example/BazTest.kt", result)
    }

    @Test
    fun `suggestTestPath replaces commonMain with commonTest`() {
        val result = TestCoverageHeuristicService.suggestTestPath("src/commonMain/kotlin/com/example/Shared.kt")
        assertEquals("src/commonTest/kotlin/com/example/SharedTest.kt", result)
    }

    // ── isTestPath ────────────────────────────────────────────────────────────

    @Test
    fun `isTestPath returns true for test directory paths`() {
        assertTrue(TestCoverageHeuristicService.isTestPath("src/test/kotlin/FooTest.kt"))
        assertTrue(TestCoverageHeuristicService.isTestPath("app/src/androidTest/java/Bar.java"))
        assertTrue(TestCoverageHeuristicService.isTestPath("src/commonTest/kotlin/Spec.kt"))
    }

    @Test
    fun `isTestPath returns false for main source paths`() {
        assertFalse(TestCoverageHeuristicService.isTestPath("src/main/kotlin/Foo.kt"))
        assertFalse(TestCoverageHeuristicService.isTestPath("app/src/main/java/Bar.java"))
    }

    // ── isNonTestableFile ─────────────────────────────────────────────────────

    @Test
    fun `isNonTestableFile returns true for infrastructure files`() {
        assertTrue(TestCoverageHeuristicService.isNonTestableFile("AppModule"))
        assertTrue(TestCoverageHeuristicService.isNonTestableFile("MyApplication"))
        assertTrue(TestCoverageHeuristicService.isNonTestableFile("main"))
        assertTrue(TestCoverageHeuristicService.isNonTestableFile("BuildConfig"))
        assertTrue(TestCoverageHeuristicService.isNonTestableFile("AppConstants"))
    }

    @Test
    fun `isNonTestableFile returns false for regular service and repository files`() {
        assertFalse(TestCoverageHeuristicService.isNonTestableFile("UserRepository"))
        assertFalse(TestCoverageHeuristicService.isNonTestableFile("AuthService"))
        assertFalse(TestCoverageHeuristicService.isNonTestableFile("LoginViewModel"))
        assertFalse(TestCoverageHeuristicService.isNonTestableFile("PaymentProcessor"))
    }

    // ── normalizeName ─────────────────────────────────────────────────────────

    @Test
    fun `normalizeName strips path and extension and lowercases`() {
        assertEquals("userrepository", TestCoverageHeuristicService.normalizeName("src/main/kotlin/UserRepository.kt"))
        assertEquals("loginviewmodel", TestCoverageHeuristicService.normalizeName("app/src/main/LoginViewModel.java"))
    }

    // ── MissingTestReport data class ──────────────────────────────────────────

    @Test
    fun `MissingTestReport has default disclaimer text`() {
        val report = TestCoverageHeuristicService.MissingTestReport(
            untestedFiles = listOf("Foo.kt"),
            partiallyTestedFiles = emptyList(),
            suggestedTestCases = emptyList(),
            detectedFramework = TestCoverageHeuristicService.TestFramework.JUNIT,
            confidence = TestCoverageHeuristicService.Confidence.HIGH,
            evidence = emptyList(),
        )
        assertTrue("Disclaimer should mention heuristic", report.disclaimer.contains("heuristic", ignoreCase = true))
    }

    @Test
    fun `Confidence enum contains expected values`() {
        val values = TestCoverageHeuristicService.Confidence.values().map { it.name }
        assertTrue(values.contains("HIGH"))
        assertTrue(values.contains("MEDIUM"))
        assertTrue(values.contains("LOW"))
    }
}
