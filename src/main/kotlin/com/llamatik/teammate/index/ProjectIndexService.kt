package com.llamatik.teammate.index

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.llamatik.teammate.context.ProjectContextService
import com.llamatik.teammate.context.ProjectIndexingState
import com.llamatik.teammate.util.PluginLogger
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Lightweight lexical index over project source files.
 * Scores files by term-frequency (TF) against a query — no embeddings required.
 * Interface is designed so an embedding-based backend can be plugged in later.
 */
@Service(Service.Level.PROJECT)
class ProjectIndexService(private val project: Project) {

    private val log = PluginLogger.logger<ProjectIndexService>()

    data class IndexedChunk(
        val path: String,
        val content: String,
        val tokens: Set<String>,
    )

    data class SearchResult(
        val path: String,
        val score: Double,
        val snippet: String,
    )

    private val chunks = mutableListOf<IndexedChunk>()
    private val indexing = AtomicBoolean(false)

    private val contextService = ProjectContextService(project)

    init {
        subscribeToFileChanges()
    }

    fun isReady(): Boolean = project.service<ProjectIndexingState>().isReady()

    fun status(): String {
        val state = project.service<ProjectIndexingState>()
        return when (state.state) {
            ProjectIndexingState.State.IDLE     -> "idle (${chunks.size} chunks)"
            ProjectIndexingState.State.INDEXING -> "indexing… (${state.indexedFileCount}/${state.totalFileCount})"
            ProjectIndexingState.State.READY    -> "ready — ${chunks.size} chunks from ${state.indexedFileCount} files"
            ProjectIndexingState.State.FAILED   -> "failed"
        }
    }

    /** Build or rebuild the index in background. Calls [onDone] with file count when finished. */
    fun build(onDone: ((Int) -> Unit)? = null) {
        if (!indexing.compareAndSet(false, true)) return
        val state = project.service<ProjectIndexingState>()
        state.startIndexing()
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val files = contextService.listFiles(null)
                    .filter { isIndexable(it) }
                state.totalFileCount = files.size
                val newChunks = mutableListOf<IndexedChunk>()
                files.forEachIndexed { i, path ->
                    if (Thread.currentThread().isInterrupted) return@executeOnPooledThread
                    state.indexedFileCount = i + 1
                    val content = runCatching { contextService.readFile(path) }.getOrNull() ?: return@forEachIndexed
                    newChunks += IndexedChunk(path, content.take(8000), tokenize(content))
                }
                synchronized(chunks) {
                    chunks.clear()
                    chunks += newChunks
                }
                state.finishIndexing(newChunks.size)
                log.info("Index built: ${newChunks.size} chunks from ${files.size} files")
                onDone?.invoke(newChunks.size)
            } catch (e: Exception) {
                state.failIndexing()
                log.warn("Index build failed: ${e.message}")
            } finally {
                indexing.set(false)
            }
        }
    }

    fun search(query: String, maxResults: Int = 10): List<SearchResult> {
        if (query.isBlank()) return emptyList()
        val queryTokens = tokenize(query)
        return synchronized(chunks) { chunks.toList() }
            .map { chunk ->
                val score = bm25Score(queryTokens, chunk.tokens, chunk.content.length)
                chunk to score
            }
            .filter { it.second > 0.0 }
            .sortedByDescending { it.second }
            .take(maxResults)
            .map { (chunk, score) ->
                val snippet = extractSnippet(chunk.content, query)
                SearchResult(chunk.path, score, snippet)
            }
    }

    fun updateFile(relativePath: String) {
        if (!isIndexable(relativePath)) return
        val content = runCatching { contextService.readFile(relativePath) }.getOrNull() ?: run {
            synchronized(chunks) { chunks.removeIf { it.path == relativePath } }
            return
        }
        val chunk = IndexedChunk(relativePath, content.take(8000), tokenize(content))
        synchronized(chunks) {
            chunks.removeIf { it.path == relativePath }
            chunks += chunk
        }
    }

    private fun bm25Score(queryTokens: Set<String>, docTokens: Set<String>, docLen: Int): Double {
        val k1 = 1.5; val b = 0.75; val avgDocLen = 500.0
        return queryTokens.sumOf { token ->
            if (token !in docTokens) return@sumOf 0.0
            val tf = docTokens.count { it == token }.toDouble()
            val norm = (k1 * ((1 - b) + b * docLen / avgDocLen))
            tf * (k1 + 1) / (tf + norm)
        }
    }

    private fun tokenize(text: String): Set<String> =
        text.lowercase()
            .split(Regex("[^a-zA-Z0-9_]+"))
            .filter { it.length >= 3 }
            .toSet()

    private fun extractSnippet(content: String, query: String, maxLen: Int = 120): String {
        val lower = content.lowercase()
        val queryLow = query.lowercase().split(Regex("\\s+")).firstOrNull { it.length >= 3 } ?: query.lowercase()
        val idx = lower.indexOf(queryLow)
        return if (idx >= 0) {
            val start = maxOf(0, idx - 30)
            val end = minOf(content.length, idx + maxLen)
            "…" + content.substring(start, end).replace('\n', ' ').trim() + "…"
        } else {
            content.take(maxLen).replace('\n', ' ').trim()
        }
    }

    private fun isIndexable(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        val normalized = path.replace('\\', '/')
        val excluded = setOf(".git", ".idea", ".gradle", "build", "out", "target",
            "node_modules", "generated", "intermediates", "__pycache__")
        if (excluded.any { seg ->
            normalized == seg ||
            normalized.startsWith("$seg/") ||
            normalized.contains("/$seg/") ||
            normalized.endsWith("/$seg")
        }) return false
        return ext in setOf("kt", "java", "py", "ts", "tsx", "js", "jsx",
            "swift", "go", "rs", "cpp", "c", "cs", "rb", "gradle", "kts", "xml", "json", "md")
    }

    private fun subscribeToFileChanges() {
        project.messageBus.connect().subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (!isReady()) return
                events.forEach { event ->
                    val path = event.path
                    val base = project.basePath ?: return@forEach
                    if (path.startsWith(base)) {
                        val rel = path.removePrefix(base).trimStart('/', '\\')
                        updateFile(rel)
                    }
                }
            }
        })
    }
}
