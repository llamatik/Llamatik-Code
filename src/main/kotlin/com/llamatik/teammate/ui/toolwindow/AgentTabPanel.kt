package com.llamatik.teammate.ui.toolwindow

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.model.AgentPhase
import com.llamatik.teammate.model.AgentTaskState
import com.llamatik.teammate.model.PluginReadinessState
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
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
) : JPanel(BorderLayout()) {

    private val rootBackground = JBColor(Color(0x111318), Color(0x111318))
    private val cardBackground = JBColor(Color(0x171A21), Color(0x171A21))
    private val panelStroke = JBColor(Color(0x2A3040), Color(0x2A3040))
    private val accent = JBColor(Color(0x5C6CFF), Color(0x5C6CFF))
    private val mutedText = JBColor(Color(0x9CA3AF), Color(0x9CA3AF))
    private val success = JBColor(Color(0x3DDC97), Color(0x3DDC97))

    private val cardLayout = java.awt.CardLayout()
    private val cardPanel = JPanel(cardLayout).apply {
        background = rootBackground
    }

    private val suggestions = listOf(
        "Find and resolve build errors in my project",
        "Suggest cleaner structure and apply changes with approval",
        "Analyze the current file and propose a safer refactor",
        "Generate tests, show the diff, and let me approve",
    )

    private val phaseList = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = rootBackground
        border = JBUI.Borders.empty(16, 16, 16, 16)
    }

    private val approveButton = JButton("Approve all changes")
    private val rejectButton = JButton("Reject")

    private val statusPill = JBLabel("").apply {
        isVisible = false
        foreground = JBColor.WHITE
        border = JBUI.Borders.compound(
            BorderFactory.createLineBorder(panelStroke, 1, true),
            JBUI.Borders.empty(4, 10),
        )
    }

    private val thinkToggleButton = JButton("▶ Thinking").apply {
        isFocusPainted = false
        isContentAreaFilled = false
        isBorderPainted = false
        foreground = mutedText
        horizontalAlignment = JButton.LEFT
    }

    private val thinkTextArea = JBTextArea("").apply {
        isEditable = false
        isOpaque = true
        lineWrap = true
        wrapStyleWord = true
        background = cardBackground
        foreground = mutedText
        border = JBUI.Borders.empty(10, 12)
        font = font.deriveFont(Font.ITALIC)
    }

    private val thinkScrollPane = JBScrollPane(thinkTextArea).apply {
        border = JBUI.Borders.compound(
            BorderFactory.createLineBorder(panelStroke, 1, true),
            JBUI.Borders.empty(),
        )
        isVisible = false
        preferredSize = Dimension(0, 120)
        viewport.background = cardBackground
    }

    private val thinkSection = JPanel(BorderLayout(0, 6)).apply {
        isOpaque = false
        isVisible = false
        border = JBUI.Borders.emptyBottom(10)
        add(thinkToggleButton, BorderLayout.NORTH)
        add(thinkScrollPane, BorderLayout.CENTER)
    }

    private val responseTextArea = JBTextArea("").apply {
        isEditable = false
        isOpaque = true
        lineWrap = true
        wrapStyleWord = true
        background = cardBackground
        foreground = JBColor.WHITE
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

    private val responseActionBar = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
        isOpaque = false
        border = JBUI.Borders.emptyTop(10)
    }

    private val backButton = JButton("← Back").apply {
        isFocusPainted = false
        isContentAreaFilled = false
        foreground = mutedText
        border = JBUI.Borders.empty(4, 0)
    }

    private val streamBuffer = StringBuilder()
    private var streamingBlocked = false

    init {
        background = rootBackground
        border = JBUI.Borders.empty()

        cardPanel.add(buildWelcomeCard(), CARD_WELCOME)
        cardPanel.add(buildTaskCard(), CARD_TASK)
        cardPanel.add(buildOverlayCard(""), CARD_OVERLAY)
        cardPanel.add(buildResponseCard(), CARD_RESPONSE)

        add(cardPanel, BorderLayout.CENTER)
        cardLayout.show(cardPanel, CARD_WELCOME)

        thinkToggleButton.addActionListener {
            val expanding = !thinkScrollPane.isVisible
            thinkScrollPane.isVisible = expanding
            thinkToggleButton.text = if (expanding) "▼ Thinking" else "▶ Thinking"
            thinkSection.revalidate()
        }

        backButton.addActionListener {
            cardLayout.show(cardPanel, CARD_WELCOME)
        }
    }

    private fun buildWelcomeCard(): JPanel {
        val title = JBLabel("<html><span style='font-size:22pt; font-weight:700; color:#5C6CFF;'>Agent</span></html>")
        val subtitle = JBLabel("I can inspect the codebase, plan a fix, and apply changes with approval.").apply {
            foreground = JBColor.WHITE
            font = font.deriveFont(Font.BOLD, font.size2D + 4f)
            border = JBUI.Borders.emptyBottom(24)
        }

        val activityCard = JPanel(BorderLayout(0, 10)).apply {
            isOpaque = true
            background = cardBackground
            border = JBUI.Borders.compound(
                BorderFactory.createLineBorder(panelStroke, 1, true),
                JBUI.Borders.empty(14, 16),
            )
        }

        val activityTitle = JBLabel("Agent workflow").apply {
            foreground = JBColor.WHITE
            font = font.deriveFont(Font.BOLD, font.size2D + 1f)
        }
        val activityBody = JBLabel(
            "<html>Analyze files, call tools, stream progress, preview diffs, and wait for your approval before applying edits.</html>",
        ).apply {
            foreground = mutedText
        }

        activityCard.add(activityTitle, BorderLayout.NORTH)
        activityCard.add(activityBody, BorderLayout.CENTER)

        val promptTitle = JBLabel("Prompts to try").apply {
            foreground = JBColor.WHITE
            font = font.deriveFont(Font.BOLD, font.size2D + 1f)
            border = JBUI.Borders.empty(24, 0, 10, 0)
        }

        val promptGrid = JPanel(GridLayout(0, 1, 0, 8)).apply {
            isOpaque = false
        }
        suggestions.forEach { suggestion ->
            promptGrid.add(buildPromptCard(suggestion))
        }

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
            add(JBLabel("Agent task").apply {
                foreground = JBColor.WHITE
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
                        JBUI.Borders.empty(22, 22),
                    )
                    add(JLabel(message).apply {
                        foreground = JBColor.WHITE
                        horizontalAlignment = JLabel.CENTER
                    }, BorderLayout.CENTER)
                },
                BorderLayout.CENTER,
            )
            border = JBUI.Borders.empty(40, 20)
        }

    private fun buildResponseCard(): JPanel {
        val copyButton = JButton("Copy").apply {
            isFocusPainted = false
            addActionListener {
                CopyPasteManager.getInstance().setContents(StringSelection(responseTextArea.text))
            }
        }

        val topBar = JPanel(BorderLayout()).apply {
            background = rootBackground
            border = JBUI.Borders.empty(12, 16, 8, 16)
            add(backButton, BorderLayout.WEST)
            add(copyButton, BorderLayout.EAST)
        }

        val headerStack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(statusPill)
            add(thinkSection)
        }

        val content = JPanel(BorderLayout()).apply {
            background = rootBackground
            border = JBUI.Borders.empty(0, 16, 16, 16)
            add(headerStack, BorderLayout.NORTH)
            add(responseScrollPane, BorderLayout.CENTER)
            add(responseActionBar, BorderLayout.SOUTH)
        }

        return JPanel(BorderLayout()).apply {
            background = rootBackground
            add(topBar, BorderLayout.NORTH)
            add(content, BorderLayout.CENTER)
        }
    }

    private fun buildPromptCard(text: String): JButton =
        JButton("<html><span style='color:#FFFFFF;'>$text</span></html>").apply {
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

    fun startResponse(prompt: String) {
        responseTextArea.text = ""
        thinkTextArea.text = ""
        thinkSection.isVisible = false
        thinkScrollPane.isVisible = false
        thinkToggleButton.text = "▶ Thinking"
        responseActionBar.removeAll()
        statusPill.text = "Analyzing…"
        statusPill.isVisible = true
        streamBuffer.clear()
        streamingBlocked = false
        cardLayout.show(cardPanel, CARD_RESPONSE)
        cardPanel.revalidate()
        cardPanel.repaint()
    }

    fun setStatus(message: String) {
        statusPill.text = message
        statusPill.isVisible = true
    }

    fun appendThinkingToken(token: String) {
        thinkTextArea.append(token)
        if (!thinkSection.isVisible) {
            thinkSection.isVisible = true
        }
    }

    fun appendAnswerToken(token: String) {
        if (streamingBlocked) return

        streamBuffer.append(token)
        val buffer = streamBuffer.toString()

        val toolIdx = buffer.indexOf("<tool_call")
        val editIdx = buffer.indexOf("<edit_proposal")
        val markupStart = when {
            toolIdx >= 0 && editIdx >= 0 -> minOf(toolIdx, editIdx)
            toolIdx >= 0 -> toolIdx
            editIdx >= 0 -> editIdx
            else -> -1
        }

        if (markupStart >= 0) {
            val safe = buffer.substring(0, markupStart).trimEnd()
            if (safe.isNotEmpty()) {
                statusPill.isVisible = false
                responseTextArea.append(safe)
                responseTextArea.caretPosition = responseTextArea.document.length
            }
            streamingBlocked = true
            return
        }

        statusPill.isVisible = false
        responseTextArea.append(token)
        responseTextArea.caretPosition = responseTextArea.document.length
    }

    fun showAnswer(text: String, onApply: (() -> Unit)? = null, onViewDiff: (() -> Unit)? = null) {
        statusPill.isVisible = false
        responseTextArea.text = text
        responseTextArea.caretPosition = 0

        if (thinkTextArea.text.isBlank()) {
            thinkSection.isVisible = false
        } else {
            thinkScrollPane.isVisible = false
            thinkToggleButton.text = "▶ Thinking"
            thinkSection.revalidate()
        }

        responseActionBar.removeAll()
        if (onViewDiff != null) {
            responseActionBar.add(JButton("View Diff").apply { addActionListener { onViewDiff() } })
        }
        if (onApply != null) {
            responseActionBar.add(JButton("Apply Edit").apply { addActionListener { onApply() } })
        }
        responseActionBar.revalidate()
        responseActionBar.repaint()
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
                JBUI.Borders.empty(14, 14),
            )
        }

        taskCard.add(JBLabel("<html><b>Task</b><br/>${taskState.prompt}</html>").apply {
            foreground = JBColor.WHITE
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
            trialExpired -> "Trial expired — Agent requires a subscription."
            state == PluginReadinessState.NO_MODEL -> "Configure a model to use Agent mode."
            state == PluginReadinessState.LOADING_MODEL -> "Loading model…"
            else -> return
        }

        cardPanel.add(buildOverlayCard(message), CARD_OVERLAY)
        cardLayout.show(cardPanel, CARD_OVERLAY)
        cardPanel.revalidate()
        cardPanel.repaint()
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
