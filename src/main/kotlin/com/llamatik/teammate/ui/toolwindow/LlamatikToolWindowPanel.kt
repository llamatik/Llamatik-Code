package com.llamatik.teammate.ui.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.agent.AgentOrchestrator
import com.llamatik.teammate.agent.BuildVerificationService
import com.llamatik.teammate.backend.GenerationHandle
import com.llamatik.teammate.diff.DiffPreviewService
import com.llamatik.teammate.licensing.TrialService
import com.llamatik.teammate.model.ChatMessage
import com.llamatik.teammate.model.ChatRole
import com.llamatik.teammate.model.EditProposal
import com.llamatik.teammate.model.PluginReadinessState
import com.llamatik.teammate.settings.LlamatikAppSettings
import com.llamatik.teammate.settings.LlamatikSettingsConfigurable
import com.llamatik.teammate.tools.ToolCallParser
import com.llamatik.teammate.ui.LlamatikTheme
import com.llamatik.teammate.ui.Strings
import com.llamatik.teammate.ui.chat.ChatMessagePanel
import com.llamatik.teammate.ui.chat.ChatSessionController
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.geom.RoundRectangle2D
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JToggleButton
import javax.swing.KeyStroke
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

class LlamatikToolWindowPanel(
    private val project: Project,
    @Suppress("UNUSED_PARAMETER") toolWindow: ToolWindow,
) : JPanel(BorderLayout()) {

    private enum class SurfaceMode {
        AGENT,
        ASK,
    }

    private val agent = project.service<AgentOrchestrator>()
    private val diffService = project.service<DiffPreviewService>()
    private val buildService = project.service<BuildVerificationService>()
    private val settings = service<LlamatikAppSettings>()
    private val trialService = service<TrialService>()
    private val stateController = PluginStateController()

    private val sessionController = ChatSessionController(project)
    private val pendingEdits = linkedMapOf<String, EditProposal>()

    private var isActivelyLoading = false
    private var currentHandle: GenerationHandle? = null
    private var currentMode = SurfaceMode.AGENT
    private var activeGenId = 0
    private var autoFixCount = 0
    private var isAutoFix = false

    companion object {
        private const val MAX_AUTO_FIX_ITERATIONS = 3
    }

    private val rootBackground = LlamatikTheme.background
    private val cardBackground = LlamatikTheme.surface
    private val accentBorder = LlamatikTheme.primary

    private val trialBannerPanel = JPanel(BorderLayout(8, 0)).apply {
        background = LlamatikTheme.warningBackground
        border = JBUI.Borders.compound(
            BorderFactory.createLineBorder(LlamatikTheme.warningBorder, 1, true),
            JBUI.Borders.empty(8, 12),
        )
        isVisible = false
    }
    private val trialBannerLabel = JBLabel()
    private val trialDismissBtn = JButton(Strings.App.DISMISS)

    private val errorBanner = ErrorBannerPanel()

    private val contentLayout = CardLayout()
    private val contentPanel = JPanel(contentLayout).apply {
        isOpaque = false
    }

    private val inputArea = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        isOpaque = true
        foreground = LlamatikTheme.onSurface
        background = rootBackground
        caretColor = LlamatikTheme.onSurface
        border = JBUI.Borders.empty(10, 12)
        font = font.deriveFont(Font.PLAIN, font.size2D + 1f)
        toolTipText = Strings.Composer.INPUT_TOOLTIP_DEFAULT
    }

    private val sendButton = JButton(AllIcons.Actions.Execute).apply {
        toolTipText = Strings.Composer.SEND_TOOLTIP
        preferredSize = Dimension(38, 32)
    }
    private val stopButton = JButton(AllIcons.Actions.Suspend).apply {
        isEnabled = false
        toolTipText = Strings.Composer.STOP_TOOLTIP
        preferredSize = Dimension(38, 32)
    }

    //private val contextButton = buildFooterPill(Strings.Composer.CONTEXT_PILL)
    //private val profileButton = buildFooterPill(Strings.Composer.PROFILE_PILL)
    private val settingsButton = buildFooterPill(AllIcons.General.Settings, Strings.Settings.PAGE_NAME)
    private val disclaimerLabel = JBLabel(Strings.App.DISCLAIMER).apply {
        foreground = LlamatikTheme.onSurfaceVariant
        font = font.deriveFont(Font.PLAIN, font.size2D - 1f)
        horizontalAlignment = SwingConstants.CENTER
    }

    private val chatTabPanel = ChatTabPanel(
        project = project,
        sessionController = sessionController,
        diffService = diffService,
        pendingEdits = pendingEdits,
        onApplyEdit = { applyEdit(it) },
        onSuggestionSelected = { text -> prefillComposer(text) },
    )

    private val agentTabPanel = AgentTabPanel(
        onSuggestionSelected = { text -> prefillComposer(text) },
        onApprove = {},
        onReject = {},
        onClear = { currentHandle?.cancel(); setGenerating(false) },
    )
