package com.llamatik.teammate.external.docs

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.llamatik.teammate.external.ExternalKnowledgeLimits
import java.nio.file.Files
import java.nio.file.Path

@Service(Service.Level.APP)
class DocsSourceService {

    private val storageFile: Path = Path.of(System.getProperty("user.home"), ".llamatik", "docs_sources.json")

    fun listSources(): List<DocsSource> {
        return try {
            if (!Files.exists(storageFile)) return emptyList()
            val json = Files.readString(storageFile)
            parseSources(json)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addSource(source: DocsSource): Boolean {
        val isPro = isPro()
        if (!canAddSource(isPro)) return false
        val current = listSources().toMutableList()
        if (current.any { it.id == source.id }) return false
        current.add(source)
        saveSources(current)
        return true
    }

    fun removeSource(id: String) {
        val current = listSources().filter { it.id != id }
        saveSources(current)
    }

    fun enableSource(id: String, enabled: Boolean) {
        val current = listSources().map { if (it.id == id) it.copy(enabled = enabled) else it }
        saveSources(current)
    }

    fun canAddSource(isPro: Boolean): Boolean = listSources().size < sourcesLimit(isPro)

    fun sourcesLimit(isPro: Boolean): Int = ExternalKnowledgeLimits.docsSourceLimit(isPro)

    private fun isPro(): Boolean = runCatching {
        com.llamatik.teammate.licensing.LicenseChecker.isLicensed() == true
    }.getOrElse { false }

    private fun saveSources(sources: List<DocsSource>) {
        try {
            Files.createDirectories(storageFile.parent)
            Files.writeString(storageFile, serializeSources(sources))
        } catch (_: Exception) {
            // Silently ignore persistence failures
        }
    }

    private fun serializeSources(sources: List<DocsSource>): String = buildString {
        appendLine("[")
        sources.forEachIndexed { index, s ->
            append("  {")
            append("\"id\":\"${s.id.jsonEscape()}\",")
            append("\"name\":\"${s.name.jsonEscape()}\",")
            append("\"type\":\"${s.type.name}\",")
            append("\"location\":\"${s.location.jsonEscape()}\",")
            append("\"enabled\":${s.enabled},")
            append("\"refreshMode\":\"${s.refreshMode.name}\",")
            append("\"lastIndexedAt\":${s.lastIndexedAt}")
            append("}")
            if (index < sources.size - 1) appendLine(",") else appendLine()
        }
        append("]")
    }

    private fun parseSources(json: String): List<DocsSource> {
        val sources = mutableListOf<DocsSource>()
        val objectRegex = Regex("""\{[^}]*\}""")
        for (match in objectRegex.findAll(json)) {
            val obj = match.value
            val id = extractStringField(obj, "id") ?: continue
            val name = extractStringField(obj, "name") ?: continue
            val type = extractStringField(obj, "type") ?: continue
            val location = extractStringField(obj, "location") ?: continue
            val enabled = extractBoolField(obj, "enabled") ?: true
            val refreshMode = extractStringField(obj, "refreshMode") ?: "MANUAL"
            val lastIndexedAt = extractLongField(obj, "lastIndexedAt") ?: 0L

            sources.add(
                DocsSource(
                    id = id,
                    name = name,
                    type = runCatching { DocsSourceType.valueOf(type) }.getOrElse { DocsSourceType.URL },
                    location = location,
                    enabled = enabled,
                    refreshMode = runCatching { DocsRefreshMode.valueOf(refreshMode) }.getOrElse { DocsRefreshMode.MANUAL },
                    lastIndexedAt = lastIndexedAt,
                )
            )
        }
        return sources
    }

    private fun extractStringField(json: String, key: String): String? {
        val regex = Regex(""""$key"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        return regex.find(json)?.groupValues?.get(1)?.replace("\\\"", "\"")?.replace("\\\\", "\\")
    }

    private fun extractBoolField(json: String, key: String): Boolean? {
        val regex = Regex(""""$key"\s*:\s*(true|false)""")
        return regex.find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull()
    }

    private fun extractLongField(json: String, key: String): Long? {
        val regex = Regex(""""$key"\s*:\s*(\d+)""")
        return regex.find(json)?.groupValues?.get(1)?.toLongOrNull()
    }

    private fun String.jsonEscape(): String =
        this.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r")

    companion object {
        @JvmStatic
        fun getInstance(): DocsSourceService = service()
    }
}
