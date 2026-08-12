package com.alad1nks.jaiqal.feature.devices.di

import com.alad1nks.jaiqal.feature.devices.data.ApiDeviceRemoteDataSource
import com.alad1nks.jaiqal.feature.devices.data.CacheDeviceLocalDataSource
import com.alad1nks.jaiqal.feature.devices.data.DeviceLocalDataSource
import com.alad1nks.jaiqal.feature.devices.data.DeviceRemoteDataSource
import com.alad1nks.jaiqal.feature.devices.domain.DeviceRepository
import com.alad1nks.jaiqal.feature.devices.domain.OfflineFirstDeviceRepository
import com.alad1nks.jaiqal.feature.devices.presentation.CalibrationViewModel
import com.alad1nks.jaiqal.feature.devices.presentation.ClaimDeviceViewModel
import com.alad1nks.jaiqal.feature.devices.presentation.DeviceDetailsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val devicesModule = module {
    single<DeviceRemoteDataSource> { ApiDeviceRemoteDataSource(get()) }
    single<DeviceLocalDataSource> { CacheDeviceLocalDataSource(get(), get()) }
    single<DeviceRepository> { OfflineFirstDeviceRepository(get(), get()) }
    viewModel { parameters -> ClaimDeviceViewModel(parameters.getOrNull(), get()) }
    viewModel { parameters -> DeviceDetailsViewModel(parameters.get(), get()) }
    viewModel { parameters -> CalibrationViewModel(parameters.get(), get()) }
}
