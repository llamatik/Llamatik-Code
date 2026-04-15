package com.llamatik.teammate.ui.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.components.JBLabel
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
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.BorderFactory
import javax.swing.ButtonGroup
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JToggleButton
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
        onSend = { text -> sendPromptFromTab(text, isAgent = false) },
        onStop = { currentHandle?.cancel(); setGenerating(false) },
        onSettingsClicked = {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, LlamatikSettingsConfigurable::class.java)
        },
    )

    private val agentTabPanel = AgentTabPanel(
        onSuggestionSelected = { text -> prefillComposer(text) },
        onApprove = {},
        onReject = {},
        onSend = { text -> sendPromptFromTab(text, isAgent = true) },
        onStop = { currentHandle?.cancel(); setGenerating(false) },
        onSettingsClicked = {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, LlamatikSettingsConfigurable::class.java)
        },
        onClear = { currentHandle?.cancel(); setGenerating(false) },
        isAutoApprove = { settings.data().agentAutoApprove },
        onAutoApproveChanged = { checked -> settings.data().agentAutoApprove = checked },
        isAutoBuild = { settings.data().agentAutoBuild },
        onAutoBuildChanged = { checked -> settings.data().agentAutoBuild = checked },
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
        add(buildDisclaimerStrip(), BorderLayout.SOUTH)

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

    private fun buildDisclaimerStrip(): JPanel =
        JPanel(BorderLayout()).apply {
            background = rootBackground
            border = JBUI.Borders.empty(0, 12, 6, 12)
            add(disclaimerLabel, BorderLayout.CENTER)
        }

    private fun bindActions() {
        agentToggle.addActionListener { applyMode(SurfaceMode.AGENT) }
        askToggle.addActionListener { applyMode(SurfaceMode.ASK) }
    }

    private fun applyMode(mode: SurfaceMode) {
        currentMode = mode
        agentToggle.isSelected = mode == SurfaceMode.AGENT
        askToggle.isSelected = mode == SurfaceMode.ASK

        when (mode) {
            SurfaceMode.AGENT -> {
                contentLayout.show(contentPanel, SurfaceMode.AGENT.name)
            }
            SurfaceMode.ASK -> {
                contentLayout.show(contentPanel, SurfaceMode.ASK.name)
                chatTabPanel.setEntryMode(ChatTabPanel.EntryMode.ASK)
            }
        }
        // Re-evaluate input lock: switching away from Agent while it generates should free input.
        setGenerating(isAgentGenerating)
    }

    private fun prefillComposer(text: String) {
        when (currentMode) {
            SurfaceMode.AGENT -> agentTabPanel.prefill(text)
            SurfaceMode.ASK -> chatTabPanel.prefill(text)
        }
    }

    private fun sendPromptFromTab(prompt: String, isAgent: Boolean) {
        if (!isAutoFix) autoFixCount = 0
        isAutoFix = false
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

        if (isAgent && !trialService.isAgentAllowed()) {
            Messages.showErrorDialog(
                project,
                Strings.App.TRIAL_EXPIRED_MSG,
                Strings.App.NAME,
            )
            return
        }

        val submittedPrompt = prompt
        if (isAgent) agentTabPanel.clearComposer() else chatTabPanel.clearComposer()
        setGenerating(true)

        if (isAgent) {
            val genId = agentTabPanel.startResponse(submittedPrompt)
            activeGenId = genId
            currentHandle = agent.submit(
                session = chatTabPanel.currentSession(),
                userPrompt = submittedPrompt,
                onToken = { token ->
                    SwingUtilities.invokeLater {
                        when {
                            token.startsWith("\u0000STATUS:") ->
                                agentTabPanel.setStatus(token.removePrefix("\u0000STATUS:"))
                            token.startsWith("\u0000TOOL:") -> {
                                val payload = token.removePrefix("\u0000TOOL:")
                                val sep = payload.indexOf(':')
                                val name = if (sep >= 0) payload.substring(0, sep) else payload
                                val arg = if (sep >= 0) payload.substring(sep + 1) else ""
                                agentTabPanel.addToolCall(name, arg)
                            }
                            else -> agentTabPanel.appendAnswerToken(token, genId)
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

                        if (settings.data().agentAutoApprove && proposals.isNotEmpty()) {
                            // Auto-apply: write every proposal immediately, no user confirmation.
                            val appliedFiles = proposals.mapNotNull { proposal ->
                                runCatching { diffService.applyProposal(proposal) }.fold(
                                    onSuccess = { proposal.filePath },
                                    onFailure = { null },
                                )
                            }
                            agentTabPanel.showAnswer(text = clean, genId = genId)
                            setGenerating(false)
                            if (appliedFiles.isNotEmpty() && settings.data().agentAutoBuild) {
                                startVerification(appliedFiles)
                            }
                            return@invokeLater
                        }

                        // Manual mode: show proposal cards and wait for per-file decisions.
                        val appliedFiles = mutableListOf<String>()
                        var remaining = proposals.size.coerceAtLeast(1)
                        fun checkAllResolved() {
                            remaining--
                            if (remaining == 0 && appliedFiles.isNotEmpty() && settings.data().agentAutoBuild) {
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
            roleLabel = "Assistant",
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
                    errorBanner.showError(message) {}
                    setGenerating(false)
                }
            },
        )
    }

    private fun computeState(): PluginReadinessState =
        stateController.currentState(isActivelyLoading = isActivelyLoading, isIndexing = false)

    private var isAgentGenerating = false

    private fun setGenerating(generating: Boolean) {
        isAgentGenerating = generating
        // Agent tab: lock while generating. Ask tab: always free (can send in parallel).
        agentTabPanel.setGenerating(generating)
        if (generating) chatTabPanel.setGenerating(false) // ensure Ask stays unlocked
    }

    private fun syncState() {
        val state = computeState()
        val composerEnabled = state != PluginReadinessState.NO_MODEL

        agentTabPanel.setComposerEnabled(composerEnabled)
        chatTabPanel.setComposerEnabled(composerEnabled)

        if (!composerEnabled) {
            agentTabPanel.setComposerTooltip(Strings.Composer.INPUT_TOOLTIP_NO_MODEL)
            chatTabPanel.setComposerTooltip(Strings.Composer.INPUT_TOOLTIP_NO_MODEL)
        } else {
            agentTabPanel.setComposerTooltip(Strings.Composer.INPUT_TOOLTIP_AGENT)
            chatTabPanel.setComposerTooltip(Strings.Composer.INPUT_TOOLTIP_ASK)
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
            sendPromptFromTab(fixPrompt, isAgent = true)
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

}
