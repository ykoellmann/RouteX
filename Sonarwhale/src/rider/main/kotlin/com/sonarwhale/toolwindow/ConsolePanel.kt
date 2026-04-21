package com.sonarwhale.toolwindow

import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.sonarwhale.script.ConsoleEntry
import com.sonarwhale.script.LogLevel
import com.sonarwhale.script.ScriptPhase
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Font
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.SwingUtilities
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

class ConsolePanel : JPanel(BorderLayout()) {

    private val textPane = javax.swing.JTextPane().apply {
        isEditable = false
        background = JBColor.background()
        border = JBUI.Borders.empty(4)
    }
    private val scroll = JBScrollPane(textPane)
    private val clearButton = JButton("Clear").apply { font = font.deriveFont(10f) }
    private val timeFmt = SimpleDateFormat("HH:mm:ss.SSS")
    private val doc get() = textPane.styledDocument

    init {
        val header = JPanel(BorderLayout(4, 0))
        header.border = JBUI.Borders.compound(
            JBUI.Borders.customLineBottom(JBColor.border()),
            JBUI.Borders.empty(3, 8)
        )
        header.add(JBLabel("Console").apply {
            font = font.deriveFont(Font.BOLD, 11f)
            foreground = JBColor.GRAY
        }, BorderLayout.WEST)
        header.add(clearButton, BorderLayout.EAST)
        add(header, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)

        clearButton.addActionListener { showEntries(emptyList()) }
    }

    fun showEntries(entries: List<ConsoleEntry>) {
        doc.remove(0, doc.length)
        entries.forEach { appendEntry(it) }
        SwingUtilities.invokeLater {
            scroll.verticalScrollBar.let { it.value = it.maximum }
        }
    }

    private fun appendEntry(entry: ConsoleEntry) = when (entry) {
        is ConsoleEntry.ScriptBoundary -> appendBoundary(entry)
        is ConsoleEntry.LogEntry       -> appendLog(entry)
        is ConsoleEntry.ErrorEntry     -> appendError(entry)
        is ConsoleEntry.HttpEntry      -> appendHttp(entry)
    }

    private fun appendBoundary(entry: ConsoleEntry.ScriptBoundary) {
        val phase = if (entry.phase == ScriptPhase.PRE) "pre" else "post"
        val name = entry.scriptPath.substringAfterLast('/').substringAfterLast('\\')
        insert("▶  $name [$phase]\n", fg = JBColor.GRAY, italic = true)
    }

    private fun appendLog(entry: ConsoleEntry.LogEntry) {
        val color = when (entry.level) {
            LogLevel.LOG   -> JBColor.foreground()
            LogLevel.WARN  -> JBColor(Color(0xCC, 0x77, 0x00), Color(0xFF, 0xBB, 0x33))
            LogLevel.ERROR -> JBColor(Color(0xCC, 0x00, 0x00), Color(0xFF, 0x44, 0x44))
        }
        val prefix = when (entry.level) {
            LogLevel.LOG   -> ""
            LogLevel.WARN  -> "⚠  "
            LogLevel.ERROR -> "✕  "
        }
        val time = timeFmt.format(Date(entry.timestampMs))
        insert("$time  $prefix${entry.message}\n", fg = color)
    }

    private fun appendError(entry: ConsoleEntry.ErrorEntry) {
        val name = entry.scriptPath.substringAfterLast('/').substringAfterLast('\\')
        insert(
            "✕  $name: ${entry.message}\n",
            fg = JBColor(Color(0xCC, 0x00, 0x00), Color(0xFF, 0x55, 0x55)),
            bg = JBColor(Color(0xFF, 0xEE, 0xEE), Color(0x55, 0x22, 0x22))
        )
    }

    private fun appendHttp(entry: ConsoleEntry.HttpEntry) {
        val statusColor = when {
            entry.status in 200..299 -> JBColor(Color(0x00, 0xAA, 0x55), Color(0x44, 0xCC, 0x77))
            entry.status == 0        -> JBColor(Color(0xCC, 0x00, 0x00), Color(0xFF, 0x44, 0x44))
            entry.status in 400..499 -> JBColor(Color(0xCC, 0x44, 0x00), Color(0xFF, 0x77, 0x33))
            else                     -> JBColor(Color(0xCC, 0x77, 0x00), Color(0xFF, 0xBB, 0x33))
        }
        val statusText = if (entry.status == 0) "ERROR" else "${entry.status}"
        insert(
            "→  ${entry.method}  ${entry.url}  ·  $statusText  ·  ${entry.durationMs}ms\n",
            fg = statusColor, bold = true
        )

        val indent = "   "
        fun section(title: String, content: String) {
            if (content.isBlank()) return
            insert("$indent$title\n", fg = JBColor.GRAY, bold = true, size = 10)
            insert(content.lines().joinToString("\n") { "$indent$it" } + "\n", fg = JBColor.foreground())
        }

        val reqHeadersText = entry.requestHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        section("REQUEST HEADERS", reqHeadersText)
        if (entry.requestBody != null) section("REQUEST BODY", entry.requestBody)

        if (entry.error != null) {
            section("ERROR", entry.error)
        } else {
            val respHeadersText = entry.responseHeaders.entries.joinToString("\n") { "${it.key}: ${it.value}" }
            section("RESPONSE HEADERS", respHeadersText)
            if (entry.responseBody.isNotBlank()) section("RESPONSE BODY", entry.responseBody.take(2000))
        }
        insert("\n", fg = JBColor.foreground())
    }

    private fun insert(
        text: String,
        fg: Color,
        bg: Color? = null,
        italic: Boolean = false,
        bold: Boolean = false,
        size: Int = 11
    ) {
        val attrs = SimpleAttributeSet()
        StyleConstants.setForeground(attrs, fg)
        StyleConstants.setFontFamily(attrs, Font.MONOSPACED)
        StyleConstants.setFontSize(attrs, size)
        StyleConstants.setItalic(attrs, italic)
        StyleConstants.setBold(attrs, bold)
        if (bg != null) StyleConstants.setBackground(attrs, bg)
        doc.insertString(doc.length, text, attrs)
    }
}
