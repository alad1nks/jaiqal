package com.alad1nks.jaiqal.feature.settings.di

import com.alad1nks.jaiqal.feature.settings.presentation.SettingsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val settingsModule = module {
    viewModel { SettingsViewModel(get(), get(), get()) }
}
