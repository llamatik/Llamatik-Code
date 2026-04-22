package com.llamatik.teammate.ui.models

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.infrastructure.HardwareDetector
import com.llamatik.teammate.infrastructure.ModelDownloadManager
import com.llamatik.teammate.licensing.LicenseChecker
import com.llamatik.teammate.licensing.TrialService
import com.llamatik.teammate.model.DownloadProgressState
import com.llamatik.teammate.model.ExtendedModelDescriptor
import com.llamatik.teammate.model.ModelCatalogStatus
import com.llamatik.teammate.model.ModelSpecialization
import com.llamatik.teammate.model.ModelStatus
import com.llamatik.teammate.model.ModelTier
import com.llamatik.teammate.settings.DownloadedModelService
import com.llamatik.teammate.settings.LlamatikAppSettings
import java.nio.file.Files
import com.llamatik.teammate.ui.Strings
import com.llamatik.teammate.ui.toolwindow.LlamatikToolWindowPanel
import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JProgressBar

/**
 * A single model card showing info, status badge, hardware compatibility,
 * and context-appropriate action buttons with a real download progress bar.
 */
class ModelCardPanel(
    private val project: Project?,
    private val descriptor: ExtendedModelDescriptor,
    private val onActionRequested: (ModelCardAction, ExtendedModelDescriptor) -> Unit,
    private val onRequestUpgrade: (() -> Unit)? = null,
) : JPanel(BorderLayout(8, 4)) {

    enum class ModelCardAction { DOWNLOAD, LOAD, UNLOAD, DELETE, SET_DEFAULT }

    private val downloadManager = service<ModelDownloadManager>()
    private val settings = service<LlamatikAppSettings>()
    private val downloadedModelService = service<DownloadedModelService>()
    private val trialService = service<TrialService>()
    private val licensed = runCatching { LicenseChecker.isLicensed() == true }.getOrElse { false }

    // Right panel stacks badge → actions/progress vertically, no gap
    private val rightPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }
    private val statusBadge = buildStatusBadge(resolveCurrentStatus())
    private var currentActionComponent: java.awt.Component? = null

    private var progressBar: JProgressBar? = null
    private var progressLabel: JBLabel? = null
    private var phaseLabel: JBLabel? = null
    private var activeListener: ((DownloadProgressState) -> Unit)? = null
    private var activeMmprojListener: ((DownloadProgressState) -> Unit)? = null

    private val mmprojId get() = "${descriptor.id}:mmproj"

    init {
        border = JBUI.Borders.compound(
            BorderFactory.createLineBorder(JBColor.border(), 1, true),
            JBUI.Borders.empty(8, 12),
        )

        val leftPanel = JPanel(BorderLayout(4, 2)).apply { isOpaque = false }

        val topPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
        }

        val titleRow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
        titleRow.add(JBLabel("<html><b>${descriptor.displayName}</b> <i>(${descriptor.quantization})</i></html>").apply {
            font = font.deriveFont(Font.BOLD)
        })
        titleRow.add(JBLabel(tierBadgeText(descriptor.tier)).apply {
            font = font.deriveFont(font.size2D - 1f)
            foreground = tierBadgeColor(descriptor.tier)
        })
        topPanel.add(titleRow)

        val metaLabel = JBLabel(
            "<html>${specializationText(descriptor.specialization)} &nbsp;•&nbsp; ${catalogStatusText(descriptor.catalogStatus)}" +
                    (descriptor.recommendedFileName?.let { " &nbsp;•&nbsp; $it" } ?: "") +
                    "</html>",
        ).apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(font.size2D - 1f)
            alignmentX = LEFT_ALIGNMENT
        }
        topPanel.add(metaLabel)

        val detailsLabel = JBLabel(
            "<html>" +
                    "Disk: ${descriptor.diskSizeGb} GB &nbsp;|&nbsp; " +
                    "RAM min: ${descriptor.minRamGb} GB, recommended: ${descriptor.recommendedRamGb} GB<br>" +
                    "Uses: ${descriptor.useCases.joinToString(", ")}<br>" +
                    "<i>${descriptor.tradeoffs}</i>" +
                    "</html>",
        )

        val hwRecommendation = HardwareDetector.evaluateForModel(descriptor.minRamGb, descriptor.diskSizeGb)
        val hwLabel = JBLabel(hwRecommendationText(hwRecommendation)).apply {
            foreground = hwRecommendationColor(hwRecommendation)
            font = font.deriveFont(font.size2D - 1f)
        }

        leftPanel.add(topPanel, BorderLayout.NORTH)
        leftPanel.add(detailsLabel, BorderLayout.CENTER)
        leftPanel.add(hwLabel, BorderLayout.SOUTH)

        // Right panel: badge on top, then actions immediately below
        rightPanel.add(statusBadge)
        rightPanel.add(Box.createVerticalStrut(4))
        refreshActionsPanel()

        add(leftPanel, BorderLayout.CENTER)
        add(rightPanel, BorderLayout.EAST)

        // Re-attach to any already-running download
        val inProgress = downloadManager.getProgress(descriptor.id)
        if (inProgress?.status == ModelStatus.DOWNLOADING) {
            showProgressBar(inProgress.bytesDownloaded, inProgress.totalBytes)
            attachProgressListener()
        }
    }

    // ── Actions panel ─────────────────────────────────────────────────────────

    private fun replaceActionComponent(component: java.awt.Component) {
        currentActionComponent?.let { rightPanel.remove(it) }
        rightPanel.add(component)
        currentActionComponent = component
        rightPanel.revalidate()
        rightPanel.repaint()
    }

    private fun refreshActionsPanel() {
        replaceActionComponent(buildActionsPanel())
    }

    private fun buildActionsPanel(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }
        val liveStatus = downloadManager.getProgress(descriptor.id)?.status ?: resolveCurrentStatus()

        when (liveStatus) {
            ModelStatus.NOT_DOWNLOADED, ModelStatus.FAILED, ModelStatus.CORRUPTED -> {
                if (!licensed && descriptor.tier == ModelTier.TIER_3) {
                    panel.add(JButton(Strings.ModelCard.ACTION_DOWNLOAD).apply {
                        isEnabled = false
                        toolTipText = Strings.ModelCard.TIER_3_PAYWALL_TITLE
                    })
                    panel.add(JButton("Pro").apply {
                        foreground = JBColor(Color(0xFF9500), Color(0xFFAA00))
                        font = font.deriveFont(Font.BOLD, font.size2D - 1f)
                        addActionListener {
                            if (onRequestUpgrade != null) {
                                onRequestUpgrade.invoke()
                            } else {
                                ApplicationManager.getApplication().invokeLater({
                                    trialService.requestLicense(Strings.ModelCard.TIER_3_PAYWALL_TITLE)
                                }, ModalityState.nonModal())
                            }
                        }
                    })
                } else {
                    panel.add(JButton(Strings.ModelCard.ACTION_DOWNLOAD).apply {
                        isEnabled = descriptor.downloadSourceUrl.isNotBlank()
                        if (!isEnabled) toolTipText = "No download URL available"
                        addActionListener {
                            onActionRequested(ModelCardAction.DOWNLOAD, descriptor)
                            startDownload()
                        }
                    })
                }
            }
            ModelStatus.DOWNLOADING -> {
                panel.add(JButton(Strings.ModelCard.ACTION_CANCEL).apply {
                    addActionListener {
                        downloadManager.cancelDownload(descriptor.id)
                        downloadManager.cancelDownload(mmprojId)
                        hideProgressBar()
                        updateStatusBadge(ModelStatus.NOT_DOWNLOADED)
                        refreshActionsPanel()
                    }
                })
            }
            ModelStatus.DOWNLOADED, ModelStatus.UNLOADED -> {
                panel.add(JButton(Strings.ModelCard.ACTION_LOAD).apply {
                    addActionListener {
                        onActionRequested(ModelCardAction.LOAD, descriptor)
                        loadModel()
                    }
                })
                panel.add(JButton(Strings.ModelCard.ACTION_DELETE).apply {
                    addActionListener {
                        val confirm = Messages.showYesNoDialog(
                            project,
                            "Delete the downloaded file for \"${descriptor.displayName}\"?\nThe file will be removed from disk.",
                            "Delete Model",
                            null,
                        )
                        if (confirm == Messages.YES) {
                            deleteLocalFile()
                            updateStatusBadge(ModelStatus.NOT_DOWNLOADED)
                            refreshActionsPanel()
                        }
                    }
                })
            }
            ModelStatus.LOADED -> {
                panel.add(JButton(Strings.ModelCard.ACTION_UNLOAD).apply {
                    addActionListener {
                        onActionRequested(ModelCardAction.UNLOAD, descriptor)
                        unloadModel()
                    }
                })
            }
            ModelStatus.PAUSED, ModelStatus.LOADING -> {}
        }

        return panel
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    private fun loadModel() {
        val fileName = localFileName()
        val localPath = settings.storageDir().resolve(fileName).toAbsolutePath().toString()

        settings.data().modelPath = localPath
        downloadedModelService.registerLocalModel(localPath)

        // For VLMs, resolve the mmproj path and activate it alongside the model.
        val mmprojUrl = descriptor.mmprojDownloadUrl
        if (mmprojUrl != null) {
            val mmprojFileName = mmprojUrl.substringAfterLast('/').substringBefore('?')
                .takeIf { it.endsWith(".gguf") } ?: "${descriptor.id}-mmproj.gguf"
            val mmprojLocalPath = settings.storageDir().resolve(mmprojFileName).toAbsolutePath().toString()
            if (java.nio.file.Files.exists(java.nio.file.Path.of(mmprojLocalPath))) {
                settings.data().mmprojPath = mmprojLocalPath
                downloadedModelService.registerLocalMmproj(mmprojLocalPath)
            }
        }

        // Refresh all open tool windows — same as LlamatikSettingsConfigurable.apply()
        ProjectManager.getInstance().openProjects.forEach { p ->
            val tw = ToolWindowManager.getInstance(p).getToolWindow("Llamatik Code")
            val panel = tw?.contentManager?.getContent(0)?.component as? LlamatikToolWindowPanel
            panel?.refreshState()
        }

        updateStatusBadge(ModelStatus.LOADED)
        refreshActionsPanel()
        Messages.showInfoMessage(
            project,
            "\"${descriptor.displayName}\" is now the active model.",
            "Model Loaded",
        )
    }

    // ── Unload ────────────────────────────────────────────────────────────────

    private fun unloadModel() {
        service<com.llamatik.teammate.llamatik.LlamatikBackendService>().unload()
        if (descriptor.mmprojDownloadUrl != null) {
            service<com.llamatik.teammate.llamatik.MultimodalBackendService>().unload()
            settings.data().mmprojPath = ""
        }
        settings.data().modelPath = ""

        ProjectManager.getInstance().openProjects.forEach { p ->
            val tw = ToolWindowManager.getInstance(p).getToolWindow("Llamatik Code")
            val panel = tw?.contentManager?.getContent(0)?.component as? LlamatikToolWindowPanel
            panel?.refreshState()
        }

        updateStatusBadge(ModelStatus.DOWNLOADED)
        refreshActionsPanel()
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    private fun deleteLocalFile() {
        val localPath = settings.storageDir().resolve(localFileName()).toAbsolutePath().toString()
        downloadedModelService.deleteModel(localPath)
        downloadManager.cancelDownload(descriptor.id)
    }

    // ── Download ──────────────────────────────────────────────────────────────

    private fun localFileName(): String =
        descriptor.recommendedFileName
            ?: descriptor.downloadSourceUrl.substringAfterLast('/').substringBefore('?')
                .takeIf { it.endsWith(".gguf") }
            ?: "${descriptor.id}.gguf"

    private fun startDownload() {
        val destDir = settings.storageDir()
        val fileName = localFileName()
        val mmprojUrl = descriptor.mmprojDownloadUrl
        val hasMmproj = mmprojUrl != null

        showProgressBar(
            bytesDownloaded = 0L,
            totalBytes = (descriptor.diskSizeGb * 1_073_741_824).toLong(),
            phase = if (hasMmproj) "Downloading model (1/2)…" else null,
        )
        attachProgressListener()

        downloadManager.startDownload(
            modelId = descriptor.id,
            downloadUrl = descriptor.downloadSourceUrl,
            destDir = destDir,
            fileName = fileName,
            onComplete = { localPath ->
                if (hasMmproj) {
                    startMmprojDownload(mmprojUrl!!, destDir, localPath)
                } else {
                    ApplicationManager.getApplication().invokeLater({
                        updateStatusBadge(ModelStatus.DOWNLOADED)
                        refreshActionsPanel()
                        Messages.showInfoMessage(
                            project,
                            "\"${descriptor.displayName}\" downloaded.\nPath: $localPath\n\nClick Load to make it active.",
                            "Download Complete",
                        )
                    }, ModalityState.any())
                }
            },
            onError = { msg ->
                ApplicationManager.getApplication().invokeLater({
                    updateStatusBadge(ModelStatus.FAILED)
                    refreshActionsPanel()
                    Messages.showErrorDialog(project, "Download failed: $msg", "Download Error")
                }, ModalityState.any())
            },
        )
    }

    private fun startMmprojDownload(mmprojUrl: String, destDir: java.nio.file.Path, modelLocalPath: String) {
        val mmprojFileName = mmprojUrl.substringAfterLast('/').substringBefore('?')
            .takeIf { it.endsWith(".gguf") } ?: "${descriptor.id}-mmproj.gguf"

        ApplicationManager.getApplication().invokeLater({
            updatePhaseLabel("Downloading mmproj (2/2)…")
            updateProgressBar(0L, -1L)
        }, ModalityState.any())

        attachMmprojProgressListener()

        downloadManager.startDownload(
            modelId = mmprojId,
            downloadUrl = mmprojUrl,
            destDir = destDir,
            fileName = mmprojFileName,
            onComplete = { mmprojLocalPath ->
                ApplicationManager.getApplication().invokeLater({
                    settings.data().mmprojPath = mmprojLocalPath
                    updateStatusBadge(ModelStatus.DOWNLOADED)
                    refreshActionsPanel()
                    Messages.showInfoMessage(
                        project,
                        "\"${descriptor.displayName}\" downloaded.\nModel: $modelLocalPath\nmmproj: $mmprojLocalPath\n\nClick Load to make it active.",
                        "Download Complete",
                    )
                }, ModalityState.any())
            },
            onError = { msg ->
                ApplicationManager.getApplication().invokeLater({
                    updateStatusBadge(ModelStatus.FAILED)
                    refreshActionsPanel()
                    Messages.showErrorDialog(project, "mmproj download failed: $msg", "Download Error")
                }, ModalityState.any())
            },
        )
    }

    private fun attachProgressListener() {
        activeListener?.let { downloadManager.removeProgressListener(descriptor.id, it) }

        val listener: (DownloadProgressState) -> Unit = { state ->
            ApplicationManager.getApplication().invokeLater({
                when (state.status) {
                    ModelStatus.DOWNLOADING -> {
                        updateProgressBar(state.bytesDownloaded, state.totalBytes)
                        updateStatusBadge(ModelStatus.DOWNLOADING)
                    }
                    ModelStatus.DOWNLOADED -> {
                        // If there is an mmproj to download, keep the progress bar visible until
                        // the mmproj phase completes; the mmproj listener will hide it.
                        if (descriptor.mmprojDownloadUrl == null) {
                            hideProgressBar()
                            updateStatusBadge(ModelStatus.DOWNLOADED)
                            refreshActionsPanel()
                        }
                        activeListener?.let { downloadManager.removeProgressListener(descriptor.id, it) }
                        activeListener = null
                    }
                    ModelStatus.FAILED, ModelStatus.NOT_DOWNLOADED -> {
                        hideProgressBar()
                        updateStatusBadge(state.status)
                        refreshActionsPanel()
                        activeListener?.let { downloadManager.removeProgressListener(descriptor.id, it) }
                        activeListener = null
                    }
                    else -> {}
                }
            }, ModalityState.any())
        }
        activeListener = listener
        downloadManager.addProgressListener(descriptor.id, listener)
    }

    private fun attachMmprojProgressListener() {
        activeMmprojListener?.let { downloadManager.removeProgressListener(mmprojId, it) }

        val listener: (DownloadProgressState) -> Unit = { state ->
            ApplicationManager.getApplication().invokeLater({
                when (state.status) {
                    ModelStatus.DOWNLOADING -> {
                        updateProgressBar(state.bytesDownloaded, state.totalBytes)
                        updateStatusBadge(ModelStatus.DOWNLOADING)
                    }
                    ModelStatus.DOWNLOADED, ModelStatus.FAILED, ModelStatus.NOT_DOWNLOADED -> {
                        hideProgressBar()
                        updateStatusBadge(state.status)
                        refreshActionsPanel()
                        activeMmprojListener?.let { downloadManager.removeProgressListener(mmprojId, it) }
                        activeMmprojListener = null
                    }
                    else -> {}
                }
            }, ModalityState.any())
        }
        activeMmprojListener = listener
        downloadManager.addProgressListener(mmprojId, listener)
    }

    // ── Progress bar ──────────────────────────────────────────────────────────

    private fun showProgressBar(bytesDownloaded: Long, totalBytes: Long, phase: String? = null) {
        val bar = JProgressBar(0, 100).apply {
            isIndeterminate = totalBytes <= 0
            value = if (totalBytes > 0) ((bytesDownloaded * 100) / totalBytes).toInt() else 0
            preferredSize = java.awt.Dimension(160, 14)
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, 14)
        }
        val pLabel = JBLabel(formatProgress(bytesDownloaded, totalBytes)).apply {
            font = font.deriveFont(font.size2D - 1.5f)
            foreground = JBColor.GRAY
        }
        val cancelButton = JButton(Strings.ModelCard.ACTION_CANCEL).apply {
            addActionListener {
                downloadManager.cancelDownload(descriptor.id)
                downloadManager.cancelDownload(mmprojId)
                hideProgressBar()
                updateStatusBadge(ModelStatus.NOT_DOWNLOADED)
                refreshActionsPanel()
            }
        }
        progressBar = bar
        progressLabel = pLabel

        val container = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            if (phase != null) {
                val pl = JBLabel(phase).apply {
                    font = font.deriveFont(font.size2D - 1.5f)
                    foreground = JBColor.GRAY
                }
                phaseLabel = pl
                add(pl)
                add(Box.createVerticalStrut(2))
            }
            add(bar)
            add(Box.createVerticalStrut(2))
            add(pLabel)
            add(Box.createVerticalStrut(4))
            add(cancelButton)
        }
        replaceActionComponent(container)
    }

    private fun updateProgressBar(bytesDownloaded: Long, totalBytes: Long) {
        progressBar?.let { bar ->
            if (totalBytes > 0) {
                bar.isIndeterminate = false
                bar.value = ((bytesDownloaded * 100) / totalBytes).toInt()
            } else {
                bar.isIndeterminate = true
            }
            bar.repaint()
        }
        progressLabel?.text = formatProgress(bytesDownloaded, totalBytes)
        revalidate()
        repaint()
    }

    private fun updatePhaseLabel(text: String) {
        phaseLabel?.text = text
        phaseLabel?.repaint()
    }

    private fun hideProgressBar() {
        progressBar = null
        progressLabel = null
        phaseLabel = null
    }

    private fun formatProgress(bytesDownloaded: Long, totalBytes: Long): String {
        val dl = bytesDownloaded / 1_048_576.0
        return if (totalBytes > 0) "%.0f / %.0f MB".format(dl, totalBytes / 1_048_576.0)
        else "%.0f MB".format(dl)
    }

    // ── Status badge ──────────────────────────────────────────────────────────

    private fun updateStatusBadge(status: ModelStatus) {
        val (text, color) = statusTextAndColor(status)
        statusBadge.text = text
        statusBadge.foreground = color
        statusBadge.repaint()
    }

    private fun buildStatusBadge(status: ModelStatus): JBLabel {
        val (text, color) = statusTextAndColor(status)
        return JBLabel(text).apply {
            foreground = color
            font = font.deriveFont(Font.BOLD, font.size2D - 1f)
        }
    }

    private fun statusTextAndColor(status: ModelStatus): Pair<String, java.awt.Color> = when (status) {
        ModelStatus.NOT_DOWNLOADED -> Strings.ModelCard.STATUS_NOT_DOWNLOADED to JBColor.GRAY
        ModelStatus.DOWNLOADING    -> Strings.ModelCard.STATUS_DOWNLOADING    to JBColor(Color(0x007AFF), Color(0x4DA3FF))
        ModelStatus.PAUSED         -> Strings.ModelCard.STATUS_PAUSED         to JBColor(Color(0xFF9500), Color(0xFFAA00))
        ModelStatus.FAILED         -> Strings.ModelCard.STATUS_FAILED         to JBColor.RED
        ModelStatus.DOWNLOADED     -> Strings.ModelCard.STATUS_DOWNLOADED     to JBColor(Color(0x34C759), Color(0x30D158))
        ModelStatus.LOADING        -> Strings.ModelCard.STATUS_LOADING        to JBColor(Color(0x007AFF), Color(0x4DA3FF))
        ModelStatus.LOADED         -> Strings.ModelCard.STATUS_LOADED         to JBColor(Color(0x34C759), Color(0x30D158))
        ModelStatus.UNLOADED       -> Strings.ModelCard.STATUS_UNLOADED       to JBColor.GRAY
        ModelStatus.CORRUPTED      -> Strings.ModelCard.STATUS_CORRUPTED      to JBColor.RED
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Derives the real status by checking disk and settings at call time.
     * - LOADED   : the file is the currently configured model path
     * - DOWNLOADED : the file exists on disk
     * - NOT_DOWNLOADED : file absent
     */
    private fun resolveCurrentStatus(): ModelStatus {
        val localPath = settings.storageDir().resolve(localFileName()).toAbsolutePath()
        val fileExists = Files.exists(localPath) && Files.isRegularFile(localPath)
        if (!fileExists) return ModelStatus.NOT_DOWNLOADED
        val configuredPath = runCatching {
            java.nio.file.Path.of(settings.data().modelPath).toAbsolutePath()
        }.getOrNull()
        return if (configuredPath != null && configuredPath == localPath) ModelStatus.LOADED
               else ModelStatus.DOWNLOADED
    }

    private fun hwRecommendationText(rec: String): String = when (rec) {
        "recommended"    -> Strings.ModelCard.HW_OK
        "may_run_slowly" -> Strings.ModelCard.HW_SLOW
        else             -> Strings.ModelCard.HW_INSUFFICIENT
    }

    private fun hwRecommendationColor(rec: String): java.awt.Color = when (rec) {
        "recommended"    -> JBColor(Color(0x34C759), Color(0x30D158))
        "may_run_slowly" -> JBColor(Color(0xFF9500), Color(0xFFAA00))
        else             -> JBColor.RED
    }

    private fun specializationText(s: ModelSpecialization) = when (s) {
        ModelSpecialization.CODING           -> "Coding"
        ModelSpecialization.GENERAL_REASONING -> "Reasoning"
        ModelSpecialization.BALANCED         -> "Balanced"
    }

    private fun catalogStatusText(s: ModelCatalogStatus) = when (s) {
        ModelCatalogStatus.RECOMMENDED  -> "Recommended"
        ModelCatalogStatus.AVAILABLE    -> "Available"
        ModelCatalogStatus.LEGACY       -> "Legacy"
        ModelCatalogStatus.EXPERIMENTAL -> "Experimental"
    }

    private fun buildLinksLine(d: ExtendedModelDescriptor): String {
        val links = buildList {
            if (d.repositoryUrl.isNotBlank()) add("Repo")
            if (d.modelCardUrl.isNotBlank()) add("Card")
            if (d.downloadSourceUrl.isNotBlank()) add("Download")
        }
        return if (links.isEmpty()) "" else "Links: ${links.joinToString(" · ")}"
    }

    private fun tierBadgeText(tier: ModelTier): String = when (tier) {
        ModelTier.TIER_1 -> Strings.ModelCard.TIER_BADGE_1
        ModelTier.TIER_2 -> Strings.ModelCard.TIER_BADGE_2
        ModelTier.TIER_3 -> Strings.ModelCard.TIER_BADGE_3
    }

    private fun tierBadgeColor(tier: ModelTier): java.awt.Color = when (tier) {
        ModelTier.TIER_1 -> JBColor(Color(0x34C759), Color(0x30D158))
        ModelTier.TIER_2 -> JBColor(Color(0xFF9500), Color(0xFFAA00))
        ModelTier.TIER_3 -> JBColor.RED
    }

}
