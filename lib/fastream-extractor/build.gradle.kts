plugins {
    id("lib-android")
}

dependencies {
    implementation("dev.datlag.jsunpacker:jsunpacker:1.0.1") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    }
    implementation(project(":lib:playlist-utils"))
}
