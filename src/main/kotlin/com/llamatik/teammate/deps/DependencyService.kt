package com.llamatik.teammate.deps

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.util.PluginLogger
import java.io.File

@Service(Service.Level.PROJECT)
class DependencyService(private val project: Project) {

    private val log = PluginLogger.logger<DependencyService>()

    enum class DepSystem { GRADLE_VERSION_CATALOG, GRADLE_KTS, GRADLE_GROOVY, MAVEN, NPM }

    data class Dependency(
        val group: String,
        val artifact: String,
        val version: String,
        val scope: String = "",
        val alias: String = "",
    ) {
        val coordinates: String get() = if (group.isNotBlank()) "$group:$artifact:$version" else "$artifact:$version"
    }

    data class DetectedSystem(
        val type: DepSystem,
        val primaryFile: String,
    )

    fun detectSystem(): DetectedSystem? {
        val base = project.basePath ?: return null
        return when {
            File(base, "gradle/libs.versions.toml").exists() ->
                DetectedSystem(DepSystem.GRADLE_VERSION_CATALOG, "gradle/libs.versions.toml")
            File(base, "build.gradle.kts").exists() ->
                DetectedSystem(DepSystem.GRADLE_KTS, "build.gradle.kts")
            File(base, "build.gradle").exists() ->
                DetectedSystem(DepSystem.GRADLE_GROOVY, "build.gradle")
            File(base, "pom.xml").exists() ->
                DetectedSystem(DepSystem.MAVEN, "pom.xml")
            File(base, "package.json").exists() ->
                DetectedSystem(DepSystem.NPM, "package.json")
            else -> null
        }
    }

    fun listDependencies(): List<Dependency> {
        val system = detectSystem() ?: return emptyList()
        val base = project.basePath ?: return emptyList()
        return when (system.type) {
            DepSystem.GRADLE_VERSION_CATALOG -> parseVersionCatalog(File(base, system.primaryFile))
            DepSystem.GRADLE_KTS -> parseGradleKts(File(base, system.primaryFile))
            DepSystem.GRADLE_GROOVY -> parseGradleGroovy(File(base, system.primaryFile))
            DepSystem.MAVEN -> parseMaven(File(base, system.primaryFile))
            DepSystem.NPM -> parseNpm(File(base, system.primaryFile))
        }
    }

    fun formatForContext(): String {
        val system = detectSystem()
        val deps = listDependencies()
        if (deps.isEmpty()) return "No dependency information available"
        return buildString {
            appendLine("Dependency system: ${system?.type}")
            appendLine("File: ${system?.primaryFile}")
            appendLine()
            deps.take(60).forEach { d ->
                if (d.alias.isNotBlank()) appendLine("  ${d.alias} = ${d.version}  (${d.coordinates})")
                else appendLine("  ${d.coordinates}")
            }
            if (deps.size > 60) appendLine("  … and ${deps.size - 60} more")
        }.trim()
    }

    private fun parseVersionCatalog(file: File): List<Dependency> {
        if (!file.exists()) return emptyList()
        val deps = mutableListOf<Dependency>()
        var inVersions = false; var inLibraries = false
        file.readLines().forEach { line ->
            val t = line.trim()
            when {
                t == "[versions]" -> { inVersions = true; inLibraries = false }
                t == "[libraries]" -> { inLibraries = true; inVersions = false }
                t.startsWith("[") -> { inVersions = false; inLibraries = false }
                inLibraries && t.contains("=") -> {
                    val alias = t.substringBefore("=").trim()
                    val value = t.substringAfter("=").trim().trim('"')
                    // module = "group:artifact:version"  or  module = "group:artifact", version.ref = "..."
                    val parts = value.split(":")
                    if (parts.size >= 2) {
                        deps += Dependency(parts[0], parts.getOrElse(1) { "" },
                            parts.getOrElse(2) { "" }, alias = alias)
                    }
                }
            }
        }
        return deps
    }

    private fun parseGradleKts(file: File): List<Dependency> {
        if (!file.exists()) return emptyList()
        // implementation("group:artifact:version") or implementation(libs.something)
        val regex = Regex("""(?:implementation|api|testImplementation|kapt|ksp|classpath)\s*\(\s*"([^"]+)"\s*\)""")
        return file.readText().let { content ->
            regex.findAll(content).mapNotNull { m ->
                val parts = m.groupValues[1].split(":")
                if (parts.size == 3) Dependency(parts[0], parts[1], parts[2]) else null
            }.take(100).toList()
        }
    }

    private fun parseGradleGroovy(file: File): List<Dependency> {
        if (!file.exists()) return emptyList()
        val regex = Regex("""(?:implementation|api|testImplementation|classpath)\s+['"]([\w.\-]+:[\w.\-]+:[\w.\-]+)['"]""")
        return file.readText().let { content ->
            regex.findAll(content).mapNotNull { m ->
                val parts = m.groupValues[1].split(":")
                if (parts.size == 3) Dependency(parts[0], parts[1], parts[2]) else null
            }.take(100).toList()
        }
    }

    private fun parseMaven(file: File): List<Dependency> {
        if (!file.exists()) return emptyList()
        val depRegex = Regex("""<dependency>\s*<groupId>([^<]+)</groupId>\s*<artifactId>([^<]+)</artifactId>\s*(?:<version>([^<]+)</version>)?""",
            RegexOption.DOT_MATCHES_ALL)
        return depRegex.findAll(file.readText()).map { m ->
            Dependency(m.groupValues[1].trim(), m.groupValues[2].trim(), m.groupValues[3].trim())
        }.take(100).toList()
    }

    private fun parseNpm(file: File): List<Dependency> {
        if (!file.exists()) return emptyList()
        val regex = Regex(""""([@\w/.\-]+)"\s*:\s*"([^"]+)"""")
        var inDeps = false
        val deps = mutableListOf<Dependency>()
        file.readLines().forEach { line ->
            val t = line.trim()
            if (t.contains("\"dependencies\"") || t.contains("\"devDependencies\"")) inDeps = true
            else if (inDeps && t == "}") inDeps = false
            else if (inDeps) {
                val m = regex.find(t)
                if (m != null) deps += Dependency("", m.groupValues[1], m.groupValues[2])
            }
        }
        return deps.take(100)
    }
}
