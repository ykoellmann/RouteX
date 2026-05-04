package com.sonarwhale.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.sonarwhale.service.RouteIndexService

class SonarwhaleToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        // Panel may have been created eagerly at startup so the run-request listener
        // is active before the user opens the tool window for the first time.
        if (toolWindow.contentManager.contentCount > 0) return

        initContent(project, toolWindow)
    }

    override fun isApplicable(project: Project): Boolean = true

    companion object {
        fun initContent(project: Project, toolWindow: ToolWindow) {
            val service = RouteIndexService.getInstance(project)
            val panel   = SonarwhalePanel(project)
            val content = ContentFactory.getInstance().createContent(panel, "", false)
            toolWindow.contentManager.addContent(content)
            panel.updateEndpoints(service.endpoints)
            service.refresh()
        }
    }
}
