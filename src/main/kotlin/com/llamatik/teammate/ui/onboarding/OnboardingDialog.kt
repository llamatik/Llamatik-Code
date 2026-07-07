package com.llamatik.teammate.ui.onboarding

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.infrastructure.ModelDownloadManager
import com.llamatik.teammate.model.ModelStatus
import com.llamatik.teammate.models.BuiltInModelCatalog
import com.llamatik.teammate.settings.LlamatikAppSettings
import com.llamatik.teammate.ui.Strings
import com.llamatik.teammate.ui.models.LoadModelFromFileAction
import com.llamatik.teammate.ui.models.ModelLibraryPanel
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JProgressBar
import javax.swing.SwingUtilities

/**
 * First-run onboarding dialog with 3 steps:
 *   Step 1: Welcome and local-first/privacy overview
 *   Step 2: Model selection (download recommended, choose from library, load from file, skip)
 *   Step 3: Live download progress for the recommended model (or completion message)
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

    private val downloadManager = service<ModelDownloadManager>()
    private val settings = service<LlamatikAppSettings>()

    // Step 3 mutable UI references (set when the card is built)
    private var step3Title: JBLabel? = null
    private var step3ProgressBar: JProgressBar? = null
    private var step3ProgressLabel: JBLabel? = null
    private var step3FinishBtn: JButton? = null
    private var step3CancelBtn: JButton? = null
    private var step3ProgressArea: JPanel? = null
    private var downloadListener: ((com.llamatik.teammate.model.DownloadProgressState) -> Unit)? = null

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

        val buttonRow = JPanel(FlowLayout(FlowLayout.RIGHT)).apply { add(nextButton) }

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
                startRecommendedDownload()
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
                    refreshToolWindowState()
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
        step3Title = title

        val bar = JProgressBar(0, 100).apply {
            isIndeterminate = true
            preferredSize = Dimension(420, 16)
            maximumSize = Dimension(Int.MAX_VALUE, 16)
        }
        step3ProgressBar = bar

        val progressLabel = JBLabel("Connecting…").apply {
            border = JBUI.Borders.emptyTop(6)
        }
        step3ProgressLabel = progressLabel

        val progressArea = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(bar)
            add(progressLabel)
        }
        step3ProgressArea = progressArea

        val cancelBtn = JButton(Strings.Onboarding.STEP3_FINISH).apply {
            addActionListener { cancelDownloadAndClose() }
        }
        step3CancelBtn = cancelBtn

        val finishBtn = JButton(Strings.Onboarding.STEP3_OPEN_AGENT).apply {
            isVisible = false
            addActionListener {
                OnboardingLauncher.markCompleted()
                close(OK_EXIT_CODE)
            }
        }
        step3FinishBtn = finishBtn

        val buttonRow = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
            add(cancelBtn)
            add(finishBtn)
        }

        add(title, BorderLayout.NORTH)
        add(progressArea, BorderLayout.CENTER)
        add(buttonRow, BorderLayout.SOUTH)
    }

    // ── Download logic ────────────────────────────────────────────────────────

    private fun startRecommendedDownload() {
        val descriptor = BuiltInModelCatalog.recommended()
        val destDir = settings.storageDir()
        val fileName = descriptor.recommendedFileName
            ?: descriptor.downloadSourceUrl.substringAfterLast('/').substringBefore('?')
                .takeIf { it.endsWith(".gguf") }
            ?: "${descriptor.id}.gguf"

        val totalEstimatedBytes = (descriptor.diskSizeGb * 1_073_741_824).toLong()

        step3ProgressBar?.isIndeterminate = true

        val listener: (com.llamatik.teammate.model.DownloadProgressState) -> Unit = { state ->
            SwingUtilities.invokeLater {
                when (state.status) {
                    ModelStatus.DOWNLOADING -> {
                        val bar = step3ProgressBar ?: return@invokeLater
                        if (state.totalBytes > 0) {
                            bar.isIndeterminate = false
                            bar.value = ((state.bytesDownloaded * 100) / state.totalBytes).toInt()
                        } else {
                            bar.isIndeterminate = true
                        }
                        val dlMb = state.bytesDownloaded / 1_048_576.0
                        val totalMb = (if (state.totalBytes > 0) state.totalBytes else totalEstimatedBytes) / 1_048_576.0
                        step3ProgressLabel?.text = "%.0f / %.0f MB".format(dlMb, totalMb)
                    }
                    ModelStatus.DOWNLOADED -> onDownloadComplete(descriptor.id)
                    ModelStatus.FAILED -> onDownloadFailed()
                    else -> {}
                }
            }
        }
        downloadListener = listener
        downloadManager.addProgressListener(descriptor.id, listener)

        downloadManager.startDownload(
            modelId = descriptor.id,
            downloadUrl = descriptor.downloadSourceUrl,
            destDir = destDir,
            fileName = fileName,
            onComplete = {},
            onError = { SwingUtilities.invokeLater { onDownloadFailed() } },
        )
    }

    private fun onDownloadComplete(modelId: String) {
        downloadListener?.let { downloadManager.removeProgressListener(modelId, it) }
        downloadListener = null

        step3Title?.text = Strings.Onboarding.STEP3_HEADING_DONE
        step3ProgressArea?.isVisible = false
        step3CancelBtn?.isVisible = false
        step3FinishBtn?.isVisible = true

        val descriptor = BuiltInModelCatalog.recommended()
        val fileName = descriptor.recommendedFileName
            ?: descriptor.downloadSourceUrl.substringAfterLast('/').substringBefore('?')
                .takeIf { it.endsWith(".gguf") }
            ?: "${descriptor.id}.gguf"
        val localPath = settings.storageDir().resolve(fileName).toAbsolutePath().toString()
        settings.data().modelPath = localPath

        refreshToolWindowState()
    }

    private fun onDownloadFailed() {
        val descriptor = BuiltInModelCatalog.recommended()
        downloadListener?.let { downloadManager.removeProgressListener(descriptor.id, it) }
        downloadListener = null
        step3Title?.text = "Download failed"
        step3ProgressBar?.isVisible = false
        step3ProgressLabel?.text = "An error occurred. You can try again from the model library."
        step3CancelBtn?.text = Strings.Onboarding.STEP3_FINISH
        step3FinishBtn?.isVisible = false
    }

    override fun doCancelAction() {
        if (currentStep == STEP_CONFIRM) {
            cancelDownloadAndClose()
        } else {
            super.doCancelAction()
        }
    }

    private fun cancelDownloadAndClose() {
        val descriptor = BuiltInModelCatalog.recommended()
        downloadListener?.let { downloadManager.removeProgressListener(descriptor.id, it) }
        downloadListener = null
        downloadManager.cancelDownload(descriptor.id)
        OnboardingLauncher.markCompleted()
        close(CANCEL_EXIT_CODE)
    }

    private fun refreshToolWindowState() {
        val projects = com.intellij.openapi.project.ProjectManager.getInstance().openProjects
        projects.forEach { p ->
            val tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(p)
                .getToolWindow("Llamatik Code")
            ApplicationManager.getApplication().invokeLater {
                val panel = tw?.contentManager?.getContent(0)?.component
                    as? com.llamatik.teammate.ui.toolwindow.LlamatikToolWindowPanel
                panel?.refreshState()
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun goToStep(step: String) {
        currentStep = step
        cardLayout.show(cardPanel, step)
    }

    private fun showModelLibraryInline(parent: JPanel) {
        val dialog = object : DialogWrapper(project, true) {
            init {
                title = Strings.Onboarding.MODEL_LIBRARY_TITLE
                init()
            }

            override fun createCenterPanel(): JComponent =
                ModelLibraryPanel(project).apply {
                    preferredSize = java.awt.Dimension(860, 640)
                }
        }
        dialog.show()
    }
}
