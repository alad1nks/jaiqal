package com.alad1nks.jaiqal.plugins

import com.alad1nks.jaiqal.auth.DeviceTokenAuthenticator
import com.alad1nks.jaiqal.auth.FirebaseTokenVerificationException
import com.alad1nks.jaiqal.auth.FirebaseTokenVerifier
import com.alad1nks.jaiqal.auth.UserPrincipal
import com.alad1nks.jaiqal.users.FirebaseIdentityConflictException
import com.alad1nks.jaiqal.users.FirebaseUserIdentityService
import com.alad1nks.jaiqal.users.UnknownFirebaseIdentityException
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.plugins.callid.callId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant

const val FIREBASE_USER_AUTH = "firebase-user"
const val DEVICE_TOKEN_AUTH = "device-token"

fun Application.configureAuthentication(
    deviceTokenAuthenticator: DeviceTokenAuthenticator,
    firebaseTokenVerifier: FirebaseTokenVerifier? = null,
    firebaseUsers: FirebaseUserIdentityService? = null,
    clock: Clock = Clock.systemUTC(),
) {
    install(Authentication) {
        bearer(FIREBASE_USER_AUTH) {
            realm = "jaiqal-users"
            authSchemes("Bearer")
            authenticate { credential ->
                val token = credential.token.takeIf(String::isNotBlank) ?: return@authenticate null
                val verifier = firebaseTokenVerifier ?: return@authenticate null
                val users = firebaseUsers ?: return@authenticate null
                val verified = try {
                    verifier.verify(token)
                } catch (_: FirebaseTokenVerificationException) {
                    return@authenticate null
                }
                if (!verified.expiresAt.isAfter(Instant.now(clock))) return@authenticate null
                val user = try {
                    withContext(Dispatchers.IO) { users.resolve(verified, callId) }
                } catch (_: UnknownFirebaseIdentityException) {
                    return@authenticate null
                } catch (_: FirebaseIdentityConflictException) {
                    return@authenticate null
                }
                UserPrincipal(
                    userId = user.id,
                    firebaseUid = verified.uid,
                    email = verified.email,
                    emailVerified = verified.emailVerified,
                    expiresAt = verified.expiresAt,
                )
            }
        }

        bearer(DEVICE_TOKEN_AUTH) {
            realm = "jaiqal-devices"
            authSchemes("Device")
            authenticate { credential ->
                deviceTokenAuthenticator.authenticate(credential.token)
            }
        }
    }
}
