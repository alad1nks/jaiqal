package com.alad1nks.jaiqal

import com.alad1nks.jaiqal.auth.DeviceTokenAuthenticator
import com.alad1nks.jaiqal.auth.FirebaseAdminTokenVerifier
import com.alad1nks.jaiqal.auth.FirebaseIdentityRepository
import com.alad1nks.jaiqal.auth.FirebaseTokenVerifier
import com.alad1nks.jaiqal.auth.FirebaseUserAuthenticator
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
import com.alad1nks.jaiqal.infrastructure.database.JdbcFirebaseIdentityRepository
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
import com.alad1nks.jaiqal.alerts.AlertService
import com.alad1nks.jaiqal.notifications.LoggingNotificationSender
import com.alad1nks.jaiqal.notifications.NotificationWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

fun main() {
    val config = AppConfig.fromEnvironment()
    val database = DatabaseInfrastructure.create(config.database)
    database.migrate()
    val eventBus = MeasurementEventBus()
    val alertService = AlertService(database.dataSource)
    val notificationWorker = NotificationWorker(database.dataSource, LoggingNotificationSender(), config.alerts)
    val firebaseVerifier = FirebaseAdminTokenVerifier.initialize(config.firebase.projectId, config.firebase.checkRevokedTokens)
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
            UserApplicationService(JdbcUserApplicationStore(database.dataSource)),
            PlantTelemetryService(JdbcPlantTelemetryRepository(database.dataSource), config.history),
            eventBus,
            alertService,
            notificationWorker,
            firebaseVerifier,
            JdbcFirebaseIdentityRepository(database.dataSource),
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
    alertService: AlertService? = null,
    notificationWorker: NotificationWorker? = null,
    firebaseTokenVerifier: FirebaseTokenVerifier = FirebaseTokenVerifier { error("Firebase verifier is not configured") },
    firebaseIdentityRepository: FirebaseIdentityRepository = FirebaseIdentityRepository { _, _ -> null },
) {
    configureMonitoring()
    configureHttp(config)
    configureAuthentication(
        FirebaseUserAuthenticator(firebaseTokenVerifier, firebaseIdentityRepository, config.firebase.autoProvisionUsers),
        deviceTokenAuthenticator,
    )
    configureRouting(databaseReadiness, deviceRepository, telemetry, userApplication, plantTelemetry, eventBus, config.history.heartbeatSeconds, alertService)
    if (alertService != null) {
        eventBus?.let { bus -> launch(Dispatchers.IO) { bus.updates.collect { event -> event.plantId?.let(alertService::evaluatePlant) } } }
        launch(Dispatchers.IO) {
            val log = LoggerFactory.getLogger("AlertBackgroundWorker")
            while (true) {
                runCatching { alertService.plantsWithRules().forEach(alertService::evaluatePlant) }.onFailure { log.error("Alert evaluation failed", it) }
                delay(config.alerts.evaluationSeconds * 1_000)
            }
        }
    }
    if (notificationWorker != null) launch(Dispatchers.IO) {
        val log = LoggerFactory.getLogger("NotificationBackgroundWorker")
        while (true) {
            runCatching(notificationWorker::runOnce).onFailure { log.error("Notification outbox poll failed", it) }
            delay(config.alerts.outboxPollSeconds * 1_000)
        }
    }
}
