package com.llamatik.teammate.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.server.ConnectResult
import com.llamatik.agent.backends.remote.ServerCapabilities
import com.llamatik.teammate.server.ServerConnectionService
import com.llamatik.teammate.server.ServerConnectionState
import com.llamatik.teammate.server.ServerStatus
import com.llamatik.teammate.ui.LlamatikTheme
import com.llamatik.teammate.ui.Strings
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Settings → Tools → Llamatik Code → Llamatik Server.
 *
 * Connects the plugin to a Llamatik server so the agent runs its inference there instead of on the
 * local model. Shows live connection state and what the server offers.
 */
class ServerSettingsConfigurable : Configurable {
    private val settings by lazy { service<LlamatikAppSettings>() }
    private val connection by lazy { service<ServerConnectionService>() }

    private lateinit var nameField: JBTextField
    private lateinit var hostField: JBTextField
    private lateinit var portField: JBTextField
    private lateinit var fallbackBox: JBCheckBox
    private lateinit var connectBtn: JButton
    private lateinit var disconnectBtn: JButton
    private lateinit var cancelBtn: JButton
    private lateinit var statusLabel: JBLabel
    private lateinit var capabilitiesLabel: JBLabel
    private lateinit var modelLabel: JBLabel

    private var removeListener: (() -> Unit)? = null
    private var connecting = false
    private var lastError: String? = null

    override fun getDisplayName(): String = Strings.Server.PAGE_NAME

    override fun createComponent(): JComponent {
        val data = settings.data()
        nameField = JBTextField(data.serverName, 24)
        hostField = JBTextField(data.serverHost, 24)
        portField = JBTextField(data.serverPort.toString(), 6)
        fallbackBox = JBCheckBox(Strings.Server.FALLBACK_TO_LOCAL, data.serverFallbackToLocal)
        connectBtn = JButton(Strings.Server.CONNECT).apply { addActionListener { onConnect() } }
        disconnectBtn = JButton(Strings.Server.DISCONNECT).apply { addActionListener { onDisconnect() } }
        cancelBtn = JButton(Strings.Server.CANCEL_APPROVAL).apply {
            isVisible = false
            addActionListener { connection.cancelPairing() }
        }
        statusLabel = JBLabel()
        capabilitiesLabel = JBLabel()
        modelLabel = JBLabel()

        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            isOpaque = false
            add(connectBtn)
            add(disconnectBtn)
            add(cancelBtn)
        }
        val note = JBLabel("<html><body style='width:520px'>${Strings.Server.SECURITY_NOTE}</body></html>").apply {
            foreground = LlamatikTheme.onSurfaceVariant
        }
        val intro = JBLabel("<html><body style='width:520px'>${Strings.Server.INTRO.removePrefix("<html>").removeSuffix("</html>")}</body></html>")

        val form = FormBuilder.createFormBuilder()
            .addComponent(intro)
            .addVerticalGap(8)
            .addLabeledComponent(Strings.Server.FIELD_NAME, nameField)
            .addLabeledComponent(Strings.Server.FIELD_HOST, hostField)
            .addLabeledComponent(Strings.Server.FIELD_PORT, portField)
            .addComponent(fallbackBox)
            .addVerticalGap(4)
            .addComponent(buttons)
            .addVerticalGap(8)
            .addLabeledComponent(Strings.Server.STATUS_LABEL, statusLabel)
            .addLabeledComponent(Strings.Server.MODEL_LABEL, modelLabel)
            .addLabeledComponent(Strings.Server.CAPABILITIES_LABEL, capabilitiesLabel)
            .addVerticalGap(8)
            .addComponent(note)
            .addComponentFillVertically(JPanel(), 0)
            .panel
        form.border = JBUI.Borders.empty(8)

