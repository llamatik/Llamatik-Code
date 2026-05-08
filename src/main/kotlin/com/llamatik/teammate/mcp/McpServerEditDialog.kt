package com.llamatik.teammate.mcp

import com.intellij.openapi.ui.ComboBoxTableRenderer
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.ui.Strings
import java.awt.Dimension
import java.awt.Font
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel

class McpServerEditDialog(
    private val existing: McpServerConfig?,
    private val isPro: Boolean,
    private val onSave: (McpServerConfig) -> Unit,
) : DialogWrapper(null, true) {

    private lateinit var nameField: JBTextField
    private lateinit var commandField: JBTextField
    private lateinit var argsArea: JBTextArea
    private lateinit var envArea: JBTextArea
    private lateinit var workingDirField: JBTextField
    private lateinit var enabledCheck: JBCheckBox
    private lateinit var autoStartCheck: JBCheckBox
    private lateinit var trustCombo: JComboBox<McpTrustLevel>

    init {
        title = if (existing == null) Strings.Mcp.ADD_SERVER else Strings.Mcp.EDIT_SERVER
        init()
    }

    override fun createCenterPanel(): JComponent {
        nameField = JBTextField(existing?.name ?: "")
        commandField = JBTextField(existing?.command ?: "")
        argsArea = JBTextArea(3, 40).apply {
            text = existing?.args?.joinToString("\n") ?: ""
            toolTipText = Strings.Mcp.ARGS_TOOLTIP
        }
        envArea = JBTextArea(3, 40).apply {
            text = existing?.env?.entries?.joinToString("\n") { "${it.key}=${it.value}" } ?: ""
            toolTipText = Strings.Mcp.ENV_TOOLTIP
        }
        workingDirField = JBTextField(existing?.workingDirectory ?: "")
        enabledCheck = JBCheckBox(Strings.Mcp.ENABLED, existing?.enabled ?: false)
        autoStartCheck = JBCheckBox(Strings.Mcp.AUTO_START, existing?.autoStart ?: false)
        trustCombo = JComboBox(McpTrustLevel.values()).apply {
            selectedItem = existing?.trustLevel ?: McpTrustLevel.ASK_EVERY_TIME
            isEnabled = isPro
        }

        val proNoteLabel = JBLabel(Strings.Mcp.AUTO_APPROVAL_PRO_REQUIRED).apply {
            font = font.deriveFont(Font.ITALIC, font.size2D - 1f)
            foreground = com.llamatik.teammate.ui.LlamatikTheme.onSurfaceVariant
            isVisible = !isPro
        }

        val securityNoteLabel = JBLabel(Strings.Mcp.SECURITY_NOTE).apply {
            font = font.deriveFont(Font.ITALIC, font.size2D - 1f)
            foreground = com.llamatik.teammate.ui.LlamatikTheme.onSurfaceVariant
        }

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel(Strings.Mcp.FIELD_NAME), nameField)
            .addLabeledComponent(JBLabel(Strings.Mcp.FIELD_COMMAND), commandField)
            .addLabeledComponent(JBLabel(Strings.Mcp.FIELD_ARGS), JBScrollPane(argsArea).apply {
                preferredSize = Dimension(400, 70)
            })
            .addLabeledComponent(JBLabel(Strings.Mcp.FIELD_ENV), JBScrollPane(envArea).apply {
                preferredSize = Dimension(400, 70)
            })
            .addLabeledComponent(JBLabel(Strings.Mcp.FIELD_WORKING_DIR), workingDirField)
            .addComponent(enabledCheck)
            .addComponent(autoStartCheck)
            .addLabeledComponent(JBLabel(Strings.Mcp.FIELD_TRUST_LEVEL), trustCombo)
            .addComponent(proNoteLabel)
            .addComponent(securityNoteLabel)
            .addComponentFillVertically(JPanel(), 0)
            .panel
            .also { it.border = JBUI.Borders.empty(8) }
    }

    override fun doValidate(): ValidationInfo? {
        if (nameField.text.isBlank()) return ValidationInfo(Strings.Mcp.VALIDATION_NAME_REQUIRED, nameField)
        if (commandField.text.isBlank()) return ValidationInfo(Strings.Mcp.VALIDATION_COMMAND_REQUIRED, commandField)
        return null
    }

    override fun doOKAction() {
        val config = McpServerConfig(
            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
            name = nameField.text.trim(),
            command = commandField.text.trim(),
            args = argsArea.text.lines().filter { it.isNotBlank() },
            env = envArea.text.lines().filter { it.contains('=') }.associate { line ->
                val eq = line.indexOf('=')
                line.substring(0, eq).trim() to line.substring(eq + 1)
            },
            workingDirectory = workingDirField.text.trim(),
            enabled = enabledCheck.isSelected,
            autoStart = autoStartCheck.isSelected,
            trustLevel = trustCombo.selectedItem as McpTrustLevel,
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
        )
        onSave(config)
        super.doOKAction()
    }
}
