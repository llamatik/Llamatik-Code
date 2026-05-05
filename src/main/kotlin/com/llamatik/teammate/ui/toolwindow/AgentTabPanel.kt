package com.llamatik.teammate.ui.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.components.service
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import com.intellij.openapi.components.service
import com.llamatik.teammate.infrastructure.ModelDownloadManager
import com.llamatik.teammate.licensing.LicenseChecker
import com.llamatik.teammate.llamatik.MultimodalBackendService
import com.llamatik.teammate.model.AgentPhase
import com.llamatik.teammate.model.AgentTaskState
import com.llamatik.teammate.model.AttachedImage
import com.llamatik.teammate.model.DownloadProgressState
import com.llamatik.teammate.model.EditProposal
import com.llamatik.teammate.model.ModelStatus
import com.llamatik.teammate.model.PluginReadinessState
import com.llamatik.teammate.models.BuiltInModelCatalog
import com.llamatik.teammate.skills.SkillService
import com.llamatik.teammate.ui.LlamatikTheme
import com.llamatik.teammate.ui.Strings
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GradientPaint
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.GridLayout
import java.awt.RenderingHints
import java.awt.Shape
import java.awt.datatransfer.StringSelection
import java.awt.event.AdjustmentEvent
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.AbstractAction
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JCheckBoxMenuItem
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JLayeredPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JProgressBar
import javax.swing.KeyStroke
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities
import javax.swing.Timer

private const val CARD_WELCOME = "WELCOME"
private const val CARD_TASK = "TASK"
private const val CARD_OVERLAY = "OVERLAY"
private const val CARD_RESPONSE = "RESPONSE"

