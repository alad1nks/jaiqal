package com.alad1nks.jaiqal.users

import com.alad1nks.jaiqal.api.contract.*
import com.alad1nks.jaiqal.config.JwtConfig
import com.alad1nks.jaiqal.devices.DeviceRecord
import com.alad1nks.jaiqal.infrastructure.database.DeviceTokens
import com.alad1nks.jaiqal.plants.PlantRecord
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import de.mkammerer.argon2.Argon2Factory
import java.security.SecureRandom
import java.time.Clock
import java.time.OffsetDateTime
import java.util.Date
import java.util.UUID

data class SessionRecord(val id: UUID, val user: UserRecord, val tokenHash: String, val expiresAt: OffsetDateTime)

interface UserApplicationStore {
    fun createUser(user: UserRecord): Boolean
    fun findUserByEmail(email: String): UserRecord?
    fun createSession(session: SessionRecord)
    fun rotateSession(oldHash: String, replacement: SessionRecord): UserRecord?
    fun revokeSession(hash: String, userId: UUID? = null): Boolean
    fun listPlants(userId: UUID): List<PlantRecord>
    fun findPlant(userId: UUID, plantId: UUID): PlantRecord?
    fun createPlant(plant: PlantRecord): PlantRecord
    fun updatePlant(userId: UUID, plantId: UUID, request: UpdatePlantRequest): PlantRecord?
    fun archivePlant(userId: UUID, plantId: UUID, at: OffsetDateTime): Boolean
    fun claimDevice(userId: UUID, plantId: UUID, claimHash: String, now: OffsetDateTime): DeviceRecord?
    fun listDevices(userId: UUID): List<DeviceRecord>
    fun findDevice(userId: UUID, deviceId: UUID): DeviceRecord?
    fun updateDevice(userId: UUID, deviceId: UUID, name: String?, plantId: UUID?): DeviceRecord?
    fun updateCalibration(userId: UUID, deviceId: UUID, dry: Int, wet: Int): DeviceRecord?
    fun rotateDeviceToken(userId: UUID, deviceId: UUID, tokenHash: String): DeviceRecord?
}

class PasswordHasher {
    fun hash(password: String): String = Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id)
        .hash(3, 65_536, 1, password.toCharArray())
    fun verify(hash: String, password: String): Boolean = runCatching {
        Argon2Factory.create(Argon2Factory.Argon2Types.ARGON2id).verify(hash, password.toCharArray())
    }.getOrDefault(false)
}

