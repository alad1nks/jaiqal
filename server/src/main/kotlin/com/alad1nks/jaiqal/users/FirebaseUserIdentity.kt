package com.alad1nks.jaiqal.users

import com.alad1nks.jaiqal.auth.VerifiedFirebaseToken
import java.time.Clock
import java.time.OffsetDateTime
import java.util.UUID

const val FIREBASE_IDENTITY_PROVIDER = "firebase"

data class UserIdentityRecord(
    val id: UUID,
    val userId: UUID,
    val provider: String,
    val externalSubject: String,
    val createdAt: OffsetDateTime,
) {
    init {
        require(provider.isNotBlank() && provider.length <= 50) { "Identity provider must contain 1 to 50 characters" }
        require(externalSubject.isNotBlank() && externalSubject.length <= 255) { "External subject must contain 1 to 255 characters" }
    }
}

interface UserIdentityStore {
    fun findUserByIdentity(provider: String, externalSubject: String): UserRecord?

    /** Atomically creates both records. Implementations must make a same-identity race idempotent. */
    fun createUserWithIdentity(user: UserRecord, identity: UserIdentityRecord): UserRecord
}

class FirebaseUserIdentityService(
    private val store: UserIdentityStore,
    private val autoProvisionUsers: Boolean,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun resolve(token: VerifiedFirebaseToken): UserRecord {
        store.findUserByIdentity(FIREBASE_IDENTITY_PROVIDER, token.uid)?.let { return it }
        if (!autoProvisionUsers) throw UnknownFirebaseIdentityException()

        val createdAt = OffsetDateTime.now(clock)
        val user = UserRecord(
            id = UUID.randomUUID(),
            email = token.email?.trim()?.lowercase()?.takeIf(String::isNotEmpty),
            passwordHash = null,
            createdAt = createdAt,
        )
        val identity = UserIdentityRecord(
            id = UUID.randomUUID(),
            userId = user.id,
            provider = FIREBASE_IDENTITY_PROVIDER,
            externalSubject = token.uid,
            createdAt = createdAt,
        )
        return store.createUserWithIdentity(user, identity)
    }
}

class UnknownFirebaseIdentityException : RuntimeException(
    "Firebase user is not linked to an internal account and automatic provisioning is disabled",
)

class FirebaseIdentityConflictException(cause: Throwable? = null) : RuntimeException(
    "Firebase identity could not be provisioned",
    cause,
)
