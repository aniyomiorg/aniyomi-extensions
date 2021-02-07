package eu.kanade.tachiyomi.multisrc.genkan

import eu.kanade.tachiyomi.multisrc.ThemeSourceData
import eu.kanade.tachiyomi.multisrc.ThemeSourceGenerator

class GenkanOriginalGenerator : ThemeSourceGenerator {

    override val themePkg = "genkan"

    override val themeClass = "GenkanOriginal"

    override val baseVersionCode: Int = 1

    override val sources = listOf(
        ThemeSourceData.SingleLang("Reaper Scans", "https://reaperscans.com", "en"),
        ThemeSourceData.SingleLang("Hatigarm Scans", "https://hatigarmscanz.net", "en", versionId = 2),
        ThemeSourceData.SingleLang("SecretScans", "https://secretscans.co", "en"),
    )

    companion object {
        @JvmStatic
        fun main(args: Array<String>) {
            GenkanOriginalGenerator().createAll()
        }
    }
}
