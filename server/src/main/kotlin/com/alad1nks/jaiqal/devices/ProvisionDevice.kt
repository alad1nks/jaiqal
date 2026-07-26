package com.alad1nks.jaiqal.devices

import com.alad1nks.jaiqal.config.DatabaseConfig
import com.alad1nks.jaiqal.infrastructure.database.DatabaseInfrastructure
import com.alad1nks.jaiqal.infrastructure.database.DeviceTokens
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.UUID

fun main() {
    fun required(name: String) = System.getenv(name)?.takeIf(String::isNotBlank) ?: error("$name is required")
    val database = DatabaseInfrastructure.create(DatabaseConfig(required("DATABASE_URL"), required("DATABASE_USER"), required("DATABASE_PASSWORD")))
    database.use {
        it.migrate()
        val name = System.getenv("DEVICE_NAME")?.trim()?.takeIf(String::isNotEmpty) ?: "Provisioned sensor"
        val deviceToken = secret()
        val claimCode = secret(16)
        val deviceId = UUID.randomUUID()
        val now = OffsetDateTime.now()
        it.dataSource.connection.use { connection ->
            connection.prepareStatement("INSERT INTO devices(id,name,token_hash,created_at) VALUES(?,?,?,?)").use { statement ->
                statement.setObject(1, deviceId); statement.setString(2, name)
                statement.setString(3, DeviceTokens.hashHex(deviceToken)); statement.setObject(4, now); statement.executeUpdate()
            }
            connection.prepareStatement("INSERT INTO device_claim_codes(id,device_id,code_hash,expires_at,created_at) VALUES(?,?,?,?,?)").use { statement ->
                statement.setObject(1, UUID.randomUUID()); statement.setObject(2, deviceId)
                statement.setString(3, DeviceTokens.hashHex(claimCode)); statement.setObject(4, now.plusHours(24)); statement.setObject(5, now); statement.executeUpdate()
            }
            connection.commit()
        }
        println("deviceId=$deviceId")
        println("deviceToken=$deviceToken")
        println("claimCode=$claimCode")
    }
}

private fun secret(bytes: Int = 32): String = ByteArray(bytes).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
