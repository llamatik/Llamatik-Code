package com.llamatik.teammate.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.skills.SkillService
import com.llamatik.teammate.ui.Strings
import com.llamatik.teammate.ui.toolwindow.LlamatikToolWindowPanel
import com.llamatik.teammate.ui.models.ModelLibraryPanel
import com.llamatik.teammate.ui.skills.SkillLibraryDialog
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.DefaultListModel
import javax.swing.ImageIcon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPanel

class LlamatikSettingsConfigurable : Configurable {
    private val settings = service<LlamatikAppSettings>()
    private val downloadedModelService = service<DownloadedModelService>()
    private val skillService = service<SkillService>()

    private val activeSkillsCountLabel = JBLabel("")

    // Model path
    private val modelPathField = JBTextField()

    // Behaviour
    private val agentAutoApproveCheck = JBCheckBox(Strings.Settings.AUTO_APPROVE)
    private val agentAutoBuildCheck = JBCheckBox(Strings.Settings.AUTO_BUILD)
    private val agentAutoFixCheck = JBCheckBox(Strings.Settings.AUTO_FIX)

    // Context / indexing
    private val ignoredDirectoriesArea = JBTextArea(3, 40)
    private val projectRulesArea = JBTextArea(4, 40)

    // Registered models list
    private val downloadedListModel = DefaultListModel<String>()
    private val downloadedList = JBList(downloadedListModel)

    override fun getDisplayName(): String = Strings.Settings.PAGE_NAME

    override fun createComponent(): JComponent {
        val browseModelButton = JButton(Strings.Settings.BROWSE).apply {
            addActionListener {
                val chooser = JFileChooser().apply {
                    fileSelectionMode = JFileChooser.FILES_ONLY
                    dialogTitle = Strings.Settings.FILE_CHOOSER_TITLE
                }
                if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                    modelPathField.text = chooser.selectedFile.absolutePath
                }
            }
        }

        val registerLocalButton = JButton(Strings.Settings.REGISTER).apply {
            toolTipText = Strings.Settings.REGISTER_TOOLTIP
            addActionListener {
                val path = modelPathField.text.trim()
                if (path.isBlank()) {
                    Messages.showErrorDialog(Strings.Settings.ERROR_NO_PATH_MSG, Strings.Settings.ERROR_NO_PATH_TITLE)
                    return@addActionListener
                }
                val err = validateLocalModelPath(path)
                if (err != null) {
                    Messages.showErrorDialog(err, Strings.Settings.ERROR_INVALID_PATH_TITLE)
                    return@addActionListener
                }
                downloadedModelService.registerLocalModel(path)
                reloadDownloadedModels()
                Messages.showInfoMessage(Strings.Settings.SUCCESS_REGISTERED_MSG, Strings.App.NAME)
            }
        }

        val useSelectedButton = JButton(Strings.Settings.USE_SELECTED).apply {
            toolTipText = Strings.Settings.USE_SELECTED_TOOLTIP
            addActionListener {
                val selected = downloadedList.selectedValue
                if (selected == null) {
                    Messages.showInfoMessage(Strings.Settings.ERROR_NO_SELECTION_MSG, Strings.Settings.ERROR_NO_SELECTION_TITLE)
                    return@addActionListener
                }
                modelPathField.text = selected
            }
        }

        val deleteSelectedButton = JButton(Strings.Settings.DELETE_SELECTED).apply {
            toolTipText = Strings.Settings.DELETE_SELECTED_TOOLTIP
            addActionListener {
                val selected = downloadedList.selectedValue
                if (selected == null) {
                    Messages.showInfoMessage(Strings.Settings.ERROR_NO_SELECTION_MSG, Strings.Settings.ERROR_NO_SELECTION_TITLE)
                    return@addActionListener
                }
                val confirm = Messages.showYesNoDialog(
                    Strings.Settings.confirmDeleteMsg(selected),
                    Strings.Settings.CONFIRM_DELETE_TITLE,
                    null,
                )
                if (confirm == Messages.YES) {
                    downloadedModelService.deleteModel(selected)
                    reloadDownloadedModels()
                }
            }
        }

