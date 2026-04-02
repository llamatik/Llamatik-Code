package com.llamatik.teammate.ui.onboarding

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.ui.Strings
import com.llamatik.teammate.ui.models.LoadModelFromFileAction
import com.llamatik.teammate.ui.models.ModelLibraryPanel
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * First-run onboarding dialog with 3 steps:
 *   Step 1: Welcome and local-first/privacy overview
 *   Step 2: Model selection (download recommended, choose from library, load from file, skip)
 *   Step 3: Confirmation / progress placeholder
 */
class OnboardingDialog(private val project: Project) : DialogWrapper(project, true) {

    companion object {
        private const val STEP_WELCOME = "WELCOME"
        private const val STEP_MODEL = "MODEL"
        private const val STEP_CONFIRM = "CONFIRM"
    }

    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)

    private var currentStep = STEP_WELCOME

    init {
        title = Strings.Onboarding.DIALOG_TITLE
        isModal = true
        init()
    }

    override fun createCenterPanel(): JComponent {
        cardPanel.add(buildWelcomeStep(), STEP_WELCOME)
        cardPanel.add(buildModelStep(), STEP_MODEL)
        cardPanel.add(buildConfirmStep(), STEP_CONFIRM)
        cardLayout.show(cardPanel, STEP_WELCOME)
        return cardPanel
    }

    override fun createActions() = emptyArray<javax.swing.Action>()

    // ── Steps ─────────────────────────────────────────────────────────────────

    private fun buildWelcomeStep(): JPanel = JPanel(BorderLayout(8, 16)).apply {
        border = JBUI.Borders.empty(24, 32)

        val title = JBLabel(Strings.Onboarding.STEP1_HEADING).apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 6f)
            border = JBUI.Borders.emptyBottom(12)
        }

        val body = JBLabel(
            "<html>" +
                "<p style='width:420px;'>${Strings.Onboarding.STEP1_BODY1}</p>" +
                "<p style='width:420px; margin-top:10px;'>${Strings.Onboarding.STEP1_BODY2}</p>" +
                "<p style='width:420px; margin-top:10px;'>${Strings.Onboarding.STEP1_BODY3}</p>" +
                "</html>",
        )

        val nextButton = JButton(Strings.Onboarding.STEP1_NEXT).apply {
            addActionListener { goToStep(STEP_MODEL) }
        }

        val buttonRow = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            add(nextButton)
        }

        add(title, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
        add(buttonRow, BorderLayout.SOUTH)
    }

    private fun buildModelStep(): JPanel = JPanel(BorderLayout(8, 16)).apply {
        border = JBUI.Borders.empty(24, 32)

        val title = JBLabel(Strings.Onboarding.STEP2_HEADING).apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 4f)
            border = JBUI.Borders.emptyBottom(8)
        }

        val recommendation = JBLabel(
            "<html>" +
                "<p>${Strings.Onboarding.STEP2_RECOMMENDED_LABEL}</p>" +
                "<p>${Strings.Onboarding.STEP2_RECOMMENDED_DETAILS}</p>" +
                "<p>${Strings.Onboarding.STEP2_RECOMMENDED_DESC}</p>" +
                "</html>",
        ).apply { border = JBUI.Borders.emptyBottom(12) }

        val downloadRecommendedBtn = JButton(Strings.Onboarding.STEP2_DOWNLOAD_RECOMMENDED).apply {
            addActionListener {
                goToStep(STEP_CONFIRM)
            }
        }

        val chooseAnotherBtn = JButton(Strings.Onboarding.STEP2_CHOOSE_ANOTHER)
        chooseAnotherBtn.addActionListener {
            showModelLibraryInline(chooseAnotherBtn.parent as JPanel)
        }

        val loadFromFileBtn = JButton(Strings.Onboarding.STEP2_LOAD_FROM_FILE).apply {
            addActionListener {
                LoadModelFromFileAction.show(project) {
                    OnboardingLauncher.markCompleted()
                    close(OK_EXIT_CODE)
                }
            }
        }

        val skipBtn = JButton(Strings.Onboarding.STEP2_SKIP).apply {
            addActionListener {
                OnboardingLauncher.markCompleted()
                close(CANCEL_EXIT_CODE)
            }
        }

        val buttonsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(downloadRecommendedBtn)
            add(javax.swing.Box.createVerticalStrut(6))
            add(chooseAnotherBtn)
            add(javax.swing.Box.createVerticalStrut(6))
            add(loadFromFileBtn)
            add(javax.swing.Box.createVerticalStrut(6))
            add(skipBtn)
        }

        val backBtn = JButton(Strings.Onboarding.STEP2_BACK).apply {
            addActionListener { goToStep(STEP_WELCOME) }
        }
        val navRow = JPanel(FlowLayout(FlowLayout.LEFT)).apply { add(backBtn) }

        add(title, BorderLayout.NORTH)
        add(JPanel(BorderLayout()).apply {
            add(recommendation, BorderLayout.NORTH)
            add(buttonsPanel, BorderLayout.CENTER)
        }, BorderLayout.CENTER)
        add(navRow, BorderLayout.SOUTH)
    }

    private fun buildConfirmStep(): JPanel = JPanel(BorderLayout(8, 16)).apply {
        border = JBUI.Borders.empty(24, 32)

        val title = JBLabel(Strings.Onboarding.STEP3_HEADING).apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 4f)
            border = JBUI.Borders.emptyBottom(12)
        }

        val body = JBLabel(
            "<html><p style='width:420px;'>${Strings.Onboarding.STEP3_BODY}</p></html>",
        )

        val finishBtn = JButton(Strings.Onboarding.STEP3_FINISH).apply {
            addActionListener {
                OnboardingLauncher.markCompleted()
                close(OK_EXIT_CODE)
            }
        }
        val buttonRow = JPanel(FlowLayout(FlowLayout.RIGHT)).apply { add(finishBtn) }

        add(title, BorderLayout.NORTH)
        add(body, BorderLayout.CENTER)
        add(buttonRow, BorderLayout.SOUTH)
    }

    private fun goToStep(step: String) {
        currentStep = step
        cardLayout.show(cardPanel, step)
    }

    private fun showModelLibraryInline(parent: JPanel) {
        // Show model library in a simple popup or replace content. Here we show a dialog.
        val dialog = object : DialogWrapper(project, true) {
            init {
                title = Strings.Onboarding.MODEL_LIBRARY_TITLE
                init()
            }

            override fun createCenterPanel(): JComponent =
                ModelLibraryPanel(project).apply {
                    preferredSize = java.awt.Dimension(700, 500)
                }
        }
        dialog.show()
    }
}
