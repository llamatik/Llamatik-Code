package com.llamatik.teammate.ui.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.components.JBLabel
import com.llamatik.teammate.ui.LlamatikTheme
import com.llamatik.teammate.ui.Strings
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.model.AgentPhase
import com.llamatik.teammate.model.AgentTaskState
import com.llamatik.teammate.model.EditProposal
import com.llamatik.teammate.model.PluginReadinessState
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import java.awt.datatransfer.StringSelection
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

private const val CARD_WELCOME = "WELCOME"
private const val CARD_TASK = "TASK"
private const val CARD_OVERLAY = "OVERLAY"
private const val CARD_RESPONSE = "RESPONSE"

class AgentTabPanel(
    private val onSuggestionSelected: (String) -> Unit,
    private val onApprove: () -> Unit,
    private val onReject: () -> Unit,
    private val onClear: () -> Unit = {},
) : JPanel(BorderLayout()) {

    private val rootBackground = LlamatikTheme.background
    private val cardBackground = LlamatikTheme.surface
    private val panelStroke = LlamatikTheme.outline
    private val accent = LlamatikTheme.primary
    private val mutedText = LlamatikTheme.onSurfaceVariant
    private val success = LlamatikTheme.success

    private val cardLayout = java.awt.CardLayout()
    private val cardPanel = JPanel(cardLayout).apply {
        background = rootBackground
    }

    private val suggestions = Strings.Agent.SUGGESTIONS

    private val phaseList = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = rootBackground
        border = JBUI.Borders.empty(16)
    }

    private val approveButton = JButton(Strings.Agent.APPROVE)
    private val rejectButton = JButton(Strings.Agent.REJECT)

    private val statusPill = JBLabel("").apply {
        isVisible = false
        foreground = LlamatikTheme.onSurface
        border = JBUI.Borders.compound(
            BorderFactory.createLineBorder(panelStroke, 1, true),
            JBUI.Borders.empty(4, 10),
        )
    }

    private val responseTextArea = JBTextArea("").apply {
        isEditable = false
        isOpaque = true
        lineWrap = true
        wrapStyleWord = true
        background = cardBackground
        foreground = LlamatikTheme.onSurface
        border = JBUI.Borders.empty(12, 14)
        font = font.deriveFont(Font.PLAIN, font.size2D + 1f)
    }

    private val responseScrollPane = JBScrollPane(responseTextArea).apply {
        border = JBUI.Borders.compound(
            BorderFactory.createLineBorder(panelStroke, 1, true),
            JBUI.Borders.empty(),
        )
        viewport.background = cardBackground
    }

    private val proposalsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        isVisible = false
    }

    private val loadingSpinner = AsyncProcessIcon("agent-loading").apply {
        isVisible = false
    }

    private val spinnerPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 6)).apply {
        isOpaque = false
        isVisible = false
        add(loadingSpinner)
    }

    private val verifyStatusLabel = JBLabel("").apply {
        isVisible = false
        font = font.deriveFont(Font.BOLD)
        border = JBUI.Borders.emptyTop(8)
        alignmentX = LEFT_ALIGNMENT
    }

    private val verifyErrorArea = JBTextArea("").apply {
        isEditable = false
        isOpaque = true
        lineWrap = true
        wrapStyleWord = true
        background = cardBackground
        foreground = LlamatikTheme.onSurface
        border = JBUI.Borders.empty(6, 8)
        font = font.deriveFont(Font.PLAIN, font.size2D - 1f)
        rows = 3
    }

    private val verifyErrorScroll = JBScrollPane(verifyErrorArea).apply {
        isVisible = false
        border = BorderFactory.createLineBorder(panelStroke, 1, true)
        viewport.background = cardBackground
        alignmentX = LEFT_ALIGNMENT
    }

    private val autoFixButton = JButton(Strings.Verification.AUTO_FIX).apply {
        isVisible = false
        isFocusPainted = false
        background = LlamatikTheme.primary
        foreground = LlamatikTheme.onPrimary
        border = JBUI.Borders.compound(
            BorderFactory.createLineBorder(LlamatikTheme.primary, 1, true),
            JBUI.Borders.empty(4, 10),
        )
        alignmentX = LEFT_ALIGNMENT
    }

    private val verificationPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        isVisible = false
        add(verifyStatusLabel)
        add(javax.swing.Box.createVerticalStrut(4))
        add(verifyErrorScroll)
        add(javax.swing.Box.createVerticalStrut(4))
        add(autoFixButton)
    }

    private val backButton = JButton(Strings.Agent.BACK).apply {
        isFocusPainted = false
        isContentAreaFilled = false
        foreground = mutedText
        border = JBUI.Borders.empty(4, 0)
    }

    private val clearButton = JButton(AllIcons.Actions.GC).apply {
        toolTipText = Strings.Chat.CLEAR
        isFocusPainted = false
        isContentAreaFilled = true
        isOpaque = true
        background = LlamatikTheme.surface
        preferredSize = java.awt.Dimension(32, 32)
        border = JBUI.Borders.compound(
            BorderFactory.createLineBorder(LlamatikTheme.outline, 1, true),
            JBUI.Borders.empty(3, 5),
        )
    }

    private val streamBuffer = StringBuilder()
    private var streamingBlocked = false
    private val thinkBuffer = StringBuilder()
    private var thinkingBlocked = false
    private var currentGenerationId = 0

    // XML prefixes that must never reach the text area (shared by both paths)
    private val blockedPrefixes = listOf("<tool_call", "<edit_proposal")

    init {
        background = rootBackground
        border = JBUI.Borders.empty()

        cardPanel.add(buildWelcomeCard(), CARD_WELCOME)
        cardPanel.add(buildTaskCard(), CARD_TASK)
        cardPanel.add(buildOverlayCard(""), CARD_OVERLAY)
        cardPanel.add(buildResponseCard(), CARD_RESPONSE)

        add(cardPanel, BorderLayout.CENTER)
        cardLayout.show(cardPanel, CARD_WELCOME)

        backButton.addActionListener {
            cardLayout.show(cardPanel, CARD_WELCOME)
        }

        clearButton.addActionListener {
            onClear()
            cardLayout.show(cardPanel, CARD_WELCOME)
        }
    }

    private fun buildWelcomeCard(): JPanel {
        val title = JBLabel("<html><span style='font-size:22pt; font-weight:700; color:${LlamatikTheme.htmlPrimary};'>${Strings.Agent.HERO_TITLE}</span></html>")
        val subtitle = JBLabel(
            "<html><span style='color:${LlamatikTheme.htmlOnSurface};'>${Strings.Agent.HERO_SUBTITLE}</span></html>",
        ).apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 4f)
            border = JBUI.Borders.emptyBottom(24)
        }

        val activityCard = JPanel(BorderLayout(0, 10)).apply {
            isOpaque = true
            background = cardBackground
            border = JBUI.Borders.compound(
                BorderFactory.createLineBorder(accent, 1, true),
                JBUI.Borders.empty(14, 16),
            )
        }

        val activityTitle = JBLabel(
            "<html><span style='color:${LlamatikTheme.htmlOnSurface};'>${Strings.Agent.WORKFLOW_TITLE}</span></html>",
        ).apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 1f)
        }
        val activityBody = JBLabel(
            "<html><span style='color:${LlamatikTheme.htmlOnSurfaceVariant};'>${Strings.Agent.WORKFLOW_BODY}</span></html>",
        )

        activityCard.add(activityTitle, BorderLayout.NORTH)
        activityCard.add(activityBody, BorderLayout.CENTER)

        val promptTitle = JBLabel(
            "<html><span style='color:${LlamatikTheme.htmlOnSurface};'>${Strings.Agent.PROMPTS_TITLE}</span></html>",
        ).apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 1f)
            border = JBUI.Borders.empty(24, 0, 10, 0)
        }

        val promptGrid = JPanel(GridLayout(0, 1, 0, 8)).apply {
            isOpaque = false
        }
        suggestions.forEach { suggestion ->
            promptGrid.add(buildPromptCard(suggestion))
        }

        title.alignmentX = LEFT_ALIGNMENT
        subtitle.alignmentX = LEFT_ALIGNMENT
        activityCard.alignmentX = LEFT_ALIGNMENT
        promptTitle.alignmentX = LEFT_ALIGNMENT
        promptGrid.alignmentX = LEFT_ALIGNMENT

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = rootBackground
            border = JBUI.Borders.empty(26, 20, 20, 20)
            add(title)
            add(subtitle)
            add(activityCard)
            add(promptTitle)
            add(promptGrid)
        }

        return JPanel(BorderLayout()).apply {
            background = rootBackground
            add(content, BorderLayout.NORTH)
        }
    }

    private fun buildTaskCard(): JPanel {
        val header = JPanel(BorderLayout()).apply {
            background = rootBackground
            border = JBUI.Borders.empty(12, 16, 0, 16)
            add(JBLabel(Strings.Agent.TASK_HEADER).apply {
                foreground = LlamatikTheme.onSurface
                font = font.deriveFont(Font.BOLD, font.size2D + 2f)
            }, BorderLayout.WEST)
        }

        val approvalBar = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 10)).apply {
            background = rootBackground
            approveButton.addActionListener { onApprove() }
            rejectButton.addActionListener { onReject() }
            add(rejectButton)
            add(approveButton)
        }

        approveButton.isVisible = false
        rejectButton.isVisible = false

        val scrollPane = JBScrollPane(phaseList).apply {
            border = JBUI.Borders.empty()
            viewport.background = rootBackground
            background = rootBackground
        }

        return JPanel(BorderLayout()).apply {
            background = rootBackground
            add(header, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
            add(approvalBar, BorderLayout.SOUTH)
        }
    }

    private fun buildOverlayCard(message: String): JPanel =
        JPanel(BorderLayout()).apply {
            background = rootBackground
            add(
                JPanel(BorderLayout()).apply {
                    background = cardBackground
                    border = JBUI.Borders.compound(
                        BorderFactory.createLineBorder(panelStroke, 1, true),
                        JBUI.Borders.empty(22),
                    )
                    add(JLabel(message).apply {
                        foreground = LlamatikTheme.onSurface
                        horizontalAlignment = JLabel.CENTER
                    }, BorderLayout.CENTER)
                },
                BorderLayout.CENTER,
            )
            border = JBUI.Borders.empty(40, 20)
        }

    private fun buildResponseCard(): JPanel {
        val copyButton = JButton(AllIcons.Actions.Copy).apply {
            toolTipText = Strings.App.COPY
            isFocusPainted = false
            isContentAreaFilled = true
            isOpaque = true
            background = LlamatikTheme.surface
            preferredSize = java.awt.Dimension(32, 32)
            border = JBUI.Borders.compound(
                BorderFactory.createLineBorder(LlamatikTheme.outline, 1, true),
                JBUI.Borders.empty(3, 5),
            )
            addActionListener {
                CopyPasteManager.getInstance().setContents(StringSelection(responseTextArea.text))
            }
        }

        val rightButtons = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
            add(clearButton)
            add(copyButton)
        }

        val topBar = JPanel(BorderLayout()).apply {
            background = rootBackground
            border = JBUI.Borders.empty(12, 16, 8, 16)
            add(backButton, BorderLayout.WEST)
            add(rightButtons, BorderLayout.EAST)
        }

        val statusRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(statusPill)
        }

        val southStack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(spinnerPanel)
            add(proposalsPanel)
            add(verificationPanel)
        }

        val content = JPanel(BorderLayout()).apply {
            background = rootBackground
            border = JBUI.Borders.empty(0, 16, 16, 16)
            add(statusRow, BorderLayout.NORTH)
            add(responseScrollPane, BorderLayout.CENTER)
            add(southStack, BorderLayout.SOUTH)
        }

        return JPanel(BorderLayout()).apply {
            background = rootBackground
            add(topBar, BorderLayout.NORTH)
            add(content, BorderLayout.CENTER)
        }
    }

    private fun buildPromptCard(text: String): JButton =
        JButton("<html><span style='color:${LlamatikTheme.htmlOnSurface};'>$text</span></html>").apply {
            horizontalAlignment = JButton.LEFT
            isFocusPainted = false
            isContentAreaFilled = true
            isOpaque = true
            background = cardBackground
            border = JBUI.Borders.compound(
                BorderFactory.createLineBorder(accent, 1, true),
                JBUI.Borders.empty(10, 14),
            )
            addActionListener { onSuggestionSelected(text) }
        }

    fun startResponse(prompt: String): Int {
        currentGenerationId++
        responseTextArea.text = ""
        proposalsPanel.removeAll()
        proposalsPanel.isVisible = false
        hideVerification()
        statusPill.text = Strings.Agent.STATUS_ANALYZING
        statusPill.isVisible = true
        loadingSpinner.resume()
        loadingSpinner.isVisible = true
        spinnerPanel.isVisible = true
        streamBuffer.clear()
        streamingBlocked = false
        thinkBuffer.clear()
        thinkingBlocked = false
        cardLayout.show(cardPanel, CARD_RESPONSE)
        cardPanel.revalidate()
        cardPanel.repaint()
        return currentGenerationId
    }

    fun setStatus(message: String) {
        statusPill.text = message
        statusPill.isVisible = true
    }

    fun appendThinkingToken(token: String, genId: Int) {
        if (genId != currentGenerationId || thinkingBlocked) return
        statusPill.isVisible = false
        flushToPanel(token, thinkBuffer, isThinking = true)
    }

    fun appendAnswerToken(token: String, genId: Int) {
        if (genId != currentGenerationId || streamingBlocked) return
        statusPill.isVisible = false
        flushToPanel(token, streamBuffer, isThinking = false)
    }

    /**
     * Shared token-to-panel flushing with blocking for XML markup that must never reach the UI.
     * Thinking tokens are blocked from showing tool/proposal XML.
     * Answer tokens are additionally blocked from showing <think> tags.
     */
    private fun flushToPanel(token: String, buffer: StringBuilder, isThinking: Boolean) {
        val allBlocked = if (isThinking) blockedPrefixes
                         else blockedPrefixes + listOf("<think>", "<think ")

        buffer.append(token)
        val buf = buffer.toString()

        val blockAt = allBlocked.mapNotNull { p -> buf.indexOf(p).takeIf { it >= 0 } }.minOrNull()
        if (blockAt != null) {
            val safe = buf.substring(0, blockAt).trimEnd()
            if (safe.isNotEmpty()) {
                responseTextArea.append(safe)
                responseTextArea.caretPosition = responseTextArea.document.length
            }
            if (isThinking) thinkingBlocked = true else streamingBlocked = true
            return
        }

        val maxPrefixLen = allBlocked.maxOf { it.length }
        val tail = buf.takeLast(maxPrefixLen)
        val holdLen = (1..tail.length).reversed().firstOrNull { len ->
            val candidate = tail.takeLast(len)
            allBlocked.any { it.startsWith(candidate) }
        } ?: 0

        val safeEnd = buf.length - holdLen
        if (safeEnd > 0) {
            val safe = buf.substring(0, safeEnd)
            buffer.clear()
            buffer.append(buf.substring(safeEnd))
            responseTextArea.append(safe)
            responseTextArea.caretPosition = responseTextArea.document.length
        }
    }

    fun showAnswer(
        text: String,
        genId: Int,
        proposals: List<EditProposal> = emptyList(),
        onViewDiff: (EditProposal) -> Unit = {},
        onApply: (EditProposal) -> Unit = {},
        onRejectProposal: (EditProposal) -> Unit = {},
    ) {
        if (genId != currentGenerationId) return
        statusPill.isVisible = false
        loadingSpinner.suspend()
        loadingSpinner.isVisible = false
        spinnerPanel.isVisible = false
        // Keep whatever was streamed into the panel — only fall back to the cleaned text
        // if nothing was streamed (e.g. generation completed before any token was displayed).
        if (responseTextArea.text.isBlank() && text.isNotBlank()) {
            responseTextArea.text = text
            responseTextArea.caretPosition = 0
        }

        proposalsPanel.removeAll()
        if (proposals.isNotEmpty()) {
            val header = JBLabel(Strings.EditDialog.PROPOSED_CHANGES_HEADER).apply {
                foreground = LlamatikTheme.onSurface
                font = font.deriveFont(Font.BOLD, font.size2D + 1f)
                border = JBUI.Borders.empty(12, 0, 8, 0)
                alignmentX = LEFT_ALIGNMENT
            }
            proposalsPanel.add(header)
            proposals.forEach { proposal ->
                proposalsPanel.add(buildProposalCard(proposal, onViewDiff, onApply, onRejectProposal))
                proposalsPanel.add(javax.swing.Box.createVerticalStrut(6).also {
                    (it as javax.swing.JComponent).alignmentX = LEFT_ALIGNMENT
                })
            }
            proposalsPanel.isVisible = true
        } else {
            proposalsPanel.isVisible = false
        }
        proposalsPanel.revalidate()
        proposalsPanel.repaint()
        revalidate()
        repaint()
    }

    private fun buildProposalCard(
        proposal: EditProposal,
        onViewDiff: (EditProposal) -> Unit,
        onApply: (EditProposal) -> Unit,
        onReject: (EditProposal) -> Unit,
    ): JPanel {
        val card = JPanel(java.awt.BorderLayout(0, 6)).apply {
            isOpaque = true
            background = cardBackground
            border = JBUI.Borders.compound(
                BorderFactory.createLineBorder(panelStroke, 1, true),
                JBUI.Borders.empty(10, 12),
            )
            alignmentX = LEFT_ALIGNMENT
        }

        val fileLabel = JBLabel(
            "<html><b>${java.io.File(proposal.filePath).name}</b></html>"
        ).apply {
            foreground = LlamatikTheme.onSurface
        }

        val pathLabel = JBLabel(
            "<html><span style='color:${LlamatikTheme.htmlOnSurfaceVariant};'>${proposal.filePath}</span></html>"
        ).apply {
            border = JBUI.Borders.emptyTop(2)
        }

        val summaryLabel = JBLabel(
            "<html><span style='color:${LlamatikTheme.htmlOnSurfaceVariant};'>${proposal.summary}</span></html>"
        ).apply {
            border = JBUI.Borders.emptyTop(2)
        }

        val labelStack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(fileLabel)
            add(pathLabel)
            add(summaryLabel)
        }

        val statusLabel = JBLabel("").apply {
            isVisible = false
            font = font.deriveFont(Font.BOLD)
        }

        val diffButton = JButton(Strings.EditDialog.VIEW_DIFF).apply {
            isFocusPainted = false
            isContentAreaFilled = false
            border = JBUI.Borders.compound(
                BorderFactory.createLineBorder(panelStroke, 1, true),
                JBUI.Borders.empty(4, 8),
            )
            addActionListener { onViewDiff(proposal) }
        }

        // Declare rejectButton before acceptButton so acceptButton can reference it directly
        val rejectButton = JButton(Strings.EditDialog.REJECT).apply {
            isFocusPainted = false
            isContentAreaFilled = false
            foreground = mutedText
            border = JBUI.Borders.compound(
                BorderFactory.createLineBorder(panelStroke, 1, true),
                JBUI.Borders.empty(4, 8),
            )
        }

        val acceptButton = JButton(Strings.EditDialog.ACCEPT).apply {
            isFocusPainted = false
            background = LlamatikTheme.primary
            foreground = LlamatikTheme.onPrimary
            border = JBUI.Borders.compound(
                BorderFactory.createLineBorder(LlamatikTheme.primary, 1, true),
                JBUI.Borders.empty(4, 10),
            )
            addActionListener {
                onApply(proposal)
                statusLabel.text = Strings.EditDialog.ACCEPTED_LABEL
                statusLabel.foreground = LlamatikTheme.success
                statusLabel.isVisible = true
                diffButton.isEnabled = false
                rejectButton.isEnabled = false
                this.isEnabled = false
                card.revalidate()
                card.repaint()
            }
        }

        rejectButton.addActionListener {
            onReject(proposal)
            statusLabel.text = Strings.EditDialog.REJECTED_LABEL
            statusLabel.foreground = mutedText
            statusLabel.isVisible = true
            diffButton.isEnabled = false
            acceptButton.isEnabled = false
            rejectButton.isEnabled = false
            card.revalidate()
            card.repaint()
        }

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            isOpaque = false
            add(statusLabel)
            add(diffButton)
            add(rejectButton)
            add(acceptButton)
        }

        // Labels on top, action buttons below
        card.add(labelStack, java.awt.BorderLayout.NORTH)
        card.add(buttons, java.awt.BorderLayout.SOUTH)
        return card
    }

    fun updateState(taskState: AgentTaskState?) {
        if (taskState == null) {
            cardLayout.show(cardPanel, CARD_WELCOME)
            return
        }

        cardLayout.show(cardPanel, CARD_TASK)
        phaseList.removeAll()

        val taskCard = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = cardBackground
            border = JBUI.Borders.compound(
                BorderFactory.createLineBorder(panelStroke, 1, true),
                JBUI.Borders.empty(14),
            )
        }

        taskCard.add(JBLabel("<html><b>Task</b><br/>${taskState.prompt}</html>").apply {
            foreground = LlamatikTheme.onSurface
            border = JBUI.Borders.emptyBottom(12)
            alignmentX = LEFT_ALIGNMENT
        })

        AgentPhase.values().forEach { phase ->
            taskCard.add(buildPhaseRow(
                phase = phase,
                isCurrent = phase == taskState.currentPhase,
                isDone = phase in taskState.phaseHistory,
            ))
        }

        if (taskState.filesInvolved.isNotEmpty()) {
            taskCard.add(JBLabel("<html><br/><b>Files</b><br/>${taskState.filesInvolved.joinToString("<br/>")}</html>").apply {
                foreground = mutedText
                alignmentX = LEFT_ALIGNMENT
            })
        }

        phaseList.add(taskCard)

        approveButton.isVisible = taskState.waitingForApproval
        rejectButton.isVisible = taskState.waitingForApproval

        phaseList.revalidate()
        phaseList.repaint()
    }

    fun showReadinessOverlay(state: PluginReadinessState, trialExpired: Boolean) {
        val message = when {
            trialExpired -> Strings.Agent.OVERLAY_TRIAL_EXPIRED
            state == PluginReadinessState.NO_MODEL -> Strings.Agent.OVERLAY_NO_MODEL
            state == PluginReadinessState.LOADING_MODEL -> Strings.Agent.OVERLAY_LOADING_MODEL
            else -> return
        }

        cardPanel.add(buildOverlayCard(message), CARD_OVERLAY)
        cardLayout.show(cardPanel, CARD_OVERLAY)
        cardPanel.revalidate()
        cardPanel.repaint()
    }

    fun hideVerification() {
        verificationPanel.isVisible = false
    }

    fun showVerifying(message: String) {
        verifyStatusLabel.text = message
        verifyStatusLabel.foreground = mutedText
        verifyStatusLabel.isVisible = true
        verifyErrorScroll.isVisible = false
        autoFixButton.isVisible = false
        verificationPanel.isVisible = true
        verificationPanel.revalidate()
        verificationPanel.repaint()
        revalidate()
        repaint()
    }

    fun showBuildSuccess(testsRan: Boolean) {
        verifyStatusLabel.text = if (testsRan) Strings.Verification.TESTS_PASSED else Strings.Verification.BUILD_OK
        verifyStatusLabel.foreground = success
        verifyStatusLabel.isVisible = true
        verifyErrorScroll.isVisible = false
        autoFixButton.isVisible = false
        verificationPanel.isVisible = true
        verificationPanel.revalidate()
        verificationPanel.repaint()
        revalidate()
        repaint()
    }

    fun showBuildErrors(errors: String, onAutoFix: () -> Unit) {
        verifyStatusLabel.text = Strings.Verification.BUILD_FAILED
        verifyStatusLabel.foreground = LlamatikTheme.error
        verifyStatusLabel.isVisible = true
        verifyErrorArea.text = errors
        verifyErrorScroll.isVisible = true
        for (l in autoFixButton.actionListeners) autoFixButton.removeActionListener(l)
        autoFixButton.text = Strings.Verification.AUTO_FIX
        autoFixButton.addActionListener { onAutoFix() }
        autoFixButton.isVisible = true
        verificationPanel.isVisible = true
        verificationPanel.revalidate()
        verificationPanel.repaint()
        revalidate()
        repaint()
    }

    fun showAutoFixLimit() {
        verifyStatusLabel.text = Strings.Verification.RETRY_LIMIT
        verifyStatusLabel.foreground = mutedText
        verifyStatusLabel.isVisible = true
        autoFixButton.isVisible = false
        verificationPanel.isVisible = true
        verificationPanel.revalidate()
        verificationPanel.repaint()
        revalidate()
        repaint()
    }

    private fun buildPhaseRow(
        phase: AgentPhase,
        isCurrent: Boolean,
        isDone: Boolean,
    ): JPanel {
        val icon = when {
            isDone -> "✓"
            isCurrent -> "●"
            else -> "○"
        }

        val color = when {
            isDone -> success
            isCurrent -> accent
            else -> mutedText
        }

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 0)
            add(JLabel("$icon  ${phase.name.replace('_', ' ')}").apply {
                foreground = color
                if (isCurrent) {
                    font = font.deriveFont(Font.BOLD)
                }
            }, BorderLayout.WEST)
        }
    }
}
