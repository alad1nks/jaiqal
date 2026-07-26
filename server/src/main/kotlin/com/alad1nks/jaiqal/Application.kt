package com.alad1nks.jaiqal

import com.alad1nks.jaiqal.auth.DeviceTokenAuthenticator
import com.alad1nks.jaiqal.config.AppConfig
import com.alad1nks.jaiqal.infrastructure.database.DatabaseReadiness
import com.alad1nks.jaiqal.infrastructure.database.JdbcDatabaseReadiness
import com.alad1nks.jaiqal.infrastructure.database.DatabaseInfrastructure
import com.alad1nks.jaiqal.infrastructure.database.DataSourceDatabaseReadiness
import com.alad1nks.jaiqal.infrastructure.database.ExposedDeviceRepository
import com.alad1nks.jaiqal.infrastructure.database.ExposedDeviceTokenAuthenticator
import com.alad1nks.jaiqal.infrastructure.database.ExposedTelemetryStore
import com.alad1nks.jaiqal.devices.DeviceRepository
import com.alad1nks.jaiqal.telemetry.TelemetryIngestionService
import com.alad1nks.jaiqal.infrastructure.database.JdbcUserApplicationStore
import com.alad1nks.jaiqal.infrastructure.database.JdbcPlantTelemetryRepository
import com.alad1nks.jaiqal.users.UserApplicationService
import com.alad1nks.jaiqal.telemetry.MeasurementEventBus
import com.alad1nks.jaiqal.telemetry.PlantTelemetryService
import com.alad1nks.jaiqal.plugins.configureAuthentication
import com.alad1nks.jaiqal.plugins.configureHttp
import com.alad1nks.jaiqal.plugins.configureMonitoring
import com.alad1nks.jaiqal.plugins.configureRouting
import io.ktor.server.application.Application
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

fun main() {
    val config = AppConfig.fromEnvironment()
    val database = DatabaseInfrastructure.create(config.database)
    database.migrate()
    val eventBus = MeasurementEventBus()
    Runtime.getRuntime().addShutdownHook(Thread(database::close))
    embeddedServer(
        factory = Netty,
        port = config.httpPort,
        host = "0.0.0.0",
    ) {
        configureApplication(
            config, DataSourceDatabaseReadiness(database.dataSource),
            ExposedDeviceTokenAuthenticator(database.database),
            ExposedDeviceRepository(database.database),
            TelemetryIngestionService(ExposedTelemetryStore(database.database), config.telemetry, eventBus),
            UserApplicationService(JdbcUserApplicationStore(database.dataSource), config.jwt),
            PlantTelemetryService(JdbcPlantTelemetryRepository(database.dataSource), config.history),
            eventBus,
        )
    }.start(wait = true)
}

fun Application.configureApplication(
    config: AppConfig,
    databaseReadiness: DatabaseReadiness = JdbcDatabaseReadiness(config.database),
    deviceTokenAuthenticator: DeviceTokenAuthenticator = DeviceTokenAuthenticator.rejectAll(),
    deviceRepository: DeviceRepository? = null,
    telemetry: TelemetryIngestionService? = null,
    userApplication: UserApplicationService? = null,
    plantTelemetry: PlantTelemetryService? = null,
    eventBus: MeasurementEventBus? = null,
) {
    configureMonitoring()
    configureHttp(config)
    configureAuthentication(config.jwt, deviceTokenAuthenticator)
    configureRouting(databaseReadiness, deviceRepository, telemetry, userApplication, plantTelemetry, eventBus, config.history.heartbeatSeconds)
}
