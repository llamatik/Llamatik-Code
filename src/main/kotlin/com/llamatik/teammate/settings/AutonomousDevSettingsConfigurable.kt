package com.llamatik.teammate.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.ui.Strings
import com.llamatik.teammate.workflows.enterprise.EnterpriseSafetyLevel
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

class AutonomousDevSettingsConfigurable : Configurable {

    private var panel: JPanel? = null
    private var enterpriseEnabled: JCheckBox? = null
    private var safetyLevel: JComboBox<String>? = null
    private var allowExternal: JCheckBox? = null
    private var requirePlan: JCheckBox? = null
    private var requireDiff: JCheckBox? = null
    private var requireAudit: JCheckBox? = null
    private var inlineEnabled: JCheckBox? = null
    private var debounceSpinner: JSpinner? = null
    private var maxContextSpinner: JSpinner? = null
    private var maxTokensSpinner: JSpinner? = null

    override fun getDisplayName() = "Autonomous Local Development"

    override fun createComponent(): JComponent {
        val settings = service<LlamatikAppSettings>().data()

        val root = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS) }

        fun sectionLabel(text: String) = JBLabel(text).apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 1f)
            border = JBUI.Borders.empty(12, 0, 4, 0)
            alignmentX = 0f
        }

        fun row(label: String, component: JComponent): JPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(4)
            add(JBLabel(label), BorderLayout.WEST)
            add(component, BorderLayout.EAST)
        }

        // Enterprise Pair Programmer section
        root.add(sectionLabel(Strings.Settings.SECTION_AUTONOMOUS))

        val ee = JCheckBox(Strings.Settings.ENTERPRISE_MODE_ENABLED).apply {
            isSelected = settings.enterpriseModeEnabled
            alignmentX = 0f
        }
        enterpriseEnabled = ee
        root.add(ee)

        val sl = JComboBox(EnterpriseSafetyLevel.values().map { it.name }.toTypedArray()).apply {
            selectedItem = settings.enterpriseDefaultSafetyLevel
        }
        safetyLevel = sl
        root.add(row(Strings.Settings.ENTERPRISE_DEFAULT_SAFETY, sl))

        val ae = JCheckBox(Strings.Settings.ENTERPRISE_ALLOW_EXTERNAL).apply {
            isSelected = settings.enterpriseAllowExternalKnowledge
            alignmentX = 0f
        }
        allowExternal = ae
        root.add(ae)

        val rp = JCheckBox(Strings.Settings.ENTERPRISE_REQUIRE_PLAN).apply {
            isSelected = settings.enterpriseRequirePlanBeforeWrite
            alignmentX = 0f
        }
        requirePlan = rp
        root.add(rp)

        val rd = JCheckBox(Strings.Settings.ENTERPRISE_REQUIRE_DIFF).apply {
            isSelected = settings.enterpriseRequireDiffBeforeWrite
            alignmentX = 0f
        }
        requireDiff = rd
        root.add(rd)

        val ra = JCheckBox(Strings.Settings.ENTERPRISE_REQUIRE_AUDIT).apply {
            isSelected = settings.enterpriseRequireAuditReport
            alignmentX = 0f
        }
        requireAudit = ra
        root.add(ra)

        // Inline completions section
        root.add(sectionLabel(Strings.Settings.SECTION_INLINE_COMPLETION))

        root.add(JBLabel("<html><i>${Strings.Settings.INLINE_COMPLETION_NOTE}</i></html>").apply {
            border = JBUI.Borders.emptyBottom(8)
            alignmentX = 0f
        })

        val ic = JCheckBox(Strings.Settings.INLINE_COMPLETION_ENABLED).apply {
            isSelected = settings.enableInlineCompletion
            alignmentX = 0f
        }
        inlineEnabled = ic
        root.add(ic)

        val ds = JSpinner(SpinnerNumberModel(settings.inlineCompletionDebounceMs, 100, 5000, 50))
        debounceSpinner = ds
        root.add(row(Strings.Settings.INLINE_COMPLETION_DEBOUNCE, ds))

        val cs = JSpinner(SpinnerNumberModel(settings.inlineCompletionMaxContextChars, 200, 4000, 100))
        maxContextSpinner = cs
        root.add(row(Strings.Settings.INLINE_COMPLETION_MAX_CONTEXT, cs))

        val ts = JSpinner(SpinnerNumberModel(settings.inlineCompletionMaxTokens, 8, 256, 8))
        maxTokensSpinner = ts
        root.add(row(Strings.Settings.INLINE_COMPLETION_MAX_TOKENS, ts))

        panel = root
        return root
    }

    override fun isModified(): Boolean {
        val s = service<LlamatikAppSettings>().data()
        return enterpriseEnabled?.isSelected != s.enterpriseModeEnabled ||
            safetyLevel?.selectedItem != s.enterpriseDefaultSafetyLevel ||
            allowExternal?.isSelected != s.enterpriseAllowExternalKnowledge ||
            requirePlan?.isSelected != s.enterpriseRequirePlanBeforeWrite ||
            requireDiff?.isSelected != s.enterpriseRequireDiffBeforeWrite ||
            requireAudit?.isSelected != s.enterpriseRequireAuditReport ||
            inlineEnabled?.isSelected != s.enableInlineCompletion ||
            (debounceSpinner?.value as? Int) != s.inlineCompletionDebounceMs ||
            (maxContextSpinner?.value as? Int) != s.inlineCompletionMaxContextChars ||
            (maxTokensSpinner?.value as? Int) != s.inlineCompletionMaxTokens
    }

    override fun apply() {
        val s = service<LlamatikAppSettings>().data()
        enterpriseEnabled?.let { s.enterpriseModeEnabled = it.isSelected }
        safetyLevel?.let { s.enterpriseDefaultSafetyLevel = it.selectedItem as? String ?: "APPROVAL_REQUIRED" }
        allowExternal?.let { s.enterpriseAllowExternalKnowledge = it.isSelected }
        requirePlan?.let { s.enterpriseRequirePlanBeforeWrite = it.isSelected }
        requireDiff?.let { s.enterpriseRequireDiffBeforeWrite = it.isSelected }
        requireAudit?.let { s.enterpriseRequireAuditReport = it.isSelected }
        inlineEnabled?.let { s.enableInlineCompletion = it.isSelected }
        (debounceSpinner?.value as? Int)?.let { s.inlineCompletionDebounceMs = it }
        (maxContextSpinner?.value as? Int)?.let { s.inlineCompletionMaxContextChars = it }
        (maxTokensSpinner?.value as? Int)?.let { s.inlineCompletionMaxTokens = it }
    }

    override fun reset() {
        val s = service<LlamatikAppSettings>().data()
        enterpriseEnabled?.isSelected = s.enterpriseModeEnabled
        safetyLevel?.selectedItem = s.enterpriseDefaultSafetyLevel
        allowExternal?.isSelected = s.enterpriseAllowExternalKnowledge
        requirePlan?.isSelected = s.enterpriseRequirePlanBeforeWrite
        requireDiff?.isSelected = s.enterpriseRequireDiffBeforeWrite
        requireAudit?.isSelected = s.enterpriseRequireAuditReport
        inlineEnabled?.isSelected = s.enableInlineCompletion
        debounceSpinner?.value = s.inlineCompletionDebounceMs
        maxContextSpinner?.value = s.inlineCompletionMaxContextChars
        maxTokensSpinner?.value = s.inlineCompletionMaxTokens
    }
}
