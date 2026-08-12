package com.alad1nks.jaiqal.feature.alerts.di

import com.alad1nks.jaiqal.feature.alerts.data.AlertLocalDataSource
import com.alad1nks.jaiqal.feature.alerts.data.AlertRemoteDataSource
import com.alad1nks.jaiqal.feature.alerts.data.ApiAlertRemoteDataSource
import com.alad1nks.jaiqal.feature.alerts.data.CacheAlertLocalDataSource
import com.alad1nks.jaiqal.feature.alerts.domain.AlertRepository
import com.alad1nks.jaiqal.feature.alerts.domain.OfflineFirstAlertRepository
import com.alad1nks.jaiqal.feature.alerts.presentation.AlertRulesViewModel
import com.alad1nks.jaiqal.feature.alerts.presentation.AlertsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val alertsModule = module {
    single<AlertRemoteDataSource> { ApiAlertRemoteDataSource(get()) }
    single<AlertLocalDataSource> { CacheAlertLocalDataSource(get(), get()) }
    single<AlertRepository> { OfflineFirstAlertRepository(get(), get()) }
    viewModel { AlertsViewModel(get()) }
    viewModel { parameters -> AlertRulesViewModel(parameters.getOrNull(), get()) }
}
