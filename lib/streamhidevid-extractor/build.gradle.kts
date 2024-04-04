plugins {
    id("lib-android")
}

dependencies {
    implementation(project(":lib:playlist-utils"))
    implementation("dev.datlag.jsunpacker:jsunpacker:1.0.1") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    }
}
