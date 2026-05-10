package com.llamatik.teammate.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.llamatik.teammate.ui.Strings
import com.llamatik.teammate.ui.toolwindow.ToolWindowAccess
import javax.swing.JOptionPane

class SearchDocsWebAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val selectedText = e.getData(CommonDataKeys.EDITOR)?.selectionModel?.selectedText?.trim()

        val query = if (!selectedText.isNullOrBlank()) {
            selectedText
        } else {
            JOptionPane.showInputDialog(
                null,
                "Enter a search query:",
                Strings.ExternalKnowledge.SEARCH_DOCS_WEB_ACTION,
                JOptionPane.PLAIN_MESSAGE,
            )?.trim()
        }

        if (!query.isNullOrBlank()) {
            ToolWindowAccess.focus(project, "Search docs and web for: $query")
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }
}
