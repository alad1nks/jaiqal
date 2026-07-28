import org.jetbrains.kotlin.gradle.dsl.JvmTarget

val productionApiBaseUrl = providers.gradleProperty("JAIQAL_PRODUCTION_API_BASE_URL")
    .orElse("https://api.example.invalid")
val localApiBaseUrl = providers.gradleProperty("JAIQAL_LOCAL_API_BASE_URL")
    .orElse("http://10.0.2.2:8080")

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(project(":app:shared"))

    implementation(libs.androidx.activity.compose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "com.alad1nks.jaiqal"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.alad1nks.jaiqal"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        debug {
            buildConfigField("String", "API_BASE_URL", "\"${localApiBaseUrl.get()}\"")
            buildConfigField("String", "APP_ENVIRONMENT", "\"local\"")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("String", "API_BASE_URL", "\"${productionApiBaseUrl.get()}\"")
            buildConfigField("String", "APP_ENVIRONMENT", "\"production\"")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}
