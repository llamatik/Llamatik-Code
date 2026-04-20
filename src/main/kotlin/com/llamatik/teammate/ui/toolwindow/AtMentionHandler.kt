package com.llamatik.teammate.ui.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.context.ProjectContextService
import com.llamatik.teammate.ui.LlamatikTheme
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.Point
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JWindow
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.text.JTextComponent

/**
 * Attaches to a [JTextComponent] and shows a floating file-picker popup whenever the
 * user types `@` followed by characters. The popup is a non-focusable [JWindow] so
 * keyboard focus stays in the text field the whole time.
 *
 * When [imageBar] is provided, selecting an image file path (png/jpg/…) routes the
 * file to the attachment bar instead of inserting a text token.
 *
 * Keyboard behaviour (while popup is visible):
 *  - Arrow Up/Down  — navigate the list
 *  - Enter/Tab      — confirm selection
 *  - Escape         — dismiss without inserting
 */
class AtMentionHandler(
    private val textComponent: JTextComponent,
    private val project: Project,
    private val imageBar: ImageAttachmentBar? = null,
) {
    private val contextService = ProjectContextService(project)

    // ── Floating window (non-focusable so the text field keeps focus) ─────────
    private val listModel = DefaultListModel<String>()
    private val resultList = JBList(listModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        background = LlamatikTheme.surface
        foreground = LlamatikTheme.onSurface
        selectionBackground = LlamatikTheme.primary
        selectionForeground = LlamatikTheme.onPrimary
        font = font.deriveFont(Font.PLAIN, font.size2D)
        border = JBUI.Borders.empty(2, 8)
        fixedCellHeight = 24
        isFocusable = false
    }

    private val scrollPane = JScrollPane(resultList).apply {
        border = JBUI.Borders.empty()
        viewport.background = LlamatikTheme.surface
        isFocusable = false
        verticalScrollBar.isFocusable = false
        horizontalScrollBar.isFocusable = false
    }

    private val contentPanel = JPanel(BorderLayout()).apply {
        background = LlamatikTheme.surface
        border = BorderFactory.createLineBorder(LlamatikTheme.outline, 1)
        add(scrollPane, BorderLayout.CENTER)
        isFocusable = false
    }

    // JWindow with focusableWindowState=false keeps focus in the text component
    private val floatingWindow = object : JWindow() {
        override fun getFocusableWindowState() = false
    }.apply {
        contentPane.add(contentPanel)
        background = LlamatikTheme.surface
    }

    // Offset of the `@` character that opened the current session
    private var atStartOffset = -1
    private var suppressDocumentListener = false

    // ── Document listener ─────────────────────────────────────────────────────
    private val documentListener = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = onDocumentChanged()
        override fun removeUpdate(e: DocumentEvent) = onDocumentChanged()
        override fun changedUpdate(e: DocumentEvent) = Unit
    }

    // ── Key listener — navigates / confirms / cancels without stealing focus ──
    private val keyListener = object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
            if (!floatingWindow.isVisible) return
            when (e.keyCode) {
                KeyEvent.VK_DOWN -> {
                    val next = (resultList.selectedIndex + 1).coerceAtMost(listModel.size - 1)
                    resultList.selectedIndex = next
                    resultList.ensureIndexIsVisible(next)
                    e.consume()
                }
                KeyEvent.VK_UP -> {
                    val prev = (resultList.selectedIndex - 1).coerceAtLeast(0)
                    resultList.selectedIndex = prev
                    resultList.ensureIndexIsVisible(prev)
                    e.consume()
                }
                KeyEvent.VK_ENTER, KeyEvent.VK_TAB -> {
                    confirmSelection()
                    e.consume()
                }
                KeyEvent.VK_ESCAPE -> {
                    dismissPopup()
                    e.consume()
                }
            }
        }
    }

    init {
        textComponent.document.addDocumentListener(documentListener)
        textComponent.addKeyListener(keyListener)

        resultList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (resultList.selectedIndex >= 0) confirmSelection()
            }
        })
    }

    // ── Logic ─────────────────────────────────────────────────────────────────

    private fun onDocumentChanged() {
        if (suppressDocumentListener) return
        SwingUtilities.invokeLater { refresh() }
    }

    private fun refresh() {
        val text = textComponent.text
        val caret = textComponent.caretPosition.coerceIn(0, text.length)

        val atIdx = findAtTrigger(text, caret)
        if (atIdx < 0) {
            dismissPopup()
            return
        }

        atStartOffset = atIdx
        val query = text.substring(atIdx + 1, caret)

        if (query.contains(' ') || query.contains('\n') || query.length > 60) {
            dismissPopup()
            return
        }

        val results = if (query.isBlank()) emptyList()
                      else contextService.searchFilenames(query).take(10)

        if (results.isEmpty()) {
            dismissPopup()
            return
        }

        listModel.clear()
        results.forEach { listModel.addElement(it) }
        if (resultList.selectedIndex < 0) resultList.selectedIndex = 0

        showFloating()
    }

    private fun findAtTrigger(text: String, caretPos: Int): Int {
        for (i in caretPos - 1 downTo 0) {
            val ch = text[i]
            if (ch == '@') return i
            if (ch == ' ' || ch == '\n') return -1
        }
        return -1
    }

    private fun showFloating() {
        val rows = listModel.size.coerceIn(1, 8)
        val itemH = resultList.fixedCellHeight + 2
        val popupH = rows * itemH + 6
        val popupW = 380

        floatingWindow.setSize(popupW, popupH)

        // Position: convert the text component's top-left corner to screen coords,
        // then place the popup directly above the composer shell.
        // We walk up to find the root window so we can anchor to the composer top.
        val screenOrigin: Point = run {
            val pt = Point(0, 0)
            SwingUtilities.convertPointToScreen(pt, textComponent)
            pt
        }
        // Place above the text component's top edge, with a small gap
        val screenX = screenOrigin.x
        val screenY = screenOrigin.y - popupH - 6

        floatingWindow.setLocation(screenX, screenY)
        floatingWindow.isVisible = true
        floatingWindow.toFront()
    }

    private fun dismissPopup() {
        atStartOffset = -1
        floatingWindow.isVisible = false
    }

    private fun confirmSelection() {
        val selected = resultList.selectedValue ?: return
        dismissPopup()

        // If the selected file is an image and an attachment bar is available, route
        // it there instead of inserting a text token.
        if (imageBar != null && com.llamatik.teammate.model.AttachedImage.isImagePath(selected)) {
            val absolutePath = resolveAbsolutePath(selected)
            if (absolutePath != null) {
                imageBar.notifyImageMention(absolutePath)
                // Remove the @-prefix the user typed from the text input
                removeAtPrefix()
                textComponent.requestFocusInWindow()
                return
            }
        }

        insertFileReference(selected)
        textComponent.requestFocusInWindow()
    }

    private fun removeAtPrefix() {
        val doc = textComponent.document
        val caret = textComponent.caretPosition.coerceIn(0, doc.length)
        val replaceStart = atStartOffset
        val replaceEnd = caret
        if (replaceStart < 0 || replaceStart > replaceEnd) return
        suppressDocumentListener = true
        try { doc.remove(replaceStart, replaceEnd - replaceStart) } finally { suppressDocumentListener = false }
    }

    private fun resolveAbsolutePath(relativePath: String): String? {
        val base = project.basePath ?: return null
        val file = java.io.File(base, relativePath)
        return if (file.exists()) file.absolutePath else null
    }

    private fun insertFileReference(path: String) {
        val doc = textComponent.document
        val caret = textComponent.caretPosition.coerceIn(0, doc.length)
        val replaceStart = atStartOffset
        val replaceEnd = caret

        if (replaceStart < 0 || replaceStart > replaceEnd) return

        suppressDocumentListener = true
        try {
            doc.remove(replaceStart, replaceEnd - replaceStart)
            val token = "@{$path} "
            doc.insertString(replaceStart, token, null)
            textComponent.caretPosition = replaceStart + token.length
        } finally {
            suppressDocumentListener = false
        }
    }

    fun detach() {
        textComponent.document.removeDocumentListener(documentListener)
        textComponent.removeKeyListener(keyListener)
        floatingWindow.dispose()
    }
}
