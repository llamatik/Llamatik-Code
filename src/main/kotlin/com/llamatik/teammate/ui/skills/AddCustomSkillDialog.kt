package com.llamatik.teammate.ui.skills

import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.skills.SkillDescriptor
import com.llamatik.teammate.skills.SkillService
import com.llamatik.teammate.ui.LlamatikTheme
import com.llamatik.teammate.ui.Strings
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextArea

class AddCustomSkillDialog(
    private val skillService: SkillService,
    private val onAdded: (SkillDescriptor) -> Unit,
) : DialogWrapper(null, true) {

    private val nameField = JBTextField().apply {
        emptyText.text = Strings.Skills.ADD_SKILL_NAME_PLACEHOLDER
    }
    private val contentArea = JTextArea(12, 50).apply {
        lineWrap = true
        wrapStyleWord = true
        font = font.deriveFont(12f)
    }
    private val urlField = JBTextField().apply {
        emptyText.text = Strings.Skills.ADD_SKILL_URL_PLACEHOLDER
    }
    private val tabs = JBTabbedPane()

    init {
        title = Strings.Skills.ADD_SKILL_DIALOG_TITLE
        isModal = true
        init()
    }

    override fun createCenterPanel(): JComponent {
        val pastePanel = buildPastePanel()
        val urlPanel = buildUrlPanel()

        tabs.addTab(Strings.Skills.ADD_SKILL_TAB_PASTE, pastePanel)
        tabs.addTab(Strings.Skills.ADD_SKILL_TAB_URL, urlPanel)

        return JPanel(BorderLayout()).apply {
            background = LlamatikTheme.background
            preferredSize = Dimension(540, 400)
            add(tabs, BorderLayout.CENTER)
        }
    }

    private fun buildPastePanel(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = LlamatikTheme.background
            border = JBUI.Borders.empty(12)
        }

        val nameLabel = JBLabel(Strings.Skills.ADD_SKILL_NAME_LABEL).apply {
            border = JBUI.Borders.emptyBottom(4)
        }
        nameField.maximumSize = Dimension(Int.MAX_VALUE, nameField.preferredSize.height)
        nameField.alignmentX = 0f

        val contentLabel = JBLabel(Strings.Skills.ADD_SKILL_CONTENT_LABEL).apply {
            border = JBUI.Borders.compound(JBUI.Borders.emptyTop(12), JBUI.Borders.emptyBottom(4))
        }

        val scrolledContent = JBScrollPane(contentArea).apply {
            border = BorderFactory.createLineBorder(LlamatikTheme.outline, 1, true)
        }
        scrolledContent.alignmentX = 0f

        panel.add(nameLabel)
        panel.add(nameField)
        panel.add(Box.createVerticalStrut(2))
        panel.add(contentLabel)
        panel.add(scrolledContent)

        return panel
    }

    private fun buildUrlPanel(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = LlamatikTheme.background
            border = JBUI.Borders.empty(12)
        }

        val urlLabel = JBLabel(Strings.Skills.ADD_SKILL_URL_LABEL).apply {
            border = JBUI.Borders.emptyBottom(4)
        }
        urlField.maximumSize = Dimension(Int.MAX_VALUE, urlField.preferredSize.height)
        urlField.alignmentX = 0f

        val hint = JBLabel(
            "<html><span style='color:${LlamatikTheme.htmlOnSurfaceVariant};'>" +
                "The file will be downloaded and saved to ~/.llamatik/skills/" +
                "</span></html>",
        ).apply {
            border = JBUI.Borders.emptyTop(8)
        }

        panel.add(urlLabel)
        panel.add(urlField)
        panel.add(hint)

        return panel
    }

    override fun doOKAction() {
        when (tabs.selectedIndex) {
            0 -> handlePaste()
            1 -> handleUrl()
        }
    }

    private fun handlePaste() {
        val name = nameField.text.trim()
        val content = contentArea.text.trim()
        if (name.isBlank()) {
            Messages.showErrorDialog(contentPane, Strings.Skills.ADD_SKILL_ERROR_EMPTY_NAME, title)
            return
        }
        if (content.isBlank()) {
            Messages.showErrorDialog(contentPane, Strings.Skills.ADD_SKILL_ERROR_EMPTY_CONTENT, title)
            return
        }
        val descriptor = skillService.addCustomSkillFromText(name, content)
        onAdded(descriptor)
        super.doOKAction()
    }

    private fun handleUrl() {
        val url = urlField.text.trim()
        if (url.isBlank()) {
            Messages.showErrorDialog(contentPane, Strings.Skills.ADD_SKILL_ERROR_EMPTY_URL, title)
            return
        }
        try {
            val descriptor = skillService.addCustomSkillFromUrl(url)
            onAdded(descriptor)
            super.doOKAction()
        } catch (_: Exception) {
            Messages.showErrorDialog(contentPane, Strings.Skills.ADD_SKILL_ERROR_DOWNLOAD, title)
        }
    }
}
