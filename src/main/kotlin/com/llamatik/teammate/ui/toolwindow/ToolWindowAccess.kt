package com.llamatik.teammate.ui.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindowManager

object ToolWindowAccess {
    fun focus(project: Project, prefill: String? = null) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Llamatik Code") ?: return
        toolWindow.activate {
            val panel = toolWindow.contentManager.getContent(0)?.component as? LlamatikToolWindowPanel ?: return@activate
            prefill?.let(panel::focusInputWithPrefill)
        }
    }
}
