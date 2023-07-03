plugins {
    `java-library`
    kotlin("jvm")
}

dependencies {
    compileOnly(libs.kotlin.stdlib)
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
