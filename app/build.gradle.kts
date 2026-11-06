import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The only place to change for a new version; versionCode follows (1.2.3 -> 10203).
val appVersion = "1.0.1"
val appVersionCode = appVersion.split(".").map(String::toInt).let { (major, minor, patch) ->
    major * 10_000 + minor * 100 + patch
}

// Signing key for releases, kept out of git (see "Publishing a release" in the README).
val keystoreFile = rootProject.file("keystore.properties")
val keystore = Properties().apply { if (keystoreFile.exists()) keystoreFile.inputStream().use(::load) }

android {
    namespace = "se.graw.wallintercom"
    compileSdk = 35

    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
        }
    }

    defaultConfig {
        applicationId = "se.graw.wallintercom"
        // Android 5.0 and newer (the WebRTC library's minimum).
        minSdk = 21
        // Sideloaded, never on the Play Store, so keep Android 10 behaviour.
        targetSdk = 29
        versionCode = appVersionCode
        versionName = appVersion
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
