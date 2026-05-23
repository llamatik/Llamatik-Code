package com.llamatik.teammate.ui.chat

import com.intellij.icons.AllIcons
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.editor.highlighter.EditorHighlighterFactory
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.ui.LlamatikTheme
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.datatransfer.StringSelection
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.text.DefaultCaret

/**
 * Renders a markdown string as a vertical stack of Swing components.
 *
 * Supported syntax:
 *  - Fenced code blocks (```lang … ```)
 *  - **bold**, *italic*, `inline code`
 *  - Numbered lists (1. …) and bullet lists (- / * …)
 *  - Plain paragraphs
 *
 * All colours are pulled from [LlamatikTheme] so they update with IDE theme switches.
 * Call [setContent] to replace the rendered content at any time.
 */
class MarkdownRenderer(private val project: Project? = null) : JPanel() {

    private val contentPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    init {
        layout = BorderLayout()
        isOpaque = false
        add(contentPanel, BorderLayout.CENTER)
    }

    fun setContent(markdown: String) {
        contentPanel.removeAll()
        for (block in parseBlocks(markdown)) {
            val widget = buildBlockWidget(block)
            widget.alignmentX = LEFT_ALIGNMENT
            contentPanel.add(widget)
        }
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    // ── Block model ───────────────────────────────────────────────────────────

    private sealed class Block {
        data class Paragraph(val text: String) : Block()
        data class CodeBlock(val language: String, val code: String) : Block()
        data class BulletList(val items: List<String>) : Block()
        data class NumberedList(val items: List<String>) : Block()
    }

    // ── Parser ────────────────────────────────────────────────────────────────

    private fun parseBlocks(text: String): List<Block> {
        val blocks = mutableListOf<Block>()
        val lines = text.lines()
        var i = 0

        while (i < lines.size) {
            val line = lines[i]

            // Fenced code block
            if (line.trimStart().startsWith("```")) {
                val lang = line.trimStart().removePrefix("```").trim()
                val codeLines = mutableListOf<String>()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    codeLines.add(lines[i])
                    i++
                }
                i++ // skip closing ```
                blocks.add(Block.CodeBlock(lang, codeLines.joinToString("\n")))
                continue
            }

            // Bullet list run
            if (line.matches(Regex("^\\s*[-*]\\s+.*"))) {
                val items = mutableListOf<String>()
                while (i < lines.size && lines[i].matches(Regex("^\\s*[-*]\\s+.*"))) {
                    items.add(lines[i].trimStart().removePrefix("-").removePrefix("*").trimStart())
                    i++
                }
                blocks.add(Block.BulletList(items))
                continue
            }

            // Numbered list run
            if (line.matches(Regex("^\\s*\\d+\\.\\s+.*"))) {
                val items = mutableListOf<String>()
                while (i < lines.size && lines[i].matches(Regex("^\\s*\\d+\\.\\s+.*"))) {
                    items.add(lines[i].trimStart().replace(Regex("^\\d+\\.\\s+"), ""))
                    i++
                }
                blocks.add(Block.NumberedList(items))
                continue
            }

            // Blank line separator — skip
            if (line.isBlank()) {
                i++
                continue
            }

            // Paragraph: accumulate non-blank, non-code, non-list lines
            val paraLines = mutableListOf<String>()
            while (i < lines.size &&
                lines[i].isNotBlank() &&
                !lines[i].trimStart().startsWith("```") &&
                !lines[i].matches(Regex("^\\s*[-*]\\s+.*")) &&
                !lines[i].matches(Regex("^\\s*\\d+\\.\\s+.*"))
            ) {
                paraLines.add(lines[i])
                i++
            }
            if (paraLines.isNotEmpty()) {
                blocks.add(Block.Paragraph(paraLines.joinToString("\n")))
            }
        }
        return blocks
    }

    // ── Widget builders ───────────────────────────────────────────────────────

    private fun buildBlockWidget(block: Block): JPanel = when (block) {
        is Block.CodeBlock -> buildCodeBlock(block)
        is Block.Paragraph -> buildParagraph(block.text)
        is Block.BulletList -> buildList(block.items, numbered = false)
        is Block.NumberedList -> buildList(block.items, numbered = true)
    }

    private fun buildParagraph(text: String): JPanel = JPanel(BorderLayout()).apply {
        isOpaque = false
        border = JBUI.Borders.empty(0, 0, 6, 0)
        val html = inlineMarkdownToHtml(text)
        val label = JBLabel("<html><body style='width:100%'>$html</body></html>").apply {
            foreground = LlamatikTheme.onSurface
            font = font.deriveFont(Font.PLAIN, font.size2D + 1f)
        }
        add(label, BorderLayout.CENTER)
    }

