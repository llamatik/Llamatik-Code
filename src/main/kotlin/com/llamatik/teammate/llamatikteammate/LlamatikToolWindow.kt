package com.llamatik.teammate.llamatikteammate

import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities

class LlamatikToolWindowFactory : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = LlamatikChatPanel(project)
        val contentManager = toolWindow.contentManager
        val content = contentManager.factory.createContent(panel, "", false)
        contentManager.addContent(content)
    }
}

class LlamatikChatPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val chatArea = JBTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
    }

    private val inputField = JBTextField()
    private val sendButton = JButton("Send")

    private val agent = AgentController(project)

    init {
        val scrollPane = JBScrollPane(chatArea)

        val bottomPanel = JPanel(BorderLayout())
        bottomPanel.add(inputField, BorderLayout.CENTER)
        bottomPanel.add(sendButton, BorderLayout.EAST)

        add(scrollPane, BorderLayout.CENTER)
        add(bottomPanel, BorderLayout.SOUTH)

        sendButton.addActionListener {
            sendMessage()
        }

        inputField.addActionListener {
            sendMessage()
        }
    }

    private fun sendMessage() {
        val message = inputField.text.trim()
        if (message.isEmpty()) return

        appendMessage("You", message)
        inputField.text = ""

        agent.send(message) { response ->
            SwingUtilities.invokeLater {
                appendMessage("Llamatik", response)
            }
        }
    }

    private fun appendMessage(sender: String, message: String) {
        chatArea.append("$sender:\n$message\n\n")
    }
}
