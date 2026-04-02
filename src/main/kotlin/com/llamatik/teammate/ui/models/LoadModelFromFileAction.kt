package com.llamatik.teammate.ui.models

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.llamatik.teammate.settings.DownloadedModelService
import com.llamatik.teammate.settings.LlamatikAppSettings
import com.llamatik.teammate.ui.Strings
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

object LoadModelFromFileAction {
    fun show(project: Project?, onModelSelected: (String) -> Unit = {}) {
        val chooser = JFileChooser().apply {
            dialogTitle = Strings.LoadFromFile.DIALOG_TITLE
            fileSelectionMode = JFileChooser.FILES_ONLY
            fileFilter = FileNameExtensionFilter(Strings.LoadFromFile.FILE_FILTER_DESC, "gguf")
        }
        val result = chooser.showOpenDialog(null)
        if (result != JFileChooser.APPROVE_OPTION) return
        val file = chooser.selectedFile
        when {
            !file.exists() -> Messages.showErrorDialog(project, Strings.LoadFromFile.errorNotExists(file.absolutePath), Strings.LoadFromFile.ERROR_NOT_EXISTS_TITLE)
            !file.canRead() -> Messages.showErrorDialog(project, Strings.LoadFromFile.errorCannotRead(file.absolutePath), Strings.LoadFromFile.ERROR_NOT_EXISTS_TITLE)
            !file.name.lowercase().endsWith(".gguf") -> Messages.showErrorDialog(project, Strings.LoadFromFile.errorNotGguf(file.name), Strings.LoadFromFile.ERROR_UNSUPPORTED_TITLE)
            else -> {
                val path = file.absolutePath
                service<DownloadedModelService>().registerLocalModel(path)
                service<LlamatikAppSettings>().data().modelPath = path
                Messages.showInfoMessage(project, Strings.LoadFromFile.successMsg(file.name, path), Strings.LoadFromFile.SUCCESS_TITLE)
                onModelSelected(path)
            }
        }
    }
}
