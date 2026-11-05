import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.wallintercom"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.wallintercom"
        minSdk = 29
        // Sideloaded onto Android 10 tablets only, so target Android 10 behaviour.
        targetSdk = 29
        versionCode = 1
        versionName = "1.0"
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
            // ./build.sh -Pemulator  -> also include x86_64 for testing in the emulator
            if (project.hasProperty("emulator")) abiFilters += "x86_64"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("io.github.webrtc-sdk:android:150.7871.04")
}
