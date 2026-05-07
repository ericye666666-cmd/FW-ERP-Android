plugins {
    id("com.android.application")
    kotlin("android")
}

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

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
}
