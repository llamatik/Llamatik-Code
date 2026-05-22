package com.llamatik.teammate.release

import java.io.File

/**
 * Loads [ToolContractCase] instances from JSON files under a given root directory.
 *
 * Expected layout:
 *   <root>/
 *     <tool-name>/
 *       <case-id>.json
 */
object ToolContractLoader {

    fun loadAll(toolCasesRoot: File): List<ToolContractCase> {
        if (!toolCasesRoot.isDirectory) return emptyList()
        return toolCasesRoot.listFiles()
            ?.filter { it.isDirectory }
            ?.flatMap { toolDir -> loadForTool(toolDir) }
            ?.sortedBy { "${it.tool}/${it.id}" }
            ?: emptyList()
    }

    fun loadForTool(toolDir: File): List<ToolContractCase> {
        return toolDir.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.mapNotNull { file -> runCatching { parse(file, toolDir.name) }.getOrNull() }
            ?: emptyList()
    }

    private fun parse(file: File, toolName: String): ToolContractCase {
        val json = file.readText()
        val id = extractString(json, "id") ?: file.nameWithoutExtension
        val tool = extractString(json, "tool") ?: toolName
        val fixtureProject = extractString(json, "fixtureProject")
        val expectSuccess = extractBoolean(json, "expectSuccess") ?: true
        val expectContentContains = extractStringList(json, "expectContentContains")
        val expectContentNotContains = extractStringList(json, "expectContentNotContains")
        val description = extractString(json, "description") ?: id
        val arguments = extractStringMap(json, "arguments")
        return ToolContractCase(
            id = id,
            tool = tool,
            arguments = arguments,
            fixtureProject = fixtureProject,
            expectSuccess = expectSuccess,
            expectContentContains = expectContentContains,
            expectContentNotContains = expectContentNotContains,
            description = description,
        )
    }

    // ── minimal JSON extractors ───────────────────────────────────────────────

    private fun extractString(json: String, key: String): String? =
        Regex(""""$key"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1)

    private fun extractBoolean(json: String, key: String): Boolean? =
        Regex(""""$key"\s*:\s*(true|false)""").find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull()

    private fun extractStringList(json: String, key: String): List<String> {
        val arrayMatch = Regex(""""$key"\s*:\s*\[([^\]]*)]""").find(json) ?: return emptyList()
        return Regex(""""([^"]*)"""").findAll(arrayMatch.groupValues[1]).map { it.groupValues[1] }.toList()
    }

    private fun extractStringMap(json: String, key: String): Map<String, String> {
        // Find the opening brace for this key, then scan for the matching closing brace.
        val keyPattern = Regex(""""$key"\s*:\s*\{""")
        val match = keyPattern.find(json) ?: return emptyMap()
        val start = match.range.last + 1
        var depth = 1
        var pos = start
        while (pos < json.length && depth > 0) {
            when (json[pos]) {
                '{' -> depth++
                '}' -> depth--
            }
            pos++
        }
        if (depth != 0) return emptyMap()
        val body = json.substring(start, pos - 1)
        val pairs = Regex(""""([^"]+)"\s*:\s*"((?:[^"\\]|\\.)*)"""").findAll(body)
        return pairs.associate { m ->
            m.groupValues[1] to m.groupValues[2].replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\")
        }
    }
}
