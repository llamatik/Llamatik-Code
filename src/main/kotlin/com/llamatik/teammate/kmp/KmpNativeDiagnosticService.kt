package com.llamatik.teammate.kmp

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.context.ProjectContextService
import com.llamatik.teammate.util.PluginLogger

@Service(Service.Level.PROJECT)
class KmpNativeDiagnosticService(private val project: Project) {

    private val log = PluginLogger.logger<KmpNativeDiagnosticService>()
    private val contextService = ProjectContextService(project)

    enum class DiagnosticCategory {
        UNRESOLVED_SYMBOL,
        MISSING_CINTEROP,
        DEF_FILE_ISSUE,
        LINKER_OPTS,
        FORCE_LOAD,
        CMAKE_PATH_MISMATCH,
        ARCH_MISMATCH,
        MISSING_EXPORTED_SYMBOL,
        UNKNOWN,
    }

    data class NativeDiagnosticReport(
        val isKmpProject: Boolean,
        val detectedTargets: List<String>,
        val issues: List<NativeIssue>,
        val gradleFiles: List<String>,
        val defFiles: List<String>,
        val cmakeFiles: List<String>,
        val suggestedFixes: List<String>,
        val verificationCommand: String,
        val evidence: List<String>,
    )

    data class NativeIssue(
        val category: DiagnosticCategory,
        val description: String,
        val affectedFile: String,
        val suggestedFix: String,
        val lineHint: String = "",
    )

    fun diagnose(buildLog: String = ""): NativeDiagnosticReport {
        val allFiles = runCatching { contextService.listFiles(null) }.getOrElse { emptyList() }
        val gradleFiles = allFiles.filter { it.endsWith("build.gradle.kts") || it.endsWith("build.gradle") }
        val defFiles = allFiles.filter { it.endsWith(".def") }
        val cmakeFiles = allFiles.filter { it.endsWith("CMakeLists.txt") }
        val evidence = mutableListOf<String>()

        val buildGradleContent = gradleFiles.firstOrNull()?.let { path ->
            runCatching { contextService.readFile(path) }.getOrNull()
        } ?: ""

        val isKmp = buildGradleContent.contains("kotlin-multiplatform", ignoreCase = true) ||
            buildGradleContent.contains("multiplatform", ignoreCase = true)

        val detectedTargets = mutableListOf<String>()
        for (target in listOf("iosArm64", "iosSimulatorArm64", "iosX64", "macosArm64", "macosX64",
            "linuxX64", "mingwX64")) {
            if (buildGradleContent.contains(target, ignoreCase = true)) detectedTargets += target
        }
        evidence += "KMP project detected: $isKmp"
        evidence += "Native targets: ${detectedTargets.joinToString(", ").ifBlank { "none"}}"
        evidence += "Gradle files: ${gradleFiles.size}, .def files: ${defFiles.size}, CMakeLists: ${cmakeFiles.size}"

        val issues = mutableListOf<NativeIssue>()
        if (buildLog.isNotBlank()) {
            issues += analyzeLog(buildLog, defFiles, cmakeFiles)
        }
        issues += analyzeGradleForIssues(buildGradleContent, gradleFiles.firstOrNull() ?: "build.gradle.kts")
        issues += analyzeDefFiles(defFiles)

        val fixes = buildSuggestedFixes(issues, detectedTargets)
        val verifyCmd = buildVerificationCommand(detectedTargets)

        return NativeDiagnosticReport(
            isKmpProject = isKmp,
            detectedTargets = detectedTargets,
            issues = issues,
            gradleFiles = gradleFiles,
            defFiles = defFiles,
            cmakeFiles = cmakeFiles,
            suggestedFixes = fixes,
            verificationCommand = verifyCmd,
            evidence = evidence,
        )
    }

