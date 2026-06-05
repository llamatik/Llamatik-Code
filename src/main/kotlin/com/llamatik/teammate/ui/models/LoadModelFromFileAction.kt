package com.llamatik.teammate.ui.models

import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.llamatik.teammate.settings.DownloadedModelService
import com.llamatik.teammate.settings.LlamatikAppSettings
import com.llamatik.teammate.ui.Strings

object LoadModelFromFileAction {
    fun show(project: Project?, onModelSelected: (String) -> Unit = {}) {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false).apply {
            title = Strings.LoadFromFile.DIALOG_TITLE
            withFileFilter { vf -> vf.name.endsWith(".gguf", ignoreCase = true) }
        }
        FileChooser.chooseFile(descriptor, project, null) { vf ->
            val path = vf.path
            when {
                !vf.exists() -> Messages.showErrorDialog(project, Strings.LoadFromFile.errorNotExists(path), Strings.LoadFromFile.ERROR_NOT_EXISTS_TITLE)
                !vf.name.lowercase().endsWith(".gguf") -> Messages.showErrorDialog(project, Strings.LoadFromFile.errorNotGguf(vf.name), Strings.LoadFromFile.ERROR_UNSUPPORTED_TITLE)
                else -> {
                    service<DownloadedModelService>().registerLocalModel(path)
                    service<LlamatikAppSettings>().data().modelPath = path
                    Messages.showInfoMessage(project, Strings.LoadFromFile.successMsg(vf.name, path), Strings.LoadFromFile.SUCCESS_TITLE)
                    onModelSelected(path)
                }
            }
        }
    }
}
