plugins {
    id("com.android.application")
}

android {
    namespace = "it.motolink.vogenavbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "it.motolink.vogenavbridge"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "1.1-heartbeat-test"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core:1.17.0")
}
