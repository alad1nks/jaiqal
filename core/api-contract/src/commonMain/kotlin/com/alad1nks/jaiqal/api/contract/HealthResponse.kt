package com.alad1nks.jaiqal.api.contract

import kotlinx.serialization.Serializable

@Serializable
data class HealthResponse(
    val status: String,
)
