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
        // Android 5.0 and newer (the WebRTC library's minimum).
        minSdk = 21
        // Sideloaded, never on the Play Store, so keep Android 10 behaviour.
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
    // 137 is the newest WebRTC build that loads on Android 5: from 144 on, the native library
    // lacks the DT_HASH section that Android 5's linker needs ("empty/missing DT_HASH").
    implementation("io.github.webrtc-sdk:android:137.7151.05")
    // EXIF rotation from a content:// stream; the platform version needs Android 7.
    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
