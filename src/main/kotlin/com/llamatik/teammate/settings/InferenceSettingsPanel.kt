package com.llamatik.teammate.settings

import com.intellij.openapi.components.service
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.ui.Strings
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

    val panel: JPanel by lazy {
        val resetButton = JButton(Strings.Inference.RESET_DEFAULTS).apply {
            addActionListener { resetToDefaults() }
        }

        FormBuilder.createFormBuilder()
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
            .also { it.border = JBUI.Borders.empty(8) }
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
