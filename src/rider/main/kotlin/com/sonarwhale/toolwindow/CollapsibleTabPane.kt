package com.sonarwhale.toolwindow

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.CardLayout
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Drop-in replacement for JBTabbedPane that supports collapsing the content area
 * by clicking the currently selected tab.
 *
 * - First tab added is auto-selected (matches JBTabbedPane default behaviour).
 * - Clicking the selected tab collapses the content panel; clicking it again expands.
 * - Clicking a different tab switches to it (and expands if collapsed).
 */
class CollapsibleTabPane : JPanel(java.awt.BorderLayout()) {

    private data class Entry(val name: String, val component: JComponent, val button: JButton)

    private val entries = mutableListOf<Entry>()
    private val cardLayout = CardLayout()
    private val contentPanel = JPanel(cardLayout)
    private val buttonBar = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))

    private var selectedIdx = -1
    private var expanded = false

    init {
        buttonBar.border = JBUI.Borders.customLineBottom(JBColor.border())
        add(buttonBar, java.awt.BorderLayout.NORTH)
        contentPanel.isVisible = false
        add(contentPanel, java.awt.BorderLayout.CENTER)
    }

    fun addTab(name: String, component: JComponent) {
        val idx = entries.size
        val btn = JButton(name).apply {
            isFocusable = false
            isContentAreaFilled = false
            isBorderPainted = false
            border = JBUI.Borders.empty(5, 10)
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = JBColor.GRAY
        }
        val entry = Entry(name, component, btn)
        entries.add(entry)
        contentPanel.add(component, name)
        btn.addActionListener { onTabClicked(idx) }
        buttonBar.add(btn)

        // Auto-select first tab, matching JBTabbedPane behaviour
        if (entries.size == 1) {
            selectedIdx = 0
            expanded = true
            cardLayout.show(contentPanel, name)
            contentPanel.isVisible = true
            updateButtonStyles()
        }
    }

    /** Fired after each user tab click. [name] is the tab name, [expanded] is whether content is visible. */
    var onTabChanged: ((name: String, expanded: Boolean) -> Unit)? = null

    private fun onTabClicked(idx: Int) {
        if (idx == selectedIdx && expanded) {
            expanded = false
            contentPanel.isVisible = false
        } else {
            selectedIdx = idx
            expanded = true
            cardLayout.show(contentPanel, entries[idx].name)
            contentPanel.isVisible = true
        }
        updateButtonStyles()
        revalidate(); repaint()
        onTabChanged?.invoke(entries[selectedIdx].name, expanded)
    }

    /** Restores tab state without firing [onTabChanged]. Used to apply remembered state on content switch. */
    fun restoreState(name: String?, isExpanded: Boolean) {
        if (name == null || !isExpanded) {
            expanded = false
            contentPanel.isVisible = false
            updateButtonStyles()
        } else {
            val idx = entries.indexOfFirst { it.name == name }
            if (idx >= 0) {
                selectedIdx = idx
                expanded = true
                cardLayout.show(contentPanel, name)
                contentPanel.isVisible = true
                updateButtonStyles()
            }
        }
        revalidate(); repaint()
    }

    private fun updateButtonStyles() {
        entries.forEachIndexed { i, e ->
            val active = i == selectedIdx && expanded
            e.button.font = e.button.font.deriveFont(if (active) Font.BOLD else Font.PLAIN)
            e.button.foreground = if (active) JBColor.foreground() else JBColor.GRAY
        }
    }

    // ── JBTabbedPane-compatible API ────────────────────────────────────────────

    var selectedIndex: Int
        get() = if (expanded) selectedIdx else -1
        set(value) {
            if (value !in entries.indices) return
            selectedIdx = value
            expanded = true
            cardLayout.show(contentPanel, entries[value].name)
            contentPanel.isVisible = true
            updateButtonStyles()
            revalidate(); repaint()
        }

    fun indexOfComponent(component: JComponent): Int =
        entries.indexOfFirst { it.component == component }

    fun setTitleAt(idx: Int, title: String) {
        if (idx in entries.indices) entries[idx].button.text = title
    }
}
