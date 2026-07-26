package com.alad1nks.jaiqal.plugins

import com.alad1nks.jaiqal.auth.DeviceTokenAuthenticator
import com.alad1nks.jaiqal.auth.FirebaseUserAuthenticator
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer

const val FIREBASE_USER_AUTH = "firebase-user"
const val DEVICE_TOKEN_AUTH = "device-token"

fun Application.configureAuthentication(
    firebase: FirebaseUserAuthenticator,
    deviceTokenAuthenticator: DeviceTokenAuthenticator,
) {
    install(Authentication) {
        bearer(FIREBASE_USER_AUTH) {
            realm = "jaiqal-users"
            authSchemes("Bearer")
            authenticate { credential -> firebase.authenticate(credential.token) }
        }
        bearer(DEVICE_TOKEN_AUTH) {
            realm = "jaiqal-devices"
            authSchemes("Device")
            authenticate { credential -> deviceTokenAuthenticator.authenticate(credential.token) }
        }
    }
}
