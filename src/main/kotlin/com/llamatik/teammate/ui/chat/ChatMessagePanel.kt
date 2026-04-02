package com.llamatik.teammate.ui.chat

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTextArea
import com.llamatik.teammate.ui.LlamatikTheme
import com.llamatik.teammate.ui.Strings
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.model.ChatRole
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.datatransfer.StringSelection
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

class ChatMessagePanel(
    private val project: Project,
    private val role: ChatRole,
    roleLabel: String,
    initialContent: String,
    private val onApplyEdit: (() -> Unit)? = null,
    private val onViewDiff: (() -> Unit)? = null,
) : JPanel(BorderLayout()) {

    private val rootBackground = LlamatikTheme.background
    private val assistantBubble = LlamatikTheme.surface
    private val userBubble = LlamatikTheme.primaryContainer
    private val bubbleStroke = LlamatikTheme.outline
    private val userStroke = LlamatikTheme.primary

    private val textArea = JBTextArea(initialContent).apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        foreground = LlamatikTheme.onSurface
        background = if (role == ChatRole.USER) userBubble else assistantBubble
        border = JBUI.Borders.empty(10, 12)
        font = font.deriveFont(font.size2D + 1f)
    }

    private val actions = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
        isOpaque = false
    }

    init {
        isOpaque = true
        background = rootBackground
        border = JBUI.Borders.emptyBottom(10)

        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(6)
        }

        header.add(JLabel("<html><span style='color:${LlamatikTheme.htmlOnSurfaceVariant};'>$roleLabel</span></html>").apply {
            font = font.deriveFont(font.size2D - 1f)
        }, BorderLayout.WEST)

        actions.add(actionButton(Strings.App.COPY) {
            CopyPasteManager.getInstance().setContents(StringSelection(textArea.text))
        })

        if (onViewDiff != null) {
            actions.add(actionButton(Strings.App.VIEW_DIFF) { onViewDiff.invoke() })
        }
        if (onApplyEdit != null) {
            actions.add(actionButton(Strings.App.APPLY_EDIT) { onApplyEdit.invoke() })
        }

        header.add(actions, BorderLayout.EAST)

        val bubbleColor = if (role == ChatRole.USER) userBubble else assistantBubble
        val strokeColor = if (role == ChatRole.USER) userStroke else bubbleStroke
        val bubble = object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = bubbleColor
                g2.fillRoundRect(0, 0, width, height, 16, 16)
                g2.color = strokeColor
                g2.drawRoundRect(0, 0, width - 1, height - 1, 16, 16)
                g2.dispose()
            }
        }.apply {
            isOpaque = false
            border = JBUI.Borders.empty(1)
            add(textArea, BorderLayout.CENTER)
        }

        val stack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(header)
            add(bubble)
        }

        val wrapper = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = if (role == ChatRole.USER) {
                JBUI.Borders.emptyLeft(52)
            } else {
                JBUI.Borders.emptyRight(52)
            }
            add(stack, BorderLayout.CENTER)
        }

        add(wrapper, BorderLayout.CENTER)
    }

    fun appendToken(token: String) {
        textArea.append(token)
        textArea.caretPosition = textArea.document.length
    }

    fun setContent(value: String) {
        textArea.text = value
        textArea.caretPosition = 0
    }

    fun attachEditProposal(onApply: () -> Unit, onViewDiff: () -> Unit) {
        actions.add(actionButton(Strings.App.VIEW_DIFF) { onViewDiff() })
        actions.add(actionButton(Strings.App.APPLY_EDIT) { onApply() })
        actions.revalidate()
        actions.repaint()
    }

    private fun actionButton(text: String, onClick: () -> Unit): JButton =
        JButton("<html><span style='color:${LlamatikTheme.htmlOnSurfaceVariant};'>$text</span></html>").apply {
            isFocusPainted = false
            isContentAreaFilled = false
            border = JBUI.Borders.empty(2, 6)
            addActionListener { onClick() }
        }
}
