package com.llamatik.teammate.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.llamatik.teammate.licensing.LicenseChecker
import com.llamatik.teammate.licensing.TrialService
import com.intellij.ide.BrowserUtil
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
import javax.swing.JLabel
import javax.swing.JPanel

class LlamatikSettingsConfigurable : Configurable {
    private val settings by lazy { service<LlamatikAppSettings>() }
    private val downloadedModelService by lazy { service<DownloadedModelService>() }
    private val skillService by lazy { service<SkillService>() }

    private val onModelRegistered: () -> Unit = {
        ApplicationManager.getApplication().invokeLater {
            reloadModelsList()
            reloadMmprojsList()
        }
    }

    private lateinit var activeSkillsCountLabel: JBLabel

    // Active model path
    private lateinit var modelPathField: JBTextField

    // Active mmproj path
    private lateinit var mmprojPathField: JBTextField

    // Behaviour
    private lateinit var agentAutoApproveCheck: JBCheckBox
    private lateinit var agentAutoBuildCheck: JBCheckBox
    private lateinit var agentAutoFixCheck: JBCheckBox
    private lateinit var agentAggressiveModeCheck: JBCheckBox

    // Context / indexing
    private lateinit var ignoredDirectoriesArea: JBTextArea
    private lateinit var projectRulesArea: JBTextArea

    // Wrapper that stores the raw path but displays "filename  (parent dir)"
    private data class ModelEntry(val path: String) {
        val fileName: String = Path.of(path).fileName?.toString() ?: path
        val parentDir: String = Path.of(path).parent?.toString() ?: ""
        override fun toString() = "$fileName  ($parentDir)"
    }

    // Separate lists for models and mmprojs
    private lateinit var modelsListModel: DefaultListModel<ModelEntry>
    private lateinit var modelsList: JBList<ModelEntry>
    private lateinit var mmprojsListModel: DefaultListModel<ModelEntry>
    private lateinit var mprojsList: JBList<ModelEntry>

    override fun getDisplayName(): String = Strings.Settings.PAGE_NAME

    override fun createComponent(): JComponent {
        activeSkillsCountLabel = JBLabel("")
        modelPathField = JBTextField()
        mmprojPathField = JBTextField()
        agentAutoApproveCheck = JBCheckBox(Strings.Settings.AUTO_APPROVE)
        agentAutoBuildCheck = JBCheckBox(Strings.Settings.AUTO_BUILD)
        agentAutoFixCheck = JBCheckBox(Strings.Settings.AUTO_FIX)
        agentAggressiveModeCheck = JBCheckBox(Strings.Settings.AGGRESSIVE_MODE_SETTING).apply {
            toolTipText = com.llamatik.teammate.ui.Strings.Agent.AGGRESSIVE_MODE_TOOLTIP
        }
        ignoredDirectoriesArea = JBTextArea(3, 40)
        projectRulesArea = JBTextArea(4, 40)
        modelsListModel = DefaultListModel()
        modelsList = JBList(modelsListModel)
        mmprojsListModel = DefaultListModel()
        mprojsList = JBList(mmprojsListModel)

        downloadedModelService.addOnRegisteredListener(onModelRegistered)

        // ── Model section ─────────────────────────────────────────────────────
        val browseModelButton = JButton(Strings.Settings.BROWSE).apply {
            addActionListener {
                val descriptor = FileChooserDescriptor(true, false, false, false, false, false).apply {
                    title = Strings.Settings.FILE_CHOOSER_TITLE
                    withFileFilter { vf -> vf.name.endsWith(".gguf", ignoreCase = true) }
                }
                val project = ProjectManager.getInstance().openProjects.firstOrNull()
                FileChooser.chooseFile(descriptor, project, null) { vf ->
                    modelPathField.text = vf.path
                }
            }
        }

        val registerModelButton = JButton(Strings.Settings.REGISTER).apply {
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
                reloadModelsList()
                Messages.showInfoMessage(Strings.Settings.SUCCESS_REGISTERED_MSG, Strings.App.NAME)
            }
        }

        val useSelectedModelButton = JButton(Strings.Settings.USE_SELECTED).apply {
            toolTipText = Strings.Settings.USE_SELECTED_TOOLTIP
            addActionListener {
                val selected = modelsList.selectedValue
                if (selected == null) {
                    Messages.showInfoMessage(Strings.Settings.ERROR_NO_SELECTION_MSG, Strings.Settings.ERROR_NO_SELECTION_TITLE)
                    return@addActionListener
                }
                modelPathField.text = selected.path
            }
        }

