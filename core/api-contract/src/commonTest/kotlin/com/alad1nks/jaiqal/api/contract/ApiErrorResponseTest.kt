package com.alad1nks.jaiqal.api.contract

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ApiErrorResponseTest {
    @Test
    fun roundTripsThroughJson() {
        val expected = ApiErrorResponse(
            code = "INVALID_REQUEST",
            message = "The request is invalid",
            requestId = "request-123",
        )

        val encoded = Json.encodeToString(expected)

        assertEquals(expected, Json.decodeFromString<ApiErrorResponse>(encoded))
    }
}
