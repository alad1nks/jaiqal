package com.alad1nks.jaiqal.core.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

class JvmDatabaseDriverFactory : DatabaseDriverFactory {
    override fun create(): SqlDriver = JdbcSqliteDriver("jdbc:sqlite:jaiqal.db").also {
        JaiqalDatabase.Schema.create(it)
    }
}
