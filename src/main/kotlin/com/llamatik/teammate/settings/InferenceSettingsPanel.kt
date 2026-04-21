package com.llamatik.teammate.settings

import com.intellij.openapi.components.service
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.licensing.LicenseChecker
import com.llamatik.teammate.licensing.TrialService
import com.llamatik.teammate.ui.Strings
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class InferenceSettingsPanel {

    val temperatureSpinner = JSpinner(SpinnerNumberModel(0.7, 0.0, 2.0, 0.05))
    val maxTokensSpinner   = JSpinner(SpinnerNumberModel(8192, 64, 65536, 256))
    val contextSizeSpinner = JSpinner(SpinnerNumberModel(16384, 512, 131072, 512))
    val batchSizeSpinner   = JSpinner(SpinnerNumberModel(2048, 64, 65536, 64))
    val numThreadsSpinner  = JSpinner(SpinnerNumberModel(4, 1, 64, 1))
    val useMmapCheck       = JBCheckBox(Strings.Inference.USE_MMAP)
    val flashAttentionCheck = JBCheckBox(Strings.Inference.FLASH_ATTENTION)

    private val controls: List<java.awt.Component> get() = listOf(
        temperatureSpinner, maxTokensSpinner, contextSizeSpinner,
        batchSizeSpinner, numThreadsSpinner, useMmapCheck, flashAttentionCheck,
    )

    val panel: JPanel by lazy {
        val resetButton = JButton(Strings.Inference.RESET_DEFAULTS).apply {
            addActionListener { resetToDefaults() }
        }

        val formPanel = FormBuilder.createFormBuilder()
            .addSeparator()
            .addLabeledComponent(JBLabel(Strings.Inference.SECTION_GENERATION), JBLabel(""))
            .addLabeledComponent(JBLabel(Strings.Inference.TEMPERATURE), temperatureSpinner)
            .addTooltip(Strings.Inference.TEMPERATURE_TOOLTIP)
            .addLabeledComponent(JBLabel(Strings.Inference.MAX_TOKENS), maxTokensSpinner)
            .addTooltip(Strings.Inference.MAX_TOKENS_TOOLTIP)
            .addSeparator()
            .addLabeledComponent(JBLabel(Strings.Inference.SECTION_CONTEXT), JBLabel(""))
            .addLabeledComponent(JBLabel(Strings.Inference.CONTEXT_SIZE), contextSizeSpinner)
            .addTooltip(Strings.Inference.CONTEXT_SIZE_TOOLTIP)
            .addLabeledComponent(JBLabel(Strings.Inference.BATCH_SIZE), batchSizeSpinner)
            .addTooltip(Strings.Inference.BATCH_SIZE_TOOLTIP)
            .addSeparator()
            .addLabeledComponent(JBLabel(Strings.Inference.SECTION_RUNTIME), JBLabel(""))
            .addLabeledComponent(JBLabel(Strings.Inference.NUM_THREADS), numThreadsSpinner)
            .addTooltip(Strings.Inference.NUM_THREADS_TOOLTIP)
            .addComponent(useMmapCheck)
            .addTooltip(Strings.Inference.USE_MMAP_TOOLTIP)
            .addComponent(flashAttentionCheck)
            .addTooltip(Strings.Inference.FLASH_ATTENTION_TOOLTIP)
            .addSeparator()
            .addComponent(resetButton)
            .addComponentFillVertically(JPanel(), 0)
            .panel

        val licensed = runCatching { LicenseChecker.isLicensed() == true }.getOrElse { false }
        if (!licensed) {
            controls.forEach { it.isEnabled = false }
            resetButton.isEnabled = false

            val messageLabel = JBLabel(Strings.Inference.UPGRADE_REQUIRED).apply {
                font = font.deriveFont(Font.PLAIN)
            }
            val upgradeButton = JButton(Strings.Inference.UPGRADE_BUTTON).apply {
                addActionListener {
                    service<TrialService>().requestLicense(Strings.Inference.UPGRADE_REQUIRED)
                }
            }
            val bannerPanel = JPanel(BorderLayout(8, 0)).apply {
                border = JBUI.Borders.compound(
                    JBUI.Borders.customLine(com.llamatik.teammate.ui.LlamatikTheme.primary, 0, 0, 1, 0),
                    JBUI.Borders.empty(8),
                )
                add(messageLabel, BorderLayout.CENTER)
                add(upgradeButton, BorderLayout.EAST)
            }

            JPanel(BorderLayout()).apply {
                add(bannerPanel, BorderLayout.NORTH)
                add(formPanel, BorderLayout.CENTER)
                border = JBUI.Borders.empty(8)
            }
        } else {
            formPanel.also { it.border = JBUI.Borders.empty(8) }
        }
    }

    fun loadFrom(s: LlamatikAppSettings.State) {
        temperatureSpinner.value   = s.temperature
        maxTokensSpinner.value     = s.maxTokens
        contextSizeSpinner.value   = s.contextSize
        batchSizeSpinner.value     = s.batchSize
        numThreadsSpinner.value    = s.numThreads
        useMmapCheck.isSelected    = s.useMmap
        flashAttentionCheck.isSelected = s.flashAttention
    }

    fun applyTo(s: LlamatikAppSettings.State) {
        s.temperature    = (temperatureSpinner.value as Number).toDouble()
        s.maxTokens      = (maxTokensSpinner.value as Number).toInt()
        s.contextSize    = (contextSizeSpinner.value as Number).toInt()
        s.batchSize      = (batchSizeSpinner.value as Number).toInt()
        s.numThreads     = (numThreadsSpinner.value as Number).toInt()
        s.useMmap        = useMmapCheck.isSelected
        s.flashAttention = flashAttentionCheck.isSelected
    }

    fun isModified(s: LlamatikAppSettings.State): Boolean =
        (temperatureSpinner.value as Number).toDouble() != s.temperature ||
        (maxTokensSpinner.value as Number).toInt()      != s.maxTokens   ||
        (contextSizeSpinner.value as Number).toInt()    != s.contextSize  ||
        (batchSizeSpinner.value as Number).toInt()      != s.batchSize    ||
        (numThreadsSpinner.value as Number).toInt()     != s.numThreads   ||
        useMmapCheck.isSelected    != s.useMmap         ||
        flashAttentionCheck.isSelected != s.flashAttention

    private fun resetToDefaults() {
        val d = LlamatikAppSettings.State()
        temperatureSpinner.value    = d.temperature
        maxTokensSpinner.value      = d.maxTokens
        contextSizeSpinner.value    = d.contextSize
        batchSizeSpinner.value      = d.batchSize
        numThreadsSpinner.value     = d.numThreads
        useMmapCheck.isSelected     = d.useMmap
        flashAttentionCheck.isSelected = d.flashAttention
    }
}
