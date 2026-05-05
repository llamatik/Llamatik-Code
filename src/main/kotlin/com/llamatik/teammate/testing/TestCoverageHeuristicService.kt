package com.llamatik.teammate.testing

import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.context.ProjectContextService
import com.llamatik.teammate.psi.PsiCodeInsightService
import com.llamatik.teammate.util.PluginLogger

@Service(Service.Level.PROJECT)
class TestCoverageHeuristicService(private val project: Project) {

    private val log = PluginLogger.logger<TestCoverageHeuristicService>()
    private val contextService = ProjectContextService(project)

    enum class TestFramework { JUNIT, MOCKK, MOCKITO, KOTEST, UNKNOWN }

    data class MissingTestReport(
        val untestedFiles: List<String>,
        val partiallyTestedFiles: List<String>,
        val suggestedTestCases: List<SuggestedTest>,
        val detectedFramework: TestFramework,
        val confidence: Confidence,
        val evidence: List<String>,
        val disclaimer: String = "Results are heuristic estimates. No actual coverage data was used.",
    )

    data class SuggestedTest(
        val sourceFile: String,
        val className: String,
        val suggestedTestFile: String,
        val reason: String,
    )

    enum class Confidence { HIGH, MEDIUM, LOW }

    fun analyze(): MissingTestReport {
        val sourceFiles = listSourceFiles()
        val testFiles = listTestFiles()
        val framework = detectFramework(testFiles)

        log.info("TestHeuristic: ${sourceFiles.size} source files, ${testFiles.size} test files, framework=$framework")

        val testFileNames = testFiles.map { normalizeName(it) }.toSet()
        val untested = mutableListOf<String>()
        val partiallyTested = mutableListOf<String>()
        val suggested = mutableListOf<SuggestedTest>()

        for (src in sourceFiles) {
            val baseName = src.substringAfterLast('/').removeSuffix(".kt").removeSuffix(".java")
            if (isNonTestableFile(baseName)) continue

            val hasTest = testFileNames.any { testName ->
                testName.contains(baseName.lowercase()) ||
                    testName.contains("${baseName.lowercase()}test") ||
                    testName.contains("${baseName.lowercase()}spec")
            }

            if (!hasTest) {
                untested += src
                val suggestedPath = suggestTestPath(src)
                suggested += SuggestedTest(
                    sourceFile = src,
                    className = baseName,
                    suggestedTestFile = suggestedPath,
                    reason = "No matching test file found for $baseName",
                )
            } else {
                // Check if PSI shows functions that lack test counterparts
                val partiallyUncovered = checkPartialCoverage(src, testFiles)
                if (partiallyUncovered) partiallyTested += src
            }
        }

        val confidence = when {
            sourceFiles.isEmpty() -> Confidence.LOW
            testFiles.isEmpty()   -> Confidence.MEDIUM
            else                  -> Confidence.HIGH
        }

        val evidence = listOf(
            "Source files scanned: ${sourceFiles.size}",
            "Test files found: ${testFiles.size}",
            "Test framework: $framework",
            "Untested source files: ${untested.size}",
        )

        return MissingTestReport(
            untestedFiles = untested,
            partiallyTestedFiles = partiallyTested,
            suggestedTestCases = suggested.take(20),
            detectedFramework = framework,
            confidence = confidence,
            evidence = evidence,
        )
    }

    fun listSourceFiles(): List<String> {
        val patterns = listOf("src/main/kotlin", "src/main/java", "app/src/main/kotlin",
            "app/src/main/java", "src/commonMain/kotlin", "src")
        for (p in patterns) {
            val files = runCatching { contextService.listFiles(p) }.getOrElse { emptyList() }
                .filter { it.endsWith(".kt") || it.endsWith(".java") }
            if (files.isNotEmpty()) return files
        }
        return runCatching { contextService.listFiles(null) }.getOrElse { emptyList() }
            .filter { it.endsWith(".kt") || it.endsWith(".java") }
            .filter { !isTestPath(it) }
    }

    fun listTestFiles(): List<String> {
        val patterns = listOf("src/test/kotlin", "src/test/java", "app/src/test/kotlin",
            "app/src/test/java", "src/commonTest/kotlin", "src/androidTest")
        for (p in patterns) {
            val files = runCatching { contextService.listFiles(p) }.getOrElse { emptyList() }
                .filter { it.endsWith(".kt") || it.endsWith(".java") }
            if (files.isNotEmpty()) return files
        }
        return runCatching { contextService.listFiles(null) }.getOrElse { emptyList() }
            .filter { it.endsWith(".kt") || it.endsWith(".java") }
            .filter { isTestPath(it) }
    }

    fun detectFramework(testFiles: List<String>): TestFramework {
        val buildContent = runCatching { contextService.readFile("build.gradle.kts") }.getOrElse { "" } +
            runCatching { contextService.readFile("build.gradle") }.getOrElse { "" }
        val testContent = testFiles.take(5).joinToString(" ") { path ->
            runCatching { contextService.readFile(path) }.getOrElse { "" }.take(2000)
        }
        val combined = (buildContent + testContent).lowercase()
        return when {
            "kotest" in combined                        -> TestFramework.KOTEST
            "mockk" in combined                        -> TestFramework.MOCKK
            "mockito" in combined                      -> TestFramework.MOCKITO
            "junit" in combined || "@test" in combined -> TestFramework.JUNIT
            else                                       -> TestFramework.UNKNOWN
        }
    }

    private fun checkPartialCoverage(sourceFile: String, testFiles: List<String>): Boolean {
        return try {
            ReadAction.compute<Boolean, Exception> {
                val svc = project.service<PsiCodeInsightService>()
                val structure = svc.fileStructure(sourceFile)
                val publicFunctions = structure.declarations.filter {
                    it.kind.contains("fun") && !it.name.startsWith("_")
                }
                publicFunctions.size > 3
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun suggestTestPath(sourcePath: String): String {
        val testPath = sourcePath
            .replace("src/main/kotlin", "src/test/kotlin")
            .replace("src/main/java", "src/test/java")
            .replace("app/src/main/kotlin", "app/src/test/kotlin")
            .replace("commonMain", "commonTest")
        val base = testPath.substringBeforeLast('.')
        val ext = testPath.substringAfterLast('.')
        return "${base}Test.$ext"
    }

    private fun normalizeName(path: String) =
        path.substringAfterLast('/').removeSuffix(".kt").removeSuffix(".java").lowercase()

    private fun isTestPath(path: String): Boolean {
        val p = path.lowercase()
        return "test" in p || "spec" in p || "androidtest" in p
    }

    private fun isNonTestableFile(name: String): Boolean {
        val n = name.lowercase()
        return n.endsWith("module") || n.endsWith("application") ||
            n == "main" || n.endsWith("buildconfig") || n.endsWith("di") ||
            n.endsWith("config") || n.endsWith("constants")
    }
}
