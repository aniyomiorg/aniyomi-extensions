plugins {
    id("lib-multisrc")
}

baseVersionCode = 20

dependencies {
    api(project(":lib:dood-extractor"))
    api(project(":lib:cryptoaes"))
    api(project(":lib:playlist-utils"))
}
