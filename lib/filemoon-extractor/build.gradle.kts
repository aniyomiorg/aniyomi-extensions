plugins {
    id("com.android.library")
    id("kotlinx-serialization")
    kotlin("android")
}

android {
    compileSdk = AndroidConfig.compileSdk
    namespace = "eu.kanade.tachiyomi.lib.filemoonextractor"

    defaultConfig {
        minSdk = AndroidConfig.minSdk
    }
}

dependencies {
    implementation("dev.datlag.jsunpacker:jsunpacker:1.0.1") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    }
    compileOnly(libs.bundles.common)
}
