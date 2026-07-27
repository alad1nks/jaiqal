package com.alad1nks.jaiqal.plugins

import com.alad1nks.jaiqal.auth.DeviceTokenAuthenticator
import com.alad1nks.jaiqal.auth.FirebaseTokenVerificationException
import com.alad1nks.jaiqal.auth.FirebaseTokenVerifier
import com.alad1nks.jaiqal.auth.UserPrincipal
import com.alad1nks.jaiqal.config.JwtConfig
import com.alad1nks.jaiqal.users.FirebaseIdentityConflictException
import com.alad1nks.jaiqal.users.FirebaseUserIdentityService
import com.alad1nks.jaiqal.users.UnknownFirebaseIdentityException
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val USER_JWT_AUTH = "user-jwt"
const val FIREBASE_USER_AUTH = "firebase-user"
const val DEVICE_TOKEN_AUTH = "device-token"

fun Application.configureAuthentication(
    config: JwtConfig,
    deviceTokenAuthenticator: DeviceTokenAuthenticator,
    firebaseTokenVerifier: FirebaseTokenVerifier? = null,
    firebaseUsers: FirebaseUserIdentityService? = null,
) {
    install(Authentication) {
        jwt(USER_JWT_AUTH) {
            realm = "jaiqal-users"
            verifier(
                JWT.require(Algorithm.HMAC256(config.secret))
                    .withIssuer(config.issuer)
                    .withAudience(config.audience)
                    .build(),
            )
            validate { credential ->
                credential.payload.subject
                    ?.takeIf(String::isNotBlank)
                    ?.let { JWTPrincipal(credential.payload) }
            }
            challenge { _, _ ->
                call.respondApiError(
                    status = HttpStatusCode.Unauthorized,
                    code = "UNAUTHORIZED",
                    message = "A valid user token is required",
                )
            }
        }

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
                val user = try {
                    withContext(Dispatchers.IO) { users.resolve(verified) }
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
