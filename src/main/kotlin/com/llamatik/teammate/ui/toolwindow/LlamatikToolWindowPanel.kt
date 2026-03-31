package com.llamatik.teammate.ui.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
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
import com.llamatik.teammate.settings.LlamatikAppSettings
import com.llamatik.teammate.ui.chat.ChatMessagePanel
import com.llamatik.teammate.ui.chat.ChatSessionController
import java.awt.BorderLayout
import java.awt.FlowLayout
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
    private val sessionController = ChatSessionController(project)
    private val diffService = project.service<DiffPreviewService>()
    private val settings = service<LlamatikAppSettings>()
    private val trialService = service<TrialService>()

    private val headerLabel = JBLabel()
    private val statusLabel = JBLabel()
    private val tabs = JBTabbedPane()
    private val inputArea = JBTextArea(4, 40)
    private val sendButton = JButton("Send")
    private val cancelButton = JButton("Cancel")
    private val clearButton = JButton("Clear")
    private val newTabButton = JButton("+ New Chat")

    private var currentHandle: GenerationHandle? = null
    private val pendingEdits = linkedMapOf<String, EditProposal>()

    init {
        border = JBUI.Borders.empty(8)
        add(buildHeader(), BorderLayout.NORTH)
        add(buildCenter(), BorderLayout.CENTER)
        add(buildFooter(), BorderLayout.SOUTH)
        rebuildTabs()
        bindActions()
        setGenerating(false)
        refreshHeader()
    }

    fun focusInputWithPrefill(text: String) {
        inputArea.text = text
        inputArea.requestFocusInWindow()
    }

    // ── Layout builders ───────────────────────────────────────────────────────

    private fun buildHeader(): JPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.compound(
            BorderFactory.createMatteBorder(0, 0, 1, 0, com.intellij.ui.JBColor.border()),
            JBUI.Borders.emptyBottom(8),
        )
        add(headerLabel, BorderLayout.WEST)
        add(statusLabel, BorderLayout.EAST)
    }

    private fun buildCenter(): JPanel = JPanel(BorderLayout()).apply {
        val topActions = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(newTabButton)
            add(clearButton)
        }
        add(topActions, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)
    }

    private fun buildFooter(): JPanel {
        inputArea.lineWrap = true
        inputArea.wrapStyleWord = true
        val footer = JPanel(BorderLayout(8, 8))
        footer.border = JBUI.Borders.emptyTop(8)
        footer.add(JBScrollPane(inputArea), BorderLayout.CENTER)
        footer.add(JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(sendButton)
            add(cancelButton)
        }, BorderLayout.EAST)
        return footer
    }

    // ── Tab management ────────────────────────────────────────────────────────

    private fun rebuildTabs() {
        val previousIndex = tabs.selectedIndex
        tabs.removeAll()
        sessionController.sessions.forEach { session ->
            val listPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty(0, 0, 8, 0)
            }
            session.messages.forEach { message ->
                val proposal = message.editProposalId?.let { pendingEdits[it] }
                listPanel.add(
                    ChatMessagePanel(
                        project = project,
                        roleLabel = message.role.name.lowercase().replaceFirstChar { it.uppercase() },
                        initialContent = message.content,
                        onApplyEdit = proposal?.let { { applyEdit(it) } },
                        onViewDiff = proposal?.let { { diffService.showDiff(it) } },
                    ),
                )
            }
            val scroll = JBScrollPane(listPanel)
            tabs.addTab(session.title, scroll)
            // Custom tab component with a close (×) button
            val tabIndex = tabs.tabCount - 1
            val tabLabel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                add(JLabel(session.title))
                add(JButton("×").apply {
                    isBorderPainted = false
                    isContentAreaFilled = false
                    isFocusPainted = false
                    toolTipText = "Close tab"
                    font = font.deriveFont(font.size2D - 1f)
                    addActionListener { closeTab(session.id) }
                })
            }
            tabs.setTabComponentAt(tabIndex, tabLabel)
        }
        val selectedIndex = sessionController.sessions
            .indexOfFirst { it.id == sessionController.selectedSessionId }
            .takeIf { it >= 0 } ?: previousIndex.coerceAtLeast(0)
        if (tabs.tabCount > 0) tabs.selectedIndex = selectedIndex.coerceAtMost(tabs.tabCount - 1)
    }

    private fun closeTab(sessionId: String) {
        sessionController.removeSession(sessionId)
        rebuildTabs()
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private fun bindActions() {
        sendButton.addActionListener { sendPrompt() }
        cancelButton.addActionListener {
            currentHandle?.cancel()
            setGenerating(false)
            statusLabel.text = "Cancelled"
        }
        clearButton.addActionListener {
            sessionController.clearSession(currentSession().id)
            rebuildTabs()
        }
        newTabButton.addActionListener {
            val session = sessionController.addSession()
            rebuildTabs()
            tabs.selectedIndex = sessionController.sessions.indexOfFirst { it.id == session.id }
        }
        tabs.addChangeListener {
            val idx = tabs.selectedIndex
            if (idx >= 0 && idx < sessionController.sessions.size) {
                sessionController.selectedSessionId = sessionController.sessions[idx].id
                sessionController.persist()
            }
        }
        // Ctrl+Enter sends the prompt
        inputArea.getInputMap(WHEN_FOCUSED).put(
            KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK),
            "sendPrompt",
        )
        inputArea.actionMap.put("sendPrompt", object : javax.swing.AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) = sendPrompt()
        })
    }

    private fun currentSession() =
        sessionController.sessions.getOrElse(tabs.selectedIndex.coerceAtLeast(0)) {
            sessionController.sessions.first()
        }

    // ── Sending and streaming ─────────────────────────────────────────────────

    private fun sendPrompt() {
        val prompt = inputArea.text.trim()
        if (prompt.isEmpty()) return
        val session = currentSession()
        val userMessage = ChatMessage(role = ChatRole.USER, content = prompt)
        sessionController.appendMessage(session.id, userMessage)
        inputArea.text = ""
        rebuildTabs()
        setGenerating(true)
        refreshHeader()

        // Add a live streaming panel directly to the active scroll pane
        val assistantPanel = ChatMessagePanel(project, "Assistant", "")
        val scrollPane = tabs.getComponentAt(tabs.selectedIndex) as JBScrollPane
        val messageContainer = scrollPane.viewport.view as JPanel
        messageContainer.add(assistantPanel)
        messageContainer.revalidate()

        currentHandle = agent.submit(
            session = session,
            userPrompt = prompt,
            onToken = { token ->
                SwingUtilities.invokeLater { assistantPanel.appendToken(token) }
            },
            onFinished = { assistantMessage, proposal ->
                ApplicationManager.getApplication().invokeLater {
                    proposal?.let { pendingEdits[it.id] = it }
                    sessionController.appendMessage(session.id, assistantMessage)
                    if (proposal != null) {
                        assistantPanel.setContent(assistantMessage.content)
                        rebuildTabs()
                    }
                    setGenerating(false)
                    refreshHeader()
                }
            },
            onError = { message ->
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(project, message, "Llamatik Code")
                    setGenerating(false)
                    refreshHeader()
                }
            },
        )
    }

    // ── State helpers ─────────────────────────────────────────────────────────

    private fun setGenerating(generating: Boolean) {
        sendButton.isEnabled = !generating
        cancelButton.isEnabled = generating
        if (generating) statusLabel.text = "Generating…"
    }

    private fun refreshHeader() {
        val state = settings.data()
        headerLabel.text = "Llamatik Code — ${project.name}"
        val modelLabel = agent.selectedModelLabel()
        val loadedLabel = if (agent.isModelLoaded()) "loaded" else "not loaded"
        val modeLabel = if (state.localOnly) "Local" else "Remote"
        statusLabel.text = "$modelLabel | $loadedLabel | $modeLabel | ${trialService.statusLabel()}"
    }

    // ── Edit / diff ───────────────────────────────────────────────────────────

    private fun applyEdit(proposal: EditProposal) {
        diffService.showDiff(proposal)
        val confirmed = Messages.showYesNoDialog(
            project,
            "Apply proposed changes to ${proposal.filePath}?",
            "Apply Llamatik Edit",
            null,
        )
        if (confirmed == Messages.YES) {
            diffService.applyProposal(proposal)
        }
    }
}
