package com.alad1nks.jaiqal.users

import java.time.OffsetDateTime
import java.util.UUID

data class UserRecord(val id: UUID, val email: String, val passwordHash: String, val createdAt: OffsetDateTime)

interface UserRepository {
    fun create(user: UserRecord): UserRecord
    fun findById(id: UUID): UserRecord?
    fun findByEmail(email: String): UserRecord?
}
