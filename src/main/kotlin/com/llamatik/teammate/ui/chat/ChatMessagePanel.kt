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

    private val rootBackground = JBColor(Color(0x0F0F10), Color(0x0F0F10))
    private val assistantBubble = JBColor(Color(0x1C1C1F), Color(0x1C1C1F))
    private val userBubble = JBColor(Color(0x2A2116), Color(0x2A2116))
    private val bubbleStroke = JBColor(Color(0x35353A), Color(0x35353A))
    private val userStroke = JBColor(Color(0xFFB74D.toInt()), Color(0xFFB74D.toInt()))

    private val textArea = JBTextArea(initialContent).apply {
        isEditable = false
        isOpaque = true
        lineWrap = true
        wrapStyleWord = true
        foreground = JBColor(Color(0xEDEDED.toInt()), Color(0xEDEDED.toInt()))
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

        header.add(JLabel("<html><span style='color:#D6D6D6;'>$roleLabel</span></html>").apply {
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
            isOpaque = true
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
        JButton("<html><span style='color:#D6D6D6;'>$text</span></html>").apply {
            isFocusPainted = false
            isContentAreaFilled = false
            border = JBUI.Borders.empty(2, 6)
            addActionListener { onClick() }
        }
}
