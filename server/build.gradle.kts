import java.util.zip.ZipFile

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
    systemProperty(
        "jaiqal.provisioning.repositoryRoot",
        rootProject.layout.projectDirectory.asFile.absolutePath,
    )
}

tasks.register<JavaExec>("migrateDatabase") {
    group = "application"
    description = "Applies Flyway migrations with dedicated migration credentials"
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass = "com.alad1nks.jaiqal.infrastructure.database.MigrateDatabaseKt"
}

val verifyFirebaseStorageRuntimeGraph = tasks.register("verifyFirebaseStorageRuntimeGraph") {
    group = "verification"
    description = "Fails if the excluded Google Cloud Storage client returns to server runtime"

    val runtimeClasspath = configurations.named("runtimeClasspath")
    inputs.files(runtimeClasspath)

    doLast {
        val runtimeArtifacts = inputs.files.files
        val forbiddenArtifacts = runtimeArtifacts
            .filter { it.name.startsWith("google-cloud-storage-") }
            .map { it.name }
            .sorted()

        val forbiddenClassPrefix = "com/google/cloud/storage/"
        val jarsWithStorageClasses = runtimeArtifacts
            .asSequence()
            .filter { it.isFile && it.extension == "jar" }
            .filter { jar ->
                ZipFile(jar).use { archive ->
                    archive.entries().asSequence().any { entry ->
                        !entry.isDirectory && entry.name.startsWith(forbiddenClassPrefix)
                    }
                }
            }
            .map { it.name }
            .sorted()
            .toList()

        check(forbiddenArtifacts.isEmpty() && jarsWithStorageClasses.isEmpty()) {
            buildString {
                append("Firebase Storage runtime graph must remain excluded.")
                if (forbiddenArtifacts.isNotEmpty()) {
                    append(" Forbidden artifacts: ${forbiddenArtifacts.joinToString()}.")
                }
                if (jarsWithStorageClasses.isNotEmpty()) {
                    append(" JARs containing Google Cloud Storage classes: ${jarsWithStorageClasses.joinToString()}.")
                }
            }
        }
    }
}

tasks.named("check") {
    dependsOn(verifyFirebaseStorageRuntimeGraph)
}

dependencies {
    implementation(project(":core:api-contract"))
    // Security floors for transitive dependencies that still lag in current upstream releases.
    implementation(platform(libs.jackson.bom))
    implementation(platform(libs.netty.bom))
    implementation(platform(libs.opentelemetry.bom))
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
    // FirebaseOptions instantiates JacksonFactory; Storage previously supplied this accidentally.
    implementation(libs.google.http.client.jackson2)
    implementation(libs.firebase.admin) {
        // The server uses Firebase Auth only. Keep Firestore because its types are part of
        // FirebaseOptions' binary surface, but do not ship the independent Storage client graph.
        exclude(group = "com.google.cloud", module = "google-cloud-storage")
    }
    implementation(libs.ktor.serializationKotlinxJson)
    implementation(libs.ktor.serverAuth)
    implementation(libs.ktor.serverBodyLimit)
    implementation(libs.ktor.serverCallId)
    implementation(libs.ktor.serverCallLogging)
    implementation(libs.ktor.serverContentNegotiation)
    implementation(libs.ktor.serverCors)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.serverRateLimit)
    implementation(libs.ktor.serverStatusPages)
    constraints {
        implementation(libs.httpclient5)
        implementation(libs.httpcore5)
        implementation(libs.httpcore5.h2)
        // Removing Storage must not silently downgrade Firebase Auth's HTTP/auth stack.
        implementation(libs.google.oauth.client)
        implementation(libs.google.http.client.apache.v2)
    }
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
    testImplementation(libs.testcontainers.postgresql)
}
