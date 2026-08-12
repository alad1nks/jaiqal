package com.alad1nks.jaiqal.core.lifecycle

import kotlinx.coroutines.flow.StateFlow

enum class AppLifecycleState { FOREGROUND, BACKGROUND }

interface AppLifecycleObserver {
    val state: StateFlow<AppLifecycleState>
}
