plugins {
    `java-library`
    kotlin("jvm")
}

versionCatalogs
    .named("libs")
    .findLibrary("kotlin-stdlib")
    .ifPresent { stdlib ->
        dependencies {
            compileOnly(stdlib)
        }
    }
