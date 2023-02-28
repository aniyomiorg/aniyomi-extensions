plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false 
    alias(libs.plugins.kotlin.serialization) apply false 
    alias(libs.plugins.kotlinter) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.buildDir)
}
