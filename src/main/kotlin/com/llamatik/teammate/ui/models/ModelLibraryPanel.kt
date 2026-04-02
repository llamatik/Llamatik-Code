package com.llamatik.teammate.ui.models

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.model.ExtendedModelDescriptor
import com.llamatik.teammate.model.ModelTier
import com.llamatik.teammate.models.BuiltInModelCatalog
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.JPanel

/**
 * Reusable panel showing all models from the built-in catalog grouped by tier.
 * Can be embedded in settings, onboarding, or a standalone dialog.
 */
class ModelLibraryPanel(private val project: Project?) : JPanel(BorderLayout()) {

    private val contentPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(8)
    }

    init {
        add(JBScrollPane(contentPanel), BorderLayout.CENTER)
        rebuild()
    }

    private fun rebuild() {
        contentPanel.removeAll()

        ModelTier.values().forEach { tier ->
            val models = BuiltInModelCatalog.byTier(tier)
            if (models.isEmpty()) return@forEach

            val tierLabel = JBLabel(tierTitle(tier)).apply {
                font = font.deriveFont(Font.BOLD, font.size2D + 2f)
                border = JBUI.Borders.compound(JBUI.Borders.emptyTop(12), JBUI.Borders.emptyBottom(4))
                alignmentX = LEFT_ALIGNMENT
            }
            contentPanel.add(tierLabel)

            models.forEach { descriptor ->
                val card = ModelCardPanel(project, descriptor) { action, model ->
                    handleAction(action, model)
                }.apply { alignmentX = LEFT_ALIGNMENT }
                contentPanel.add(card)
                contentPanel.add(javax.swing.Box.createVerticalStrut(6))
            }
        }

        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun handleAction(action: ModelCardPanel.ModelCardAction, model: ExtendedModelDescriptor) {
        // Actions are handled inside ModelCardPanel with placeholder messages.
        // This callback is available for external coordination if needed.
    }

    private fun tierTitle(tier: ModelTier): String = when (tier) {
        ModelTier.TIER_1 -> "Tier 1 — Lightweight (up to 5 GB)"
        ModelTier.TIER_2 -> "Tier 2 — Balanced (5–15 GB)"
        ModelTier.TIER_3 -> "Tier 3 — High-performance (15 GB+)"
    }
}
