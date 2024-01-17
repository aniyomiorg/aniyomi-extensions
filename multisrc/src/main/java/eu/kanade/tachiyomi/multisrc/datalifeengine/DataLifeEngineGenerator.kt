package eu.kanade.tachiyomi.multisrc.datalifeengine

import generator.ThemeSourceData.SingleLang
import generator.ThemeSourceGenerator

class DataLifeEngineGenerator : ThemeSourceGenerator {
    override val themePkg = "datalifeengine"

    override val themeClass = "DataLifeEngine"

    override val baseVersionCode = 1

    override val sources = listOf(
        SingleLang("Wiflix", "https://wiflix.voto", "fr", overrideVersionCode = 3),
        SingleLang("French Anime", "https://french-anime.com", "fr", overrideVersionCode = 5),
    )

    companion object {
        @JvmStatic
        fun main(args: Array<String>) = DataLifeEngineGenerator().createAll()
    }
}