        val deleteSelectedModelButton = JButton(Strings.Settings.DELETE_SELECTED).apply {
            toolTipText = Strings.Settings.DELETE_SELECTED_TOOLTIP
            addActionListener {
                val selected = modelsList.selectedValue
                if (selected == null) {
                    Messages.showInfoMessage(Strings.Settings.ERROR_NO_SELECTION_MSG, Strings.Settings.ERROR_NO_SELECTION_TITLE)
                    return@addActionListener
                }
                val confirm = Messages.showYesNoDialog(
                    Strings.Settings.confirmDeleteMsg(selected.fileName),
                    Strings.Settings.CONFIRM_DELETE_TITLE,
                    null,
                )
                if (confirm == Messages.YES) {
                    downloadedModelService.deleteModel(selected.path)
                    reloadModelsList()
                }
            }
        }

        val openModelLibraryButton = JButton(Strings.Settings.BROWSE_CATALOG)
        openModelLibraryButton.addActionListener {
            var catalogDialog: DialogWrapper? = null
            val onRequestUpgrade: () -> Unit = {
                catalogDialog?.close(DialogWrapper.CANCEL_EXIT_CODE)
                val settingsWindow = javax.swing.SwingUtilities.getWindowAncestor(openModelLibraryButton)
                ApplicationManager.getApplication().invokeLater({
                    settingsWindow?.dispose()
                    ApplicationManager.getApplication().invokeLater({
                        service<TrialService>().requestLicense(Strings.ModelCard.TIER_3_PAYWALL_TITLE)
                    }, ModalityState.nonModal())
                }, ModalityState.any())
            }
            catalogDialog = object : DialogWrapper(null, true) {
                init { title = Strings.Settings.MODEL_LIBRARY_TITLE; init() }
                override fun createCenterPanel(): JComponent =
                    ModelLibraryPanel(null, onRequestUpgrade).apply { preferredSize = Dimension(860, 640) }
                override fun createActions() = arrayOf(okAction)
            }
            catalogDialog.show()
        }

        val modelPathPanel = JPanel(BorderLayout(6, 0)).apply {
            add(modelPathField, BorderLayout.CENTER)
            add(JPanel().apply {
                add(browseModelButton)
                add(registerModelButton)
            }, BorderLayout.EAST)
        }

        val registeredModelsPanel = JPanel(BorderLayout(0, 4)).apply {
            add(JBScrollPane(modelsList).apply { preferredSize = Dimension(300, 90) }, BorderLayout.CENTER)
            add(JPanel().apply {
                add(useSelectedModelButton)
                add(deleteSelectedModelButton)
            }, BorderLayout.SOUTH)
        }

        // ── Mmproj section ────────────────────────────────────────────────────
        val browseMmprojButton = JButton(Strings.Settings.BROWSE).apply {
            addActionListener {
                val descriptor = FileChooserDescriptor(true, false, false, false, false, false).apply {
                    title = Strings.Multimodal.MMPROJ_PATH_LABEL
                    withFileFilter { vf -> vf.name.endsWith(".gguf", ignoreCase = true) }
                }
                val project = ProjectManager.getInstance().openProjects.firstOrNull()
                FileChooser.chooseFile(descriptor, project, null) { vf ->
                    mmprojPathField.text = vf.path
                }
            }
        }

        val registerMmprojButton = JButton(Strings.Settings.REGISTER).apply {
            toolTipText = Strings.Settings.REGISTER_MMPROJ_TOOLTIP
            addActionListener {
                val path = mmprojPathField.text.trim()
                if (path.isBlank()) {
                    Messages.showErrorDialog(Strings.Settings.ERROR_NO_PATH_MSG, Strings.Settings.ERROR_NO_PATH_TITLE)
                    return@addActionListener
                }
                val err = validateLocalModelPath(path)
                if (err != null) {
                    Messages.showErrorDialog(err, Strings.Settings.ERROR_INVALID_PATH_TITLE)
                    return@addActionListener
                }
                downloadedModelService.registerLocalMmproj(path)
                reloadMmprojsList()
                Messages.showInfoMessage(Strings.Settings.SUCCESS_REGISTERED_MMPROJ_MSG, Strings.App.NAME)
            }
        }

        val useSelectedMmprojButton = JButton(Strings.Settings.USE_SELECTED).apply {
            toolTipText = Strings.Settings.USE_SELECTED_MMPROJ_TOOLTIP
            addActionListener {
                val selected = mprojsList.selectedValue
                if (selected == null) {
                    Messages.showInfoMessage(Strings.Settings.ERROR_NO_SELECTION_MSG, Strings.Settings.ERROR_NO_SELECTION_TITLE)
                    return@addActionListener
                }
                mmprojPathField.text = selected.path
            }
        }

