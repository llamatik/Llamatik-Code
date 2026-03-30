package com.llamatik.teammate.llamatikteammate

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JFileChooser

class LlamatikSettingsConfigurable : Configurable {

    private var panel: JPanel? = null
    private val modelPathField = JBTextField()

    override fun getDisplayName(): String = "Llamatik Teammate"

    override fun createComponent(): JComponent {
        val root = JPanel(BorderLayout(8, 8))

        val browse = JButton("Browse…").apply {
            addActionListener {
                val chooser = JFileChooser().apply {
                    fileSelectionMode = JFileChooser.FILES_ONLY
                }
                if (chooser.showOpenDialog(root) == JFileChooser.APPROVE_OPTION) {
                    modelPathField.text = chooser.selectedFile.absolutePath
                }
            }
        }

        val top = JPanel(BorderLayout(8, 8))
        top.add(modelPathField, BorderLayout.CENTER)
        top.add(browse, BorderLayout.EAST)

        root.add(top, BorderLayout.NORTH)

        panel = root
        reset()
        return root
    }

    override fun isModified(): Boolean {
        val settings = service<LlamatikSettings>()
        return modelPathField.text.trim() != settings.modelPath()
    }

    override fun apply() {
        service<LlamatikSettings>().setModelPath(modelPathField.text.trim())
    }

    override fun reset() {
        modelPathField.text = service<LlamatikSettings>().modelPath()
    }

    override fun disposeUIResources() {
        panel = null
    }
}
