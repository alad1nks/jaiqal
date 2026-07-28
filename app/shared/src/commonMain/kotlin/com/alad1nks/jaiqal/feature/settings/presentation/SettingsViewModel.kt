package com.alad1nks.jaiqal.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.alad1nks.jaiqal.core.auth.AuthProvider
import com.alad1nks.jaiqal.core.auth.UserSessionStore
import com.alad1nks.jaiqal.core.network.AppEnvironment
import kotlinx.coroutines.launch

class SettingsViewModel(
    appEnvironment: AppEnvironment,
    private val authProvider: AuthProvider,
    userSessionStore: UserSessionStore,
) : ViewModel() {
    val environmentName: String = appEnvironment.name.lowercase()
    val userSession = userSessionStore.session

    fun signOut() {
        viewModelScope.launch { authProvider.signOut() }
    }
}
