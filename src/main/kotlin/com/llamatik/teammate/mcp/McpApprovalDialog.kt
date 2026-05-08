package com.llamatik.teammate.mcp

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.ui.Strings
import java.awt.Dimension
import java.awt.Font
import javax.swing.Action
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Non-blocking per-tool-call approval dialog.
 *
 * Shows server name, tool name, arguments (redacted), risk level, and reason.
 * Returns a [McpApprovalDecision] to the caller via the [onDecision] callback.
 *
 * On Free: only ALLOW_ONCE and DENY are available.
 * On Pro:  ALLOW_ALWAYS_TOOL and ALLOW_ALWAYS_SERVER are also offered.
 */
class McpApprovalDialog(
    private val serverName: String,
    private val toolName: String,
    private val argsDisplay: String,
    private val riskLevel: McpRiskLevel,
    private val isPro: Boolean,
    private val onDecision: (McpApprovalDecision) -> Unit,
) : DialogWrapper(null, true) {

    private var decision: McpApprovalDecision = McpApprovalDecision.DENY

    init {
        title = Strings.Mcp.APPROVAL_TITLE
        isOKActionEnabled = true
        init()
    }

    override fun createCenterPanel(): JComponent {
        val riskColor = when (riskLevel) {
            McpRiskLevel.HIGH -> java.awt.Color(0xCC3333)
            McpRiskLevel.MEDIUM -> java.awt.Color(0xCC8800)
            McpRiskLevel.LOW -> java.awt.Color(0x336600)
        }
        val riskLabel = JBLabel("Risk: $riskLevel").apply {
            foreground = riskColor
            font = font.deriveFont(Font.BOLD)
        }
        val argsArea = JBTextArea(argsDisplay.take(1000)).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            rows = 6
        }
        val proNote = JBLabel(Strings.Mcp.APPROVAL_PRO_HINT).apply {
            font = font.deriveFont(Font.ITALIC, font.size2D - 1f)
            foreground = com.llamatik.teammate.ui.LlamatikTheme.onSurfaceVariant
            isVisible = !isPro
        }

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Server:"), JBLabel(serverName))
            .addLabeledComponent(JBLabel("Tool:"), JBLabel(toolName))
            .addComponent(riskLabel)
            .addLabeledComponent(JBLabel("Arguments:"), JBScrollPane(argsArea).apply {
                preferredSize = Dimension(420, 120)
            })
            .addComponent(proNote)
            .addComponentFillVertically(JPanel(), 0)
            .panel
            .also { it.border = JBUI.Borders.empty(8) }
    }

    override fun createActions(): Array<Action> {
        val allowOnce = object : DialogWrapperAction(Strings.Mcp.APPROVAL_ALLOW_ONCE) {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                decision = McpApprovalDecision.ALLOW_ONCE
                close(OK_EXIT_CODE)
            }
        }
        val deny = object : DialogWrapperAction(Strings.Mcp.APPROVAL_DENY) {
            override fun doAction(e: java.awt.event.ActionEvent?) {
                decision = McpApprovalDecision.DENY
                close(CANCEL_EXIT_CODE)
            }
        }

        return if (isPro) {
            val allowTool = object : DialogWrapperAction(Strings.Mcp.APPROVAL_ALLOW_ALWAYS_TOOL) {
                override fun doAction(e: java.awt.event.ActionEvent?) {
                    decision = McpApprovalDecision.ALLOW_ALWAYS_TOOL
                    close(OK_EXIT_CODE)
                }
            }
            val allowServer = object : DialogWrapperAction(Strings.Mcp.APPROVAL_ALLOW_ALWAYS_SERVER) {
                override fun doAction(e: java.awt.event.ActionEvent?) {
                    decision = McpApprovalDecision.ALLOW_ALWAYS_SERVER
                    close(OK_EXIT_CODE)
                }
            }
            arrayOf(allowOnce, allowTool, allowServer, deny)
        } else {
            arrayOf(allowOnce, deny)
        }
    }

    override fun doOKAction() {
        onDecision(decision)
        super.doOKAction()
    }

    override fun doCancelAction() {
        onDecision(McpApprovalDecision.DENY)
        super.doCancelAction()
    }
}
