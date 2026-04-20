package com.llamatik.teammate.ui.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.licensing.LicenseChecker
import com.llamatik.teammate.licensing.TrialService
import com.llamatik.teammate.model.AttachedImage
import com.llamatik.teammate.ui.LlamatikTheme
import com.llamatik.teammate.ui.Strings
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetAdapter
import java.awt.dnd.DropTargetDragEvent
import java.awt.dnd.DropTargetDropEvent
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import javax.swing.BorderFactory
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * A compact horizontal bar that shows image attachment chips and provides three
 * ways to attach images:
 *  1. 📎 button → file chooser
 *  2. Drag-and-drop onto the parent composer shell (registered externally)
 *  3. @-mention of an image path (resolved by AtMentionHandler → notifyImageMention)
 *  4. Current editor file auto-detection via [syncFromEditor]
 *
 * All attachment methods respect the [isMultimodalEnabled] gate (paid + VLM configured).
 */
class ImageAttachmentBar(
    private val project: Project,
    private val isMultimodalEnabled: () -> Boolean,
    private val onAttachmentsChanged: (List<AttachedImage>) -> Unit,
) : JPanel(BorderLayout()) {

    private val attachments = mutableListOf<AttachedImage>()

    private val chipsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
        isOpaque = false
    }

    val attachButton = JButton(AllIcons.General.Locate).apply {
        toolTipText = Strings.Multimodal.ATTACH_IMAGE_TOOLTIP
        isFocusPainted = false
        isContentAreaFilled = false
        preferredSize = Dimension(28, 28)
        border = JBUI.Borders.empty(2)
        addActionListener { onAttachClicked() }
    }

    init {
        isOpaque = false
        isVisible = false  // hidden until at least one image attached or during drag hover
        border = JBUI.Borders.empty(4, 0, 0, 0)
        add(chipsPanel, BorderLayout.CENTER)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun currentAttachments(): List<AttachedImage> = attachments.toList()

    fun clear() {
        attachments.clear()
        rebuildChips()
    }

    /**
     * Called by AtMentionHandler when the user selects an image path via @-mention.
     */
    fun notifyImageMention(absolutePath: String) {
        if (!isMultimodalEnabled()) { showUpgradePrompt(); return }
        addImageFile(File(absolutePath))
    }

    /**
     * Checks if the currently open editor file is an image and pre-attaches it.
     * Safe to call repeatedly — deduplicates by path.
     */
    fun syncFromEditor() {
        if (!isMultimodalEnabled()) return
        val editorFile = FileEditorManager.getInstance(project).selectedFiles.firstOrNull() ?: return
        val path = editorFile.path
        if (AttachedImage.isImagePath(path) && attachments.none { it.absolutePath == path }) {
            addImageFile(File(path))
        }
    }

    /**
     * Install a drag-and-drop handler onto [target] so images dropped anywhere on the
     * composer shell are captured.
     */
    fun installDropTarget(target: java.awt.Component) {
        DropTarget(target, DnDConstants.ACTION_COPY, object : DropTargetAdapter() {
            override fun dragEnter(e: DropTargetDragEvent) {
                if (e.isDataFlavorSupported(DataFlavor.javaFileListFlavor) && isMultimodalEnabled()) {
                    e.acceptDrag(DnDConstants.ACTION_COPY)
                    isVisible = true
                }
            }

            override fun dragExit(dtde: java.awt.dnd.DropTargetEvent) {
                if (attachments.isEmpty()) isVisible = false
            }

            override fun drop(e: DropTargetDropEvent) {
                e.acceptDrop(DnDConstants.ACTION_COPY)
                if (!isMultimodalEnabled()) { showUpgradePrompt(); e.dropComplete(false); return }
                try {
                    @Suppress("UNCHECKED_CAST")
                    val files = e.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File>
                    files.filter { AttachedImage.isImagePath(it.name) }.forEach { addImageFile(it) }
                    e.dropComplete(true)
                } catch (_: Exception) {
                    e.dropComplete(false)
                }
            }
        })
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun onAttachClicked() {
        if (!isMultimodalEnabled()) { showUpgradePrompt(); return }
        val chooser = JFileChooser().apply {
            dialogTitle = Strings.Multimodal.FILE_CHOOSER_TITLE
            isMultiSelectionEnabled = true
            fileFilter = FileNameExtensionFilter(
                Strings.Multimodal.FILE_FILTER_DESC,
                "png", "jpg", "jpeg", "bmp", "webp", "gif",
            )
        }
        val parent = SwingUtilities.getWindowAncestor(this)
        if (chooser.showOpenDialog(parent) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFiles.forEach { addImageFile(it) }
        }
    }

    private fun addImageFile(file: File) {
        if (!file.exists() || !file.isFile) return
        if (!AttachedImage.isImagePath(file.name)) return
        if (attachments.any { it.absolutePath == file.absolutePath }) return

        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return
        val mimeType = AttachedImage.mimeTypeFor(file.name)
        val normalized = AttachedImage.normalizeBytes(bytes, mimeType)

        val image = AttachedImage(
            displayName = file.name,
            absolutePath = file.absolutePath,
            bytes = normalized,
            mimeType = "image/jpeg",
        )
        attachments.add(image)
        rebuildChips()
        onAttachmentsChanged(attachments.toList())
    }

    private fun rebuildChips() {
        chipsPanel.removeAll()
        attachments.forEach { img -> chipsPanel.add(buildChip(img)) }
        isVisible = attachments.isNotEmpty()
        chipsPanel.revalidate()
        chipsPanel.repaint()
    }

    private fun buildChip(image: AttachedImage): JPanel {
        val chip = JPanel(BorderLayout(4, 0)).apply {
            isOpaque = false
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(LlamatikTheme.outline, 1, true),
                JBUI.Borders.empty(2, 6, 2, 4),
            )
        }

        // Tiny 24×24 thumbnail rendered inline
        val thumb: BufferedImage? = image.thumbnail
        val thumbLabel = object : JLabel() {
            override fun paintComponent(g: Graphics) {
                if (thumb != null) {
                    val g2 = g.create() as Graphics2D
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    g2.drawImage(thumb, 0, 0, 24, 24, null)
                    g2.dispose()
                } else {
                    super.paintComponent(g)
                }
            }
        }.apply {
            preferredSize = Dimension(24, 24)
            if (thumb == null) text = "🖼"
        }

        val nameLabel = JLabel(
            "<html><span style='color:${LlamatikTheme.htmlOnSurface};'>${image.displayName}</span></html>",
        ).apply {
            font = font.deriveFont(font.size2D - 1f)
        }

        val removeBtn = JButton("×").apply {
            isFocusPainted = false
            isContentAreaFilled = false
            border = JBUI.Borders.empty(0, 4)
            font = font.deriveFont(font.size2D + 1f)
            foreground = LlamatikTheme.onSurfaceVariant
            toolTipText = Strings.Multimodal.REMOVE_IMAGE_TOOLTIP
            addActionListener {
                attachments.remove(image)
                rebuildChips()
                onAttachmentsChanged(attachments.toList())
            }
        }

        chip.add(thumbLabel, BorderLayout.WEST)
        chip.add(nameLabel, BorderLayout.CENTER)
        chip.add(removeBtn, BorderLayout.EAST)
        return chip
    }

    private fun showUpgradePrompt() {
        val trialService = com.intellij.openapi.components.service<TrialService>()
        trialService.requestLicense(Strings.Multimodal.UPGRADE_MESSAGE)
    }
}
