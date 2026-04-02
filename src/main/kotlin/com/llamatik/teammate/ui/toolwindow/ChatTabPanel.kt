package com.llamatik.teammate.ui.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.diff.DiffPreviewService
import com.llamatik.teammate.model.EditProposal
import com.llamatik.teammate.ui.chat.ChatMessagePanel
import com.llamatik.teammate.ui.chat.ChatSessionController
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

private const val CARD_WELCOME = "WELCOME"
private const val CARD_MESSAGES = "MESSAGES"

/**
 * Chat mode tab panel.
 *
 * Shows a Gemini-style greeting + suggestion cards when the session is empty,
 * and the message thread once a conversation has started.
 */
class ChatTabPanel(
    private val project: Project,
    val sessionController: ChatSessionController,
    private val diffService: DiffPreviewService,
    private val pendingEdits: LinkedHashMap<String, EditProposal>,
    private val onApplyEdit: (EditProposal) -> Unit,
    private val onSuggestionSelected: (String) -> Unit,
) : JPanel(BorderLayout()) {

    companion object {
        val SUGGESTIONS = listOf(
            "Walk me through what the highlighted code does" to "Walk me through what the highlighted code does",
            "Look for potential issues and edge cases" to "Look for potential issues and edge cases",
            "Propose cleaner ways to structure this code" to "Propose cleaner ways to structure this code",
            "Generate unit tests for the current file" to "Generate unit tests for the current file",
        )
    }

    val tabs = JBTabbedPane()
    private val newChatButton = JButton("+ New Chat")
    private val clearButton = JButton("Clear")

    init {
        border = JBUI.Borders.empty()
        val topBar = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
            isOpaque = false
            add(newChatButton)
            add(clearButton)
        }
        add(topBar, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)
        bindActions()
        rebuildTabs()
    }

    private fun bindActions() {
        newChatButton.addActionListener {
            val s = sessionController.addSession()
            rebuildTabs()
            tabs.selectedIndex = sessionController.sessions.indexOfFirst { it.id == s.id }
        }
        clearButton.addActionListener {
            val idx = tabs.selectedIndex.coerceAtLeast(0)
            sessionController.sessions.getOrNull(idx)?.let {
                sessionController.clearSession(it.id)
                rebuildTabs()
            }
        }
        tabs.addChangeListener {
            val idx = tabs.selectedIndex
            if (idx in 0 until sessionController.sessions.size) {
                sessionController.selectedSessionId = sessionController.sessions[idx].id
                sessionController.persist()
            }
        }
    }

    fun rebuildTabs() {
        val prevIndex = tabs.selectedIndex
        tabs.removeAll()

        sessionController.sessions.forEach { session ->
            val isEmpty = session.messages.isEmpty()

            // Card layout: welcome view ↔ message thread
            val cardLayout = CardLayout()
            val cardContainer = JPanel(cardLayout)

            // Welcome card
            cardContainer.add(buildWelcomeCard(), CARD_WELCOME)

            // Messages card
            val msgList = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.emptyBottom(8)
            }
            session.messages.forEach { message ->
                val proposal = message.editProposalId?.let { pendingEdits[it] }
                msgList.add(ChatMessagePanel(
                    project = project,
                    roleLabel = message.role.name.lowercase().replaceFirstChar { it.uppercase() },
                    initialContent = message.content,
                    onApplyEdit = proposal?.let { { onApplyEdit(it) } },
                    onViewDiff = proposal?.let { { diffService.showDiff(it) } },
                ))
            }
            cardContainer.add(JBScrollPane(msgList).apply { border = JBUI.Borders.empty() }, CARD_MESSAGES)

            cardLayout.show(cardContainer, if (isEmpty) CARD_WELCOME else CARD_MESSAGES)
            tabs.addTab(session.title, cardContainer)

            val tabIdx = tabs.tabCount - 1
            val tabLabel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                add(JLabel(session.title))
                add(JButton("×").apply {
                    isBorderPainted = false
                    isContentAreaFilled = false
                    isFocusPainted = false
                    toolTipText = "Close"
                    font = font.deriveFont(font.size2D - 1f)
                    addActionListener { closeTab(session.id) }
                })
            }
            tabs.setTabComponentAt(tabIdx, tabLabel)
        }

        val target = sessionController.sessions
            .indexOfFirst { it.id == sessionController.selectedSessionId }
            .takeIf { it >= 0 } ?: prevIndex.coerceAtLeast(0)
        if (tabs.tabCount > 0) tabs.selectedIndex = target.coerceAtMost(tabs.tabCount - 1)
    }

    private fun buildWelcomeCard(): JPanel {
        val panel = JPanel(BorderLayout()).apply { isOpaque = false }
        panel.border = JBUI.Borders.empty(28, 24, 16, 24)

        val greeting = JBLabel(
            "<html><span style='font-size:18pt; font-weight:bold; color:#5B8AF0;'>Hi, ${System.getProperty("user.name", "there")}</span></html>",
        ).apply {
            border = JBUI.Borders.emptyBottom(4)
            alignmentX = LEFT_ALIGNMENT
        }

        val subtitle = JBLabel("What can I help you build today?").apply {
            font = font.deriveFont(Font.PLAIN, font.size2D + 3f)
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
            alignmentX = LEFT_ALIGNMENT
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

    private fun closeTab(sessionId: String) {
        sessionController.removeSession(sessionId)
        rebuildTabs()
    }

    fun currentSession() =
        sessionController.sessions.getOrElse(tabs.selectedIndex.coerceAtLeast(0)) {
            sessionController.sessions.first()
        }

    fun activeScrollPane(): JBScrollPane? {
        val idx = tabs.selectedIndex
        if (idx < 0 || idx >= tabs.tabCount) return null
        val card = tabs.getComponentAt(idx) as? JPanel ?: return null
        // Find the JBScrollPane inside the MESSAGES card
        return card.components.filterIsInstance<JBScrollPane>().firstOrNull()
    }

    fun appendStreamingPanel(streamPanel: ChatMessagePanel) {
        val idx = tabs.selectedIndex
        if (idx < 0 || idx >= tabs.tabCount) return
        val card = tabs.getComponentAt(idx) as? JPanel ?: return

        // Switch to messages card
        (card.layout as? CardLayout)?.show(card, CARD_MESSAGES)

        val scroll = card.components.filterIsInstance<JBScrollPane>().firstOrNull() ?: return
        val container = scroll.viewport.view as? JPanel ?: return
        container.add(streamPanel)
        container.revalidate()
        // Scroll to bottom
        scroll.verticalScrollBar.value = scroll.verticalScrollBar.maximum
    }
}
