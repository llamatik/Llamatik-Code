package com.llamatik.teammate.ui.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
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
import com.llamatik.teammate.ui.chat.ChatMessagePanel
import com.llamatik.teammate.ui.chat.ChatSessionController
import com.llamatik.teammate.tools.ToolCallParser
import com.llamatik.teammate.ui.models.LoadModelFromFileAction
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

class LlamatikToolWindowPanel(
    private val project: Project,
    @Suppress("UNUSED_PARAMETER") toolWindow: ToolWindow,
) : JPanel(BorderLayout()) {

    private val agent = project.service<AgentOrchestrator>()
    private val diffService = project.service<DiffPreviewService>()
    private val settings = service<LlamatikAppSettings>()
    private val trialService = service<TrialService>()
    private val stateController = PluginStateController()

    private val sessionController = ChatSessionController(project)
    private val pendingEdits = linkedMapOf<String, EditProposal>()

    // State
    private var isActivelyLoading = false
    private var currentHandle: GenerationHandle? = null

    // Trial warning banner
    private val trialBannerPanel = JPanel(BorderLayout(8, 0)).apply {
        background = JBColor(Color(0xFFF3CD), Color(0x4A3A00))
        border = JBUI.Borders.empty(4, 12)
        isVisible = false
    }
    private val trialBannerLabel = JBLabel()
    private val trialDismissBtn = JButton("Dismiss")

    // Error banner
    private val errorBanner = ErrorBannerPanel()

    // Main tab switcher: Agent | Chat
    private val mainTabs = JBTabbedPane()

    // Composer (declared before tab panels so lambdas can capture inputArea)
    private val inputArea = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val sendButton = JButton("Send")
    private val stopButton = JButton("Stop").apply { isEnabled = false }
    private val modeLabel = JBLabel("Chat", JLabel.LEFT).apply {
        font = font.deriveFont(Font.BOLD, font.size2D - 1f)
        foreground = JBColor(Color(0x007AFF), Color(0x4DA3FF))
    }

    // Tab content panels
    private val agentTabPanel = AgentTabPanel(
        onSuggestionSelected = { text -> prefillComposer(text) },
        onApprove = {},
        onReject = {},
    )
    private val chatTabPanel = ChatTabPanel(
        project = project,
        sessionController = sessionController,
        diffService = diffService,
        pendingEdits = pendingEdits,
        onApplyEdit = { applyEdit(it) },
        onSuggestionSelected = { text -> prefillComposer(text) },
    )

    init {
        border = JBUI.Borders.empty(0)
        add(buildHeader(), BorderLayout.NORTH)
        add(buildCenter(), BorderLayout.CENTER)
        add(buildComposer(), BorderLayout.SOUTH)
        bindActions()
        syncState()
    }

    fun focusInputWithPrefill(text: String) = prefillComposer(text)

    // ── Layout ────────────────────────────────────────────────────────────────

    private fun buildHeader(): JPanel {
        trialBannerLabel.border = JBUI.Borders.emptyRight(8)
        trialDismissBtn.addActionListener {
            settings.data().trialWarningDismissed = true
            trialBannerPanel.isVisible = false
        }
        trialBannerPanel.add(trialBannerLabel, BorderLayout.CENTER)
        trialBannerPanel.add(trialDismissBtn, BorderLayout.EAST)

        return JPanel(BorderLayout()).apply {
            add(trialBannerPanel, BorderLayout.NORTH)
            add(errorBanner, BorderLayout.SOUTH)
        }
    }

    private fun buildCenter(): JPanel {
        mainTabs.addTab("Chat", chatTabPanel)
        mainTabs.addTab("Agent", agentTabPanel)
        return JPanel(BorderLayout()).apply {
            add(mainTabs, BorderLayout.CENTER)
        }
    }

    private fun buildComposer(): JPanel {
        val inputScroll = JBScrollPane(inputArea).apply {
            border = BorderFactory.createLineBorder(JBColor.border(), 1, true)
            minimumSize = Dimension(100, 60)
        }

        // Bottom meta row: mode indicator left, send/stop right
        val sendStop = JPanel().apply {
            isOpaque = false
            add(stopButton)
            add(sendButton)
        }
        val metaRow = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(4)
            add(modeLabel, BorderLayout.WEST)
            add(sendStop, BorderLayout.EAST)
        }

        return JPanel(BorderLayout(0, 0)).apply {
            border = JBUI.Borders.compound(
                BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()),
                JBUI.Borders.empty(8, 12, 8, 12),
            )
            add(inputScroll, BorderLayout.CENTER)
            add(metaRow, BorderLayout.SOUTH)
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private fun bindActions() {
        sendButton.addActionListener { sendPrompt() }
        stopButton.addActionListener {
            currentHandle?.cancel()
            setGenerating(false)
        }
        inputArea.getInputMap(WHEN_FOCUSED).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "send",
        )
        inputArea.actionMap.put("send", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) = sendPrompt()
        })
        mainTabs.addChangeListener { syncModeLabel() }
    }

    private fun prefillComposer(text: String) {
        inputArea.text = text
        inputArea.requestFocusInWindow()
    }

    // ── Send / generate ───────────────────────────────────────────────────────

    private fun sendPrompt() {
        val prompt = inputArea.text.trim()
        if (prompt.isEmpty()) return

        val state = computeState()
        if (state == PluginReadinessState.NO_MODEL) {
            Messages.showInfoMessage(
                project,
                "No model is configured. Go to Settings → Llamatik Code and set a model path.",
                "No model loaded",
            )
            return
        }

        val isAgentTab = mainTabs.selectedIndex == 1
        if (isAgentTab && !trialService.isAgentAllowed()) {
            Messages.showErrorDialog(project, "Trial expired — Agent requires a subscription.", "Llamatik Code")
            return
        }

        inputArea.text = ""
        setGenerating(true)

        if (isAgentTab) {
            agentTabPanel.startResponse(prompt)
            currentHandle = agent.submit(
                session = chatTabPanel.currentSession(),
                userPrompt = prompt,
                onToken = { token ->
                    if (token.startsWith("\u0000STATUS:")) {
                        val status = token.removePrefix("\u0000STATUS:")
                        SwingUtilities.invokeLater { agentTabPanel.setStatus(status) }
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
        } else {
            val streamPanel = ChatMessagePanel(project, "Assistant", "")
            val session = chatTabPanel.currentSession()
            sessionController.appendMessage(session.id, ChatMessage(role = ChatRole.USER, content = prompt))
            chatTabPanel.rebuildTabs()
            chatTabPanel.appendStreamingPanel(streamPanel)

            currentHandle = agent.submit(
                session = session,
                userPrompt = prompt,
                onToken = { token ->
                    SwingUtilities.invokeLater { streamPanel.appendToken(token) }
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
                            chatTabPanel.rebuildTabs()
                        }
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
    }

    // ── State ─────────────────────────────────────────────────────────────────

    private fun computeState(): PluginReadinessState =
        stateController.currentState(isActivelyLoading = isActivelyLoading, isIndexing = false)

    private fun setGenerating(generating: Boolean) {
        sendButton.isEnabled = !generating
        stopButton.isEnabled = generating
    }

    private fun syncState() {
        val state = computeState()
        val composerEnabled = state != PluginReadinessState.NO_MODEL
        inputArea.isEnabled = composerEnabled
        sendButton.isEnabled = composerEnabled
        if (!composerEnabled) {
            val tip = "Configure a model in Settings → Llamatik Code to start"
            inputArea.toolTipText = tip
            sendButton.toolTipText = tip
        }

        // Trial expired overlay on agent tab
        if (!trialService.isTrialActive()) {
            agentTabPanel.showReadinessOverlay(state, trialExpired = true)
        } else if (state == PluginReadinessState.NO_MODEL) {
            agentTabPanel.showReadinessOverlay(state, trialExpired = false)
        }

        // Trial warning banner
        val trialState = trialService.trialState()
        if (trialState.isInWarningPeriod && !trialState.warningDismissed) {
            trialBannerLabel.text =
                "${trialState.daysRemaining} days left in trial. Agent features disabled after expiry."
            trialBannerPanel.isVisible = true
        } else {
            trialBannerPanel.isVisible = false
        }

        syncModeLabel()
    }

    private fun syncModeLabel() {
        modeLabel.text = if (mainTabs.selectedIndex == 1) "Agent" else "Chat"
    }

    // ── Edit / diff ───────────────────────────────────────────────────────────

    private fun applyEdit(proposal: EditProposal) {
        diffService.showDiff(proposal)
        val ok = Messages.showYesNoDialog(
            project,
            "Apply proposed changes to ${proposal.filePath}?",
            "Apply Llamatik Edit",
            null,
        )
        if (ok == Messages.YES) diffService.applyProposal(proposal)
    }
}
