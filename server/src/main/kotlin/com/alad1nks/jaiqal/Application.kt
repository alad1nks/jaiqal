package com.alad1nks.jaiqal

import com.alad1nks.jaiqal.auth.DeviceTokenAuthenticator
import com.alad1nks.jaiqal.auth.FirebaseTokenVerifier
import com.alad1nks.jaiqal.config.AppConfig
import com.alad1nks.jaiqal.infrastructure.database.DatabaseReadiness
import com.alad1nks.jaiqal.infrastructure.database.CachedDatabaseReadiness
import com.alad1nks.jaiqal.infrastructure.database.JdbcDatabaseReadiness
import com.alad1nks.jaiqal.infrastructure.database.DatabaseInfrastructure
import com.alad1nks.jaiqal.infrastructure.database.DatabaseMigrator
import com.alad1nks.jaiqal.config.MigrationDatabaseConfig
import com.alad1nks.jaiqal.infrastructure.database.DataSourceDatabaseReadiness
import com.alad1nks.jaiqal.infrastructure.database.ExposedDeviceRepository
import com.alad1nks.jaiqal.infrastructure.database.ExposedDeviceTokenAuthenticator
import com.alad1nks.jaiqal.infrastructure.database.ExposedTelemetryStore
import com.alad1nks.jaiqal.infrastructure.database.JdbcDeviceIngestionQuota
import com.alad1nks.jaiqal.infrastructure.database.DatabaseCapacityMonitor
import com.alad1nks.jaiqal.infrastructure.database.TelemetryRetentionWorker
import com.alad1nks.jaiqal.infrastructure.security.FirebaseAdmin
import com.alad1nks.jaiqal.infrastructure.security.SecurityAuditTrail
import com.alad1nks.jaiqal.devices.DeviceRepository
import com.alad1nks.jaiqal.telemetry.TelemetryIngestionService
import com.alad1nks.jaiqal.infrastructure.database.JdbcUserApplicationStore
import com.alad1nks.jaiqal.infrastructure.database.JdbcPlantTelemetryRepository
import com.alad1nks.jaiqal.infrastructure.database.JdbcUserIdentityStore
import com.alad1nks.jaiqal.users.UserApplicationService
import com.alad1nks.jaiqal.users.FirebaseUserIdentityService
import com.alad1nks.jaiqal.telemetry.MeasurementEventBus
import com.alad1nks.jaiqal.telemetry.PlantTelemetryService
import com.alad1nks.jaiqal.plugins.configureAuthentication
import com.alad1nks.jaiqal.plugins.configureHttp
import com.alad1nks.jaiqal.plugins.configureMonitoring
import com.alad1nks.jaiqal.plugins.configureRouting
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
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
    val firebaseTokenVerifier = FirebaseAdmin.initialize(config.firebase)
    if (!config.deployment.isProduction) {
        DatabaseMigrator.migrate(MigrationDatabaseConfig.fromEnvironmentOrRuntime(config.database))
    }
    val database = DatabaseInfrastructure.create(config.database)
    if (config.deployment.isProduction) {
        database.verifyRuntimeHasNoDdlPrivileges()
    }
    val eventBus = MeasurementEventBus()
    val securityAuditTrail = SecurityAuditTrail.logging()
    val alertService = AlertService(database.dataSource)
    val notificationWorker = NotificationWorker(database.dataSource, LoggingNotificationSender(), config.alerts)
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
            TelemetryIngestionService(
                store = ExposedTelemetryStore(database.database),
                config = config.telemetry,
                publisher = eventBus,
                quota = JdbcDeviceIngestionQuota(
                    database.dataSource,
                    config.telemetry,
                    securityAuditTrail = securityAuditTrail,
                ),
            ),
            UserApplicationService(JdbcUserApplicationStore(database.dataSource)),
            PlantTelemetryService(JdbcPlantTelemetryRepository(database.dataSource), config.history),
            eventBus,
            alertService,
            notificationWorker,
            firebaseTokenVerifier,
            FirebaseUserIdentityService(
                JdbcUserIdentityStore(database.dataSource),
                config.firebase.autoProvisionUsers,
                securityAuditTrail = securityAuditTrail,
            ),
            DatabaseCapacityMonitor(database.dataSource, config.capacityMonitoring),
            TelemetryRetentionWorker(database.dataSource, config.telemetryRetention),
            securityAuditTrail,
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
    firebaseTokenVerifier: FirebaseTokenVerifier? = null,
    firebaseUsers: FirebaseUserIdentityService? = null,
    databaseCapacityMonitor: DatabaseCapacityMonitor? = null,
    telemetryRetentionWorker: TelemetryRetentionWorker? = null,
    securityAuditTrail: SecurityAuditTrail = SecurityAuditTrail.logging(),
    directPeerAddress: (ApplicationCall) -> String = { call -> call.request.local.remoteAddress },
) {
    configureMonitoring()
    configureHttp(config, securityAuditTrail, directPeerAddress)
    configureAuthentication(deviceTokenAuthenticator, firebaseTokenVerifier, firebaseUsers)
    configureRouting(
        databaseReadiness = CachedDatabaseReadiness(
            delegate = databaseReadiness,
            cacheTtlMilliseconds = config.httpLimits.readinessCacheTtlMilliseconds,
        ),
        deviceRepository = deviceRepository,
        telemetry = telemetry,
        userApplication = userApplication,
        plantTelemetry = plantTelemetry,
        eventBus = eventBus,
        heartbeatSeconds = config.history.heartbeatSeconds,
        streamMaxLifetimeSeconds = config.history.streamMaxLifetimeSeconds,
        streamOwnershipRecheckSeconds = config.history.streamOwnershipRecheckSeconds,
        deploymentCommitSha = config.deployment.commitSha,
        httpLimits = config.httpLimits,
        alerts = alertService,
        securityAuditTrail = securityAuditTrail,
    )
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
    if (databaseCapacityMonitor != null) launch(Dispatchers.IO) {
        val log = LoggerFactory.getLogger("DatabaseCapacityBackgroundWorker")
        while (true) {
            runCatching(databaseCapacityMonitor::check).onFailure { log.error("Database capacity check failed", it) }
            delay(config.capacityMonitoring.intervalSeconds * 1_000)
        }
    }
    if (telemetryRetentionWorker != null) launch(Dispatchers.IO) {
        val log = LoggerFactory.getLogger("TelemetryRetentionBackgroundWorker")
        while (true) {
            runCatching(telemetryRetentionWorker::runOnce)
                .onFailure { log.error("Telemetry retention failed", it) }
            delay(config.telemetryRetention.intervalSeconds * 1_000)
        }
    }
}
