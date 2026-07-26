package com.alad1nks.jaiqal.infrastructure.database

import com.alad1nks.jaiqal.auth.FirebaseIdentityRepository
import com.alad1nks.jaiqal.auth.VerifiedFirebaseToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.SQLException
import java.util.UUID
import javax.sql.DataSource

class JdbcFirebaseIdentityRepository(private val dataSource: DataSource) : FirebaseIdentityRepository {
    override suspend fun resolve(token: VerifiedFirebaseToken, autoProvision: Boolean): UUID? = withContext(Dispatchers.IO) {
        find(token.uid) ?: if (!autoProvision) null else provision(token)
    }

    private fun find(uid: String): UUID? = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT user_id FROM user_identities WHERE provider='firebase' AND external_subject=?").use {
            it.setString(1, uid)
            it.executeQuery().use { result -> if (result.next()) result.getObject(1, UUID::class.java) else null }
        }
    }

    private fun provision(token: VerifiedFirebaseToken): UUID? {
        val userId = UUID.randomUUID()
        try {
            dataSource.connection.use { connection ->
                connection.autoCommit = false
                try {
                    connection.insertFirebaseUser(userId, token)
                    connection.commit()
                    return userId
                } catch (failure: Throwable) {
                    connection.rollback()
                    throw failure
                }
            }
        } catch (failure: SQLException) {
            // A concurrent first request may win the unique Firebase-subject constraint.
            return find(token.uid) ?: throw failure
        }
    }
}

private fun Connection.insertFirebaseUser(userId: UUID, token: VerifiedFirebaseToken) {
    prepareStatement("INSERT INTO users(id,email,password_hash,created_at) VALUES (?,?,NULL,now())").use {
        it.setObject(1, userId)
        it.setString(2, token.email?.trim()?.lowercase())
        it.executeUpdate()
    }
    prepareStatement("INSERT INTO user_identities(id,user_id,provider,external_subject,created_at) VALUES (?,?, 'firebase', ?,now())").use {
        it.setObject(1, UUID.randomUUID())
        it.setObject(2, userId)
        it.setString(3, token.uid)
        it.executeUpdate()
    }
}
