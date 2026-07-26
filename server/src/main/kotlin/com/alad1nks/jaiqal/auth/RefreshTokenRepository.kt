package com.alad1nks.jaiqal.auth

import java.time.OffsetDateTime
import java.util.UUID

data class RefreshTokenRecord(
    val id: UUID, val userId: UUID, val tokenHash: String, val expiresAt: OffsetDateTime,
    val createdAt: OffsetDateTime, val revokedAt: OffsetDateTime? = null, val replacedById: UUID? = null,
)

interface RefreshTokenRepository {
    fun create(token: RefreshTokenRecord): RefreshTokenRecord
    fun findByHash(tokenHash: String): RefreshTokenRecord?
}