        removeListener = connection.addListener {
            ApplicationManager.getApplication().invokeLater({ refreshStatus() }, ModalityState.any())
        }
        connection.start()
        refreshStatus()
        return form
    }

    override fun isModified(): Boolean {
        if (!::fallbackBox.isInitialized) return false
        val data = settings.data()
        if (fallbackBox.isSelected != data.serverFallbackToLocal) return true
        if (data.serverEnabled) return false // connected: the address fields are read-only
        return hostField.text.trim() != data.serverHost ||
            nameField.text.trim() != data.serverName ||
            (portField.text.trim().toIntOrNull() ?: data.serverPort) != data.serverPort
    }

    override fun apply() {
        val data = settings.data()
        data.serverFallbackToLocal = fallbackBox.isSelected
        if (!data.serverEnabled) {
            // Remember the draft; connecting is an explicit action.
            data.serverHost = hostField.text.trim()
            data.serverName = nameField.text.trim()
            data.serverPort = portField.text.trim().toIntOrNull() ?: data.serverPort
        }
    }

    override fun reset() {
        val data = settings.data()
        nameField.text = data.serverName
        hostField.text = data.serverHost
        portField.text = data.serverPort.toString()
        fallbackBox.isSelected = data.serverFallbackToLocal
        refreshStatus()
    }

    override fun disposeUIResources() {
        removeListener?.invoke()
        removeListener = null
    }

    // ── Actions ─────────────────────────────────────────────────────────────

    private fun onConnect() {
        val port = portField.text.trim().toIntOrNull()
        if (port == null || port !in 1..65535) {
            lastError = Strings.Server.INVALID_PORT
            refreshStatus()
            return
        }
        val host = hostField.text.trim()
        val name = nameField.text.trim().ifBlank { host }
        // The fallback checkbox takes effect immediately; it is part of the connection's behaviour.
        settings.data().serverFallbackToLocal = fallbackBox.isSelected
        connecting = true
        lastError = null
        refreshStatus()
        connection.connectAsync(host, port, name) { result ->
            ApplicationManager.getApplication().invokeLater({
                connecting = false
                lastError = (result as? ConnectResult.Failed)?.reason
                if (result is ConnectResult.Success) nameField.text = result.status.displayName
                refreshStatus()
            }, ModalityState.any())
        }
    }

    private fun onDisconnect() {
        lastError = null
        connection.disconnect()
        refreshStatus()
    }

    // ── Rendering ───────────────────────────────────────────────────────────

    private fun refreshStatus() {
        if (!::statusLabel.isInitialized) return
        val st = connection.status()
        val enabled = settings.data().serverEnabled
        val busy = connecting || st.state == ServerConnectionState.CONNECTING || st.state == ServerConnectionState.PAIRING

        val (text, color) = statusText(st)
        statusLabel.text = lastError?.let { "<html>${escape(it)}</html>" } ?: text
        statusLabel.foreground = if (lastError != null) LlamatikTheme.error else color

        modelLabel.text = if (enabled && st.capabilities.hasText) st.capabilities.textModel ?: "—" else "—"
        capabilitiesLabel.text = if (enabled && st.state != ServerConnectionState.DISABLED) describe(st.capabilities) else "—"

        val editable = !enabled && !busy
        nameField.isEnabled = editable
        hostField.isEnabled = editable
        portField.isEnabled = editable
        connectBtn.isVisible = !enabled
        connectBtn.isEnabled = !busy
        disconnectBtn.isVisible = enabled
        disconnectBtn.isEnabled = !busy
        cancelBtn.isVisible = st.state == ServerConnectionState.PAIRING
    }

    private fun statusText(st: ServerStatus): Pair<String, java.awt.Color> = when (st.state) {
        ServerConnectionState.DISABLED -> Strings.Server.STATUS_DISCONNECTED to LlamatikTheme.onSurfaceVariant
        ServerConnectionState.CONNECTING -> Strings.Server.statusConnecting(st.displayName) to LlamatikTheme.onSurfaceVariant
        ServerConnectionState.PAIRING -> Strings.Server.STATUS_PAIRING to LlamatikTheme.statusOrange
        ServerConnectionState.CONNECTED -> Strings.Server.statusConnected(st.displayName) to LlamatikTheme.statusGreen
        ServerConnectionState.RECONNECTING -> Strings.Server.statusReconnecting(st.displayName) to LlamatikTheme.statusOrange
        ServerConnectionState.LOST -> Strings.Server.statusLost(st.displayName) to LlamatikTheme.error
        ServerConnectionState.APPROVAL_REQUIRED -> Strings.Server.statusApprovalRequired(st.displayName) to LlamatikTheme.error
    }

    private fun describe(caps: ServerCapabilities): String {
        val parts = buildList {
            if (caps.hasText) add("Text")
            if (caps.hasVision) add("Vision")
            if (caps.hasImage) add(if (caps.hasVideo) "Image & video" else "Image")
            if (caps.hasAudio) add("Audio")
            if (caps.hasEmbedding) add("Embeddings")
        }
        return if (parts.isEmpty()) Strings.Server.NO_CAPABILITIES else parts.joinToString(", ")
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
