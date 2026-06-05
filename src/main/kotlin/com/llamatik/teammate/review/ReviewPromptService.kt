package com.llamatik.teammate.review

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.settings.LlamatikAppSettings
import com.llamatik.teammate.ui.Strings
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

@Service(Service.Level.APP)
class ReviewPromptService {

    private val settings get() = service<LlamatikAppSettings>()

    /** Call after a task completes that produced at least one file change. */
    fun onSuccessfulTaskWithChanges(project: Project) {
        val s = settings.data()
        if (s.reviewPromptDismissedPermanently) return
        s.completedTasksWithChanges++

        val qualifies = s.completedTasksWithChanges >= TASKS_THRESHOLD
        val cooldownExpired = (System.currentTimeMillis() - s.reviewPromptLastShownMs) >= COOLDOWN_MS
        if (!qualifies || !cooldownExpired) return

        ApplicationManager.getApplication().invokeLater {
            showReviewDialog(project)
        }
    }

    private fun showReviewDialog(project: Project) {
        val s = settings.data()
        s.reviewPromptLastShownMs = System.currentTimeMillis()

        val dialog = object : DialogWrapper(project, false) {
            init {
                title = Strings.ReviewFeedback.REVIEW_DIALOG_TITLE
                isModal = false
                init()
            }

            override fun createCenterPanel(): JComponent {
                val panel = JPanel(GridBagLayout())
                panel.border = JBUI.Borders.empty(16, 24, 8, 24)
                val gbc = GridBagConstraints().apply {
                    gridx = 0
                    gridy = GridBagConstraints.RELATIVE
                    anchor = GridBagConstraints.CENTER
                    fill = GridBagConstraints.NONE
                    insets = JBUI.insets(6, 0)
                }
                panel.add(JLabel(Strings.ReviewFeedback.REVIEW_MESSAGE).apply {
                    font = font.deriveFont(Font.PLAIN, font.size2D + 1f)
                }, gbc)
                return panel
            }

            override fun createActions() = emptyArray<javax.swing.Action>()

            override fun createButtonsPanel(buttons: MutableList<out JButton>): JPanel {
                val panel = JPanel()
                panel.border = JBUI.Borders.empty(0, 8, 8, 8)

                val reviewBtn = JButton(Strings.ReviewFeedback.REVIEW_BUTTON).apply {
                    addActionListener {
                        BrowserUtil.browse(Strings.ReviewFeedback.REVIEW_URL)
                        settings.data().reviewPromptDismissedPermanently = true
                        close(OK_EXIT_CODE)
                    }
                }
                val laterBtn = JButton(Strings.ReviewFeedback.REVIEW_LATER).apply {
                    addActionListener { close(CANCEL_EXIT_CODE) }
                }
                val noThanksBtn = JButton(Strings.ReviewFeedback.REVIEW_NO_THANKS).apply {
                    addActionListener {
                        settings.data().reviewPromptDismissedPermanently = true
                        close(CANCEL_EXIT_CODE)
                    }
                }

                panel.add(reviewBtn)
                panel.add(laterBtn)
                panel.add(noThanksBtn)
                return panel
            }
        }
        dialog.show()
    }

    companion object {
        private const val TASKS_THRESHOLD = 3
        private val COOLDOWN_MS = 7 * 24 * 60 * 60 * 1000L // 7 days
    }
}