        val deleteSelectedMmprojButton = JButton(Strings.Settings.DELETE_SELECTED).apply {
            toolTipText = Strings.Settings.DELETE_SELECTED_TOOLTIP
            addActionListener {
                val selected = mprojsList.selectedValue
                if (selected == null) {
                    Messages.showInfoMessage(Strings.Settings.ERROR_NO_SELECTION_MSG, Strings.Settings.ERROR_NO_SELECTION_TITLE)
                    return@addActionListener
                }
                val confirm = Messages.showYesNoDialog(
                    Strings.Settings.confirmDeleteMsg(selected.fileName),
                    Strings.Settings.CONFIRM_DELETE_TITLE,
                    null,
                )
                if (confirm == Messages.YES) {
                    downloadedModelService.deleteMmproj(selected.path)
                    reloadMmprojsList()
                }
            }
        }

        val mmprojPathPanel = JPanel(BorderLayout(6, 0)).apply {
            add(mmprojPathField, BorderLayout.CENTER)
            add(JPanel().apply {
                add(browseMmprojButton)
                add(registerMmprojButton)
            }, BorderLayout.EAST)
        }

        val registeredMmprojsPanel = JPanel(BorderLayout(0, 4)).apply {
            add(JBScrollPane(mprojsList).apply { preferredSize = Dimension(300, 90) }, BorderLayout.CENTER)
            add(JPanel().apply {
                add(useSelectedMmprojButton)
                add(deleteSelectedMmprojButton)
            }, BorderLayout.SOUTH)
        }

        // ── Other ─────────────────────────────────────────────────────────────
        val openSkillLibraryButton = JButton(Strings.Skills.OPEN_LIBRARY).apply {
            addActionListener {
                SkillLibraryDialog(skillService, onChanged = { reloadActiveSkills() }).show()
            }
        }

        val resetOnboardingButton = JButton(Strings.Settings.RESET_ONBOARDING).apply {
            toolTipText = Strings.Settings.RESET_ONBOARDING_TOOLTIP
            addActionListener {
                settings.data().onboardingCompleted = false
                Messages.showInfoMessage(Strings.Settings.ONBOARDING_RESET_MSG, Strings.Settings.ONBOARDING_RESET_TITLE)
            }
        }

        val aboutButton = JButton(Strings.About.BUTTON).apply {
            addActionListener { showAboutDialog() }
        }

        reloadModelsList()
        reloadMmprojsList()
        reloadActiveSkills()

        val licensed = runCatching { LicenseChecker.isLicensed() == true }.getOrElse { false }
        if (!licensed) {
            agentAutoApproveCheck.isEnabled = false
            agentAutoBuildCheck.isEnabled = false
            agentAutoFixCheck.isEnabled = false
            agentAggressiveModeCheck.isEnabled = false
        }

        return FormBuilder.createFormBuilder()
            .addComponent(openModelLibraryButton)
            .addSeparator()
            .addLabeledComponent(JBLabel(Strings.Settings.SECTION_ACTIVE_MODEL), modelPathPanel)
            .addTooltip(Strings.Settings.MODEL_PATH_TOOLTIP)
            .addLabeledComponent(JBLabel(Strings.Settings.SECTION_REGISTERED_MODELS), registeredModelsPanel)
            .addSeparator()
            .addLabeledComponent(JBLabel(Strings.Settings.SECTION_ACTIVE_MMPROJ), mmprojPathPanel)
            .addTooltip(Strings.Multimodal.MMPROJ_PATH_TOOLTIP)
            .addLabeledComponent(JBLabel(Strings.Settings.SECTION_REGISTERED_MMPROJS), registeredMmprojsPanel)
            .addSeparator()
            .addLabeledComponent(JBLabel(Strings.Settings.SECTION_BEHAVIOUR), buildRunOptionsPaywallLabel())
            .addComponent(agentAutoApproveCheck)
            .addComponent(agentAutoBuildCheck)
            .addComponent(agentAutoFixCheck)
            .addComponent(agentAggressiveModeCheck)
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

    override fun disposeUIResources() {
        downloadedModelService.removeOnRegisteredListener(onModelRegistered)
    }

