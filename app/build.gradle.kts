plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.farks.autopilot"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.farks.autopilot"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    // Firma: si hay un keystore en las variables de entorno (CI) se usa; si no, firma de depuración.
    signingConfigs {
        create("release") {
            val ks = System.getenv("FARKS_KEYSTORE")
            if (ks != null && file(ks).exists()) {
                storeFile = file(ks)
                storePassword = System.getenv("FARKS_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("FARKS_KEY_ALIAS")
                keyPassword = System.getenv("FARKS_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (System.getenv("FARKS_KEYSTORE") != null) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    // El modelo .tflite (si existe) no se debe comprimir para poder mapearlo en memoria.
    androidResources { noCompress += "tflite" }
    packaging { jniLibs.useLegacyPackaging = false }
}

dependencies {
    implementation(project(":core"))
    implementation(project(":vision"))
    implementation("org.opencv:opencv:4.9.0")
    implementation("com.google.ai.edge.litert:litert:1.0.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
}
