package com.llamatik.teammate.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.llamatik.teammate.licensing.LicenseChecker
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.JCheckBox
import javax.swing.JComboBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField

class CodeHealthSettingsConfigurable : Configurable {

    private val enabledCb = JCheckBox("Enable Code Health Monitor")
    private val modeCombo = JComboBox(arrayOf("OFF", "LIGHT", "BALANCED", "AGGRESSIVE"))
    private val runOnCombo = JComboBox(arrayOf("MANUAL", "ON_SAVE", "IDLE", "BEFORE_COMMIT"))
    private val maxFilesField = JTextField(8)
    private val maxRuntimeField = JTextField(8)
    private val maxCallsField = JTextField(8)
    private val liveSuggestCb = JCheckBox("Enable live suggestions")
    private val testAwarenessCb = JCheckBox("Enable test awareness")
    private val regressionCb = JCheckBox("Enable regression detection (Pro)")
    private val dashboardCb = JCheckBox("Enable code health dashboard")
    private val beforeCommitCb = JCheckBox("Enable before-commit assistant")
    private var panel: JPanel? = null

    private fun isPro() = runCatching { LicenseChecker.isLicensed() == true }.getOrElse { false }

    override fun getDisplayName() = "Code Health Monitor"

    override fun createComponent(): JComponent {
        val p = JPanel(GridBagLayout())
        val gbc = GridBagConstraints().apply {
            insets = Insets(4, 6, 4, 6)
            fill = GridBagConstraints.HORIZONTAL
        }

        fun addFull(row: Int, comp: JComponent) {
            gbc.gridy = row; gbc.gridx = 0; gbc.gridwidth = 3
            p.add(comp, gbc)
        }

        fun addRow(row: Int, labelText: String, comp: JComponent) {
            gbc.gridy = row; gbc.gridx = 0; gbc.gridwidth = 1
            p.add(JLabel(labelText), gbc)
            gbc.gridx = 1; gbc.gridwidth = 2
            p.add(comp, gbc)
        }

        addFull(0, enabledCb)

        if (!isPro()) {
            addFull(1, JLabel("<html><i>BALANCED and AGGRESSIVE modes require Pro. Free tier: LIGHT + manual refresh only.</i></html>"))
        }

        addRow(2, "Analysis mode:", modeCombo)
        addRow(3, "Run analysis:", runOnCombo)
        addRow(4, "Max files per scan:", maxFilesField)
        addRow(5, "Max analysis time (ms):", maxRuntimeField)
        addRow(6, "Max model calls/hour:", maxCallsField)
        addFull(7, liveSuggestCb)
        addFull(8, testAwarenessCb)
        addFull(9, regressionCb)
        addFull(10, dashboardCb)
        addFull(11, beforeCommitCb)

        if (!isPro()) {
            modeCombo.addActionListener {
                val selected = modeCombo.selectedItem as? String ?: return@addActionListener
                if (selected in listOf("BALANCED", "AGGRESSIVE")) {
                    modeCombo.selectedItem = "LIGHT"
                    JOptionPane.showMessageDialog(
                        p,
                        "BALANCED and AGGRESSIVE modes require a Pro subscription.",
                        "Pro Required",
                        JOptionPane.INFORMATION_MESSAGE,
                    )
                }
            }
            regressionCb.isEnabled = false
        }

        panel = p
        return p
    }

    override fun isModified(): Boolean {
        val s = service<LlamatikAppSettings>().data()
        return enabledCb.isSelected != s.codeHealthEnabled ||
            (modeCombo.selectedItem as? String) != s.codeHealthMode ||
            (runOnCombo.selectedItem as? String) != s.codeHealthRunOn ||
            maxFilesField.text != s.codeHealthMaxFilesPerScan.toString() ||
            maxRuntimeField.text != s.codeHealthMaxRuntimeMs.toString() ||
            maxCallsField.text != s.codeHealthMaxModelCallsPerHour.toString() ||
            liveSuggestCb.isSelected != s.codeHealthLiveSuggestions ||
            testAwarenessCb.isSelected != s.codeHealthTestAwareness ||
            regressionCb.isSelected != s.codeHealthRegressionDetection ||
            dashboardCb.isSelected != s.codeHealthDashboard ||
            beforeCommitCb.isSelected != s.codeHealthBeforeCommit
    }

    override fun apply() {
        val s = service<LlamatikAppSettings>().data()
        s.codeHealthEnabled = enabledCb.isSelected
        s.codeHealthMode = (modeCombo.selectedItem as? String) ?: "OFF"
        s.codeHealthRunOn = (runOnCombo.selectedItem as? String) ?: "MANUAL"
        s.codeHealthMaxFilesPerScan = maxFilesField.text.toIntOrNull() ?: 10
        s.codeHealthMaxRuntimeMs = maxRuntimeField.text.toLongOrNull() ?: 10_000L
        s.codeHealthMaxModelCallsPerHour = maxCallsField.text.toIntOrNull() ?: 5
        s.codeHealthLiveSuggestions = liveSuggestCb.isSelected
        s.codeHealthTestAwareness = testAwarenessCb.isSelected
        s.codeHealthRegressionDetection = regressionCb.isSelected
        s.codeHealthDashboard = dashboardCb.isSelected
        s.codeHealthBeforeCommit = beforeCommitCb.isSelected
    }

    override fun reset() {
        val s = service<LlamatikAppSettings>().data()
        enabledCb.isSelected = s.codeHealthEnabled
        modeCombo.selectedItem = s.codeHealthMode
        runOnCombo.selectedItem = s.codeHealthRunOn
        maxFilesField.text = s.codeHealthMaxFilesPerScan.toString()
        maxRuntimeField.text = s.codeHealthMaxRuntimeMs.toString()
        maxCallsField.text = s.codeHealthMaxModelCallsPerHour.toString()
        liveSuggestCb.isSelected = s.codeHealthLiveSuggestions
        testAwarenessCb.isSelected = s.codeHealthTestAwareness
        regressionCb.isSelected = s.codeHealthRegressionDetection
        dashboardCb.isSelected = s.codeHealthDashboard
        beforeCommitCb.isSelected = s.codeHealthBeforeCommit
    }
}
