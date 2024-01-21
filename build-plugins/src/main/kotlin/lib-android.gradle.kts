import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    id("com.android.library")
    kotlin("android")
    id("kotlinx-serialization")
}

android {
    compileSdk = AndroidConfig.compileSdk

    defaultConfig {
        minSdk = AndroidConfig.minSdk
    }

    namespace = "eu.kanade.tachiyomi.lib.${name.removePrefix("lib-").replace("-", "")}"
}

val libs = the<LibrariesForLibs>()

dependencies {
    compileOnly(libs.bundles.common)
}
