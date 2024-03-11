plugins {
    id("lib-multisrc")
}

baseVersionCode = 2

dependencies {
    api(project(":lib:megacloud-extractor"))
    api(project(":lib:streamtape-extractor"))
}
