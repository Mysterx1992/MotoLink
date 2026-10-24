import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.motolink.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "it.motolink.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 34
        versionName = "1.8"
    }

    buildTypes {
        // Only the draft combined-validation branch produces this isolated APK.
        // It installs next to the official signed MotoLink V1.8 without
        // replacing it or deleting its existing app data.
        debug {
            applicationIdSuffix = ".preplaytest"
            versionNameSuffix = "-preplay-test"
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget("17")
    }
}


dependencies {
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // 16-KiB Android native page fix: LiteRT 1.4.2 preserves the existing
    // org.tensorflow.lite.Interpreter API, model and roundabout classifier behavior.
    // Do not add litert-support: its native image-processing utilities are not used here.
    implementation("com.google.ai.edge.litert:litert:1.4.2")

    // V1.2 fallback: Google Code Scanner remains the first choice. CameraX is loaded
    // only when Play services cannot provide its scanner UI on a specific phone.
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.camera:camera-camera2:1.6.2")
    implementation("androidx.camera:camera-lifecycle:1.6.2")
    implementation("androidx.camera:camera-view:1.6.2")
}
