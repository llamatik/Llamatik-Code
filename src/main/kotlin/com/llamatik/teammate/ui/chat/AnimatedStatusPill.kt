package com.llamatik.teammate.ui.chat

import com.intellij.util.ui.JBUI
import com.llamatik.teammate.ui.LlamatikTheme
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel
import javax.swing.JLabel
import javax.swing.Timer
import java.awt.BorderLayout
import kotlin.math.sin

/**
 * A rounded pill label that animates its border with a pulsing glow while [isAnimating] is true.
 * Uses [LlamatikTheme.primary] as the glow colour so it adapts to the IDE theme.
 */
class AnimatedStatusPill : JPanel(BorderLayout()) {

    private val label = JLabel("").apply {
        font = font.deriveFont(Font.PLAIN, font.size2D - 0.5f)
        foreground = LlamatikTheme.onSurfaceVariant
        border = JBUI.Borders.empty(6, 14, 6, 14)
    }

    var text: String
        get() = label.text
        set(value) { label.text = value; repaint() }

    var isAnimating: Boolean = false
        set(value) {
            field = value
            if (value) animTimer.start() else { animTimer.stop(); phase = 0.0; repaint() }
        }

    private var phase = 0.0
    private val animTimer = Timer(40) {
        phase += 0.12
        if (phase > 2 * Math.PI) phase -= 2 * Math.PI
        repaint()
    }

    init {
        isOpaque = false
        add(label, BorderLayout.CENTER)
    }

    override fun getPreferredSize(): Dimension {
        val ps = label.preferredSize
        return Dimension(ps.width, ps.height)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val arc = height.toFloat()
        val shape = RoundRectangle2D.Float(1f, 1f, width - 2f, height - 2f, arc, arc)

        // Background fill
        g2.color = LlamatikTheme.surface
        g2.fill(shape)

        if (isAnimating) {
            // Pulsing border: alpha oscillates between 60 and 255 using a sine wave
            val alpha = (60 + ((sin(phase) + 1.0) / 2.0) * 195).toInt().coerceIn(0, 255)
            val accent = LlamatikTheme.primary
            g2.color = Color(accent.red, accent.green, accent.blue, alpha)
            g2.stroke = BasicStroke(1.6f)
        } else {
            g2.color = LlamatikTheme.outline
            g2.stroke = BasicStroke(1f)
        }
        g2.draw(shape)
        g2.dispose()
    }
}
