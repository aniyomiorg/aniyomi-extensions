plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    compileSdk = AndroidConfig.compileSdk

    defaultConfig {
        minSdk = AndroidConfig.minSdk
        targetSdk = AndroidConfig.targetSdk
    }
}

repositories {
    mavenCentral()
}

dependencies {
    compileOnly(libs.kotlin.stdlib)
    compileOnly(libs.okhttp)
    compileOnly(libs.aniyomi.lib)
    compileOnly(libs.jsoup)
    compileOnly(libs.kotlin.json)
    implementation("dev.datlag.jsunpacker:jsunpacker:1.0.1")
}