package com.llamatik.teammate.benchmark

import java.io.File

data class BenchmarkTask(
    val id: String,
    val name: String,
    val language: String,
    val timeoutMinutes: Int,
    val maxSteps: Int,
    val expectedFiles: List<String>,
    val repoDir: File,
    val promptFile: File,
    val testCommandFile: File,
    val taskDir: File,
) {
    val prompt: String get() = promptFile.readText().trim()
    val testCommand: String get() = testCommandFile.readText().trim()
}

object BenchmarkTaskLoader {

    private val IGNORED_DIRS = setOf(".gradle", "build", "out", ".idea", "node_modules", "target")

    fun loadAll(tasksRoot: File): List<BenchmarkTask> {
        require(tasksRoot.isDirectory) { "Tasks root does not exist: ${tasksRoot.absolutePath}" }
        return tasksRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.sortedBy { it.name }
            ?.mapNotNull { taskDir -> runCatching { load(taskDir) }.getOrNull() }
            ?: emptyList()
    }

    fun load(taskDir: File): BenchmarkTask {
        val repoDir = File(taskDir, "repo")
        val promptFile = File(taskDir, "prompt.md")
        val testCommandFile = File(taskDir, "test-command.txt")

        require(repoDir.isDirectory) { "Missing repo/ directory in ${taskDir.name}" }
        require(promptFile.isFile) { "Missing prompt.md in ${taskDir.name}" }
        require(testCommandFile.isFile) { "Missing test-command.txt in ${taskDir.name}" }

        val meta = parseMetadata(File(taskDir, "metadata.json"), taskDir.name)

        return BenchmarkTask(
            id = meta.id,
            name = meta.name,
            language = meta.language,
            timeoutMinutes = meta.timeoutMinutes,
            maxSteps = meta.maxSteps,
            expectedFiles = meta.expectedFiles,
            repoDir = repoDir,
            promptFile = promptFile,
            testCommandFile = testCommandFile,
            taskDir = taskDir,
        )
    }

    private data class Meta(
        val id: String,
        val name: String,
        val language: String,
        val timeoutMinutes: Int,
        val maxSteps: Int,
        val expectedFiles: List<String>,
    )

    private fun parseMetadata(metaFile: File, fallbackId: String): Meta {
        if (!metaFile.isFile) return Meta(
            id = fallbackId,
            name = fallbackId,
            language = "unknown",
            timeoutMinutes = 15,
            maxSteps = 30,
            expectedFiles = emptyList(),
        )
        val json = metaFile.readText()
        return Meta(
            id = extractString(json, "id") ?: fallbackId,
            name = extractString(json, "name") ?: fallbackId,
            language = extractString(json, "language") ?: "unknown",
            timeoutMinutes = extractInt(json, "timeoutMinutes") ?: 15,
            maxSteps = extractInt(json, "maxSteps") ?: 30,
            expectedFiles = extractStringList(json, "expectedFiles"),
        )
    }

    // Minimal JSON field extractors — avoids a JSON library dependency in test code.
    private fun extractString(json: String, key: String): String? =
        Regex(""""$key"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1)

    private fun extractInt(json: String, key: String): Int? =
        Regex(""""$key"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toIntOrNull()

    private fun extractStringList(json: String, key: String): List<String> {
        val arrayMatch = Regex(""""$key"\s*:\s*\[([^\]]*)]""").find(json) ?: return emptyList()
        val body = arrayMatch.groupValues[1]
        return Regex(""""([^"]*)"""").findAll(body).map { it.groupValues[1] }.toList()
    }

    fun snapshotFiles(dir: File): Map<String, Long> {
        val result = mutableMapOf<String, Long>()
        collectForSnapshot(dir, dir, result)
        return result
    }

    private fun collectForSnapshot(root: File, current: File, out: MutableMap<String, Long>) {
        current.listFiles()?.forEach { f ->
            if (f.isDirectory) {
                if (f.name !in IGNORED_DIRS) collectForSnapshot(root, f, out)
            } else {
                val rel = f.relativeTo(root).path
                out[rel] = f.lastModified()
            }
        }
    }

    fun computeChangedFiles(before: Map<String, Long>, repoDir: File): List<String> {
        val after = snapshotFiles(repoDir)
        val changed = mutableListOf<String>()
        after.forEach { (path, mtime) ->
            if (before[path] == null || before[path] != mtime) changed += path
        }
        return changed.sorted()
    }
}
