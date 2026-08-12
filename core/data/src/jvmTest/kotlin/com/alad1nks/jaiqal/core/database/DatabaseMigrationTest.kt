package com.alad1nks.jaiqal.core.database

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals

class DatabaseMigrationTest {
    @Test
    fun migrationFromMetadataOnlySchemaPreservesDataAndAddsOfflineTables() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            driver.execute(
                identifier = null,
                sql = """
                    CREATE TABLE cache_metadata (
                        account_id TEXT NOT NULL,
                        cache_key TEXT NOT NULL,
                        synced_at TEXT NOT NULL,
                        PRIMARY KEY (account_id, cache_key)
                    )
                """.trimIndent(),
                parameters = 0,
            ).value
            driver.execute(
                identifier = null,
                sql = "INSERT INTO cache_metadata VALUES ('account-a', 'plants', '2026-07-29T00:00:00Z')",
                parameters = 0,
            ).value

            JaiqalDatabase.Schema.migrate(driver, oldVersion = 1, newVersion = 3).value
            val queries = JaiqalDatabase(driver).cacheMetadataQueries

            assertEquals("2026-07-29T00:00:00Z", queries.selectCacheMetadata("account-a", "plants").executeAsOne().synced_at)
            queries.replacePlant("account-a", "plant-a", "Aloe", null, null, "2026-07-29T00:00:00Z")
            assertEquals("plant-a", queries.selectPlants("account-a").executeAsOne().id)
            queries.upsertAppPreference("language", "KAZAKH")
            assertEquals("KAZAKH", queries.selectAppPreferences().executeAsOne().preference_value)
            assertEquals(3L, JaiqalDatabase.Schema.version)
        } finally {
            driver.close()
        }
    }
}
