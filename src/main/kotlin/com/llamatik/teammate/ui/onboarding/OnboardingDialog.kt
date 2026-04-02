package com.llamatik.teammate.ui.onboarding

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
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
        title = "Welcome to Llamatik Code"
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

        val title = JBLabel("Local-first AI coding assistant").apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 6f)
            border = JBUI.Borders.emptyBottom(12)
        }

        val body = JBLabel(
            "<html>" +
                "<p style='width:420px;'>" +
                "Llamatik Code runs <b>entirely on your device</b>. No code, prompts, or results ever " +
                "leave your machine — no internet connection required after initial setup." +
                "</p>" +
                "<p style='width:420px; margin-top:10px;'>" +
                "Powered by local GGUF models via the Llamatik runtime library. " +
                "You choose which model to use — from lightweight quick helpers to large reasoning models." +
                "</p>" +
                "<p style='width:420px; margin-top:10px;'>" +
                "<b>Privacy guarantee:</b> Your code stays on your hardware, always." +
                "</p>" +
                "</html>",
        )

        val nextButton = JButton("Next: Choose a model").apply {
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

        val title = JBLabel("Set up your model").apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 4f)
            border = JBUI.Borders.emptyBottom(8)
        }

        val recommendation = JBLabel(
            "<html>" +
                "<p><b>Recommended:</b> Qwen3-14B-Q4_K_M</p>" +
                "<p>~9 GB disk &nbsp;|&nbsp; 16 GB RAM recommended</p>" +
                "<p>Excellent reasoning, great for complex refactoring and architecture tasks.</p>" +
                "</html>",
        ).apply { border = JBUI.Borders.emptyBottom(12) }

        val downloadRecommendedBtn = JButton("Download recommended model (Qwen3-14B)").apply {
            addActionListener {
                goToStep(STEP_CONFIRM)
            }
        }

        val chooseAnotherBtn = JButton("Choose another model…")
        chooseAnotherBtn.addActionListener {
            showModelLibraryInline(chooseAnotherBtn.parent as JPanel)
        }

        val loadFromFileBtn = JButton("Load model from file (.gguf)").apply {
            addActionListener {
                LoadModelFromFileAction.show(project) {
                    OnboardingLauncher.markCompleted()
                    close(OK_EXIT_CODE)
                }
            }
        }

        val skipBtn = JButton("Skip for now").apply {
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

        val backBtn = JButton("Back").apply {
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

        val title = JBLabel("You're all set!").apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 4f)
            border = JBUI.Borders.emptyBottom(12)
        }

        val body = JBLabel(
            "<html>" +
                "<p style='width:420px;'>" +
                "Your model selection has been noted. Automatic downloads will be available in a future release.<br><br>" +
                "For now, download your chosen GGUF model manually and register it via " +
                "<b>Settings &gt; Llamatik Code &gt; Model path</b>, or use the <b>Load model from file</b> " +
                "option in the tool window." +
                "</p>" +
                "</html>",
        )

        val finishBtn = JButton("Finish").apply {
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
                title = "Model Library"
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
