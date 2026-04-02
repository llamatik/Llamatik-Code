package com.llamatik.teammate.diff

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.llamatik.teammate.model.EditProposal

@Service(Service.Level.PROJECT)
class DiffPreviewService(private val project: Project) {
    fun showDiff(proposal: EditProposal) {
        val factory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest(
            "Llamatik Code Diff",
            factory.create(project, proposal.oldContent),
            factory.create(project, proposal.newContent),
            "Original",
            "Proposed",
        )
        DiffManager.getInstance().showDiff(project, request)
    }

    fun applyProposal(proposal: EditProposal) {
        val basePath = project.basePath ?: error("Project root not found")
        val target = java.nio.file.Path.of(basePath, proposal.filePath)
        if (java.nio.file.Files.isDirectory(target)) {
            com.intellij.openapi.ui.Messages.showErrorDialog(
                project,
                "Cannot apply edit: '${proposal.filePath}' is a directory, not a file.",
                "Invalid Edit Proposal",
            )
            return
        }
        ApplicationManager.getApplication().runWriteAction {
            target.parent?.let { java.nio.file.Files.createDirectories(it) }
            java.nio.file.Files.writeString(target, proposal.newContent)
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(target)?.refresh(false, false)
        }
    }
}
