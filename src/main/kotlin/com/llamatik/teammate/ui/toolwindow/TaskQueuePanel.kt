package com.llamatik.teammate.ui.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.JBUI
import com.llamatik.teammate.model.TaskRecord
import com.llamatik.teammate.model.TaskStatus
import com.llamatik.teammate.ui.LlamatikTheme
import com.llamatik.teammate.ui.Strings
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.RoundRectangle2D
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer

class TaskQueuePanel(
    private val onTaskSelected: (TaskRecord) -> Unit,
    private val onTaskDeleted: (TaskRecord) -> Unit,
    private val onTaskRerun: (TaskRecord) -> Unit,
    private val onNewTask: () -> Unit,
) : JPanel(BorderLayout()) {

    private val tasks = mutableListOf<TaskRecord>()
    private var selectedTaskId: String? = null

    // Map from taskId → status label for in-place elapsed tick (avoids full rebuild)
    private val statusLabels = mutableMapOf<String, JBLabel>()

    // Ticks only the elapsed labels of running rows — no removeAll/rebuild
    private val refreshTimer = Timer(1000) { tickElapsed() }.apply { isRepeats = true }

    private val rootBackground = LlamatikTheme.background
    private val cardBackground = LlamatikTheme.surface

    private val listPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = rootBackground
        border = JBUI.Borders.empty(8, 8, 8, 8)
    }

    private val scrollPane = JBScrollPane(JPanel(BorderLayout()).apply {
        background = rootBackground
        add(listPanel, BorderLayout.NORTH)
    }).apply {
        border = JBUI.Borders.empty()
        viewport.background = rootBackground
        background = rootBackground
        horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
        verticalScrollBarPolicy = JBScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
    }

    init {
        background = rootBackground
        border = BorderFactory.createMatteBorder(0, 1, 0, 0, LlamatikTheme.divider)
        preferredSize = Dimension(240, 0)

        val titleLabel = JBLabel(Strings.TaskQueue.PANEL_TITLE).apply {
            foreground = LlamatikTheme.onSurface
            font = font.deriveFont(Font.BOLD, font.size2D)
        }
        val newTaskButton = JButton(AllIcons.General.Add).apply {
            toolTipText = Strings.TaskQueue.NEW_TASK_TOOLTIP
            isFocusPainted = false
            isContentAreaFilled = false
            isBorderPainted = false
            preferredSize = Dimension(24, 24)
            cursor = java.awt.Cursor(java.awt.Cursor.HAND_CURSOR)
            addActionListener { onNewTask() }
        }

        val header = JPanel(BorderLayout()).apply {
            background = rootBackground
            border = JBUI.Borders.compound(
                BorderFactory.createMatteBorder(0, 0, 1, 0, LlamatikTheme.divider),
                JBUI.Borders.empty(10, 12),
            )
            add(titleLabel, BorderLayout.WEST)
            add(newTaskButton, BorderLayout.EAST)
        }

        add(header, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)

        rebuild()
    }

    fun setTasks(newTasks: List<TaskRecord>) {
        tasks.clear()
        tasks.addAll(newTasks)
        SwingUtilities.invokeLater {
            rebuild()
            val hasRunning = tasks.any { it.status == TaskStatus.RUNNING }
            if (hasRunning && !refreshTimer.isRunning) refreshTimer.start()
            else if (!hasRunning && refreshTimer.isRunning) refreshTimer.stop()
        }
    }

    fun setSelectedTask(taskId: String?) {
        selectedTaskId = taskId
        SwingUtilities.invokeLater { rebuild() }
    }

    private fun tickElapsed() {
        tasks.filter { it.status == TaskStatus.RUNNING }.forEach { record ->
            statusLabels[record.id]?.let { label ->
                val elapsed = formatElapsed(System.currentTimeMillis() - record.startedAtMs)
                label.text = "<html><span style='color:${LlamatikTheme.htmlOnSurfaceVariant};'>$elapsed</span></html>"
            }
        }
    }

    private fun rebuild() {
        statusLabels.clear()
        listPanel.removeAll()

        val running = tasks.filter { it.status == TaskStatus.RUNNING }
        val finished = tasks.filter { it.status != TaskStatus.RUNNING }

        if (running.isNotEmpty()) {
            addSectionHeader(Strings.TaskQueue.SECTION_RUNNING)
            running.forEach { addTaskRow(it) }
        }

        if (finished.isNotEmpty()) {
            addSectionHeader(Strings.TaskQueue.SECTION_DONE)
            finished.reversed().forEach { addTaskRow(it) }
        }

        if (tasks.isEmpty()) {
            listPanel.add(JBLabel(Strings.TaskQueue.EMPTY_HINT).apply {
                foreground = LlamatikTheme.onSurfaceVariant
                font = font.deriveFont(Font.ITALIC, font.size2D - 1f)
                horizontalAlignment = SwingConstants.CENTER
                border = JBUI.Borders.empty(24, 12)
            })
        }

        listPanel.revalidate()
        listPanel.repaint()
    }

    private fun addSectionHeader(title: String) {
        listPanel.add(JBLabel(title).apply {
            foreground = LlamatikTheme.onSurfaceVariant
            font = font.deriveFont(Font.PLAIN, font.size2D - 1.5f)
            border = JBUI.Borders.empty(10, 4, 4, 4)
        })
    }

    private fun addTaskRow(record: TaskRecord) {
        val isSelected = record.id == selectedTaskId
        val rowBg = if (isSelected) LlamatikTheme.surfaceVariant else cardBackground
        val borderColor = if (isSelected) LlamatikTheme.primary else LlamatikTheme.outline

        val row = object : JPanel(BorderLayout(6, 0)) {
            private val arc = 10f
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = rowBg
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc))
                if (isSelected) {
                    g2.color = borderColor
                    g2.draw(RoundRectangle2D.Float(0.5f, 0.5f, width - 1f, height - 1f, arc, arc))
                }
                g2.dispose()
            }
        }.apply {
            isOpaque = false
            border = JBUI.Borders.compound(
                BorderFactory.createEmptyBorder(2, 0, 2, 0),
                JBUI.Borders.empty(8, 10),
            )
            maximumSize = Dimension(Int.MAX_VALUE, 72)
            cursor = java.awt.Cursor(java.awt.Cursor.HAND_CURSOR)
        }

        when (record.status) {
            TaskStatus.RUNNING -> {
                val spinner = AsyncProcessIcon("task-${record.id}").apply {
                    resume()
                    preferredSize = Dimension(16, 16)
                    minimumSize = Dimension(16, 16)
                    maximumSize = Dimension(16, 16)
                }
                row.add(JPanel(BorderLayout()).apply {
                    isOpaque = false
                    border = JBUI.Borders.emptyTop(2)
                    add(spinner, BorderLayout.NORTH)
                    preferredSize = Dimension(20, 20)
                    minimumSize = Dimension(20, 20)
                    maximumSize = Dimension(20, 20)
                }, BorderLayout.WEST)
            }
            else -> {
                val iconLabel = when (record.status) {
                    TaskStatus.DONE -> JLabel("✓").apply { foreground = LlamatikTheme.success }
                    TaskStatus.HALTED, TaskStatus.ERROR -> JLabel("✕").apply { foreground = LlamatikTheme.error }
                    TaskStatus.RUNNING -> JLabel()
                }.apply { font = font.deriveFont(Font.BOLD, font.size2D) }
                row.add(JPanel(BorderLayout()).apply {
                    isOpaque = false
                    border = JBUI.Borders.emptyTop(2)
                    add(iconLabel, BorderLayout.NORTH)
                    preferredSize = Dimension(20, 20)
                    minimumSize = Dimension(20, 20)
                    maximumSize = Dimension(20, 20)
                }, BorderLayout.WEST)
            }
        }

        val promptText = record.prompt.let {
            if (it.length > 46) it.take(43) + "…" else it
        }
        val statusText = when (record.status) {
            TaskStatus.RUNNING -> {
                val elapsed = formatElapsed(System.currentTimeMillis() - record.startedAtMs)
                elapsed
            }
            TaskStatus.DONE -> {
                val tokens = if (record.tokenCount > 0) " · ${formatTokens(record.tokenCount)}" else ""
                if (record.changeCount > 0)
                    Strings.TaskQueue.statusDoneChanges(record.changeCount) + tokens
                else
                    Strings.TaskQueue.STATUS_DONE + tokens
            }
            TaskStatus.HALTED -> {
                val tokens = if (record.tokenCount > 0) " · ${formatTokens(record.tokenCount)}" else ""
                Strings.TaskQueue.STATUS_HALTED + tokens
            }
            TaskStatus.ERROR -> {
                val tokens = if (record.tokenCount > 0) " · ${formatTokens(record.tokenCount)}" else ""
                Strings.TaskQueue.STATUS_ERROR + tokens
            }
        }

        val statusLabel = JBLabel("<html><span style='color:${LlamatikTheme.htmlOnSurfaceVariant};'>$statusText</span></html>").apply {
            font = font.deriveFont(Font.PLAIN, font.size2D - 1.5f)
            border = JBUI.Borders.emptyTop(2)
        }
        if (record.status == TaskStatus.RUNNING) statusLabels[record.id] = statusLabel

        val textPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            add(JBLabel("<html><span style='color:${LlamatikTheme.htmlOnSurface};'>$promptText</span></html>").apply {
                font = font.deriveFont(Font.PLAIN, font.size2D)
            })
            add(statusLabel)
        }

        val deleteBtn = JButton(AllIcons.Actions.GC).apply {
            toolTipText = Strings.TaskQueue.DELETE_TOOLTIP
            isFocusPainted = false
            isContentAreaFilled = false
            isBorderPainted = false
            preferredSize = Dimension(22, 22)
            cursor = java.awt.Cursor(java.awt.Cursor.HAND_CURSOR)
            addActionListener { onTaskDeleted(record) }
        }
        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 2, 0)).apply {
            isOpaque = false
            if (record.status != TaskStatus.RUNNING) {
                val rerunBtn = JButton(AllIcons.Actions.Execute).apply {
                    toolTipText = Strings.TaskQueue.RERUN_TOOLTIP
                    isFocusPainted = false
                    isContentAreaFilled = false
                    isBorderPainted = false
                    preferredSize = Dimension(22, 22)
                    cursor = java.awt.Cursor(java.awt.Cursor.HAND_CURSOR)
                    addActionListener { onTaskRerun(record) }
                }
                add(rerunBtn)
            }
            add(deleteBtn)
        }

        row.add(textPanel, BorderLayout.CENTER)
        row.add(rightPanel, BorderLayout.EAST)

        row.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                onTaskSelected(record)
            }
        })
        textPanel.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                onTaskSelected(record)
            }
        })

        listPanel.add(row)
        listPanel.add(javax.swing.Box.createVerticalStrut(4))
    }

    private fun formatElapsed(ms: Long): String {
        val secs = ms / 1000
        val mins = secs / 60
        return if (mins > 0) "${mins}m ${secs % 60}s" else "${secs}s"
    }

    private fun formatTokens(count: Int): String = when {
        count >= 1_000 -> "${count / 1_000}.${(count % 1_000) / 100}k tokens"
        else -> "$count tokens"
    }
}
