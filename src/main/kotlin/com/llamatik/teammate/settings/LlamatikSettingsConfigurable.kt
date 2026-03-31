package com.llamatik.teammate.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import java.awt.BorderLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class LlamatikSettingsConfigurable : Configurable {
    private val settings = service<LlamatikAppSettings>()
    private val downloadedModelService = service<DownloadedModelService>()

    private val modelPathField = JBTextField()
    private val modelIdCombo = ComboBox(settings.predefinedModels().map { it.id }.toTypedArray())
    private val temperatureSpinner = JSpinner(SpinnerNumberModel(0.2, 0.0, 2.0, 0.1))
    private val maxTokensSpinner = JSpinner(SpinnerNumberModel(1024, 64, 32768, 64))
    private val contextSizeSpinner = JSpinner(SpinnerNumberModel(8192, 512, 131072, 512))
    private val maxFileSizeSpinner = JSpinner(SpinnerNumberModel(256, 16, 4096, 16))
    private val maxContextCharsSpinner = JSpinner(SpinnerNumberModel(40000, 1000, 500000, 1000))
    private val maxChangedFilesSpinner = JSpinner(SpinnerNumberModel(10, 1, 100, 1))
    private val localOnlyCheck = JBCheckBox("Enable local-only mode")
    private val autoApplyCheck = JBCheckBox("Allow auto-apply")
    private val ignoredDirectoriesArea = JBTextArea(4, 40)
    private val projectRulesArea = JBTextArea(5, 40)
    private val backendField = JBTextField()
    private val downloadedListModel = DefaultListModel<String>()
    private val downloadedList = JBList(downloadedListModel)

    override fun getDisplayName(): String = "Llamatik Code"

    override fun createComponent(): JComponent {
        val browseButton = JButton("Browse…").apply {
            addActionListener {
                val chooser = JFileChooser().apply { fileSelectionMode = JFileChooser.FILES_ONLY }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    modelPathField.text = chooser.selectedFile.absolutePath
                }
            }
        }
        val downloadButton = JButton("Download selected model").apply {
            addActionListener {
                val selectedId = modelIdCombo.selectedItem as? String ?: return@addActionListener
                downloadedModelService.downloadPredefined(ProjectManager.getInstance().defaultProject, selectedId) {
                    modelPathField.text = it
                    reloadDownloadedModels()
                }
            }
        }
        val registerLocalButton = JButton("Register local path").apply {
            addActionListener {
                downloadedModelService.registerLocalModel(modelPathField.text.trim())
                reloadDownloadedModels()
            }
        }
        val deleteOneButton = JButton("Delete selected").apply {
            addActionListener {
                downloadedList.selectedValue?.let(downloadedModelService::deleteModel)
                reloadDownloadedModels()
            }
        }
        val deleteAllButton = JButton("Delete all").apply {
            addActionListener {
                downloadedModelService.deleteAll()
                reloadDownloadedModels()
            }
        }

        val modelPathPanel = JPanel(BorderLayout(8, 8)).apply {
            add(modelPathField, BorderLayout.CENTER)
            add(JPanel().apply {
                add(browseButton)
                add(registerLocalButton)
            }, BorderLayout.EAST)
        }
        val downloadsPanel = JPanel(BorderLayout(8, 8)).apply {
            add(JBScrollPane(downloadedList), BorderLayout.CENTER)
            add(JPanel().apply {
                add(downloadButton)
                add(deleteOneButton)
                add(deleteAllButton)
            }, BorderLayout.SOUTH)
        }

        reloadDownloadedModels()
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Predefined model", modelIdCombo)
            .addLabeledComponent("Model path", modelPathPanel)
            .addLabeledComponent("Backend", backendField)
            .addLabeledComponent("Temperature", temperatureSpinner)
            .addLabeledComponent("Max tokens", maxTokensSpinner)
            .addLabeledComponent("Context size", contextSizeSpinner)
            .addLabeledComponent("Max file size (KB)", maxFileSizeSpinner)
            .addLabeledComponent("Max context chars", maxContextCharsSpinner)
            .addLabeledComponent("Max changed files", maxChangedFilesSpinner)
            .addComponent(localOnlyCheck)
            .addComponent(autoApplyCheck)
            .addLabeledComponent("Ignored directories (comma separated)", JBScrollPane(ignoredDirectoriesArea))
            .addLabeledComponent("Project rules / instructions", JBScrollPane(projectRulesArea))
            .addLabeledComponent("Downloaded models", downloadsPanel)
            .panel
    }

    override fun isModified(): Boolean {
        val state = settings.data()
        return modelPathField.text.trim() != state.modelPath ||
            (modelIdCombo.selectedItem as? String ?: "") != state.modelIdentifier ||
            (temperatureSpinner.value as Number).toDouble() != state.temperature ||
            (maxTokensSpinner.value as Number).toInt() != state.maxTokens ||
            (contextSizeSpinner.value as Number).toInt() != state.contextSize ||
            localOnlyCheck.isSelected != state.localOnly ||
            backendField.text.trim() != state.backendId ||
            ignoredDirectoriesArea.text != state.ignoredDirectories ||
            projectRulesArea.text != state.projectRules ||
            autoApplyCheck.isSelected != state.autoApply ||
            (maxFileSizeSpinner.value as Number).toInt() != state.maxFileSizeKb ||
            (maxContextCharsSpinner.value as Number).toInt() != state.maxContextChars ||
            (maxChangedFilesSpinner.value as Number).toInt() != state.maxChangedFiles
    }

    override fun apply() {
        val state = settings.data()
        state.modelPath = modelPathField.text.trim()
        state.modelIdentifier = modelIdCombo.selectedItem as? String ?: state.modelIdentifier
        state.temperature = (temperatureSpinner.value as Number).toDouble()
        state.maxTokens = (maxTokensSpinner.value as Number).toInt()
        state.contextSize = (contextSizeSpinner.value as Number).toInt()
        state.localOnly = localOnlyCheck.isSelected
        state.backendId = backendField.text.trim().ifBlank { "llamatik" }
        state.ignoredDirectories = ignoredDirectoriesArea.text
        state.projectRules = projectRulesArea.text
        state.autoApply = autoApplyCheck.isSelected
        state.maxFileSizeKb = (maxFileSizeSpinner.value as Number).toInt()
        state.maxContextChars = (maxContextCharsSpinner.value as Number).toInt()
        state.maxChangedFiles = (maxChangedFilesSpinner.value as Number).toInt()
    }

    override fun reset() {
        val state = settings.data()
        modelPathField.text = state.modelPath
        modelIdCombo.selectedItem = state.modelIdentifier
        temperatureSpinner.value = state.temperature
        maxTokensSpinner.value = state.maxTokens
        contextSizeSpinner.value = state.contextSize
        localOnlyCheck.isSelected = state.localOnly
        backendField.text = state.backendId
        ignoredDirectoriesArea.text = state.ignoredDirectories
        projectRulesArea.text = state.projectRules
        autoApplyCheck.isSelected = state.autoApply
        maxFileSizeSpinner.value = state.maxFileSizeKb
        maxContextCharsSpinner.value = state.maxContextChars
        maxChangedFilesSpinner.value = state.maxChangedFiles
        reloadDownloadedModels()
    }

    private fun reloadDownloadedModels() {
        downloadedListModel.removeAllElements()
        downloadedModelService.listDownloaded().forEach(downloadedListModel::addElement)
    }
}
