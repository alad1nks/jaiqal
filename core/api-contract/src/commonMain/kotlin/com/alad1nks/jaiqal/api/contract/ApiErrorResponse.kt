package com.alad1nks.jaiqal.api.contract

import kotlinx.serialization.Serializable

@Serializable
data class ApiErrorResponse(
    val code: String,
    val message: String,
    val requestId: String? = null,
)
