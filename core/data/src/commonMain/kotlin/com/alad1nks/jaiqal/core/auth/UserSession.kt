package com.alad1nks.jaiqal.core.auth

import com.alad1nks.jaiqal.api.contract.CurrentUserResponse
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class UserSession(
    val userId: String,
    val email: String?,
    val emailVerified: Boolean,
)

class UserSessionStore {
    private val mutableSession = MutableStateFlow<UserSession?>(null)
    val session: StateFlow<UserSession?> = mutableSession.asStateFlow()

    fun set(user: CurrentUserResponse) {
        mutableSession.value = UserSession(user.id, user.email, user.emailVerified)
    }

    fun clear() {
        mutableSession.value = null
    }
}
