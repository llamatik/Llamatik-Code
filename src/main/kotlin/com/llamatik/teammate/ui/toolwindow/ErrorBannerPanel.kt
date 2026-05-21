package com.llamatik.teammate.ui.toolwindow

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.ui.Strings
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

class ErrorBannerPanel : JPanel(BorderLayout(8, 0)) {
    private val messageLabel = JLabel()
    private val dismissButton = JButton(Strings.App.DISMISS)
    private var onRetry: (() -> Unit)? = null

    init {
        background = JBColor(Color(0xFFE0E0, false), Color(0x5C2626, false))
        border = JBUI.Borders.empty(6, 12)
        add(messageLabel, BorderLayout.CENTER)
        val actions = JPanel().apply { isOpaque = false }
        val retryButton = JButton(Strings.App.RETRY)
        retryButton.addActionListener { onRetry?.invoke() }
        actions.add(retryButton)
        actions.add(dismissButton)
        add(actions, BorderLayout.EAST)
        isVisible = false
        dismissButton.addActionListener { isVisible = false }
    }

    fun showError(message: String, retry: (() -> Unit)? = null) {
        messageLabel.text = message
        onRetry = retry
        isVisible = true
        revalidate()
    }

    fun hideBanner() { isVisible = false }
}
