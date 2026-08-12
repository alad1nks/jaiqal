package com.alad1nks.jaiqal.core.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SessionErrorStore {
    private val mutableRequiresSignIn = MutableStateFlow(false)
    val requiresSignIn: StateFlow<Boolean> = mutableRequiresSignIn.asStateFlow()

    fun reportExpiredSession() {
        mutableRequiresSignIn.value = true
    }

    fun clear() {
        mutableRequiresSignIn.value = false
    }
}
