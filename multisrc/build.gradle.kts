plugins {
    id("com.android.library")
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

android {
    compileSdk = AndroidConfig.compileSdk
    namespace = AndroidConfig.multisrcNamespace

    defaultConfig {
        minSdk = 29
    }

    kotlinOptions {
        freeCompilerArgs += "-opt-in=kotlinx.serialization.ExperimentalSerializationApi"
    }
}

configurations {
    compileOnly {
        isCanBeResolved = true
    }
}

dependencies {
    compileOnly(libs.bundles.common)

    // Implements all shared-extractors on the extensions generator
    // Note that this does not mean that generated sources are going to
    // implement them too; this is just to be able to compile and generate sources.
    rootProject.subprojects
        .filter { it.path.startsWith(":lib:") }
        .forEach(::implementation)
}

tasks {
    register<JavaExec>("generateExtensions") {
        classpath = configurations.compileOnly.get() +
            configurations.androidApis.get() + // android.jar path
            layout.buildDirectory.files("intermediates/aar_main_jar/debug/classes.jar") // jar made from this module

        // Default generator class, generates extensions for all themes.
        mainClass.set("generator.GeneratorMainKt")

        // Only generate extensions from a specified theme.
        if (project.hasProperty("theme")) {
            val theme = project.property("theme")
            val themeDir = file("src/main/java/eu/kanade/tachiyomi/multisrc/$theme")
            if (themeDir.isDirectory) {
                val className = themeDir.list()!!
                    .first { it.endsWith("Generator.kt") }
                    .removeSuffix(".kt")
                mainClass.set("eu.kanade.tachiyomi.multisrc.$theme.$className")
            }
        }

        workingDir = workingDir.parentFile // project root

        errorOutput = System.out // for GitHub workflow commands

        if (!logger.isInfoEnabled) {
            standardOutput = org.gradle.internal.io.NullOutputStream.INSTANCE
        }

        dependsOn("ktFormat", "ktLint", "assembleDebug")
    }

    register<org.jmailen.gradle.kotlinter.tasks.LintTask>("ktLint") {
        if (project.hasProperty("theme")) {
            val theme = project.property("theme")
            source(files("src/main/java/eu/kanade/tachiyomi/multisrc/$theme", "overrides/$theme"))
            return@register
        }
        source(files("src", "overrides"))
    }

    register<org.jmailen.gradle.kotlinter.tasks.FormatTask>("ktFormat") {
        if (project.hasProperty("theme")) {
            val theme = project.property("theme")
            source(files("src/main/java/eu/kanade/tachiyomi/multisrc/$theme", "overrides/$theme"))
            return@register
        }
        source(files("src", "overrides"))
    }
}
