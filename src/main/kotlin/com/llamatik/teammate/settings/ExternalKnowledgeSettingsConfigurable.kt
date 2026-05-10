package com.llamatik.teammate.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.Configurable
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.FormBuilder
import com.llamatik.teammate.external.ExternalKnowledgeLimits
import com.llamatik.teammate.external.docs.DocsSource
import com.llamatik.teammate.external.docs.DocsSourceService
import com.llamatik.teammate.external.docs.DocsSourceType
import com.llamatik.teammate.licensing.LicenseChecker
import com.llamatik.teammate.ui.Strings
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.util.UUID
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel

class ExternalKnowledgeSettingsConfigurable : Configurable {

    private val settings by lazy { service<LlamatikAppSettings>() }
    private val docsSvc by lazy { service<DocsSourceService>() }

    private lateinit var enableResearchCheck: JBCheckBox
    private lateinit var autoUseCheck: JBCheckBox
    private lateinit var showCitationsCheck: JBCheckBox
    private lateinit var preferredSourcesArea: JBTextArea
    private lateinit var blockedDomainsArea: JBTextArea
    private lateinit var docsSourcesListModel: DefaultListModel<String>
    private lateinit var docsSourcesList: JList<String>
    private lateinit var docsLimitLabel: JBLabel

    override fun getDisplayName(): String = "External Knowledge"

