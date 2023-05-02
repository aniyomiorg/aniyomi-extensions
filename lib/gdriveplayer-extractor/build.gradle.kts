plugins {
    id("com.android.library")
    kotlin("android")
    id("kotlinx-serialization")
}

android {
    compileSdk = AndroidConfig.compileSdk
    namespace = "eu.kanade.tachiyomi.lib.gdriveplayerextractor"

    defaultConfig {
        minSdk = AndroidConfig.minSdk
        targetSdk = AndroidConfig.targetSdk
    }
}

dependencies {
    compileOnly(libs.bundles.common)
    implementation(project(":lib-cryptoaes"))
    implementation(project(":lib-unpacker"))
}
