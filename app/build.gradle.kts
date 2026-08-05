plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// One place for the version. `versionName` is what the window, the notification
// and every recording's header report, so it has to identify the exact tree the
// APK came from -- a bare "0.2.0" on a sideloaded debug build tells you nothing
// about which of six builds is actually on the phone. Same reasoning as the
// firmware's TRAILINK_VERSION.
val appVersion = "0.2.0"
val appVersionCode = 2

fun gitDescribe(): String {
    fun run(vararg cmd: String): String = try {
        val p = ProcessBuilder(*cmd).directory(rootDir).redirectErrorStream(true).start()
        p.inputStream.bufferedReader().use { it.readText() }.trim()
    } catch (t: Exception) {
        ""
    }
    val sha = run("git", "rev-parse", "--short", "HEAD")
    if (sha.isEmpty()) return ""
    // Scoped to this directory: unrelated edits elsewhere in the parent repo
    // (map specs, firmware worktrees) must not mark the app build dirty.
    val dirty = if (run("git", "status", "--porcelain", "--", ".").isNotEmpty()) "-dirty" else ""
    return "-g$sha$dirty"
}

android {
    namespace = "org.trailink.gpsbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.trailink.gpsbridge"
        minSdk = 31
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Debug builds carry the commit they were built from.
            versionNameSuffix = gitDescribe()
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

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // FileProvider for the share intent. Only dependency the app needs; the UI is
    // plain framework views, no AppCompat, no Compose.
    // Pinned to the last release that compiles against SDK 36 (Android 16),
    // which is what this app targets. 1.19.0 demands SDK 37 / AGP 9.
    implementation("androidx.core:core-ktx:1.17.0")

    // The 19-byte encoder, the transfer wire format and the tile-fetch state
    // machine are pure JVM code, so they are checked on the laptop rather than
    // only on the phone. `unitTests.isReturnDefaultValues` above is what lets
    // classes that call android.util.Log run here: without it every log line in
    // a class under test throws "not mocked", which would push the state machine
    // out of reach of the tests that matter most for it.
    testImplementation("junit:junit:4.13.2")
}
