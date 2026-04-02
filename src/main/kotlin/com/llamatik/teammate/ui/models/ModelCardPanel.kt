package com.llamatik.teammate.ui.models

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.infrastructure.HardwareDetector
import com.llamatik.teammate.model.ExtendedModelDescriptor
import com.llamatik.teammate.model.ModelStatus
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JPanel

/**
 * A single model card showing info, status badge, hardware compatibility,
 * and context-appropriate action buttons.
 */
class ModelCardPanel(
    private val project: Project?,
    private val descriptor: ExtendedModelDescriptor,
    private val onActionRequested: (ModelCardAction, ExtendedModelDescriptor) -> Unit,
) : JPanel(BorderLayout(8, 4)) {

    enum class ModelCardAction { DOWNLOAD, LOAD, UNLOAD, DELETE, SET_DEFAULT }

    init {
        border = JBUI.Borders.compound(
            BorderFactory.createLineBorder(JBColor.border(), 1, true),
            JBUI.Borders.empty(8, 12),
        )

        val leftPanel = JPanel(BorderLayout(4, 2)).apply { isOpaque = false }

        val nameLabel = JBLabel("<html><b>${descriptor.displayName}</b> <i>(${descriptor.quantization})</i></html>").apply {
            font = font.deriveFont(Font.BOLD)
        }

        val detailsLabel = JBLabel(
            "<html>" +
                "Disk: ${descriptor.diskSizeGb} GB &nbsp;|&nbsp; " +
                "RAM min: ${descriptor.minRamGb} GB &nbsp;|&nbsp; " +
                "Recommended: ${descriptor.recommendedRamGb} GB<br>" +
                "Uses: ${descriptor.useCases.joinToString(", ")}<br>" +
                "<i>${descriptor.tradeoffs}</i>" +
                "</html>",
        )

        val hwRecommendation = HardwareDetector.evaluateForModel(descriptor.minRamGb, descriptor.diskSizeGb)
        val hwLabel = JBLabel(hwRecommendationText(hwRecommendation)).apply {
            foreground = hwRecommendationColor(hwRecommendation)
            font = font.deriveFont(font.size2D - 1f)
        }

        leftPanel.add(nameLabel, BorderLayout.NORTH)
        leftPanel.add(detailsLabel, BorderLayout.CENTER)
        leftPanel.add(hwLabel, BorderLayout.SOUTH)

        val rightPanel = JPanel(BorderLayout(4, 4)).apply { isOpaque = false }
        val statusBadge = buildStatusBadge(descriptor.status)
        val actionsPanel = buildActionsPanel()

        rightPanel.add(statusBadge, BorderLayout.NORTH)
        rightPanel.add(actionsPanel, BorderLayout.SOUTH)

        add(leftPanel, BorderLayout.CENTER)
        add(rightPanel, BorderLayout.EAST)
    }

    private fun buildStatusBadge(status: ModelStatus): JBLabel {
        val (text, color) = when (status) {
            ModelStatus.NOT_DOWNLOADED -> "Not downloaded" to JBColor.GRAY
            ModelStatus.DOWNLOADING -> "Downloading…" to JBColor(Color(0x007AFF), Color(0x4DA3FF))
            ModelStatus.PAUSED -> "Paused" to JBColor(Color(0xFF9500), Color(0xFFAA00))
            ModelStatus.FAILED -> "Failed" to JBColor.RED
            ModelStatus.DOWNLOADED -> "Downloaded" to JBColor(Color(0x34C759), Color(0x30D158))
            ModelStatus.LOADING -> "Loading…" to JBColor(Color(0x007AFF), Color(0x4DA3FF))
            ModelStatus.LOADED -> "Loaded" to JBColor(Color(0x34C759), Color(0x30D158))
            ModelStatus.UNLOADED -> "Unloaded" to JBColor.GRAY
            ModelStatus.CORRUPTED -> "Corrupted" to JBColor.RED
        }
        return JBLabel(text).apply {
            foreground = color
            font = font.deriveFont(Font.BOLD, font.size2D - 1f)
        }
    }

    private fun buildActionsPanel(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }
        val placeholder = "Coming in next release"

        when (descriptor.status) {
            ModelStatus.NOT_DOWNLOADED, ModelStatus.FAILED, ModelStatus.CORRUPTED -> {
                panel.add(JButton("Download").apply {
                    addActionListener {
                        onActionRequested(ModelCardAction.DOWNLOAD, descriptor)
                        Messages.showInfoMessage(project, placeholder, "Llamatik Code")
                    }
                })
            }
            ModelStatus.DOWNLOADED, ModelStatus.UNLOADED -> {
                panel.add(JButton("Load").apply {
                    addActionListener {
                        onActionRequested(ModelCardAction.LOAD, descriptor)
                        Messages.showInfoMessage(project, placeholder, "Llamatik Code")
                    }
                })
                panel.add(JButton("Delete").apply {
                    addActionListener {
                        onActionRequested(ModelCardAction.DELETE, descriptor)
                        Messages.showInfoMessage(project, placeholder, "Llamatik Code")
                    }
                })
            }
            ModelStatus.LOADED -> {
                panel.add(JButton("Unload").apply {
                    addActionListener {
                        onActionRequested(ModelCardAction.UNLOAD, descriptor)
                        Messages.showInfoMessage(project, placeholder, "Llamatik Code")
                    }
                })
            }
            ModelStatus.DOWNLOADING, ModelStatus.PAUSED, ModelStatus.LOADING -> {
                panel.add(JButton("Set as default").apply {
                    addActionListener {
                        onActionRequested(ModelCardAction.SET_DEFAULT, descriptor)
                        Messages.showInfoMessage(project, placeholder, "Llamatik Code")
                    }
                })
            }
        }

        return panel
    }

    private fun hwRecommendationText(rec: String): String = when (rec) {
        "recommended" -> "Your system meets the recommended requirements."
        "may_run_slowly" -> "Your system meets minimum requirements but may run slowly."
        else -> "Insufficient system resources for this model."
    }

    private fun hwRecommendationColor(rec: String): java.awt.Color = when (rec) {
        "recommended" -> JBColor(Color(0x34C759), Color(0x30D158))
        "may_run_slowly" -> JBColor(Color(0xFF9500), Color(0xFFAA00))
        else -> JBColor.RED
    }
}
