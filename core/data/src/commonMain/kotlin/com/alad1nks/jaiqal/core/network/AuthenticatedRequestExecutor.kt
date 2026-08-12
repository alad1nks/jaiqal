package com.alad1nks.jaiqal.core.network

import com.alad1nks.jaiqal.core.auth.AuthProvider
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

interface AuthenticatedRequestExecutor {
    suspend fun execute(request: suspend (idToken: String) -> HttpResponse): HttpResponse
}

class FirebaseAuthenticatedRequestExecutor(
    private val authProvider: AuthProvider,
    private val sessionErrorStore: SessionErrorStore,
) : AuthenticatedRequestExecutor {
    private val refreshMutex = Mutex()

    override suspend fun execute(request: suspend (idToken: String) -> HttpResponse): HttpResponse {
        val initialToken = authProvider.getIdToken(forceRefresh = false)
            ?: throw expiredSession()
        val initialResponse = request(initialToken)
        if (initialResponse.status != HttpStatusCode.Unauthorized) return initialResponse

        initialResponse.bodyAsText() // Consume the response before reusing the connection.
        val refreshedToken = refreshMutex.withLock {
            val latestToken = authProvider.getIdToken(forceRefresh = false)
            if (latestToken != null && latestToken != initialToken) {
                latestToken
            } else {
                authProvider.getIdToken(forceRefresh = true)
            }
        } ?: throw expiredSession()

        val retryResponse = request(refreshedToken)
        if (retryResponse.status == HttpStatusCode.Unauthorized) {
            retryResponse.bodyAsText()
            throw expiredSession()
        }
        return retryResponse
    }

    private fun expiredSession(): ApiException.SessionExpired {
        sessionErrorStore.reportExpiredSession()
        return ApiException.SessionExpired()
    }
}
