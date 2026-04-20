package com.sonarwhale

import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.sonarwhale.gutter.SonarwhaleGutterService
import com.sonarwhale.script.SonarwhaleScriptService
import com.sonarwhale.service.RouteIndexService

class SonarwhaleStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        // Eagerly initialize gutter service so it can register its editor listeners
        SonarwhaleGutterService.getInstance(project)
        RouteIndexService.getInstance(project).refresh()
        // Ensure sw.d.ts and jsconfig.json exist so IDE autocomplete works immediately
        SonarwhaleScriptService.getInstance(project).ensureSwDts()
    }
}
