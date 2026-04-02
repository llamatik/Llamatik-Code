package com.llamatik.teammate.ui.toolwindow

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.model.AgentPhase
import com.llamatik.teammate.model.AgentTaskState
import com.llamatik.teammate.model.PluginReadinessState
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
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

/**
 * Agent mode tab panel.
 *
 * Welcome view: greeting + suggestion cards (Gemini-style).
 * Task view: phase timeline, involved files, approval buttons.
 * Overlay: shown when trial expired or no model.
 */
class AgentTabPanel(
    private val onSuggestionSelected: (String) -> Unit,
    private val onApprove: () -> Unit,
    private val onReject: () -> Unit,
) : JPanel(BorderLayout()) {

    companion object {
        val SUGGESTIONS = listOf(
            "Suggest cleaner structure and apply changes with approval" to "Suggest cleaner structure and apply changes with approval",
            "Generate comprehensive test cases and create the file" to "Generate comprehensive test cases and create the file",
            "Analyse, identify root cause, and propose a fix" to "Analyse, identify root cause, and propose a fix",
            "Make the selected function robust against failures" to "Make the selected function robust against failures",
        )
    }

    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)

    private val phaseList = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(8)
    }
    private val approveButton = JButton("Approve all changes")
    private val rejectButton = JButton("Reject")

    // Response card components
    private val statusLabel = JBLabel("Analysing…").apply {
        font = font.deriveFont(Font.ITALIC)
        foreground = JBColor(Color(0x888888), Color(0x888888))
        border = JBUI.Borders.empty(0, 0, 8, 0)
    }
    private val responseTextArea = com.intellij.ui.components.JBTextArea("").apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(8)
        background = JBColor(Color(0xF7F7F7), Color(0x2B2D30))
    }
    private val responseActionBar = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0)).apply {
        isOpaque = false
    }
    private val responseScrollPane = JBScrollPane(responseTextArea).apply {
        border = BorderFactory.createLineBorder(JBColor.border(), 1, true)
    }
    private val backButton = JButton("← Back").apply {
        border = JBUI.Borders.empty(4, 8)
        isContentAreaFilled = false
        isFocusPainted = false
    }

    init {
        border = JBUI.Borders.empty(0)

        cardPanel.add(buildWelcomeCard(), CARD_WELCOME)
        cardPanel.add(buildTaskCard(), CARD_TASK)
        cardPanel.add(buildOverlayCard(""), CARD_OVERLAY)
        cardPanel.add(buildResponseCard(), CARD_RESPONSE)

        add(cardPanel, BorderLayout.CENTER)
        cardLayout.show(cardPanel, CARD_WELCOME)
    }

    // ── Card builders ─────────────────────────────────────────────────────────

    private fun buildWelcomeCard(): JPanel {
        val panel = JPanel(BorderLayout()).apply { isOpaque = false }
        panel.border = JBUI.Borders.empty(28, 24, 16, 24)

        val greeting = JBLabel(
            "<html><span style='font-size:18pt; font-weight:bold; color:#5B8AF0;'>Agent mode</span></html>",
        ).apply {
            border = JBUI.Borders.emptyBottom(4)
            alignmentX = LEFT_ALIGNMENT
        }

        val subtitle = JBLabel("I'll analyse, plan, propose changes, and apply with your approval.").apply {
            font = font.deriveFont(Font.PLAIN, font.size2D + 1f)
            border = JBUI.Borders.emptyBottom(24)
            alignmentX = LEFT_ALIGNMENT
        }

        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(greeting)
            add(subtitle)
        }

        val suggestionsTitle = JBLabel("Prompts to try").apply {
            font = font.deriveFont(Font.BOLD, font.size2D)
            border = JBUI.Borders.emptyBottom(10)
        }

        val grid = JPanel(GridLayout(0, 1, 0, 8)).apply { isOpaque = false }
        SUGGESTIONS.forEach { (short, desc) ->
            grid.add(suggestionCard(desc) { onSuggestionSelected(short) })
        }

        val body = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(suggestionsTitle, BorderLayout.NORTH)
            add(grid, BorderLayout.CENTER)
        }

        val outer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(header)
            add(body)
        }

        panel.add(outer, BorderLayout.NORTH)
        return panel
    }

    private fun buildTaskCard(): JPanel {
        val approvalBar = JPanel().apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(8)
            approveButton.addActionListener { onApprove() }
            rejectButton.addActionListener { onReject() }
            add(rejectButton)
            add(approveButton)
        }
        approveButton.isVisible = false
        rejectButton.isVisible = false

        return JPanel(BorderLayout()).apply {
            add(JBScrollPane(phaseList).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
            add(approvalBar, BorderLayout.SOUTH)
        }
    }

    private fun buildOverlayCard(message: String): JPanel =
        JPanel(BorderLayout()).apply {
            isOpaque = false
            add(JBLabel(message).apply {
                horizontalAlignment = JLabel.CENTER
                border = JBUI.Borders.empty(24)
            }, BorderLayout.CENTER)
        }

    private fun buildResponseCard(): JPanel {
        backButton.addActionListener {
            cardLayout.show(cardPanel, CARD_WELCOME)
        }

        val copyBtn = JButton("Copy").apply {
            addActionListener {
                CopyPasteManager.getInstance().setContents(StringSelection(responseTextArea.text))
            }
        }

        val topBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 8, 8, 8)
            add(backButton, BorderLayout.WEST)
            add(copyBtn, BorderLayout.EAST)
        }

        val content = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 12, 8, 12)
            add(statusLabel, BorderLayout.NORTH)
            add(responseScrollPane, BorderLayout.CENTER)
            add(responseActionBar, BorderLayout.SOUTH)
        }

        return JPanel(BorderLayout()).apply {
            add(topBar, BorderLayout.NORTH)
            add(content, BorderLayout.CENTER)
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Called when generation starts. Shows the response card with a status indicator. */
    fun startResponse(prompt: String) {
        responseTextArea.text = ""
        responseActionBar.removeAll()
        statusLabel.text = "Analysing…"
        statusLabel.isVisible = true
        cardLayout.show(cardPanel, CARD_RESPONSE)
        cardPanel.revalidate()
    }

    /** Called to update the status message during generation (e.g. "Calling tools…"). */
    fun setStatus(message: String) {
        statusLabel.text = message
    }

    /** Called when generation completes. Shows the clean answer text. */
    fun showAnswer(text: String, onApply: (() -> Unit)? = null, onViewDiff: (() -> Unit)? = null) {
        statusLabel.isVisible = false
        responseTextArea.text = text
        responseTextArea.caretPosition = 0
        responseActionBar.removeAll()
        if (onViewDiff != null) {
            responseActionBar.add(JButton("View Diff").apply { addActionListener { onViewDiff() } })
        }
        if (onApply != null) {
            responseActionBar.add(JButton("Apply Edit").apply { addActionListener { onApply() } })
        }
        responseActionBar.revalidate()
        responseScrollPane.verticalScrollBar.value = 0
        cardPanel.revalidate()
        cardPanel.repaint()
    }

    fun updateState(taskState: AgentTaskState?) {
        if (taskState == null) {
            cardLayout.show(cardPanel, CARD_WELCOME)
            return
        }
        cardLayout.show(cardPanel, CARD_TASK)
        phaseList.removeAll()

        val promptLabel = JBLabel("<html><b>Task:</b> ${taskState.prompt}</html>").apply {
            border = JBUI.Borders.emptyBottom(8)
            alignmentX = LEFT_ALIGNMENT
        }
        phaseList.add(promptLabel)

        AgentPhase.values().forEach { phase ->
            phaseList.add(phaseRow(phase, isCurrent = phase == taskState.currentPhase, isDone = phase in taskState.phaseHistory))
        }

        if (taskState.filesInvolved.isNotEmpty()) {
            phaseList.add(JBLabel("<html><b>Files:</b> ${taskState.filesInvolved.joinToString(", ")}</html>").apply {
                border = JBUI.Borders.emptyTop(8)
                alignmentX = LEFT_ALIGNMENT
            })
        }

        approveButton.isVisible = taskState.waitingForApproval
        rejectButton.isVisible = taskState.waitingForApproval

        phaseList.revalidate()
        phaseList.repaint()
    }

    fun showReadinessOverlay(state: PluginReadinessState, trialExpired: Boolean) {
        val message = when {
            trialExpired -> "Trial expired — Agent requires a subscription."
            state == PluginReadinessState.NO_MODEL -> "Configure a model to use Agent mode."
            state == PluginReadinessState.LOADING_MODEL -> "Loading model…"
            else -> null
        } ?: return

        // Replace overlay card with fresh one carrying the message
        cardPanel.remove(cardPanel.components.lastOrNull { true })
        cardPanel.add(buildOverlayCard(message), CARD_OVERLAY)
        cardLayout.show(cardPanel, CARD_OVERLAY)
        cardPanel.revalidate()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun phaseRow(phase: AgentPhase, isCurrent: Boolean, isDone: Boolean): JPanel {
        val icon = if (isDone) "✓" else if (isCurrent) "▶" else "○"
        val color: Color = when {
            isCurrent -> JBColor(Color(0x007AFF), Color(0x4DA3FF))
            isDone -> JBColor(Color(0x34C759), Color(0x30D158))
            else -> JBColor.GRAY
        }
        val label = JLabel("$icon  ${phase.name.replace('_', ' ')}").apply {
            foreground = color
            if (isCurrent) font = font.deriveFont(Font.BOLD)
        }
        return JPanel(java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 4, 2)).apply {
            isOpaque = false
            add(label)
        }
    }

    private fun suggestionCard(text: String, action: () -> Unit): JButton {
        return JButton("<html><span style='font-weight:normal;'>$text</span></html>").apply {
            horizontalAlignment = JButton.LEFT
            border = JBUI.Borders.compound(
                BorderFactory.createLineBorder(JBColor.border(), 1, true),
                JBUI.Borders.empty(10, 14),
            )
            isContentAreaFilled = false
            isFocusPainted = false
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            addActionListener { action() }
        }
    }
}
