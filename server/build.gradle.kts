plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.ktor)
}

group = "com.alad1nks.jaiqal"
version = "1.0.0"
application {
    mainClass = "com.alad1nks.jaiqal.ApplicationKt"
}

tasks.register<JavaExec>("provisionDevice") {
    group = "application"
    description = "Creates an unclaimed device and one-time claim code"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.alad1nks.jaiqal.devices.ProvisionDeviceKt"
}

dependencies {
    implementation(project(":core:api-contract"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logback)
    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.javaTime)
    implementation(libs.exposed.json)
    implementation(libs.firebase.admin)
    implementation(libs.argon2)
    implementation(libs.ktor.serializationKotlinxJson)
    implementation(libs.ktor.serverAuth)
    implementation(libs.ktor.serverAuthJwt)
    implementation(libs.ktor.serverCallId)
    implementation(libs.ktor.serverCallLogging)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serverCors)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverStatusPages)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.testcontainers.postgresql)
}
