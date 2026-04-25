package com.llamatik.teammate.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import javax.swing.JComponent

class InferenceSettingsConfigurable : Configurable {

    private val settings by lazy { service<LlamatikAppSettings>() }
    private lateinit var inferencePanel: InferenceSettingsPanel

    override fun getDisplayName(): String = "Inference"

    override fun createComponent(): JComponent {
        inferencePanel = InferenceSettingsPanel()
        return inferencePanel.panel
    }

    override fun isModified(): Boolean = inferencePanel.isModified(settings.data())

    override fun apply() {
        inferencePanel.applyTo(settings.data())
    }

    override fun reset() {
        inferencePanel.loadFrom(settings.data())
    }
}
