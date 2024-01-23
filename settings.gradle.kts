apply(from = "repositories.gradle.kts")

pluginManagement {
    includeBuild("build-plugins")
}

include(":core")

// Load all modules under /lib
File(rootDir, "lib").eachDir { include("lib:${it.name}") }

// Fix deprecation warnings with Gradle 8.5+.
// See https://docs.gradle.org/8.5/userguide/upgrading_version_8.html#deprecated_missing_project_directory
listOf(
    ":extensions" to "$rootDir/gradle", // Temporary workaround.
    ":extensions:individual" to "$rootDir/src",
    ":extensions:multisrc" to "$rootDir/generated-src",
).forEach { (name, path) ->
    val projectDir = file(path)
    if (projectDir.exists()) {
        include(name)
        project(name).projectDir = projectDir
    }
}

if (System.getenv("CI") == null || System.getenv("CI_MODULE_GEN") == "true") {
    // Local development (full project build)

    include(":multisrc")
    project(":multisrc").projectDir = File("multisrc")

    /**
     * Add or remove modules to load as needed for local development here.
     * To generate multisrc extensions first, run the `:multisrc:generateExtensions` task first.
     */
    loadAllIndividualExtensions()
    loadAllGeneratedMultisrcExtensions()
    // loadIndividualExtension("all", "jellyfin")
    // loadGeneratedMultisrcExtension("en", "aniwatch")
} else {
    // Running in CI (GitHub Actions)

    val isMultisrc = System.getenv("CI_MULTISRC") == "true"
    val chunkSize = System.getenv("CI_CHUNK_SIZE").toInt()
    val chunk = System.getenv("CI_CHUNK_NUM").toInt()

    if (isMultisrc) {
        include(":multisrc")
        project(":multisrc").projectDir = File("multisrc")

        // Loads generated extensions from multisrc
        File(rootDir, "generated-src").getChunk(chunk, chunkSize)?.forEach {
            loadGeneratedMultisrcExtension(it.parentFile.name, it.name, log = true)
        }
    } else {
        // Loads individual extensions
        File(rootDir, "src").getChunk(chunk, chunkSize)?.forEach {
            loadIndividualExtension(it.parentFile.name, it.name, log = true)
        }
    }
}

fun loadAllIndividualExtensions() {
    File(rootDir, "src").eachDir { lang ->
        lang.eachDir { extension ->
            loadIndividualExtension(lang.name, extension.name)
        }
    }
}
fun loadAllGeneratedMultisrcExtensions() {
    File(rootDir, "generated-src").eachDir { lang ->
        lang.eachDir { extension ->
            loadGeneratedMultisrcExtension(lang.name, extension.name)
        }
    }
}

fun loadIndividualExtension(lang: String, name: String, log: Boolean = false) {
    val projectName = ":extensions:individual:$lang:$name"
    if (log) println(projectName)
    include(projectName)
    project(projectName).projectDir = File("src/$lang/$name")
}

fun loadGeneratedMultisrcExtension(lang: String, name: String, log: Boolean = false) {
    val projectName = ":extensions:multisrc:$lang:$name"
    if (log) println(projectName)
    include(projectName)
    project(projectName).projectDir = File("generated-src/$lang/$name")
}

fun File.getChunk(chunk: Int, chunkSize: Int): List<File>? {
    return listFiles()
        // Lang folder
        ?.filter { it.isDirectory }
        // Extension subfolders
        ?.mapNotNull { dir -> dir.listFiles()?.filter { it.isDirectory } }
        ?.flatten()
        ?.sortedBy { it.name }
        ?.chunked(chunkSize)
        ?.get(chunk)
}

fun File.eachDir(block: (File) -> Unit) {
    listFiles()?.filter { it.isDirectory }?.forEach { block(it) }
}
