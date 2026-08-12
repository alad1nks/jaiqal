package com.alad1nks.jaiqal.core.database

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver

class IosDatabaseDriverFactory : DatabaseDriverFactory {
    override fun create(): SqlDriver = NativeSqliteDriver(JaiqalDatabase.Schema, "jaiqal.db")
}
