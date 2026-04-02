package com.llamatik.teammate.ui.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.agent.AgentOrchestrator
import com.llamatik.teammate.backend.GenerationHandle
import com.llamatik.teammate.diff.DiffPreviewService
import com.llamatik.teammate.licensing.TrialService
import com.llamatik.teammate.model.ChatMessage
import com.llamatik.teammate.model.ChatRole
import com.llamatik.teammate.model.EditProposal
import com.llamatik.teammate.model.PluginReadinessState
import com.llamatik.teammate.settings.LlamatikAppSettings
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
import java.awt.GridLayout
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
    private val settings = service<LlamatikAppSettings>()
    private val trialService = service<TrialService>()
    private val stateController = PluginStateController()

    private val sessionController = ChatSessionController(project)
    private val pendingEdits = linkedMapOf<String, EditProposal>()

    private var isActivelyLoading = false
    private var currentHandle: GenerationHandle? = null
    private var currentMode = SurfaceMode.AGENT

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

    private val sendButton = JButton("➤").apply {
        toolTipText = Strings.Composer.SEND_TOOLTIP
        preferredSize = Dimension(38, 32)
    }
    private val stopButton = JButton("■").apply {
        isEnabled = false
        toolTipText = Strings.Composer.STOP_TOOLTIP
        preferredSize = Dimension(38, 32)
    }

    private val contextButton = buildFooterPill(Strings.Composer.CONTEXT_PILL)
    private val profileButton = buildFooterPill(Strings.Composer.PROFILE_PILL)
    private val settingsButton = buildFooterPill(Strings.Composer.SETTINGS_PILL)
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
        val inputScroll = JBScrollPane(inputArea).apply {
            border = JBUI.Borders.empty()
            viewport.border = null
            isOpaque = true
            background = rootBackground
            viewport.isOpaque = true
            viewport.background = rootBackground
            preferredSize = Dimension(100, 86)
        }

        val composerShell = JPanel(BorderLayout(0, 8)).apply {
            background = cardBackground
            border = JBUI.Borders.compound(
                BorderFactory.createLineBorder(accentBorder, 1, true),
                JBUI.Borders.empty(10, 10, 8, 10),
            )
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
            add(contextButton)
        }

        val rightControls = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            isOpaque = false
            add(profileButton)
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
            agentTabPanel.startResponse(submittedPrompt)
            currentHandle = agent.submit(
                session = chatTabPanel.currentSession(),
                userPrompt = submittedPrompt,
                onToken = { token ->
                    SwingUtilities.invokeLater {
                        if (token.startsWith("\u0000STATUS:")) {
                            agentTabPanel.setStatus(token.removePrefix("\u0000STATUS:"))
                        } else {
                            agentTabPanel.appendAnswerToken(token)
                        }
                    }
                },
                onThinkingToken = { token ->
                    SwingUtilities.invokeLater {
                        agentTabPanel.appendThinkingToken(token)
                    }
                },
                onFinished = { msg, proposal ->
                    ApplicationManager.getApplication().invokeLater {
                        proposal?.let { pendingEdits[it.id] = it }
                        val clean = ToolCallParser.cleanForDisplay(msg.content)
                        agentTabPanel.showAnswer(
                            text = clean,
                            onApply = proposal?.let { { applyEdit(it) } },
                            onViewDiff = proposal?.let { { diffService.showDiff(it) } },
                        )
                        setGenerating(false)
                    }
                },
                onError = { message ->
                    ApplicationManager.getApplication().invokeLater {
                        agentTabPanel.showAnswer("Error: $message")
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
            onFinished = { msg, proposal ->
                ApplicationManager.getApplication().invokeLater {
                    proposal?.let { pendingEdits[it.id] = it }
                    val clean = ToolCallParser.cleanForDisplay(msg.content)
                    sessionController.appendMessage(session.id, msg.copy(content = clean))
                    streamPanel.setContent(clean)
                    if (proposal != null) {
                        streamPanel.attachEditProposal(
                            onApply = { applyEdit(proposal) },
                            onViewDiff = { diffService.showDiff(proposal) },
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
        }
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

    private fun buildModeToggle(label: String): JToggleButton =
        JToggleButton("<html><span style='color:${LlamatikTheme.htmlOnSurface};'>$label</span></html>").apply {
            isFocusPainted = false
            isContentAreaFilled = true
            isOpaque = true
            border = JBUI.Borders.empty(7, 14)
            background = unselectedToggleBackground
            addChangeListener {
                background = if (isSelected) selectedToggleBackground else unselectedToggleBackground
                val labelColor = if (isSelected) LlamatikTheme.htmlOnPrimary else LlamatikTheme.htmlOnSurface
                text = "<html><span style='color:$labelColor;'>$label</span></html>"
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

    private fun headerIconButton(text: String): JButton =
        JButton("<html><span style='color:${LlamatikTheme.htmlOnSurfaceVariant};'>$text</span></html>").apply {
            isFocusPainted = false
            isContentAreaFilled = false
            border = JBUI.Borders.empty(2, 6)
        }
}