    private fun analyzeLog(log: String, defFiles: List<String>, cmakeFiles: List<String>): List<NativeIssue> {
        val issues = mutableListOf<NativeIssue>()
        val lines = log.lines()

        for (line in lines) {
            val lower = line.lowercase()
            when {
                "undefined symbol" in lower || "unresolved external" in lower -> {
                    val sym = Regex("""undefined symbol:\s*(\S+)""").find(line)?.groupValues?.get(1) ?: "unknown"
                    issues += NativeIssue(
                        category = DiagnosticCategory.UNRESOLVED_SYMBOL,
                        description = "Unresolved symbol: $sym",
                        affectedFile = cmakeFiles.firstOrNull() ?: "native source",
                        suggestedFix = "Check that the library exporting '$sym' is listed in linkerOpts or the .def file's headers. Verify CMake outputs the expected .a/.dylib and the path in Gradle matches.",
                        lineHint = line.trim().take(120),
                    )
                }
                "cinterop" in lower && ("error" in lower || "failed" in lower) -> {
                    issues += NativeIssue(
                        category = DiagnosticCategory.MISSING_CINTEROP,
                        description = "CInterop task failed",
                        affectedFile = defFiles.firstOrNull() ?: "*.def",
                        suggestedFix = "Verify the .def file headers are correct. Check that 'includeDirs' and 'compilerOpts' match the actual header locations. Run './gradlew cinteropXxx' separately to see the full error.",
                        lineHint = line.trim().take(120),
                    )
                }
                "force_load" in lower || "-force_load" in lower -> {
                    issues += NativeIssue(
                        category = DiagnosticCategory.FORCE_LOAD,
                        description = "force_load linking issue",
                        affectedFile = defFiles.firstOrNull() ?: "build.gradle.kts",
                        suggestedFix = "Ensure -force_load path matches the actual library output path. Confirm the library was built before the link step. Use `\$(BUILT_PRODUCTS_DIR)/libXxx.a` pattern.",
                        lineHint = line.trim().take(120),
                    )
                }
                "architecture" in lower && ("not found" in lower || "mismatch" in lower || "wrong" in lower) -> {
                    issues += NativeIssue(
                        category = DiagnosticCategory.ARCH_MISMATCH,
                        description = "Architecture mismatch in native library",
                        affectedFile = "native library",
                        suggestedFix = "Use `file <library.a>` or `lipo -info` to check which architectures are included. Ensure you build for the right target: iosSimulatorArm64 → arm64-apple-ios-simulator, not arm64-apple-ios.",
                    )
                }
                "cmake" in lower && ("error" in lower || "not found" in lower) -> {
                    issues += NativeIssue(
                        category = DiagnosticCategory.CMAKE_PATH_MISMATCH,
                        description = "CMake configuration error",
                        affectedFile = cmakeFiles.firstOrNull() ?: "CMakeLists.txt",
                        suggestedFix = "Check OUTPUT_DIRECTORY and install() paths in CMakeLists.txt match the paths in Gradle's cinterop and linkerOpts. Ensure cmake.path points to the correct CMakeLists.txt.",
                        lineHint = line.trim().take(120),
                    )
                }
            }
        }

        return issues.distinctBy { it.category.name + it.description.take(60) }
    }

    private fun analyzeGradleForIssues(content: String, file: String): List<NativeIssue> {
        val issues = mutableListOf<NativeIssue>()
        if (content.isBlank()) return issues

        if (content.contains("cinterops") && !content.contains("defFile") && !content.contains("packageName")) {
            issues += NativeIssue(
                category = DiagnosticCategory.MISSING_CINTEROP,
                description = "cinterops block found but may be missing defFile or packageName",
                affectedFile = file,
                suggestedFix = "Add 'defFile = file(\"src/nativeInterop/cinterop/MyLib.def\")' and 'packageName = \"com.example.mylib\"' inside the cinterops block.",
            )
        }

        if (!content.contains("exportLibraries") && content.contains("framework")) {
            issues += NativeIssue(
                category = DiagnosticCategory.MISSING_EXPORTED_SYMBOL,
                description = "Framework target may not export all symbols",
                affectedFile = file,
                suggestedFix = "Add 'export(project(\":library\"))' inside the framework { } block to ensure transitive symbols are exported to the Xcode framework.",
            )
        }

        return issues
    }

    private fun analyzeDefFiles(defFiles: List<String>): List<NativeIssue> {
        val issues = mutableListOf<NativeIssue>()
        for (path in defFiles) {
            val content = runCatching { contextService.readFile(path) }.getOrElse { continue }
            if (!content.contains("headers") && !content.contains("headerFilter")) {
                issues += NativeIssue(
                    category = DiagnosticCategory.DEF_FILE_ISSUE,
                    description = "Missing 'headers' or 'headerFilter' in .def file",
                    affectedFile = path,
                    suggestedFix = "Add 'headers = MyLib.h' or 'headerFilter = MyLib/**' to the .def file so CInterop knows which headers to process.",
                )
            }
            if (!content.contains("libraryPaths") && !content.contains("linkerOpts")) {
                issues += NativeIssue(
                    category = DiagnosticCategory.LINKER_OPTS,
                    description = "No libraryPaths or linkerOpts in .def file — linker may not find the library",
                    affectedFile = path,
                    suggestedFix = "Add 'libraryPaths = /path/to/lib' or 'linkerOpts = -L/path/to/lib -lMyLib' to the .def file.",
                )
            }
        }
        return issues
    }

    private fun buildSuggestedFixes(issues: List<NativeIssue>, targets: List<String>): List<String> {
        val fixes = mutableListOf<String>()
        issues.forEach { fixes += it.suggestedFix }
        if (targets.any { "ios" in it.lowercase() }) {
            fixes += "Run 'lipo -info <library.a>' to verify that the library was built for the correct architecture."
            fixes += "Check Xcode build settings: LIBRARY_SEARCH_PATHS and FRAMEWORK_SEARCH_PATHS must point to Gradle build output."
        }
        if (issues.any { it.category == DiagnosticCategory.UNRESOLVED_SYMBOL }) {
            fixes += "Run the cinterop task in isolation: './gradlew <target>CInterop<name>' to isolate the failure."
        }
        return fixes.distinct()
    }

    private fun buildVerificationCommand(targets: List<String>): String {
        val target = targets.firstOrNull() ?: "iosArm64"
        return "./gradlew ${target}MainBinaries --info 2>&1 | grep -E 'error:|warning:|undefined'"
    }
}
