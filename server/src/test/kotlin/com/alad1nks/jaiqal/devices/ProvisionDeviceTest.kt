package com.alad1nks.jaiqal.devices

import org.junit.Test
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.UUID
import kotlin.io.path.createTempDirectory
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ProvisionDeviceTest {
    @Test
    fun `credentials are written once with owner-only permissions`() {
        val directory = createTempDirectory("jaiqal-provisioning-")
        val path = directory.resolve("device.credentials")
        val credentials = ProvisioningCredentials(UUID.randomUUID(), "device-secret", "claim-secret")

        writeCredentialsFile(path, credentials)

        assertEquals(
            setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            Files.getPosixFilePermissions(path),
        )
        assertEquals(
            "deviceId=${credentials.deviceId}\ndeviceToken=device-secret\nclaimCode=claim-secret\n",
            path.readText(),
        )
        assertFailsWith<FileAlreadyExistsException> { writeCredentialsFile(path, credentials) }
    }

    @Test
    fun `provisioning rejects CI and requires explicit operator confirmation`() {
        assertFailsWith<IllegalArgumentException> {
            requireSafeProvisioningEnvironment(mapOf("CI" to "true", "PROVISIONING_CONFIRM" to "I_UNDERSTAND_DEVICE_SECRETS"))
        }
        assertFailsWith<IllegalArgumentException> { requireSafeProvisioningEnvironment(emptyMap()) }
        requireSafeProvisioningEnvironment(mapOf("PROVISIONING_CONFIRM" to "I_UNDERSTAND_DEVICE_SECRETS"))
    }

    @Test
    fun `credentials path must be explicit and absolute`() {
        val repository = createTempDirectory("jaiqal-repository-")
        val destination = createTempDirectory("jaiqal-credentials-").resolve("device.credentials")

        assertFailsWith<IllegalArgumentException> { requiredCredentialsPath(emptyMap(), repository) }
        assertFailsWith<IllegalArgumentException> {
            requiredCredentialsPath(mapOf("DEVICE_CREDENTIALS_FILE" to "relative.credentials"), repository)
        }
        assertFailsWith<IllegalArgumentException> {
            requiredCredentialsPath(mapOf("DEVICE_CREDENTIALS_FILE" to destination.resolveSibling("device.secret").toString()), repository)
        }
        assertTrue(
            requiredCredentialsPath(
                mapOf("DEVICE_CREDENTIALS_FILE" to destination.toString()),
                repository,
            ).isAbsolute,
        )
    }

    @Test
    fun `credentials path must resolve outside repository`() {
        val repository = createTempDirectory("jaiqal-repository-")
        val secretDirectory = Files.createDirectories(repository.resolve("server/private"))

        assertFailsWith<IllegalArgumentException> {
            requiredCredentialsPath(
                mapOf("DEVICE_CREDENTIALS_FILE" to secretDirectory.resolve("device.credentials").toString()),
                repository,
            )
        }
    }

    @Test
    fun `ancestor symlink cannot hide credentials path inside repository`() {
        val repository = createTempDirectory("jaiqal-repository-")
        Files.createDirectories(repository.resolve("server/private"))
        val externalDirectory = createTempDirectory("jaiqal-credentials-")
        val repositoryAlias = externalDirectory.resolve("checkout-alias")
        Files.createSymbolicLink(repositoryAlias, repository)

        assertFailsWith<IllegalArgumentException> {
            requiredCredentialsPath(
                mapOf(
                    "DEVICE_CREDENTIALS_FILE" to repositoryAlias
                        .resolve("server/private/device.credentials")
                        .toString(),
                ),
                repository,
            )
        }
    }

    @Test
    fun `repository root is mandatory and canonicalized`() {
        assertFailsWith<IllegalArgumentException> { requiredRepositoryRoot(null) }
        assertFailsWith<IllegalArgumentException> { requiredRepositoryRoot("relative") }

        val repository = createTempDirectory("jaiqal-repository-")
        assertEquals(repository.toRealPath(), requiredRepositoryRoot(repository.toString()))
    }
}
