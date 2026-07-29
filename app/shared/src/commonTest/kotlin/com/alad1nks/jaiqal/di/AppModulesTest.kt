package com.alad1nks.jaiqal.di

import com.alad1nks.jaiqal.app.AppViewModel
import com.alad1nks.jaiqal.core.auth.FakeAuthProvider
import com.alad1nks.jaiqal.core.network.AppEnvironment
import com.alad1nks.jaiqal.core.network.BackendConfig
import com.alad1nks.jaiqal.core.network.DefaultBackendConfig
import kotlin.test.Test
import kotlin.test.assertSame

class AppModulesTest {
    @Test
    fun exposesEnvironmentAndAppViewModel() {
        val config = DefaultBackendConfig(AppEnvironment.LOCAL, "http://localhost:8080")
        val application = createKoinApplication(config, FakeAuthProvider())

        assertSame(config, application.koin.get<BackendConfig>())
        application.koin.get<AppViewModel>()

        application.close()
    }
}
