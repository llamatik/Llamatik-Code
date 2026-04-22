package com.llamatik.teammate.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.llamatik.teammate.ui.Strings
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists

@Service(Service.Level.APP)
class DownloadedModelService {

    private val onRegisteredListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addOnRegisteredListener(listener: () -> Unit) { onRegisteredListeners.add(listener) }
    fun removeOnRegisteredListener(listener: () -> Unit) { onRegisteredListeners.remove(listener) }
    private fun notifyRegistered() { onRegisteredListeners.forEach { it() } }
    fun listDownloaded(): List<String> {
        val settings = service<LlamatikAppSettings>()
        val storageDir = settings.storageDir()
        val all = settings.data().downloadedModels.toList()

        val resolved = all.mapNotNull { path ->
            val p = runCatching { Path.of(path) }.getOrNull() ?: return@mapNotNull null
            when {
                // Path still valid
                Files.exists(p) && Files.isRegularFile(p) -> p.toAbsolutePath().normalize().toString()
                // Path stale — try to find by filename in current storageDir
                else -> {
                    val fileName = p.fileName?.toString() ?: return@mapNotNull null
                    val candidate = storageDir.resolve(fileName).toAbsolutePath().normalize()
                    if (Files.exists(candidate) && Files.isRegularFile(candidate)) candidate.toString()
                    else null
                }
            }
        }.distinct()

        // Update persisted list if anything changed
        if (resolved != all) {
            settings.data().downloadedModels.apply {
                clear()
                addAll(resolved)
            }
        }
        return resolved
    }

    fun registerLocalModel(path: String) {
        val normalized = normalizeLocalModelPath(path) ?: return
        val settings = service<LlamatikAppSettings>()
        if (normalized !in settings.data().downloadedModels) {
            settings.data().downloadedModels.add(normalized)
        }
        notifyRegistered()
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

    fun listDownloadedMmprojs(): List<String> {
        val settings = service<LlamatikAppSettings>()
        val storageDir = settings.storageDir()
        val all = settings.data().downloadedMmprojs.toList()
        val resolved = all.mapNotNull { path ->
            val p = runCatching { Path.of(path) }.getOrNull() ?: return@mapNotNull null
            when {
                Files.exists(p) && Files.isRegularFile(p) -> p.toAbsolutePath().normalize().toString()
                else -> {
                    val fileName = p.fileName?.toString() ?: return@mapNotNull null
                    val candidate = storageDir.resolve(fileName).toAbsolutePath().normalize()
                    if (Files.exists(candidate) && Files.isRegularFile(candidate)) candidate.toString()
                    else null
                }
            }
        }.distinct()
        if (resolved != all) {
            settings.data().downloadedMmprojs.apply { clear(); addAll(resolved) }
        }
        return resolved
    }

    fun registerLocalMmproj(path: String) {
        val normalized = normalizeLocalModelPath(path) ?: return
        val settings = service<LlamatikAppSettings>()
        if (normalized !in settings.data().downloadedMmprojs) {
            settings.data().downloadedMmprojs.add(normalized)
        }
        notifyRegistered()
    }

    fun deleteMmproj(path: String) {
        val settings = service<LlamatikAppSettings>()
        settings.data().downloadedMmprojs.remove(path)
    }

    fun deleteAll() {
        listDownloaded().forEach { deleteModel(it) }
    }

    fun downloadPredefined(project: Project?, modelId: String, onFinished: (String) -> Unit) {
        val descriptor = service<LlamatikAppSettings>().predefinedModels().firstOrNull { it.id == modelId }
        val message = buildString {
            appendLine(Strings.ModelSetup.INTRO)
            appendLine()
            appendLine(Strings.ModelSetup.recommendedModel(descriptor?.displayName ?: modelId))
            appendLine(Strings.ModelSetup.source(descriptor?.source ?: Strings.ModelSetup.SOURCE_MANUAL))
            appendLine()
            appendLine(descriptor?.description ?: "Download the model manually, then select the .gguf file in Settings.")
            appendLine()
            appendLine(Strings.ModelSetup.NEXT_STEPS)
            appendLine(Strings.ModelSetup.STEP1)
            appendLine(Strings.ModelSetup.STEP2)
        }
        ApplicationManager.getApplication().invokeLater {
            Messages.showInfoMessage(project, message, Strings.ModelSetup.DIALOG_TITLE)
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
