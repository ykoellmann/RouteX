package com.sonarwhale.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.sonarwhale.model.ApiCollection
import com.sonarwhale.model.CollectionEnvironment
import com.sonarwhale.model.EnvironmentSource
import com.sonarwhale.script.ScriptLevel
import com.sonarwhale.service.CollectionService
import java.awt.*
import javax.swing.*

class CollectionDetailPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val collectionService = CollectionService.getInstance(project)
    private var collection: ApiCollection? = null

    private val nameLabel = JBLabel("").apply {
        font = font.deriveFont(Font.BOLD, 13f)
        border = JBUI.Borders.empty(8, 12, 4, 12)
    }

    private val tabs = CollapsibleTabPane()
    private val envsPanel = EnvironmentsListPanel()

    // Wrapper panel used as a stable tab component; its content is replaced on each showCollection call
    private val configTabWrapper = JPanel(BorderLayout())
    private var configPanel: HierarchyConfigPanel? = null

    init {
        add(nameLabel, BorderLayout.NORTH)
        tabs.addTab("Environments", JBScrollPane(envsPanel))
        tabs.addTab("Variables / Auth / Scripts", configTabWrapper)
        add(tabs, BorderLayout.CENTER)
    }

    fun showCollection(col: ApiCollection) {
        collection = col
        nameLabel.text = col.name

        envsPanel.setCollection(col,
            onAddEnv = { env ->
                val updated = col.copy(environments = col.environments + env)
                collectionService.update(updated)
                showCollection(collectionService.getById(col.id) ?: updated)
            },
            onRemoveEnv = { envId ->
                val updated = col.copy(environments = col.environments.filter { it.id != envId })
                collectionService.update(updated)
                showCollection(collectionService.getById(col.id) ?: updated)
            },
            onSetActive = { envId ->
                collectionService.setActiveEnvironment(col.id, envId)
                showCollection(collectionService.getById(col.id) ?: col)
            }
        )

        val newPanel = HierarchyConfigPanel(
            project = project,
            config = col.config,
            onSave = { updated ->
                collectionService.updateConfig(col.id, updated)
            },
            scriptContext = ScriptContext(level = ScriptLevel.COLLECTION, collectionId = col.id)
        )
        configTabWrapper.removeAll()
        configTabWrapper.add(newPanel, BorderLayout.CENTER)
        configPanel = newPanel
        revalidate(); repaint()
    }
}

private class EnvironmentsListPanel : JPanel(BorderLayout()) {
    private val listModel = DefaultListModel<CollectionEnvironment>()
    private val list = JList(listModel)
    private var onAdd: ((CollectionEnvironment) -> Unit)? = null
    private var onRemove: ((String) -> Unit)? = null
    private var onSetActive: ((String) -> Unit)? = null
    private var activeId: String? = null

    init {
        list.cellRenderer = ListCellRenderer { _, value, _, selected, _ ->
            JBLabel("${if (value.id == activeId) "● " else "  "}${value.name}  " +
                    sourceLabel(value.source)).also {
                if (selected) { it.isOpaque = true; it.background = list.selectionBackground }
            }
        }

        val addBtn = JButton(com.intellij.icons.AllIcons.General.Add).apply {
            isBorderPainted = false; isContentAreaFilled = false
            addActionListener { showAddDialog() }
        }
        val removeBtn = JButton(com.intellij.icons.AllIcons.General.Remove).apply {
            isBorderPainted = false; isContentAreaFilled = false
            addActionListener {
                val env = list.selectedValue ?: return@addActionListener
                onRemove?.invoke(env.id)
            }
        }
        val setActiveBtn = JButton("Set Active").apply {
            addActionListener {
                val env = list.selectedValue ?: return@addActionListener
                onSetActive?.invoke(env.id)
            }
        }

        val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0))
        toolbar.add(addBtn); toolbar.add(removeBtn); toolbar.add(setActiveBtn)
        toolbar.border = JBUI.Borders.customLineBottom(JBColor.border())

        add(toolbar, BorderLayout.NORTH)
        add(JBScrollPane(list), BorderLayout.CENTER)
    }

    fun setCollection(
        col: ApiCollection,
        onAddEnv: (CollectionEnvironment) -> Unit,
        onRemoveEnv: (String) -> Unit,
        onSetActive: (String) -> Unit
    ) {
        activeId = col.activeEnvironmentId
        onAdd = onAddEnv; onRemove = onRemoveEnv; this.onSetActive = onSetActive
        listModel.clear()
        col.environments.forEach { listModel.addElement(it) }
        list.repaint()
    }

    private fun showAddDialog() {
        val nameField = JTextField(10)
        val hostField = JTextField("http://localhost", 15)
        val portField = JTextField("5000", 6)
        val panel = JPanel(GridLayout(0, 2, 4, 4))
        panel.add(JLabel("Name:")); panel.add(nameField)
        panel.add(JLabel("Host:")); panel.add(hostField)
        panel.add(JLabel("Port:")); panel.add(portField)
        val result = JOptionPane.showConfirmDialog(
            this, panel, "Add Environment",
            JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE
        )
        if (result != JOptionPane.OK_OPTION) return
        val name = nameField.text.trim().ifBlank { return }
        val port = portField.text.trim().toIntOrNull() ?: return
        val env = CollectionEnvironment(
            name = name,
            source = EnvironmentSource.ServerUrl(host = hostField.text.trim(), port = port)
        )
        onAdd?.invoke(env)
    }

    private fun sourceLabel(s: EnvironmentSource) = when (s) {
        is EnvironmentSource.ServerUrl -> "${s.host}:${s.port}"
        is EnvironmentSource.FilePath -> s.path
        is EnvironmentSource.StaticImport -> "(static)"
    }
}
