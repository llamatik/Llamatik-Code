package com.llamatik.teammate.ui.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.diff.DiffPreviewService
import com.llamatik.teammate.licensing.LicenseChecker
import com.llamatik.teammate.llamatik.MultimodalBackendService
import com.llamatik.teammate.model.AttachedImage
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
    private val onSend: (String, List<AttachedImage>) -> Unit = { _, _ -> },
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

    // ── Model picker ──────────────────────────────────────────────────────────
    private val modelPickerButton = ModelPickerButton(onModelChanged = onModelChanged)

    // ── Image attachment bar ──────────────────────────────────────────────────
    private val isMultimodalEnabled: () -> Boolean = {
        runCatching { service<MultimodalBackendService>().isConfigured() }.getOrElse { false }
    }
    private val isLicensed: () -> Boolean = {
        runCatching { LicenseChecker.isLicensed() == true }.getOrElse { false }
    }
    private val imageBar = ImageAttachmentBar(project, isMultimodalEnabled, { /* onChange — read on send */ }, isLicensed)

    // ── Composer ──────────────────────────────────────────────────────────────
    private val composerInput = object : JBTextArea(3, 40) {
        override fun paintComponent(g: Graphics) {
            super.paintComponent(g)
            if (text.isEmpty() && !isFocusOwner) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g2.color = LlamatikTheme.onSurfaceVariant
                g2.font = font.deriveFont(Font.ITALIC)
                val insets = insets
                g2.drawString(Strings.Composer.INPUT_PLACEHOLDER, insets.left, insets.top + g2.fontMetrics.ascent)
                g2.dispose()
            }
        }
    }.apply {
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
        isFocusPainted = false
        isContentAreaFilled = false
        preferredSize = Dimension(38, 32)
        border = JBUI.Borders.compound(
            BorderFactory.createLineBorder(LlamatikTheme.outline, 1, true),
            JBUI.Borders.empty(5, 10),
        )
    }
    private val composerStopButton = JButton(AllIcons.Actions.Suspend).apply {
        isEnabled = false
        toolTipText = Strings.Composer.STOP_TOOLTIP
        isFocusPainted = false
        isContentAreaFilled = false
        preferredSize = Dimension(38, 32)
        border = JBUI.Borders.compound(
            BorderFactory.createLineBorder(LlamatikTheme.outline, 1, true),
            JBUI.Borders.empty(5, 10),
        )
    }

    init {
        background = rootBackground
        border = JBUI.Borders.empty()

        add(tabs, BorderLayout.CENTER)
        add(buildComposer(), BorderLayout.SOUTH)

        composerSendButton.addActionListener {
            val text = composerInput.text.trim()
            if (text.isNotEmpty()) onSend(text, imageBar.currentAttachments())
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
                    if (text.isNotEmpty()) onSend(text, imageBar.currentAttachments())
                }
            })
            put("newline", object : AbstractAction() {
                override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                    composerInput.document.insertString(composerInput.caretPosition, "\n", null)
                }
            })
        }

        composerInput.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent?) = composerInput.repaint()
            override fun focusLost(e: java.awt.event.FocusEvent?) = composerInput.repaint()
        })

        AtMentionHandler(composerInput, project, imageBar)

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
        imageBar.clear()
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
        if (entryMode == mode) return
        entryMode = mode
        rebuildTabs()
    }

    private fun addNewSession() {
        val newSession = sessionController.addSession()
        rebuildTabs()
        val index = sessionController.sessions.indexOfFirst { it.id == newSession.id }
        if (index >= 0) tabs.selectedIndex = index
    }

    private fun bindActions() {
        tabs.addChangeListener {
            val idx = tabs.selectedIndex
            // Last tab is always the "+" fake tab — redirect to new session
            if (idx == tabs.tabCount - 1 && idx > sessionController.sessions.lastIndex) {
                addNewSession()
                return@addChangeListener
            }
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

            val sessionClearButton = toolbarButton(AllIcons.Actions.GC, Strings.Chat.CLEAR).apply {
                addActionListener {
                    sessionController.clearSession(session.id)
                    rebuildTabs()
                }
            }

            val scrollToBottomButton = toolbarButton(AllIcons.Actions.MoveDown, Strings.Agent.SCROLL_TO_BOTTOM).apply {
                isVisible = false
                addActionListener {
                    val bar = messagesScroll.verticalScrollBar
                    bar.value = bar.maximum
                }
            }

            val tabToolbar = JPanel(BorderLayout()).apply {
                isOpaque = false
                val left = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4)).apply {
                    isOpaque = false
                    add(scrollToBottomButton)
                }
                val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 4)).apply {
                    isOpaque = false
                    add(sessionClearButton)
                }
                add(left, BorderLayout.WEST)
                add(right, BorderLayout.EAST)
            }

            messagesScroll.verticalScrollBar.model.addChangeListener {
                javax.swing.SwingUtilities.invokeLater {
                    val bar = messagesScroll.verticalScrollBar
                    val atBottom = bar.maximum <= bar.visibleAmount || bar.value + bar.visibleAmount >= bar.maximum - 5
                    scrollToBottomButton.isVisible = !atBottom
                }
            }

            val welcomeCard = buildWelcomeCard()
            val messagesCard = JPanel(BorderLayout()).apply {
                background = rootBackground
                putClientProperty("isMessagesCard", true)
                add(tabToolbar, BorderLayout.NORTH)
                add(messagesScroll, BorderLayout.CENTER)
            }

            val cardPanel = JPanel(cardLayout).apply {
                background = rootBackground
                add(welcomeCard, CARD_WELCOME)
                add(messagesCard, CARD_MESSAGES)
            }

            cardLayout.show(cardPanel, if (session.messages.isEmpty()) CARD_WELCOME else CARD_MESSAGES)

            tabs.addTab(session.title, cardPanel)

            val tabIndex = tabs.tabCount - 1
            tabs.setTabComponentAt(tabIndex, buildTabHeader(session.title) {
                closeTab(session.id)
            })
        }

        // "+" add-tab button as a trailing fake tab
        tabs.addTab("", JPanel())
        val addTabIndex = tabs.tabCount - 1
        tabs.setTabComponentAt(addTabIndex, JButton(AllIcons.General.Add).apply {
            toolTipText = Strings.Chat.NEW_CHAT
            isFocusPainted = false
            isContentAreaFilled = false
            isBorderPainted = false
            border = JBUI.Borders.empty(2, 4)
            addActionListener { addNewSession() }
        })

        val restoredIndex = sessionController.sessions.indexOfFirst { it.id == previousSelectedId }
        if (tabs.tabCount > 1) {
            tabs.selectedIndex = if (restoredIndex >= 0) restoredIndex else 0
        }
    }

    private fun buildWelcomeCard(): javax.swing.JComponent {
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

        val infoCard = object : JPanel(BorderLayout(0, 8)) {
            private val arc = 12
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = LlamatikTheme.tertiaryContainer
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc.toFloat(), arc.toFloat()))
                g2.color = LlamatikTheme.primary
                g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc.toFloat(), arc.toFloat()))
                g2.dispose()
            }
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(14, 16)
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

        val inner = JPanel(BorderLayout()).apply {
            background = rootBackground
            add(content, BorderLayout.NORTH)
        }
        return JBScrollPane(inner).apply {
            border = JBUI.Borders.empty()
            viewport.background = rootBackground
            background = rootBackground
            horizontalScrollBarPolicy = javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
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

    private fun promptChip(text: String): JButton {
        val borderColor = if (entryMode == EntryMode.ASK) heroSecondary else heroAccent
        return object : JButton("<html><span style='color:${LlamatikTheme.htmlOnSurface};'>$text</span></html>") {
            private val arc = 12
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = cardBackground
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc.toFloat(), arc.toFloat()))
                g2.color = borderColor
                g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc.toFloat(), arc.toFloat()))
                g2.dispose()
                super.paintComponent(g)
            }
        }.apply {
            horizontalAlignment = JButton.LEFT
            isFocusPainted = false
            isContentAreaFilled = false
            isOpaque = false
            border = JBUI.Borders.empty(10, 14)
            preferredSize = Dimension(preferredSize.width, 38)
            addActionListener { onSuggestionSelected(text) }
        }
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

        val buttonBorder = JBUI.Borders.compound(
            BorderFactory.createLineBorder(LlamatikTheme.outline, 1, true),
            JBUI.Borders.empty(5, 10),
        )
        val settingsButton = JButton(AllIcons.General.Settings).apply {
            toolTipText = Strings.Settings.PAGE_NAME
            isFocusPainted = false
            isContentAreaFilled = false
            preferredSize = Dimension(38, 32)
            border = buttonBorder
            addActionListener { onSettingsClicked() }
        }
        val inferenceButton = JButton(AllIcons.Actions.Properties).apply {
            toolTipText = Strings.Inference.BUTTON_TOOLTIP
            isFocusPainted = false
            isContentAreaFilled = false
            preferredSize = Dimension(38, 32)
            border = buttonBorder
            addActionListener { showInferenceDialog() }
        }

        val footerLeft = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(modelPickerButton)
            add(imageBar.attachButton)
        }
        val footerRight = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
            isOpaque = false
            add(inferenceButton)
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
            add(imageBar, BorderLayout.NORTH)
            add(inputScroll, BorderLayout.CENTER)
            add(footer, BorderLayout.SOUTH)
            imageBar.installDropTarget(this)
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

        val messagesCard = cardPanel.components.filterIsInstance<JPanel>()
            .firstOrNull { it.getClientProperty("isMessagesCard") == true } ?: return
        val scrollPane = messagesCard.components.filterIsInstance<JBScrollPane>().firstOrNull() ?: return
        val content = scrollPane.viewport.view as? JPanel ?: return

        content.add(streamPanel)
        content.revalidate()
        content.repaint()

        javax.swing.SwingUtilities.invokeLater {
            val bar = scrollPane.verticalScrollBar
            bar.value = bar.maximum
        }
    }

    private fun showInferenceDialog() {
        val settings = com.intellij.openapi.components.service<com.llamatik.teammate.settings.LlamatikAppSettings>()
        var dialog: com.intellij.openapi.ui.DialogWrapper? = null
        val panel = com.llamatik.teammate.settings.InferenceSettingsPanel(onUpgrade = {
            dialog?.close(com.intellij.openapi.ui.DialogWrapper.CANCEL_EXIT_CODE)
            com.intellij.openapi.components.service<com.llamatik.teammate.licensing.TrialService>()
                .requestLicense(Strings.Inference.UPGRADE_REQUIRED)
        })
        panel.loadFrom(settings.data())
        dialog = object : com.intellij.openapi.ui.DialogWrapper(false) {
            init {
                title = Strings.Inference.DIALOG_TITLE
                init()
            }
            override fun createCenterPanel() = panel.panel
        }
        if (dialog.showAndGet()) {
            panel.applyTo(settings.data())
        }
    }
}
