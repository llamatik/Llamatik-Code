package com.llamatik.teammate.codehealth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegressionRiskAnalyzerTest {

    // Inline the pure diff analysis logic — mirrors RegressionRiskAnalyzer without IntelliJ services
    private fun analyzeGitDiff(diff: String, changedFiles: List<String>): List<CodeHealthInsight> {
        val risks = mutableListOf<CodeHealthInsight>()
        val key = diff.hashCode()

        val removedPublicRegex = Regex("""^-\s*(public|fun |class |object |interface )[^\n]+""", RegexOption.MULTILINE)
        val removedPublic = removedPublicRegex.findAll(diff).count()
        if (removedPublic > 0) risks.add(CodeHealthInsight(
            id = "regression-$key-removed-public", type = InsightType.REGRESSION_RISK, severity = InsightSeverity.HIGH,
            title = "Removed public declarations ($removedPublic)", description = "May break callers",
            filePath = changedFiles.firstOrNull() ?: "", source = "git-diff"))

        val removedTestRegex = Regex("""^-\s*@Test""", RegexOption.MULTILINE)
        val removedTests = removedTestRegex.findAll(diff).count()
        if (removedTests > 0) risks.add(CodeHealthInsight(
            id = "regression-$key-removed-tests", type = InsightType.REGRESSION_RISK, severity = InsightSeverity.HIGH,
            title = "Removed tests ($removedTests)", description = "Tests may have been deleted",
            filePath = changedFiles.firstOrNull() ?: "", source = "git-diff"))

        val gradleFiles = changedFiles.filter { it.endsWith(".gradle") || it.endsWith(".gradle.kts") }
        if (gradleFiles.isNotEmpty()) {
            val depChangeRegex = Regex("""^[-+]\s*(implementation|testImplementation|api)\s*\(""", RegexOption.MULTILINE)
            if (depChangeRegex.containsMatchIn(diff)) {
                risks.add(CodeHealthInsight(
                    id = "regression-$key-deps", type = InsightType.DEPENDENCY_RISK, severity = InsightSeverity.WARNING,
                    title = "Dependency changes detected", description = "Gradle deps changed",
                    filePath = gradleFiles.first(), source = "git-diff"))
            }
        }

        val removedTryRegex = Regex("""^-\s*(try\s*\{|catch\s*\()""", RegexOption.MULTILINE)
        if (removedTryRegex.containsMatchIn(diff)) risks.add(CodeHealthInsight(
            id = "regression-$key-error-handling", type = InsightType.ERROR_HANDLING, severity = InsightSeverity.WARNING,
            title = "Removed error handling", description = "Try/catch removed",
            filePath = changedFiles.firstOrNull() ?: "", source = "git-diff"))

        changedFiles.filter { it.endsWith("AndroidManifest.xml") }.firstOrNull()?.let { manifest ->
            risks.add(CodeHealthInsight(
                id = "regression-$key-manifest", type = InsightType.REGRESSION_RISK, severity = InsightSeverity.WARNING,
                title = "Android manifest changed", description = "Manifest modified",
                filePath = manifest, source = "git-diff"))
        }

        return risks
    }

    @Test fun `empty diff produces no risks`() {
        assertTrue(analyzeGitDiff("", emptyList()).isEmpty())
    }

    @Test fun `diff with only additions produces no removed-public risk`() {
        val diff = "+fun newPublicMethod() { }"
        assertFalse(analyzeGitDiff(diff, listOf("Foo.kt")).any { it.type == InsightType.REGRESSION_RISK && "Removed public" in it.title })
    }

    @Test fun `detects removed fun`() {
        val diff = "-fun publicMethod() { }"
        val risks = analyzeGitDiff(diff, listOf("Foo.kt"))
        assertTrue(risks.any { it.type == InsightType.REGRESSION_RISK && "Removed public" in it.title })
    }

    @Test fun `detects removed class`() {
        val diff = "-class MyService {"
        val risks = analyzeGitDiff(diff, listOf("MyService.kt"))
        assertTrue(risks.any { it.type == InsightType.REGRESSION_RISK })
    }

    @Test fun `detects removed interface`() {
        val diff = "-interface Callback {"
        val risks = analyzeGitDiff(diff, listOf("Callback.kt"))
        assertTrue(risks.any { InsightType.REGRESSION_RISK == it.type })
    }

    @Test fun `detects removed @Test annotation`() {
        val diff = "-    @Test\n-    fun testSomething() {}"
        val risks = analyzeGitDiff(diff, listOf("FooTest.kt"))
        assertTrue(risks.any { "Removed tests" in it.title })
        assertEquals(InsightSeverity.HIGH, risks.first { "Removed tests" in it.title }.severity)
    }

    @Test fun `detects multiple removed test annotations`() {
        val diff = "-    @Test\n-    fun test1() {}\n-    @Test\n-    fun test2() {}"
        val risks = analyzeGitDiff(diff, listOf("FooTest.kt"))
        assertTrue(risks.any { "2" in it.title })
    }

    @Test fun `detects dependency change in gradle kts file`() {
        val diff = "-implementation(\"com.example:lib:1.0\")\n+implementation(\"com.example:lib:2.0\")"
        val risks = analyzeGitDiff(diff, listOf("app/build.gradle.kts"))
        assertTrue(risks.any { it.type == InsightType.DEPENDENCY_RISK })
    }

    @Test fun `detects dependency change in gradle file`() {
        val diff = "-implementation(\"com.example:lib:1.0\")"
        val risks = analyzeGitDiff(diff, listOf("app/build.gradle"))
        assertTrue(risks.any { it.type == InsightType.DEPENDENCY_RISK })
    }

    @Test fun `does not flag dep change without gradle file in changed files`() {
        val diff = "-implementation(\"com.example:lib:1.0\")"
        val risks = analyzeGitDiff(diff, listOf("Foo.kt"))
        assertFalse(risks.any { it.type == InsightType.DEPENDENCY_RISK })
    }

    @Test fun `detects removed try block`() {
        val diff = "-        try {"
        val risks = analyzeGitDiff(diff, listOf("DataRepo.kt"))
        assertTrue(risks.any { it.type == InsightType.ERROR_HANDLING })
    }

    @Test fun `detects removed catch block`() {
        val diff = "-        catch (e: Exception) {"
        val risks = analyzeGitDiff(diff, listOf("DataRepo.kt"))
        assertTrue(risks.any { it.type == InsightType.ERROR_HANDLING })
    }

    @Test fun `detects manifest change`() {
        val risks = analyzeGitDiff("", listOf("app/src/main/AndroidManifest.xml"))
        assertTrue(risks.any { "manifest" in it.title.lowercase() })
        assertEquals(InsightSeverity.WARNING, risks.first { "manifest" in it.title.lowercase() }.severity)
    }

    @Test fun `manifest risk uses manifest file path`() {
        val risks = analyzeGitDiff("", listOf("app/src/main/AndroidManifest.xml"))
        assertEquals("app/src/main/AndroidManifest.xml", risks.first { "manifest" in it.title.lowercase() }.filePath)
    }

    @Test fun `all risks have non-blank ids`() {
        val diff = "-fun foo() {}\n-    @Test\n-    fun t() {}"
        val risks = analyzeGitDiff(diff, listOf("Foo.kt"))
        assertTrue(risks.all { it.id.isNotBlank() })
    }

    @Test fun `source is git-diff for all risks`() {
        val diff = "-fun foo() {}"
        val risks = analyzeGitDiff(diff, listOf("Foo.kt"))
        assertTrue(risks.all { it.source == "git-diff" })
    }
}