class UserApplicationService(
    private val store: UserApplicationStore,
    private val jwt: JwtConfig,
    private val passwordHasher: PasswordHasher = PasswordHasher(),
    private val clock: Clock = Clock.systemUTC(),
    private val random: SecureRandom = SecureRandom(),
) {
    fun register(request: RegisterRequest): AuthResponse {
        val email = normalizeEmail(request.email)
        validatePassword(request.password)
        val now = now()
        val user = UserRecord(UUID.randomUUID(), email, passwordHasher.hash(request.password), now)
        if (!store.createUser(user)) conflict("EMAIL_ALREADY_REGISTERED", "An account with this email already exists")
        return issueSession(user)
    }

    fun login(request: LoginRequest): AuthResponse {
        val user = store.findUserByEmail(normalizeEmail(request.email))
        if (user == null || !passwordHasher.verify(user.passwordHash, request.password)) unauthorized("INVALID_CREDENTIALS", "Email or password is invalid")
        return issueSession(user)
    }

    fun refresh(request: RefreshRequest): AuthResponse {
        val token = newToken()
        val replacement = SessionRecord(UUID.randomUUID(), placeholderUser, tokenHash(token), now().plusSeconds(jwt.refreshTokenSeconds))
        val user = store.rotateSession(tokenHash(request.refreshToken), replacement)
            ?: unauthorized("INVALID_REFRESH_TOKEN", "Refresh token is invalid or has already been used")
        return authResponse(user, token)
    }

    fun logout(userId: UUID, request: LogoutRequest) {
        if (!store.revokeSession(tokenHash(request.refreshToken), userId)) unauthorized("INVALID_REFRESH_TOKEN", "Refresh token is invalid")
    }

    fun listPlants(userId: UUID) = store.listPlants(userId).map(::plantResponse)
    fun getPlant(userId: UUID, id: UUID) = store.findPlant(userId, id)?.let(::plantResponse) ?: notFound()
    fun createPlant(userId: UUID, request: CreatePlantRequest): PlantResponse {
        val name = requiredName(request.name)
        val record = PlantRecord(UUID.randomUUID(), userId, name, clean(request.species), clean(request.imageUrl), now())
        return plantResponse(store.createPlant(record))
    }
    fun updatePlant(userId: UUID, id: UUID, request: UpdatePlantRequest): PlantResponse {
        if (request.name == null && request.species == null && request.imageUrl == null) bad("EMPTY_UPDATE", "At least one field is required")
        request.name?.let(::requiredName)
        return store.updatePlant(userId, id, request)?.let(::plantResponse) ?: notFound()
    }
    fun archivePlant(userId: UUID, id: UUID) { if (!store.archivePlant(userId, id, now())) notFound() }

    fun claimDevice(userId: UUID, request: ClaimDeviceRequest): DeviceResponse {
        val plantId = uuid(request.plantId)
        if (request.claimCode.isBlank()) bad("INVALID_CLAIM_CODE", "Claim code is required")
        return store.claimDevice(userId, plantId, tokenHash(request.claimCode), now())?.let(::deviceResponse) ?: notFound()
    }
    fun listDevices(userId: UUID) = store.listDevices(userId).map(::deviceResponse)
    fun getDevice(userId: UUID, id: UUID) = store.findDevice(userId, id)?.let(::deviceResponse) ?: notFound()
    fun updateDevice(userId: UUID, id: UUID, request: UpdateDeviceRequest): DeviceResponse {
        if (request.name == null && request.plantId == null) bad("EMPTY_UPDATE", "At least one field is required")
        request.name?.let(::requiredName)
        val plant = request.plantId?.let(::uuid)
        return store.updateDevice(userId, id, request.name?.trim(), plant)?.let(::deviceResponse) ?: notFound()
    }
    fun updateCalibration(userId: UUID, id: UUID, request: UpdateCalibrationRequest): DeviceResponse {
        if (request.soilDryRaw < 0 || request.soilWetRaw < 0 || request.soilDryRaw == request.soilWetRaw) bad("INVALID_CALIBRATION", "Dry and wet values must be distinct non-negative values")
        return store.updateCalibration(userId, id, request.soilDryRaw, request.soilWetRaw)?.let(::deviceResponse) ?: notFound()
    }
    fun rotateDeviceToken(userId: UUID, id: UUID): RotateDeviceTokenResponse {
        val raw = newToken()
        val device = store.rotateDeviceToken(userId, id, DeviceTokens.hashHex(raw)) ?: notFound()
        return RotateDeviceTokenResponse(deviceResponse(device), raw)
    }

    private fun issueSession(user: UserRecord): AuthResponse {
        val raw = newToken()
        store.createSession(SessionRecord(UUID.randomUUID(), user, tokenHash(raw), now().plusSeconds(jwt.refreshTokenSeconds)))
        return authResponse(user, raw)
    }
    private fun authResponse(user: UserRecord, refresh: String): AuthResponse {
        val issued = now()
        val access = JWT.create().withIssuer(jwt.issuer).withAudience(jwt.audience).withSubject(user.id.toString())
            .withIssuedAt(Date.from(issued.toInstant())).withExpiresAt(Date.from(issued.plusSeconds(jwt.accessTokenSeconds).toInstant()))
            .sign(Algorithm.HMAC256(jwt.secret))
        return AuthResponse(UserResponse(user.id.toString(), user.email), access, refresh, jwt.accessTokenSeconds)
    }
    private fun newToken() = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
    private fun tokenHash(value: String) = DeviceTokens.hashHex(value)
    private fun now() = OffsetDateTime.now(clock)
    private fun normalizeEmail(value: String) = value.trim().lowercase().also { if (!it.matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))) bad("INVALID_EMAIL", "Email address is invalid") }
    private fun validatePassword(value: String) { if (value.length !in 10..1024) bad("INVALID_PASSWORD", "Password must contain at least 10 characters") }
    private fun requiredName(value: String) = value.trim().also { if (it.isEmpty() || it.length > 255) bad("INVALID_NAME", "Name must contain between 1 and 255 characters") }
    private fun clean(value: String?) = value?.trim()?.takeIf(String::isNotEmpty)
    private fun uuid(value: String) = runCatching { UUID.fromString(value) }.getOrElse { bad("INVALID_ID", "Identifier is invalid") }
    private fun plantResponse(v: PlantRecord) = PlantResponse(v.id.toString(), v.name, v.species, v.imageUrl, v.createdAt.toString())
    private fun deviceResponse(v: DeviceRecord) = DeviceResponse(v.id.toString(), v.plantId?.toString(), v.name, v.firmwareVersion, v.lastSeenAt?.toString(), v.soilDryRaw, v.soilWetRaw)
    private fun bad(code: String, message: String): Nothing = throw UserApiException(400, code, message)
    private fun unauthorized(code: String, message: String): Nothing = throw UserApiException(401, code, message)
    private fun conflict(code: String, message: String): Nothing = throw UserApiException(409, code, message)
    private fun notFound(): Nothing = throw UserApiException(404, "NOT_FOUND", "Resource was not found")
    private val placeholderUser = UserRecord(UUID(0, 0), "", "", OffsetDateTime.MIN)
}

class UserApiException(val status: Int, val code: String, override val message: String) : RuntimeException(message)
