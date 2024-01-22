import org.gradle.accessors.dm.LibrariesForLibs

plugins {
    `java-library`
    kotlin("jvm")
}

val libs = the<LibrariesForLibs>()

dependencies {
    compileOnly(libs.kotlin.stdlib)
}
