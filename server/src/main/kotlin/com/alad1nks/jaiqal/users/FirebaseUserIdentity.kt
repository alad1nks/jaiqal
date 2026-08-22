package com.alad1nks.jaiqal.users

import com.alad1nks.jaiqal.auth.VerifiedFirebaseToken
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditAction
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditEvent
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditResult
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditTarget
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditTrail
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

    fun deletedIdentityOwner(provider: String, externalSubject: String): UUID? = null

    /** Atomically creates both records. Implementations must make a same-identity race idempotent. */
    fun createUserWithIdentity(user: UserRecord, identity: UserIdentityRecord): UserRecord
}

fun interface AccountDeletionStore {
    /** Atomically tombstones the Firebase identity and removes all account-owned data. */
    fun deleteAccount(userId: UUID, firebaseUid: String)
}

class AccountDeletionService(private val store: AccountDeletionStore) {
    fun deleteAccount(userId: UUID, firebaseUid: String) = store.deleteAccount(userId, firebaseUid)
}

class FirebaseUserIdentityService(
    private val store: UserIdentityStore,
    private val autoProvisionUsers: Boolean,
    private val clock: Clock = Clock.systemUTC(),
    private val securityAuditTrail: SecurityAuditTrail = SecurityAuditTrail.logging(),
) {
    fun resolve(token: VerifiedFirebaseToken, requestId: String? = null): UserRecord {
        store.deletedIdentityOwner(FIREBASE_IDENTITY_PROVIDER, token.uid)?.let { ownerId ->
            throw DeletedFirebaseIdentityException(ownerId)
        }
        store.findUserByIdentity(FIREBASE_IDENTITY_PROVIDER, token.uid)?.let { return it }
        if (!autoProvisionUsers) {
            recordProvisioning(SecurityAuditResult.REJECTED, requestId = requestId)
            throw UnknownFirebaseIdentityException()
        }

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
        return try {
            store.createUserWithIdentity(user, identity).also { resolved ->
                recordProvisioning(SecurityAuditResult.SUCCESS, resolved.id, requestId)
            }
        } catch (exception: FirebaseIdentityConflictException) {
            recordProvisioning(SecurityAuditResult.FAILURE, requestId = requestId)
            throw exception
        } catch (exception: Exception) {
            recordProvisioning(SecurityAuditResult.FAILURE, requestId = requestId)
            throw exception
        }
    }

    private fun recordProvisioning(result: SecurityAuditResult, userId: UUID? = null, requestId: String?) =
        securityAuditTrail.record(
            SecurityAuditEvent(
                action = SecurityAuditAction.PROVISION_USER,
                result = result,
                target = SecurityAuditTarget.USER_API,
                actorUserId = userId,
                resourceId = userId,
                requestId = requestId,
            ),
        )
}

class UnknownFirebaseIdentityException : RuntimeException(
    "Firebase user is not linked to an internal account and automatic provisioning is disabled",
)

class FirebaseIdentityConflictException(cause: Throwable? = null) : RuntimeException(
    "Firebase identity could not be provisioned",
    cause,
)

class DeletedFirebaseIdentityException(val userId: UUID) : RuntimeException(
    "Firebase identity belongs to a deleted account",
)
