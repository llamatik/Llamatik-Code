package com.llamatik.teammate.ui.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.llamatik.teammate.ui.onboarding.OnboardingLauncher

class LlamatikToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project): Boolean = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = LlamatikToolWindowPanel(project, toolWindow)
        val content = toolWindow.contentManager.factory.createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)

        // Show onboarding on first run, deferred to avoid blocking tool window creation
        if (OnboardingLauncher.shouldShow(project)) {
            ApplicationManager.getApplication().invokeLater {
                OnboardingLauncher.launch(project)
            }
        }
    }
}
