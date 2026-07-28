package com.alad1nks.jaiqal.core.connectivity

import kotlinx.coroutines.flow.StateFlow

enum class ConnectionStatus { AVAILABLE, UNAVAILABLE, UNKNOWN }

interface ConnectivityObserver {
    val status: StateFlow<ConnectionStatus>
}
