package com.llamatik.teammate.ui.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.model.ChatRole
import com.llamatik.teammate.model.ChatSession
import com.llamatik.teammate.ui.LlamatikTheme
import com.llamatik.teammate.ui.Strings
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities

class ChatListPanel(
    private val onSessionSelected: (ChatSession) -> Unit,
    private val onSessionDeleted: (ChatSession) -> Unit,
    private val onNewSession: () -> Unit,
) : JPanel(BorderLayout()) {

    private val sessions = mutableListOf<ChatSession>()
    private var selectedSessionId: String? = null
    var generatingSessionIds: Set<String> = emptySet()
        private set

    private val rootBackground = LlamatikTheme.background
    private val cardBackground = LlamatikTheme.surface

    private val listPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = rootBackground
        border = JBUI.Borders.empty(8, 8, 8, 8)
    }

    private val scrollPane = JBScrollPane(JPanel(BorderLayout()).apply {
        background = rootBackground
        add(listPanel, BorderLayout.NORTH)
    }).apply {
        border = JBUI.Borders.empty()
        viewport.background = rootBackground
        background = rootBackground
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
    }

    init {
        background = rootBackground
        border = BorderFactory.createMatteBorder(0, 1, 0, 0, LlamatikTheme.divider)
        preferredSize = Dimension(240, 0)

        val titleLabel = JBLabel(Strings.ChatList.PANEL_TITLE).apply {
            foreground = LlamatikTheme.onSurface
            font = font.deriveFont(Font.BOLD, font.size2D)
        }
        val newSessionButton = JButton(AllIcons.General.Add).apply {
            toolTipText = Strings.ChatList.NEW_CHAT_TOOLTIP
            isFocusPainted = false
            isContentAreaFilled = false
            isBorderPainted = false
            preferredSize = Dimension(24, 24)
            cursor = java.awt.Cursor(java.awt.Cursor.HAND_CURSOR)
            addActionListener { onNewSession() }
        }

        val header = JPanel(BorderLayout()).apply {
            background = rootBackground
            border = JBUI.Borders.compound(
                BorderFactory.createMatteBorder(0, 0, 1, 0, LlamatikTheme.divider),
                JBUI.Borders.empty(10, 12),
            )
            add(titleLabel, BorderLayout.WEST)
            add(newSessionButton, BorderLayout.EAST)
        }

        add(header, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)

        rebuild()
    }

    fun setSessions(newSessions: List<ChatSession>) {
        sessions.clear()
        sessions.addAll(newSessions)
        SwingUtilities.invokeLater { rebuild() }
    }

    fun setSelectedSession(sessionId: String?) {
        selectedSessionId = sessionId
        SwingUtilities.invokeLater { rebuild() }
    }

    fun setGeneratingSession(sessionId: String?) {
        generatingSessionIds = if (sessionId != null) setOf(sessionId) else emptySet()
        SwingUtilities.invokeLater { rebuild() }
    }

    fun addGeneratingSession(sessionId: String) {
        generatingSessionIds = generatingSessionIds + sessionId
        SwingUtilities.invokeLater { rebuild() }
    }

    fun removeGeneratingSession(sessionId: String) {
        generatingSessionIds = generatingSessionIds - sessionId
        SwingUtilities.invokeLater { rebuild() }
    }

    private fun rebuild() {
        listPanel.removeAll()

        if (sessions.isEmpty()) {
            listPanel.add(JBLabel(Strings.ChatList.EMPTY_HINT).apply {
                foreground = LlamatikTheme.onSurfaceVariant
                font = font.deriveFont(Font.ITALIC, font.size2D - 1f)
                horizontalAlignment = SwingConstants.CENTER
                border = JBUI.Borders.empty(24, 12)
            })
        } else {
            sessions.reversed().forEach { addSessionRow(it) }
        }

        listPanel.revalidate()
        listPanel.repaint()
    }

    private fun sessionTitle(session: ChatSession): String {
        val firstUserMsg = session.messages.firstOrNull { it.role == ChatRole.USER }?.content
            ?: return session.title
        return if (firstUserMsg.length > 46) firstUserMsg.take(43) + "…" else firstUserMsg
    }

    private fun addSessionRow(session: ChatSession) {
        val isSelected = session.id == selectedSessionId
        val rowBg = if (isSelected) LlamatikTheme.surfaceVariant else cardBackground
        val borderColor = if (isSelected) LlamatikTheme.primary else LlamatikTheme.outline

        val row = object : JPanel(BorderLayout(6, 0)) {
            private val arc = 10f
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = rowBg
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc))
                if (isSelected) {
                    g2.color = borderColor
                    g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc))
                }
                g2.dispose()
            }
        }.apply {
            isOpaque = false
            border = JBUI.Borders.compound(
                BorderFactory.createEmptyBorder(2, 0, 2, 0),
                JBUI.Borders.empty(8, 10),
            )
            maximumSize = Dimension(Int.MAX_VALUE, 56)
            cursor = java.awt.Cursor(java.awt.Cursor.HAND_CURSOR)
        }

        val isGenerating = session.id in generatingSessionIds

        val title = sessionTitle(session)
        val textLabel = JBLabel("<html><nobr><span style='color:${LlamatikTheme.htmlOnSurface};'>$title</span></nobr></html>").apply {
            font = font.deriveFont(Font.PLAIN, font.size2D)
        }

        if (isGenerating) {
            val spinner = AsyncProcessIcon("chat-${session.id}").apply {
                resume()
                preferredSize = Dimension(16, 16)
                minimumSize = Dimension(16, 16)
                maximumSize = Dimension(16, 16)
            }
            row.add(JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.emptyRight(6)
                add(spinner, BorderLayout.CENTER)
                preferredSize = Dimension(22, 22)
                minimumSize = Dimension(22, 22)
                maximumSize = Dimension(22, 22)
            }, BorderLayout.WEST)
        }

        val deleteBtn = JButton(AllIcons.Actions.GC).apply {
            toolTipText = Strings.ChatList.DELETE_TOOLTIP
            isFocusPainted = false
            isContentAreaFilled = false
            isBorderPainted = false
            preferredSize = Dimension(22, 22)
            cursor = java.awt.Cursor(java.awt.Cursor.HAND_CURSOR)
            addActionListener { onSessionDeleted(session) }
        }

        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
            isOpaque = false
            add(deleteBtn)
        }

        row.add(textLabel, BorderLayout.CENTER)
        row.add(rightPanel, BorderLayout.EAST)

        row.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                onSessionSelected(session)
            }
        })
        textLabel.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                onSessionSelected(session)
            }
        })

        listPanel.add(row)
        listPanel.add(javax.swing.Box.createVerticalStrut(4))
    }
}
