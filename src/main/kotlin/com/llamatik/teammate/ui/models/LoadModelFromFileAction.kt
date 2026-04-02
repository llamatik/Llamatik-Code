package com.llamatik.teammate.ui.models

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.llamatik.teammate.settings.DownloadedModelService
import com.llamatik.teammate.settings.LlamatikAppSettings
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

object LoadModelFromFileAction {
    fun show(project: Project?, onModelSelected: (String) -> Unit = {}) {
        val chooser = JFileChooser().apply {
            dialogTitle = "Select Local Model File (.gguf)"
            fileSelectionMode = JFileChooser.FILES_ONLY
            fileFilter = FileNameExtensionFilter("GGUF Model Files", "gguf")
        }
        val result = chooser.showOpenDialog(null)
        if (result != JFileChooser.APPROVE_OPTION) return
        val file = chooser.selectedFile
        when {
            !file.exists() -> Messages.showErrorDialog(project, "File does not exist: ${file.absolutePath}", "Invalid Model File")
            !file.canRead() -> Messages.showErrorDialog(project, "Cannot read file: ${file.absolutePath}", "Invalid Model File")
            !file.name.lowercase().endsWith(".gguf") -> Messages.showErrorDialog(project, "File must be a .gguf model: ${file.name}", "Unsupported Format")
            else -> {
                val path = file.absolutePath
                service<DownloadedModelService>().registerLocalModel(path)
                service<LlamatikAppSettings>().data().modelPath = path
                Messages.showInfoMessage(project, "Model registered: ${file.name}\nPath: $path", "Model Loaded")
                onModelSelected(path)
            }
        }
    }
}