    override fun createComponent(): JComponent {
        val isPro = runCatching { LicenseChecker.isLicensed() == true }.getOrElse { false }
        val s = settings.data()

        enableResearchCheck = JBCheckBox(Strings.ExternalKnowledge.ENABLE_RESEARCH)
        enableResearchCheck.isSelected = s.externalResearchEnabled

        autoUseCheck = JBCheckBox(Strings.ExternalKnowledge.AUTO_USE)
        autoUseCheck.isSelected = s.externalResearchAutoUse
        if (!isPro) {
            autoUseCheck.isEnabled = false
            autoUseCheck.toolTipText = Strings.ExternalKnowledge.AUTO_USE_PRO_ONLY
        }

        showCitationsCheck = JBCheckBox(Strings.ExternalKnowledge.SHOW_CITATIONS)
        showCitationsCheck.isSelected = s.externalResearchShowCitations

        preferredSourcesArea = JBTextArea(3, 40)
        preferredSourcesArea.text = s.externalResearchPreferredSources

        blockedDomainsArea = JBTextArea(3, 40)
        blockedDomainsArea.text = s.externalResearchBlockedDomains

        docsSourcesListModel = DefaultListModel()
        docsSourcesList = JList(docsSourcesListModel)
        reloadDocsSources()

        val docsLimit = ExternalKnowledgeLimits.docsSourceLimit(isPro)
        docsLimitLabel = if (isPro) JBLabel("")
        else JBLabel(Strings.ExternalKnowledge.DOCS_SOURCE_LIMIT_FREE).apply {
            foreground = com.llamatik.teammate.ui.LlamatikTheme.onSurfaceVariant
            font = font.deriveFont(Font.ITALIC, font.size2D - 1f)
        }

        val addDocsButton = JButton(Strings.ExternalKnowledge.ADD_DOCS_SOURCE).apply {
            addActionListener {
                val name = JOptionPane.showInputDialog(null, "Source name:", "Add Docs Source", JOptionPane.PLAIN_MESSAGE)
                if (name.isNullOrBlank()) return@addActionListener
                val location = JOptionPane.showInputDialog(null, "URL or path:", "Add Docs Source", JOptionPane.PLAIN_MESSAGE)
                if (location.isNullOrBlank()) return@addActionListener
                val typeOptions = arrayOf("URL", "WEBSITE", "GITHUB", "LOCAL_FOLDER")
                val typeChoice = JOptionPane.showInputDialog(
                    null, "Source type:", "Add Docs Source",
                    JOptionPane.PLAIN_MESSAGE, null, typeOptions, typeOptions[0]
                ) as? String ?: "URL"
                val sourceType = runCatching { DocsSourceType.valueOf(typeChoice) }.getOrElse { DocsSourceType.URL }
                val source = DocsSource(
                    id = UUID.randomUUID().toString().take(8),
                    name = name,
                    type = sourceType,
                    location = location,
                )
                val added = docsSvc.addSource(source)
                if (!added) {
                    JOptionPane.showMessageDialog(null, Strings.ExternalKnowledge.DOCS_SOURCE_LIMIT_FREE, "Limit reached", JOptionPane.WARNING_MESSAGE)
                }
                reloadDocsSources()
            }
        }

        val removeDocsButton = JButton(Strings.ExternalKnowledge.REMOVE_DOCS_SOURCE).apply {
            addActionListener {
                val selected = docsSourcesList.selectedValue ?: return@addActionListener
                val id = selected.substringBefore("]").removePrefix("[").trim()
                docsSvc.removeSource(id)
                reloadDocsSources()
            }
        }

        val docsPanel = JPanel(BorderLayout(0, 4)).apply {
            add(JBScrollPane(docsSourcesList).apply { preferredSize = Dimension(300, 80) }, BorderLayout.CENTER)
            add(JPanel().apply {
                add(addDocsButton)
                add(removeDocsButton)
            }, BorderLayout.SOUTH)
        }

        val dailyLimit = ExternalKnowledgeLimits.dailySearchLimit(isPro)
        val pagesPerTask = ExternalKnowledgeLimits.pagesPerTask(isPro)

        val proNoteFont = Font(Font.DIALOG, Font.ITALIC, 11)

        val limitLabel = JBLabel(Strings.ExternalKnowledge.dailyLimitStatus(s.externalSearchUsedToday, dailyLimit)).apply {
            font = proNoteFont
            foreground = com.llamatik.teammate.ui.LlamatikTheme.onSurfaceVariant
        }
        val pagesLabel = JBLabel(Strings.ExternalKnowledge.pagesPerTaskLabel(pagesPerTask)).apply {
            font = proNoteFont
            foreground = com.llamatik.teammate.ui.LlamatikTheme.onSurfaceVariant
        }
        val providerLabel = JBLabel(Strings.ExternalKnowledge.PROVIDER_DEFAULT)
        val privacyLabel = JBLabel("<html><i>${Strings.ExternalKnowledge.PRIVACY_NOTICE}</i></html>").apply {
            font = font.deriveFont(Font.ITALIC, font.size2D - 1f)
        }
        val autoUseProLabel = if (!isPro) JBLabel(Strings.ExternalKnowledge.PRO_REQUIRED_AUTO_USE).apply {
            foreground = com.llamatik.teammate.ui.LlamatikTheme.onSurfaceVariant
            font = font.deriveFont(Font.ITALIC, font.size2D - 1f)
        } else null

        return FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel(Strings.ExternalKnowledge.SECTION_TITLE), JBLabel(""))
            .addComponent(enableResearchCheck)
            .addSeparator()
            .addLabeledComponent(JBLabel(Strings.ExternalKnowledge.SEARCH_PROVIDER), providerLabel)
            .addLabeledComponent(JBLabel(Strings.ExternalKnowledge.DAILY_LIMIT), limitLabel)
            .addLabeledComponent(JBLabel(Strings.ExternalKnowledge.PAGES_PER_TASK), pagesLabel)
            .addSeparator()
            .addComponent(showCitationsCheck)
            .addComponent(autoUseCheck)
            .apply { if (autoUseProLabel != null) addComponent(autoUseProLabel) }
            .addSeparator()
            .addLabeledComponent(JBLabel(Strings.ExternalKnowledge.PREFERRED_SOURCES), JBScrollPane(preferredSourcesArea))
            .addLabeledComponent(JBLabel(Strings.ExternalKnowledge.BLOCKED_DOMAINS), JBScrollPane(blockedDomainsArea))
            .addSeparator()
            .addLabeledComponent(JBLabel(Strings.ExternalKnowledge.SECTION_DOCS_SOURCES), docsLimitLabel)
            .addComponent(docsPanel)
            .addSeparator()
            .addComponent(privacyLabel)
            .addComponentFillVertically(JPanel(), 0)
            .panel
    }

    override fun isModified(): Boolean {
        val s = settings.data()
        return enableResearchCheck.isSelected != s.externalResearchEnabled ||
                autoUseCheck.isSelected != s.externalResearchAutoUse ||
                showCitationsCheck.isSelected != s.externalResearchShowCitations ||
                preferredSourcesArea.text != s.externalResearchPreferredSources ||
                blockedDomainsArea.text != s.externalResearchBlockedDomains
    }

    override fun apply() {
        val s = settings.data()
        s.externalResearchEnabled = enableResearchCheck.isSelected
        s.externalResearchAutoUse = autoUseCheck.isSelected
        s.externalResearchShowCitations = showCitationsCheck.isSelected
        s.externalResearchPreferredSources = preferredSourcesArea.text.trim()
        s.externalResearchBlockedDomains = blockedDomainsArea.text.trim()
    }

    override fun reset() {
        val s = settings.data()
        enableResearchCheck.isSelected = s.externalResearchEnabled
        autoUseCheck.isSelected = s.externalResearchAutoUse
        showCitationsCheck.isSelected = s.externalResearchShowCitations
        preferredSourcesArea.text = s.externalResearchPreferredSources
        blockedDomainsArea.text = s.externalResearchBlockedDomains
        reloadDocsSources()
    }

    private fun reloadDocsSources() {
        docsSourcesListModel.removeAllElements()
        docsSvc.listSources().forEach { s ->
            docsSourcesListModel.addElement("[${s.id}] ${s.name} — ${s.location}")
        }
    }
}
