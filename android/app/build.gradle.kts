plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val keystorePath = System.getenv("KEYSTORE_PATH").orEmpty()
val keystorePassword = System.getenv("KEYSTORE_PASSWORD").orEmpty()

android {
    namespace = "com.dztrafic.carte"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.dztrafic.carte"
        minSdk = 24
        targetSdk = 34
        versionCode = providers.gradleProperty("versionCodeOverride").map { it.toInt() }.getOrElse(8)
        versionName = "0.8"
        buildConfigField("String", "ROUTING_URL", "\"${providers.gradleProperty("routingUrl").getOrElse("https://github.com/SamEntertainmentGroup/tariki/releases/download/routes-latest/algeria-routing.bin.gz")}\"")
        buildConfigField("String", "ALERTS_URL", "\"${providers.gradleProperty("alertsUrl").getOrElse("https://jarti.app/wp-admin/admin-ajax.php")}\"")
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    // Clé de signature fixe (fournie par GitHub) : indispensable pour mettre à jour par-dessus l'appli déjà installée.
    signingConfigs {
        if (keystorePath.isNotEmpty() && keystorePassword.isNotEmpty()) {
            create("tariki") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = "tariki"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfigs.findByName("tariki")?.let { signingConfig = it }
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
