package com.alad1nks.jaiqal.feature.plants.di

import com.alad1nks.jaiqal.feature.plants.data.ApiPlantRealtimeDataSource
import com.alad1nks.jaiqal.feature.plants.data.ApiPlantRemoteDataSource
import com.alad1nks.jaiqal.feature.plants.data.PlantRealtimeDataSource
import com.alad1nks.jaiqal.feature.plants.data.PlantRemoteDataSource
import com.alad1nks.jaiqal.feature.plants.domain.OfflineFirstPlantRepository
import com.alad1nks.jaiqal.feature.plants.domain.PlantRepository
import com.alad1nks.jaiqal.feature.plants.presentation.CreatePlantViewModel
import com.alad1nks.jaiqal.feature.plants.presentation.EditPlantViewModel
import com.alad1nks.jaiqal.feature.plants.presentation.PlantDetailsViewModel
import com.alad1nks.jaiqal.feature.plants.presentation.PlantsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

val plantsModule = module {
    single<PlantRemoteDataSource> { ApiPlantRemoteDataSource(get()) }
    single<PlantRealtimeDataSource> { ApiPlantRealtimeDataSource(get(), get(), get(), get()) }
    single<PlantRepository> { OfflineFirstPlantRepository(get(), get(), get(), get(), get()) }
    viewModel { PlantsViewModel(get()) }
    viewModel { parameters -> PlantDetailsViewModel(parameters.get(), get()) }
    viewModel { CreatePlantViewModel(get()) }
    viewModel { parameters -> EditPlantViewModel(parameters.get(), get()) }
}
