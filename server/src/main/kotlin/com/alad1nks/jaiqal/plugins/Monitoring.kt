package com.alad1nks.jaiqal.plugins

import io.ktor.http.HttpHeaders
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.callid.CallId
import io.ktor.server.plugins.callid.callId
import io.ktor.server.plugins.calllogging.CallLogging
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import org.slf4j.event.Level
import java.util.UUID

fun Application.configureMonitoring() {
    install(CallId) {
        retrieveFromHeader(HttpHeaders.XRequestId)
        verify { requestId ->
            requestId.length in 1..128 &&
                requestId.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }
        }
        generate { UUID.randomUUID().toString() }
        replyToHeader(HttpHeaders.XRequestId)
    }

    install(CallLogging) {
        level = Level.INFO
        mdc("requestId") { call -> call.callId }
        format { call ->
            val status = call.response.status()?.value ?: 0
            "${call.request.httpMethod.value} ${call.request.path()} status=$status requestId=${call.callId}"
        }
    }
}
