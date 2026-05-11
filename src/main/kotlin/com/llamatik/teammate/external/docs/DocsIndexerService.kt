package com.llamatik.teammate.external.docs

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.llamatik.teammate.external.ExternalKnowledgeLimits
import com.llamatik.teammate.external.reader.ExternalPageReaderService
import com.llamatik.teammate.external.reader.PageReadRequest
import java.io.File
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path

@Service(Service.Level.APP)
class DocsIndexerService {

    private val indexDir: Path = Path.of(System.getProperty("user.home"), ".llamatik", "docs_index")
    private val inMemoryIndex = mutableMapOf<String, MutableList<DocsIndexEntry>>()

    private val readerService by lazy { service<ExternalPageReaderService>() }

    fun indexSource(source: DocsSource, isPro: Boolean): Int {
        val entries = mutableListOf<DocsIndexEntry>()
        val pageLimit = if (isPro) 20 else 5

        try {
            when (source.type) {
                DocsSourceType.URL -> {
                    val page = readerService.readPage(PageReadRequest(source.location))
                    if (page.error == null) {
                        entries.add(
                            DocsIndexEntry(
                                sourceId = source.id,
                                url = source.location,
                                title = page.title,
                                content = page.text,
                                headings = page.headings,
                            )
                        )
                    }
                }
                DocsSourceType.WEBSITE -> {
                    val rootPage = readerService.readPage(PageReadRequest(source.location))
                    if (rootPage.error == null) {
                        entries.add(
                            DocsIndexEntry(
                                sourceId = source.id,
                                url = source.location,
                                title = rootPage.title,
                                content = rootPage.text,
                                headings = rootPage.headings,
                            )
                        )
                        val baseHost = runCatching { URL(source.location).host }.getOrElse { "" }
                        val internalLinks = rootPage.links
                            .filter { runCatching { URL(it).host == baseHost }.getOrElse { false } }
                            .take(pageLimit - 1)

                        for (link in internalLinks) {
                            if (entries.size >= pageLimit) break
                            val page = readerService.readPage(PageReadRequest(link))
                            if (page.error == null) {
                                entries.add(
                                    DocsIndexEntry(
                                        sourceId = source.id,
                                        url = link,
                                        title = page.title,
                                        content = page.text,
                                        headings = page.headings,
                                    )
                                )
                            }
                        }
                    }
                }
                DocsSourceType.GITHUB -> {
                    // Read README from GitHub
                    val readmeUrls = listOf(
                        "${source.location}/raw/main/README.md",
                        "${source.location}/raw/master/README.md",
                    )
                    for (url in readmeUrls) {
                        val page = readerService.readPage(PageReadRequest(url))
                        if (page.error == null) {
                            entries.add(
                                DocsIndexEntry(
                                    sourceId = source.id,
                                    url = url,
                                    title = page.title.ifBlank { "README" },
                                    content = page.text,
                                    headings = page.headings,
                                )
                            )
                            break
                        }
                    }
                }
                DocsSourceType.LOCAL_FOLDER -> {
                    val folder = File(source.location)
                    if (folder.exists() && folder.isDirectory) {
                        val mdFiles = folder.walkTopDown()
                            .filter { it.isFile && it.extension.lowercase() == "md" }
                            .take(pageLimit)
                            .toList()

                        for (file in mdFiles) {
                            val content = runCatching { file.readText() }.getOrElse { continue }
                            entries.add(
                                DocsIndexEntry(
                                    sourceId = source.id,
                                    url = "file://${file.absolutePath}",
                                    title = file.nameWithoutExtension,
                                    content = content.take(8000),
                                    headings = extractMarkdownHeadings(content),
                                )
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Return what we managed to index
        }

        inMemoryIndex[source.id] = entries.toMutableList()
        persistIndex(source.id, entries)
        return entries.size
    }

    fun searchIndex(query: String, sourceIds: List<String> = emptyList()): List<DocsIndexEntry> {
        loadPersistedIndexIfNeeded()
        val queryWords = query.lowercase().split(" ").filter { it.length > 2 }
        val searchIn = if (sourceIds.isEmpty()) inMemoryIndex.values.flatten()
        else sourceIds.flatMap { inMemoryIndex[it] ?: emptyList() }

        return searchIn
            .map { entry ->
                val text = "${entry.title} ${entry.content} ${entry.headings.joinToString(" ")}".lowercase()
                val score = queryWords.count { word -> text.contains(word) }
                entry to score
            }
            .filter { (_, score) -> score > 0 }
            .sortedWith(Comparator { a, b -> b.second.compareTo(a.second) })
            .map { (entry, _) -> entry }
            .take(10)
    }

    fun clearIndex(sourceId: String) {
        inMemoryIndex.remove(sourceId)
        try {
            val file = indexDir.resolve("$sourceId.txt")
            Files.deleteIfExists(file)
        } catch (_: Exception) {}
    }

    private fun persistIndex(sourceId: String, entries: List<DocsIndexEntry>) {
        try {
            Files.createDirectories(indexDir)
            val file = indexDir.resolve("$sourceId.txt")
            val content = entries.joinToString("\n---\n") { e ->
                "URL: ${e.url}\nTITLE: ${e.title}\nCONTENT: ${e.content.take(4000)}\nHEADINGS: ${e.headings.joinToString("|")}"
            }
            Files.writeString(file, content)
        } catch (_: Exception) {}
    }

    private fun loadPersistedIndexIfNeeded() {
        try {
            if (!Files.exists(indexDir)) return
            Files.list(indexDir).filter { it.toString().endsWith(".txt") }.forEach { file ->
                val sourceId = file.fileName.toString().removeSuffix(".txt")
                if (inMemoryIndex.containsKey(sourceId)) return@forEach
                val content = Files.readString(file)
                val entries = parsePersistedEntries(sourceId, content)
                inMemoryIndex[sourceId] = entries.toMutableList()
            }
        } catch (_: Exception) {}
    }

    private fun parsePersistedEntries(sourceId: String, content: String): List<DocsIndexEntry> {
        return content.split("\n---\n").mapNotNull { block ->
            val url = extractLine(block, "URL:") ?: return@mapNotNull null
            val title = extractLine(block, "TITLE:") ?: ""
            val text = extractLine(block, "CONTENT:") ?: ""
            val headingsRaw = extractLine(block, "HEADINGS:") ?: ""
            val headings = if (headingsRaw.isBlank()) emptyList() else headingsRaw.split("|")
            DocsIndexEntry(sourceId = sourceId, url = url, title = title, content = text, headings = headings)
        }
    }

    private fun extractLine(block: String, prefix: String): String? {
        val line = block.lines().firstOrNull { it.startsWith(prefix) } ?: return null
        return line.removePrefix(prefix).trim()
    }

    private fun extractMarkdownHeadings(content: String): List<String> {
        return content.lines()
            .filter { it.startsWith("#") }
            .map { it.trimStart('#').trim() }
            .take(20)
    }

    companion object {
        @JvmStatic
        fun getInstance(): DocsIndexerService = service()
    }
}
