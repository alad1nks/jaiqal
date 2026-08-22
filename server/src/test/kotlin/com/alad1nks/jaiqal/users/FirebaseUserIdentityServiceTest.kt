package com.alad1nks.jaiqal.users

import com.alad1nks.jaiqal.auth.VerifiedFirebaseToken
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditAction
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditEvent
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditResult
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditTrail
import com.alad1nks.jaiqal.infrastructure.security.toStructuredMessage
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class FirebaseUserIdentityServiceTest {
    @Test
    fun `known Firebase identity resolves while auto provisioning is disabled`() {
        val store = MemoryIdentityStore()
        val created = FirebaseUserIdentityService(store, true, clock).resolve(token("known"))

        val resolved = FirebaseUserIdentityService(store, false, clock).resolve(token("known"))

        assertEquals(created, resolved)
        assertEquals(1, store.users.size)
    }

    @Test
    fun `unknown Firebase identity is refused while auto provisioning is disabled`() {
        val store = MemoryIdentityStore()

        assertFailsWith<UnknownFirebaseIdentityException> {
            FirebaseUserIdentityService(store, false, clock).resolve(token("unknown"))
        }
        assertEquals(0, store.users.size)
    }

    @Test
    fun `auto provisioning creates one passwordless internal user`() {
        val store = MemoryIdentityStore()
        val service = FirebaseUserIdentityService(store, true, clock)

        val created = service.resolve(token("firebase-uid", " SAME@example.test "))
        val repeated = service.resolve(token("firebase-uid", "ignored@example.test"))

        assertEquals(created.id, repeated.id)
        assertEquals("same@example.test", created.email)
        assertNull(created.passwordHash)
        assertEquals(1, store.users.size)
        assertEquals(1, store.identities.size)
    }

    @Test
    fun `auto provisioning supports a Firebase token without email`() {
        val created = FirebaseUserIdentityService(MemoryIdentityStore(), true, clock)
            .resolve(token("phone-user", null))

        assertNull(created.email)
        assertNull(created.passwordHash)
    }

    @Test
    fun `provisioning signals exclude Firebase identity and email`() {
        val events = mutableListOf<SecurityAuditEvent>()
        val service = FirebaseUserIdentityService(
            MemoryIdentityStore(),
            autoProvisionUsers = true,
            clock = clock,
            securityAuditTrail = SecurityAuditTrail(events::add),
        )

        val created = service.resolve(token("sensitive-firebase-uid", "secret@example.test"), "request-17")

        assertEquals(1, events.size)
        assertEquals(SecurityAuditAction.PROVISION_USER, events.single().action)
        assertEquals(SecurityAuditResult.SUCCESS, events.single().result)
        assertEquals(created.id, events.single().actorUserId)
        assertEquals("request-17", events.single().requestId)
        assertEquals(6, SecurityAuditEvent::class.java.declaredFields.size)
        val serializedEvent = events.single().toStructuredMessage()
        assertFalse(serializedEvent.contains("sensitive-firebase-uid"))
        assertFalse(serializedEvent.contains("secret@example.test"))
    }

    @Test
    fun `rejected and failed provisioning emit safe anomaly results`() {
        val events = mutableListOf<SecurityAuditEvent>()
        val audit = SecurityAuditTrail(events::add)
        val disabled = FirebaseUserIdentityService(
            MemoryIdentityStore(),
            autoProvisionUsers = false,
            clock = clock,
            securityAuditTrail = audit,
        )
        val failingStore = object : UserIdentityStore {
            override fun findUserByIdentity(provider: String, externalSubject: String): UserRecord? = null
            override fun createUserWithIdentity(user: UserRecord, identity: UserIdentityRecord): UserRecord =
                throw FirebaseIdentityConflictException()
        }
        val failing = FirebaseUserIdentityService(failingStore, true, clock, audit)

        assertFailsWith<UnknownFirebaseIdentityException> {
            disabled.resolve(token("rejected-sensitive-uid"), "request-rejected")
        }
        assertFailsWith<FirebaseIdentityConflictException> {
            failing.resolve(token("failed-sensitive-uid"), "request-failed")
        }

        assertEquals(listOf(SecurityAuditResult.REJECTED, SecurityAuditResult.FAILURE), events.map { it.result })
        assertEquals(listOf("request-rejected", "request-failed"), events.map { it.requestId })
        assertEquals(listOf(null, null), events.map { it.actorUserId })
        assertEquals(listOf(null, null), events.map { it.resourceId })
    }

    private fun token(uid: String, email: String? = "user@example.test") =
        VerifiedFirebaseToken(uid, email, emailVerified = email != null, expiresAt = clock.instant().plusSeconds(3_600))

    private val clock = Clock.fixed(Instant.parse("2026-07-27T08:00:00Z"), ZoneOffset.UTC)
}

private class MemoryIdentityStore : UserIdentityStore {
    val users = mutableListOf<UserRecord>()
    val identities = mutableListOf<UserIdentityRecord>()

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
