package com.alad1nks.jaiqal.core.database

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

class AndroidDatabaseDriverFactory(private val context: Context) : DatabaseDriverFactory {
    override fun create(): SqlDriver = AndroidSqliteDriver(JaiqalDatabase.Schema, context, "jaiqal.db")
}
