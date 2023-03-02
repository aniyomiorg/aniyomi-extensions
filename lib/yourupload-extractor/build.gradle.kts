plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    compileSdk = AndroidConfig.compileSdk
    namespace = "eu.kanade.tachiyomi.lib.youruploadextractor"

    defaultConfig {
        minSdk = AndroidConfig.minSdk
        targetSdk = AndroidConfig.targetSdk
    }
}

dependencies {
    compileOnly(libs.kotlin.stdlib)
    compileOnly(libs.okhttp)
    compileOnly(libs.aniyomi.lib)
    compileOnly(libs.jsoup)
}
// BUMPS: 0
