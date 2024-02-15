plugins {
    id("lib-multisrc")
}

baseVersionCode = 19

dependencies {
    api(project(":lib:dood-extractor"))
    api(project(":lib:cryptoaes"))
    api(project(":lib:playlist-utils"))
}
