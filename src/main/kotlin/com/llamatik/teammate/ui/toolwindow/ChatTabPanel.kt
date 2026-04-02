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

class ChatTabPanel(
    private val project: Project,
    val sessionController: ChatSessionController,
    private val diffService: DiffPreviewService,
    private val pendingEdits: LinkedHashMap<String, EditProposal>,
    private val onApplyEdit: (EditProposal) -> Unit,
    private val onSuggestionSelected: (String) -> Unit,
) : JPanel(BorderLayout()) {

    enum class EntryMode {
        CHAT,
        ASK,
    }

    private val rootBackground = JBColor(Color(0x111318), Color(0x111318))
    private val cardBackground = JBColor(Color(0x171A21), Color(0x171A21))
    private val heroAccent = JBColor(Color(0x5C6CFF), Color(0x5C6CFF))
    private val heroSecondary = JBColor(Color(0x9C7CFF), Color(0x9C7CFF))
    private val mutedText = JBColor(Color(0xA0A6B2), Color(0xA0A6B2))

    private var entryMode = EntryMode.CHAT

    private val chatSuggestions = listOf(
        "Find and resolve build errors in my project",
        "Add documentation to my current file",
        "Extract all hardcoded strings from this class and move them into strings.xml",
        "Fix the warnings and errors in the editor",
    )

    private val askSuggestions = listOf(
        "Explain this file and summarize its responsibilities",
        "What could break in this implementation?",
        "How should I refactor this class safely?",
        "Which tests are missing here?",
    )

    val tabs = JBTabbedPane().apply {
        background = rootBackground
        foreground = JBColor.WHITE
    }

    private val newChatButton = toolbarButton("+ New Chat")
    private val clearButton = toolbarButton("Clear")

    init {
        background = rootBackground
        border = JBUI.Borders.empty()

        val topBar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 8)).apply {
            background = rootBackground
            border = JBUI.Borders.empty(0, 12, 0, 12)
            add(newChatButton)
            add(clearButton)
        }

        add(topBar, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)

        bindActions()
        rebuildTabs()
    }

    fun setEntryMode(mode: EntryMode) {
        entryMode = mode
        rebuildTabs()
    }

    private fun bindActions() {
        newChatButton.addActionListener {
            val newSession = sessionController.addSession()
            rebuildTabs()
            val index = sessionController.sessions.indexOfFirst { it.id == newSession.id }
            if (index >= 0) tabs.selectedIndex = index
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
        val previousSelectedId = sessionController.selectedSessionId
        tabs.removeAll()

        sessionController.sessions.forEach { session ->
            val cardLayout = CardLayout()
            val cardPanel = JPanel(cardLayout).apply {
                background = rootBackground
            }

            val welcomeCard = buildWelcomeCard()
            val messagesList = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                background = rootBackground
                border = JBUI.Borders.empty(12, 12, 12, 12)
            }

            session.messages.forEach { message ->
                val proposal = message.editProposalId?.let { pendingEdits[it] }
                messagesList.add(
                    ChatMessagePanel(
                        project = project,
                        role = message.role,
                        roleLabel = message.role.name.lowercase().replaceFirstChar { it.uppercase() },
                        initialContent = message.content,
                        onApplyEdit = proposal?.let { { onApplyEdit(it) } },
                        onViewDiff = proposal?.let { { diffService.showDiff(it) } },
                    ),
                )
            }

            val messagesScroll = JBScrollPane(messagesList).apply {
                border = JBUI.Borders.empty()
                viewport.background = rootBackground
                background = rootBackground
            }

            cardPanel.add(welcomeCard, CARD_WELCOME)
            cardPanel.add(messagesScroll, CARD_MESSAGES)

            cardLayout.show(cardPanel, if (session.messages.isEmpty()) CARD_WELCOME else CARD_MESSAGES)

            tabs.addTab(session.title, cardPanel)

            val tabIndex = tabs.tabCount - 1
            tabs.setTabComponentAt(tabIndex, buildTabHeader(session.title) {
                closeTab(session.id)
            })
        }

        val restoredIndex = sessionController.sessions.indexOfFirst { it.id == previousSelectedId }
        if (tabs.tabCount > 0) {
            tabs.selectedIndex = if (restoredIndex >= 0) restoredIndex else 0
        }
    }

    private fun buildWelcomeCard(): JPanel {
        val greetingName = System.getProperty("user.name", "there").replaceFirstChar { it.uppercase() }
        val modeTitle = if (entryMode == EntryMode.ASK) "Ask" else "Hi, $greetingName"
        val modeSubtitle = if (entryMode == EntryMode.ASK) {
            "Ask a focused question about your current file or project"
        } else {
            "What can I help you build today?"
        }

        val heroTitle = JBLabel(
            "<html><span style='font-size:22pt; font-weight:700; color:#5C6CFF;'>$modeTitle</span></html>",
        ).apply {
            border = JBUI.Borders.emptyBottom(4)
        }

        val heroSubtitle = JBLabel(modeSubtitle).apply {
            foreground = JBColor.WHITE
            font = font.deriveFont(Font.BOLD, font.size2D + 5f)
            border = JBUI.Borders.emptyBottom(28)
        }

        val infoCard = JPanel(BorderLayout(0, 8)).apply {
            isOpaque = true
            background = JBColor(Color(0x1D2E5D), Color(0x1D2E5D))
            border = JBUI.Borders.compound(
                BorderFactory.createLineBorder(JBColor(Color(0x3B5DB8), Color(0x3B5DB8)), 1, true),
                JBUI.Borders.empty(14, 16),
            )
        }

        val infoTitle = JBLabel(
            if (entryMode == EntryMode.ASK) {
                "Use Ask for quick, scoped questions"
            } else {
                "Use a Llamatik model for private local assistance"
            },
        ).apply {
            foreground = JBColor.WHITE
            font = font.deriveFont(Font.BOLD, font.size2D + 1f)
            border = JBUI.Borders.emptyBottom(4)
        }

        val infoBody = JBLabel(
            "<html>${if (entryMode == EntryMode.ASK) "Ask keeps the same workflow, but presents a lighter question-first surface." else "Stream responses locally, keep your workflow private, and move into Agent mode when you want assisted multi-step execution."}</html>",
        ).apply {
            foreground = JBColor(Color(0xD8E1FF), Color(0xD8E1FF))
        }

        infoCard.add(infoTitle, BorderLayout.NORTH)
        infoCard.add(infoBody, BorderLayout.CENTER)

        val promptsTitle = JBLabel("Prompts to try").apply {
            foreground = JBColor.WHITE
            font = font.deriveFont(Font.BOLD, font.size2D + 1f)
            border = JBUI.Borders.empty(26, 0, 10, 0)
        }

        val promptsGrid = JPanel(GridLayout(0, 1, 0, 8)).apply {
            isOpaque = false
        }

        (if (entryMode == EntryMode.ASK) askSuggestions else chatSuggestions).forEach { suggestion ->
            promptsGrid.add(promptChip(suggestion))
        }

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = rootBackground
            border = JBUI.Borders.empty(26, 20, 20, 20)
            add(heroTitle)
            add(heroSubtitle)
            add(infoCard)
            add(promptsTitle)
            add(promptsGrid)
        }

        return JPanel(BorderLayout()).apply {
            background = rootBackground
            add(content, BorderLayout.NORTH)
        }
    }

    private fun buildTabHeader(title: String, onClose: () -> Unit): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            add(JLabel(title).apply { foreground = JBColor.WHITE })
            add(JButton("×").apply {
                isBorderPainted = false
                isContentAreaFilled = false
                isFocusPainted = false
                foreground = mutedText
                toolTipText = "Close"
                addActionListener { onClose() }
            })
        }

    private fun promptChip(text: String): JButton =
        JButton("<html><span style='color:#FFFFFF;'>$text</span></html>").apply {
            horizontalAlignment = JButton.LEFT
            isFocusPainted = false
            isContentAreaFilled = true
            isOpaque = true
            background = cardBackground
            border = JBUI.Borders.compound(
                BorderFactory.createLineBorder(
                    JBColor(
                        if (entryMode == EntryMode.ASK) heroSecondary else heroAccent,
                        if (entryMode == EntryMode.ASK) heroSecondary else heroAccent,
                    ),
                    1,
                    true,
                ),
                JBUI.Borders.empty(10, 14),
            )
            preferredSize = Dimension(preferredSize.width, 38)
            addActionListener { onSuggestionSelected(text) }
        }

    private fun toolbarButton(text: String): JButton =
        JButton("<html><span style='color:#FFFFFF;'>$text</span></html>").apply {
            isFocusPainted = false
            isContentAreaFilled = true
            isOpaque = true
            background = cardBackground
            border = JBUI.Borders.compound(
                BorderFactory.createLineBorder(JBColor(Color(0x303644), Color(0x303644)), 1, true),
                JBUI.Borders.empty(6, 10),
            )
        }

    private fun closeTab(sessionId: String) {
        sessionController.removeSession(sessionId)
        rebuildTabs()
    }

    fun currentSession() =
        sessionController.sessions.getOrElse(tabs.selectedIndex.coerceAtLeast(0)) {
            sessionController.sessions.first()
        }

    fun appendStreamingPanel(streamPanel: ChatMessagePanel) {
        val selectedIndex = tabs.selectedIndex
        if (selectedIndex < 0 || selectedIndex >= tabs.tabCount) return

        val cardPanel = tabs.getComponentAt(selectedIndex) as? JPanel ?: return
        (cardPanel.layout as? CardLayout)?.show(cardPanel, CARD_MESSAGES)

        val scrollPane = cardPanel.components.filterIsInstance<JBScrollPane>().firstOrNull() ?: return
        val content = scrollPane.viewport.view as? JPanel ?: return

        content.add(streamPanel)
        content.revalidate()
        content.repaint()

        val bar = scrollPane.verticalScrollBar
        bar.value = bar.maximum
    }
}
