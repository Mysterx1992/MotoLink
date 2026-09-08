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
        versionCode = 1
        versionName = "1.0-test"
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
