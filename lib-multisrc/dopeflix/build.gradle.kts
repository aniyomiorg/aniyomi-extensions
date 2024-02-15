plugins {
    id("lib-multisrc")
}

baseVersionCode = 19

dependencies {
    implementation(project(":lib:dood-extractor"))
    implementation(project(":lib:cryptoaes"))
    implementation(project(":lib:playlist-utils"))
}