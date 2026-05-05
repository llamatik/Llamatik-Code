package com.llamatik.teammate.deps

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DependencyServiceTest {

    // Mirrors DependencyService parse logic without requiring Project

    private fun parseVersionCatalog(content: String): List<DependencyService.Dependency> {
        val deps = mutableListOf<DependencyService.Dependency>()
        var inLibraries = false
        content.lines().forEach { line ->
            val t = line.trim()
            when {
                t == "[versions]" -> inLibraries = false
                t == "[libraries]" -> inLibraries = true
                t.startsWith("[") -> inLibraries = false
                inLibraries && t.contains("=") -> {
                    val alias = t.substringBefore("=").trim()
                    val value = t.substringAfter("=").trim().trim('"')
                    val parts = value.split(":")
                    if (parts.size >= 2) deps += DependencyService.Dependency(
                        parts[0], parts.getOrElse(1) { "" }, parts.getOrElse(2) { "" }, alias = alias)
                }
            }
        }
        return deps
    }

    private fun parseGradleKts(content: String): List<DependencyService.Dependency> {
        val regex = Regex("""(?:implementation|api|testImplementation|kapt|ksp|classpath)\s*\(\s*"([^"]+)"\s*\)""")
        return regex.findAll(content).mapNotNull { m ->
            val parts = m.groupValues[1].split(":")
            if (parts.size == 3) DependencyService.Dependency(parts[0], parts[1], parts[2]) else null
        }.take(100).toList()
    }

    private fun parseNpm(content: String): List<DependencyService.Dependency> {
        val regex = Regex(""""([@\w/.\-]+)"\s*:\s*"([^"]+)"""")
        var inDeps = false
        val deps = mutableListOf<DependencyService.Dependency>()
        content.lines().forEach { line ->
            val t = line.trim()
            if (t.contains("\"dependencies\"") || t.contains("\"devDependencies\"")) inDeps = true
            else if (inDeps && t == "}") inDeps = false
            else if (inDeps) {
                val m = regex.find(t)
                if (m != null) deps += DependencyService.Dependency("", m.groupValues[1], m.groupValues[2])
            }
        }
        return deps.take(100)
    }

    @Test fun `parse version catalog libraries`() {
        val content = """
            [versions]
            kotlin = "2.0.0"
            [libraries]
            coroutines-core = "org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0"
            retrofit = "com.squareup.retrofit2:retrofit:2.9.0"
        """.trimIndent()
        val deps = parseVersionCatalog(content)
        assertEquals(2, deps.size)
        assertEquals("coroutines-core", deps[0].alias)
        assertEquals("org.jetbrains.kotlinx", deps[0].group)
        assertEquals("1.8.0", deps[0].version)
        assertEquals("retrofit", deps[1].alias)
    }

    @Test fun `parse version catalog ignores versions section`() {
        val content = """
            [versions]
            mylib = "1.0.0"
            [libraries]
            mylib = "com.example:mylib:1.0.0"
        """.trimIndent()
        val deps = parseVersionCatalog(content)
        assertEquals(1, deps.size)
        assertEquals("com.example", deps[0].group)
    }

    @Test fun `parse gradle kts dependencies`() {
        val content = """
            dependencies {
                implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.0")
                testImplementation("junit:junit:4.13.2")
                api("io.ktor:ktor-client-core:2.3.0")
            }
        """.trimIndent()
        val deps = parseGradleKts(content)
        assertEquals(3, deps.size)
        assertTrue(deps.any { it.artifact == "kotlin-stdlib" })
        assertTrue(deps.any { it.artifact == "junit" })
        assertTrue(deps.any { it.artifact == "ktor-client-core" })
    }

    @Test fun `parse npm package json`() {
        val content = """
            {
              "dependencies": {
                "react": "^18.0.0",
                "axios": "^1.4.0"
              },
              "devDependencies": {
                "typescript": "^5.0.0"
              }
            }
        """.trimIndent()
        val deps = parseNpm(content)
        assertTrue(deps.any { it.artifact == "react" && it.version == "^18.0.0" })
        assertTrue(deps.any { it.artifact == "axios" })
        assertTrue(deps.any { it.artifact == "typescript" })
    }

    @Test fun `dependency coordinates`() {
        val dep = DependencyService.Dependency("com.example", "mylib", "1.0.0")
        assertEquals("com.example:mylib:1.0.0", dep.coordinates)
    }
}
