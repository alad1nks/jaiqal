package com.alad1nks.jaiqal.infrastructure.database

import com.alad1nks.jaiqal.config.MigrationDatabaseConfig

fun main() {
    val config = MigrationDatabaseConfig.fromEnvironment()
    require(config.usesVerifiedTls()) {
        "MIGRATION_DATABASE_URL must set sslmode=verify-full and channelBinding=require"
    }
    DatabaseMigrator.migrate(config)
}
