package com.llamatik.teammate.ui.skills

import com.intellij.openapi.components.service
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.skills.SkillDescriptor
import com.llamatik.teammate.skills.SkillService
import com.llamatik.teammate.skills.SkillStatus
import com.llamatik.teammate.ui.LlamatikTheme
import com.llamatik.teammate.ui.Strings
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component.LEFT_ALIGNMENT
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.BorderFactory
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.event.DocumentEvent

class SkillLibraryDialog(
    private val skillService: SkillService = service<SkillService>(),
    private val onChanged: () -> Unit = {},
) : DialogWrapper(null, true) {

    private val searchField = SearchTextField(false)
    private val listPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = LlamatikTheme.background
        border = JBUI.Borders.empty(8)
    }

    private val scrollPane = JBScrollPane(listPanel).apply {
        border = JBUI.Borders.empty()
        viewport.background = LlamatikTheme.background
        preferredSize = Dimension(780, 580)
    }

    init {
        title = Strings.Skills.LIBRARY_TITLE
        isModal = true
        init()
    }

    override fun createCenterPanel(): JComponent {
        searchField.textEditor.border = JBUI.Borders.empty(4, 8)
        searchField.textEditor.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                rebuildList(searchField.text.trim())
            }
        })

        val searchRow = JPanel(BorderLayout()).apply {
            background = LlamatikTheme.background
            border = JBUI.Borders.compound(
                BorderFactory.createMatteBorder(0, 0, 1, 0, LlamatikTheme.divider),
                JBUI.Borders.empty(8, 12),
            )
            add(searchField, BorderLayout.CENTER)
        }

        rebuildList("")

        return JPanel(BorderLayout()).apply {
            background = LlamatikTheme.background
            add(searchRow, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }
    }

    override fun createActions() = arrayOf(okAction)

    override fun getDimensionServiceKey() = "LlamatikSkillLibraryDialog"

    private fun rebuildList(query: String) {
        listPanel.removeAll()

        val filtered = skillService.catalog().filter { skill ->
            query.isBlank() ||
                skill.displayName.contains(query, ignoreCase = true) ||
                skill.description.contains(query, ignoreCase = true) ||
                skill.category.contains(query, ignoreCase = true) ||
                skill.tags.any { it.contains(query, ignoreCase = true) }
        }

        if (filtered.isEmpty()) {
            listPanel.add(JBLabel(Strings.Skills.NO_RESULTS).apply {
                foreground = LlamatikTheme.onSurfaceVariant
                border = JBUI.Borders.empty(24)
                alignmentX = LEFT_ALIGNMENT
            })
        } else {
            filtered.forEach { skill ->
                listPanel.add(buildSkillCard(skill))
                listPanel.add(Box.createVerticalStrut(8))
            }
        }

        listPanel.revalidate()
        listPanel.repaint()
    }

    private fun buildSkillCard(skill: SkillDescriptor): JPanel {
        val status = skillService.skillStatus(skill.id)

        val card = JPanel(BorderLayout(0, 6)).apply {
            isOpaque = true
            background = LlamatikTheme.surface
            border = JBUI.Borders.compound(
                BorderFactory.createLineBorder(LlamatikTheme.outline, 1, true),
                JBUI.Borders.empty(12, 14),
            )
            alignmentX = LEFT_ALIGNMENT
        }

        // ── Header row: name + category badge ──────────────────────────────
        val nameLabel = JBLabel("<html><b>${skill.displayName}</b></html>").apply {
            foreground = LlamatikTheme.onSurface
        }

        val categoryBadge = buildBadge(skill.category, LlamatikTheme.primary)

        val statusBadge = buildStatusBadge(status)

        val headerRight = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            isOpaque = false
            add(statusBadge)
            add(categoryBadge)
        }

        val headerRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            add(nameLabel, BorderLayout.WEST)
            add(headerRight, BorderLayout.EAST)
        }

        // ── Description ─────────────────────────────────────────────────────
        val descLabel = JBLabel(
            "<html><span style='color:${LlamatikTheme.htmlOnSurfaceVariant};'>${skill.description}</span></html>",
        ).apply {
            border = JBUI.Borders.emptyTop(2)
        }

        // ── Footer row: tags + action buttons ───────────────────────────────
        val tagsLabel = JBLabel(
            "<html><span style='color:${LlamatikTheme.htmlOnSurfaceVariant};'>" +
                skill.tags.joinToString("  ") { "#$it" } +
                "</span></html>",
        )

        val actionButtons = buildActionButtons(skill, status)

        val footerRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(4)
            add(tagsLabel, BorderLayout.WEST)
            add(actionButtons, BorderLayout.EAST)
        }

        // ── Center content ───────────────────────────────────────────────────
        val centerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(descLabel)
            add(footerRow)
        }

        card.add(headerRow, BorderLayout.NORTH)
        card.add(centerPanel, BorderLayout.CENTER)
        // Fill full width in BoxLayout.Y_AXIS; height unconstrained so card uses preferred size.
        card.maximumSize = Dimension(Int.MAX_VALUE, Short.MAX_VALUE.toInt())

        return card
    }

    private fun buildStatusBadge(status: SkillStatus): JBLabel {
        val (text, fg) = when (status) {
            SkillStatus.ACTIVE -> Pair(Strings.Skills.STATUS_ACTIVE, LlamatikTheme.success)
            SkillStatus.DOWNLOADED -> Pair(Strings.Skills.STATUS_DOWNLOADED, LlamatikTheme.primary)
            SkillStatus.NOT_DOWNLOADED -> Pair(Strings.Skills.STATUS_NOT_DOWNLOADED, LlamatikTheme.onSurfaceVariant)
        }
        return JBLabel(text).apply {
            foreground = fg
            font = font.deriveFont(Font.BOLD, font.size2D - 1f)
            border = JBUI.Borders.compound(
                BorderFactory.createLineBorder(fg, 1, true),
                JBUI.Borders.empty(2, 7),
            )
        }
    }

    private fun buildBadge(text: String, color: Color): JBLabel =
        JBLabel(text).apply {
            foreground = color
            font = font.deriveFont(Font.PLAIN, font.size2D - 1f)
            border = JBUI.Borders.compound(
                BorderFactory.createLineBorder(color, 1, true),
                JBUI.Borders.empty(2, 7),
            )
        }

    private fun buildActionButtons(skill: SkillDescriptor, status: SkillStatus): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }

        when (status) {
            SkillStatus.NOT_DOWNLOADED -> {
                // Built-in skills are bundled — no real download needed; activate directly
                panel.add(buildButton(Strings.Skills.ACTION_ACTIVATE, primary = true) {
                    skillService.download(skill.id)
                    skillService.activate(skill.id)
                    onChanged()
                    rebuildList(searchField.text.trim())
                })
            }
            SkillStatus.DOWNLOADED -> {
                panel.add(buildButton(Strings.Skills.ACTION_ACTIVATE, primary = true) {
                    skillService.activate(skill.id)
                    onChanged()
                    rebuildList(searchField.text.trim())
                })
            }
            SkillStatus.ACTIVE -> {
                panel.add(buildButton(Strings.Skills.ACTION_DEACTIVATE, primary = false) {
                    skillService.deactivate(skill.id)
                    onChanged()
                    rebuildList(searchField.text.trim())
                })
            }
        }
        return panel
    }

    private fun buildButton(text: String, primary: Boolean, onClick: () -> Unit): JButton =
        JButton(text).apply {
            isFocusPainted = false
            if (primary) {
                background = LlamatikTheme.primary
                foreground = LlamatikTheme.onPrimary
                border = JBUI.Borders.compound(
                    BorderFactory.createLineBorder(LlamatikTheme.primary, 1, true),
                    JBUI.Borders.empty(4, 10),
                )
            } else {
                isContentAreaFilled = false
                foreground = LlamatikTheme.onSurfaceVariant
                border = JBUI.Borders.compound(
                    BorderFactory.createLineBorder(LlamatikTheme.outline, 1, true),
                    JBUI.Borders.empty(4, 10),
                )
            }
            addActionListener { onClick() }
        }
}