    override fun isModified(): Boolean {
        val s = settings.data()
        return modelPathField.text.trim() != s.modelPath ||
            mmprojPathField.text.trim() != s.mmprojPath ||
            agentAutoApproveCheck.isSelected != s.agentAutoApprove ||
            agentAutoBuildCheck.isSelected != s.agentAutoBuild ||
            agentAutoFixCheck.isSelected != s.agentAutoFix ||
            agentAggressiveModeCheck.isSelected != (s.agentExecutionMode == "AGGRESSIVE") ||
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
        s.mmprojPath = mmprojPathField.text.trim()
        s.agentAutoApprove = agentAutoApproveCheck.isSelected
        s.agentAutoBuild = agentAutoBuildCheck.isSelected
        s.agentAutoFix = agentAutoFixCheck.isSelected
        s.agentExecutionMode = if (agentAggressiveModeCheck.isSelected) "AGGRESSIVE" else "NORMAL"
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
        mmprojPathField.text = s.mmprojPath
        agentAutoApproveCheck.isSelected = s.agentAutoApprove
        agentAutoBuildCheck.isSelected = s.agentAutoBuild
        agentAutoFixCheck.isSelected = s.agentAutoFix
        agentAggressiveModeCheck.isSelected = (s.agentExecutionMode == "AGGRESSIVE")
        ignoredDirectoriesArea.text = s.ignoredDirectories
        projectRulesArea.text = s.projectRules
        reloadModelsList()
        reloadMmprojsList()
        reloadActiveSkills()
    }

    private fun reloadModelsList() {
        modelsListModel.removeAllElements()
        downloadedModelService.listDownloaded().forEach { modelsListModel.addElement(ModelEntry(it)) }
    }

    private fun reloadMmprojsList() {
        mmprojsListModel.removeAllElements()
        downloadedModelService.listDownloadedMmprojs().forEach { mmprojsListModel.addElement(ModelEntry(it)) }
    }

    private fun reloadActiveSkills() {
        val active = skillService.activeSkills()
        if (active.isEmpty()) {
            activeSkillsCountLabel.text = Strings.Skills.activeCount(0)
            activeSkillsCountLabel.toolTipText = null
        } else {
            activeSkillsCountLabel.text = active.joinToString(", ") { it.displayName }
            activeSkillsCountLabel.toolTipText = null
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

    private fun buildRunOptionsPaywallLabel(): JBLabel {
        val licensed = runCatching { LicenseChecker.isLicensed() == true }.getOrElse { false }
        return if (licensed) JBLabel("") else JBLabel(Strings.Agent.RUN_OPTIONS_PRO_REQUIRED).apply {
            foreground = com.llamatik.teammate.ui.LlamatikTheme.onSurfaceVariant
            font = font.deriveFont(Font.ITALIC, font.size2D - 1f)
        }
    }

    companion object {
        fun showAboutDialog() {
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
                    val logoUrl = LlamatikSettingsConfigurable::class.java.getResource("/icons/llamatik.svg")
                    if (logoUrl != null) {
                        val raw = ImageIcon(logoUrl)
                        val scaled = ImageIcon(raw.image.getScaledInstance(80, 80, java.awt.Image.SCALE_SMOOTH))
                        panel.add(JLabel(scaled), gbc)
                    }
                    panel.add(JLabel(Strings.About.TOOL_NAME).apply {
                        font = font.deriveFont(Font.BOLD, 18f)
                    }, gbc)
                    for (line in listOf(Strings.About.DEVELOPED_BY, Strings.About.POWERED_BY, Strings.About.COPYRIGHT)) {
                        panel.add(JLabel(line), gbc)
                    }
                    val linksPanel = JPanel().apply {
                        isOpaque = false
                        val discordButton = JButton(Strings.About.DISCORD).apply {
                            isBorderPainted = false
                            isContentAreaFilled = false
                            cursor = java.awt.Cursor(java.awt.Cursor.HAND_CURSOR)
                            foreground = java.awt.Color(0x5865F2)
                            addActionListener { BrowserUtil.browse(Strings.About.DISCORD_URL) }
                        }
                        val xButton = JButton(Strings.About.X_TWITTER).apply {
                            isBorderPainted = false
                            isContentAreaFilled = false
                            cursor = java.awt.Cursor(java.awt.Cursor.HAND_CURSOR)
                            addActionListener { BrowserUtil.browse(Strings.About.X_TWITTER_URL) }
                        }
                        val feedbackButton = JButton(Strings.ReviewFeedback.FEEDBACK_BUTTON).apply {
                            isBorderPainted = false
                            isContentAreaFilled = false
                            cursor = java.awt.Cursor(java.awt.Cursor.HAND_CURSOR)
                            addActionListener { BrowserUtil.browse(Strings.ReviewFeedback.FEEDBACK_URL) }
                        }
                        add(discordButton)
                        add(xButton)
                        add(feedbackButton)
                    }
                    panel.add(linksPanel, gbc)
                    return panel
                }
                override fun createActions() = arrayOf(okAction)
            }
            dialog.show()
        }
    }
}
