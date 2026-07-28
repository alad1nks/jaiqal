package com.alad1nks.jaiqal.core.database

import app.cash.sqldelight.db.SqlDriver

interface DatabaseDriverFactory {
    fun create(): SqlDriver
}
