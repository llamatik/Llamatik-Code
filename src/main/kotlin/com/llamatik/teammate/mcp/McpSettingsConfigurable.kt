package com.llamatik.teammate.mcp

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.ui.Strings
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.util.concurrent.Executors
import javax.swing.BoxLayout
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.SwingUtilities

class McpSettingsConfigurable : Configurable {

    private val configService by lazy { service<McpServerConfigService>() }
    private val managerService by lazy { service<McpManagerService>() }
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "llamatik-mcp-settings").also { it.isDaemon = true }
    }

    private lateinit var listModel: DefaultListModel<McpServerListEntry>
    private lateinit var serverList: JList<McpServerListEntry>
    private lateinit var editButton: JButton
    private lateinit var removeButton: JButton
    private lateinit var testButton: JButton
    private lateinit var refreshButton: JButton
    private lateinit var logsButton: JButton
    private lateinit var statusLabel: JBLabel

    override fun getDisplayName() = Strings.Mcp.SETTINGS_PAGE_NAME

    override fun createComponent(): JComponent {
        listModel = DefaultListModel()
        serverList = JList(listModel).apply {
            preferredSize = Dimension(400, 140)
            addListSelectionListener { updateButtonStates() }
        }
        statusLabel = JBLabel("").apply {
            font = font.deriveFont(Font.ITALIC, font.size2D - 1f)
            foreground = com.llamatik.teammate.ui.LlamatikTheme.onSurfaceVariant
        }

        val addButton = JButton(Strings.Mcp.ADD_SERVER).apply {
            addActionListener { showEditDialog(null) }
        }
        editButton = JButton(Strings.Mcp.EDIT_SERVER).apply {
            isEnabled = false
            addActionListener { showEditDialog(selectedConfig()) }
        }
        removeButton = JButton(Strings.Mcp.REMOVE_SERVER).apply {
            isEnabled = false
            addActionListener { removeSelected() }
        }
        testButton = JButton(Strings.Mcp.TEST_CONNECTION).apply {
            isEnabled = false
            addActionListener { testSelectedConnection() }
        }
        refreshButton = JButton(Strings.Mcp.REFRESH_TOOLS).apply {
            isEnabled = false
            addActionListener { refreshSelectedTools() }
        }
        logsButton = JButton(Strings.Mcp.VIEW_LOGS).apply {
            isEnabled = false
            addActionListener { showSelectedLogs() }
        }

        val buttonPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(addButton)
            add(editButton)
            add(removeButton)
            add(testButton)
            add(refreshButton)
            add(logsButton)
        }

        val listPanel = JPanel(BorderLayout(8, 0)).apply {
            add(JBScrollPane(serverList), BorderLayout.CENTER)
            add(buttonPanel, BorderLayout.EAST)
        }

        if (!configService.isPro()) {
            statusLabel.text = Strings.Mcp.FREE_TIER_NOTE
        }

        reloadList()

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel(Strings.Mcp.SECTION_MCP_SERVERS), listPanel)
            .addComponent(statusLabel)
            .addComponentFillVertically(JPanel(), 0)
            .panel
            .also { it.border = JBUI.Borders.empty(8) }
    }

    override fun isModified() = false  // edits are applied immediately in dialogs

    override fun apply() {}

    override fun reset() { reloadList() }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun reloadList() {
        listModel.clear()
        val statuses = managerService.allStatuses()
        configService.listConfigs().forEach { config ->
            val state = statuses[config.id]?.state ?: McpServerState.STOPPED
            listModel.addElement(McpServerListEntry(config, state))
        }
    }

    private fun selectedConfig(): McpServerConfig? =
        serverList.selectedValue?.config

    private fun updateButtonStates() {
        val selected = serverList.selectedValue != null
        editButton.isEnabled = selected
        removeButton.isEnabled = selected
        testButton.isEnabled = selected
        refreshButton.isEnabled = selected && serverList.selectedValue?.state == McpServerState.RUNNING
        logsButton.isEnabled = selected
    }

    private fun showEditDialog(existing: McpServerConfig?) {
        val dialog = McpServerEditDialog(existing, configService.isPro()) { config ->
            val result = configService.saveConfig(config)
            if (result.isSuccess) {
                reloadList()
                if (config.enabled) {
                    executor.execute {
                        runCatching { managerService.startServer(config) }
                        SwingUtilities.invokeLater { reloadList() }
                    }
                } else {
                    managerService.stopServer(config.id)
                }
            } else {
                Messages.showErrorDialog(
                    result.exceptionOrNull()?.message ?: "Could not save server.",
                    Strings.Mcp.ERROR_SAVE_TITLE
                )
            }
        }
        dialog.show()
    }

    private fun removeSelected() {
        val config = selectedConfig() ?: return
        val confirm = Messages.showYesNoDialog(
            Strings.Mcp.confirmRemoveMsg(config.name),
            Strings.Mcp.REMOVE_SERVER,
            null,
        )
        if (confirm == Messages.YES) {
            managerService.stopServer(config.id)
            configService.deleteConfig(config.id)
            reloadList()
        }
    }

    private fun testSelectedConnection() {
        val config = selectedConfig() ?: return
        testButton.isEnabled = false
        testButton.text = Strings.Mcp.TESTING
        executor.execute {
            val result = managerService.testConnection(config)
            SwingUtilities.invokeLater {
                reloadList()
                testButton.text = Strings.Mcp.TEST_CONNECTION
                updateButtonStates()
                if (result.success) {
                    Messages.showInfoMessage(
                        Strings.Mcp.testSuccessMsg(result.toolCount, result.resourceCount, result.promptCount),
                        Strings.Mcp.TEST_SUCCESS_TITLE
                    )
                } else {
                    Messages.showErrorDialog(
                        Strings.Mcp.testFailureMsg(result.error ?: "Unknown error"),
                        Strings.Mcp.TEST_FAILURE_TITLE
                    )
                }
            }
        }
    }

    private fun refreshSelectedTools() {
        val config = selectedConfig() ?: return
        executor.execute {
            runCatching { managerService.refreshToolsForServer(config) }
            SwingUtilities.invokeLater { reloadList() }
        }
    }

    private fun showSelectedLogs() {
        val config = selectedConfig() ?: return
        val logs = managerService.getServerLogs(config.id)
        val text = if (logs.isEmpty()) "(no logs yet)" else logs.joinToString("\n")
        Messages.showInfoMessage(text.take(4000), Strings.Mcp.logsTitle(config.name))
    }

    data class McpServerListEntry(val config: McpServerConfig, val state: McpServerState) {
        override fun toString(): String {
            val enabled = if (config.enabled) "enabled" else "disabled"
            val stateStr = when (state) {
                McpServerState.RUNNING -> "● running"
                McpServerState.STARTING -> "◌ starting"
                McpServerState.FAILED -> "✗ failed"
                McpServerState.STOPPED -> "○ stopped"
            }
            return "${config.name}  [$enabled | $stateStr]"
        }
    }
}
