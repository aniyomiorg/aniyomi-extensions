plugins {
    id("com.android.library")
    id("kotlinx-serialization") // for the DTO
    kotlin("android")
}

android {
    compileSdk = AndroidConfig.compileSdk
    namespace = "eu.kanade.tachiyomi.lib.streamsbextractor"

    defaultConfig {
        minSdk = AndroidConfig.minSdk
    }
}

dependencies {
    compileOnly(libs.bundles.common)
}
