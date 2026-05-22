package com.llamatik.teammate.release

import java.io.File

object AgentScriptLoader {

    fun loadAll(scriptsRoot: File): List<AgentScript> {
        if (!scriptsRoot.isDirectory) return emptyList()
        return scriptsRoot.listFiles()
            ?.filter { it.isFile && it.extension == "json" }
            ?.sortedBy { it.name }
            ?.mapNotNull { file -> runCatching { parse(file) }.getOrNull() }
            ?: emptyList()
    }

    private fun parse(file: File): AgentScript {
        val json = file.readText()
        return AgentScript(
            id = extractString(json, "id") ?: file.nameWithoutExtension,
            description = extractString(json, "description") ?: "",
            fixtureProject = extractString(json, "fixtureProject") ?: "",
            prompt = extractString(json, "prompt") ?: "",
            responses = extractStringList(json, "responses"),
            expectToolsUsed = extractStringList(json, "expectToolsUsed"),
            expectFilesChanged = extractStringList(json, "expectFilesChanged"),
            expectFinalMessageContains = extractStringList(json, "expectFinalMessageContains"),
            maxRounds = extractInt(json, "maxRounds") ?: 5,
        )
    }

    private fun extractString(json: String, key: String): String? =
        Regex(""""$key"\s*:\s*"([^"]*)"""").find(json)?.groupValues?.get(1)

    private fun extractInt(json: String, key: String): Int? =
        Regex(""""$key"\s*:\s*(\d+)""").find(json)?.groupValues?.get(1)?.toIntOrNull()

    private fun extractStringList(json: String, key: String): List<String> {
        val arrayMatch = Regex(""""$key"\s*:\s*\[([^\]]*?)]""", RegexOption.DOT_MATCHES_ALL).find(json)
            ?: return emptyList()
        val body = arrayMatch.groupValues[1]
        return Regex(""""((?:[^"\\]|\\.)*)"""").findAll(body).map { m ->
            m.groupValues[1].replace("\\\"", "\"").replace("\\n", "\n").replace("\\\\", "\\")
        }.toList()
    }
}
