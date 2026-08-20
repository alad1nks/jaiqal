package com.alad1nks.jaiqal.devices

import com.alad1nks.jaiqal.config.DatabaseConfig
import com.alad1nks.jaiqal.infrastructure.database.DatabaseInfrastructure
import com.alad1nks.jaiqal.infrastructure.database.DeviceTokens
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.UUID

private const val PROVISIONING_CONFIRMATION = "I_UNDERSTAND_DEVICE_SECRETS"
private const val PROVISIONING_REPOSITORY_ROOT_PROPERTY = "jaiqal.provisioning.repositoryRoot"
private const val PROVISIONING_CREDENTIALS_SUFFIX = ".credentials"

internal data class ProvisioningCredentials(
    val deviceId: UUID,
    val deviceToken: String,
    val claimCode: String,
)

fun main() {
    val environment = System.getenv()
    requireSafeProvisioningEnvironment(environment)
    val repositoryRoot = requiredRepositoryRoot(System.getProperty(PROVISIONING_REPOSITORY_ROOT_PROPERTY))
    val credentialsPath = requiredCredentialsPath(environment, repositoryRoot)
    val credentials = ProvisioningCredentials(
        deviceId = UUID.randomUUID(),
        deviceToken = secret(),
        claimCode = secret(16),
    )

    writeCredentialsFile(credentialsPath, credentials)
    try {
        val database = DatabaseInfrastructure.create(DatabaseConfig.fromEnvironment())
        database.use {
            val name = environment["DEVICE_NAME"]?.trim()?.takeIf(String::isNotEmpty) ?: "Provisioned sensor"
            val now = OffsetDateTime.now()
            it.dataSource.connection.use { connection ->
                try {
                    connection.prepareStatement("INSERT INTO devices(id,name,token_hash,created_at) VALUES(?,?,?,?)").use { statement ->
                        statement.setObject(1, credentials.deviceId)
                        statement.setString(2, name)
                        statement.setString(3, DeviceTokens.hashHex(credentials.deviceToken))
                        statement.setObject(4, now)
                        statement.executeUpdate()
                    }
                    connection.prepareStatement("INSERT INTO device_claim_codes(id,device_id,code_hash,expires_at,created_at) VALUES(?,?,?,?,?)").use { statement ->
                        statement.setObject(1, UUID.randomUUID())
                        statement.setObject(2, credentials.deviceId)
                        statement.setString(3, DeviceTokens.hashHex(credentials.claimCode))
                        statement.setObject(4, now.plusHours(24))
                        statement.setObject(5, now)
                        statement.executeUpdate()
                    }
                    connection.commit()
                } catch (failure: Throwable) {
                    connection.rollback()
                    throw failure
                }
            }
        }
    } catch (failure: Throwable) {
        throw IllegalStateException(
            "Provisioning failed; protected credentials file remains at $credentialsPath for operator reconciliation",
            failure,
        )
    }

    println("Provisioned device ${credentials.deviceId}; credentials written to $credentialsPath")
}

internal fun requireSafeProvisioningEnvironment(environment: Map<String, String>) {
    require(!environment["CI"].isTruthy()) {
        "Device provisioning is forbidden when CI is enabled"
    }
    require(environment["PROVISIONING_CONFIRM"] == PROVISIONING_CONFIRMATION) {
        "Set PROVISIONING_CONFIRM=$PROVISIONING_CONFIRMATION for an intentional operator-run provisioning"
    }
}

internal fun requiredRepositoryRoot(raw: String?): Path {
    require(!raw.isNullOrBlank()) {
        "Provisioning must be run through the Gradle provisionDevice task"
    }
    val path = Path.of(raw)
    require(path.isAbsolute) { "Provisioning repository root must be absolute" }
    require(Files.isDirectory(path)) { "Provisioning repository root must be an existing directory" }
    return path.toRealPath()
}

internal fun requiredCredentialsPath(environment: Map<String, String>, repositoryRoot: Path): Path {
    val raw = environment["DEVICE_CREDENTIALS_FILE"]?.trim().orEmpty()
    require(raw.isNotEmpty()) { "DEVICE_CREDENTIALS_FILE is required" }
    val path = Path.of(raw)
    require(path.isAbsolute) { "DEVICE_CREDENTIALS_FILE must be an absolute path" }
    require(path.fileName != null) { "DEVICE_CREDENTIALS_FILE must name a file" }
    require(path.fileName.toString().endsWith(PROVISIONING_CREDENTIALS_SUFFIX)) {
        "DEVICE_CREDENTIALS_FILE must end with $PROVISIONING_CREDENTIALS_SUFFIX"
    }

    val parent = path.normalize().parent ?: error("Credentials file must have a parent directory")
    require(Files.isDirectory(parent)) { "Credentials parent must be an existing directory" }
    val canonicalPath = parent.toRealPath().resolve(path.fileName.toString())
    val canonicalRepositoryRoot = requiredRepositoryRoot(repositoryRoot.toString())
    require(!canonicalPath.startsWith(canonicalRepositoryRoot)) {
        "DEVICE_CREDENTIALS_FILE must resolve outside the repository"
    }
    return canonicalPath
}

internal fun writeCredentialsFile(path: Path, credentials: ProvisioningCredentials) {
    val parent = path.parent ?: error("Credentials file must have a parent directory")
    require(Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
        "Credentials parent must be an existing directory and must not be a symbolic link"
    }
    val permissions = PosixFilePermissions.asFileAttribute(
        setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
    )
    val content = buildString {
        append("deviceId=").append(credentials.deviceId).append('\n')
        append("deviceToken=").append(credentials.deviceToken).append('\n')
        append("claimCode=").append(credentials.claimCode).append('\n')
    }
    Files.createFile(path, permissions)
    try {
        Files.writeString(path, content, StandardCharsets.UTF_8, StandardOpenOption.WRITE)
        check(Files.getPosixFilePermissions(path) == setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)) {
            "Credentials file permissions are not 0600"
        }
    } catch (failure: Throwable) {
        Files.deleteIfExists(path)
        throw failure
    }
}

private fun String?.isTruthy(): Boolean = this?.trim()?.lowercase() in setOf("1", "true", "yes", "on")

private fun secret(bytes: Int = 32): String =
    ByteArray(bytes).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
