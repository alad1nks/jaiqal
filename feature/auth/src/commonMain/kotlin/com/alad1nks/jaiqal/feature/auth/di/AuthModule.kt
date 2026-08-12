package com.alad1nks.jaiqal.feature.auth.di

import com.alad1nks.jaiqal.feature.auth.presentation.AuthViewModel
import com.alad1nks.jaiqal.feature.auth.presentation.VerifyEmailViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val authModule = module {
    viewModel { AuthViewModel(get()) }
    viewModel { VerifyEmailViewModel(get()) }
}
