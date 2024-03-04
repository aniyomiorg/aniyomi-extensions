plugins {
    id("lib-multisrc")
}

baseVersionCode = 1

dependencies {
    api(project(":lib:megacloud-extractor"))
    api(project(":lib:streamtape-extractor"))
}
