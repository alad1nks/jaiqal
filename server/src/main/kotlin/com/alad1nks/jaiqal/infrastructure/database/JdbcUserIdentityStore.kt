package com.alad1nks.jaiqal.infrastructure.database

import com.alad1nks.jaiqal.users.FirebaseIdentityConflictException
import com.alad1nks.jaiqal.users.UserIdentityRecord
import com.alad1nks.jaiqal.users.UserIdentityStore
import com.alad1nks.jaiqal.users.UserRecord
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource

class JdbcUserIdentityStore(private val dataSource: DataSource) : UserIdentityStore {
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
            user
        }
    } catch (failure: SQLException) {
        if (failure.sqlState != POSTGRES_UNIQUE_VIOLATION) throw failure

        // A concurrent first login for the same Firebase UID is successful once
        // the winning transaction commits. Other uniqueness conflicts are refused.
        findUserByIdentity(identity.provider, identity.externalSubject)
            ?: throw FirebaseIdentityConflictException(failure)
    }

    private fun <T> transaction(block: (Connection) -> T): T = dataSource.connection.use { connection ->
        try {
            block(connection).also { connection.commit() }
        } catch (failure: Throwable) {
            connection.rollback()
            throw failure
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
