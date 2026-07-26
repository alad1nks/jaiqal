package com.alad1nks.jaiqal

import com.alad1nks.jaiqal.auth.DeviceTokenAuthenticator
import com.alad1nks.jaiqal.config.AppConfig
import com.alad1nks.jaiqal.infrastructure.database.DatabaseReadiness
import com.alad1nks.jaiqal.infrastructure.database.JdbcDatabaseReadiness
import com.alad1nks.jaiqal.infrastructure.database.DatabaseInfrastructure
import com.alad1nks.jaiqal.infrastructure.database.DataSourceDatabaseReadiness
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
    Runtime.getRuntime().addShutdownHook(Thread(database::close))
    embeddedServer(
        factory = Netty,
        port = config.httpPort,
        host = "0.0.0.0",
    ) {
        configureApplication(config, DataSourceDatabaseReadiness(database.dataSource))
    }.start(wait = true)
}

fun Application.configureApplication(
    config: AppConfig,
    databaseReadiness: DatabaseReadiness = JdbcDatabaseReadiness(config.database),
    deviceTokenAuthenticator: DeviceTokenAuthenticator = DeviceTokenAuthenticator.rejectAll(),
) {
    configureMonitoring()
    configureHttp(config)
    configureAuthentication(config.jwt, deviceTokenAuthenticator)
    configureRouting(databaseReadiness)
}
