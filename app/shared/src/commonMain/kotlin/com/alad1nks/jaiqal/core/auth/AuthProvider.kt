package com.alad1nks.jaiqal.core.auth

import kotlinx.coroutines.flow.StateFlow

sealed interface AuthState {
    data object Loading : AuthState
    data object Unauthenticated : AuthState
    data class Authenticated(val email: String?, val emailVerified: Boolean) : AuthState
}

/** Implemented by the official Firebase SDK in each native application host. */
interface AuthProvider {
    val authState: StateFlow<AuthState>
    suspend fun signUp(email: String, password: String)
    suspend fun signIn(email: String, password: String)
    suspend fun sendPasswordReset(email: String)
    suspend fun sendEmailVerification()
    suspend fun reloadUser()
    suspend fun getIdToken(forceRefresh: Boolean = false): String?
    suspend fun signOut()
}

fun validateEmail(value: String): Boolean {
    val at = value.indexOf('@')
    return at > 0 && at < value.lastIndex && '.' in value.substring(at + 1)
}

fun validatePassword(value: String): Boolean = value.length >= 6
