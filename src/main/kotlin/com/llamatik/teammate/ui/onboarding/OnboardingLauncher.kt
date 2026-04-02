package com.llamatik.teammate.ui.onboarding

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.llamatik.teammate.settings.LlamatikAppSettings

object OnboardingLauncher {
    fun shouldShow(project: Project): Boolean {
        return !service<LlamatikAppSettings>().data().onboardingCompleted
    }

    fun launch(project: Project) {
        OnboardingDialog(project).show()
    }

    fun markCompleted() {
        service<LlamatikAppSettings>().data().onboardingCompleted = true
    }
}
