plugins {
    id("com.android.library")
}

android {
    compileSdk = AndroidConfig.compileSdk
    namespace = AndroidConfig.coreNamespace

    defaultConfig {
        minSdk = AndroidConfig.minSdk
    }

    sourceSets {
        named("main") {
            manifest.srcFile("AndroidManifest.xml")
            res.setSrcDirs(listOf("res"))
        }
    }

    libraryVariants.all {
        generateBuildConfigProvider?.configure {
            enabled = false
        }
    }
}
