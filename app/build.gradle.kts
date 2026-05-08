plugins {
    id("com.android.application")
    kotlin("android")
}

fun signingEnv(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

val pdaDebugKeystorePath = signingEnv("PDA_DEBUG_KEYSTORE_PATH")
val pdaDebugKeystorePassword = signingEnv("PDA_DEBUG_KEYSTORE_PASSWORD")
val pdaDebugKeyAlias = signingEnv("PDA_DEBUG_KEY_ALIAS")
val pdaDebugKeyPassword = signingEnv("PDA_DEBUG_KEY_PASSWORD")
val pdaDebugSigningEnvComplete = listOf(
    pdaDebugKeystorePath,
    pdaDebugKeystorePassword,
    pdaDebugKeyAlias,
    pdaDebugKeyPassword,
).all { it != null }

android {
    namespace = "com.directloop.pda"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.directloop.pda"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        buildConfigField(
            "String",
            "FW_ERP_APP_URL",
            "\"https://fw-erp-34-35-52-250.nip.io/app/\"",
        )
        buildConfigField(
            "String",
            "FW_ERP_HOST",
            "\"fw-erp-34-35-52-250.nip.io\"",
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    if (pdaDebugSigningEnvComplete) {
        signingConfigs.create("pdaDebug") {
            storeFile = file(pdaDebugKeystorePath!!)
            storePassword = pdaDebugKeystorePassword
            keyAlias = pdaDebugKeyAlias
            keyPassword = pdaDebugKeyPassword
        }
    }

    buildTypes {
        val debug = getByName("debug")
        if (pdaDebugSigningEnvComplete) {
            debug.signingConfig = signingConfigs.getByName("pdaDebug")
        }
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
}
