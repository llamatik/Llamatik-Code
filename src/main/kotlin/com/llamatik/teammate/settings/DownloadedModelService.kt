package com.llamatik.teammate.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

@Service(Service.Level.APP)
class DownloadedModelService {
    fun listDownloaded(): List<String> {
        val settings = service<LlamatikAppSettings>()
        return settings.data().downloadedModels.toList()
    }

    fun registerLocalModel(path: String) {
        val normalized = normalizeLocalModelPath(path) ?: return
        val settings = service<LlamatikAppSettings>()
        if (normalized !in settings.data().downloadedModels) {
            settings.data().downloadedModels.add(normalized)
        }
    }

    fun deleteModel(path: String) {
        val settings = service<LlamatikAppSettings>()
        settings.data().downloadedModels.remove(path)
        runCatching {
            val nioPath = Path.of(path)
            val managedDir = settings.storageDir().toAbsolutePath().normalize()
            val target = nioPath.toAbsolutePath().normalize()
            if (target.startsWith(managedDir)) {
                target.deleteIfExists()
            }
        }
    }

    fun deleteAll() {
        listDownloaded().forEach { deleteModel(it) }
    }

    fun downloadPredefined(project: Project?, modelId: String, onFinished: (String) -> Unit) {
        val descriptor = service<LlamatikAppSettings>().predefinedModels().firstOrNull { it.id == modelId }
        val message = buildString {
            appendLine("This plugin currently supports local GGUF files only.")
            appendLine()
            appendLine("Recommended model: ${descriptor?.displayName ?: modelId}")
            appendLine("Source: ${descriptor?.source ?: "Manual local download"}")
            appendLine()
            appendLine(descriptor?.description ?: "Download the model manually, then select the .gguf file in Settings.")
            appendLine()
            appendLine("Next steps:")
            appendLine("1. Download the GGUF model manually from your preferred source (e.g. Hugging Face).")
            appendLine("2. In Settings → Llamatik Code, browse to the .gguf file and click Register.")
        }
        ApplicationManager.getApplication().invokeLater {
            Messages.showInfoMessage(project, message, "How to add a local model")
            onFinished("")
        }
    }

    private fun normalizeLocalModelPath(path: String): String? {
        val trimmed = path.trim()
        if (trimmed.isBlank()) return null
        val nioPath = runCatching { Path.of(trimmed) }.getOrNull() ?: return null
        if (!Files.exists(nioPath) || !Files.isRegularFile(nioPath)) return null
        if (!trimmed.lowercase().endsWith(".gguf")) return null
        return nioPath.toAbsolutePath().normalize().toString()
    }
}
