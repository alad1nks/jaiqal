package com.alad1nks.jaiqal.core.network

import com.alad1nks.jaiqal.api.contract.HealthResponse
import com.alad1nks.jaiqal.core.auth.AuthProvider
import com.alad1nks.jaiqal.core.auth.AuthState
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AuthenticatedRequestExecutorTest {
    @Test
    fun retriesOnceWithAForcedFirebaseTokenAfterUnauthorized() = runTest {
        val auth = FakeAuthProvider()
        val seenHeaders = mutableListOf<String?>()
        val executor = executor(auth) { request ->
            seenHeaders += request.headers[HttpHeaders.Authorization]
            if (seenHeaders.size == 1) unauthorized() else ok()
        }

        assertEquals("ok", executor.execute<HealthResponse> { url("/protected") }.status)
        assertEquals(listOf<String?>("Bearer stale", "Bearer fresh"), seenHeaders)
        assertEquals(1, auth.forcedRefreshes)
    }

    @Test
    fun doesNotEnterASecondRetryLoop() = runTest {
        val auth = FakeAuthProvider()
        var requests = 0
        val executor = executor(auth) {
            requests++
            unauthorized()
        }

        assertFailsWith<BackendException> {
            executor.execute<HealthResponse> { url("/protected") }
        }
        assertEquals(2, requests)
        assertEquals(1, auth.forcedRefreshes)
    }

    @Test
    fun concurrentUnauthorizedResponsesShareOneForcedRefresh() = runTest {
        val auth = FakeAuthProvider(refreshDelayMillis = 50)
        val executor = executor(auth) { request ->
            if (request.headers[HttpHeaders.Authorization] == "Bearer stale") unauthorized() else ok()
        }

        val results = coroutineScope {
            List(8) { async { executor.execute<HealthResponse> { url("/protected") }.status } }.awaitAll()
        }

        assertEquals(List(8) { "ok" }, results)
        assertEquals(1, auth.forcedRefreshes)
    }

    private fun executor(
        auth: AuthProvider,
        handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> HttpResponseData,
    ): AuthenticatedRequestExecutor {
        val client = HttpClient(MockEngine { request -> handler(request) }) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return AuthenticatedRequestExecutor(client, auth, BackendConfig("https://example.test"))
    }

    private fun MockRequestHandleScope.ok() = respond(
        content = "{\"status\":\"ok\"}",
        status = HttpStatusCode.OK,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private fun MockRequestHandleScope.unauthorized() = respond(
        content = "{\"code\":\"UNAUTHORIZED\",\"message\":\"Unauthorized\"}",
        status = HttpStatusCode.Unauthorized,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
    )

    private class FakeAuthProvider(private val refreshDelayMillis: Long = 0) : AuthProvider {
        override val authState = MutableStateFlow<AuthState>(AuthState.Authenticated("user@example.test", true))
        private var token = "stale"
        var forcedRefreshes = 0
            private set

        override suspend fun getIdToken(forceRefresh: Boolean): String {
            if (forceRefresh) {
                forcedRefreshes++
                delay(refreshDelayMillis)
                token = "fresh"
            }
            return token
        }

        override suspend fun signUp(email: String, password: String) = Unit
        override suspend fun signIn(email: String, password: String) = Unit
        override suspend fun sendPasswordReset(email: String) = Unit
        override suspend fun sendEmailVerification() = Unit
        override suspend fun reloadUser() = Unit
        override suspend fun signOut() = Unit
    }
}
