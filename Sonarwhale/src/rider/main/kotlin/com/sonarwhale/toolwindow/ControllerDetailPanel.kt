package com.sonarwhale.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.sonarwhale.SonarwhaleStateService
import com.sonarwhale.model.TagConfig
import com.sonarwhale.script.ScriptLevel
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.JPanel

class ControllerDetailPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val stateService = SonarwhaleStateService.getInstance(project)
    private val nameLabel = JBLabel("").apply {
        font = font.deriveFont(Font.BOLD, 13f)
        border = JBUI.Borders.empty(8, 12)
    }
    private var configPanel: HierarchyConfigPanel? = null

    init {
        add(nameLabel, BorderLayout.NORTH)
    }

    fun showController(tag: String) {
        nameLabel.text = tag

        val panel = HierarchyConfigPanel(
            project = project,
            config = stateService.getTagConfig(tag).config,
            onSave = { updated ->
                stateService.setTagConfig(TagConfig(tag = tag, config = updated))
            },
            scriptContext = ScriptContext(level = ScriptLevel.TAG, tag = tag)
        )
        configPanel?.let { remove(it) }
        configPanel = panel
        add(panel, BorderLayout.CENTER)
        revalidate(); repaint()
    }
}
