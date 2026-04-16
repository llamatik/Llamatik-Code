package com.llamatik.teammate.ui.toolwindow

import com.intellij.openapi.components.service
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.settings.DownloadedModelService
import com.llamatik.teammate.settings.LlamatikAppSettings
import com.llamatik.teammate.ui.LlamatikTheme
import java.nio.file.Path
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPopupMenu
import javax.swing.JRadioButtonMenuItem

/**
 * A compact pill button that shows the active model filename and opens a dropdown
 * listing all downloaded/registered models. Selecting one sets it as the active model.
 */
class ModelPickerButton(
    private val onModelChanged: () -> Unit = {},
) : JButton() {

    private val settings = service<LlamatikAppSettings>()
    private val downloadedModelService = service<DownloadedModelService>()

    init {
        isFocusPainted = false
        isContentAreaFilled = false
        border = JBUI.Borders.compound(
            BorderFactory.createLineBorder(LlamatikTheme.outline, 1, true),
            JBUI.Borders.empty(3, 8),
        )
        font = font.deriveFont(font.size2D - 1f)
        foreground = LlamatikTheme.onSurfaceVariant
        refresh()
        addActionListener { showPicker() }
    }

    /** Call after any model change to update the displayed label and visibility. */
    fun refresh() {
        val models = downloadedModelService.listDownloaded()
        isVisible = models.size > 1
        if (!isVisible) return
        val label = activeModelLabel()
        text = "<html><span style='color:${LlamatikTheme.htmlOnSurfaceVariant};'>$label ▾</span></html>"
        toolTipText = settings.data().modelPath.ifBlank { "No model loaded" }
    }

    private fun showPicker() {
        val models = downloadedModelService.listDownloaded()
        if (models.isEmpty()) {
            // No models — just bounce to settings; nothing to pick from
            return
        }

        val popup = JPopupMenu()
        val currentPath = settings.data().modelPath.trim()

        models.forEach { path ->
            val name = fileNameOf(path)
            val item = JRadioButtonMenuItem(name, path == currentPath).apply {
                addActionListener {
                    settings.data().modelPath = path
                    refresh()
                    onModelChanged()
                }
            }
            popup.add(item)
        }

        popup.show(this, 0, height)
    }

    private fun activeModelLabel(): String {
        val path = settings.data().modelPath.trim()
        if (path.isBlank()) return "No model"
        return fileNameOf(path)
    }

    private fun fileNameOf(path: String): String =
        runCatching { Path.of(path).fileName?.toString() ?: path }.getOrDefault(path)
}
