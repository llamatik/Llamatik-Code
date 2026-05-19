package com.llamatik.teammate.release

import java.io.File

/**
 * Executes [ToolContractCase] instances against a filesystem-backed tool stub.
 * Does not require an IntelliJ [Project].
 */
class ToolContractRunner(private val fixturesRoot: File) {

    data class ContractResult(
        val case: ToolContractCase,
        val passed: Boolean,
        val actual: String,
        val failureReason: String?,
    )

    fun run(case: ToolContractCase): ContractResult {
        val projectRoot = resolveProjectRoot(case.fixtureProject)

        val result = runCatching { executeTool(case.tool, case.arguments, projectRoot) }
            .getOrElse { "ERROR: ${it.message}" }

        val succeeded = !result.startsWith("ERROR:") && !result.startsWith("File not found") &&
            !result.startsWith("Unknown tool") && !result.startsWith("Missing ") &&
            !result.startsWith("Pattern not found") && !result.startsWith("No such directory") &&
            !result.startsWith("File already exists")

        if (case.expectSuccess != succeeded) {
            val reason = if (case.expectSuccess)
                "Expected success but got: ${result.take(200)}"
            else
                "Expected failure but got: ${result.take(200)}"
            return ContractResult(case, false, result, reason)
        }

        for (expected in case.expectContentContains) {
            if (!result.contains(expected)) {
                return ContractResult(case, false, result, "Expected content not found: \"$expected\"")
            }
        }

        for (forbidden in case.expectContentNotContains) {
            if (result.contains(forbidden)) {
                return ContractResult(case, false, result, "Forbidden content found: \"$forbidden\"")
            }
        }

        return ContractResult(case, true, result, null)
    }

    private fun resolveProjectRoot(fixtureProject: String?): File {
        if (fixtureProject.isNullOrBlank()) return fixturesRoot
        val dir = File(fixturesRoot, "projects/$fixtureProject")
        return if (dir.isDirectory) dir else fixturesRoot
    }

    private fun executeTool(toolName: String, args: Map<String, String>, root: File): String {
        return when (toolName) {
            "list_files" -> listFiles(args["path"], root)
            "read_file" -> readFile(args["path"] ?: return "Missing path", root)
            "search_code" -> searchCode(args["query"] ?: return "Missing query", root)
            "search_filenames" -> searchFilenames(args["query"] ?: return "Missing query", root)
            "write_file" -> writeFile(
                args["path"] ?: return "Missing path",
                args["content"] ?: return "Missing content",
                root,
            )
            "create_file" -> createFile(
                args["path"] ?: return "Missing path",
                args["content"] ?: return "Missing content",
                root,
            )
            "replace_in_file" -> replaceInFile(
                args["path"] ?: return "Missing path",
                args["old"] ?: return "Missing old",
                args["new"] ?: return "Missing new",
                root,
            )
            else -> "Unknown tool: $toolName"
        }
    }

    private fun listFiles(path: String?, root: File): String {
        val base = if (path.isNullOrBlank() || path == ".") root
                   else File(root, path).takeIf { it.isDirectory } ?: return "No such directory: $path"
        val lines = mutableListOf<String>()
        base.walkTopDown().maxDepth(3).forEach { f ->
            if (f == base) return@forEach
            val rel = f.relativeTo(root).path
            if (rel.split(File.separatorChar).any { it in IGNORED }) return@forEach
            lines += rel + if (f.isDirectory) "/" else ""
        }
        return lines.sorted().take(200).joinToString("\n").ifEmpty { "(empty directory)" }
    }

    private fun readFile(path: String, root: File): String {
        val file = File(root, path).takeIf { it.isFile } ?: return "File not found: $path"
        return file.readText()
    }

    private fun searchCode(query: String, root: File): String {
        val hits = mutableListOf<String>()
        root.walkTopDown().forEach { f ->
            if (!f.isFile) return@forEach
            if (f.relativeTo(root).path.split(File.separatorChar).any { it in IGNORED }) return@forEach
            if (f.readText().contains(query, ignoreCase = true)) hits += f.relativeTo(root).path
        }
        return hits.take(30).joinToString("\n").ifEmpty { "No matches for: $query" }
    }

    private fun searchFilenames(query: String, root: File): String {
        val hits = mutableListOf<String>()
        root.walkTopDown().forEach { f ->
            if (!f.isFile) return@forEach
            if (f.relativeTo(root).path.split(File.separatorChar).any { it in IGNORED }) return@forEach
            if (f.name.contains(query, ignoreCase = true)) hits += f.relativeTo(root).path
        }
        return hits.take(30).joinToString("\n").ifEmpty { "No matches for: $query" }
    }

    private fun writeFile(path: String, content: String, root: File): String {
        val file = File(root, path)
        file.parentFile?.mkdirs()
        file.writeText(content)
        return "Prepared file replacement for $path"
    }

    private fun createFile(path: String, content: String, root: File): String {
        val file = File(root, path)
        if (file.exists()) return "File already exists: $path"
        file.parentFile?.mkdirs()
        file.writeText(content)
        return "Prepared new file for $path"
    }

    private fun replaceInFile(path: String, old: String, new: String, root: File): String {
        val file = File(root, path).takeIf { it.isFile } ?: return "File not found: $path"
        val original = file.readText()
        if (!original.contains(old)) return "Pattern not found in $path"
        file.writeText(original.replace(old, new))
        return "Prepared file replacement for $path"
    }

    companion object {
        private val IGNORED = setOf(".gradle", "build", "out", ".idea", "node_modules", "target", ".git")
    }
}
