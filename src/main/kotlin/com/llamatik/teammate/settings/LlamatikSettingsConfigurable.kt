package com.llamatik.teammate.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.llamatik.teammate.ui.models.ModelLibraryPanel
import java.awt.BorderLayout
import java.awt.Dimension
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JPanel

class LlamatikSettingsConfigurable : Configurable {
    private val settings = service<LlamatikAppSettings>()
    private val downloadedModelService = service<DownloadedModelService>()

    // Model path
    private val modelPathField = JBTextField()

    // Behaviour
    private val loadModelOnStartupCheck = JBCheckBox("Load model on startup")
    private val agentAutoApproveCheck = JBCheckBox("Agent: auto-approve file edits without confirmation")

    // Context / indexing
    private val ignoredDirectoriesArea = JBTextArea(3, 40)
    private val projectRulesArea = JBTextArea(4, 40)

    // Registered models list
    private val downloadedListModel = DefaultListModel<String>()
    private val downloadedList = JBList(downloadedListModel)

    override fun getDisplayName(): String = "Llamatik Code"

    override fun createComponent(): JComponent {
        val browseModelButton = JButton("Browse…").apply {
            addActionListener {
                val chooser = JFileChooser().apply {
                    fileSelectionMode = JFileChooser.FILES_ONLY
                    dialogTitle = "Select local .gguf model file"
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    modelPathField.text = chooser.selectedFile.absolutePath
                }
            }
        }

        val registerLocalButton = JButton("Register").apply {
            toolTipText = "Register the path above as a known model"
            addActionListener {
                val path = modelPathField.text.trim()
                if (path.isBlank()) {
                    Messages.showErrorDialog("Enter a model path first.", "No Path")
                    return@addActionListener
                }
                val err = validateLocalModelPath(path)
                if (err != null) {
                    Messages.showErrorDialog(err, "Invalid Model Path")
                    return@addActionListener
                }
                downloadedModelService.registerLocalModel(path)
                reloadDownloadedModels()
                Messages.showInfoMessage("Model registered successfully.", "Llamatik Code")
            }
        }

        val useSelectedButton = JButton("Use selected").apply {
            toolTipText = "Set the selected model as active model path"
            addActionListener {
                val selected = downloadedList.selectedValue
                if (selected == null) {
                    Messages.showInfoMessage("Select a model from the list first.", "No Selection")
                    return@addActionListener
                }
                modelPathField.text = selected
            }
        }

        val deleteSelectedButton = JButton("Delete selected").apply {
            toolTipText = "Remove the selected model from the registry"
            addActionListener {
                val selected = downloadedList.selectedValue
                if (selected == null) {
                    Messages.showInfoMessage("Select a model from the list first.", "No Selection")
                    return@addActionListener
                }
                val confirm = Messages.showYesNoDialog(
                    "Remove \"$selected\" from the registry? The file will not be deleted from disk.",
                    "Confirm Remove",
                    null,
                )
                if (confirm == Messages.YES) {
                    downloadedModelService.deleteModel(selected)
                    reloadDownloadedModels()
                }
            }
        }

        val openModelLibraryButton = JButton("Browse model catalog…").apply {
            addActionListener {
                val dialog = object : DialogWrapper(null, true) {
                    init {
                        title = "Llamatik Code — Model Library"
                        init()
                    }
                    override fun createCenterPanel(): JComponent =
                        ModelLibraryPanel(null).apply {
                            preferredSize = Dimension(720, 520)
                        }
                    override fun createActions() = arrayOf(okAction)
                }
                dialog.show()
            }
        }

        val resetOnboardingButton = JButton("Reset onboarding").apply {
            toolTipText = "Shows the welcome dialog again next time the tool window is opened"
            addActionListener {
                settings.data().onboardingCompleted = false
                Messages.showInfoMessage(
                    "Onboarding will appear the next time you open the Llamatik Code panel.",
                    "Onboarding Reset",
                )
            }
        }

        val modelPathPanel = JPanel(BorderLayout(6, 0)).apply {
            add(modelPathField, BorderLayout.CENTER)
            add(JPanel().apply {
                add(browseModelButton)
                add(registerLocalButton)
            }, BorderLayout.EAST)
        }

        val listButtonsPanel = JPanel().apply {
            add(useSelectedButton)
            add(deleteSelectedButton)
        }
        val registeredModelsPanel = JPanel(BorderLayout(0, 4)).apply {
            add(JBScrollPane(downloadedList).apply { preferredSize = Dimension(300, 90) }, BorderLayout.CENTER)
            add(listButtonsPanel, BorderLayout.SOUTH)
        }

        reloadDownloadedModels()

        return FormBuilder.createFormBuilder()
            .addSeparator()
            .addLabeledComponent(JBLabel("<html><b>Active model path</b></html>"), modelPathPanel)
            .addTooltip("Path to the .gguf model file used for inference")
            .addSeparator()
            .addLabeledComponent(JBLabel("<html><b>Registered models</b></html>"), registeredModelsPanel)
            .addComponent(openModelLibraryButton)
            .addSeparator()
            .addLabeledComponent(JBLabel("<html><b>Behaviour</b></html>"), loadModelOnStartupCheck)
            .addComponent(agentAutoApproveCheck)
            .addSeparator()
            .addLabeledComponent(JBLabel("<html><b>Context</b></html>"),
                JBLabel("Ignored directories (comma-separated):"))
            .addComponent(JBScrollPane(ignoredDirectoriesArea))
            .addLabeledComponent(JBLabel("Project rules / instructions:"), JBScrollPane(projectRulesArea))
            .addSeparator()
            .addComponent(resetOnboardingButton)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean {
        val s = settings.data()
        return modelPathField.text.trim() != s.modelPath ||
            loadModelOnStartupCheck.isSelected != s.loadModelOnStartup ||
            agentAutoApproveCheck.isSelected != s.agentAutoApprove ||
            ignoredDirectoriesArea.text != s.ignoredDirectories ||
            projectRulesArea.text != s.projectRules
    }

    override fun apply() {
        val s = settings.data()
        val path = modelPathField.text.trim()
        if (path.isNotBlank()) {
            val err = validateLocalModelPath(path)
            if (err != null) throw com.intellij.openapi.options.ConfigurationException(err)
        }
        s.modelPath = path
        s.loadModelOnStartup = loadModelOnStartupCheck.isSelected
        s.agentAutoApprove = agentAutoApproveCheck.isSelected
        s.ignoredDirectories = ignoredDirectoriesArea.text
        s.projectRules = projectRulesArea.text
        if (path.isNotBlank()) downloadedModelService.registerLocalModel(path)
    }

    override fun reset() {
        val s = settings.data()
        modelPathField.text = s.modelPath
        loadModelOnStartupCheck.isSelected = s.loadModelOnStartup
        agentAutoApproveCheck.isSelected = s.agentAutoApprove
        ignoredDirectoriesArea.text = s.ignoredDirectories
        projectRulesArea.text = s.projectRules
        reloadDownloadedModels()
    }

    private fun reloadDownloadedModels() {
        downloadedListModel.removeAllElements()
        downloadedModelService.listDownloaded().forEach(downloadedListModel::addElement)
    }

    private fun validateLocalModelPath(path: String): String? {
        val nioPath = runCatching { Path.of(path) }.getOrNull()
            ?: return "Not a valid file path."
        if (!Files.exists(nioPath)) return "File does not exist: $path"
        if (!Files.isRegularFile(nioPath)) return "Path is not a file: $path"
        if (!path.lowercase().endsWith(".gguf")) return "File must end with .gguf"
        return null
    }
}
