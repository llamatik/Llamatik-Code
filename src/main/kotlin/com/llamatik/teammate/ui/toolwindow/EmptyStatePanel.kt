package com.llamatik.teammate.ui.toolwindow

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.GridLayout
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Shown when no model is configured.
 * Clean layout: title + subtitle centred, then action cards below.
 */
class EmptyStatePanel(
    private val onDownloadRecommended: () -> Unit,
    private val onOpenModelLibrary: () -> Unit,
    private val onLoadFromFile: () -> Unit,
) : JPanel(BorderLayout()) {

    init {
        border = JBUI.Borders.empty(32, 24, 16, 24)
        isOpaque = false

        val center = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        val title = JBLabel("No model loaded").apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 6f)
            alignmentX = CENTER_ALIGNMENT
            border = JBUI.Borders.emptyBottom(8)
        }

        val subtitle = JBLabel(
            "<html><div style='text-align:center; width:280px;'>" +
                "Your code never leaves your machine — all AI runs locally on-device." +
                "</div></html>",
        ).apply {
            alignmentX = CENTER_ALIGNMENT
            border = JBUI.Borders.emptyBottom(28)
        }

        center.add(title)
        center.add(subtitle)
        center.add(buildActionCards())

        add(center, BorderLayout.CENTER)
    }

    private fun buildActionCards(): JPanel {
        val grid = JPanel(GridLayout(3, 1, 0, 8)).apply { isOpaque = false }

        grid.add(actionCard(
            label = "Download recommended model",
            description = "Qwen3-14B-Q4_K_M · 9 GB · requires 16 GB RAM",
            action = onDownloadRecommended,
        ))
        grid.add(actionCard(
            label = "Browse model library",
            description = "Pick from lightweight to high-performance models",
            action = onOpenModelLibrary,
        ))
        grid.add(actionCard(
            label = "Load model from file",
            description = "Point to an existing .gguf file on your disk",
            action = onLoadFromFile,
        ))

        return JPanel(BorderLayout()).apply {
            isOpaque = false
            add(grid, BorderLayout.CENTER)
        }
    }

    private fun actionCard(label: String, description: String, action: () -> Unit): JButton {
        return JButton("<html><b>$label</b><br><span style='font-weight:normal;'>$description</span></html>").apply {
            horizontalAlignment = JButton.LEFT
            border = JBUI.Borders.compound(
                BorderFactory.createLineBorder(JBColor.border(), 1, true),
                JBUI.Borders.empty(10, 14),
            )
            isContentAreaFilled = false
            isFocusPainted = false
            maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
            addActionListener { action() }
        }
    }
}
