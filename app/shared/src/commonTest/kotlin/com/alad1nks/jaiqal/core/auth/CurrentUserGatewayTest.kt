package com.alad1nks.jaiqal.core.auth

import com.alad1nks.jaiqal.core.network.ApiException
import com.alad1nks.jaiqal.core.network.AppEnvironment
import com.alad1nks.jaiqal.core.network.DefaultBackendConfig
import com.alad1nks.jaiqal.core.network.FirebaseAuthenticatedRequestExecutor
import com.alad1nks.jaiqal.core.network.KtorApiClient
import com.alad1nks.jaiqal.core.network.NetworkLogger
import com.alad1nks.jaiqal.core.network.SessionErrorStore
import com.alad1nks.jaiqal.core.network.createApiHttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.io.IOException

class CurrentUserGatewayTest {
    @Test
    fun protectedRequestAddsBearerAndRefreshesExactlyOnceAfter401() = runTest {
        val authorizations = mutableListOf<String?>()
        val engine = MockEngine { request ->
            authorizations += request.headers[HttpHeaders.Authorization]
            if (authorizations.size == 1) {
                respond("", HttpStatusCode.Unauthorized)
            } else {
                userResponse()
            }
        }
        val auth = FakeAuthProvider().apply { refreshedIdToken = "refreshed-token" }
        val gateway = gateway(engine, auth)

        val user = gateway.fetchCurrentUser()

        assertEquals("internal-user-id", user.id)
        assertEquals(listOf<String?>("Bearer fake-id-token", "Bearer refreshed-token"), authorizations)
        assertEquals(1, auth.tokenRequests.count { it })
    }

    @Test
    fun second401StopsRetryAndReportsManagedSessionError() = runTest {
        var requests = 0
        val engine = MockEngine {
            requests += 1
            respond("", HttpStatusCode.Unauthorized)
        }
        val auth = FakeAuthProvider().apply { refreshedIdToken = "refreshed-token" }
        val sessionErrors = SessionErrorStore()
        val gateway = gateway(engine, auth, sessionErrors)

        val failure = runCatching { gateway.fetchCurrentUser() }.exceptionOrNull()

        assertIs<ApiException.SessionExpired>(failure)
        assertEquals(2, requests)
        assertEquals(1, auth.tokenRequests.count { it })
        assertTrue(sessionErrors.requiresSignIn.value)
    }

    @Test
    fun concurrent401ResponsesShareOneForcedRefresh() = runTest {
        val engine = MockEngine { request ->
            if (request.headers[HttpHeaders.Authorization] == "Bearer fake-id-token") {
                respond("", HttpStatusCode.Unauthorized)
            } else {
                userResponse()
            }
        }
        val auth = FakeAuthProvider().apply {
            refreshedIdToken = "refreshed-token"
            forceRefreshDelayMillis = 100
        }
        val gateway = gateway(engine, auth)

        awaitAll(
            async { gateway.fetchCurrentUser() },
            async { gateway.fetchCurrentUser() },
        )

        assertEquals(1, auth.tokenRequests.count { it })
    }

    @Test
    fun backendErrorUsesSharedContractWithoutLeakingServerMessage() = runTest {
        val engine = MockEngine {
            respond(
                content = """{"code":"FORBIDDEN","message":"database password is secret","requestId":"request-7"}""",
                status = HttpStatusCode.Forbidden,
                headers = jsonHeaders,
            )
        }
        val failure = runCatching { gateway(engine, FakeAuthProvider()).fetchCurrentUser() }
            .exceptionOrNull()

        val backend = assertIs<ApiException.Backend>(failure)
        assertEquals("FORBIDDEN", backend.errorCode)
        assertEquals("request-7", backend.requestId)
        assertFalse(backend.message.orEmpty().contains("password"))
        assertFalse(backend.message.orEmpty().contains("secret"))
    }

    @Test
    fun cancellationIsNotMappedToGenericNetworkError() = runTest {
        val cancellation = CancellationException("test cancellation")
        val engine = MockEngine { throw cancellation }

        val failure = runCatching { gateway(engine, FakeAuthProvider()).fetchCurrentUser() }
            .exceptionOrNull()

        assertIs<CancellationException>(failure)
        assertEquals("test cancellation", failure.message)
    }

    @Test
    fun connectivityFailureHasAStableProjectError() = runTest {
        val engine = MockEngine { throw IOException("host unavailable") }

        val failure = runCatching { gateway(engine, FakeAuthProvider()).fetchCurrentUser() }
            .exceptionOrNull()

        assertIs<ApiException.Connectivity>(failure)
        assertEquals("The backend is unreachable", failure.message)
    }

    @Test
    fun debugLoggingNeverContainsFirebaseToken() = runTest {
        val logs = mutableListOf<String>()
        val engine = MockEngine { userResponse() }
        val auth = FakeAuthProvider()
        gateway(
            engine = engine,
            auth = auth,
            clientFactory = {
                createApiHttpClient(
                    engine = engine,
                    enableDebugLogging = true,
                    networkLogger = NetworkLogger(logs::add),
                )
            },
        ).fetchCurrentUser()

        val output = logs.joinToString("\n")
        assertFalse(output.contains("fake-id-token"))
    }

    private fun gateway(
        engine: MockEngine,
        auth: AuthProvider,
        sessionErrors: SessionErrorStore = SessionErrorStore(),
        clientFactory: () -> io.ktor.client.HttpClient = { createApiHttpClient(engine) },
    ): CurrentUserGateway {
        val apiClient = KtorApiClient(
            client = clientFactory(),
            backendConfig = DefaultBackendConfig(AppEnvironment.LOCAL, "http://localhost:8080"),
            authenticatedRequestExecutor = FirebaseAuthenticatedRequestExecutor(auth, sessionErrors),
        )
        return ApiCurrentUserGateway(apiClient)
    }

    private fun MockRequestHandleScope.userResponse() = respond(
        content = """{"id":"internal-user-id","email":"plant@example.com","emailVerified":true}""",
        status = HttpStatusCode.OK,
        headers = jsonHeaders,
    )

    private companion object {
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
