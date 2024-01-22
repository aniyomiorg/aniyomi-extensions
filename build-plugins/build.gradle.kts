plugins {
    `kotlin-dsl`
}

dependencies {
    implementation(libs.gradle.agp)
    implementation(libs.gradle.kotlin)
    implementation(libs.gradle.kotlin.serialization)
    // Workaround: https://github.com/gradle/gradle/issues/15383#issuecomment-779893192
    implementation(files(libs.javaClass.superclass.protectionDomain.codeSource.location))
}

kotlin {
    // To access AndroidConfig
    sourceSets.getByName("main").kotlin.srcDir("../buildSrc/src/main/kotlin")
}
