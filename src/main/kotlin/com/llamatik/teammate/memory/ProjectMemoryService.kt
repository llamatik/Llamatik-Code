package com.llamatik.teammate.memory

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.project.Project

@State(name = "LlamatikProjectMemory", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
@Service(Service.Level.PROJECT)
class ProjectMemoryService(private val project: Project) :
    PersistentStateComponent<ProjectMemoryService.State> {

    data class MemoryEntry(
        var id: String = "",
        var content: String = "",
        var tags: MutableList<String> = mutableListOf(),
        var createdAtMs: Long = 0L,
        var enabled: Boolean = true,
    )

    data class State(
        var entries: MutableList<MemoryEntry> = mutableListOf(),
        var memoryEnabled: Boolean = true,
    )

    private var myState = State()
    override fun getState(): State = myState
    override fun loadState(state: State) { myState = state }

    fun isEnabled(): Boolean = myState.memoryEnabled
    fun setEnabled(enabled: Boolean) { myState.memoryEnabled = enabled }

    fun getAll(): List<MemoryEntry> = myState.entries.filter { it.enabled }

    fun add(content: String, tags: List<String> = emptyList()): MemoryEntry {
        val entry = MemoryEntry(
            id = java.util.UUID.randomUUID().toString().take(8),
            content = content.take(500),
            tags = tags.toMutableList(),
            createdAtMs = System.currentTimeMillis(),
            enabled = true,
        )
        myState.entries.add(entry)
        if (myState.entries.size > 100) myState.entries.removeAt(0)
        return entry
    }

    fun update(id: String, content: String) {
        myState.entries.find { it.id == id }?.content = content.take(500)
    }

    fun delete(id: String) { myState.entries.removeIf { it.id == id } }

    fun setEntryEnabled(id: String, enabled: Boolean) {
        myState.entries.find { it.id == id }?.enabled = enabled
    }

    fun search(query: String): List<MemoryEntry> {
        if (query.isBlank()) return getAll()
        val tokens = query.lowercase().split(Regex("\\s+"))
        return getAll().filter { entry ->
            val lower = entry.content.lowercase()
            tokens.any { lower.contains(it) }
        }
    }

    fun buildPromptBlock(maxChars: Int = 400): String {
        if (!isEnabled()) return ""
        val entries = getAll()
        if (entries.isEmpty()) return ""
        return buildString {
            appendLine("=== Project memory ===")
            entries.take(10).forEach { appendLine("- ${it.content}") }
            appendLine("=== End project memory ===")
        }.take(maxChars)
    }
}
