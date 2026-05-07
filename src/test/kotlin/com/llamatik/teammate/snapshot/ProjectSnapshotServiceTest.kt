package com.llamatik.teammate.snapshot

import org.junit.Assert.*
import org.junit.Test

class ProjectSnapshotServiceTest {

    private fun snapshot(
        name: String = "MyApp",
        techStack: List<String> = listOf("Kotlin", "Compose"),
        modules: List<String> = emptyList(),
        architecture: String = "MVVM",
        entryPoints: List<String> = emptyList(),
        testSetup: String = "JUnit",
        buildTools: List<String> = listOf("Gradle"),
        ui: String = "Compose",
        di: String = "Hilt",
        networking: String = "Ktor",
        storage: String = "Room",
        risks: List<String> = emptyList(),
        improvements: List<String> = emptyList(),
        inspectedFiles: List<String> = listOf("build.gradle.kts"),
        confidence: String = "HIGH",
    ) = ProjectSnapshotService.ProjectSnapshot(
        projectName = name,
        techStack = techStack,
        modules = modules,
        architectureStyle = architecture,
        mainEntryPoints = entryPoints,
        testSetup = testSetup,
        buildTools = buildTools,
        uiFramework = ui,
        dependencyInjection = di,
        networkingLayer = networking,
        storageLayer = storage,
        risks = risks,
        recommendedImprovements = improvements,
        inspectedFiles = inspectedFiles,
        confidence = confidence,
    )

    @Test
    fun `toMarkdown contains project name`() {
        val md = snapshot(name = "CoolProject").toMarkdown()
        assertTrue(md.contains("CoolProject"))
    }

    @Test
    fun `toMarkdown contains tech stack`() {
        val md = snapshot(techStack = listOf("Kotlin", "Ktor")).toMarkdown()
        assertTrue(md.contains("Kotlin"))
        assertTrue(md.contains("Ktor"))
    }

    @Test
    fun `toMarkdown contains architecture style`() {
        val md = snapshot(architecture = "Clean Architecture").toMarkdown()
        assertTrue(md.contains("Clean Architecture"))
    }

    @Test
    fun `toMarkdown shows single-module when modules list is empty`() {
        val md = snapshot(modules = emptyList()).toMarkdown()
        assertTrue(md.contains("single-module"))
    }

    @Test
    fun `toMarkdown lists module names when present`() {
        val md = snapshot(modules = listOf("core", "feature-auth")).toMarkdown()
        assertTrue(md.contains("core"))
        assertTrue(md.contains("feature-auth"))
    }

    @Test
    fun `toMarkdown includes risks when non-empty`() {
        val md = snapshot(risks = listOf("No test coverage", "Large god class")).toMarkdown()
        assertTrue(md.contains("No test coverage"))
        assertTrue(md.contains("Large god class"))
    }

    @Test
    fun `toMarkdown does not include risks section when empty`() {
        val md = snapshot(risks = emptyList()).toMarkdown()
        assertFalse(md.contains("Risks"))
    }

    @Test
    fun `toMarkdown includes improvements when non-empty`() {
        val md = snapshot(improvements = listOf("Add DI", "Extract interfaces")).toMarkdown()
        assertTrue(md.contains("Add DI"))
        assertTrue(md.contains("Extract interfaces"))
    }

    @Test
    fun `toMarkdown includes inspected file count`() {
        val md = snapshot(inspectedFiles = listOf("a.kt", "b.kt", "c.kt")).toMarkdown()
        assertTrue(md.contains("3"))
    }

    @Test
    fun `toMarkdown includes confidence`() {
        val md = snapshot(confidence = "MEDIUM").toMarkdown()
        assertTrue(md.contains("MEDIUM"))
    }

    @Test
    fun `toMarkdown includes entry points when present`() {
        val md = snapshot(entryPoints = listOf("MainActivity", "App")).toMarkdown()
        assertTrue(md.contains("MainActivity"))
        assertTrue(md.contains("App"))
    }

    @Test
    fun `formattedTimestamp returns non-blank string`() {
        val ts = snapshot().formattedTimestamp()
        assertTrue("Timestamp should not be blank", ts.isNotBlank())
        assertTrue("Timestamp should contain year", ts.contains("202"))
    }
}
