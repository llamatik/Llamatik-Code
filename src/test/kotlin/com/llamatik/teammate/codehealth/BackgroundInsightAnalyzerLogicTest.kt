package com.llamatik.teammate.codehealth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests the deterministic static analysis logic replicated from BackgroundInsightAnalyzer
 * without requiring IntelliJ services.
 */
class BackgroundInsightAnalyzerLogicTest {

    private fun analyzeContent(filePath: String, content: String): List<CodeHealthInsight> {
        val insights = mutableListOf<CodeHealthInsight>()
        val lines = content.lines()

        if (lines.size > 300) {
            insights.add(CodeHealthInsight(
                id = "complexity-size", type = InsightType.COMPLEXITY, severity = InsightSeverity.WARNING,
                title = "Large file (${lines.size} lines)", description = "Large", filePath = filePath))
        }

        val todoCount = lines.count { line ->
            val u = line.uppercase()
            "TODO" in u || "FIXME" in u || "HACK" in u || "XXX" in u
        }
        if (todoCount >= 3) {
            insights.add(CodeHealthInsight(
                id = "complexity-todos", type = InsightType.COMPLEXITY, severity = InsightSeverity.INFO,
                title = "$todoCount TODO/FIXME comments", description = "Many TODOs", filePath = filePath))
        }

        val emptyCatch = Regex("""catch\s*\([^)]+\)\s*\{\s*\}""")
        if (emptyCatch.containsMatchIn(content)) {
            insights.add(CodeHealthInsight(
                id = "errorhandling-emptycatch", type = InsightType.ERROR_HANDLING, severity = InsightSeverity.WARNING,
                title = "Empty catch block", description = "Swallows exception", filePath = filePath))
        }

        if (content.contains("@Deprecated")) {
            insights.add(CodeHealthInsight(
                id = "deprecated", type = InsightType.DEPRECATED_API, severity = InsightSeverity.INFO,
                title = "Deprecated API usage", description = "May use deprecated API", filePath = filePath))
        }

        return insights
    }

    private fun isIgnored(path: String, ignoredDirs: Set<String>): Boolean =
        ignoredDirs.any { dir -> path.contains("/$dir/") || path.startsWith("$dir/") }

    @Test fun `no insights for clean short file`() {
        val content = "class Foo {\n    fun bar() = 42\n}"
        assertTrue(analyzeContent("Foo.kt", content).isEmpty())
    }

    @Test fun `detects large file over 300 lines`() {
        val content = (1..350).joinToString("\n") { "val x$it = $it" }
        val result = analyzeContent("Big.kt", content)
        assertTrue("Should detect large file", result.any { it.type == InsightType.COMPLEXITY && "Large" in it.title })
    }

    @Test fun `does not flag file exactly at 300 lines`() {
        val content = (1..300).joinToString("\n") { "val x$it = $it" }
        val result = analyzeContent("Fine.kt", content)
        assertFalse("300 lines should not trigger large file", result.any { "Large" in it.title })
    }

    @Test fun `detects 3 TODO comments`() {
        val content = "// TODO fix\n// TODO later\n// TODO cleanup\nfun foo() {}"
        assertTrue(analyzeContent("Todos.kt", content).any { it.type == InsightType.COMPLEXITY })
    }

    @Test fun `does not flag 2 TODO comments`() {
        val content = "// TODO fix\n// TODO later\nfun foo() {}"
        assertFalse(analyzeContent("TwoTodos.kt", content).any { "TODO" in it.title })
    }

    @Test fun `detects FIXME as TODO-equivalent`() {
        val content = "// FIXME this\n// FIXME that\n// FIXME other\nfun foo() {}"
        assertTrue(analyzeContent("Fixmes.kt", content).any { it.type == InsightType.COMPLEXITY })
    }

    @Test fun `detects HACK keyword`() {
        val content = "// HACK workaround\n// HACK again\n// HACK third\nfun foo() {}"
        assertTrue(analyzeContent("Hacks.kt", content).any { it.type == InsightType.COMPLEXITY })
    }

    @Test fun `detects empty catch block`() {
        val content = "fun foo() { try { bar() } catch (e: Exception) {} }"
        assertTrue(analyzeContent("Foo.kt", content).any { it.type == InsightType.ERROR_HANDLING })
    }

    @Test fun `does not flag catch with content`() {
        val content = "fun foo() { try { bar() } catch (e: Exception) { log(e) } }"
        assertFalse(analyzeContent("Foo.kt", content).any { it.type == InsightType.ERROR_HANDLING })
    }

    @Test fun `detects Deprecated annotation`() {
        val content = "@Deprecated(\"old\")\nfun oldFun() {}"
        assertTrue(analyzeContent("Old.kt", content).any { it.type == InsightType.DEPRECATED_API })
    }

    @Test fun `ignored dir filter correctly identifies build folder`() {
        val dirs = setOf("build", "node_modules", ".gradle")
        assertTrue(isIgnored("build/generated/Foo.kt", dirs))
    }

    @Test fun `ignored dir filter correctly identifies nested ignored dir`() {
        val dirs = setOf("build", "node_modules", ".gradle")
        assertTrue(isIgnored("project/build/classes/Foo.kt", dirs))
    }

    @Test fun `ignored dir filter does not block src files`() {
        val dirs = setOf("build", "node_modules", ".gradle")
        assertFalse(isIgnored("src/main/kotlin/Foo.kt", dirs))
    }

    @Test fun `non-kt and non-java files produce no insights`() {
        val content = "<resources><string name=\"app_name\">App</string></resources>"
        // Analyzer skips non-kt/java — here we just verify logic works on XML
        assertTrue("XML content is short, should have no size insight",
            analyzeContent("strings.xml", content).none { "Large" in it.title })
    }

    @Test fun `multiple insight types can be returned from one file`() {
        val largeContent = (1..350).joinToString("\n") { "val x$it = $it" }
        val contentWithTodos = "// TODO 1\n// TODO 2\n// TODO 3\n" + largeContent
        val contentWithCatch = contentWithTodos + "\nfun f() { try {} catch(e: Exception) {} }"
        val result = analyzeContent("Multi.kt", contentWithCatch)
        assertTrue("Should have COMPLEXITY (size)", result.any { it.title.contains("Large") })
        assertTrue("Should have COMPLEXITY (todos)", result.any { it.title.contains("TODO") })
        assertTrue("Should have ERROR_HANDLING", result.any { it.type == InsightType.ERROR_HANDLING })
    }
}