    private fun buildList(items: List<String>, numbered: Boolean): JPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        border = JBUI.Borders.empty(0, 0, 6, 0)
        items.forEachIndexed { index, item ->
            val prefix = if (numbered) "${index + 1}. " else "• "
            val html = inlineMarkdownToHtml(item)
            val row = JPanel(BorderLayout(4, 0)).apply {
                isOpaque = false
                border = JBUI.Borders.empty(1, 0)
                add(JBLabel(prefix).apply {
                    foreground = LlamatikTheme.onSurface
                    font = font.deriveFont(Font.PLAIN, font.size2D + 1f)
                    verticalAlignment = JBLabel.TOP
                    preferredSize = Dimension(if (numbered) 28 else 16, preferredSize.height)
                    minimumSize = Dimension(if (numbered) 28 else 16, 0)
                }, BorderLayout.WEST)
                add(JBLabel("<html><body>$html</body></html>").apply {
                    foreground = LlamatikTheme.onSurface
                    font = font.deriveFont(Font.PLAIN, font.size2D + 1f)
                }, BorderLayout.CENTER)
            }
            row.alignmentX = LEFT_ALIGNMENT
            add(row)
        }
    }

    private fun buildCodeBlock(block: Block.CodeBlock): JPanel {
        val lang = block.language.ifBlank { "Code" }
            .replaceFirstChar { it.uppercase() }

        val langLabel = JBLabel(lang).apply {
            foreground = LlamatikTheme.onSurfaceVariant
            font = font.deriveFont(Font.BOLD, font.size2D - 1f)
            border = JBUI.Borders.empty(0, 4)
        }

        val copyBtn = JButton(AllIcons.Actions.Copy).apply {
            toolTipText = "Copy code"
            isFocusPainted = false
            isContentAreaFilled = false
            isBorderPainted = false
            preferredSize = Dimension(22, 22)
            border = JBUI.Borders.empty(1)
            addActionListener {
                CopyPasteManager.getInstance().setContents(StringSelection(block.code))
            }
        }

        val header = JPanel(BorderLayout()).apply {
            background = LlamatikTheme.codeHeaderBackground
            isOpaque = true
            border = JBUI.Borders.empty(4, 8, 4, 6)
            add(langLabel, BorderLayout.WEST)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
                isOpaque = false
                add(copyBtn)
            }, BorderLayout.EAST)
        }

        val codeBody: JComponent = buildCodeBody(block)

        val scrollWrapper = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = LlamatikTheme.codeBackground
            add(codeBody, BorderLayout.CENTER)
        }

        return object : JPanel(BorderLayout()) {
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = LlamatikTheme.codeBackground
                g2.fillRoundRect(0, 0, width, height, 10, 10)
                g2.dispose()
            }
            override fun getMaximumSize(): Dimension = Dimension(Int.MAX_VALUE, preferredSize.height)
        }.apply {
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            val outerBorder = JBUI.Borders.empty(4, 0, 8, 0)
            border = BorderFactory.createCompoundBorder(
                outerBorder,
                BorderFactory.createLineBorder(LlamatikTheme.outline, 1, true)
            )
            add(header, BorderLayout.NORTH)
            add(scrollWrapper, BorderLayout.CENTER)
        }
    }

    private fun buildCodeBody(block: Block.CodeBlock): JComponent {
        if (project != null) {
            val ext = block.language.lowercase()
            val fileType = FileTypeManager.getInstance().getFileTypeByExtension(ext)
                .takeIf { it !is PlainTextFileType || ext == "txt" }
                ?: PlainTextFileType.INSTANCE
            val document = EditorFactory.getInstance().createDocument(block.code)
            val editor = EditorFactory.getInstance().createViewer(document, project) as EditorEx
            editor.apply {
                isViewer = true
                val scheme = EditorColorsManager.getInstance().globalScheme
                colorsScheme = scheme
                settings.apply {
                    isLineNumbersShown = false
                    isLineMarkerAreaShown = false
                    isFoldingOutlineShown = false
                    isIndentGuidesShown = false
                    isUseSoftWraps = false
                    additionalLinesCount = 0
                    additionalColumnsCount = 0
                    isRightMarginShown = false
                    isCaretRowShown = false
                }
                val highlighter = EditorHighlighterFactory.getInstance()
                    .createEditorHighlighter(project, fileType)
                setHighlighter(highlighter)
            }
            // Wrap the editor component so we can add padding and dispose on removal
            return object : JPanel(BorderLayout()) {
                init {
                    isOpaque = false
                    border = JBUI.Borders.empty(6, 8)
                    add(editor.component, BorderLayout.CENTER)
                }
                override fun removeNotify() {
                    super.removeNotify()
                    if (!editor.isDisposed) EditorFactory.getInstance().releaseEditor(editor)
                }
            }
        }
        // Fallback: plain monospaced text area
        return object : JBTextArea(block.code) {
            override fun getPreferredSize(): Dimension {
                val ps = super.getPreferredSize()
                return Dimension(0, ps.height)
            }
        }.apply {
            isEditable = false
            isOpaque = true
            background = LlamatikTheme.codeBackground
            foreground = LlamatikTheme.onSurface
            lineWrap = false
            wrapStyleWord = false
            font = Font(Font.MONOSPACED, Font.PLAIN, font.size - 1)
            border = JBUI.Borders.empty(10, 12)
            caret = object : DefaultCaret() {
                override fun setSelectionVisible(visible: Boolean) = super.setSelectionVisible(true)
            }
        }
    }

    // ── Inline markdown → HTML ────────────────────────────────────────────────

    private fun inlineMarkdownToHtml(text: String): String {
        var s = text
        // Escape HTML entities first
        s = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        // Bold+italic: ***text***
        s = s.replace(Regex("""\*\*\*(.+?)\*\*\*""")) {
            "<b><i>${it.groupValues[1]}</i></b>"
        }
        // Bold: **text**
        s = s.replace(Regex("""\*\*(.+?)\*\*""")) {
            "<b>${it.groupValues[1]}</b>"
        }
        // Italic: *text* or _text_
        s = s.replace(Regex("""\*(.+?)\*""")) { "<i>${it.groupValues[1]}</i>" }
        s = s.replace(Regex("""_(.+?)_""")) { "<i>${it.groupValues[1]}</i>" }
        // Inline code: `code`
        s = s.replace(Regex("""`([^`]+)`""")) {
            "<span style='font-family:monospace; background-color:${LlamatikTheme.htmlInlineCodeBackground}; color:${LlamatikTheme.htmlOnSurface};'>&nbsp;${it.groupValues[1]}&nbsp;</span>"
        }
        // Newlines inside a paragraph become <br>
        s = s.replace("\n", "<br>")
        return s
    }
}
