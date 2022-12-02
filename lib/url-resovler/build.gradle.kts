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
    implementation(project(":lib-dood-extractor"))
    implementation(project(":lib-fembed-extractor"))
    implementation(project(":lib-okru-extractor"))
    implementation(project(":lib-streamsb-extractor"))
    implementation(project(":lib-streamtape-extractor"))
    implementation("dev.datlag.jsunpacker:jsunpacker:1.0.1")

}