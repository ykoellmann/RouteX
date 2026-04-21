package com.sonarwhale.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import com.sonarwhale.SonarwhaleStateService
import com.sonarwhale.model.Environment
import java.awt.BorderLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import javax.swing.DefaultListModel
import javax.swing.JComponent
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableModel

class SonarwhaleEnvironmentsConfigurable(private val project: Project) : Configurable {

    private val stateService: SonarwhaleStateService get() = SonarwhaleStateService.getInstance(project)

    private var envs: MutableList<Environment> = mutableListOf()
    private var modified    = false
    private var isInitializing = true

    private val envListModel = DefaultListModel<String>()
    private val envList = JBList(envListModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }

    private val baseUrlField = JTextField()

    private val varTableModel = object : DefaultTableModel(arrayOf("Variable", "Value"), 0) {
        override fun isCellEditable(row: Int, col: Int) = true
    }
    private val varTable = JBTable(varTableModel).apply {
        putClientProperty("terminateEditOnFocusLost", true)
        tableHeader.reorderingAllowed = false
    }

    private var selectedEnvIdx = -1

    override fun getDisplayName() = "Environments"

    override fun createComponent(): JComponent {
        reset()

        baseUrlField.document.addDocumentListener(object : DocumentListener {
            override fun insertUpdate(e: DocumentEvent)  { if (!isInitializing) modified = true }
            override fun removeUpdate(e: DocumentEvent)  { if (!isInitializing) modified = true }
            override fun changedUpdate(e: DocumentEvent) { if (!isInitializing) modified = true }
        })

        envList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                if (!isInitializing) saveCurrentVarsToEnv()
                selectedEnvIdx = envList.selectedIndex
                loadEnvVars()
            }
        }

        val root = JPanel(BorderLayout(8, 0))
        root.add(buildEnvListPanel(), BorderLayout.WEST)
        root.add(buildVarPanel(), BorderLayout.CENTER)
        return root
    }

    override fun isModified() = modified

    override fun apply() {
        saveCurrentVarsToEnv()
        val newIds = envs.map { it.id }.toSet()
        stateService.getEnvironments().filter { it.id !in newIds }
            .forEach { stateService.removeEnvironment(it.id) }
        envs.forEach { stateService.upsertEnvironment(it) }
        val activeId = stateService.getActiveEnvironment()?.id
        if (activeId != null && activeId !in newIds) stateService.setActiveEnvironment("")
        modified = false
    }

    override fun reset() {
        isInitializing = true
        envs = stateService.getEnvironments()
            .map { it.copy(variables = LinkedHashMap(it.variables)) }
            .toMutableList()
        envListModel.clear()
        envs.forEach { envListModel.addElement(it.name) }
        selectedEnvIdx = -1
        if (envs.isNotEmpty()) {
            selectedEnvIdx = 0
            envList.selectedIndex = 0
        }
        loadEnvVars()
        modified = false
        isInitializing = false
    }

    private fun buildEnvListPanel(): JPanel {
        val decorator = ToolbarDecorator.createDecorator(envList)
            .setAddAction {
                val name = JOptionPane.showInputDialog(
                    envList, "Environment name:", "New Environment", JOptionPane.PLAIN_MESSAGE
                )?.trim() ?: return@setAddAction
                if (name.isEmpty()) return@setAddAction
                if (!isInitializing) saveCurrentVarsToEnv()
                val env = Environment(name = name)
                envs.add(env); envListModel.addElement(env.name)
                envList.selectedIndex = envs.size - 1
                modified = true
            }
            .setRemoveAction {
                val idx = envList.selectedIndex.takeIf { it >= 0 } ?: return@setRemoveAction
                envs.removeAt(idx); envListModel.removeElementAt(idx)
                selectedEnvIdx = -1; clearVarTable()
                if (envs.isNotEmpty()) {
                    val newIdx = (idx - 1).coerceAtLeast(0)
                    envList.selectedIndex = newIdx
                }
                modified = true
            }
            .disableUpDownActions()

        val panel = JPanel(BorderLayout(0, 4))
        panel.border = JBUI.Borders.empty(0, 0, 0, 4)
        panel.add(sectionLabel("Environments"), BorderLayout.NORTH)
        panel.add(decorator.createPanel(), BorderLayout.CENTER)
        panel.preferredSize = JBUI.size(180, 300)
        return panel
    }

    private fun buildVarPanel(): JPanel {
        // Pinned Base URL row
        val baseUrlRow = JPanel(GridBagLayout())
        baseUrlRow.border = JBUI.Borders.emptyBottom(8)
        val gbc = GridBagConstraints().also {
            it.fill = GridBagConstraints.HORIZONTAL; it.anchor = GridBagConstraints.WEST
        }
        gbc.gridx = 0; gbc.weightx = 0.0; gbc.insets = Insets(0, 0, 0, 6)
        baseUrlRow.add(JBLabel("Base URL").apply { font = font.deriveFont(Font.PLAIN, 11f) }, gbc)
        gbc.gridx = 1; gbc.weightx = 1.0; gbc.insets = Insets(0, 0, 0, 0)
        baseUrlRow.add(baseUrlField, gbc)

        val decorator = ToolbarDecorator.createDecorator(varTable)
            .setAddAction {
                varTableModel.addRow(arrayOf("", ""))
                val lastRow = varTableModel.rowCount - 1
                varTable.editCellAt(lastRow, 0)
                varTable.changeSelection(lastRow, 0, false, false)
                modified = true
            }
            .setRemoveAction {
                val row = varTable.selectedRow.takeIf { it >= 0 } ?: return@setRemoveAction
                if (varTable.isEditing) varTable.cellEditor?.stopCellEditing()
                varTableModel.removeRow(row)
                modified = true
            }
            .disableUpDownActions()

        val inner = JPanel(BorderLayout(0, 8))
        inner.add(baseUrlRow, BorderLayout.NORTH)
        inner.add(decorator.createPanel(), BorderLayout.CENTER)

        val panel = JPanel(BorderLayout(0, 4))
        panel.add(sectionLabel("Variables  (use {{variableName}} in URLs, headers, and body)"), BorderLayout.NORTH)
        panel.add(inner, BorderLayout.CENTER)
        return panel
    }

    private fun loadEnvVars() {
        clearVarTable()
        val env = envs.getOrNull(selectedEnvIdx) ?: return
        baseUrlField.text = env.variables["baseUrl"] ?: ""
        env.variables.entries
            .filter { it.key != "baseUrl" }
            .forEach { (k, v) -> varTableModel.addRow(arrayOf(k, v)) }
    }

    private fun clearVarTable() {
        if (varTable.isEditing) varTable.cellEditor?.stopCellEditing()
        varTableModel.rowCount = 0
        baseUrlField.text = ""
    }

    private fun saveCurrentVarsToEnv() {
        val env = envs.getOrNull(selectedEnvIdx) ?: return
        if (varTable.isEditing) varTable.cellEditor?.stopCellEditing()
        val vars = LinkedHashMap<String, String>()
        val base = baseUrlField.text.trim()
        if (base.isNotEmpty()) vars["baseUrl"] = base
        for (row in 0 until varTableModel.rowCount) {
            val key   = (varTableModel.getValueAt(row, 0) as? String)?.trim() ?: continue
            val value = (varTableModel.getValueAt(row, 1) as? String) ?: ""
            if (key.isNotEmpty()) vars[key] = value
        }
        envs[selectedEnvIdx] = env.copy(variables = vars)
    }

    private fun sectionLabel(text: String) = JBLabel(text).apply {
        foreground = JBColor.GRAY; font = font.deriveFont(Font.PLAIN, 11f)
        border = JBUI.Borders.emptyBottom(2)
    }
}
