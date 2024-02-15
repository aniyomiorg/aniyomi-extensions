plugins {
    `java-library`
    kotlin("jvm")
}

versionCatalogs
    .named("libs")
    .findBundle("common")
    .ifPresent { common ->
        dependencies {
            implementation(common)
        }
    }
