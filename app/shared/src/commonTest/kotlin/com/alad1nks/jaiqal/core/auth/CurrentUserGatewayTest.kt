package com.alad1nks.jaiqal.core.auth

import com.alad1nks.jaiqal.core.network.AppEnvironment
import com.alad1nks.jaiqal.core.network.DefaultBackendConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class CurrentUserGatewayTest {
    @Test
    fun callsActualMeEndpointWithFirebaseBearerToken() = runTest {
        var path: String? = null
        var authorization: String? = null
        val engine = MockEngine { request ->
            path = request.url.encodedPath
            authorization = request.headers[HttpHeaders.Authorization]
            respond(
                content = """{"id":"internal-user-id","email":"plant@example.com","emailVerified":true}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val gateway = KtorCurrentUserGateway(
            client = createAuthHttpClient(engine),
            backendConfig = DefaultBackendConfig(AppEnvironment.LOCAL, "http://localhost:8080"),
        )

        val user = gateway.fetchCurrentUser("firebase-id-token")

        assertEquals("/api/v1/auth/me", path)
        assertEquals("Bearer firebase-id-token", authorization)
        assertEquals("internal-user-id", user.id)
    }
}