class AgentTabPanel(
    private val onSuggestionSelected: (String) -> Unit,
    private val onApprove: () -> Unit,
    private val onReject: () -> Unit,
    private val onSend: (String, List<AttachedImage>) -> Unit = { _, _ -> },
    private val onStop: () -> Unit = {},
    private val onSettingsClicked: () -> Unit = {},
    private val onModelChanged: () -> Unit = {},
    private val onClear: () -> Unit = {},
    private val isAutoApprove: () -> Boolean = { false },
    private val onAutoApproveChanged: (Boolean) -> Unit = {},
    private val isAutoBuild: () -> Boolean = { true },
    private val onAutoBuildChanged: (Boolean) -> Unit = {},
    private val isAutoFix: () -> Boolean = { false },
    private val onAutoFixChanged: (Boolean) -> Unit = {},
    private val project: com.intellij.openapi.project.Project? = null,
) : JPanel(BorderLayout()) {

    private val rootBackground = LlamatikTheme.background
    private val cardBackground = LlamatikTheme.surface
    private val panelStroke = LlamatikTheme.outline
    private val accent = LlamatikTheme.primary
    private val mutedText = LlamatikTheme.onSurfaceVariant
    private val success = LlamatikTheme.success

    private val cardLayout = java.awt.CardLayout()
    private val cardPanel = JPanel(cardLayout).apply { background = rootBackground }

    private val suggestions = Strings.Agent.SUGGESTIONS

    // Welcome card labels that need HTML text refresh when the IDE theme changes
    private lateinit var heroTitleLabel: JBLabel
    private lateinit var heroSubtitleLabel: JBLabel
    private lateinit var activityTitleLabel: JBLabel
    private lateinit var activityBodyLabel: JBLabel
    private lateinit var promptsTitleLabel: JBLabel
    private val promptCardButtons = mutableListOf<JButton>()

    // ── Task card ──────────────────────────────────────────────────────────────
    private val phaseList = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = rootBackground
        border = JBUI.Borders.empty(16)
    }
    private val approveButton = JButton(Strings.Agent.APPROVE)
    private val rejectButton = JButton(Strings.Agent.REJECT)

    // ── Response card: elapsed timer + token counter ──────────────────────────
    private var genStartMs = 0L
    private val elapsedLabel = JBLabel("").apply {
        foreground = mutedText
        font = font.deriveFont(Font.PLAIN, font.size2D - 1f)
    }
    private val elapsedTimer = Timer(500) {
        elapsedLabel.text = formatElapsed(System.currentTimeMillis() - genStartMs)
    }
    private val tokenLabel = JBLabel("0 tokens").apply {
        foreground = mutedText
        font = font.deriveFont(Font.PLAIN, font.size2D - 1f)
    }
    private lateinit var timerRow: JPanel

    // ── Single conversation scroll area ───────────────────────────────────────
    // ONE JScrollPane containing ONE vertical BoxLayout panel.
    // Every piece of content is a direct child — no nested scroll panes.
    //
    // The BoxLayout panel sits at NORTH of a BorderLayout wrapper so that
    // the scroll pane always has a correct preferred height (no content clipping).
    private val conversationPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = cardBackground
        border = JBUI.Borders.empty(12, 16, 12, 16)
        // BoxLayout requires this so children stretch to full width
        isOpaque = true
    }
    // Wrapper anchors conversationPanel to NORTH so it never collapses mid-scroll
    private val conversationWrapper = JPanel(BorderLayout()).apply {
        background = cardBackground
        isOpaque = true
        add(conversationPanel, BorderLayout.NORTH)
    }
    private val conversationScroll = JBScrollPane(conversationWrapper).apply {
        border = JBUI.Borders.empty()
        viewport.background = cardBackground
        background = cardBackground
        horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
    }

    // ── Prompt bubble ─────────────────────────────────────────────────────────
    private val promptLabel = JBLabel("").apply {
        foreground = LlamatikTheme.onSurface
        font = font.deriveFont(Font.BOLD, font.size2D)
        border = JBUI.Borders.empty(0, 0, 10, 0)
    }
    private val userAvatar = object : JPanel() {
        private val initials: String get() {
            val name = System.getProperty("user.name", "U")
            return name.take(1).uppercase()
        }
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.color = LlamatikTheme.primary
            g2.fillOval(0, 0, width, height)
            g2.color = LlamatikTheme.onPrimary
            val f = font.deriveFont(Font.BOLD, width * 0.42f)
            g2.font = f
            val fm = g2.fontMetrics
            val x = (width - fm.stringWidth(initials)) / 2
            val y = (height - fm.height) / 2 + fm.ascent
            g2.drawString(initials, x, y)
            g2.dispose()
        }
    }.apply {
        isOpaque = false
        preferredSize = Dimension(28, 28)
        minimumSize = Dimension(28, 28)
        maximumSize = Dimension(28, 28)
    }
    private val promptBubble = JPanel(BorderLayout(10, 0)).apply {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        add(JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(2)
            add(userAvatar, BorderLayout.NORTH)
        }, BorderLayout.WEST)
        add(promptLabel, BorderLayout.CENTER)
        isVisible = false
    }

    // ── Status pill ───────────────────────────────────────────────────────────
    private val statusPill = JBLabel("").apply {
        isVisible = false
        foreground = mutedText
        border = JBUI.Borders.empty(0, 0, 6, 0)
        alignmentX = LEFT_ALIGNMENT
    }
    private val statusRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, 32)
        add(statusPill)
    }

    // ── Reasoning section ─────────────────────────────────────────────────────
    // thinkTextArea is a plain JBTextArea with no wrapping JScrollPane.
    // It auto-expands vertically — the outer conversationScroll handles overflow.
    private var thinkHasContent = false
    private var thinkStartMs = 0L
    private var thinkElapsedMs = 0L
    private val thinkTextArea = JBTextArea("").apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        background = cardBackground
        foreground = mutedText
        border = JBUI.Borders.empty(0, 0, 8, 0)
        font = font.deriveFont(Font.PLAIN, font.size2D - 1f)
    }
    private val thinkContent = JPanel(BorderLayout()).apply {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        add(thinkTextArea, BorderLayout.CENTER)
        isVisible = false
    }
    private val thinkToggleBtn = JButton(Strings.Agent.REASONING_STREAMING).apply {
        isFocusPainted = false
        isContentAreaFilled = false
        border = JBUI.Borders.empty(4, 0)
        foreground = mutedText
        font = font.deriveFont(Font.PLAIN, font.size2D - 1f)
        horizontalAlignment = JButton.LEFT
        cursor = java.awt.Cursor(java.awt.Cursor.HAND_CURSOR)
        alignmentX = LEFT_ALIGNMENT
    }
    private val thinkingSection = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        isVisible = false
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
    }

    // ── Tool calls ────────────────────────────────────────────────────────────
    private val toolCallsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        isVisible = false
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
    }

    // ── Agent trace ("What I tried") ──────────────────────────────────────────
    private val traceTextArea = JBTextArea("").apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        background = cardBackground
        foreground = mutedText
        border = JBUI.Borders.empty(0, 0, 8, 0)
        font = font.deriveFont(Font.PLAIN, font.size2D - 1f)
    }
    private val traceContent = JPanel(BorderLayout()).apply {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        add(traceTextArea, BorderLayout.CENTER)
        isVisible = false
    }
    private val traceToggleBtn = JButton(Strings.Agent.TRACE_COLLAPSED).apply {
        isFocusPainted = false
        isContentAreaFilled = false
        border = JBUI.Borders.empty(4, 0)
        foreground = mutedText
        font = font.deriveFont(Font.PLAIN, font.size2D - 1f)
        horizontalAlignment = JButton.LEFT
        cursor = java.awt.Cursor(java.awt.Cursor.HAND_CURSOR)
        alignmentX = LEFT_ALIGNMENT
    }
    private val traceSection = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        isVisible = false
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
    }

    // ── Response text ─────────────────────────────────────────────────────────
    // Plain JBTextArea — no wrapping JScrollPane. Expands vertically in-place.
    private val responseTextArea = JBTextArea("").apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        background = cardBackground
        foreground = LlamatikTheme.onSurface
        border = JBUI.Borders.empty(0)
        font = font.deriveFont(Font.PLAIN, font.size2D + 1f)
    }
    private val responseContent = JPanel(BorderLayout()).apply {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        add(responseTextArea, BorderLayout.CENTER)
    }

    // ── Response action buttons ("Fix it automatically", etc.) ──────────────
    private val responseActionsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 4)).apply {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, 44)
        isVisible = false
    }

    // ── Proposals list ────────────────────────────────────────────────────────
    private val proposalsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        isVisible = false
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
    }

    // ── Run-options popup (auto-approve / auto-build / auto-fix) ─────────────
    // State is tracked via JCheckBoxMenuItems; the button label reflects active options.
    private val autoApproveItem = JCheckBoxMenuItem(Strings.Agent.AUTO_APPROVE_NEXT).apply {
        isSelected = isAutoApprove()
        addActionListener { onAutoApproveChanged(isSelected); updateRunOptionsButton() }
    }
    private val autoBuildItem = JCheckBoxMenuItem(Strings.Agent.AUTO_BUILD_NEXT).apply {
        isSelected = isAutoBuild()
        addActionListener { onAutoBuildChanged(isSelected); updateRunOptionsButton() }
    }
    private val autoFixItem = JCheckBoxMenuItem(Strings.Agent.AUTO_FIX_NEXT).apply {
        isSelected = isAutoFix()
        addActionListener { onAutoFixChanged(isSelected); updateRunOptionsButton() }
    }
    private val runOptionsPopup = JPopupMenu().apply {
        val licensed = runCatching { LicenseChecker.isLicensed() == true }.getOrElse { false }
        if (!licensed) {
            val proLabel = JBLabel(Strings.Agent.RUN_OPTIONS_PRO_REQUIRED).apply {
                foreground = LlamatikTheme.onSurfaceVariant
                font = font.deriveFont(Font.BOLD, font.size2D - 1f)
                border = JBUI.Borders.empty(6, 12, 4, 12)
            }
            add(proLabel)
            addSeparator()
            autoApproveItem.isEnabled = false
            autoBuildItem.isEnabled = false
            autoFixItem.isEnabled = false
        }
        add(autoApproveItem)
        add(autoBuildItem)
        add(autoFixItem)
    }
    private val runOptionsButton = JButton().apply {
        isFocusPainted = false
        isContentAreaFilled = false
        border = JBUI.Borders.compound(
            BorderFactory.createLineBorder(LlamatikTheme.outline, 1, true),
            JBUI.Borders.empty(3, 8),
        )
        font = font.deriveFont(Font.PLAIN, font.size2D - 1f)
        addActionListener {
            runOptionsPopup.show(this, 0, height)
        }
    }

    // ── Spinner ───────────────────────────────────────────────────────────────
    private val loadingSpinner = AsyncProcessIcon("agent-loading").apply { isVisible = false }
    private val spinnerPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        isOpaque = false
        isVisible = false
        add(loadingSpinner)
    }

    // ── Verification panel ────────────────────────────────────────────────────
    private val verifyStatusLabel = JBLabel("").apply {
        isVisible = false
        font = font.deriveFont(Font.BOLD)
        border = JBUI.Borders.emptyTop(8)
        alignmentX = LEFT_ALIGNMENT
    }
    // Build errors shown as plain non-editable text — no nested scroll pane.
    private val verifyErrorArea = JBTextArea("").apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        background = cardBackground
        foreground = LlamatikTheme.onSurface
        border = JBUI.Borders.empty(4, 0)
        font = font.deriveFont(Font.PLAIN, font.size2D - 1f)
    }
    private val verifyErrorContent = JPanel(BorderLayout()).apply {
        isOpaque = false
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        add(verifyErrorArea, BorderLayout.CENTER)
        isVisible = false
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
        alignmentX = LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        add(verifyStatusLabel)
        add(javax.swing.Box.createVerticalStrut(4))
        add(verifyErrorContent)
        add(javax.swing.Box.createVerticalStrut(4))
        add(autoFixButton)
    }

    // ── Scroll-to-bottom button ───────────────────────────────────────────────
    private val scrollToBottomButton = object : JButton(Strings.Agent.SCROLL_TO_BOTTOM) {
        private val arc = 20f
        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.color = LlamatikTheme.primary
            g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc))
            g2.dispose()
            super.paintComponent(g)
        }
    }.apply {
        foreground = LlamatikTheme.onPrimary
        isFocusPainted = false
        isContentAreaFilled = false
        isBorderPainted = false
        isOpaque = false
        border = JBUI.Borders.empty(6, 16)
        font = font.deriveFont(Font.PLAIN, font.size2D - 1f)
        cursor = java.awt.Cursor(java.awt.Cursor.HAND_CURSOR)
        isVisible = false
    }

    // ── Navigation buttons ────────────────────────────────────────────────────
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
        preferredSize = Dimension(32, 32)
        border = JBUI.Borders.compound(
            BorderFactory.createLineBorder(LlamatikTheme.outline, 1, true),
            JBUI.Borders.empty(3, 5),
        )
    }

    // Tracks which CardLayout card is currently visible so hideReadinessOverlay()
    // doesn't clobber CARD_RESPONSE when called after generation finishes.
    private var currentCard = CARD_WELCOME

    private fun showCard(name: String) {
        currentCard = name
        cardLayout.show(cardPanel, name)
    }

    // ── Streaming state ───────────────────────────────────────────────────────
    private val streamBuffer = StringBuilder()
    private var streamingBlocked = false
    private val thinkBuffer = StringBuilder()
    private var thinkingBlocked = false
    private var currentGenerationId = 0
    private val blockedPrefixes = listOf(
        "<tool_call", "<edit_proposal", "<action>", "<action ",
        "</answer>", "</result>", "</results>",
    )
    // Tags that must never appear in the reasoning (think) text area — model-specific stop/control tokens.
    private val thinkBlockedPrefixes = blockedPrefixes + listOf(
        "</think>", "<|im_end|>", "<|endoftext|>", "<|end|>", "<wait",
    )

    // ── Skills info label (read-only, response card bottom bar) ──────────────
    private val skillService = service<SkillService>()
    private val activeSkillsLabel = JBLabel("").apply {
        foreground = LlamatikTheme.onSurfaceVariant
        font = font.deriveFont(java.awt.Font.PLAIN, font.size2D - 1f)
        border = JBUI.Borders.compound(
            BorderFactory.createLineBorder(LlamatikTheme.outline, 1, true),
            JBUI.Borders.empty(3, 8),
        )
        isVisible = false
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
    private val imageBar = project?.let {
        ImageAttachmentBar(it, isMultimodalEnabled, { /* onChange — no-op, we read on send */ }, isLicensed)
    }

    // ── Composer ──────────────────────────────────────────────────────────────
    private val composerInput = object : JBTextArea(3, 40) {
        override fun paintComponent(g: java.awt.Graphics) {
            super.paintComponent(g)
            if (text.isEmpty() && !isFocusOwner) {
                val g2 = g.create() as java.awt.Graphics2D
                g2.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g2.color = LlamatikTheme.onSurfaceVariant
                g2.font = font.deriveFont(java.awt.Font.ITALIC)
                val ins = insets
                g2.drawString(Strings.Composer.INPUT_PLACEHOLDER, ins.left, ins.top + g2.fontMetrics.ascent)
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
        toolTipText = Strings.Composer.INPUT_TOOLTIP_AGENT
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

    // ── Init ──────────────────────────────────────────────────────────────────
    init {
        background = rootBackground
        border = JBUI.Borders.empty()

        // Wire up thinking section: toggle button + expandable content (no inner scroll)
        val thinkHeaderRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 32)
            add(thinkToggleBtn)
        }
        thinkingSection.add(thinkHeaderRow)
        thinkingSection.add(thinkContent)

        thinkToggleBtn.addActionListener {
            val expand = !thinkContent.isVisible
            thinkContent.isVisible = expand
            val prefix = if (expand) Strings.Agent.REASONING_PREFIX_EXPANDED
                         else Strings.Agent.REASONING_PREFIX_COLLAPSED
            thinkToggleBtn.text = if (thinkElapsedMs > 0) "$prefix${formatElapsed(thinkElapsedMs)}"
                                  else Strings.Agent.REASONING_STREAMING
            conversationPanel.revalidate()
            conversationPanel.repaint()
        }

        // Wire up trace section
        val traceHeaderRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 32)
            add(traceToggleBtn)
        }
        traceSection.add(traceHeaderRow)
        traceSection.add(traceContent)
        traceToggleBtn.addActionListener {
            val expand = !traceContent.isVisible
            traceContent.isVisible = expand
            traceToggleBtn.text = if (expand) Strings.Agent.TRACE_EXPANDED else Strings.Agent.TRACE_COLLAPSED
            conversationPanel.revalidate()
            conversationPanel.repaint()
        }

        cardPanel.add(buildWelcomeCard(), CARD_WELCOME)
        cardPanel.add(buildTaskCard(), CARD_TASK)
        cardPanel.add(buildOverlayCard(""), CARD_OVERLAY)
        cardPanel.add(buildResponseCard(), CARD_RESPONSE)

        add(cardPanel, BorderLayout.CENTER)
        add(buildComposer(), BorderLayout.SOUTH)
        showCard(CARD_WELCOME)

        backButton.addActionListener { showCard(CARD_WELCOME) }
        clearButton.addActionListener {
            elapsedTimer.stop()
            onClear()
            showCard(CARD_WELCOME)
        }

        composerSendButton.addActionListener {
            val text = composerInput.text.trim()
            if (text.isNotEmpty()) {
                val images = imageBar?.currentAttachments() ?: emptyList()
                onSend(text, images)
            }
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
                    if (text.isNotEmpty()) {
                        val images = imageBar?.currentAttachments() ?: emptyList()
                        onSend(text, images)
                    }
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

        if (project != null) AtMentionHandler(composerInput, project, imageBar)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun refreshModelPicker() = modelPickerButton.refresh()

    fun refreshActiveSkills() = refreshActiveSkillsLabel()

    private fun refreshActiveSkillsLabel() {
        val active = skillService.activeSkills()
        if (active.isEmpty()) {
            activeSkillsLabel.isVisible = false
        } else {
            val names = active.joinToString(", ") { it.displayName }
            activeSkillsLabel.text = "<html><span style='color:${LlamatikTheme.htmlOnSurfaceVariant};'>Skills: $names</span></html>"
            activeSkillsLabel.isVisible = true
        }
    }

    fun prefill(text: String) {
        composerInput.text = text
        composerInput.requestFocusInWindow()
        composerInput.caretPosition = composerInput.document.length
    }

    fun getComposerText(): String = composerInput.text.trim()

    fun clearComposer() {
        composerInput.text = ""
        imageBar?.clear()
    }

    fun setGenerating(generating: Boolean) {
        composerStopButton.isEnabled = generating
        composerSendButton.isEnabled = !generating
        composerInput.isEnabled = !generating
        if (!generating) stopSpinnerAndTimer()
    }

    private fun stopSpinnerAndTimer() {
        elapsedTimer.stop()
        loadingSpinner.suspend()
        loadingSpinner.isVisible = false
        spinnerPanel.isVisible = false
        statusPill.isVisible = false
    }

    fun setComposerEnabled(enabled: Boolean) {
        composerInput.isEnabled = enabled
        composerSendButton.isEnabled = enabled
    }

    fun setComposerTooltip(text: String) {
        composerInput.toolTipText = text
    }

    fun startResponse(prompt: String): Int {
        currentGenerationId++
        genStartMs = System.currentTimeMillis()

        promptLabel.text = "<html>${prompt.replace("\n", "<br>")}</html>"
        promptBubble.isVisible = true

        thinkHasContent = false
        thinkStartMs = 0L
        thinkElapsedMs = 0L
        thinkTextArea.text = ""
        thinkContent.isVisible = false
        thinkToggleBtn.text = Strings.Agent.REASONING_STREAMING
        thinkingSection.isVisible = false

        toolCallsPanel.removeAll()
        toolCallsPanel.isVisible = false

        responseTextArea.text = ""

        traceTextArea.text = ""
        traceContent.isVisible = false
        traceToggleBtn.text = Strings.Agent.TRACE_COLLAPSED
        traceSection.isVisible = false

        responseActionsPanel.removeAll()
        responseActionsPanel.isVisible = false

        proposalsPanel.removeAll()
        proposalsPanel.isVisible = false
        hideVerification()

        streamBuffer.clear()
        streamingBlocked = false
        thinkBuffer.clear()
        thinkingBlocked = false

        statusPill.text = Strings.Agent.STATUS_ANALYZING
        statusPill.isVisible = true
        loadingSpinner.resume()
        loadingSpinner.isVisible = true
        spinnerPanel.isVisible = true  // spinnerPanel lives in the bottom bar

        elapsedTimer.stop()
        elapsedLabel.text = "0s"
        elapsedTimer.start()
        tokenLabel.text = "0 tokens"

        autoApproveItem.isSelected = isAutoApprove()
        autoBuildItem.isSelected = isAutoBuild()
        autoFixItem.isSelected = isAutoFix()
        updateRunOptionsButton()

        conversationScroll.viewport.viewPosition = java.awt.Point(0, 0)

        showCard(CARD_RESPONSE)
        cardPanel.revalidate()
        cardPanel.repaint()
        return currentGenerationId
    }

    fun setStatus(message: String) {
        // A new round is starting — unblock streaming so round 2+ thinking/answer tokens are accepted.
        thinkingBlocked = false
        streamingBlocked = false
        statusPill.text = message
        statusPill.isVisible = true
    }

    fun appendThinkingToken(token: String, genId: Int) {
        if (genId != currentGenerationId || thinkingBlocked) return
        statusPill.isVisible = false
        if (!thinkHasContent) {
            thinkHasContent = true
            thinkStartMs = System.currentTimeMillis()
        }
        // Re-expand the thinking section when a new round starts (it may have been collapsed
        // after round 1's </think> tag caused thinkContent to hide).
        if (!thinkingSection.isVisible || !thinkContent.isVisible) {
            thinkStartMs = System.currentTimeMillis()
            thinkingSection.isVisible = true
            thinkContent.isVisible = true
            thinkToggleBtn.text = Strings.Agent.REASONING_STREAMING
            conversationPanel.revalidate()
        }
        flushToArea(token, thinkBuffer, thinkTextArea)
        scrollToBottom()
    }

    fun appendAnswerToken(token: String, genId: Int) {
        if (genId != currentGenerationId || streamingBlocked) return
        statusPill.isVisible = false
        if (thinkHasContent && thinkContent.isVisible) {
            thinkElapsedMs = System.currentTimeMillis() - thinkStartMs
            thinkContent.isVisible = false
            thinkToggleBtn.text = Strings.Agent.REASONING_PREFIX_COLLAPSED + formatElapsed(thinkElapsedMs)
            conversationPanel.revalidate()
        }
        flushToArea(token, streamBuffer, responseTextArea)
        scrollToBottom()
    }

    fun updateTokenCount(count: Int) {
        tokenLabel.text = formatTokenCount(count)
    }

    private fun formatTokenCount(count: Int): String = when {
        count >= 1_000 -> "${count / 1_000}.${(count % 1_000) / 100}k tokens"
        else -> "$count tokens"
    }

    fun addToolCall(name: String, arg: String) {
        toolCallsPanel.add(buildToolCallRow(name, arg))
        toolCallsPanel.isVisible = true
        toolCallsPanel.revalidate()
        toolCallsPanel.repaint()
        scrollToBottom()
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

        elapsedLabel.text = Strings.Agent.brewedFor(formatElapsed(System.currentTimeMillis() - genStartMs))
        stopSpinnerAndTimer()

        if (thinkHasContent && thinkContent.isVisible) {
            thinkElapsedMs = System.currentTimeMillis() - thinkStartMs
            thinkContent.isVisible = false
            thinkToggleBtn.text = Strings.Agent.REASONING_PREFIX_COLLAPSED + formatElapsed(thinkElapsedMs)
            conversationPanel.revalidate()
        }

        if (text.isNotBlank()) {
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
            val licensed = runCatching { LicenseChecker.isLicensed() == true }.getOrElse { false }
            val freeTierPaywall = !licensed && proposals.size > 1
            val lockFunctions = mutableListOf<() -> Unit>()
            proposals.forEachIndexed { index, proposal ->
                val onApplyWrapped: (EditProposal) -> Unit = { p ->
                    onApply(p)
                    if (freeTierPaywall) lockFunctions.filterIndexed { i, _ -> i != index }.forEach { it() }
                }
                val (card, lockAccept) = buildProposalCard(proposal, onViewDiff, onApplyWrapped, onRejectProposal)
                lockFunctions.add(lockAccept)
                proposalsPanel.add(card)
                proposalsPanel.add(vStrut(6))
            }
            proposalsPanel.isVisible = true
        } else {
            proposalsPanel.isVisible = false
        }

        conversationPanel.revalidate()
        conversationPanel.repaint()
        scrollToBottom()
    }

    fun showAgentTrace(traceText: String) {
        if (traceText.isBlank()) return
        traceTextArea.text = traceText
        traceToggleBtn.text = Strings.Agent.TRACE_COLLAPSED
        traceContent.isVisible = false
        traceSection.isVisible = true
        conversationPanel.revalidate()
        conversationPanel.repaint()
    }

    fun showResponseActions(
        showFixIt: Boolean,
        isLicensed: Boolean,
        onFixIt: () -> Unit,
        onCreatePlan: () -> Unit,
        onExplainMore: () -> Unit,
    ) {
        responseActionsPanel.removeAll()
        if (showFixIt) {
            val fixBtn = actionButton(Strings.Agent.RESPONSE_ACTION_FIX_IT) {
                if (isLicensed) onFixIt() else onCreatePlan()
            }
            val planBtn = actionButton(Strings.Agent.RESPONSE_ACTION_CREATE_PLAN) { onCreatePlan() }
            val explainBtn = actionButton(Strings.Agent.RESPONSE_ACTION_EXPLAIN_MORE) { onExplainMore() }
            responseActionsPanel.add(fixBtn)
            responseActionsPanel.add(planBtn)
            responseActionsPanel.add(explainBtn)
            responseActionsPanel.isVisible = true
        } else {
            responseActionsPanel.isVisible = false
        }
        responseActionsPanel.revalidate()
        responseActionsPanel.repaint()
    }

    fun showReadinessOverlay(state: PluginReadinessState, trialExpired: Boolean) {
        val message = when {
            trialExpired -> Strings.Agent.OVERLAY_TRIAL_EXPIRED
            state == PluginReadinessState.NO_MODEL -> Strings.Agent.OVERLAY_NO_MODEL
            state == PluginReadinessState.LOADING_MODEL -> Strings.Agent.OVERLAY_LOADING_MODEL
            else -> return
        }
        cardPanel.add(buildOverlayCard(message), CARD_OVERLAY)
        showCard(CARD_OVERLAY)
        cardPanel.revalidate()
        cardPanel.repaint()
    }

    fun hideReadinessOverlay() {
        removeDownloadOverlayListener()
        // Only return to welcome if we're actually on the overlay — don't
        // clobber CARD_RESPONSE when syncState() fires after generation ends.
        if (currentCard == CARD_OVERLAY) {
            showCard(CARD_WELCOME)
            cardPanel.revalidate()
            cardPanel.repaint()
        }
    }

    private var downloadOverlayListener: ((DownloadProgressState) -> Unit)? = null
    private var downloadOverlayModelId: String? = null

    fun showDownloadingOverlay(modelId: String, displayName: String, diskSizeGb: Double) {
        removeDownloadOverlayListener()
        downloadOverlayModelId = modelId

        val downloadManager = service<ModelDownloadManager>()
        val totalEstimatedBytes = (diskSizeGb * 1_073_741_824).toLong()

        val bar = JProgressBar(0, 100).apply {
            isIndeterminate = true
            preferredSize = Dimension(260, 14)
            maximumSize = Dimension(Int.MAX_VALUE, 14)
        }
        val progressLabel = JBLabel("Connecting…").apply {
            foreground = mutedText
            font = font.deriveFont(Font.PLAIN, font.size2D - 1f)
        }
        val cancelBtn = JButton(Strings.Agent.OVERLAY_DOWNLOADING_CANCEL).apply {
            isFocusPainted = false
            isContentAreaFilled = false
            border = JBUI.Borders.compound(
                BorderFactory.createLineBorder(mutedText, 1, true),
                JBUI.Borders.empty(3, 8),
            )
            addActionListener {
                downloadManager.cancelDownload(modelId)
                removeDownloadOverlayListener()
            }
        }

        val listener: (DownloadProgressState) -> Unit = { state ->
            SwingUtilities.invokeLater {
                when (state.status) {
                    ModelStatus.DOWNLOADING -> {
                        if (state.totalBytes > 0) {
                            bar.isIndeterminate = false
                            bar.value = ((state.bytesDownloaded * 100) / state.totalBytes).toInt()
                        }
                        val dlMb = state.bytesDownloaded / 1_048_576.0
                        val totalMb = (if (state.totalBytes > 0) state.totalBytes else totalEstimatedBytes) / 1_048_576.0
                        progressLabel.text = "%.0f / %.0f MB".format(dlMb, totalMb)
                    }
                    ModelStatus.DOWNLOADED -> removeDownloadOverlayListener()
                    ModelStatus.NOT_DOWNLOADED, ModelStatus.FAILED -> removeDownloadOverlayListener()
                    else -> {}
                }
            }
        }
        downloadOverlayListener = listener
        downloadManager.addProgressListener(modelId, listener)

        val existingProgress = downloadManager.getProgress(modelId)
        if (existingProgress != null && existingProgress.totalBytes > 0) {
            bar.isIndeterminate = false
            bar.value = ((existingProgress.bytesDownloaded * 100) / existingProgress.totalBytes).toInt()
            val dlMb = existingProgress.bytesDownloaded / 1_048_576.0
            val totalMb = existingProgress.totalBytes / 1_048_576.0
            progressLabel.text = "%.0f / %.0f MB".format(dlMb, totalMb)
        }

        val titleLabel = JBLabel(
            "<html><b>${Strings.Agent.OVERLAY_DOWNLOADING_TITLE}</b><br>" +
                "<span style='color:${LlamatikTheme.htmlOnSurfaceVariant};'>$displayName</span></html>",
        ).apply {
            foreground = LlamatikTheme.onSurface
            horizontalAlignment = JLabel.CENTER
            border = JBUI.Borders.emptyBottom(14)
        }

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            titleLabel.alignmentX = CENTER_ALIGNMENT
            bar.alignmentX = CENTER_ALIGNMENT
            progressLabel.alignmentX = CENTER_ALIGNMENT
            cancelBtn.alignmentX = CENTER_ALIGNMENT
            add(titleLabel)
            add(bar)
            add(javax.swing.Box.createVerticalStrut(6))
            add(progressLabel)
            add(javax.swing.Box.createVerticalStrut(10))
            add(cancelBtn)
        }

        val overlay = JPanel(BorderLayout()).apply {
            background = rootBackground
            add(
                JPanel(BorderLayout()).apply {
                    background = cardBackground
                    border = JBUI.Borders.compound(
                        BorderFactory.createLineBorder(panelStroke, 1, true),
                        JBUI.Borders.empty(22),
                    )
                    add(content, BorderLayout.CENTER)
                },
                BorderLayout.CENTER,
            )
            border = JBUI.Borders.empty(40, 20)
        }

        cardPanel.add(overlay, CARD_OVERLAY)
        showCard(CARD_OVERLAY)
        cardPanel.revalidate()
        cardPanel.repaint()
    }

    private fun removeDownloadOverlayListener() {
        val id = downloadOverlayModelId ?: return
        val listener = downloadOverlayListener ?: return
        service<ModelDownloadManager>().removeProgressListener(id, listener)
        downloadOverlayListener = null
        downloadOverlayModelId = null
    }

    fun hideVerification() {
        verificationPanel.isVisible = false
    }

    fun showVerifying(message: String) {
        verifyStatusLabel.text = message
        verifyStatusLabel.foreground = mutedText
        verifyStatusLabel.isVisible = true
        verifyErrorContent.isVisible = false
        autoFixButton.isVisible = false
        verificationPanel.isVisible = true
        conversationPanel.revalidate()
        conversationPanel.repaint()
        scrollToBottom()
    }

    fun showBuildSuccess(testsRan: Boolean) {
        verifyStatusLabel.text = if (testsRan) Strings.Verification.TESTS_PASSED else Strings.Verification.BUILD_OK
        verifyStatusLabel.foreground = success
        verifyStatusLabel.isVisible = true
        verifyErrorContent.isVisible = false
        autoFixButton.isVisible = false
        verificationPanel.isVisible = true
        conversationPanel.revalidate()
        conversationPanel.repaint()
        scrollToBottom()
    }

    fun showBuildErrors(errors: String, onAutoFix: () -> Unit) {
        verifyStatusLabel.text = Strings.Verification.BUILD_FAILED
        verifyStatusLabel.foreground = LlamatikTheme.error
        verifyStatusLabel.isVisible = true
        verifyErrorArea.text = errors
        verifyErrorContent.isVisible = true
        for (l in autoFixButton.actionListeners) autoFixButton.removeActionListener(l)
        autoFixButton.text = Strings.Verification.AUTO_FIX
        autoFixButton.addActionListener { onAutoFix() }
        autoFixButton.isVisible = true
        verificationPanel.isVisible = true
        conversationPanel.revalidate()
        conversationPanel.repaint()
        scrollToBottom()
    }

    fun showAutoFixLimit() {
        verifyStatusLabel.text = Strings.Verification.RETRY_LIMIT
        verifyStatusLabel.foreground = mutedText
        verifyStatusLabel.isVisible = true
        autoFixButton.isVisible = false
        verificationPanel.isVisible = true
        conversationPanel.revalidate()
        conversationPanel.repaint()
        scrollToBottom()
    }

    fun updateState(taskState: AgentTaskState?) {
        if (taskState == null) {
            showCard(CARD_WELCOME)
            return
        }
        showCard(CARD_TASK)
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
            taskCard.add(buildPhaseRow(phase, isCurrent = phase == taskState.currentPhase, isDone = phase in taskState.phaseHistory))
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

    // ── Card builders ─────────────────────────────────────────────────────────

    private fun buildWelcomeCard(): javax.swing.JComponent {
        heroTitleLabel = JBLabel("<html><span style='font-size:22pt; font-weight:700; color:${LlamatikTheme.htmlPrimary};'>${Strings.Agent.HERO_TITLE}</span></html>")
        heroSubtitleLabel = JBLabel(
            "<html><span style='color:${LlamatikTheme.htmlOnSurface};'>${Strings.Agent.HERO_SUBTITLE}</span></html>",
        ).apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 4f)
            border = JBUI.Borders.emptyBottom(24)
        }
        val activityCard = roundedCard(cardBackground, accent)
        activityTitleLabel = JBLabel(
            "<html><span style='color:${LlamatikTheme.htmlOnSurface};'>${Strings.Agent.WORKFLOW_TITLE}</span></html>",
        ).apply { font = font.deriveFont(Font.BOLD, font.size2D + 1f) }
        activityBodyLabel = JBLabel(
            "<html><span style='color:${LlamatikTheme.htmlOnSurfaceVariant};'>${Strings.Agent.WORKFLOW_BODY}</span></html>",
        )
        activityCard.add(activityTitleLabel, BorderLayout.NORTH)
        activityCard.add(activityBodyLabel, BorderLayout.CENTER)

        promptsTitleLabel = JBLabel(
            "<html><span style='color:${LlamatikTheme.htmlOnSurface};'>${Strings.Agent.PROMPTS_TITLE}</span></html>",
        ).apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 1f)
            border = JBUI.Borders.empty(24, 0, 10, 0)
        }
        promptCardButtons.clear()
        val promptGrid = JPanel(GridLayout(0, 1, 0, 8)).apply { isOpaque = false }
        suggestions.forEach { promptGrid.add(buildPromptCard(it)) }

        listOf(heroTitleLabel, heroSubtitleLabel, activityCard, promptsTitleLabel, promptGrid).forEach { it.alignmentX = LEFT_ALIGNMENT }

        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = rootBackground
            border = JBUI.Borders.empty(26, 20, 20, 20)
            add(heroTitleLabel); add(heroSubtitleLabel); add(activityCard); add(promptsTitleLabel); add(promptGrid)
        }
        val inner = JPanel(BorderLayout()).apply {
            background = rootBackground
            add(content, BorderLayout.NORTH)
        }
        return JBScrollPane(inner).apply {
            border = JBUI.Borders.empty()
            viewport.background = rootBackground
            background = rootBackground
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }
    }

    fun refreshThemeColors() {
        heroTitleLabel.text = "<html><span style='font-size:22pt; font-weight:700; color:${LlamatikTheme.htmlPrimary};'>${Strings.Agent.HERO_TITLE}</span></html>"
        heroSubtitleLabel.text = "<html><span style='color:${LlamatikTheme.htmlOnSurface};'>${Strings.Agent.HERO_SUBTITLE}</span></html>"
        activityTitleLabel.text = "<html><span style='color:${LlamatikTheme.htmlOnSurface};'>${Strings.Agent.WORKFLOW_TITLE}</span></html>"
        activityBodyLabel.text = "<html><span style='color:${LlamatikTheme.htmlOnSurfaceVariant};'>${Strings.Agent.WORKFLOW_BODY}</span></html>"
        promptsTitleLabel.text = "<html><span style='color:${LlamatikTheme.htmlOnSurface};'>${Strings.Agent.PROMPTS_TITLE}</span></html>"
        promptCardButtons.forEachIndexed { i, btn ->
            btn.text = "<html><span style='color:${LlamatikTheme.htmlOnSurface};'>${suggestions[i]}</span></html>"
        }
        updateRunOptionsButton()
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
            add(rejectButton); add(approveButton)
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
            preferredSize = Dimension(32, 32)
            border = JBUI.Borders.compound(
                BorderFactory.createLineBorder(LlamatikTheme.outline, 1, true),
                JBUI.Borders.empty(3, 5),
            )
            addActionListener {
                CopyPasteManager.getInstance().setContents(StringSelection(responseTextArea.text))
            }
        }

        val rightButtons = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 8)).apply {
            isOpaque = false
            add(clearButton); add(copyButton)
        }

        // Spinner sits inline next to the elapsed timer and token counter in the top bar
        timerRow = JPanel(FlowLayout(FlowLayout.LEFT, 16, 16)).apply {
            isOpaque = false
            add(spinnerPanel)
            add(elapsedLabel)
            add(tokenLabel)
        }

        val topBar = JPanel(BorderLayout(8, 0)).apply {
            background = cardBackground
            isOpaque = true
            border = BorderFactory.createMatteBorder(0, 0, 1, 0, panelStroke)
            add(timerRow, BorderLayout.WEST)
            add(rightButtons, BorderLayout.EAST)
        }

        // Conversation scroll: prompt → status → reasoning → tool calls →
        //                      response text → proposals → verification
        // (inline content only — no controls)
        conversationPanel.removeAll()
        fun addItem(c: javax.swing.JComponent, bottomGap: Int = 8) {
            c.alignmentX = LEFT_ALIGNMENT
            conversationPanel.add(c)
            conversationPanel.add(vStrut(bottomGap))
        }
        addItem(promptBubble)
        addItem(statusRow, 4)
        addItem(thinkingSection)
        addItem(toolCallsPanel, 4)
        addItem(responseContent)
        addItem(responseActionsPanel, 2)
        addItem(traceSection, 4)
        addItem(proposalsPanel)
        conversationPanel.add(verificationPanel.also { it.alignmentX = LEFT_ALIGNMENT })

        // Fixed bottom bar: run options popup + active skills info label
        updateRunOptionsButton()
        refreshActiveSkillsLabel()
        val bottomBar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 6)).apply {
            isOpaque = true
            background = cardBackground
            border = BorderFactory.createMatteBorder(1, 0, 0, 0, panelStroke)
            add(runOptionsButton)
            add(activeSkillsLabel)
        }

        // Gradient overlay — fades cardBackground from transparent at top to opaque at bottom
        val gradientHeight = 64
        val gradientOverlay = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                val base = cardBackground
                val transparent = Color(base.red, base.green, base.blue, 0)
                val opaque = Color(base.red, base.green, base.blue, 220)
                g2.paint = GradientPaint(0f, 0f, transparent, 0f, height.toFloat(), opaque)
                g2.fillRect(0, 0, width, height)
                g2.dispose()
            }
        }.apply {
            isOpaque = false
            isVisible = false
            preferredSize = Dimension(0, gradientHeight)
        }

        // Scroll-to-bottom button — centred inside a GridBagLayout wrapper
        scrollToBottomButton.addActionListener { scrollToBottom() }
        val scrollBtnWrapper = JPanel(GridBagLayout()).apply {
            isOpaque = false
            val gbc = GridBagConstraints()
            gbc.anchor = GridBagConstraints.CENTER
            add(scrollToBottomButton, gbc)
        }

        // Scroll listener — show/hide the button when not at bottom
        conversationScroll.verticalScrollBar.addAdjustmentListener { e: AdjustmentEvent ->
            val bar = conversationScroll.verticalScrollBar
            val atBottom = bar.value + bar.visibleAmount >= bar.maximum - 8
            scrollToBottomButton.isVisible = !atBottom
            gradientOverlay.isVisible = !atBottom
        }

        // Layer everything: scroll pane at DEFAULT layer, gradient + button at PALETTE layer
        val layered = object : JLayeredPane() {
            override fun doLayout() {
                val w = width; val h = height
                conversationScroll.setBounds(0, 0, w, h)
                gradientOverlay.setBounds(0, h - gradientHeight, w, gradientHeight)
                scrollBtnWrapper.setBounds(0, h - gradientHeight, w, gradientHeight)
            }
        }.apply {
            add(conversationScroll, JLayeredPane.DEFAULT_LAYER)
            add(gradientOverlay, JLayeredPane.PALETTE_LAYER)
            add(scrollBtnWrapper, JLayeredPane.PALETTE_LAYER)
        }

        return JPanel(BorderLayout()).apply {
            background = cardBackground
            isOpaque = true
            add(topBar, BorderLayout.NORTH)
            add(layered, BorderLayout.CENTER)
            add(bottomBar, BorderLayout.SOUTH)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

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
            addActionListener { showInferenceDialog(this) }
        }

        val footerLeft = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(modelPickerButton)
            if (imageBar != null) add(imageBar.attachButton)
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
            if (imageBar != null) add(imageBar, BorderLayout.NORTH)
            add(inputScroll, BorderLayout.CENTER)
            add(footer, BorderLayout.SOUTH)
            if (imageBar != null) imageBar.installDropTarget(this)
        }

        return JPanel(BorderLayout()).apply {
            background = rootBackground
            border = JBUI.Borders.empty(10, 12, 8, 12)
            add(shell, BorderLayout.CENTER)
        }
    }

    private fun vStrut(height: Int): javax.swing.JComponent =
        (javax.swing.Box.createVerticalStrut(height) as javax.swing.JComponent).also {
            it.alignmentX = LEFT_ALIGNMENT
        }

    private fun scrollToBottom() {
        javax.swing.SwingUtilities.invokeLater {
            val bar = conversationScroll.verticalScrollBar
            bar.value = bar.maximum
        }
    }

    private fun buildPromptCard(text: String): JButton =
        object : JButton("<html><span style='color:${LlamatikTheme.htmlOnSurface};'>$text</span></html>") {
            private val arc = 12
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = cardBackground
                g2.fill(java.awt.geom.RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc.toFloat(), arc.toFloat()))
                g2.color = accent
                g2.draw(java.awt.geom.RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc.toFloat(), arc.toFloat()))
                g2.dispose()
                super.paintComponent(g)
            }
        }.apply {
            horizontalAlignment = JButton.LEFT
            isFocusPainted = false
            isContentAreaFilled = false
            isOpaque = false
            border = JBUI.Borders.empty(10, 14)
            addActionListener { onSuggestionSelected(text) }
            promptCardButtons.add(this)
        }

    private fun buildToolCallRow(name: String, arg: String): JPanel =
        JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            isOpaque = false
            add(JBLabel(Strings.Agent.TOOL_CALL_ICON).apply { foreground = mutedText })
            add(JBLabel("<html><span style='font-family:monospace; color:${LlamatikTheme.htmlOnSurface};'>$name</span></html>"))
            if (arg.isNotBlank()) {
                add(JBLabel("<html><span style='font-family:monospace; color:${LlamatikTheme.htmlOnSurfaceVariant};'>$arg</span></html>"))
            }
        }

    private fun buildProposalCard(
        proposal: EditProposal,
        onViewDiff: (EditProposal) -> Unit,
        onApply: (EditProposal) -> Unit,
        onReject: (EditProposal) -> Unit,
    ): Pair<JPanel, () -> Unit> {
        val card = JPanel(BorderLayout(0, 6)).apply {
            isOpaque = false
            border = JBUI.Borders.compound(
                BorderFactory.createMatteBorder(0, 0, 1, 0, panelStroke),
                JBUI.Borders.empty(10, 0),
            )
            alignmentX = LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }
        val fileLabel = JBLabel("<html><b>${java.io.File(proposal.filePath).name}</b></html>").apply {
            foreground = LlamatikTheme.onSurface
        }
        val pathLabel = JBLabel(
            "<html><span style='color:${LlamatikTheme.htmlOnSurfaceVariant};'>${proposal.filePath}</span></html>",
        ).apply { border = JBUI.Borders.emptyTop(2) }
        val summaryLabel = JBLabel(
            "<html><span style='color:${LlamatikTheme.htmlOnSurfaceVariant};'>${proposal.summary}</span></html>",
        ).apply { border = JBUI.Borders.emptyTop(2) }
        val labelStack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(fileLabel); add(pathLabel); add(summaryLabel)
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
                card.revalidate(); card.repaint()
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
            card.revalidate(); card.repaint()
        }

        val upgradeLabel = JBLabel(Strings.EditDialog.FREE_PLAN_LIMIT).apply {
            foreground = LlamatikTheme.onSurfaceVariant
            font = font.deriveFont(Font.ITALIC, font.size2D - 1f)
            border = JBUI.Borders.empty(2, 0, 0, 0)
            isVisible = false
        }

        val lockAccept: () -> Unit = {
            acceptButton.isEnabled = false
            upgradeLabel.isVisible = true
            card.revalidate(); card.repaint()
        }

        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            isOpaque = false
            add(statusLabel); add(diffButton); add(rejectButton); add(acceptButton)
        }

        val south = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            buttons.alignmentX = RIGHT_ALIGNMENT
            upgradeLabel.alignmentX = RIGHT_ALIGNMENT
            add(buttons)
            add(upgradeLabel)
        }

        card.add(labelStack, BorderLayout.NORTH)
        card.add(south, BorderLayout.SOUTH)
        return Pair(card, lockAccept)
    }

    private fun buildPhaseRow(phase: AgentPhase, isCurrent: Boolean, isDone: Boolean): JPanel {
        val icon = when { isDone -> "✓"; isCurrent -> "●"; else -> "○" }
        val color = when { isDone -> success; isCurrent -> accent; else -> mutedText }
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 0)
            add(JLabel("$icon  ${phase.name.replace('_', ' ')}").apply {
                foreground = color
                if (isCurrent) font = font.deriveFont(Font.BOLD)
            }, BorderLayout.WEST)
        }
    }

    private fun flushToArea(token: String, buffer: StringBuilder, target: JBTextArea) {
        val allBlocked = if (target === thinkTextArea) thinkBlockedPrefixes
                         else blockedPrefixes + listOf("<think>", "<think ")

        buffer.append(token)
        // Strip any </think> closing tag that may arrive before tool calls in the answer stream
        if (target !== thinkTextArea) {
            val cleaned = buffer.toString()
                .replace(Regex("</think>", RegexOption.IGNORE_CASE), "")
            buffer.clear()
            buffer.append(cleaned)
        }
        val buf = buffer.toString()
        val bufLower = buf.lowercase()

        // Case-insensitive match for all blocked prefixes
        val blockAt = allBlocked.mapNotNull { p -> bufLower.indexOf(p.lowercase()).takeIf { it >= 0 } }.minOrNull()
        if (blockAt != null) {
            val safe = buf.substring(0, blockAt).trimEnd()
            if (safe.isNotEmpty()) {
                target.append(safe)
                target.caretPosition = target.document.length
            }
            if (target === thinkTextArea) thinkingBlocked = true else streamingBlocked = true
            return
        }

        val maxPrefixLen = allBlocked.maxOf { it.length }
        val tail = bufLower.takeLast(maxPrefixLen)
        val holdLen = (1..tail.length).reversed().firstOrNull { len ->
            val candidate = tail.takeLast(len)
            allBlocked.any { it.lowercase().startsWith(candidate) }
        } ?: 0

        val safeEnd = buf.length - holdLen
        if (safeEnd > 0) {
            val safe = buf.substring(0, safeEnd)
            buffer.clear()
            buffer.append(buf.substring(safeEnd))
            target.append(safe)
            target.caretPosition = target.document.length
        }
    }

    private fun updateRunOptionsButton() {
        val active = listOfNotNull(
            if (autoApproveItem.isSelected) "Auto-apply" else null,
            if (autoBuildItem.isSelected) "Auto-build" else null,
            if (autoFixItem.isSelected) "Auto-fix" else null,
        )
        val label = if (active.isEmpty()) "Run options ▾" else "${active.joinToString(", ")} ▾"
        runOptionsButton.text = "<html><span style='color:${LlamatikTheme.htmlOnSurfaceVariant};'>$label</span></html>"
    }

    private fun actionButton(label: String, onClick: () -> Unit): JButton =
        JButton(label).apply {
            isFocusPainted = false
            isContentAreaFilled = false
            border = JBUI.Borders.compound(
                BorderFactory.createLineBorder(LlamatikTheme.outline, 1, true),
                JBUI.Borders.empty(4, 10),
            )
            font = font.deriveFont(Font.PLAIN, font.size2D - 1f)
            addActionListener { onClick() }
        }

    private fun formatElapsed(ms: Long): String {
        val secs = ms / 1000
        val mins = secs / 60
        return if (mins > 0) "${mins}m ${secs % 60}s" else "${secs}s"
    }

    private fun roundedCard(bg: java.awt.Color, borderColor: java.awt.Color, arc: Int = 12, layout: java.awt.LayoutManager = BorderLayout(0, 8)): JPanel =
        object : JPanel(layout) {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = bg
                g2.fill(java.awt.geom.RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc.toFloat(), arc.toFloat()))
                g2.color = borderColor
                g2.draw(java.awt.geom.RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc.toFloat(), arc.toFloat()))
                g2.dispose()
            }
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(14, 16)
        }

    private fun showInferenceDialog(anchor: java.awt.Component) {
        val settings = com.intellij.openapi.components.service<com.llamatik.teammate.settings.LlamatikAppSettings>()
        var dialog: com.intellij.openapi.ui.DialogWrapper? = null
        val panel = com.llamatik.teammate.settings.InferenceSettingsPanel(onUpgrade = {
            dialog?.close(com.intellij.openapi.ui.DialogWrapper.CANCEL_EXIT_CODE)
            com.intellij.openapi.components.service<com.llamatik.teammate.licensing.TrialService>()
                .requestLicense(com.llamatik.teammate.ui.Strings.Inference.UPGRADE_REQUIRED)
        })
        panel.loadFrom(settings.data())
        dialog = object : com.intellij.openapi.ui.DialogWrapper(false) {
            init {
                title = com.llamatik.teammate.ui.Strings.Inference.DIALOG_TITLE
                init()
            }
            override fun createCenterPanel() = panel.panel
        }
        if (dialog.showAndGet()) {
            panel.applyTo(settings.data())
        }
    }
}
