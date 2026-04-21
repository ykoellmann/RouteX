package com.sonarwhale.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project

class SonarwhaleConfigurable(private val project: Project) : Configurable {

    override fun getDisplayName() = "Sonarwhale"

    override fun createComponent() = null

    override fun isModified() = false
    override fun apply() {}
    override fun reset() {}
}
