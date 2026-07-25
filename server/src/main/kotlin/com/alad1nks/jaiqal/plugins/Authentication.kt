package com.alad1nks.jaiqal.plugins

import com.alad1nks.jaiqal.auth.DeviceTokenAuthenticator
import com.alad1nks.jaiqal.config.JwtConfig
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.auth.jwt.JWTPrincipal
import io.ktor.server.auth.jwt.jwt

const val USER_JWT_AUTH = "user-jwt"
const val DEVICE_TOKEN_AUTH = "device-token"

fun Application.configureAuthentication(
    config: JwtConfig,
    deviceTokenAuthenticator: DeviceTokenAuthenticator,
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

        bearer(DEVICE_TOKEN_AUTH) {
            realm = "jaiqal-devices"
            authenticate { credential ->
                deviceTokenAuthenticator.authenticate(credential.token)
            }
        }
    }
}
