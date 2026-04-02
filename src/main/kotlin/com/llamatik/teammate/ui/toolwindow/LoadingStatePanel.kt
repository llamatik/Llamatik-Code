package com.llamatik.teammate.ui.toolwindow

import com.intellij.ui.AnimatedIcon
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.ui.Strings
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.BoxLayout
import javax.swing.JPanel

/** Shown while a model is being loaded into memory. */
class LoadingStatePanel : JPanel(BorderLayout()) {

    init {
        border = JBUI.Borders.empty(32, 24)

        val centerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }

        val spinner = JBLabel(AnimatedIcon.Default()).apply {
            alignmentX = CENTER_ALIGNMENT
            border = JBUI.Borders.emptyBottom(12)
        }

        val loadingLabel = JBLabel(Strings.LoadingState.TITLE).apply {
            font = font.deriveFont(Font.BOLD, font.size2D + 2f)
            alignmentX = CENTER_ALIGNMENT
        }

        val hintLabel = JBLabel(Strings.LoadingState.HINT).apply {
            alignmentX = CENTER_ALIGNMENT
            border = JBUI.Borders.emptyTop(8)
        }

        centerPanel.add(spinner)
        centerPanel.add(loadingLabel)
        centerPanel.add(hintLabel)

        add(centerPanel, BorderLayout.CENTER)
    }
}
