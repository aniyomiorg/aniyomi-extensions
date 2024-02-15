plugins {
    id("lib-multisrc")
}

baseVersionCode = 1

dependencies {
    implementation(project(":lib:megacloud-extractor"))
    implementation(project(":lib:streamtape-extractor"))
}
