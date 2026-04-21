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
    // Free tier: max tokens capped at 4096; Pro: up to 65536
    private val maxTokensMax = if (licensed) 65536 else 4096
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

    private val freeControls: List<java.awt.Component> get() = listOf(
        temperatureSpinner, maxTokensSpinner, numThreadsSpinner, useMmapCheck,
    )
    private val proControls: List<java.awt.Component> get() = listOf(
        contextSizeSpinner, batchSizeSpinner, flashAttentionCheck,
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
            .addTooltip(if (licensed) Strings.Inference.MAX_TOKENS_TOOLTIP else Strings.Inference.MAX_TOKENS_FREE_TOOLTIP)
            .addSeparator()
            .addLabeledComponent(JBLabel(Strings.Inference.SECTION_RUNTIME), JBLabel(""))
            .addLabeledComponent(JBLabel(Strings.Inference.NUM_THREADS), numThreadsSpinner)
            .addTooltip(Strings.Inference.NUM_THREADS_TOOLTIP)
            .addComponent(useMmapCheck)
            .addTooltip(Strings.Inference.USE_MMAP_TOOLTIP)
            .addSeparator()

        // Pro section header — shows "Requires Pro" label when not licensed
        val proSectionRight = if (!licensed) {
            JBLabel(Strings.Inference.PRO_REQUIRED).apply {
                foreground = LlamatikTheme.onSurfaceVariant
                font = font.deriveFont(Font.ITALIC, font.size2D - 1f)
            }
        } else JBLabel("")

        formBuilder
            .addLabeledComponent(JBLabel(Strings.Inference.SECTION_CONTEXT), proSectionRight)
            .addLabeledComponent(JBLabel(Strings.Inference.CONTEXT_SIZE), contextSizeSpinner)
            .addTooltip(Strings.Inference.CONTEXT_SIZE_TOOLTIP)
            .addLabeledComponent(JBLabel(Strings.Inference.BATCH_SIZE), batchSizeSpinner)
            .addTooltip(Strings.Inference.BATCH_SIZE_TOOLTIP)
            .addComponent(flashAttentionCheck)
            .addTooltip(Strings.Inference.FLASH_ATTENTION_TOOLTIP)
            .addSeparator()
            .addComponent(resetButton)
            .addComponentFillVertically(JPanel(), 0)

        val formPanel = formBuilder.panel

        if (!licensed) {
            proControls.forEach { it.isEnabled = false }
            resetButton.isEnabled = false

            val messageLabel = JBLabel(Strings.Inference.UPGRADE_REQUIRED).apply {
                font = font.deriveFont(Font.PLAIN)
            }
            val upgradeButton = JButton(Strings.Inference.UPGRADE_BUTTON).apply {
                addActionListener {
                    if (onUpgrade != null) {
                        onUpgrade.invoke()
                    } else {
                        service<TrialService>().requestLicense(Strings.Inference.UPGRADE_REQUIRED)
                    }
                }
            }
            val bannerPanel = JPanel(BorderLayout(8, 0)).apply {
                border = JBUI.Borders.compound(
                    JBUI.Borders.customLine(LlamatikTheme.primary, 0, 0, 1, 0),
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
        maxTokensSpinner.value     = minOf(s.maxTokens, maxTokensMax)
        contextSizeSpinner.value   = s.contextSize
        batchSizeSpinner.value     = s.batchSize
        numThreadsSpinner.value    = s.numThreads
        useMmapCheck.isSelected    = s.useMmap
        flashAttentionCheck.isSelected = s.flashAttention
    }

    fun applyTo(s: LlamatikAppSettings.State) {
        s.temperature    = (temperatureSpinner.value as Number).toDouble()
        s.maxTokens      = (maxTokensSpinner.value as Number).toInt()
        if (licensed) {
            s.contextSize    = (contextSizeSpinner.value as Number).toInt()
            s.batchSize      = (batchSizeSpinner.value as Number).toInt()
            s.flashAttention = flashAttentionCheck.isSelected
        }
        s.numThreads     = (numThreadsSpinner.value as Number).toInt()
        s.useMmap        = useMmapCheck.isSelected
    }

    fun isModified(s: LlamatikAppSettings.State): Boolean =
        (temperatureSpinner.value as Number).toDouble() != s.temperature ||
        (maxTokensSpinner.value as Number).toInt()      != s.maxTokens   ||
        (numThreadsSpinner.value as Number).toInt()     != s.numThreads  ||
        useMmapCheck.isSelected                         != s.useMmap     ||
        (licensed && (
            (contextSizeSpinner.value as Number).toInt()  != s.contextSize ||
            (batchSizeSpinner.value as Number).toInt()    != s.batchSize   ||
            flashAttentionCheck.isSelected                != s.flashAttention
        ))

    private fun resetToDefaults() {
        val d = LlamatikAppSettings.State()
        temperatureSpinner.value    = d.temperature
        maxTokensSpinner.value      = minOf(d.maxTokens, maxTokensMax)
        numThreadsSpinner.value     = d.numThreads
        useMmapCheck.isSelected     = d.useMmap
        if (licensed) {
            contextSizeSpinner.value    = d.contextSize
            batchSizeSpinner.value      = d.batchSize
            flashAttentionCheck.isSelected = d.flashAttention
        }
    }
}
