package com.alad1nks.jaiqal.infrastructure.database

import com.alad1nks.jaiqal.users.FirebaseIdentityConflictException
import com.alad1nks.jaiqal.users.DeletedFirebaseIdentityException
import com.alad1nks.jaiqal.users.AccountDeletionStore
import com.alad1nks.jaiqal.users.FIREBASE_IDENTITY_PROVIDER
import com.alad1nks.jaiqal.users.UserIdentityRecord
import com.alad1nks.jaiqal.users.UserIdentityStore
import com.alad1nks.jaiqal.users.UserRecord
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.security.MessageDigest
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

class JdbcUserIdentityStore(private val dataSource: DataSource) : UserIdentityStore, AccountDeletionStore {
    override fun deletedIdentityOwner(provider: String, externalSubject: String): UUID? {
        if (provider != FIREBASE_IDENTITY_PROVIDER) return null
        return dataSource.connection.use { connection ->
            connection.prepareStatement(
                "SELECT user_id FROM deleted_firebase_identities WHERE firebase_uid_hash=?",
            ).use { statement ->
                statement.setString(1, externalSubject.sha256())
                statement.executeQuery().use { rows ->
                    if (rows.next()) rows.getObject(1, UUID::class.java) else null
                }
            }
        }
    }

    override fun findUserByIdentity(provider: String, externalSubject: String): UserRecord? =
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                """SELECT u.* FROM user_identities i JOIN users u ON u.id=i.user_id WHERE i.provider=? AND i.external_subject=?""",
            ).use { statement ->
                statement.setString(1, provider)
                statement.setString(2, externalSubject)
                statement.executeQuery().use { results -> if (results.next()) results.toUser() else null }
            }
        }

    override fun createUserWithIdentity(user: UserRecord, identity: UserIdentityRecord): UserRecord = try {
        transaction { connection ->
            if (identity.provider == FIREBASE_IDENTITY_PROVIDER) {
                connection.deletedFirebaseIdentityOwner(identity.externalSubject)?.let { ownerId ->
                    throw DeletedFirebaseIdentityException(ownerId)
                }
            }
            connection.prepareStatement(
                "INSERT INTO users(id,email,password_hash,created_at) VALUES (?,?,?,?)",
            ).use { statement ->
                statement.setObject(1, user.id)
                statement.setString(2, user.email)
                statement.setString(3, user.passwordHash)
                statement.setObject(4, user.createdAt)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO user_identities(id,user_id,provider,external_subject,created_at) VALUES (?,?,?,?,?)",
            ).use { statement ->
                statement.setObject(1, identity.id)
                statement.setObject(2, identity.userId)
                statement.setString(3, identity.provider)
                statement.setString(4, identity.externalSubject)
                statement.setObject(5, identity.createdAt)
                statement.executeUpdate()
            }
            connection.prepareStatement("SELECT * FROM users WHERE id=?").use { statement ->
                statement.setObject(1, user.id)
                statement.executeQuery().use { results ->
                    check(results.next()) { "Created user could not be read back" }
                    results.toUser()
                }
            }
        }
    } catch (failure: SQLException) {
        if (failure.sqlState != POSTGRES_UNIQUE_VIOLATION) throw failure

        // A concurrent first login for the same Firebase UID is successful once
        // the winning transaction commits. Other uniqueness conflicts are refused.
        findUserByIdentity(identity.provider, identity.externalSubject)
            ?: throw FirebaseIdentityConflictException(failure)
    }

    override fun deleteAccount(userId: UUID, firebaseUid: String) {
        transaction { connection ->
            val uidHash = firebaseUid.sha256()
            connection.prepareStatement(
                "INSERT INTO deleted_firebase_identities(firebase_uid_hash,user_id,deleted_at) VALUES (?,?,CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING",
            ).use { statement ->
                statement.setString(1, uidHash)
                statement.setObject(2, userId)
                statement.executeUpdate()
            }

            val identityOwner = connection.prepareStatement(
                "SELECT user_id FROM user_identities WHERE provider=? AND external_subject=? FOR UPDATE",
            ).use { statement ->
                statement.setString(1, FIREBASE_IDENTITY_PROVIDER)
                statement.setString(2, firebaseUid)
                statement.executeQuery().use { rows ->
                    if (rows.next()) rows.getObject(1, UUID::class.java) else null
                }
            }
            if (identityOwner != null && identityOwner != userId) {
                throw FirebaseIdentityConflictException()
            }
            if (identityOwner == null) return@transaction

            connection.executeAccountDelete(
                "DELETE FROM device_latest_state WHERE device_id IN (SELECT d.id FROM devices d JOIN plants p ON p.id=d.plant_id WHERE p.user_id=?)",
                userId,
            )
            connection.executeAccountDelete(
                "DELETE FROM measurements WHERE device_id IN (SELECT d.id FROM devices d JOIN plants p ON p.id=d.plant_id WHERE p.user_id=?)",
                userId,
            )
            connection.executeAccountDelete(
                "DELETE FROM devices WHERE plant_id IN (SELECT id FROM plants WHERE user_id=?)",
                userId,
            )
            connection.executeAccountDelete("DELETE FROM plants WHERE user_id=?", userId)
            connection.executeAccountDelete("DELETE FROM users WHERE id=?", userId)
        }
    }

    private fun <T> transaction(block: (Connection) -> T): T = dataSource.connection.use { connection ->
        try {
            block(connection).also { connection.commit() }
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
        }
    }

    private fun Connection.deletedFirebaseIdentityOwner(firebaseUid: String): UUID? = prepareStatement(
        "SELECT user_id FROM deleted_firebase_identities WHERE firebase_uid_hash=?",
    ).use { statement ->
        statement.setString(1, firebaseUid.sha256())
        statement.executeQuery().use { rows ->
            if (rows.next()) rows.getObject(1, UUID::class.java) else null
        }
    }

    private fun Connection.executeAccountDelete(sql: String, userId: UUID) {
        prepareStatement(sql).use { statement ->
            statement.setObject(1, userId)
            statement.executeUpdate()
        }
    }

    private fun ResultSet.toUser() = UserRecord(
        id = getObject("id", UUID::class.java),
        email = getString("email"),
        passwordHash = getString("password_hash"),
        createdAt = getObject("created_at", OffsetDateTime::class.java),
    )

    private companion object {
        const val POSTGRES_UNIQUE_VIOLATION = "23505"
    }
}

private fun String.sha256(): String = MessageDigest.getInstance("SHA-256")
    .digest(toByteArray(Charsets.UTF_8))
    .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
