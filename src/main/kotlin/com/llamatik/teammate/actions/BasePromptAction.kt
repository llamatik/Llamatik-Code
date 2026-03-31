package com.llamatik.teammate.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.llamatik.teammate.ui.toolwindow.ToolWindowAccess

abstract class BasePromptAction(private val promptBuilder: (Project, AnActionEvent) -> String) : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        ToolWindowAccess.focus(project, promptBuilder(project, e))
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    protected fun selectedText(e: AnActionEvent): String = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText.orEmpty()

    protected fun currentFilePath(project: Project): String = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()?.path.orEmpty()
}
