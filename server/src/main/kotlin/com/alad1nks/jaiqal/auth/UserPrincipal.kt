package com.alad1nks.jaiqal.auth

import java.util.UUID

data class UserPrincipal(
    val userId: UUID,
    val firebaseUid: String,
    val email: String?,
    val emailVerified: Boolean,
)
