package com.sonarwhale.model

data class SonarwhaleGeneralSettings(
    val gutterIconsEnabled: Boolean = true,
    val autoRefreshIntervalSeconds: Int = 60,
    val autoFormatResponse: Boolean = true,
    val requestTimeoutSeconds: Int = 30,
    val followRedirects: Boolean = true,
    val verifySsl: Boolean = true,
    val defaultContentType: String = "application/json"
)
