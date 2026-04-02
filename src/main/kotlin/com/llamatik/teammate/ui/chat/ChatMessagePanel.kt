package com.llamatik.teammate.ui.chat

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.datatransfer.StringSelection
import javax.swing.JButton
import javax.swing.JPanel

class ChatMessagePanel(
    private val project: Project,
    roleLabel: String,
    initialContent: String,
    private val onApplyEdit: (() -> Unit)? = null,
    private val onViewDiff: (() -> Unit)? = null,
) : JPanel(BorderLayout()) {
    private val textArea = JBTextArea(initialContent).apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        border = JBUI.Borders.empty(8)
        background = JBColor(Color(0xF7F7F7), Color(0x313335))
    }
    private val actions = JPanel(java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }

    init {
        border = JBUI.Borders.compound(JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0), JBUI.Borders.empty(8))
        val header = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(6)
        }
        header.add(javax.swing.JLabel(roleLabel), BorderLayout.WEST)

        actions.add(JButton("Copy").apply {
            addActionListener {
                CopyPasteManager.getInstance().setContents(StringSelection(textArea.text))
            }
        })
        if (onViewDiff != null) {
            actions.add(JButton("View Diff").apply { addActionListener { onViewDiff.invoke() } })
        }
        if (onApplyEdit != null) {
            actions.add(JButton("Apply Edit").apply { addActionListener { onApplyEdit.invoke() } })
        }
        header.add(actions, BorderLayout.EAST)

        add(header, BorderLayout.NORTH)
        add(JBScrollPane(textArea).apply { border = JBUI.Borders.empty() }, BorderLayout.CENTER)
    }

    fun appendToken(token: String) {
        textArea.append(token)
        textArea.caretPosition = textArea.document.length
    }

    fun setContent(value: String) {
        textArea.text = value
    }

    /** Called after streaming completes when an edit proposal is available. */
    fun attachEditProposal(onApply: () -> Unit, onViewDiff: () -> Unit) {
        actions.add(JButton("View Diff").apply { addActionListener { onViewDiff() } })
        actions.add(JButton("Apply Edit").apply { addActionListener { onApply() } })
        actions.revalidate()
        actions.repaint()
    }
}
