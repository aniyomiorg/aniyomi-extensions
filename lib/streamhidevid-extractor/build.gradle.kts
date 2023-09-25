plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    compileSdk = AndroidConfig.compileSdk
    namespace = "eu.kanade.tachiyomi.lib.streamhidevidextractor"

    defaultConfig {
        minSdk = AndroidConfig.minSdk
    }
}

dependencies {
    compileOnly(libs.bundles.common)
    implementation("dev.datlag.jsunpacker:jsunpacker:1.0.1") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    }
}
