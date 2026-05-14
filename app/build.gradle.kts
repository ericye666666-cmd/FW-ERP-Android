plugins {
    id("com.android.application")
    kotlin("android")
}

fun signingEnv(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }
fun buildConfigString(value: String): String = "\"$value\""

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
        applicationId = "com.directloop.erp.pda"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    flavorDimensions += "environment"
    productFlavors {
        create("production") {
            dimension = "environment"
            applicationId = "com.directloop.erp.pda"
            resValue("string", "app_name", "Direct Loop PDA")
            buildConfigField("String", "FW_ERP_APP_URL", buildConfigString("https://directlooperp.com/app/"))
            buildConfigField("String", "FW_ERP_HOST", buildConfigString("directlooperp.com"))
            buildConfigField("String", "PDA_ENVIRONMENT", buildConfigString("production"))
            buildConfigField("String", "PDA_SPLASH_TITLE", buildConfigString("Direct Loop PDA"))
        }

        create("staging") {
            dimension = "environment"
            applicationId = "com.directloop.erp.pda.staging"
            versionNameSuffix = "-staging"
            resValue("string", "app_name", "Direct Loop PDA Staging")
            buildConfigField("String", "FW_ERP_APP_URL", buildConfigString("https://staging.directlooperp.com/app/"))
            buildConfigField("String", "FW_ERP_HOST", buildConfigString("staging.directlooperp.com"))
            buildConfigField("String", "PDA_ENVIRONMENT", buildConfigString("staging"))
            buildConfigField("String", "PDA_SPLASH_TITLE", buildConfigString("Direct Loop PDA STAGING"))
        }
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
    implementation(files("libs/ctaiotCtpl1.1.8.jar"))
}
