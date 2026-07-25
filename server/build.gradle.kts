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

dependencies {
    implementation(project(":core:api-contract"))
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.logback)
    implementation(libs.postgresql)
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
}
