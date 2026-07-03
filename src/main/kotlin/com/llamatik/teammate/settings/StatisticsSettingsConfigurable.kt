package com.llamatik.teammate.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.llamatik.teammate.analytics.GenerationStatsService
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JSeparator

class StatisticsSettingsConfigurable : Configurable {

    override fun getDisplayName() = "Statistics"

    // Statistics page is read-only — no modified state.
    override fun isModified() = false
    override fun apply() = Unit

    override fun createComponent(): JComponent {
        val stats = service<GenerationStatsService>()

        val p = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            insets = Insets(4, 6, 4, 6)
            fill = GridBagConstraints.HORIZONTAL
        }

        var row = 0

        fun addSection(title: String) {
            gbc.gridy = row++; gbc.gridx = 0; gbc.gridwidth = 2
            p.add(JLabel("<html><b>$title</b></html>"), gbc)
            gbc.gridy = row++
            p.add(JSeparator(), gbc)
        }

        fun addRow(label: String, value: String) {
            gbc.gridy = row; gbc.gridx = 0; gbc.gridwidth = 1; gbc.weightx = 0.4
            p.add(JLabel(label), gbc)
            gbc.gridx = 1; gbc.weightx = 0.6
            p.add(JLabel(value), gbc)
            row++
        }

        // ── Time ────────────────────────────────────────────────────────────
        addSection("Time")
        addRow("Total generations:", stats.data().totalGenerations.toString())
        addRow("Average generation time:", formatDuration(stats.averageDurationMs()))
        addRow("Longest task:", formatDuration(stats.data().longestTaskDurationMs))
        addRow("Total generation time:", formatDuration(stats.data().totalDurationMs))

        // ── Tokens ──────────────────────────────────────────────────────────
        addSection("Tokens")
        addRow("Total tokens consumed:", formatTokens(stats.data().totalTokens))

        // ── Energy ──────────────────────────────────────────────────────────
        addSection("Energy & Environment")
        addRow("Energy consumed (estimated):", formatEnergy(stats.totalEnergyWh()))
        addRow("Avg energy per task:", formatEnergy(stats.averageEnergyPerTaskWh()))
        addRow("CO₂ avoided vs cloud API:", formatCo2(stats.co2AvoidedGrams()))

        // ── Economy ─────────────────────────────────────────────────────────
        addSection("Savings")
        addRow("Money saved vs cloud API:", formatMoney(stats.moneySavedUsd()))
        addRow("Model most used:", stats.mostUsedModel())

        // ── Delete all data ─────────────────────────────────────────────────
        gbc.gridy = row++; gbc.gridx = 0; gbc.gridwidth = 2; gbc.weightx = 1.0
        p.add(JSeparator(), gbc)

        val deleteBtn = JButton("Delete all statistics data")
        deleteBtn.addActionListener {
            val confirm = JOptionPane.showConfirmDialog(
                p,
                "This will permanently delete all stored statistics. Continue?",
                "Delete Statistics",
                JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE,
            )
            if (confirm == JOptionPane.YES_OPTION) {
                service<GenerationStatsService>().reset()
                service<com.llamatik.teammate.analytics.TrackingService>()
                    .track(com.llamatik.teammate.analytics.AnalyticsEvent.StatisticsCleared)
                JOptionPane.showMessageDialog(p, "Statistics cleared.", "Done", JOptionPane.INFORMATION_MESSAGE)
            }
        }
        gbc.gridy = row; gbc.gridx = 0; gbc.gridwidth = 2
        p.add(deleteBtn, gbc)

        // Push everything to the top
        gbc.gridy = row + 1; gbc.weighty = 1.0; gbc.fill = GridBagConstraints.BOTH
        p.add(JPanel(), gbc)

        return p
    }

    // ── Formatting helpers ───────────────────────────────────────────────────

    private fun formatDuration(ms: Long): String = when {
        ms <= 0L -> "—"
        ms < 1_000L -> "${ms} ms"
        ms < 60_000L -> "%.1f s".format(ms / 1_000.0)
        ms < 3_600_000L -> "%d m %02d s".format(ms / 60_000, (ms % 60_000) / 1_000)
        else -> "%d h %02d m".format(ms / 3_600_000, (ms % 3_600_000) / 60_000)
    }

    private fun formatTokens(n: Long): String = when {
        n <= 0L -> "—"
        n < 1_000L -> n.toString()
        n < 1_000_000L -> "%.1f K".format(n / 1_000.0)
        else -> "%.2f M".format(n / 1_000_000.0)
    }

    private fun formatEnergy(wh: Double): String = when {
        wh <= 0.0 -> "—"
        wh < 1.0 -> "%.2f mWh".format(wh * 1_000)
        else -> "%.4f Wh".format(wh)
    }

    private fun formatCo2(grams: Double): String = when {
        grams <= 0.0 -> "—"
        grams < 1.0 -> "%.3f g CO₂".format(grams)
        else -> "%.2f g CO₂".format(grams)
    }

    private fun formatMoney(usd: Double): String = when {
        usd <= 0.0 -> "—"
        usd < 0.01 -> "$%.4f".format(usd)
        else -> "$%.2f".format(usd)
    }
}
