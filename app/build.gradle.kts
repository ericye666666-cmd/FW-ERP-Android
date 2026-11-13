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

// Brand of the WebView shell. Default is the Direct Loop PDA; build the
// MEIJIAMEI mattress factory app with `./gradlew assembleDebug -Pbrand=meijiamei`.
// The factory app is the same shell pointed at its own YXSYSTEM instance, with
// its own app id, name and launcher icon (src/meijiamei).
val shellBrand = (findProperty("brand") as String?)?.trim()?.lowercase().orEmpty().ifBlank { "directloop" }
val isMeijiamei = shellBrand == "meijiamei"
require(shellBrand in setOf("directloop", "meijiamei")) { "Unknown brand: $shellBrand" }

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

    if (isMeijiamei) {
        defaultConfig.applicationId = "com.meijiamei.factory"
        // Until meijiamei.directlooperp.com has DNS the live server is only on
        // its nip.io address; pass -PmeijiameiHost=... to build for another host.
        val meijiameiHost = (project.findProperty("meijiameiHost") as String?)
            ?: "meijiamei-34-35-52-250.nip.io"
        defaultConfig.buildConfigField("String", "FW_ERP_APP_URL", "\"https://$meijiameiHost/app/\"")
        defaultConfig.buildConfigField("String", "FW_ERP_HOST", "\"$meijiameiHost\"")
        // Build-type source sets override main, so the brand's strings and
        // icon replace the Direct Loop ones without duplicate resources.
        listOf("debug", "release").forEach { type ->
            sourceSets.getByName(type).res.srcDir("src/meijiamei/res")
            sourceSets.getByName(type).manifest.srcFile("src/meijiamei/AndroidManifest.xml")
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
