package com.alad1nks.jaiqal

import com.alad1nks.jaiqal.api.contract.ApiErrorResponse
import com.alad1nks.jaiqal.auth.FakeFirebaseTokenVerifier
import com.alad1nks.jaiqal.auth.FirebaseTokenVerificationException
import com.alad1nks.jaiqal.auth.UserPrincipal
import com.alad1nks.jaiqal.auth.VerifiedFirebaseToken
import com.alad1nks.jaiqal.config.AppConfig
import com.alad1nks.jaiqal.config.DatabaseConfig
import com.alad1nks.jaiqal.config.FirebaseConfig
import com.alad1nks.jaiqal.plugins.FIREBASE_USER_AUTH
import com.alad1nks.jaiqal.users.FirebaseIdentityConflictException
import com.alad1nks.jaiqal.users.FirebaseUserIdentityService
import com.alad1nks.jaiqal.users.UserIdentityRecord
import com.alad1nks.jaiqal.users.UserIdentityStore
import com.alad1nks.jaiqal.users.UserRecord
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import org.junit.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirebaseAuthenticationTest {
    @Test
    fun `valid Firebase token creates principal with internal UUID`() = testApplication {
        val verifier = FakeFirebaseTokenVerifier(
            mapOf("valid-token" to Result.success(VerifiedFirebaseToken("firebase-uid", "user@example.test", true))),
        )
        val store = MemoryIdentityStore()
        application { firebaseTestApplication(verifier, FirebaseUserIdentityService(store, true)) }

        val response = client.get("/testing/firebase") {
            header(HttpHeaders.Authorization, "Bearer valid-token")
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val fields = response.bodyAsText().split('|')
        assertEquals(store.users.single().id.toString(), fields[0])
        assertEquals("firebase-uid", fields[1])
        assertEquals("user@example.test", fields[2])
        assertEquals("true", fields[3])
        assertEquals(listOf("valid-token"), verifier.verifiedTokens)
    }

    @Test
    fun `missing wrong-scheme and empty credentials return neutral 401 without verification`() = testApplication {
        val verifier = FakeFirebaseTokenVerifier(emptyMap())
        application { firebaseTestApplication(verifier, FirebaseUserIdentityService(MemoryIdentityStore(), true)) }

        val responses = listOf(
            client.get("/testing/firebase"),
            client.get("/testing/firebase") { header(HttpHeaders.Authorization, "Basic credentials") },
            client.get("/testing/firebase") { header(HttpHeaders.Authorization, "Bearer ") },
            client.get("/testing/firebase") { header(HttpHeaders.Authorization, "Device token") },
        )

        responses.forEach { response ->
            assertNeutralUnauthorized(response.status, response.bodyAsText())
        }
        assertTrue(verifier.verifiedTokens.isEmpty())
    }

    @Test
    fun `Firebase validation failures return the same neutral 401`() = testApplication {
        val failedTokens = listOf("bad-signature", "wrong-project", "expired", "disabled", "revoked")
        val verifier = FakeFirebaseTokenVerifier(
            failedTokens.associateWith {
                Result.failure(FirebaseTokenVerificationException(IllegalStateException("sensitive-$it")))
            },
        )
        application { firebaseTestApplication(verifier, FirebaseUserIdentityService(MemoryIdentityStore(), true)) }

        failedTokens.forEach { token ->
            val response = client.get("/testing/firebase") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            val body = response.bodyAsText()
            assertNeutralUnauthorized(response.status, body)
            assertFalse(body.contains(token))
            assertFalse(body.contains("sensitive"))
        }
        assertEquals(failedTokens, verifier.verifiedTokens)
    }

    @Test
    fun `unknown UID with disabled provisioning returns neutral 401`() = testApplication {
        val verifier = FakeFirebaseTokenVerifier(
            mapOf("valid-token" to Result.success(VerifiedFirebaseToken("unknown-uid", null, false))),
        )
        application { firebaseTestApplication(verifier, FirebaseUserIdentityService(MemoryIdentityStore(), false)) }

        val response = client.get("/testing/firebase") {
            header(HttpHeaders.Authorization, "Bearer valid-token")
        }

        assertNeutralUnauthorized(response.status, response.bodyAsText())
    }

    @Test
    fun `identity uniqueness conflict returns neutral 401`() = testApplication {
        val verifier = FakeFirebaseTokenVerifier(
            mapOf("valid-token" to Result.success(VerifiedFirebaseToken("firebase-uid", null, false))),
        )
        val conflictingStore = object : UserIdentityStore {
            override fun findUserByIdentity(provider: String, externalSubject: String): UserRecord? = null
            override fun createUserWithIdentity(user: UserRecord, identity: UserIdentityRecord): UserRecord =
                throw FirebaseIdentityConflictException(IllegalStateException("sensitive database detail"))
        }
        application { firebaseTestApplication(verifier, FirebaseUserIdentityService(conflictingStore, true)) }

        val response = client.get("/testing/firebase") {
            header(HttpHeaders.Authorization, "Bearer valid-token")
        }

        assertNeutralUnauthorized(response.status, response.bodyAsText())
    }

    private fun io.ktor.server.application.Application.firebaseTestApplication(
        verifier: FakeFirebaseTokenVerifier,
        users: FirebaseUserIdentityService,
    ) {
        configureApplication(
            config = testConfig(),
            databaseReadiness = { true },
            firebaseTokenVerifier = verifier,
            firebaseUsers = users,
        )
        routing {
            authenticate(FIREBASE_USER_AUTH) {
                get("/testing/firebase") {
                    val principal = requireNotNull(call.principal<UserPrincipal>())
                    call.respondText(
                        listOf(
                            principal.userId,
                            principal.firebaseUid,
                            principal.email.orEmpty(),
                            principal.emailVerified,
                        ).joinToString("|"),
                    )
                }
            }
        }
    }

    private fun assertNeutralUnauthorized(status: HttpStatusCode, body: String) {
        assertEquals(HttpStatusCode.Unauthorized, status)
        val error = Json.decodeFromString<ApiErrorResponse>(body)
        assertEquals("UNAUTHORIZED", error.code)
        assertEquals("Authentication is required", error.message)
    }

    private fun testConfig() = AppConfig(
        httpPort = 8080,
        database = DatabaseConfig("jdbc:none", "test", "not-logged"),
        allowedOrigins = emptySet(),
        firebase = FirebaseConfig("test-project"),
    )
}

private class MemoryIdentityStore : UserIdentityStore {
    val users = mutableListOf<UserRecord>()
    private val identities = mutableListOf<UserIdentityRecord>()

    override fun findUserByIdentity(provider: String, externalSubject: String): UserRecord? =
        identities.find { it.provider == provider && it.externalSubject == externalSubject }
            ?.let { identity -> users.single { it.id == identity.userId } }

    override fun createUserWithIdentity(user: UserRecord, identity: UserIdentityRecord): UserRecord {
        findUserByIdentity(identity.provider, identity.externalSubject)?.let { return it }
        users += user
        identities += identity
        return user
    }
}
