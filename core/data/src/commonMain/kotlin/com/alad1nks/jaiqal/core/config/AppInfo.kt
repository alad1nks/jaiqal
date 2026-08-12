package com.alad1nks.jaiqal.core.config

data class AppInfo(
    val version: String,
    val platform: String,
    val isDebug: Boolean,
    val privacyPolicyUrl: String?,
)
