package com.llamatik.teammate.external

import com.llamatik.teammate.external.citation.Citation
import com.llamatik.teammate.external.citation.SourceConfidence
import com.llamatik.teammate.external.citation.SourceType
import com.llamatik.teammate.external.research.ErrorResearchService
import com.llamatik.teammate.external.research.ResearchFinding
import com.llamatik.teammate.external.research.ResearchReport
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ErrorResearchExtendedTest {

    private fun makeService(): ErrorResearchService = ErrorResearchService()

    // ── detectTool edge cases ──────────────────────────────────────────────

    @Test
    fun `detects Xcode from xcodebuild reference`() {
        val svc = makeService()
        val ctx = svc.parseErrorContext("xcodebuild: error: Failed to build")
        assertTrue("Should detect Xcode from xcodebuild", ctx.tool == "Xcode")
    }

    @Test
    fun `detects Kotlin compiler from kotlinc`() {
        val svc = makeService()
        val ctx = svc.parseErrorContext("kotlinc: error: unresolved reference MyClass")
        assertTrue("Should detect Kotlin compiler from kotlinc", ctx.tool == "Kotlin compiler")
    }

    @Test
    fun `detects Java compiler from javac`() {
        val svc = makeService()
        val ctx = svc.parseErrorContext("javac: error: cannot find symbol")
        assertTrue("Should detect Java compiler from javac", ctx.tool == "Java compiler")
    }

    // ── detectFramework edge cases ─────────────────────────────────────────

    @Test
    fun `detects Jetpack Compose framework`() {
        val svc = makeService()
        val ctx = svc.parseErrorContext("Composable function call in non-compose UI context failed")
        // "compose" + "ui" triggers Jetpack Compose
        assertTrue("Should detect Jetpack Compose", ctx.framework == "Jetpack Compose")
    }

    @Test
    fun `detects Spring framework`() {
        val svc = makeService()
        val ctx = svc.parseErrorContext("org.springframework.beans.factory.BeanCreationException")
        assertTrue("Should detect Spring framework", ctx.framework == "Spring")
    }

    @Test
    fun `detects Ktor framework`() {
        val svc = makeService()
        val ctx = svc.parseErrorContext("io.ktor.client.plugins.ClientRequestException: 404")
        assertTrue("Should detect Ktor framework", ctx.framework == "Ktor")
    }

    // ── detectVersion edge cases ──────────────────────────────────────────

    @Test
    fun `extracts two-part version`() {
        val svc = makeService()
        val ctx = svc.parseErrorContext("Could not resolve: androidx.compose:compose-ui:1.6")
        assertFalse("Should extract version 1.6", ctx.version.isBlank())
        assertTrue("Should contain 1.6", ctx.version == "1.6")
    }

    @Test
    fun `extracts version after colon`() {
        val svc = makeService()
        val ctx = svc.parseErrorContext("com.example:lib:2.3.1 could not be resolved")
        assertTrue("Should extract version 2.3.1", ctx.version == "2.3.1")
    }

    // ── detectErrorCode edge cases ────────────────────────────────────────

    @Test
    fun `extracts AAPT error code`() {
        val svc = makeService()
        val ctx = svc.parseErrorContext("AAPT2: error: failed to parse resource")
        assertTrue("Should extract AAPT error code", ctx.errorCode.contains("AAPT"))
    }

    @Test
    fun `extracts Kotlin error message as error code`() {
        val svc = makeService()
        val ctx = svc.parseErrorContext("src/Foo.kt:10:5: error: Unresolved reference: Bar")
        assertTrue("Error code should contain the error message", ctx.errorCode.isNotBlank())
    }

    // ── extractFilePaths edge cases ────────────────────────────────────────

    @Test
    fun `extracts java file paths`() {
        val svc = makeService()
        val ctx = svc.parseErrorContext("src/main/java/com/example/Main.java:10: error:")
        assertTrue("Should extract .java files", ctx.relevantFiles.any { it.contains(".java") })
    }

    @Test
    fun `extracts xml file paths`() {
        val svc = makeService()
        val ctx = svc.parseErrorContext("res/layout/activity_main.xml: error processing")
        assertTrue("Should extract .xml files", ctx.relevantFiles.any { it.contains(".xml") })
    }

    @Test
    fun `extracts swift file paths`() {
        val svc = makeService()
        val ctx = svc.parseErrorContext("Sources/App/ViewController.swift:20: error: use of unresolved identifier")
        assertTrue("Should extract .swift files", ctx.relevantFiles.any { it.contains(".swift") })
    }

    @Test
    fun `extracts multiple distinct file paths`() {
        val svc = makeService()
        val error = "Foo.kt:1: error\nBar.kt:2: error\nBaz.gradle:3: error"
        val ctx = svc.parseErrorContext(error)
        assertTrue("Should extract multiple file paths", ctx.relevantFiles.size >= 2)
    }

    // ── buildFixPlan ──────────────────────────────────────────────────────

    private fun buildFixPlan(svc: ErrorResearchService, ctx: ErrorResearchService.ErrorContext, report: ResearchReport): String {
        return svc.buildFixPlan(ctx, report)
    }

    private fun emptyReport() = ResearchReport(
        answer = "No information found.",
        findings = emptyList(),
        sources = emptyList(),
        confidence = SourceConfidence.LOW,
    )

    @Test
    fun `buildFixPlan includes tool name in header`() {
        val svc = makeService()
        val ctx = svc.parseErrorContext("FAILURE: Build failed with an exception.\nTask :app:compileDebugKotlin FAILED")
        val plan = buildFixPlan(svc, ctx, emptyReport())
        assertTrue("Fix plan should mention the tool", plan.contains("Gradle"))
    }

    @Test
    fun `buildFixPlan includes error code when present`() {
        val svc = makeService()
        val ctx = svc.parseErrorContext("Task :app:buildRelease FAILED\nExecution failed")
        val plan = buildFixPlan(svc, ctx, emptyReport())
        assertTrue("Fix plan should include error code section", plan.contains("Error code:"))
    }

    @Test
    fun `buildFixPlan includes library name when present`() {
        val svc = makeService()
        val ctx = svc.parseErrorContext("Could not resolve: com.example:awesome-lib:1.2.3")
        val plan = buildFixPlan(svc, ctx, emptyReport())
        assertTrue("Fix plan should include library name", plan.contains("awesome-lib"))
    }

    @Test
    fun `buildFixPlan includes version when library and version present`() {
        val svc = makeService()
        val ctx = svc.parseErrorContext("Could not resolve: com.example:awesome-lib:1.2.3")
        val plan = buildFixPlan(svc, ctx, emptyReport())
        assertTrue("Fix plan should include version", plan.contains("1.2.3"))
    }

    @Test
    fun `buildFixPlan includes diagnosis from report answer`() {
        val svc = makeService()
        val ctx = ErrorResearchService.ErrorContext(rawError = "build error")
        val report = ResearchReport(
            answer = "The dependency version is incompatible",
            findings = emptyList(),
            sources = emptyList(),
            confidence = SourceConfidence.MEDIUM,
        )
        val plan = buildFixPlan(svc, ctx, report)
        assertTrue("Fix plan should include the diagnosis text", plan.contains("incompatible"))
    }

    @Test
    fun `buildFixPlan includes steps from findings`() {
        val svc = makeService()
        val ctx = ErrorResearchService.ErrorContext(rawError = "error")
        val report = ResearchReport(
            answer = "Analysis",
            findings = listOf(
                ResearchFinding(
                    claim = "Update the dependency to version 2.0.0",
                    evidenceUrls = listOf("https://example.com"),
                    confidence = SourceConfidence.MEDIUM,
                    appliesToProject = true,
                    suggestedAction = "Change version in build.gradle",
                )
            ),
            sources = emptyList(),
            confidence = SourceConfidence.MEDIUM,
        )
        val plan = buildFixPlan(svc, ctx, report)
        assertTrue("Fix plan should include steps section", plan.contains("Steps:"))
        assertTrue("Fix plan should include finding claim", plan.contains("Update the dependency"))
        assertTrue("Fix plan should include suggested action", plan.contains("Change version"))
    }

    @Test
    fun `buildFixPlan includes affected files when present`() {
        val svc = makeService()
        val ctx = svc.parseErrorContext("Foo.kt:1: error: unresolved reference Bar")
        val plan = buildFixPlan(svc, ctx, emptyReport())
        assertTrue("Fix plan should include affected files", plan.contains("Affected files:"))
    }

    @Test
    fun `buildFixPlan includes sources when report has sources`() {
        val svc = makeService()
        val ctx = ErrorResearchService.ErrorContext(rawError = "error")
        val report = ResearchReport(
            answer = "Analysis",
            findings = emptyList(),
            sources = listOf(
                Citation(
                    title = "Stack Overflow answer",
                    url = "https://stackoverflow.com/questions/1234",
                    sourceType = SourceType.STACKOVERFLOW,
                )
            ),
            confidence = SourceConfidence.MEDIUM,
        )
        val plan = buildFixPlan(svc, ctx, report)
        assertTrue("Fix plan should include sources", plan.contains("Sources:"))
        assertTrue("Fix plan should include source URL", plan.contains("stackoverflow.com"))
    }

    @Test
    fun `buildFixPlan does not crash on empty context`() {
        val svc = makeService()
        val ctx = ErrorResearchService.ErrorContext(rawError = "")
        val plan = buildFixPlan(svc, ctx, emptyReport())
        assertFalse("Fix plan should not be empty", plan.isBlank())
    }

    @Test
    fun `buildFixPlan uses build when tool is blank`() {
        val svc = makeService()
        val ctx = ErrorResearchService.ErrorContext(rawError = "some unknown error")
        val plan = buildFixPlan(svc, ctx, emptyReport())
        assertTrue("Fix plan header should say 'build' when tool is blank", plan.contains("build"))
    }
}
