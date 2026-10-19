plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.dztrafic.carte"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dztrafic.carte"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "0.2"
        buildConfigField("String", "ALERTS_URL", "\"${providers.gradleProperty("alertsUrl").getOrElse("")}\"")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    androidResources {
        noCompress += "mbtiles"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("org.maplibre.gl:android-sdk:13.6.1")
}
