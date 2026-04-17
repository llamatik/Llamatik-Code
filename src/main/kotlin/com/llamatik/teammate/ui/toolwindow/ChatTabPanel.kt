package com.llamatik.teammate.ui.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.diff.DiffPreviewService
import com.llamatik.teammate.model.EditProposal
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
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.KeyStroke

private const val CARD_WELCOME = "WELCOME"
private const val CARD_MESSAGES = "MESSAGES"

class ChatTabPanel(
    private val project: Project,
    val sessionController: ChatSessionController,
    private val diffService: DiffPreviewService,
    private val pendingEdits: LinkedHashMap<String, EditProposal>,
    private val onApplyEdit: (EditProposal) -> Unit,
    private val onSuggestionSelected: (String) -> Unit,
    private val onSend: (String) -> Unit = {},
    private val onStop: () -> Unit = {},
    private val onSettingsClicked: () -> Unit = {},
    private val onModelChanged: () -> Unit = {},
) : JPanel(BorderLayout()) {

    enum class EntryMode {
        CHAT,
        ASK,
    }

    private val rootBackground = LlamatikTheme.background
    private val cardBackground = LlamatikTheme.surface
    private val heroAccent = LlamatikTheme.primary
    private val heroSecondary = LlamatikTheme.secondary
    private val mutedText = LlamatikTheme.onSurfaceVariant

    private var entryMode = EntryMode.CHAT

    private val chatSuggestions = Strings.Chat.CHAT_SUGGESTIONS
    private val askSuggestions = Strings.Chat.ASK_SUGGESTIONS

    val tabs = JBTabbedPane().apply {
        isOpaque = true
        background = rootBackground
        foreground = LlamatikTheme.onSurface
        setUI(object : javax.swing.plaf.basic.BasicTabbedPaneUI() {
            private val arc = 10
            override fun installDefaults() {
                super.installDefaults()
                highlight = rootBackground
                lightHighlight = rootBackground
                shadow = LlamatikTheme.outline
                darkShadow = LlamatikTheme.outline
                focus = LlamatikTheme.primary
            }
            override fun paintTabBorder(g: java.awt.Graphics, tabPlacement: Int, tabIndex: Int, x: Int, y: Int, w: Int, h: Int, isSelected: Boolean) {
                val g2 = g.create() as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = if (isSelected) LlamatikTheme.primary else LlamatikTheme.outline
                // top-left, top-right rounded; bottom straight
                val path = java.awt.geom.Path2D.Float()
                path.moveTo((x + arc).toFloat(), y.toFloat())
                path.quadTo(x.toFloat(), y.toFloat(), x.toFloat(), (y + arc).toFloat())
                path.lineTo(x.toFloat(), (y + h).toFloat())
                path.lineTo((x + w).toFloat(), (y + h).toFloat())
                path.lineTo((x + w).toFloat(), (y + arc).toFloat())
                path.quadTo((x + w).toFloat(), y.toFloat(), (x + w - arc).toFloat(), y.toFloat())
                path.closePath()
                g2.draw(path)
                g2.dispose()
            }
            override fun paintTabBackground(g: java.awt.Graphics, tabPlacement: Int, tabIndex: Int, x: Int, y: Int, w: Int, h: Int, isSelected: Boolean) {
                val g2 = g.create() as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = if (isSelected) LlamatikTheme.surface else rootBackground
                val path = java.awt.geom.Path2D.Float()
                path.moveTo((x + arc).toFloat(), y.toFloat())
                path.quadTo(x.toFloat(), y.toFloat(), x.toFloat(), (y + arc).toFloat())
                path.lineTo(x.toFloat(), (y + h).toFloat())
                path.lineTo((x + w).toFloat(), (y + h).toFloat())
                path.lineTo((x + w).toFloat(), (y + arc).toFloat())
                path.quadTo((x + w).toFloat(), y.toFloat(), (x + w - arc).toFloat(), y.toFloat())
                path.closePath()
                g2.fill(path)
                g2.dispose()
            }
            override fun paintFocusIndicator(g: java.awt.Graphics, tabPlacement: Int, rects: Array<java.awt.Rectangle>, tabIndex: Int, iconRect: java.awt.Rectangle, textRect: java.awt.Rectangle, isSelected: Boolean) {
                // suppress focus ring
            }
            override fun paintContentBorder(g: java.awt.Graphics, tabPlacement: Int, selectedIndex: Int) {
                val g2 = g.create() as java.awt.Graphics2D
                g2.color = LlamatikTheme.primary
                val insets = tabAreaInsets
                val tabAreaHeight = calculateTabAreaHeight(tabPlacement, runCount, maxTabHeight)
                g2.fillRect(0, insets.top + tabAreaHeight, tabPane.width, 2)
                g2.dispose()
            }
        })
    }

    private val newChatButton = toolbarButton(AllIcons.General.Add, Strings.Chat.NEW_CHAT)
    private val clearButton = toolbarButton(AllIcons.Actions.GC, Strings.Chat.CLEAR)

    // ── Model picker ──────────────────────────────────────────────────────────
    private val modelPickerButton = ModelPickerButton(onModelChanged = onModelChanged)

    // ── Composer ──────────────────────────────────────────────────────────────
    private val composerInput = JBTextArea(3, 40).apply {
        lineWrap = true
        wrapStyleWord = true
        isOpaque = false
        foreground = LlamatikTheme.onSurface
        background = cardBackground
        caretColor = LlamatikTheme.onSurface
        border = JBUI.Borders.empty(10, 12)
        font = font.deriveFont(java.awt.Font.PLAIN, font.size2D + 1f)
        toolTipText = Strings.Composer.INPUT_TOOLTIP_ASK
    }
    private val composerSendButton = JButton(AllIcons.Actions.Execute).apply {
        toolTipText = Strings.Composer.SEND_TOOLTIP
        preferredSize = Dimension(38, 32)
    }
    private val composerStopButton = JButton(AllIcons.Actions.Suspend).apply {
        isEnabled = false
        toolTipText = Strings.Composer.STOP_TOOLTIP
        preferredSize = Dimension(38, 32)
    }

    init {
        background = rootBackground
        border = JBUI.Borders.empty()

        val topBar = JPanel(FlowLayout(FlowLayout.RIGHT, 3, 4)).apply {
            background = rootBackground
            border = JBUI.Borders.empty(0, 12)
            add(newChatButton)
            add(clearButton)
        }

        add(topBar, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)
        add(buildComposer(), BorderLayout.SOUTH)

        composerSendButton.addActionListener {
            val text = composerInput.text.trim()
            if (text.isNotEmpty()) onSend(text)
        }
        composerStopButton.addActionListener { onStop() }

        composerInput.getInputMap(JComponent.WHEN_FOCUSED).apply {
            put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send")
            put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "newline")
        }
        composerInput.actionMap.apply {
            put("send", object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                    val text = composerInput.text.trim()
                    if (text.isNotEmpty()) onSend(text)
                }
            })
            put("newline", object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                    composerInput.document.insertString(composerInput.caretPosition, "\n", null)
                }
            })
        }

        AtMentionHandler(composerInput, project)

        bindActions()
        rebuildTabs()
    }

    // ── Composer public API ───────────────────────────────────────────────────

    fun refreshModelPicker() = modelPickerButton.refresh()

    fun prefill(text: String) {
        composerInput.text = text
        composerInput.requestFocusInWindow()
        composerInput.caretPosition = composerInput.document.length
    }

    fun getComposerText(): String = composerInput.text.trim()

    fun clearComposer() {
        composerInput.text = ""
    }

    fun setGenerating(generating: Boolean) {
        composerStopButton.isEnabled = generating
        composerSendButton.isEnabled = !generating
        composerInput.isEnabled = !generating
    }

    fun setComposerEnabled(enabled: Boolean) {
        composerInput.isEnabled = enabled
        composerSendButton.isEnabled = enabled
    }

    fun setComposerTooltip(text: String) {
        composerInput.toolTipText = text
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
            if (idx in sessionController.sessions.indices) {
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
                border = JBUI.Borders.empty(12)
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
        val modeTitle = if (entryMode == EntryMode.ASK) Strings.Chat.ASK_HERO_TITLE else "Hi, $greetingName"
        val modeSubtitle = if (entryMode == EntryMode.ASK) {
            Strings.Chat.ASK_SUBTITLE
        } else {
            Strings.Chat.CHAT_SUBTITLE
        }

        val heroTitle = JBLabel(
            "<html><span style='font-size:22pt; font-weight:700; color:${LlamatikTheme.htmlPrimary};'>$modeTitle</span></html>",
        ).apply {
            border = JBUI.Borders.emptyBottom(4)
        }

        val heroSubtitle = JBLabel(
            "<html><span style='color:${LlamatikTheme.htmlOnSurface};'>$modeSubtitle</span></html>",
        ).apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 5f)
            border = JBUI.Borders.emptyBottom(28)
        }

        val infoCard = JPanel(BorderLayout(0, 8)).apply {
            isOpaque = true
            background = LlamatikTheme.tertiaryContainer
            border = JBUI.Borders.compound(
                BorderFactory.createLineBorder(LlamatikTheme.primary, 1, true),
                JBUI.Borders.empty(14, 16),
            )
        }

        val infoTitle = JBLabel(
            "<html><span style='color:${LlamatikTheme.htmlOnSurface};'>${if (entryMode == EntryMode.ASK) Strings.Chat.ASK_INFO_TITLE else Strings.Chat.CHAT_INFO_TITLE}</span></html>",
        ).apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 1f)
            border = JBUI.Borders.emptyBottom(4)
        }

        val infoBody = JBLabel(
            "<html><span style='color:${LlamatikTheme.htmlOnSurfaceVariant};'>${if (entryMode == EntryMode.ASK) Strings.Chat.ASK_INFO_BODY else Strings.Chat.CHAT_INFO_BODY}</span></html>",
        )

        infoCard.add(infoTitle, BorderLayout.NORTH)
        infoCard.add(infoBody, BorderLayout.CENTER)

        val promptsTitle = JBLabel(Strings.Chat.PROMPTS_TITLE).apply {
            foreground = LlamatikTheme.onSurface
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
            heroTitle.alignmentX = LEFT_ALIGNMENT
            heroSubtitle.alignmentX = LEFT_ALIGNMENT
            infoCard.alignmentX = LEFT_ALIGNMENT
            promptsTitle.alignmentX = LEFT_ALIGNMENT
            promptsGrid.alignmentX = LEFT_ALIGNMENT
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
            add(JLabel("<html><span style='color:${LlamatikTheme.htmlOnSurface};'>$title</span></html>"))
            add(JButton("<html><span style='color:${LlamatikTheme.htmlOnSurfaceVariant};'>${Strings.Chat.TAB_CLOSE_ICON}</span></html>").apply {
                isBorderPainted = false
                isContentAreaFilled = false
                isFocusPainted = false
                toolTipText = Strings.Chat.TAB_CLOSE_TOOLTIP
                addActionListener { onClose() }
            })
        }

    private fun promptChip(text: String): JButton =
        JButton("<html><span style='color:${LlamatikTheme.htmlOnSurface};'>$text</span></html>").apply {
            horizontalAlignment = JButton.LEFT
            isFocusPainted = false
            isContentAreaFilled = true
            isOpaque = true
            background = cardBackground
            border = JBUI.Borders.compound(
                BorderFactory.createLineBorder(
                    if (entryMode == EntryMode.ASK) heroSecondary else heroAccent,
                    1,
                    true,
                ),
                JBUI.Borders.empty(10, 14),
            )
            preferredSize = Dimension(preferredSize.width, 38)
            addActionListener { onSuggestionSelected(text) }
        }

    private fun toolbarButton(icon: Icon, tooltip: String): JButton =
        JButton(icon).apply {
            toolTipText = tooltip
            isFocusPainted = false
            isContentAreaFilled = true
            isOpaque = true
            background = cardBackground
            border = JBUI.Borders.compound(
                BorderFactory.createLineBorder(LlamatikTheme.outline, 1, true),
                JBUI.Borders.empty(3, 5),
            )
        }

    private fun buildComposer(): JPanel {
        val arc = 16
        val inputScroll = JBScrollPane(composerInput).apply {
            border = JBUI.Borders.empty()
            viewport.border = null
            isOpaque = false
            background = cardBackground
            viewport.isOpaque = false
            viewport.background = cardBackground
            preferredSize = Dimension(100, 86)
        }
        composerInput.isOpaque = false
        composerInput.background = cardBackground

        val settingsButton = JButton(AllIcons.General.Settings).apply {
            toolTipText = Strings.Settings.PAGE_NAME
            isFocusPainted = false
            isContentAreaFilled = false
            preferredSize = Dimension(38, 32)
            border = JBUI.Borders.compound(
                BorderFactory.createLineBorder(LlamatikTheme.outline, 1, true),
                JBUI.Borders.empty(5, 10),
            )
            addActionListener { onSettingsClicked() }
        }

        val footerLeft = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(modelPickerButton)
        }
        val footerRight = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            isOpaque = false
            add(settingsButton)
            add(composerStopButton)
            add(composerSendButton)
        }
        val footer = JPanel(java.awt.BorderLayout()).apply {
            isOpaque = false
            add(footerLeft, java.awt.BorderLayout.WEST)
            add(footerRight, java.awt.BorderLayout.EAST)
        }

        val shell = object : JPanel(BorderLayout(0, 8)) {
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
                g2.color = LlamatikTheme.primary
                g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc.toFloat(), arc.toFloat()))
                g2.dispose()
            }
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, 10, 8, 10)
            add(inputScroll, BorderLayout.CENTER)
            add(footer, BorderLayout.SOUTH)
        }

        return JPanel(BorderLayout()).apply {
            background = rootBackground
            border = JBUI.Borders.empty(10, 12, 8, 12)
            add(shell, BorderLayout.CENTER)
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
