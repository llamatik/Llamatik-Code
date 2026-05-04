package com.llamatik.teammate.build

import org.junit.Assert.*
import org.junit.Test

class BuildFailureServiceTest {

    // Mirrors the parse logic from BuildFailureService without requiring Project
    private fun parse(raw: String): List<BuildFailureService.BuildError> {
        val errors = mutableListOf<BuildFailureService.BuildError>()
        val ktRegex = Regex("""^e:\s*(.+?\.kt):\s*\((\d+),\s*\d+\):\s*(.+)$""", RegexOption.MULTILINE)
        ktRegex.findAll(raw).forEach { m ->
            errors += BuildFailureService.BuildError(BuildFailureService.FailureKind.KOTLIN_COMPILE,
                m.groupValues[1], m.groupValues[2].toIntOrNull() ?: 0, m.groupValues[3].trim(), m.value.trim())
        }
        val javaRegex = Regex("""^(.+?\.java):(\d+):\s*error:\s*(.+)$""", RegexOption.MULTILINE)
        javaRegex.findAll(raw).forEach { m ->
            errors += BuildFailureService.BuildError(BuildFailureService.FailureKind.JAVA_COMPILE,
                m.groupValues[1], m.groupValues[2].toIntOrNull() ?: 0, m.groupValues[3].trim(), m.value.trim())
        }
        if (raw.contains("Could not resolve") || raw.contains("Could not find")) {
            val depRegex = Regex("""Could not (?:resolve|find) ([^\n]+?)\.?\s*$""", RegexOption.MULTILINE)
            depRegex.findAll(raw).forEach { m ->
                errors += BuildFailureService.BuildError(BuildFailureService.FailureKind.DEPENDENCY_RESOLUTION,
                    "", 0, "Dependency not found: ${m.groupValues[1]}", m.value.trim())
            }
        }
        return errors.take(50)
    }

    @Test fun `parse kotlin error`() {
        val raw = "e: /home/user/project/src/main/kotlin/Foo.kt: (10, 5): Unresolved reference: bar"
        val errors = parse(raw)
        assertEquals(1, errors.size)
        assertEquals(BuildFailureService.FailureKind.KOTLIN_COMPILE, errors[0].kind)
        assertEquals(10, errors[0].line)
        assertTrue(errors[0].message.contains("Unresolved reference"))
    }

    @Test fun `parse java error`() {
        val raw = "/home/project/src/main/java/Main.java:42: error: cannot find symbol"
        val errors = parse(raw)
        assertEquals(1, errors.size)
        assertEquals(BuildFailureService.FailureKind.JAVA_COMPILE, errors[0].kind)
        assertEquals(42, errors[0].line)
    }

    @Test fun `parse dependency resolution failure`() {
        val raw = """
            Could not resolve com.example:library:1.0.0.
            Required by: project ':app'
        """.trimIndent()
        val errors = parse(raw)
        assertEquals(1, errors.size)
        assertEquals(BuildFailureService.FailureKind.DEPENDENCY_RESOLUTION, errors[0].kind)
        assertTrue(errors[0].message.contains("com.example:library:1.0.0"))
    }

    @Test fun `parse returns empty for clean output`() {
        val raw = "BUILD SUCCESSFUL in 3s\n> Task :compileKotlin UP-TO-DATE"
        assertTrue(parse(raw).isEmpty())
    }

    @Test fun `parse multiple kotlin errors`() {
        val raw = """
            e: /project/src/Foo.kt: (5, 3): Type mismatch
            e: /project/src/Bar.kt: (12, 8): Overload resolution ambiguity
        """.trimIndent()
        val errors = parse(raw)
        assertEquals(2, errors.size)
        assertEquals(BuildFailureService.FailureKind.KOTLIN_COMPILE, errors[0].kind)
        assertEquals(BuildFailureService.FailureKind.KOTLIN_COMPILE, errors[1].kind)
    }
}
