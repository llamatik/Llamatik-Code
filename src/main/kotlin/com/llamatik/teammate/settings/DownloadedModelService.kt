package com.llamatik.teammate.settings

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import java.nio.file.Files
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists

@Service(Service.Level.APP)
class DownloadedModelService {
    fun listDownloaded(): List<String> {
        val settings = service<LlamatikAppSettings>()
        return settings.data().downloadedModels.toList()
    }

    fun registerLocalModel(path: String) {
        val settings = service<LlamatikAppSettings>()
        if (path.isNotBlank() && path !in settings.data().downloadedModels) {
            settings.data().downloadedModels.add(path)
        }
    }

    fun deleteModel(path: String) {
        val settings = service<LlamatikAppSettings>()
        settings.data().downloadedModels.remove(path)
        runCatching { java.nio.file.Path.of(path).deleteIfExists() }
    }

    fun deleteAll() {
        listDownloaded().forEach { deleteModel(it) }
    }

    fun downloadPredefined(project: Project?, modelId: String, onFinished: (String) -> Unit) {
        object : Task.Backgroundable(project, "Registering model", false) {
            override fun run(indicator: ProgressIndicator) {
                val settings = service<LlamatikAppSettings>()
                val baseDir = settings.storageDir()
                Files.createDirectories(baseDir)
                val sanitizedName = modelId.replace('/', '_') + ".gguf"
                val target = baseDir.resolve(sanitizedName)
                if (!target.exists()) {
                    Files.writeString(
                        target,
                        "TODO: Replace placeholder file with a Hugging Face downloader integrated with Llamatik-supported model artifacts.\nRequested model: $modelId\n",
                    )
                }
                registerLocalModel(target.toString())
                onFinished(target.toString())
            }
        }.queue()
        // TODO: Implement authenticated Hugging Face download + checksum verification.
    }
}
