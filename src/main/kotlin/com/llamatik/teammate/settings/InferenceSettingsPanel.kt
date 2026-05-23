package com.llamatik.teammate.settings

import com.intellij.openapi.components.service
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.licensing.LicenseChecker
import com.llamatik.teammate.licensing.TrialService
import com.llamatik.teammate.ui.LlamatikTheme
import com.llamatik.teammate.ui.Strings
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class InferenceSettingsPanel(private val onUpgrade: (() -> Unit)? = null) {

    private val licensed = runCatching { LicenseChecker.isLicensed() == true }.getOrElse { false }

    // ── Free tier controls ────────────────────────────────────────────────────
    val temperatureSpinner = JSpinner(SpinnerNumberModel(0.7, 0.0, 2.0, 0.05))
    private val maxTokensMax = 65536
    val maxTokensSpinner = JSpinner(SpinnerNumberModel(
        /* value */ minOf(8192, maxTokensMax),
        /* min   */ 64,
        /* max   */ maxTokensMax,
        /* step  */ 256,
    ))
    val numThreadsSpinner  = JSpinner(SpinnerNumberModel(4, 1, 64, 1))
    val useMmapCheck       = JBCheckBox(Strings.Inference.USE_MMAP)

    // ── Pro-only controls ─────────────────────────────────────────────────────
    val contextSizeSpinner = JSpinner(SpinnerNumberModel(16384, 512, 131072, 512))
    val batchSizeSpinner   = JSpinner(SpinnerNumberModel(2048, 64, 65536, 64))
    val flashAttentionCheck = JBCheckBox(Strings.Inference.FLASH_ATTENTION)
    val gpuOffloadCheck     = JBCheckBox(Strings.Inference.GPU_LAYERS)

    private val freeControls: List<java.awt.Component> get() = listOf(
        temperatureSpinner, maxTokensSpinner, numThreadsSpinner, useMmapCheck, batchSizeSpinner, flashAttentionCheck, gpuOffloadCheck,
    )
    private val proControls: List<java.awt.Component> get() = listOf(
        contextSizeSpinner,
    )

    val panel: JPanel by lazy {
        val resetButton = JButton(Strings.Inference.RESET_DEFAULTS).apply {
            addActionListener { resetToDefaults() }
        }

        val formBuilder = FormBuilder.createFormBuilder()
            .addSeparator()
            .addLabeledComponent(JBLabel(Strings.Inference.SECTION_GENERATION), JBLabel(""))
            .addLabeledComponent(JBLabel(Strings.Inference.TEMPERATURE), temperatureSpinner)
            .addTooltip(Strings.Inference.TEMPERATURE_TOOLTIP)
            .addLabeledComponent(JBLabel(Strings.Inference.MAX_TOKENS), maxTokensSpinner)
            .addTooltip(Strings.Inference.MAX_TOKENS_TOOLTIP)
            .addSeparator()
            .addLabeledComponent(JBLabel(Strings.Inference.SECTION_RUNTIME), JBLabel(""))
            .addLabeledComponent(JBLabel(Strings.Inference.NUM_THREADS), numThreadsSpinner)
            .addTooltip(Strings.Inference.NUM_THREADS_TOOLTIP)
            .addComponent(useMmapCheck)
            .addTooltip(Strings.Inference.USE_MMAP_TOOLTIP)
            .addSeparator()

        val proSectionRight = JBLabel("")

        formBuilder
            .addLabeledComponent(JBLabel(Strings.Inference.SECTION_CONTEXT), proSectionRight)
            .addLabeledComponent(JBLabel(Strings.Inference.CONTEXT_SIZE), contextSizeSpinner)
            .addTooltip(Strings.Inference.CONTEXT_SIZE_TOOLTIP)
            .addLabeledComponent(JBLabel(Strings.Inference.BATCH_SIZE), batchSizeSpinner)
            .addTooltip(Strings.Inference.BATCH_SIZE_TOOLTIP)
            .addComponent(flashAttentionCheck)
            .addTooltip(Strings.Inference.FLASH_ATTENTION_TOOLTIP)
            .addComponent(gpuOffloadCheck)
            .addTooltip(Strings.Inference.GPU_LAYERS_TOOLTIP)
            .addSeparator()
            .addComponent(resetButton)
            .addComponentFillVertically(JPanel(), 0)

        val formPanel = formBuilder.panel

        formPanel.also { it.border = JBUI.Borders.empty(8) }
    }

    fun loadFrom(s: LlamatikAppSettings.State) {
        temperatureSpinner.value       = s.temperature
        maxTokensSpinner.value         = minOf(s.maxTokens, maxTokensMax)
        contextSizeSpinner.value       = s.contextSize
        batchSizeSpinner.value         = s.batchSize
        numThreadsSpinner.value        = s.numThreads
        useMmapCheck.isSelected        = s.useMmap
        flashAttentionCheck.isSelected = s.flashAttention
        gpuOffloadCheck.isSelected     = s.gpuLayers != 0
    }

    fun applyTo(s: LlamatikAppSettings.State) {
        s.temperature    = (temperatureSpinner.value as Number).toDouble()
        s.maxTokens      = (maxTokensSpinner.value as Number).toInt()
        s.batchSize      = (batchSizeSpinner.value as Number).toInt()
        s.flashAttention = flashAttentionCheck.isSelected
        s.gpuLayers      = if (gpuOffloadCheck.isSelected) -1 else 0
        s.contextSize = (contextSizeSpinner.value as Number).toInt()
        s.numThreads     = (numThreadsSpinner.value as Number).toInt()
        s.useMmap        = useMmapCheck.isSelected
    }

    fun isModified(s: LlamatikAppSettings.State): Boolean =
        (temperatureSpinner.value as Number).toDouble() != s.temperature ||
        (maxTokensSpinner.value as Number).toInt()      != s.maxTokens   ||
        (numThreadsSpinner.value as Number).toInt()     != s.numThreads  ||
        useMmapCheck.isSelected                         != s.useMmap     ||
        (batchSizeSpinner.value as Number).toInt()      != s.batchSize   ||
        flashAttentionCheck.isSelected                  != s.flashAttention ||
        gpuOffloadCheck.isSelected                      != (s.gpuLayers != 0) ||
        (contextSizeSpinner.value as Number).toInt() != s.contextSize

    private fun resetToDefaults() {
        val d = LlamatikAppSettings.State()
        temperatureSpinner.value       = d.temperature
        maxTokensSpinner.value         = minOf(d.maxTokens, maxTokensMax)
        numThreadsSpinner.value        = d.numThreads
        useMmapCheck.isSelected        = d.useMmap
        batchSizeSpinner.value         = d.batchSize
        flashAttentionCheck.isSelected = d.flashAttention
        gpuOffloadCheck.isSelected     = d.gpuLayers != 0
        contextSizeSpinner.value = d.contextSize
    }
}
