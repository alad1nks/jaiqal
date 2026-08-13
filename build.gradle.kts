import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction

plugins {
    // this is necessary to avoid the plugins to be loaded multiple times
    // in each subproject's classloader
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidMultiplatformLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.ktor) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.googleServices) apply false
    alias(libs.plugins.firebaseCrashlytics) apply false
}

abstract class VerifyReleasePlatformConfiguration : DefaultTask() {
    @get:InputFile abstract val androidManifestFile: RegularFileProperty
    @get:InputFile abstract val androidBuildFile: RegularFileProperty
    @get:InputFile abstract val iosReleaseInfoFile: RegularFileProperty
    @get:InputFile abstract val iosDebugInfoFile: RegularFileProperty
    @get:InputFile abstract val iosProjectFile: RegularFileProperty

    @TaskAction
    fun verify() {
        val androidManifest = androidManifestFile.get().asFile.readText()
        val androidBuild = androidBuildFile.get().asFile.readText()
        val iosReleaseInfo = iosReleaseInfoFile.get().asFile.readText()
        val iosDebugInfo = iosDebugInfoFile.get().asFile.readText()
        val iosProject = iosProjectFile.get().asFile.readText()

        check("android:scheme=\"jaiqal\" android:host=\"plants\"" in androidManifest) {
            "Android plant deep link is not registered"
        }
        check("manifestPlaceholders[\"crashlyticsCollectionEnabled\"] = \"true\"" in androidBuild) {
            "Android release Crashlytics collection is not enabled"
        }
        check("FirebaseCrashlyticsCollectionEnabled</key>\n\t<true/>" in iosReleaseInfo) {
            "iOS release Crashlytics collection is not enabled"
        }
        check("FirebaseCrashlyticsCollectionEnabled</key>\n\t<false/>" in iosDebugInfo) {
            "iOS debug Crashlytics collection must stay disabled"
        }
        check("Upload Crashlytics Symbols" in iosProject && "CONFIGURATION\\\" != \\\"Release" in iosProject) {
            "iOS release dSYM upload phase is not configured"
        }
    }
}

tasks.register<VerifyReleasePlatformConfiguration>("verifyReleasePlatformConfiguration") {
    group = "verification"
    description = "Verifies frontend platform deep-link and release Crashlytics configuration."
    androidManifestFile.set(layout.projectDirectory.file("app/androidApp/src/main/AndroidManifest.xml"))
    androidBuildFile.set(layout.projectDirectory.file("app/androidApp/build.gradle.kts"))
    iosReleaseInfoFile.set(layout.projectDirectory.file("app/iosApp/iosApp/Info.plist"))
    iosDebugInfoFile.set(layout.projectDirectory.file("app/iosApp/iosApp/Info-Debug.plist"))
    iosProjectFile.set(layout.projectDirectory.file("app/iosApp/iosApp.xcodeproj/project.pbxproj"))
}