/*
    private val titleLabel = JBLabel("Llamatik Code").apply {
        font = font.deriveFont(Font.BOLD, font.size2D + 1f)
        foreground = LlamatikTheme.onSurface
    }
    */

    private val selectedToggleBackground = LlamatikTheme.primary
    private val unselectedToggleBackground = LlamatikTheme.surfaceVariant

    private val agentToggle = buildModeToggle(Strings.Mode.AGENT)
    private val askToggle = buildModeToggle(Strings.Mode.ASK)

    init {
        background = rootBackground
        border = JBUI.Borders.empty()

        contentPanel.add(agentTabPanel, SurfaceMode.AGENT.name)
        contentPanel.add(chatTabPanel, SurfaceMode.ASK.name)

        add(buildTopSection(), BorderLayout.NORTH)
        add(contentPanel, BorderLayout.CENTER)
        add(buildComposer(), BorderLayout.SOUTH)

        agentToggle.isSelected = true
        bindActions()
        applyMode(SurfaceMode.AGENT)
        syncState()
    }

    fun focusInputWithPrefill(text: String) = prefillComposer(text)

    private fun buildTopSection(): JPanel {
        trialBannerLabel.foreground = LlamatikTheme.onSurface
        trialDismissBtn.addActionListener {
            settings.data().trialWarningDismissed = true
            trialBannerPanel.isVisible = false
        }

        trialDismissBtn.isContentAreaFilled = false
        trialDismissBtn.isFocusPainted = false
        trialDismissBtn.border = JBUI.Borders.empty(2, 6)

        trialBannerPanel.add(trialBannerLabel, BorderLayout.CENTER)
        trialBannerPanel.add(trialDismissBtn, BorderLayout.EAST)

        val modeGroup = ButtonGroup().apply {
            add(agentToggle)
            add(askToggle)
        }

        val segmented = JPanel(GridLayout(1, 3, 6, 0)).apply {
            isOpaque = false
            add(agentToggle)
            add(askToggle)
        }
/*
        val rightActions = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            isOpaque = false
            add(headerIconButton("+"))
            add(headerIconButton("◻"))
            add(headerIconButton("⋮"))
        }
*/
        val titleRow = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, 12, 8, 12)
            //add(titleLabel, BorderLayout.WEST)
            //add(rightActions, BorderLayout.EAST)
        }

        val modeRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 12, 10, 12)
            add(segmented, BorderLayout.WEST)
        }

        val header = JPanel(BorderLayout()).apply {
            background = rootBackground
            border = JBUI.Borders.customLine(LlamatikTheme.divider, 0, 0, 1, 0)
            add(titleRow, BorderLayout.NORTH)
            add(modeRow, BorderLayout.CENTER)
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = rootBackground
            add(trialBannerPanel, BorderLayout.NORTH)
            add(header, BorderLayout.CENTER)
            add(errorBanner, BorderLayout.SOUTH)
        }
    }

    private fun buildComposer(): JPanel {
        val arc = 16

        val inputScroll = JBScrollPane(inputArea).apply {
            border = JBUI.Borders.empty()
            viewport.border = null
            isOpaque = false
            background = cardBackground
            viewport.isOpaque = false
            viewport.background = cardBackground
            preferredSize = Dimension(100, 86)
        }

        inputArea.isOpaque = false
        inputArea.background = cardBackground

        val composerShell = object : JPanel(BorderLayout(0, 8)) {
            private val shape: Shape get() = RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc.toFloat(), arc.toFloat())
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = cardBackground
                g2.fill(shape)
                g2.dispose()
            }
            override fun paintBorder(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = accentBorder
                g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc.toFloat(), arc.toFloat()))
                g2.dispose()
            }
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, 10, 8, 10)
            add(inputScroll, BorderLayout.CENTER)
            add(buildComposerFooter(), BorderLayout.SOUTH)
        }

        return JPanel(BorderLayout()).apply {
            background = rootBackground
            border = JBUI.Borders.empty(10, 12, 8, 12)
            add(composerShell, BorderLayout.CENTER)
            add(disclaimerLabel, BorderLayout.SOUTH)
        }
    }

    private fun buildComposerFooter(): JPanel {
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            //add(contextButton)
        }

        val rightControls = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            isOpaque = false
            //add(profileButton)
            add(settingsButton)
            add(stopButton)
            add(sendButton)
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(left, BorderLayout.WEST)
            add(rightControls, BorderLayout.EAST)
        }
    }

    private fun bindActions() {
        sendButton.addActionListener { sendPrompt() }
        stopButton.addActionListener {
            currentHandle?.cancel()
            setGenerating(false)
        }

        agentToggle.addActionListener { applyMode(SurfaceMode.AGENT) }
        askToggle.addActionListener { applyMode(SurfaceMode.ASK) }

        inputArea.getInputMap(JComponent.WHEN_FOCUSED).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK),
            "send",
        )
        inputArea.actionMap.put("send", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                sendPrompt()
            }
        })
    }

    private fun applyMode(mode: SurfaceMode) {
        currentMode = mode
        agentToggle.isSelected = mode == SurfaceMode.AGENT
        askToggle.isSelected = mode == SurfaceMode.ASK

        when (mode) {
            SurfaceMode.AGENT -> {
                contentLayout.show(contentPanel, SurfaceMode.AGENT.name)
                inputArea.toolTipText = Strings.Composer.INPUT_TOOLTIP_AGENT
            }
            SurfaceMode.ASK -> {
                contentLayout.show(contentPanel, SurfaceMode.ASK.name)
                chatTabPanel.setEntryMode(ChatTabPanel.EntryMode.ASK)
                inputArea.toolTipText = Strings.Composer.INPUT_TOOLTIP_ASK
            }
        }
    }

    private fun prefillComposer(text: String) {
        inputArea.text = text
        inputArea.requestFocusInWindow()
        inputArea.caretPosition = inputArea.document.length
    }

    private fun sendPrompt() {
        if (!isAutoFix) autoFixCount = 0
        isAutoFix = false
        val prompt = inputArea.text.trim()
        if (prompt.isEmpty()) return

        val state = computeState()
        if (state == PluginReadinessState.NO_MODEL) {
            Messages.showInfoMessage(
                project,
                Strings.App.NO_MODEL_CONFIGURED_MSG,
                Strings.App.NO_MODEL_CONFIGURED_TITLE,
            )
            return
        }

        val isAgentMode = currentMode == SurfaceMode.AGENT
        if (isAgentMode && !trialService.isAgentAllowed()) {
            Messages.showErrorDialog(
                project,
                Strings.App.TRIAL_EXPIRED_MSG,
                Strings.App.NAME,
            )
            return
        }

        val submittedPrompt = prompt
        inputArea.text = ""
        setGenerating(true)

        if (isAgentMode) {
            val genId = agentTabPanel.startResponse(submittedPrompt)
            activeGenId = genId
            currentHandle = agent.submit(
                session = chatTabPanel.currentSession(),
                userPrompt = submittedPrompt,
                onToken = { token ->
                    SwingUtilities.invokeLater {
                        if (token.startsWith("\u0000STATUS:")) {
                            agentTabPanel.setStatus(token.removePrefix("\u0000STATUS:"))
                        } else {
                            agentTabPanel.appendAnswerToken(token, genId)
                        }
                    }
                },
                onThinkingToken = { token ->
                    SwingUtilities.invokeLater {
                        agentTabPanel.appendThinkingToken(token, genId)
                    }
                },
                onFinished = { msg, proposals ->
                    ApplicationManager.getApplication().invokeLater {
                        if (genId != activeGenId) return@invokeLater
                        proposals.forEach { pendingEdits[it.id] = it }
                        val clean = ToolCallParser.cleanForDisplay(msg.content)
                        // Track decisions across all proposals; verify only after the last one.
                        val appliedFiles = mutableListOf<String>()
                        var remaining = proposals.size
                        fun checkAllResolved() {
                            remaining--
                            if (remaining == 0 && appliedFiles.isNotEmpty()) {
                                startVerification(appliedFiles.toList())
                            }
                        }
                        agentTabPanel.showAnswer(
                            text = clean,
                            genId = genId,
                            proposals = proposals,
                            onViewDiff = { proposal -> diffService.showDiff(proposal) },
                            onApply = { proposal ->
                                diffService.showDiff(proposal)
                                diffService.applyProposal(proposal)
                                appliedFiles += proposal.filePath
                                checkAllResolved()
                            },
                            onRejectProposal = { checkAllResolved() },
                        )
                        setGenerating(false)
                    }
                },
                onError = { message ->
                    ApplicationManager.getApplication().invokeLater {
                        if (genId != activeGenId) return@invokeLater
                        agentTabPanel.showAnswer("Error: $message", genId = genId)
                        setGenerating(false)
                    }
                },
            )
            return
        }

        val session = chatTabPanel.currentSession()
        sessionController.appendMessage(session.id, ChatMessage(role = ChatRole.USER, content = submittedPrompt))
        chatTabPanel.rebuildTabs()

        val streamPanel = ChatMessagePanel(
            project = project,
            role = ChatRole.ASSISTANT,
            roleLabel = if (currentMode == SurfaceMode.ASK) "Ask" else "Assistant",
            initialContent = "",
        )
        chatTabPanel.appendStreamingPanel(streamPanel)

        currentHandle = agent.submit(
            session = session,
            userPrompt = submittedPrompt,
            onToken = { token ->
                SwingUtilities.invokeLater {
                    streamPanel.appendToken(token)
                }
            },
            onFinished = { msg, proposals ->
                ApplicationManager.getApplication().invokeLater {
                    proposals.forEach { pendingEdits[it.id] = it }
                    val clean = ToolCallParser.cleanForDisplay(msg.content)
                    sessionController.appendMessage(session.id, msg.copy(content = clean))
                    streamPanel.setContent(clean)
                    val firstProposal = proposals.firstOrNull()
                    if (firstProposal != null) {
                        streamPanel.attachEditProposal(
                            onApply = { applyEdit(firstProposal) },
                            onViewDiff = { diffService.showDiff(firstProposal) },
                        )
                    }
                    chatTabPanel.rebuildTabs()
                    setGenerating(false)
                }
            },
            onError = { message ->
                ApplicationManager.getApplication().invokeLater {
                    errorBanner.showError(message) { sendPrompt() }
                    setGenerating(false)
                }
            },
        )
    }

    private fun computeState(): PluginReadinessState =
        stateController.currentState(isActivelyLoading = isActivelyLoading, isIndexing = false)

    private fun setGenerating(generating: Boolean) {
        sendButton.isEnabled = !generating
        stopButton.isEnabled = generating
        inputArea.isEnabled = !generating || currentMode != SurfaceMode.AGENT
    }

    private fun syncState() {
        val state = computeState()
        val composerEnabled = state != PluginReadinessState.NO_MODEL

        inputArea.isEnabled = composerEnabled
        sendButton.isEnabled = composerEnabled

        if (!composerEnabled) {
            inputArea.toolTipText = Strings.Composer.INPUT_TOOLTIP_NO_MODEL
            sendButton.toolTipText = Strings.Composer.INPUT_TOOLTIP_NO_MODEL
        } else {
            inputArea.toolTipText = when (currentMode) {
                SurfaceMode.AGENT -> Strings.Composer.INPUT_TOOLTIP_AGENT
                SurfaceMode.ASK -> Strings.Composer.INPUT_TOOLTIP_ASK
            }
            sendButton.toolTipText = Strings.Composer.SEND_TOOLTIP
        }

        val trialState = trialService.trialState()
        if (trialState.isInWarningPeriod && !trialState.warningDismissed) {
            trialBannerLabel.text = Strings.App.trialDaysLeft(trialState.daysRemaining)
            trialBannerPanel.isVisible = true
        } else {
            trialBannerPanel.isVisible = false
        }

        if (!trialService.isTrialActive()) {
            agentTabPanel.showReadinessOverlay(state, trialExpired = true)
        } else if (state == PluginReadinessState.NO_MODEL || state == PluginReadinessState.LOADING_MODEL) {
            agentTabPanel.showReadinessOverlay(state, trialExpired = false)
        } else {
            agentTabPanel.hideReadinessOverlay()
        }
    }

    fun refreshState() {
        syncState()
    }

    private fun applyEdit(proposal: EditProposal) {
        diffService.showDiff(proposal)
        val ok = Messages.showYesNoDialog(
            project,
            Strings.EditDialog.applyMsg(proposal.filePath),
            Strings.EditDialog.APPLY_TITLE,
            null,
        )
        if (ok == Messages.YES) {
            diffService.applyProposal(proposal)
        }
    }

    private fun startVerification(appliedFiles: List<String>) {
        ApplicationManager.getApplication().invokeLater {
            agentTabPanel.showVerifying(Strings.Verification.BUILDING)
        }
        buildService.compile { success, errors ->
            if (success) {
                val testFiles = appliedFiles.filter { buildService.isTestFile(it) }
                if (testFiles.isNotEmpty()) {
                    val testClasses = testFiles.map { java.io.File(it).nameWithoutExtension }
                    ApplicationManager.getApplication().invokeLater {
                        agentTabPanel.showVerifying(Strings.Verification.RUNNING_TESTS)
                    }
                    buildService.runTests(testClasses) { testSuccess, output ->
                        ApplicationManager.getApplication().invokeLater {
                            if (testSuccess) {
                                agentTabPanel.showBuildSuccess(testsRan = true)
                            } else {
                                agentTabPanel.showBuildErrors(output) { autoFixErrors(output) }
                            }
                        }
                    }
                } else {
                    ApplicationManager.getApplication().invokeLater {
                        agentTabPanel.showBuildSuccess(testsRan = false)
                    }
                }
            } else {
                val errorText = errors.joinToString("\n")
                ApplicationManager.getApplication().invokeLater {
                    agentTabPanel.showBuildErrors(errorText) { autoFixErrors(errorText) }
                }
            }
        }
    }

    private fun autoFixErrors(errors: String) {
        autoFixCount++
        if (autoFixCount > MAX_AUTO_FIX_ITERATIONS) {
            ApplicationManager.getApplication().invokeLater {
                agentTabPanel.showAutoFixLimit()
            }
            return
        }
        val fixPrompt = "Fix the following build/test errors:\n$errors"
        isAutoFix = true
        ApplicationManager.getApplication().invokeLater {
            prefillComposer(fixPrompt)
            sendPrompt()
        }
    }

    private fun buildModeToggle(label: String): JToggleButton =
        object : JToggleButton() {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = if (isSelected) selectedToggleBackground else unselectedToggleBackground
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 20f, 20f))
                g2.dispose()
                super.paintComponent(g)
            }
        }.apply {
            text = "<html><span style='color:${LlamatikTheme.htmlOnSurface};'>$label</span></html>"
            isFocusPainted = false
            isContentAreaFilled = false
            isOpaque = false
            border = JBUI.Borders.empty(7, 14)
            background = unselectedToggleBackground
            addChangeListener {
                val labelColor = if (isSelected) LlamatikTheme.htmlOnPrimary else LlamatikTheme.htmlOnSurface
                text = "<html><span style='color:$labelColor;'>$label</span></html>"
                repaint()
            }
        }

    private fun buildFooterPill(text: String): JButton =
        JButton("<html><span style='color:${LlamatikTheme.htmlOnSurfaceVariant};'>$text</span></html>").apply {
            isFocusPainted = false
            isContentAreaFilled = false
            border = JBUI.Borders.compound(
                BorderFactory.createLineBorder(LlamatikTheme.outline, 1, true),
                JBUI.Borders.empty(5, 10),
            )
        }

    private fun buildFooterPill(icon: javax.swing.Icon, tooltip: String): JButton =
        JButton(icon).apply {
            toolTipText = tooltip
            isFocusPainted = false
            isContentAreaFilled = false
            preferredSize = Dimension(38, 32)
            border = JBUI.Borders.compound(
                BorderFactory.createLineBorder(LlamatikTheme.outline, 1, true),
                JBUI.Borders.empty(5, 10),
            )
            addActionListener {
                ShowSettingsUtil.getInstance().showSettingsDialog(
                    project,
                    LlamatikSettingsConfigurable::class.java,
                )
            }
        }

    private fun headerIconButton(text: String): JButton =
        JButton("<html><span style='color:${LlamatikTheme.htmlOnSurfaceVariant};'>$text</span></html>").apply {
            isFocusPainted = false
            isContentAreaFilled = false
            border = JBUI.Borders.empty(2, 6)
        }
}
