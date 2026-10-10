plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ch.foodlogger.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "ch.foodlogger.app"
        // Health Connect's Jetpack client requires API 26+.
        minSdk = 26
        targetSdk = 36
        // CI passes the run number so every published build has a higher versionCode.
        val build = (project.findProperty("buildNumber") as String?)?.toInt() ?: 1
        versionCode = build
        versionName = "0.1.$build"
    }

    // The release key lives only in GitHub Actions secrets; CI decodes it to the file named by
    // SIGNING_KEYSTORE. Without it (local builds, forks) the release APK is left unsigned.
    val releaseKeystore = System.getenv("SIGNING_KEYSTORE")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = "foodlogger"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("ch.foodlogger:core")

    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.activity:activity-compose:1.10.1")
    // Play services pulls in fragment 1.0; the ActivityResult API needs 1.3+ (lint InvalidFragmentVersionForActivityResult).
    implementation("androidx.fragment:fragment:1.8.5")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")

    implementation("androidx.health.connect:connect-client:1.1.0")
    // Google code scanner: Play services provides the camera UI, so no CAMERA permission is needed.
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    // On-device text recognition for nutrition labels, also delivered by Play services (keeps the APK small).
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    // Barcodes in saved photos (the code scanner above only works with the live camera).
    implementation("com.google.android.gms:play-services-mlkit-barcode-scanning:18.3.1")
    // Text of receipt PDFs shared from the Migros and Coop apps. Bouncy Castle is only needed for encrypted
    // PDFs and would add several MB, so it is left out; such a PDF is read like a photo instead.
    implementation("com.tom-roush:pdfbox-android:2.0.27.0") {
        exclude(group = "org.bouncycastle")
    }
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
}
