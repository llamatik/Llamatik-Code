package com.llamatik.teammate.ui.chat

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.AsyncProcessIcon
import com.llamatik.teammate.ui.LlamatikTheme
import com.llamatik.teammate.ui.Strings
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.model.ChatRole
import java.awt.BorderLayout
import java.awt.Dimension
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

    private val assistantBubble = LlamatikTheme.surface
    private val userBubble = LlamatikTheme.primaryContainer
    private val bubbleStroke = LlamatikTheme.outline
    private val userStroke = LlamatikTheme.primary
    private val isUser = role == ChatRole.USER

    private val textArea = JBTextArea(initialContent).apply {
        isEditable = false
        isOpaque = false
        lineWrap = true
        wrapStyleWord = true
        foreground = LlamatikTheme.onSurface
        border = JBUI.Borders.empty(10, 12, 4, 12)
        font = font.deriveFont(font.size2D + 1f)
    }

    private val editActions = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
        isOpaque = false
    }

    private val spinner = AsyncProcessIcon("chat-streaming").apply {
        isVisible = initialContent.isEmpty() && role == ChatRole.ASSISTANT
        if (isVisible) resume()
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(0, 0, 12, 0)

        val bubbleColor = if (isUser) userBubble else assistantBubble
        val strokeColor = if (isUser) userStroke else bubbleStroke

        val roleLabelWidget = JLabel(
            "<html><span style='color:${LlamatikTheme.htmlOnSurfaceVariant};'>$roleLabel</span></html>"
        ).apply {
            font = font.deriveFont(font.size2D - 1f)
        }

        val copyButton = iconButton(AllIcons.Actions.Copy, Strings.App.COPY) {
            CopyPasteManager.getInstance().setContents(StringSelection(textArea.text))
        }

        if (onViewDiff != null) editActions.add(actionButton(Strings.App.VIEW_DIFF) { onViewDiff.invoke() })
        if (onApplyEdit != null) editActions.add(actionButton(Strings.App.APPLY_EDIT) { onApplyEdit.invoke() })

        val spinnerRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            add(spinner)
        }

        val bubbleFooter = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(0, 8, 6, 8)
            add(spinnerRow, BorderLayout.WEST)
            val footerRight = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
                isOpaque = false
                add(editActions)
                add(copyButton)
            }
            add(footerRight, BorderLayout.EAST)
        }

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
            add(bubbleFooter, BorderLayout.SOUTH)
        }

        // Indent: user bubbles pushed right (spacer on left), assistant pushed left (spacer on right).
        // Bubble sits in CENTER so it fills the available width after the spacer.
        val indent = Dimension(100, 0)
        val spacer = JPanel().apply {
            isOpaque = false
            preferredSize = indent
            minimumSize = indent
            maximumSize = Dimension(indent.width, Int.MAX_VALUE)
        }

        val bubbleRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            if (isUser) {
                add(spacer, BorderLayout.WEST)
                add(bubble, BorderLayout.CENTER)
            } else {
                add(bubble, BorderLayout.CENTER)
                add(spacer, BorderLayout.EAST)
            }
        }

        val roleRow = JPanel(if (isUser) FlowLayout(FlowLayout.RIGHT, 2, 0) else FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
            isOpaque = false
            add(roleLabelWidget)
        }

        val stack = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            roleRow.alignmentX = LEFT_ALIGNMENT
            bubbleRow.alignmentX = LEFT_ALIGNMENT
            add(roleRow)
            add(bubbleRow)
        }

        add(stack, BorderLayout.CENTER)
    }

    fun appendToken(token: String) {
        if (spinner.isVisible) {
            spinner.isVisible = false
            spinner.suspend()
        }
        textArea.append(token)
        textArea.caretPosition = textArea.document.length
    }

    fun setContent(value: String) {
        spinner.isVisible = false
        spinner.suspend()
        textArea.text = value
        textArea.caretPosition = 0
    }

    fun attachEditProposal(onApply: () -> Unit, onViewDiff: () -> Unit) {
        editActions.add(actionButton(Strings.App.VIEW_DIFF) { onViewDiff() })
        editActions.add(actionButton(Strings.App.APPLY_EDIT) { onApply() })
        editActions.revalidate()
        editActions.repaint()
    }

    private fun iconButton(icon: javax.swing.Icon, tooltip: String, onClick: () -> Unit): JButton =
        JButton(icon).apply {
            toolTipText = tooltip
            isFocusPainted = false
            isContentAreaFilled = false
            preferredSize = Dimension(24, 24)
            border = JBUI.Borders.empty(2)
            addActionListener { onClick() }
        }

    private fun actionButton(text: String, onClick: () -> Unit): JButton =
        JButton("<html><span style='color:${LlamatikTheme.htmlOnSurfaceVariant};'>$text</span></html>").apply {
            isFocusPainted = false
            isContentAreaFilled = false
            border = JBUI.Borders.empty(2, 6)
            addActionListener { onClick() }
        }
}
