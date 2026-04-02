package com.llamatik.teammate.ui.chat

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.model.ChatRole
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.datatransfer.StringSelection
import javax.swing.BorderFactory
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

    private val rootBackground = JBColor(Color(0x111318), Color(0x111318))
    private val assistantBubble = JBColor(Color(0x1A1E27), Color(0x1A1E27))
    private val userBubble = JBColor(Color(0x202B57), Color(0x202B57))
    private val bubbleStroke = JBColor(Color(0x2F3645), Color(0x2F3645))
    private val userStroke = JBColor(Color(0x5C6CFF), Color(0x5C6CFF))
    private val mutedText = JBColor(Color(0xA0A6B2), Color(0xA0A6B2))

    private val textArea = JBTextArea(initialContent).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        foreground = JBColor.WHITE
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
        border = JBUI.Borders.empty(0, 0, 10, 0)

        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(6)
        }

        header.add(JLabel(roleLabel).apply {
            foreground = mutedText
            font = font.deriveFont(font.size2D - 1f)
        }, BorderLayout.WEST)

        actions.add(actionButton("Copy") {
            CopyPasteManager.getInstance().setContents(StringSelection(textArea.text))
        })

        if (onViewDiff != null) {
            actions.add(actionButton("View Diff") { onViewDiff.invoke() })
        }
        if (onApplyEdit != null) {
            actions.add(actionButton("Apply Edit") { onApplyEdit.invoke() })
        }

        header.add(actions, BorderLayout.EAST)

        val bubble = JPanel(BorderLayout()).apply {
            background = if (role == ChatRole.USER) userBubble else assistantBubble
            border = JBUI.Borders.compound(
                BorderFactory.createLineBorder(
                    if (role == ChatRole.USER) userStroke else bubbleStroke,
                    1,
                    true,
                ),
                JBUI.Borders.empty(),
            )
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
        actions.add(actionButton("View Diff") { onViewDiff() })
        actions.add(actionButton("Apply Edit") { onApply() })
        actions.revalidate()
        actions.repaint()
    }

    private fun actionButton(text: String, onClick: () -> Unit): JButton =
        JButton(text).apply {
            isFocusPainted = false
            isContentAreaFilled = false
            foreground = mutedText
            border = JBUI.Borders.empty(2, 6)
            addActionListener { onClick() }
        }
}
