import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    listOf(iosArm64(), iosSimulatorArm64()).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
            binaryOption("bundleId", "com.alad1nks.jaiqal.Shared")
            export(project(":core:data"))
        }
    }

    jvm()
    js { browser() }
    @OptIn(ExperimentalWasmDsl::class)
    wasmJs { browser() }

    android {
        namespace = "com.alad1nks.jaiqal.app.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        compilerOptions { jvmTarget = JvmTarget.JVM_11 }
        androidResources { enable = true }
        withHostTest { isIncludeAndroidResources = true }
        withDeviceTestBuilder { sourceSetTreeName = "test" }.configure {
            instrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":core:data"))
            implementation(project(":core"))
            implementation(project(":core:api-contract"))
            implementation(project(":core:designsystem"))
            implementation(project(":feature:alerts"))
            implementation(project(":feature:auth"))
            implementation(project(":feature:devices"))
            implementation(project(":feature:plants"))
            implementation(project(":feature:settings"))
            implementation(project(":resources"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.navigation.compose)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.koin.core)
            implementation(libs.koin.compose)
            implementation(libs.koin.compose.viewmodel)
        }
        commonTest.dependencies {
            implementation(project(":core:testing"))
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            implementation(libs.androidx.appcompat)
            implementation(libs.androidx.core.ktx)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.compose.uiTooling)
            implementation(libs.ktor.client.okhttp)
            implementation(project.dependencies.platform(libs.firebase.bom))
            implementation(libs.firebase.auth)
        }
        iosMain.dependencies { implementation(libs.ktor.client.darwin) }
        jvmTest.dependencies { implementation(libs.sqldelight.sqliteDriver) }
        jsMain.dependencies { implementation(libs.wrappers.browser) }
    }
}

dependencies { androidRuntimeClasspath(libs.compose.uiTooling) }