        val openModelLibraryButton = JButton(Strings.Settings.BROWSE_CATALOG).apply {
            addActionListener {
                val dialog = object : DialogWrapper(null, true) {
                    init {
                        title = Strings.Settings.MODEL_LIBRARY_TITLE
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

        val openSkillLibraryButton = JButton(Strings.Skills.OPEN_LIBRARY).apply {
            addActionListener {
                SkillLibraryDialog(skillService, onChanged = { reloadActiveSkills() }).show()
            }
        }

        val resetOnboardingButton = JButton(Strings.Settings.RESET_ONBOARDING).apply {
            toolTipText = Strings.Settings.RESET_ONBOARDING_TOOLTIP
            addActionListener {
                settings.data().onboardingCompleted = false
                Messages.showInfoMessage(
                    Strings.Settings.ONBOARDING_RESET_MSG,
                    Strings.Settings.ONBOARDING_RESET_TITLE,
                )
            }
        }

        val aboutButton = JButton(Strings.About.BUTTON).apply {
            addActionListener {
                val dialog = object : DialogWrapper(null, true) {
                    init {
                        title = Strings.About.DIALOG_TITLE
                        init()
                    }
                    override fun createCenterPanel(): JComponent {
                        val panel = JPanel(GridBagLayout())
                        panel.border = JBUI.Borders.empty(16, 24)
                        val gbc = GridBagConstraints().apply {
                            gridx = 0; gridy = GridBagConstraints.RELATIVE
                            anchor = GridBagConstraints.CENTER
                            fill = GridBagConstraints.NONE
                            insets = JBUI.insets(4, 0)
                        }

                        // Logo
                        val logoUrl = LlamatikSettingsConfigurable::class.java
                            .getResource("/icons/llamatik.svg")
                        if (logoUrl != null) {
                            val raw = ImageIcon(logoUrl)
                            val scaled = ImageIcon(
                                raw.image.getScaledInstance(80, 80, java.awt.Image.SCALE_SMOOTH)
                            )
                            panel.add(JLabel(scaled), gbc)
                        }

                        // Title
                        panel.add(JLabel(Strings.About.TOOL_NAME).apply {
                            font = font.deriveFont(Font.BOLD, 18f)
                        }, gbc)

                        // Credits
                        for (line in listOf(
                            Strings.About.DEVELOPED_BY,
                            Strings.About.POWERED_BY,
                            Strings.About.COPYRIGHT,
                        )) {
                            panel.add(JLabel(line), gbc)
                        }

                        return panel
                    }
                    override fun createActions() = arrayOf(okAction)
                }
                dialog.show()
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
        reloadActiveSkills()

        return FormBuilder.createFormBuilder()
            .addSeparator()
            .addLabeledComponent(JBLabel(Strings.Settings.SECTION_ACTIVE_MODEL), modelPathPanel)
            .addTooltip(Strings.Settings.MODEL_PATH_TOOLTIP)
            .addSeparator()
            .addLabeledComponent(JBLabel(Strings.Settings.SECTION_REGISTERED_MODELS), registeredModelsPanel)
            .addComponent(openModelLibraryButton)
            .addSeparator()
            .addLabeledComponent(JBLabel(Strings.Settings.SECTION_BEHAVIOUR), JBLabel(""))
            .addComponent(agentAutoApproveCheck)
            .addComponent(agentAutoBuildCheck)
            .addComponent(agentAutoFixCheck)
            .addSeparator()
            .addLabeledComponent(JBLabel(Strings.Settings.SECTION_CONTEXT),
                JBLabel(Strings.Settings.FIELD_IGNORED_DIRS))
            .addComponent(JBScrollPane(ignoredDirectoriesArea))
            .addLabeledComponent(JBLabel(Strings.Settings.FIELD_INSTRUCTIONS), JBScrollPane(projectRulesArea))
            .addSeparator()
            .addLabeledComponent(JBLabel(Strings.Skills.SECTION_SETTINGS), activeSkillsCountLabel)
            .addComponent(openSkillLibraryButton)
            .addSeparator()
            .addComponent(resetOnboardingButton)
            .addComponent(aboutButton)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean {
        val s = settings.data()
        return modelPathField.text.trim() != s.modelPath ||
            agentAutoApproveCheck.isSelected != s.agentAutoApprove ||
            agentAutoBuildCheck.isSelected != s.agentAutoBuild ||
            agentAutoFixCheck.isSelected != s.agentAutoFix ||
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
        s.agentAutoApprove = agentAutoApproveCheck.isSelected
        s.agentAutoBuild = agentAutoBuildCheck.isSelected
        s.agentAutoFix = agentAutoFixCheck.isSelected
        s.ignoredDirectories = ignoredDirectoriesArea.text
        s.projectRules = projectRulesArea.text
        if (path.isNotBlank()) downloadedModelService.registerLocalModel(path)

        ProjectManager.getInstance().openProjects.forEach { project ->
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Llamatik Code")
            val panel = toolWindow?.contentManager?.getContent(0)?.component as? LlamatikToolWindowPanel
            panel?.refreshState()
        }
    }

    override fun reset() {
        val s = settings.data()
        modelPathField.text = s.modelPath
        agentAutoApproveCheck.isSelected = s.agentAutoApprove
        agentAutoBuildCheck.isSelected = s.agentAutoBuild
        agentAutoFixCheck.isSelected = s.agentAutoFix
        ignoredDirectoriesArea.text = s.ignoredDirectories
        projectRulesArea.text = s.projectRules
        reloadDownloadedModels()
        reloadActiveSkills()
    }

    private fun reloadDownloadedModels() {
        downloadedListModel.removeAllElements()
        downloadedModelService.listDownloaded().forEach(downloadedListModel::addElement)
    }

    private fun reloadActiveSkills() {
        val active = skillService.activeSkills()
        activeSkillsCountLabel.text = Strings.Skills.activeCount(active.size)
        if (active.isNotEmpty()) {
            activeSkillsCountLabel.toolTipText = active.joinToString(", ") { it.displayName }
        }
    }

    private fun validateLocalModelPath(path: String): String? {
        val nioPath = runCatching { Path.of(path) }.getOrNull()
            ?: return Strings.Settings.VALIDATION_NOT_VALID
        if (!Files.exists(nioPath)) return Strings.Settings.validationNotExists(path)
        if (!Files.isRegularFile(nioPath)) return Strings.Settings.validationNotFile(path)
        if (!path.lowercase().endsWith(".gguf")) return Strings.Settings.VALIDATION_NOT_GGUF
        return null
    }
}
