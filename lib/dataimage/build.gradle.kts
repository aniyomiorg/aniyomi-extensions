plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    compileSdk = AndroidConfig.compileSdk
    namespace = "eu.kanade.tachiyomi.lib.dataimage"

    defaultConfig {
        minSdk = AndroidConfig.minSdk
    }
}

dependencies {
    compileOnly(libs.bundles.common)
}
