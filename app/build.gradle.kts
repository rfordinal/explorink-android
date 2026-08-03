plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.trailink.gpsbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.trailink.gpsbridge"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
        }
    }

    buildFeatures {
        viewBinding = false
        buildConfig = true
    }
}

dependencies {
    // FileProvider for the share intent. Only dependency the app needs; the UI is
    // plain framework views, no AppCompat, no Compose.
    // Pinned to the last release that compiles against SDK 36 (Android 16),
    // which is what this app targets. 1.19.0 demands SDK 37 / AGP 9.
    implementation("androidx.core:core-ktx:1.17.0")

    // The 19-byte encoder is pure JVM code, so it is checked on the laptop
    // rather than only on the phone.
    testImplementation("junit:junit:4.13.2")
}
