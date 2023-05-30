package eu.kanade.tachiyomi.multisrc.animestream

import generator.ThemeSourceData.SingleLang
import generator.ThemeSourceGenerator

class AnimeStreamGenerator : ThemeSourceGenerator {
    override val themePkg = "animestream"

    override val themeClass = "AnimeStream"

    override val baseVersionCode = 1

    override val sources = emptyList<SingleLang>()

    companion object {
        @JvmStatic
        fun main(args: Array<String>) = AnimeStreamGenerator().createAll()
    }
}
